# 安卓 App × 中文输入法2 dsh 通道协作设计（通道委托与提醒去重）

> 状态：设计稿（2026-11-24），未实现。
> 关联：[`plans/todo-app-plan.md`](../plans/todo-app-plan.md) §4.1、[`docs/ime-push-client.md`](./ime-push-client.md) §17/§18、[`docs/reminder-and-tag-model.md`](./reminder-and-tag-model.md) §一/§一之二。

## 一、目标与原则

1. **输入法通道优先**：用户启用中文输入法2（`com.nirenr.trime`）的 dsh-kit 时，Android App 依赖它的 MQTT nudge 作为实时唤醒信号，不再自己维持第二条 MQTT 长连接。
2. **通知/提醒由 App pull**：`/v1/dsh/pull`、`/v1/dsh/ack`、游标与 `msg_id` 去重由 Android App 执行和管理；输入法只提供 nudge。
3. **无输入法通道时 App 自建**：输入法未安装、未启用、未授权、MQTT 未连、App 未登录、provider 不可达时，App 启动自己的 dsh 客户端（Paho MQTT 优先，失败降级 HTTP 轮询）。
4. **单一提醒展示者**：同一台手机上，待办提醒只允许一个进程弹通知。默认由 App 展示；只有 App 不可用时才由输入法兜底。
5. **去重键统一**：提醒以 `(workspace_id, task_id, remind_at_ms)` 为业务幂等键；消息以 `msg_id` 为传输幂等键。
6. **插件不保存账号密码**：账号与登录态只属于 Android App；App 以「授权一台 dsh 设备」的方式把短票据和 MQTT 参数交给插件，插件不再保存用户名/密码/refresh token。
7. **剪贴板逻辑全部留在插件**：Android App 后台权限低于输入法，无法可靠持续监听剪贴板；`ClipSource`、上行、下行应用、防回流都只在 dsh-kit 内完成，App 不参与剪贴板同步。

## 二、现状与问题

| 现状 | 问题 |
| --- | --- |
| 输入法 dsh-kit 自己保存账号/密码、自己注册设备、自己 pull/ack、自己弹通知 | App 没有 dsh 客户端，无法复用通道；插件持有账号凭据也不符合权限边界 |
| App 本地用 AlarmManager 提醒，`task_local.last_fired_at` 只在本进程内生效 | 输入法不知道 App 已提醒，服务端提醒到达时双弹 |
| 插件→App 只有 `feature:ime` 的 ContentProvider（任务查询/新增/勾选/删除） | 没有设备授权、通道状态、nudge、设置下发接口 |
| 服务端提醒按用户广播到全部设备，各设备各自 ack | App 与输入法各注册设备时天然会各收一份 |
| 插件 `Session.pullNow` 在 `apply=false` 时仍推进游标 | 一旦引入「委托 App 展示」失败，消息会被吞掉 |
| App 后台无法持续读剪贴板 | 剪贴板只能由输入法进程负责 |

## 三、设备身份、授权与 pull 所有权

### 3.1 两个设备，各自独立

同一台手机上，App 与输入法使用两个独立的 dsh 设备：

| 消费者 | device_id | 票据来源 | 游标/去重 | 职责 |
| --- | --- | --- | --- | --- |
| Android App | `dsh-app-*` | App 用自身 Bearer 注册 | App 的 `dsh_cursor` / `dsh_seen` | pull/ack 通知与提醒；提醒展示 |
| 输入法 dsh-kit | `dsh-ime-*` | **App 代为注册并下发短票据** | 插件的 `cursor` / `notify_seen` | MQTT nudge；剪贴板上行/下行；兜底展示 |

**不共用 `device_id`**。原因：

- 服务端 ack 是**按设备**记录，两个进程共用设备会出现「一方 ack、另一方永远拉不到」；
- 两个 puller 共用游标会互相覆盖；
- 分离后服务端广播会分别投递到两台设备，但通过「单一展示者」消除重复提醒，代价只是多一份队列消息。

### 3.2 App 授权 + 插件自主续期（设备级凭证）

