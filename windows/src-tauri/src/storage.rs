//! SQLite 存储层：op_log 只追加、HLC 推进、本地偏好与首次种子数据。
//!
//! 业务状态不落业务表作为真相；启动时读取 op_log，由 core_domain 的 reducer 重建投影。

use crate::core_domain::{Hlc, Op, Settings};
use anyhow::{Context, Result};
use chrono::{Duration, Local, TimeZone};
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use uuid::Uuid;

/// 数据目录名：位于可执行文件同级目录下。
pub const DATA_DIR_NAME: &str = "data";
/// 数据库文件名。
pub const DB_FILE: &str = "todo.sqlite3";

pub fn now_ms() -> i64 {
    Local::now().timestamp_millis()
}

/// 解析数据目录：默认使用「软件同目录/data」。
///
/// 优先级：
/// 1. `TODO_DATA_DIR` 环境变量（开发脚本用它把数据固定到 `windows/data`）；
/// 2. 可执行文件同级目录下的 `data`；
/// 3. `fallback`（通常是系统应用数据目录），用于安装目录不可写的情况。
pub fn resolve_data_dir(fallback: Option<&Path>) -> PathBuf {
    if let Some(dir) = env_data_dir() {
        return dir;
    }
    if let Some(dir) = exe_sibling_data_dir() {
        return dir;
    }
    fallback
        .map(Path::to_path_buf)
        .unwrap_or_else(|| PathBuf::from(DATA_DIR_NAME))
}

fn env_data_dir() -> Option<PathBuf> {
    let raw = std::env::var_os("TODO_DATA_DIR")?;
    if raw.is_empty() {
        return None;
    }
    let dir = PathBuf::from(raw);
    std::fs::create_dir_all(&dir).ok()?;
    Some(dir)
}

fn exe_sibling_data_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let candidate = exe.parent()?.join(DATA_DIR_NAME);
    if std::fs::create_dir_all(&candidate).is_err() {
        return None;
    }
    // 目录存在不代表可写，做一次真实写入探测再决定。
    let probe = candidate.join(".write-probe");
    match std::fs::write(&probe, b"ok") {
        Ok(()) => {
            let _ = std::fs::remove_file(&probe);
            Some(candidate)
        }
        Err(_) => None,
    }
}

/// 首次切换到 exe 同级目录时，把旧版本 `app_data_dir` 里的数据库搬迁过来，
/// 避免升级后看起来像「数据丢了」。已有新库时不覆盖。
pub fn migrate_legacy_database(data_dir: &Path, legacy_dir: Option<&Path>) -> Result<()> {
    let Some(legacy_dir) = legacy_dir else {
        return Ok(());
    };
    if same_path(data_dir, legacy_dir) {
        return Ok(());
    }

    let target = data_dir.join(DB_FILE);
    if target.exists() {
        return Ok(());
    }
    let source = legacy_dir.join(DB_FILE);
    if !source.exists() {
        return Ok(());
    }

    std::fs::create_dir_all(data_dir)
        .with_context(|| format!("创建数据目录失败: {}", data_dir.display()))?;

    // WAL 模式下 -wal / -shm 必须和主库一起搬，否则最近的提交会丢失。
    for suffix in ["", "-wal", "-shm"] {
        let from = PathBuf::from(format!("{}{}", source.display(), suffix));
        if !from.exists() {
            continue;
        }
        let to = PathBuf::from(format!("{}{}", target.display(), suffix));
        std::fs::copy(&from, &to)
            .with_context(|| format!("迁移旧数据失败: {} -> {}", from.display(), to.display()))?;
    }
    Ok(())
}

fn same_path(a: &Path, b: &Path) -> bool {
    let normalize = |path: &Path| {
        path.canonicalize()
            .unwrap_or_else(|_| path.to_path_buf())
            .to_string_lossy()
            .replace('/', "\\")
            .to_lowercase()
    };
    normalize(a) == normalize(b)
}

pub fn open(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("创建数据目录失败: {}", parent.display()))?;
    }
    let conn = Connection::open(path)
        .with_context(|| format!("打开 SQLite 失败: {}", path.display()))?;
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    migrate(&conn)?;
    Ok(conn)
}

