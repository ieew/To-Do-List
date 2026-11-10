//! SQLite 存储层：op_log 只追加、HLC 推进、本地偏好与首次种子数据。
//!
//! 业务状态不落业务表作为真相；启动时读取 op_log，由 core_domain 的 reducer 重建投影。

use crate::core_domain::{Hlc, Op, Projection, Settings};
use anyhow::{Context, Result};
use chrono::Local;
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
    let conn =
        Connection::open(path).with_context(|| format!("打开 SQLite 失败: {}", path.display()))?;
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

    // 同步状态列：旧库通过 ALTER 补齐，新库由上面的 CREATE 后同样走这里（幂等）。
    add_column_if_missing(
        conn,
        "op_log",
        "sync_state",
        "TEXT NOT NULL DEFAULT 'pending'",
    )?;
    add_column_if_missing(conn, "op_log", "server_seq", "INTEGER")?;
    add_column_if_missing(conn, "op_log", "sync_error", "TEXT")?;
    conn.execute_batch("CREATE INDEX IF NOT EXISTS idx_op_sync_state ON op_log(sync_state);")?;

    let exists: Option<i64> = conn
        .query_row("SELECT id FROM local_hlc WHERE id = 1", [], |row| {
            row.get(0)
        })
        .optional()?;
    if exists.is_none() {
        conn.execute(
            "INSERT INTO local_hlc (id, node_id, physical_ms, counter) VALUES (1, ?1, ?2, 0)",
            params![
                format!("win-{}", &Uuid::new_v4().to_string()[..8]),
                now_ms()
            ],
        )?;
    }
    Ok(())
}

fn add_column_if_missing(
    conn: &Connection,
    table: &str,
    column: &str,
    definition: &str,
) -> Result<()> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info({table})"))?;
    let mut rows = stmt.query([])?;
    let mut exists = false;
    while let Some(row) = rows.next()? {
        let name: String = row.get(1)?;
        if name == column {
            exists = true;
            break;
        }
    }
    if !exists {
        conn.execute_batch(&format!(
            "ALTER TABLE {table} ADD COLUMN {column} {definition}"
        ))?;
    }
    Ok(())
}

/// 投影用：不包含同步被拒的 op（服务端永远不会让它生效）。
pub fn load_ops(conn: &Connection) -> Result<Vec<Op>> {
    load_ops_where(conn, "WHERE sync_state <> 'rejected'")
}

/// 导出/排障用：包含被拒 op。
pub fn load_all_ops(conn: &Connection) -> Result<Vec<Op>> {
    load_ops_where(conn, "")
}

fn load_ops_where(conn: &Connection, filter: &str) -> Result<Vec<Op>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT op_id, workspace_id, entity_type, entity_id, actor_id, device_id,
                operation, field, value_json, physical_ms, counter, node_id, source, model
         FROM op_log {filter}"
    ))?;
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

/// 拉取到的远端 op 及其服务端全局序号。
pub struct PulledOp {
    pub op: Op,
    pub seq: i64,
    pub received_at: Option<i64>,
}

/// 本地新建的 op 默认处于 pending，由同步层负责推送后改状态。
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

/// 拉取落库：已存在（例如自己刚推送的同 op）时只补同步状态；否则以 synced 状态插入。
/// 调用方负责包事务。
pub fn insert_pulled_ops(conn: &Connection, items: &[PulledOp]) -> Result<usize> {
    let mut inserted = 0usize;
    for item in items {
        let created_at = item.received_at.unwrap_or(item.op.physical_ms);
        let changed = conn.execute(
            "INSERT OR IGNORE INTO op_log
             (op_id, workspace_id, entity_type, entity_id, actor_id, device_id,
              operation, field, value_json, physical_ms, counter, node_id, source, model,
              created_at, sync_state, server_seq)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15,
                     'synced', ?16)",
            params![
                item.op.op_id,
                item.op.workspace_id,
                item.op.entity_type,
                item.op.entity_id,
                item.op.actor_id,
                item.op.device_id,
                item.op.operation,
                item.op.field,
                serde_json::to_string(&item.op.value)?,
                item.op.physical_ms,
                item.op.counter,
                item.op.node_id,
                item.op.source,
                item.op.model,
                created_at,
                item.seq,
            ],
        )?;
        inserted += changed;
        conn.execute(
            "UPDATE op_log
                SET sync_state = 'synced', server_seq = COALESCE(server_seq, ?2)
              WHERE op_id = ?1 AND sync_state IN ('pending', 'synced')",
            params![item.op.op_id, item.seq],
        )?;
    }
    Ok(inserted)
}