- 账号、access/refresh token 只由 Android App 持有；**插件不保存用户名、密码、账号 refresh token**。
- 首次授权：
  1. App 用自己的 Bearer 调 `POST /v1/dsh/devices/register`（`platform="android-ime"`，复用 `ime_device_id`）；
  2. App 调 `POST /v1/dsh/devices/{device_id}/credential` 签发**设备级凭证**；
  3. App 通过 `dsh_authorize` 把 `device_id`、10 分钟 `ticket`、`credential`、`mqtt.*`、`user_id`、`server_url` 返回给插件。
- 插件把 `device_id` 与 `credential` 持久化到私有目录；`credential` 用 Android Keystore AES/GCM 加密，`ticket` 仅存内存。
- 插件自主续期：每 8 分钟或收到 `401/403/409` 时调
  `POST /v1/dsh/devices/refresh`（`device_id` + `credential`），换新票据并轮换凭证；不依赖 App 在线，也不需要账号密码。
- 凭证失效/被吊销时，插件清除本地凭证并重新调用 `dsh_authorize`；App 不可用则停止通道。
- App 只保存 `ime_device_id`（非敏感），**不保存 credential**；用户退出登录、切换账号或关闭「使用输入法通道」时调用
  `POST /v1/dsh/devices/{id}/revoke`，凭证立即失效并踢线。
- 凭证只允许续期 dsh 票据，不能调用账号级接口；服务端只存 `sha256(credential)`。

### 3.3 pull 所有权拆分

- **通知/提醒**：App 始终自己注册 `dsh-app-*` 并 pull/ack；即使输入法通道在线，也不把通知 pull 委托给插件。输入法只发 nudge。
- **剪贴板**：插件用自己的 `dsh-ime-*` 票据 pull/ack，并独占 `ClipSource` 与剪贴板写入；App 不 pull 剪贴板、不监听剪贴板。
- 两个 device 各有独立游标；不存在「谁替谁 ack」的问题。

## 四、单一展示者与通道状态机

### 4.1 判定「输入法通道可用」

输入法通过 App 的 ContentProvider 上报心跳（见 §五），App 按以下条件判定：

- provider 可达，且调用方是 `com.nirenr.trime`（沿用现有 UID/包名校验）；
- 心跳时间 ≤ **120s**；
- `mqtt_connected = true`；
- `user_id` 与 `server_url` 与 App 当前会话一致（插件不自带账号，以 `dsh_authorize` 返回值为准）；
- App 自身有通知权限（否则 `delegate=false`，由输入法兜底）；
- App 设置 `prefer_ime_channel = true`（默认 true）。

全部满足 → `IME_ACTIVE`。

### 4.2 状态机

| 状态 | 触发 | 行为 |
| --- | --- | --- |
| `IME_ACTIVE` | 上述条件满足 | 不启动 App MQTT；等待 `dsh_nudge`；保持 120s 兜底 pull |
| `APP_CONNECTING` | 心跳连续丢失 2 次（≈120s）或 provider 不可达 | 启动 App dsh 客户端，注册设备、连 MQTT |
| `APP_MQTT` | App MQTT 连上 | 自己收 nudge 并 pull 通知/提醒；clip 只 ack 丢弃（剪贴板仍归插件） |
| `APP_POLL` | App MQTT 失败 | 30s HTTP pull；仅通知可用，默认配置下剪贴板实时接收不可用 |
| `OFFLINE` | 未登录/无网络 | 仅保留本地提醒，等待恢复 |

切换采用滞回：

- IME → App：连续 2 次心跳缺失或 provider 调用失败立即切换；
- App → IME：连续 2 次心跳（间隔 ≥10s）且 `mqtt_connected=true` 才停掉 App MQTT，避免抖动。

### 4.3 展示归属

| 场景 | 谁展示提醒 | 剪贴板 | 说明 |
| --- | --- | --- | --- |
| `IME_ACTIVE` 且 App 可用 | **App** | **输入法** | 输入法收到 `kind=notify` 只 ack，不调 `NotifySink`；`ClipSource` 保持运行 |
| 输入法通道不可用 | **App** | 不可用 | App 自建通道收通知；插件未运行时不具备剪贴板能力 |
| App 被卸载/强制停止 | **输入法** | **输入法** | provider 不可达，输入法恢复自己的通知与剪贴板 |
| App 未登录/未授权 | **输入法**（若它仍有票据）或无人 | **输入法** | `dsh_authorize` 失败后插件不能再续票，只能处理手头未过期的票据 |