fn migrate(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS op_log (
            op_id         TEXT PRIMARY KEY,
            workspace_id  TEXT NOT NULL,
            entity_type   TEXT NOT NULL,
            entity_id     TEXT NOT NULL,
            actor_id      TEXT NOT NULL,
            device_id     TEXT NOT NULL,
            operation     TEXT NOT NULL,
            field         TEXT NOT NULL,
            value_json    TEXT NOT NULL,
            physical_ms   INTEGER NOT NULL,
            counter       INTEGER NOT NULL,
            node_id       TEXT NOT NULL,
            source        TEXT NOT NULL,
            model         TEXT,
            created_at    INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_op_workspace ON op_log(workspace_id);
        CREATE INDEX IF NOT EXISTS idx_op_entity ON op_log(entity_type, entity_id);

        CREATE TABLE IF NOT EXISTS local_hlc (
            id          INTEGER PRIMARY KEY CHECK (id = 1),
            node_id     TEXT NOT NULL,
            physical_ms INTEGER NOT NULL,
            counter     INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS preferences (
            key        TEXT PRIMARY KEY,
            value_json TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS meta (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        "#,
    )?;

    let exists: Option<i64> = conn
        .query_row("SELECT id FROM local_hlc WHERE id = 1", [], |row| row.get(0))
        .optional()?;
    if exists.is_none() {
        conn.execute(
            "INSERT INTO local_hlc (id, node_id, physical_ms, counter) VALUES (1, ?1, ?2, 0)",
            params![format!("win-{}", &Uuid::new_v4().to_string()[..8]), now_ms()],
        )?;
    }
    Ok(())
}

pub fn load_ops(conn: &Connection) -> Result<Vec<Op>> {
    let mut stmt = conn.prepare(
        "SELECT op_id, workspace_id, entity_type, entity_id, actor_id, device_id,
                operation, field, value_json, physical_ms, counter, node_id, source, model
         FROM op_log",
    )?;
    let rows = stmt.query_map([], |row| {
        let value_json: String = row.get(8)?;
        let value: Value = serde_json::from_str(&value_json).unwrap_or(Value::Null);
        Ok(Op {
            op_id: row.get(0)?,
            workspace_id: row.get(1)?,
            entity_type: row.get(2)?,
            entity_id: row.get(3)?,
            actor_id: row.get(4)?,
            device_id: row.get(5)?,
            operation: row.get(6)?,
            field: row.get(7)?,
            value,
            physical_ms: row.get(9)?,
            counter: row.get(10)?,
            node_id: row.get(11)?,
            source: row.get(12)?,
            model: row.get(13)?,
        })
    })?;

    let mut ops = Vec::new();
    for row in rows {
        ops.push(row?);
    }
    Ok(ops)
}

pub fn insert_op(conn: &Connection, op: &Op) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO op_log
         (op_id, workspace_id, entity_type, entity_id, actor_id, device_id,
          operation, field, value_json, physical_ms, counter, node_id, source, model, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
        params![
            op.op_id,
            op.workspace_id,
            op.entity_type,
            op.entity_id,
            op.actor_id,
            op.device_id,
            op.operation,
            op.field,
            serde_json::to_string(&op.value)?,
            op.physical_ms,
            op.counter,
            op.node_id,
            op.source,
            op.model,
            op.physical_ms,
        ],
    )?;
    Ok(())
}

pub fn next_hlc(conn: &Connection) -> Result<Hlc> {
    let (node_id, physical_ms, counter): (String, i64, u64) = conn.query_row(
        "SELECT node_id, physical_ms, counter FROM local_hlc WHERE id = 1",
        [],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    )?;

    let now = now_ms();
    let (next_physical, next_counter) = if now > physical_ms {
        (now, 0)
    } else {
        (physical_ms, counter + 1)
    };

    conn.execute(
        "UPDATE local_hlc SET physical_ms = ?1, counter = ?2 WHERE id = 1",
        params![next_physical, next_counter],
    )?;

    Ok(Hlc {
        physical_ms: next_physical,
        counter: next_counter,
        node_id,
    })
}

pub fn pref_get(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row(
        "SELECT value_json FROM preferences WHERE key = ?1",
        params![key],
        |row| row.get(0),
    )
    .optional()
    .ok()
    .flatten()
}

pub fn pref_set(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO preferences (key, value_json) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json",
        params![key, value],
    )?;
    Ok(())
}

pub fn device_id(conn: &Connection) -> Result<String> {
    if let Some(id) = pref_get(conn, "device_id") {
        return Ok(id);
    }
    let id = format!("win-{}", &Uuid::new_v4().to_string()[..12]);
    pref_set(conn, "device_id", &id)?;
    Ok(id)
}

pub fn current_user_id(conn: &Connection) -> String {
    pref_get(conn, "current_user_id").unwrap_or_else(|| "u-me".to_string())
}

pub fn active_workspace_id(conn: &Connection) -> Option<String> {
    pref_get(conn, "active_workspace_id")
}

pub fn load_settings(conn: &Connection) -> Settings {
    pref_get(conn, "settings")
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

pub fn save_settings(conn: &Connection, settings: &Settings) -> Result<()> {
    pref_set(conn, "settings", &serde_json::to_string(settings)?)
}

fn today_at(hour: u32, minute: u32, offset_days: i64) -> String {
    let base = Local::now() + Duration::days(offset_days);
    base.date_naive()
        .and_hms_opt(hour, minute, 0)
        .and_then(|naive| Local.from_local_datetime(&naive).single())
        .map(|dt| dt.to_rfc3339())
        .unwrap_or_else(|| Local::now().to_rfc3339())
}

struct SeedOp<'a> {
    workspace: &'a str,
    entity_type: &'a str,
    entity_id: &'a str,
    operation: &'a str,
    field: &'a str,
    value: Value,
    source: &'a str,
    model: Option<&'a str>,
}

fn seed_write(conn: &Connection, device: &str, actor: &str, op: SeedOp<'_>) -> Result<()> {
    let hlc = next_hlc(conn)?;
    insert_op(
        conn,
        &Op {
            op_id: Uuid::new_v4().to_string(),
            workspace_id: op.workspace.to_string(),
            entity_type: op.entity_type.to_string(),
            entity_id: op.entity_id.to_string(),
            actor_id: actor.to_string(),
            device_id: device.to_string(),
            operation: op.operation.to_string(),
            field: op.field.to_string(),
            value: op.value,
            physical_ms: hlc.physical_ms,
            counter: hlc.counter,
            node_id: hlc.node_id,
            source: op.source.to_string(),
            model: op.model.map(|m| m.to_string()),
        },
    )
}

fn seed_set(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    entity_type: &str,
    entity_id: &str,
    field: &str,
    value: Value,
    source: &str,
    model: Option<&str>,
) -> Result<()> {
    seed_write(
        conn,
        device,
        actor,
        SeedOp {
            workspace,
            entity_type,
            entity_id,
            operation: "set",
            field,
            value,
            source,
            model,
        },
    )
}

fn seed_collection(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    entity_type: &str,
    entity_id: &str,
    operation: &str,
    field: &str,
    value: &str,
) -> Result<()> {
    seed_write(
        conn,
        device,
        actor,
        SeedOp {
            workspace,
            entity_type,
            entity_id,
            operation,
            field,
            value: json!(value),
            source: "human",
            model: None,
        },
    )
}

/// 首次启动写入演示 op。仅当 op_log 为空时执行。
pub fn seed_if_empty(conn: &Connection) -> Result<()> {
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM op_log", [], |row| row.get(0))?;
    if count > 0 {
        return Ok(());
    }

    let device = device_id(conn)?;
    let me = "u-me";

    seed_workspace(conn, &device, me, "ws-me", "我的空间")?;
    seed_workspace(conn, &device, me, "ws-team", "产品团队")?;

    seed_user(conn, &device, me, "ws-me", "u-me", "我", "我", "#3B82F6", "me@todo.local")?;
    seed_user(conn, &device, me, "ws-me", "u-ming", "小明", "明", "#10B981", "ming@todo.local")?;
    seed_user(conn, &device, me, "ws-me", "u-hong", "小红", "红", "#8B5CF6", "hong@todo.local")?;
    seed_user(conn, &device, me, "ws-team", "u-me", "我", "我", "#3B82F6", "me@todo.local")?;
    seed_user(conn, &device, me, "ws-team", "u-lin", "林可", "林", "#F59E0B", "lin@todo.local")?;
    seed_user(conn, &device, me, "ws-team", "u-chen", "陈晨", "陈", "#EC4899", "chen@todo.local")?;

    seed_membership(conn, &device, me, "ws-me", "u-me", "owner")?;
    seed_membership(conn, &device, me, "ws-me", "u-ming", "editor")?;
    seed_membership(conn, &device, me, "ws-me", "u-hong", "viewer")?;
    seed_membership(conn, &device, me, "ws-team", "u-me", "owner")?;
    seed_membership(conn, &device, me, "ws-team", "u-lin", "editor")?;
    seed_membership(conn, &device, me, "ws-team", "u-chen", "viewer")?;

    // 我的空间：清单与标签
    seed_list(conn, &device, me, "ws-me", "me-today", "今天", "#3B82F6", 0.0)?;
    seed_list(conn, &device, me, "ws-me", "me-work", "工作", "#F59E0B", 1.0)?;
    seed_list(conn, &device, me, "ws-me", "me-life", "生活", "#10B981", 2.0)?;
    seed_list(conn, &device, me, "ws-me", "me-study", "学习", "#8B5CF6", 3.0)?;
    seed_tag(conn, &device, me, "ws-me", "me-tag-work", "工作", "#EFF6FF", "#2563EB", false)?;
    seed_tag(conn, &device, me, "ws-me", "me-tag-life", "生活", "#ECFDF5", "#059669", false)?;
    seed_tag(conn, &device, me, "ws-me", "me-tag-study", "学习", "#F5F3FF", "#7C3AED", false)?;
    seed_tag(conn, &device, me, "ws-me", "me-tag-important", "重要", "#FEF2F2", "#DC2626", true)?;
    seed_tag(conn, &device, me, "ws-me", "me-tag-week", "本周", "#FEF3C7", "#B45309", true)?;

    // 产品团队：清单与标签
    seed_list(conn, &device, me, "ws-team", "team-today", "今天", "#3B82F6", 0.0)?;
    seed_list(conn, &device, me, "ws-team", "team-work", "迭代", "#F59E0B", 1.0)?;
    seed_list(conn, &device, me, "ws-team", "team-research", "调研", "#8B5CF6", 2.0)?;
    seed_tag(conn, &device, me, "ws-team", "team-tag-api", "接口", "#EFF6FF", "#2563EB", false)?;
    seed_tag(conn, &device, me, "ws-team", "team-tag-risk", "风险", "#FEF2F2", "#DC2626", true)?;

    seed_tasks_my_space(conn, &device, me)?;
    seed_tasks_team(conn, &device, me)?;

    pref_set(conn, "active_workspace_id", "ws-me")?;
    pref_set(conn, "current_user_id", me)?;
    Ok(())
}

fn seed_workspace(conn: &Connection, device: &str, actor: &str, id: &str, name: &str) -> Result<()> {
    seed_set(conn, device, actor, id, "workspace", id, "__created", json!(today_at(9, 0, -30)), "human", None)?;
    seed_set(conn, device, actor, id, "workspace", id, "name", json!(name), "human", None)?;
    Ok(())
}

fn seed_user(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    id: &str,
    name: &str,
    initial: &str,
    color: &str,
    email: &str,
) -> Result<()> {
    seed_set(conn, device, actor, workspace, "user", id, "name", json!(name), "system", None)?;
    seed_set(conn, device, actor, workspace, "user", id, "initial", json!(initial), "system", None)?;
    seed_set(conn, device, actor, workspace, "user", id, "color", json!(color), "system", None)?;
    seed_set(conn, device, actor, workspace, "user", id, "email", json!(email), "system", None)?;
    Ok(())
}

fn seed_membership(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    user: &str,
    role: &str,
) -> Result<()> {
    let entity_id = format!("{workspace}:{user}");
    seed_set(conn, device, actor, workspace, "membership", &entity_id, "__role", json!(role), "human", None)?;
    Ok(())
}

fn seed_list(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    id: &str,
    name: &str,
    color: &str,
    order: f64,
) -> Result<()> {
    seed_set(conn, device, actor, workspace, "list", id, "__created", json!(today_at(9, 0, -30)), "human", None)?;
    seed_set(conn, device, actor, workspace, "list", id, "workspace_id", json!(workspace), "human", None)?;
    seed_set(conn, device, actor, workspace, "list", id, "name", json!(name), "human", None)?;
    seed_set(conn, device, actor, workspace, "list", id, "color", json!(color), "human", None)?;
    seed_set(conn, device, actor, workspace, "list", id, "order", json!(order), "human", None)?;
    Ok(())
}

fn seed_tag(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    id: &str,
    name: &str,
    bg: &str,
    fg: &str,
    sidebar: bool,
) -> Result<()> {
    seed_set(conn, device, actor, workspace, "tag", id, "__created", json!(today_at(9, 0, -30)), "human", None)?;
    seed_set(conn, device, actor, workspace, "tag", id, "workspace_id", json!(workspace), "human", None)?;
    seed_set(conn, device, actor, workspace, "tag", id, "name", json!(name), "human", None)?;
    seed_set(conn, device, actor, workspace, "tag", id, "bg", json!(bg), "human", None)?;
    seed_set(conn, device, actor, workspace, "tag", id, "fg", json!(fg), "human", None)?;
    seed_set(conn, device, actor, workspace, "tag", id, "show_in_sidebar", json!(sidebar), "human", None)?;
    Ok(())
}

struct SeedTask<'a> {
    id: &'a str,
    list: &'a str,
    title: &'a str,
    notes: &'a str,
    due: Option<String>,
    remind: Option<String>,
    priority: &'a str,
    status: &'a str,
    tags: &'a [&'a str],
    subtasks: &'a [(&'a str, &'a str, bool)],
    order: f64,
    source: &'a str,
    model: Option<&'a str>,
}

