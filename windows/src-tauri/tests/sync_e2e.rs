//! 客户端同步层与真实服务端的端到端测试。
//!
//! 默认忽略；先启动服务端，再运行：
//! `TODO_SYNC_E2E_BASE_URL=http://127.0.0.1:8011 cargo test --test sync_e2e --no-default-features -- --ignored --nocapture`

use serde_json::json;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use todo_windows_lib::core_domain::Op;
use todo_windows_lib::storage;
use todo_windows_lib::sync::client::{Api, RegisterBody};
use todo_windows_lib::sync::session::{self, AccountRecord, Tokens};
use todo_windows_lib::sync::{engine, SyncState};
use todo_windows_lib::AppState;

fn base_url() -> Option<String> {
    std::env::var("TODO_SYNC_E2E_BASE_URL").ok()
}

fn temp_dir(tag: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "todo-sync-e2e-{tag}-{}",
        &uuid::Uuid::new_v4().to_string()[..8]
    ));
    std::fs::create_dir_all(&dir).expect("创建临时目录失败");
    dir
}

fn state_at(dir: &PathBuf) -> AppState {
    let conn = storage::open(&dir.join("todo.sqlite3")).expect("打开测试库失败");
    let sync = SyncState::new(&conn);
    AppState {
        db: Mutex::new(conn),
        sticky_contexts: Mutex::new(HashMap::new()),
        data_dir: dir.clone(),
        sync,
    }
}

fn prepare(db: &AppState, account: &AccountRecord, tokens: &Tokens, workspace: &str) {
    let conn = db.db.lock().expect("锁失败");
    storage::reset_for_account(&conn, &db.data_dir.join("backups")).expect("重置失败");
    storage::pref_set(&conn, "current_user_id", &account.user_id).expect("写用户失败");
    storage::pref_set(&conn, "active_workspace_id", workspace).expect("写工作区失败");
    session::save_account(&conn, account).expect("保存账户失败");
    drop(conn);
    session::save_tokens(account, tokens).expect("保存令牌失败");
    db.sync.set_signed_in(true);
}

fn write_op(db: &AppState, op: Op) {
    let conn = db.db.lock().expect("锁失败");
    storage::insert_op(&conn, &op).expect("写 op 失败");
}

fn new_op(
    device: &str,
    actor: &str,
    workspace: &str,
    entity_type: &str,
    entity_id: &str,
    operation: &str,
    field: &str,
    value: serde_json::Value,
    physical_ms: i64,
    counter: u64,
    node_id: &str,
) -> Op {
    Op {
        op_id: uuid::Uuid::new_v4().to_string(),
        workspace_id: workspace.to_string(),
        entity_type: entity_type.to_string(),
        entity_id: entity_id.to_string(),
        actor_id: actor.to_string(),
        device_id: device.to_string(),
        operation: operation.to_string(),
        field: field.to_string(),
        value,
        physical_ms,
        counter,
        node_id: node_id.to_string(),
        source: "human".to_string(),
        model: None,
    }
}

fn append(
    db: &AppState,
    device: &str,
    actor: &str,
    workspace: &str,
    entity_type: &str,
    entity_id: &str,
    operation: &str,
    field: &str,
    value: serde_json::Value,
) {
    let physical_ms = storage::now_ms();
    let op = new_op(
        device,
        actor,
        workspace,
        entity_type,
        entity_id,
        operation,
        field,
        value,
        physical_ms,
        0,
        device,
    );
    write_op(db, op);
}

fn append_with_time(
    db: &AppState,
    device: &str,
    actor: &str,
    workspace: &str,
    entity_id: &str,
    field: &str,
    value: serde_json::Value,
    physical_ms: i64,
    counter: u64,
    node_id: &str,
) {
    let op = new_op(
        device,
        actor,
        workspace,
        "task",
        entity_id,
        "set",
        field,
        value,
        physical_ms,
        counter,
        node_id,
    );
    write_op(db, op);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore]