/// 待推送队列：仅本地生成且尚未被服务端确认的 op。
pub fn pending_ops(conn: &Connection, limit: usize) -> Result<Vec<Op>> {
    let mut stmt = conn.prepare(
        "SELECT op_id, workspace_id, entity_type, entity_id, actor_id, device_id,
                operation, field, value_json, physical_ms, counter, node_id, source, model
         FROM op_log
         WHERE sync_state = 'pending'
         ORDER BY created_at, rowid
         LIMIT ?1",
    )?;
    let rows = stmt.query_map(params![limit as i64], |row| {
        let value_json: String = row.get(8)?;
        Ok(Op {
            op_id: row.get(0)?,
            workspace_id: row.get(1)?,
            entity_type: row.get(2)?,
            entity_id: row.get(3)?,
            actor_id: row.get(4)?,
            device_id: row.get(5)?,
            operation: row.get(6)?,
            field: row.get(7)?,
            value: serde_json::from_str(&value_json).unwrap_or(Value::Null),
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

pub fn pending_count(conn: &Connection) -> Result<i64> {
    Ok(conn.query_row(
        "SELECT COUNT(*) FROM op_log WHERE sync_state = 'pending'",
        [],
        |row| row.get(0),
    )?)
}

pub fn rejected_count(conn: &Connection) -> Result<i64> {
    Ok(conn.query_row(
        "SELECT COUNT(*) FROM op_log WHERE sync_state = 'rejected'",
        [],
        |row| row.get(0),
    )?)
}

pub fn mark_ops_synced(conn: &Connection, items: &[(String, i64)]) -> Result<()> {
    for (op_id, seq) in items {
        conn.execute(
            "UPDATE op_log SET sync_state = 'synced', server_seq = ?2, sync_error = NULL
              WHERE op_id = ?1",
            params![op_id, seq],
        )?;
    }
    Ok(())
}

pub fn mark_op_rejected(conn: &Connection, op_id: &str, error: &str) -> Result<()> {
    conn.execute(
        "UPDATE op_log SET sync_state = 'rejected', sync_error = ?2 WHERE op_id = ?1",
        params![op_id, error],
    )?;
    Ok(())
}

pub fn meta_get(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row(
        "SELECT value FROM meta WHERE key = ?1",
        params![key],
        |row| row.get(0),
    )
    .optional()
    .ok()
    .flatten()
}

pub fn meta_set(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

pub fn cursor(conn: &Connection) -> i64 {
    meta_get(conn, "sync_cursor")
        .and_then(|raw| raw.parse::<i64>().ok())
        .unwrap_or(0)
}

pub fn set_cursor(conn: &Connection, seq: i64) -> Result<()> {
    meta_set(conn, "sync_cursor", &seq.to_string())
}

/// 收到远端 HLC 后推进本地时钟，保证后续 op 的全序不会落在已知事件之前。
pub fn observe_hlc(conn: &Connection, physical_ms: i64, counter: u64) -> Result<()> {
    let (local_physical, local_counter): (i64, u64) = conn.query_row(
        "SELECT physical_ms, counter FROM local_hlc WHERE id = 1",
        [],
        |row| Ok((row.get(0)?, row.get(1)?)),
    )?;
    let (next_physical, next_counter) = if physical_ms > local_physical {
        (physical_ms, counter)
    } else if physical_ms == local_physical {
        (local_physical, local_counter.max(counter))
    } else {
        (local_physical, local_counter)
    };
    conn.execute(
        "UPDATE local_hlc SET physical_ms = ?1, counter = ?2 WHERE id = 1",
        params![next_physical, next_counter],
    )?;
    Ok(())
}

pub fn set_sync_account(conn: &Connection, account: Option<&str>) -> Result<()> {
    match account {
        Some(value) => meta_set(conn, "sync_account", value),
        None => {
            conn.execute("DELETE FROM meta WHERE key = 'sync_account'", [])?;
            Ok(())
        }
    }
}

pub fn sync_account(conn: &Connection) -> Option<String> {
    meta_get(conn, "sync_account")
}

/// 登录到新的云端账户前，把当前库整库备份，然后清空本地 op 与同步游标。
/// 返回备份文件路径；本地没有任何 op 时不备份。
pub fn reset_for_account(conn: &Connection, backup_dir: &Path) -> Result<Option<PathBuf>> {
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM op_log", [], |row| row.get(0))?;
    let mut backup_path = None;
    if count > 0 {
        std::fs::create_dir_all(backup_dir)
            .with_context(|| format!("创建备份目录失败: {}", backup_dir.display()))?;
        let stamp = Local::now().format("%Y%m%d-%H%M%S-%3f");
        let target = backup_dir.join(format!("todo-{stamp}.sqlite3"));
        let target_text = target.to_string_lossy().replace('\'', "''");
        conn.execute_batch(&format!("VACUUM INTO '{target_text}'"))
            .with_context(|| format!("备份本地库失败: {}", target.display()))?;
        backup_path = Some(target);
    }

    conn.execute("DELETE FROM op_log", [])?;
    conn.execute("DELETE FROM meta", [])?;
    conn.execute(
        "UPDATE local_hlc SET physical_ms = ?1, counter = 0 WHERE id = 1",
        params![now_ms()],
    )?;
    Ok(backup_path)
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

/// 同步引擎与测试用的投影入口：不需要 Tauri 的 AI 密钥状态。
pub fn build_projection(conn: &Connection, workspace_id: &str) -> Result<Projection> {
    let ops = load_ops(conn)?;
    let settings = load_settings(conn);
    Ok(Projection::build(
        ops,
        Some(workspace_id),
        &current_user_id(conn),
        settings,
        false,
    ))
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

/// 预设清单：key、名称、颜色、排序。默认工作区与新建工作区必须共用这一份定义。
const DEFAULT_LISTS: [(&str, &str, &str, f64); 4] = [
    ("today", "今天", "#3B82F6", 0.0),
    ("work", "工作", "#F59E0B", 1.0),
    ("life", "生活", "#10B981", 2.0),
    ("study", "学习", "#8B5CF6", 3.0),
];

/// 预设标签：key、名称、背景色、前景色、是否显示在侧栏。
const DEFAULT_TAGS: [(&str, &str, &str, &str, bool); 5] = [
    ("work", "工作", "#EFF6FF", "#2563EB", false),
    ("life", "生活", "#ECFDF5", "#059669", false),
    ("study", "学习", "#F5F3FF", "#7C3AED", false),
    ("important", "重要", "#FEF2F2", "#DC2626", true),
    ("week", "本周", "#FEF3C7", "#B45309", true),
];

/// 给单个工作区铺预设清单与标签。
///
/// `prefix` 决定实体 id：默认工作区传 `me`，得到 `me-today` / `me-tag-work`；
/// 新工作区传 `workspace_id`，得到 `<workspace_id>-today` / `<workspace_id>-tag-work`。
pub fn seed_workspace_presets(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    prefix: &str,
) -> Result<()> {
    for (key, name, color, order) in DEFAULT_LISTS {
        seed_list(
            conn,
            device,
            actor,
            workspace,
            &format!("{prefix}-{key}"),
            name,
            color,
            order,
        )?;
    }
    for (key, name, bg, fg, sidebar) in DEFAULT_TAGS {
        seed_tag(
            conn,
            device,
            actor,
            workspace,
            &format!("{prefix}-tag-{key}"),
            name,
            bg,
            fg,
            sidebar,
        )?;
    }
    Ok(())
}

/// 首次启动写入最小可用骨架：默认工作区、当前用户、所有者关系、预设清单与预设标签。
/// 不写入任何演示任务、协作成员或历史，保证用户首次打开就是干净的数据环境。
pub fn seed_if_empty(conn: &Connection) -> Result<()> {
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM op_log", [], |row| row.get(0))?;
    if count > 0 {
        return Ok(());
    }

    let device = device_id(conn)?;
    let me = "u-me";
    let workspace = "ws-me";

    seed_workspace(conn, &device, me, workspace, "我的空间")?;
    seed_user(
        conn,
        &device,
        me,
        workspace,
        me,
        "我",
        "我",
        "#3B82F6",
        "me@todo.local",
    )?;
    seed_membership(conn, &device, me, workspace, me, "owner")?;
    seed_workspace_presets(conn, &device, me, workspace, "me")?;

    pref_set(conn, "active_workspace_id", workspace)?;
    pref_set(conn, "current_user_id", me)?;
    Ok(())
}

/// 在单事务内执行一组写入；任一步失败整体回滚，避免留下半成品工作区。
pub fn with_transaction<T>(
    conn: &Connection,
    run: impl FnOnce(&Connection) -> Result<T>,
) -> Result<T> {
    let transaction = conn.unchecked_transaction()?;
    let output = run(&transaction)?;
    transaction.commit()?;
    Ok(output)
}

fn seed_workspace(
    conn: &Connection,
    device: &str,
    actor: &str,
    id: &str,
    name: &str,
) -> Result<()> {
    let created_at = Local::now().to_rfc3339();
    seed_set(
        conn,
        device,
        actor,
        id,
        "workspace",
        id,
        "__created",
        json!(created_at),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        id,
        "workspace",
        id,
        "name",
        json!(name),
        "human",
        None,
    )?;
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
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "user",
        id,
        "name",
        json!(name),
        "system",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "user",
        id,
        "initial",
        json!(initial),
        "system",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "user",
        id,
        "color",
        json!(color),
        "system",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "user",
        id,
        "email",
        json!(email),
        "system",
        None,
    )?;
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
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "membership",
        &entity_id,
        "__role",
        json!(role),
        "human",
        None,
    )?;
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
    let created_at = Local::now().to_rfc3339();
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "list",
        id,
        "__created",
        json!(created_at),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "list",
        id,
        "workspace_id",
        json!(workspace),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "list",
        id,
        "name",
        json!(name),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "list",
        id,
        "color",
        json!(color),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "list",
        id,
        "order",
        json!(order),
        "human",
        None,
    )?;
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
    let created_at = Local::now().to_rfc3339();
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "tag",
        id,
        "__created",
        json!(created_at),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "tag",
        id,
        "workspace_id",
        json!(workspace),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "tag",
        id,
        "name",
        json!(name),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "tag",
        id,
        "bg",
        json!(bg),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "tag",
        id,
        "fg",
        json!(fg),
        "human",
        None,
    )?;
    seed_set(
        conn,
        device,
        actor,
        workspace,
        "tag",
        id,
        "show_in_sidebar",
        json!(sidebar),
        "human",
        None,
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core_domain::Projection;

    fn build_for(conn: &Connection, workspace: &str) -> Projection {
        Projection::build(
            load_ops(conn).expect("加载 op 失败"),
            Some(workspace),
            "u-me",
            Settings::default(),
            false,
        )
    }

    fn seed_test_workspace(conn: &Connection) -> String {
        let device = device_id(conn).expect("设备 id 失败");
        let workspace = "ws-test";
        seed_workspace(conn, &device, "u-me", workspace, "测试空间").expect("工作区");
        seed_membership(conn, &device, "u-me", workspace, "u-me", "owner").expect("成员");
        seed_workspace_presets(conn, &device, "u-me", workspace, workspace).expect("预设");
        workspace.to_string()
    }

    #[test]
    fn workspace_presets_match_default_workspace() {
        let conn = Connection::open_in_memory().expect("打开内存库失败");
        migrate(&conn).expect("建表失败");
        let workspace = seed_test_workspace(&conn);

        let projection = build_for(&conn, &workspace);
        assert_eq!(projection.workspaces.len(), 1);
        let list_names: Vec<&str> = projection
            .lists
            .iter()
            .map(|list| list.name.as_str())
            .collect();
        assert_eq!(list_names, vec!["今天", "工作", "生活", "学习"]);
        assert_eq!(
            projection
                .lists
                .iter()
                .filter(|list| list.smart == Some(true))
                .count(),
            1
        );
        assert_eq!(projection.tags.len(), 5);
        let mut sidebar: Vec<&str> = projection
            .tags
            .iter()
            .filter(|tag| tag.show_in_sidebar == Some(true))
            .map(|tag| tag.name.as_str())
            .collect();
        sidebar.sort();
        assert_eq!(sidebar, vec!["本周", "重要"]);
    }

    #[test]
    fn deleted_workspace_is_filtered_from_projection() {
        let conn = Connection::open_in_memory().expect("打开内存库失败");
        migrate(&conn).expect("建表失败");
        let workspace = seed_test_workspace(&conn);
        let device = device_id(&conn).expect("设备 id 失败");
        seed_set(
            &conn,
            &device,
            "u-me",
            &workspace,
            "workspace",
            &workspace,
            "__deleted",
            json!(true),
            "human",
            None,
        )
        .expect("墓碑");

        let projection = build_for(&conn, &workspace);
        assert!(projection.workspaces.is_empty());
        assert!(projection.lists.is_empty());
        assert!(projection.tags.is_empty());
    }

    #[test]
    fn rejected_ops_are_excluded_from_projection() {
        let conn = Connection::open_in_memory().expect("打开内存库失败");
        migrate(&conn).expect("建表失败");
        let workspace = seed_test_workspace(&conn);
        let ops = load_all_ops(&conn).expect("读取 op 失败");
        let target = ops.first().expect("种子 op 为空");

        mark_op_rejected(&conn, &target.op_id, "forbidden: 测试").expect("标记失败");

        assert_eq!(load_ops(&conn).expect("读取 op 失败").len(), ops.len() - 1);
        assert_eq!(rejected_count(&conn).expect("统计失败"), 1);
        let projection = build_for(&conn, &workspace);
        assert_eq!(projection.workspaces.len(), 1);
    }

    #[test]
    fn pending_queue_transitions_to_synced() {
        let conn = Connection::open_in_memory().expect("打开内存库失败");
        migrate(&conn).expect("建表失败");
        seed_test_workspace(&conn);

        let pending = pending_ops(&conn, 10_000).expect("读取待推送失败");
        assert!(!pending.is_empty());
        let items: Vec<(String, i64)> = pending
            .iter()
            .enumerate()
            .map(|(index, op)| (op.op_id.clone(), index as i64 + 10))
            .collect();
        mark_ops_synced(&conn, &items).expect("标记失败");

        assert_eq!(pending_count(&conn).expect("统计失败"), 0);
        for (op_id, seq) in &items {
            let stored: Option<i64> = conn
                .query_row(
                    "SELECT server_seq FROM op_log WHERE op_id = ?1",
                    params![op_id],
                    |row| row.get(0),
                )
                .optional()
                .expect("查询失败");
            assert_eq!(stored, Some(*seq));
        }
    }

    #[test]
    fn observe_hlc_only_moves_forward() {
        let conn = Connection::open_in_memory().expect("打开内存库失败");
        migrate(&conn).expect("建表失败");

        let local = next_hlc(&conn).expect("本地 HLC 失败");
        observe_hlc(&conn, local.physical_ms, local.counter + 5).expect("校正失败");
        let after = next_hlc(&conn).expect("本地 HLC 失败");
        assert!(after.physical_ms >= local.physical_ms);

        let far = local.physical_ms + 10_000;
        observe_hlc(&conn, far, 7).expect("校正失败");
        let advanced = next_hlc(&conn).expect("本地 HLC 失败");
        assert!(advanced.physical_ms >= far);
    }

    #[test]
    fn reset_for_account_backs_up_then_clears() {
        let conn = Connection::open_in_memory().expect("打开内存库失败");
        migrate(&conn).expect("建表失败");
        seed_test_workspace(&conn);
        set_cursor(&conn, 42).expect("写游标失败");
        let node_before: String = conn
            .query_row("SELECT node_id FROM local_hlc WHERE id = 1", [], |row| {
                row.get(0)
            })
            .expect("读取 node_id 失败");

        let dir = std::env::temp_dir().join(format!(
            "todo-backup-test-{}",
            Uuid::new_v4().to_string()[..8].to_string()
        ));
        let backup = reset_for_account(&conn, &dir).expect("重置失败");

        let backup = backup.expect("应当生成备份");
        assert!(backup.exists(), "备份文件不存在: {}", backup.display());
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM op_log", [], |row| row.get(0))
            .expect("统计失败");
        assert_eq!(count, 0);
        assert_eq!(cursor(&conn), 0);
        let node_after: String = conn
            .query_row("SELECT node_id FROM local_hlc WHERE id = 1", [], |row| {
                row.get(0)
            })
            .expect("读取 node_id 失败");
        assert_eq!(node_before, node_after);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