fn seed_task(conn: &Connection, device: &str, actor: &str, workspace: &str, task: SeedTask<'_>) -> Result<()> {
    seed_set(
        conn, device, actor, workspace, "task", task.id, "__created",
        json!(today_at(9, 12, -2)), task.source, task.model,
    )?;
    seed_set(conn, device, actor, workspace, "task", task.id, "title", json!(task.title), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "notes", json!(task.notes), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "due_at", json!(task.due), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "remind_at", json!(task.remind), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "priority", json!(task.priority), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "status", json!(task.status), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "list_id", json!(task.list), task.source, task.model)?;
    seed_set(conn, device, actor, workspace, "task", task.id, "order", json!(task.order), task.source, task.model)?;

    for tag in task.tags {
        seed_collection(conn, device, actor, workspace, "task", task.id, "add", "__tag", tag)?;
    }
    for (index, (sub_id, text, done)) in task.subtasks.iter().enumerate() {
        seed_collection(conn, device, actor, workspace, "task", task.id, "add", "__subtask", sub_id)?;
        seed_set(conn, device, actor, workspace, "task", task.id, &format!("__subtask:{sub_id}:text"), json!(text), "human", None)?;
        seed_set(conn, device, actor, workspace, "task", task.id, &format!("__subtask:{sub_id}:done"), json!(done), "human", None)?;
        seed_set(conn, device, actor, workspace, "task", task.id, &format!("__subtask:{sub_id}:order"), json!(index as f64), "human", None)?;
    }
    Ok(())
}