async fn client_server_round_trip() {
    let Some(base) = base_url() else {
        eprintln!("未设置 TODO_SYNC_E2E_BASE_URL，跳过");
        return;
    };
    std::env::set_var("TODO_SYNC_MEMORY_TOKENS", "1");

    let api = Api::new(&base).expect("构造 API 失败");
    let device = format!("win-{}", &uuid::Uuid::new_v4().to_string()[..12]);
    let username = format!("e2e{}", &uuid::Uuid::new_v4().to_string()[..8]);
    let register = api
        .register(&RegisterBody {
            username,
            password: "Passw0rd!234".to_string(),
            email: None,
            display_name: Some("端到端测试".to_string()),
            device_id: Some(device.clone()),
            workspace_name: Some("同步测试空间".to_string()),
        })
        .await
        .expect("注册失败");

    let account = AccountRecord {
        server_url: api.base_url.clone(),
        user_id: register.user.user_id.clone(),
        username: register.user.username.clone(),
        display_name: register.user.display_name.clone(),
    };
    let tokens = Tokens::from(&register.tokens);

    // 设备 1：首次全量拉取。
    let dir1 = temp_dir("one");
    let db1 = state_at(&dir1);
    prepare(&db1, &account, &tokens, &register.default_workspace_id);
    let outcome = engine::pull_initial(&db1).await.expect("首次拉取失败");
    assert!(outcome.pulled > 0, "首次拉取应包含服务端种子 op");
    assert_eq!(storage::pending_count(&db1.db.lock().unwrap()).unwrap(), 0);

    let workspace = register.default_workspace_id.clone();
    let today = {
        let conn = db1.db.lock().unwrap();
        let projection = storage::build_projection(&conn, &workspace).expect("投影失败");
        assert_eq!(projection.workspaces.len(), 1);
        assert_eq!(projection.lists.len(), 4);
        assert_eq!(projection.tags.len(), 5);
        projection
            .lists
            .iter()
            .find(|list| list.smart == Some(true))
            .expect("缺少今天清单")
            .id
            .clone()
    };

    // 本地建任务 → push。
    let task_id = format!("task-{}", &uuid::Uuid::new_v4().to_string()[..8]);
    append(
        &db1,
        &device,
        &account.user_id,
        &workspace,
        "task",
        &task_id,
        "set",
        "__created",
        json!("2026-11-10T10:00:00+08:00"),
    );
    append(
        &db1,
        &device,
        &account.user_id,
        &workspace,
        "task",
        &task_id,
        "set",
        "title",
        json!("端到端任务"),
    );
    append(
        &db1,
        &device,
        &account.user_id,
        &workspace,
        "task",
        &task_id,
        "set",
        "list_id",
        json!(today),
    );
    let outcome = engine::run_once(&db1).await.expect("推送失败");
    assert!(outcome.pushed >= 3, "应当推送任务 op");
    assert_eq!(storage::pending_count(&db1.db.lock().unwrap()).unwrap(), 0);

    // 设备 2：同账户拉取后应看到同一任务。
    let dir2 = temp_dir("two");
    let db2 = state_at(&dir2);
    prepare(&db2, &account, &tokens, &workspace);
    engine::pull_initial(&db2).await.expect("设备 2 拉取失败");
    {
        let conn = db2.db.lock().unwrap();
        let projection = storage::build_projection(&conn, &workspace).expect("投影失败");
        assert!(
            projection.tasks.iter().any(|task| task.id == task_id),
            "设备 2 未看到设备 1 的任务"
        );
    }

    // 双端并发改标题：LWW 取 HLC 更大者。
    // HLC 必须晚于 membership 授权 op，否则服务端按 role-at-HLC 会判定为无权限；
    // 略微超前（2 秒内）在服务端 5 分钟容差内。
    let base_ms = storage::now_ms() + 1_000;
    append_with_time(
        &db1,
        &device,
        &account.user_id,
        &workspace,
        &task_id,
        "title",
        json!("标题 A"),
        base_ms,
        0,
        "node-a",
    );
    append_with_time(
        &db2,
        &device,
        &account.user_id,
        &workspace,
        &task_id,
        "title",
        json!("标题 B"),
        base_ms + 1_000,
        0,
        "node-b",
    );
    engine::run_once(&db1).await.expect("设备 1 推送失败");
    engine::run_once(&db2).await.expect("设备 2 推送失败");

    let dir3 = temp_dir("three");
    let db3 = state_at(&dir3);
    prepare(&db3, &account, &tokens, &workspace);
    engine::pull_initial(&db3).await.expect("设备 3 拉取失败");
    {
        let conn = db3.db.lock().unwrap();
        let projection = storage::build_projection(&conn, &workspace).expect("投影失败");
        let task = projection
            .tasks
            .iter()
            .find(|task| task.id == task_id)
            .expect("缺少任务");
        assert_eq!(task.title, "标题 B", "LWW 应取 HLC 更大的标题");
    }

    // 非成员推送：所有 op 被拒，且不进入投影。
    let outsider_name = format!("out{}", &uuid::Uuid::new_v4().to_string()[..8]);
    let outsider = api
        .register(&RegisterBody {
            username: outsider_name,
            password: "Passw0rd!234".to_string(),
            email: None,
            display_name: Some("外部用户".to_string()),
            device_id: Some(device.clone()),
            workspace_name: None,
        })
        .await
        .expect("注册外部用户失败");
    let outsider_account = AccountRecord {
        server_url: api.base_url.clone(),
        user_id: outsider.user.user_id.clone(),
        username: outsider.user.username.clone(),
        display_name: outsider.user.display_name.clone(),
    };
    let outsider_tokens = Tokens::from(&outsider.tokens);
    let dir4 = temp_dir("four");
    let db4 = state_at(&dir4);
    prepare(
        &db4,
        &outsider_account,
        &outsider_tokens,
        &outsider.default_workspace_id,
    );
    engine::pull_initial(&db4).await.expect("外部用户拉取失败");
    let denied_task = format!("task-{}", &uuid::Uuid::new_v4().to_string()[..8]);
    append(
        &db4,
        &device,
        &outsider_account.user_id,
        &workspace,
        "task",
        &denied_task,
        "set",
        "__created",
        json!("2026-11-10T10:00:00+08:00"),
    );
    let outcome = engine::run_once(&db4).await.expect("推送不应导致引擎错误");
    assert!(outcome
        .notices
        .iter()
        .any(|notice| notice.kind == "permission"));
    assert!(storage::rejected_count(&db4.db.lock().unwrap()).unwrap() >= 1);
    {
        let conn = db4.db.lock().unwrap();
        let projection = storage::build_projection(&conn, &workspace).expect("投影失败");
        assert!(projection.tasks.is_empty());
    }

    let _ = std::fs::remove_dir_all(dir1);
    let _ = std::fs::remove_dir_all(dir2);
    let _ = std::fs::remove_dir_all(dir3);
    let _ = std::fs::remove_dir_all(dir4);
}