## 五、跨进程契约

方向一：**插件 → App**（沿用现有 `ContentResolver.call`，继续只放行 `com.nirenr.trime`）：

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `dsh_authorize` | `device_id?`, `device_name`, `platform` | `device_id`, `ticket`, `ticket_expires_at`, `credential`, `credential_expires_at`, `mqtt.*`, `user_id`, `server_url`, `channel_token` | App 用自身 Bearer 注册设备并签发设备级凭证；插件不碰账号密码 |
| `dsh_status` | `device_id`, `mqtt_connected`, `last_pull_at`, `can_notify`, `settings_version` | `delegate`, `reason`, `heartbeat_ttl_ms`, `settings_version` | 心跳/让位决策；60s 一次，连接变化立即一次 |
| `dsh_nudge` | `workspace_id?`, `dsh_seq?`, `at` | `accepted` | 插件收到 MQTT nudge 后通知 App 立即 pull |
| `dsh_settings` | — | `dnd_enabled`, `dnd_start`, `dnd_end`, `reminder_lead`, `notifications`, `delegate` | 插件兜底展示时读取 App 的提醒设置 |

方向二：**App → 插件**（新增，仅用于设置变更推送）：

- 插件动态注册 `RECEIVER_EXPORTED` 的广播接收器，action：`com.todo.android.dsh.SETTINGS`；
- App 用显式广播（`Intent.setPackage("com.nirenr.trime")`）发送；
- 广播 payload：`channel_token`、`dnd_enabled/dnd_start/dnd_end`、`reminder_lead`、`notifications`、`delegate`、`settings_version`；
- `channel_token` 来自最近一次 `dsh_authorize` 响应（仅内存）；插件校验不通过则丢弃并重新 `dsh_authorize`；
- 插件把 DND/提前量/通知开关缓存为**非敏感设置**，App 不可达时用缓存/默认值兜底。

约束：

- 插件每次 MQTT 连上/断开/每 60s 调 `dsh_status`；每次 nudge 调 `dsh_nudge`；启动和收到广播后调 `dsh_settings`。
- `dsh_status` 返回的 `delegate` 决定插件是否抑制 `kind=notify`；插件不再转发 clip（剪贴板由插件自己处理）。
- provider 调用会拉起 App 进程，这是「App 可用」的判定方式；调用失败即视为不可用。
- 广播接收器必须校验 `channel_token`；不要把票据/账号写进广播。

## 六、提醒去重

### 6.1 统一幂等键

- 业务键：`(workspace_id, task_id, remind_at_ms)`。
- 传输键：`msg_id`（服务端 `remind-<hash>` 或普通消息 id）。

### 6.2 App 侧统一入口

新增 `ReminderCoordinator.claim(task_id, remind_at_ms, source)`，在 `task_local` 上原子判断：

- `last_fired_at == remind_at_ms` → 已被其他通道处理，返回 false；
- 否则写入 `last_fired_at = remind_at_ms`、`fired_source = local|server`、`fired_at = now`，返回 true。

调用方：

- 本地 AlarmManager / 补发路径：先 claim 再 `ReminderNotifier`；
- 服务端消息：先校验 payload 的 `remind_at` 仍等于当前任务的 `remind_at`（旧消息直接 ack 丢弃），再 claim；
- DND：claim 后写 `reminder_deferred`，DND 结束汇总展示；
- App 通知权限不足：claim 后只记日志，避免反复补发；`dsh_status` 返回 `delegate=false`，由输入法接管展示。

### 6.3 幂等语义修正

Android 现有 `ReminderScheduler` 使用 `last_fired_at >= remind_at`，与 Windows 的 `==` 不一致。设计统一为：