fn seed_tasks_my_space(conn: &Connection, device: &str, actor: &str) -> Result<()> {
    let ws = "ws-me";
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-write-prd",
            list: "me-work",
            title: "写产品需求文档",
            notes: "对齐 MVP 范围、权限矩阵与同步流程，输出给设计与研发评审。",
            due: Some(today_at(18, 0, 0)),
            remind: Some(today_at(17, 30, 0)),
            priority: "high",
            status: "todo",
            tags: &["me-tag-work", "me-tag-important"],
            subtasks: &[
                ("me-sub-1", "收集竞品数据", true),
                ("me-sub-2", "整理用户反馈", true),
                ("me-sub-3", "写第一版初稿", false),
                ("me-sub-4", "和设计对齐交互", false),
            ],
            order: 0.0,
            source: "ai",
            model: Some("gpt-4o"),
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-reply-mail",
            list: "me-work",
            title: "回复客户邮件",
            notes: "确认下周交付节点，说明离线合并的边界。",
            due: Some(today_at(20, 0, 0)),
            remind: None,
            priority: "mid",
            status: "todo",
            tags: &["me-tag-work"],
            subtasks: &[],
            order: 1.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-dentist",
            list: "me-life",
            title: "预约牙医",
            notes: "",
            due: Some(today_at(16, 0, 0)),
            remind: Some(today_at(15, 30, 0)),
            priority: "low",
            status: "todo",
            tags: &["me-tag-life"],
            subtasks: &[],
            order: 2.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-weekly-meeting",
            list: "me-work",
            title: "准备周会材料",
            notes: "同步本周进度、风险与下周计划。",
            due: Some(today_at(10, 0, 1)),
            remind: Some(today_at(9, 30, 1)),
            priority: "mid",
            status: "todo",
            tags: &["me-tag-work", "me-tag-week"],
            subtasks: &[("me-sub-5", "整理数据看板", false), ("me-sub-6", "列出风险项", false)],
            order: 3.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-call-mom",
            list: "me-life",
            title: "给妈妈打电话",
            notes: "",
            due: Some(today_at(19, 0, 2)),
            remind: None,
            priority: "low",
            status: "todo",
            tags: &["me-tag-life"],
            subtasks: &[],
            order: 4.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-coffee",
            list: "me-life",
            title: "买咖啡豆",
            notes: "",
            due: Some(today_at(9, 0, 0)),
            remind: None,
            priority: "low",
            status: "done",
            tags: &["me-tag-life"],
            subtasks: &[],
            order: 5.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-notes",
            list: "me-work",
            title: "整理会议笔记",
            notes: "把决策项同步到项目空间。",
            due: Some(today_at(10, 0, -1)),
            remind: None,
            priority: "mid",
            status: "done",
            tags: &["me-tag-work"],
            subtasks: &[],
            order: 6.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "me-task-reading",
            list: "me-study",
            title: "看完《设计心理学》第一章",
            notes: "记三条对当前界面有帮助的原则。",
            due: Some(today_at(8, 0, -3)),
            remind: None,
            priority: "low",
            status: "done",
            tags: &["me-tag-study", "me-tag-week"],
            subtasks: &[],
            order: 7.0,
            source: "human",
            model: None,
        },
    )?;
    Ok(())
}

