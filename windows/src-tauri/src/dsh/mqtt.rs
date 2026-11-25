//! 极薄的 rumqttc 封装：只订阅 `topic_in`，把 nudge / clip 解析后交给 worker。
//!
//! 服务端默认不启用 MQTT TLS；遇到 `tls=true` 直接报错，由 worker 降级为 HTTP 轮询。

use super::protocol::{parse_mqtt_payload, MqttEndpoint, MqttPayload};
use rumqttc::{AsyncClient, Event, Incoming, MqttOptions, QoS};
use tokio::sync::mpsc::Sender;

#[derive(Clone, Debug, PartialEq)]
pub enum MqttEvent {
    Connected,
    Nudge,
    /// 其它设备写了数据：让同步 worker 立即 pull。
    Sync,
    Clip {
        id: String,
        text: String,
        fp: String,
    },
}

/// 连接并持续 poll，直到出错或被取消。返回 Err 时由调用方负责退避重连。
pub async fn run(
    endpoint: MqttEndpoint,
    ticket: String,
    events: Sender<MqttEvent>,
) -> Result<(), String> {
    if endpoint.tls {
        return Err("服务端启用了 MQTT TLS，当前客户端暂不支持".into());
    }

    let mut options = MqttOptions::new(
        endpoint.username.clone(),
        endpoint.host.clone(),
        endpoint.port,
    );
    options.set_credentials(endpoint.username.clone(), ticket);
    options.set_keep_alive(std::time::Duration::from_secs(60));
    options.set_clean_session(true);

    let (client, mut eventloop) = AsyncClient::new(options, 32);
    client
        .subscribe(endpoint.topic_in.clone(), QoS::AtLeastOnce)
        .await
        .map_err(|error| format!("订阅 MQTT 主题失败: {error}"))?;

    loop {
        match eventloop.poll().await {
            Ok(Event::Incoming(Incoming::ConnAck(_))) => {
                let _ = events.send(MqttEvent::Connected).await;
            }
            Ok(Event::Incoming(Incoming::Publish(publish))) => {
                let payload = parse_mqtt_payload(&publish.payload);
                match payload {
                    Some(MqttPayload::Nudge { .. }) => {
                        if events.send(MqttEvent::Nudge).await.is_err() {
                            return Ok(());
                        }
                    }
                    Some(MqttPayload::Sync) => {
                        if events.send(MqttEvent::Sync).await.is_err() {
                            return Ok(());
                        }
                    }
                    Some(MqttPayload::Clip { id, text, fp }) => {
                        if events.send(MqttEvent::Clip { id, text, fp }).await.is_err() {
                            return Ok(());
                        }
                    }
                    None => {
                        crate::logging::warn(
                            "dsh",
                            format!("忽略未知 MQTT 载荷，topic={}", publish.topic),
                        );
                    }
                }
            }
            Ok(_) => {}
            Err(error) => {
                return Err(format!("MQTT 连接中断: {error}"));
            }
        }
    }
}