- 比较用 `last_fired_at == remind_at`；
- `claim` 写入时**覆盖**而不是 `MAX`，否则把 `remind_at` 改早后会每轮重复触发；
- 本地修改 `remind_at` 时清空/覆盖 `task_local`；服务端旧消息通过「payload 的 remind_at 必须等于当前任务 remind_at」过滤。

### 6.4 输入法侧

- `IME_ACTIVE` 且 `delegate=true`：`NotifyModule` 对 **广播**（`device_id=None`）的 `kind=notify` **不展示**，只 `markSeen` + ack；nudge 已转发给 App。
- 定向到 IME 设备的 `kind=notify`（`device_id=本机`）仍由插件展示：App 的队列里没有这条，抑制会导致丢失。
- provider 不可达 / `delegate=false`：输入法恢复现有展示逻辑，并用自身 `notify_seen` 去重。
- 修复 `Session.pullNow`：`apply=false` 的消息不得推进游标；只把游标推进到「最后一个已应用 seq」。

## 七、剪贴板（插件独占）

Android 后台限制决定了 App 无法在切后台后持续监听剪贴板，因此：

- **全部剪贴板逻辑保留在 dsh-kit**：`ClipSource` 监听、指纹/2s 去重、`POST /v1/dsh/clip` 上行、MQTT clip 下行、系统剪贴板写入、防回流、`ignore_apps` 全在插件内。
- 插件使用自己的 `dsh-ime-*` 设备票据上行（服务端据此排除来源）；App 不调用 `dsh/clip`、不监听剪贴板。
- 默认 `clip_persist=false`：clip 只经 MQTT 直传，插件自己收、自己应用。
- `clip_persist=true` 时，clip 会进入插件自己的 pull 队列；插件按 `msg_id` 去重后应用；App 的 pull 队列即使收到 clip 也只 ack 丢弃，不写系统剪贴板。
- **插件未运行时没有剪贴板同步**：App 自建通道只承担通知/提醒，不提供剪贴板；设置页要明确提示这一点。
- 剪贴板与提醒的展示归属相互独立：提醒由 App 单一展示，剪贴板由插件独占。

## 八、账号、服务器与授权生命周期

- 插件没有自己的账号；`dsh_authorize` 返回的 `user_id`/`server_url` 就是唯一来源。
- 票据 10 分钟过期；插件每 8 分钟或收到 `401/403/409` 时调 `/v1/dsh/devices/refresh`，不依赖 App。
- 每次 refresh 轮换凭证，旧凭证立即失效；服务端只存哈希，日志不记录明文。
- 凭证默认 30 天有效；持续使用会随轮换延长。凭证过期/被吊销且 App 不可用时，插件停止通道。
- App 退出登录、切换账号、关闭「使用输入法通道」时：
  1. 调 `POST /v1/dsh/devices/{ime_device_id}/revoke`；
  2. 清空本地 `ime_device_id` 映射与 `channel_token`；
  3. 后续 `refresh` 返回 401/409，插件清除凭证并等待 App 重新授权。
- App 保存 `ime_device_id`（非敏感）以便同账号续期；插件持久化 `device_id`、加密后的 `credential`、游标、`notify_seen`，**不持久化账号密码/access/refresh token**。
- 切换服务器地址时同样先吊销旧设备，再用新地址重新授权。

## 九、失败与边界

| 场景 | 行为 |
| --- | --- |
| 输入法进程被杀 | 心跳停止；App 在 120s 内切到 `APP_MQTT`；剪贴板暂停，直到插件恢复 |
| App 被强制停止 | provider 调用失败；输入法用设备级凭证继续续票/展示/剪贴板；凭证被吊销或过期后才停止 |
| 交接瞬间双通道都在 | App 用 `dsh_seen` + `task_local` 去重；输入法 `delegate=true` 不展示 |
| MQTT 断线 | 双方各自退避重连；App 在 `APP_POLL` 30s 兜底；插件继续重连剪贴板 |
| 服务端换库/seq 回退 | 双方各自用 `latest_seq < cursor` 重置游标 |
| 通知权限被关 | App claim 后只记账，`dsh_status` 返回 `delegate=false`；输入法接管展示 |
| DND | App 展示时用 AppSettings；输入法兜底时用 `dsh_settings` 缓存/广播的 DND 与提前量 |
| 同一账号多台手机 | 每台手机各自 App/IME 设备；广播到全部设备，各自展示一份 |
| 插件未运行 | App 自建通道只保证通知/提醒；剪贴板同步不可用 |