fn seed_tasks_team(conn: &Connection, device: &str, actor: &str) -> Result<()> {
    let ws = "ws-team";
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "team-task-sync-api",
            list: "team-work",
            title: "对齐同步接口字段",
            notes: "push/pull 的 op 信封与错误码需要和客户端一致。",
            due: Some(today_at(17, 0, 0)),
            remind: Some(today_at(16, 30, 0)),
            priority: "high",
            status: "todo",
            tags: &["team-tag-api"],
            subtasks: &[("team-sub-1", "整理字段清单", true), ("team-sub-2", "补错误码示例", false)],
            order: 0.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "team-task-review",
            list: "team-work",
            title: "评审离线冲突测试向量",
            notes: "覆盖 LWW、OR-Set、子任务与权限撤回。",
            due: Some(today_at(11, 0, 1)),
            remind: None,
            priority: "mid",
            status: "todo",
            tags: &["team-tag-risk"],
            subtasks: &[],
            order: 1.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "team-task-research",
            list: "team-research",
            title: "调研 Windows 通知兼容性",
            notes: "确认 tauri-plugin-notifications 在 Win10/11 的表现。",
            due: Some(today_at(15, 0, 2)),
            remind: None,
            priority: "low",
            status: "todo",
            tags: &[],
            subtasks: &[],
            order: 2.0,
            source: "human",
            model: None,
        },
    )?;
    seed_task(
        conn, device, actor, ws,
        SeedTask {
            id: "team-task-archive",
            list: "team-work",
            title: "归档上一迭代文档",
            notes: "",
            due: Some(today_at(10, 0, -1)),
            remind: None,
            priority: "low",
            status: "done",
            tags: &[],
            subtasks: &[],
            order: 3.0,
            source: "human",
            model: None,
        },
    )?;
    Ok(())
}
