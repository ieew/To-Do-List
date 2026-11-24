//! dsh 推送通道（`/v1/dsh/*`）：设备注册、MQTT nudge、HTTP pull/ack、剪贴板同步。

pub mod protocol;

pub mod client;
#[cfg(feature = "desktop")]
pub mod clipboard;
#[cfg(feature = "desktop")]
pub mod mqtt;
#[cfg(feature = "desktop")]
pub mod worker;

use protocol::MqttEndpoint;
use serde::Serialize;
use std::sync::Mutex;

/// 设置页与同步横幅使用的通道状态。
#[derive(Clone, Debug, Default, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct DshStatusDto {
    /// 通道总开关（推送或剪贴板任一开启）且已登录。
    pub enabled: bool,
    pub signed_in: bool,
    pub mqtt_enabled: bool,
    pub mqtt_connected: bool,
    /// MQTT 不可用，已退化为定时 HTTP 轮询；此时默认配置下收不到剪贴板。
    pub degraded: bool,
    pub device_id: Option<String>,
    pub last_pull_at: Option<i64>,
    pub last_clip_sent_at: Option<i64>,
    pub last_clip_received_at: Option<i64>,
    pub last_error: Option<String>,
}

#[derive(Default)]
pub struct DshRuntime {
    pub status: DshStatusDto,
    pub device_id: Option<String>,
    pub ticket: Option<String>,
    pub ticket_expires_at: i64,
    pub endpoint: Option<MqttEndpoint>,
    /// 请求下一轮强制重新注册（手动重连 / 票据失效）。
    pub force_register: bool,
    /// 请求立即 pull 一次（手动同步 / 收到 nudge）。
    pub force_pull: bool,
    /// 手动重连：注册成功后清零 MQTT 退避。
    pub mqtt_reconnect: bool,
    /// 上次广播的 JSON，用于避免无变化的重复事件。
    pub last_emitted: Option<String>,
    /// 远端刚写入剪贴板的文本：上行轮询看到它时只更新水位，避免把远端内容传回去。
    pub clip_last_applied: Option<String>,
}

#[derive(Default)]
pub struct DshState {
    pub runtime: Mutex<DshRuntime>,
    /// 登录、设置变更、手动操作时立即唤醒后台循环。
    pub notify: tokio::sync::Notify,
}

impl DshState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn wake(&self) {
        self.notify.notify_one();
    }

    /// 退出登录/切换账户：清掉内存里的票据与 MQTT 参数，等待重新注册。
    pub fn reset(&self) {
        if let Ok(mut runtime) = self.runtime.lock() {
            let last_error = runtime.status.last_error.take();
            let device_id = runtime.device_id.clone();
            runtime.ticket = None;
            runtime.ticket_expires_at = 0;
            runtime.endpoint = None;
            runtime.force_register = true;
            runtime.force_pull = false;
            runtime.mqtt_reconnect = false;
            runtime.clip_last_applied = None;
            runtime.status = DshStatusDto {
                device_id,
                last_error,
                ..Default::default()
            };
        }
        self.wake();
    }

    pub fn snapshot(&self) -> DshStatusDto {
        self.runtime
            .lock()
            .map(|runtime| runtime.status.clone())
            .unwrap_or_default()
    }

    #[cfg(feature = "desktop")]
    pub fn emit(&self, app: &tauri::AppHandle) {
        use tauri::Emitter;

        let status = self.snapshot();
        let Ok(raw) = serde_json::to_string(&status) else {
            return;
        };
        let fresh = match self.runtime.lock() {
            Ok(mut runtime) => {
                if runtime.last_emitted.as_deref() == Some(raw.as_str()) {
                    false
                } else {
                    runtime.last_emitted = Some(raw);
                    true
                }
            }
            Err(_) => false,
        };
        if fresh {
            let _ = app.emit("dsh-status-changed", &status);
        }
    }
}