## 十、服务端是否需要改

v1 不需要改协议：

- 服务端提醒已按 `(workspace_id, task_id, remind_at, user_id)` 去重，并广播到该用户全部设备；
- App 与输入法各注册设备后，各自收到一份，由客户端单一展示者去重。

可选后续（plan §4.1 阶段四）：`/v1/sync/push` 提交 op 后向工作区其他成员发 `kind="sync"` nudge，App 收到后立即 `/v1/sync/pull`。这与本设计正交。

## 十一、实施阶段

| 阶段 | 内容 |
| --- | --- |
| P0 | 修复插件 `Session.pullNow` 游标推进缺陷 |
| P1 | 服务端：新增 `POST /devices/{id}/credential`、`POST /devices/refresh`、凭证哈希存储/轮换/吊销/限流与测试（见 `server-protocol.md` §15.12） |
| P2 | App 新增 `core/dsh`：HTTP 注册/pull/ack、设备凭证签发、Paho MQTT、Room `dsh_seen`/`dsh_cursor`、`ReminderCoordinator.claim`、provider `dsh_authorize/dsh_status/dsh_settings/dsh_nudge` |
| P3 | 输入法：删除账号/密码配置，改用设备级凭证自主续期；`dsh_status` 心跳、`dsh_nudge` 转发、设置缓存/广播；`delegate` 时抑制广播通知，但**保留 ClipSource** |
| P4 | App 自建 MQTT 客户端（挂在 `KeepAliveService`），含票据续期、重连、降级轮询；App 忽略 clip |
| P5 | 设置页「通道状态」、设备授权/吊销入口、测试按钮、通知点击跳转任务、验收脚本 |

## 十二、验收矩阵

- [ ] 输入法通道在线：App 不建 MQTT；到点只弹一条（App）
- [ ] 输入法通道关闭/杀进程：120s 内 App 自建通道，到点只弹一条；剪贴板同步不可用
- [ ] App 强制停止：输入法用现有票据兜底弹提醒；票据过期后停止
- [ ] 同一提醒本地先弹、服务端后到：只弹一次
- [ ] 服务端提醒先到、本地闹钟后到：只弹一次
- [ ] 修改 `remind_at` 改早后：新时间弹一次，旧时间不再弹
- [ ] DND 期间：不弹，结束后汇总一次；输入法兜底时读取 App 设置
- [ ] App 通知权限关闭时，输入法接管展示；定向到 IME 设备的 notify 不会被抑制丢失
- [ ] 剪贴板：始终由输入法上传/应用；App 不监听剪贴板；对端内容不回流
- [ ] 插件不再保存账号密码/refresh token；退出登录能吊销 IME 设备
- [ ] 账号/服务器切换：旧 IME 设备被吊销，新授权生效
- [ ] 断网恢复/服务端重启：游标自愈，不重复弹旧提醒

## 十三、已确认与待办

已确认：

1. App 自建 MQTT 使用 **Paho**。
2. `delegate` 覆盖所有 `kind=notify`。
3. 心跳 60s / 超时 120s。
4. `task_local` 增加 `fired_source`/`fired_at`，Room 迁移版本 +1。
5. 需要 App → 插件反向通道（设置变更广播 + `channel_token`）。
6. 插件不保存账号密码；由 App 授权设备票据。
7. 剪贴板逻辑全部留在插件；App 不参与剪贴板同步。
8. 采用**方案 B：设备级凭证**。插件保存加密后的 `credential`，自主调 `/v1/dsh/devices/refresh` 续票据；不保存账号密码或账号 refresh token。

待实现时确认：

1. `channel_token` 用随机串还是 `PendingIntent` 能力；本设计默认随机串。
2. 插件票据续期间隔 8 分钟是否与 App 的网络/唤醒策略冲突。
3. 输入法兜底时是否需要显示「App 不可用，剪贴板正常/提醒由输入法接管」的状态。
