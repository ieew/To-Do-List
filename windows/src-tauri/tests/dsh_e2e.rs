//! dsh HTTP 通道与真实服务端的端到端测试（MQTT 可关闭）。
//!
//! 默认忽略；先启动服务端，再运行：
//! `TODO_DSH_E2E_BASE_URL=http://127.0.0.1:8012 cargo test --test dsh_e2e --no-default-features -- --ignored --nocapture`
//!
//! 覆盖：设备注册 → push 落库 → 票据 pull → ack → 剪贴板上行（ticket 鉴权）。

use todo_windows_lib::dsh::client::DshApi;
use todo_windows_lib::sync::client::{Api, RegisterBody};

fn base_url() -> Option<String> {
    std::env::var("TODO_DSH_E2E_BASE_URL").ok()
}

fn tag() -> String {
    uuid::Uuid::new_v4().to_string()[..8].to_string()
}

#[tokio::test]
#[ignore]
async fn register_push_pull_ack_and_clip() {
    let Some(base) = base_url() else {
        eprintln!("跳过：未设置 TODO_DSH_E2E_BASE_URL");
        return;
    };

    let api = Api::new(&base).expect("创建 API 失败");
    let username = format!("dsh-e2e-{}", tag());
    let registered = api
        .register(&RegisterBody {
            username,
            password: "Passw0rd!dsh".into(),
            email: None,
            display_name: Some("dsh e2e".into()),
            device_id: None,
            workspace_name: None,
        })
        .await
        .expect("注册失败");
    let token = registered.tokens.access_token;

    let dsh = DshApi::new(&base).expect("创建 dsh API 失败");
    let device = dsh
        .register_device(&token, "e2e-windows", "windows", None)
        .await
        .expect("注册设备失败");
    assert!(device.device_id.starts_with("dsh-"));
    assert!(!device.ticket.is_empty());

    // 1) push 落库 → 用设备票据 pull → ack。
    let msg_id = format!("e2e-{}", tag());
    dsh.push_notify(&token, "测试通知", "端到端正文", 3, &msg_id)
        .await
        .expect("push 失败");

    let mut cursor = 0i64;
    let mut found = false;
    for _ in 0..5 {
        let page = dsh
            .pull_as_device(&device.device_id, &device.ticket, cursor, 100)
            .await
            .expect("pull 失败");
        if page.messages.iter().any(|message| message.msg_id == msg_id) {
            found = true;
        }
        if !page.messages.is_empty() {
            let seqs = page
                .messages
                .iter()
                .map(|message| message.seq)
                .collect::<Vec<_>>();
            let acked = dsh
                .ack_as_device(&device.device_id, &device.ticket, &seqs)
                .await
                .expect("ack 失败");
            assert_eq!(acked.requested, seqs.len() as i64);
        }
        cursor = page.next_seq;
        if found || !page.has_more {
            break;
        }
    }
    assert!(found, "没有拉到刚推送的消息");

    // ack 后同一游标再 pull 应为空（验证票据鉴权与游标语义）。
    let page = dsh
        .pull_as_device(&device.device_id, &device.ticket, cursor, 100)
        .await
        .expect("二次 pull 失败");
    assert!(page.messages.is_empty(), "ack 后不应再拉到消息");

    // 2) 剪贴板上行：默认不落库；无其他在线设备时 delivered=0。
    let clip = dsh
        .clip_as_device(
            &device.device_id,
            &device.ticket,
            "dsh e2e 剪贴板",
            "abc123",
            &format!("e2e-clip-{}", tag()),
        )
        .await
        .expect("剪贴板上行失败");
    assert!(!clip.stored);
}
