# dsh 通知推送链路：稳定性与资源设计

> 状态：设计稿（待评审，未实现）
> 范围：`POST /v1/dsh/push` 发出 → MQTT nudge → 客户端 pull → 展示通知 的完整链路，
> 覆盖服务端、Android App、输入法插件三端。
> 相关文档：[server-protocol.md](server-protocol.md) §15、[ime-push-client.md](ime-push-client.md)、
> [reminder-and-tag-model.md](reminder-and-tag-model.md)。

## 0. 背景与目标

dsh 通道目前承载四类流量：通知推送、任务提醒、剪贴板、数据变更信号（`{"t":"sync"}`）。
通道最初是按「任务提醒」设计的，现在要把它当作**通用通知 API** 用（第三方程序也能推），
于是对稳定性和资源的要求整体抬高了一个档次。本文不追求「最小可用」，而是把整条链路的
失效模式逐个堵死，并把常驻开销压到可量化、可验收的水平。

### 0.1 目标（按优先级）

目标分级设定：主通道（输入法插件）追求「绝对」，降级通道（App）追求「不漏太久」。

| # | 目标 | 主通道 | 降级通道 |
| --- | --- | --- | --- |
| 1 | **不丢**：服务端接受（2xx）的通知，TTL 内至少送达并展示一次 | 结构上保证（先落盘再 ack） | 结构上保证（落库再 ack）+ 前台服务争取存活 |
| 2 | **不重**：同一设备最多展示一次；多端只有一个展示者 | `msg_id` + `claim` 去重 | 同左，但 claim 失败时让位给主通道 |
| 3 | **快**：从 push 返回到通知出现，P95 | < 1.5s | < 3s（进程在时）/ 下次唤醒 |
| 4 | **省**：空闲时的网络请求与写盘 | ≤ 100 次/天 | ≤ 300 次/天 |
| 5 | **可观测**：延迟/丢弃能定位到环节 | 必须 | 必须 |
| 6 | **诚实**：用户能看到当前是主通道还是降级 | 必须 | 必须 |

「诚实」这一项是新增的：既然两条通道可靠性不同，界面就不能只有一个「已连接」/「未连接」，
否则用户无从判断自己是否处在弱保障状态。

**优先级是硬的：稳定与实时 > 省资源。** 所有「省」的手段都必须在**不损害长连接稳定性、
不增加通知延迟**的前提下才能采纳。具体到取舍：

- 长连接（MQTT）是主路径，**不因为省电而降级成轮询**；轮询永远是兜底，不是主路径；
- 省资源只允许动「空闲期的无效动作」（重复 pull、全量重写、线程泄漏、合并 nudge），
  不允许动「有消息时的响应路径」；
- 任何优化如果在极端情况下引入「通知延迟变大」的风险，一律不做。

这条也否决了一个看起来更简单的方案：让降级通道只轮询、不建长连接。
它省下的那点电，换的是插件缺席时 15 分钟的延迟上限 —— 不符合本项目的取舍。

### 0.2 非目标

- 不做推送内容的多设备漫游（同一条通知只要求每台自己的设备各收一次）。
- 不替换本地闹钟：任务提醒的「准时」由本地 `AlarmManager` 保证，推送只是旁路。
- 不在本轮做 Webhook / 长轮询给外部消费。
- **Windows 客户端不在推送链路改造范围内**：它已经是可信进程、有自己的 `dsh` worker
  （`windows/src-tauri/src/dsh/`），本轮只要求它**跟随协议变化**
  （`{"t":"nudge"}`/`{"t":"sync"}` 的字段兼容、`source_kind` 可选）。
  唯一例外是**账号态与本地数据安全**（§9.6）—— 那里它和 Android 有同样的丢数据风险，必须一起修。

### 0.3 三端在本轮的角色

| 端 | 本轮是否改造 | 说明 |
| --- | --- | --- |
| 服务端 | ✅ 改 | 提醒索引、nudge 合并、指标、限流键 |
| 输入法插件 | ✅ 改 | 主通道：队列、重连、撤销、单实例 |
| Android App | ✅ 改 | 降级通道 + 撤销入口 + 凭证收口 |
| Windows 客户端 | ✅ 改（仅账号态） | 数据安全部分（§9.6）要改；其他只保证协议兼容 |

### 0.4 交接说明（给实现方）

这份文档是**设计定稿**，代码尚未开始改。实现由另一个环境（服务器版 dsh-web）接手，
本节是交接约定；有冲突时以本文档的「设计契约」为准，不以口头描述为准。

#### 任务范围

按 §12 的阶段表实施，**从阶段 1 开始，严格按顺序**，不要并行开工多个阶段：
阶段之间是有依赖的（落库 → 自适应轮询 → 指标），跳步会造成中间状态不可验证。

#### 代码位置

| 端 | 路径 | 说明 |
| --- | --- | --- |
| 服务端 | `server/` | FastAPI + aiosqlite，`uvicorn --workers 1`（多进程会被 `ProcessLock` 拒绝） |
| Android App | `android/` | 模块化：`core:dsh`、`core:sync`、`core:reminder`、`feature:ime`、`feature:settings` |
| 输入法插件 | `ime-plugin/` | 纯 Java（编译目标 android-23），`src/java/com/dshkit/`、`src/lua/` |
| Windows 客户端 | `windows/src-tauri/` | Rust；本轮只有 §9.6 的数据安全改动 |

#### 必须遵守的设计契约

这十条是本轮的前提，改动时不得绕过；如果发现某条无法实现，**先改文档再改代码**。

1. 插件自持：主通道的关键路径（接收/落盘/展示/重试）不得依赖 App 进程（§3.0）
2. 送达与展示分离：ack 只表示「已落盘」；展示失败要能重试（§6.1）
3. 实时性优先于省资源（§0.1）；省资源只动空闲期的无效动作
4. 两条通道互斥但允许短暂重叠，宁可双连不可空档（§6.7）
5. 撤销权威在服务端，App 不删除 `dsh_ime_device_id`（§5.5）
6. 插件只持设备凭证：废弃 `autoLogin`、`refresh_token` 不落盘（§5.5）
7. 提醒索引逐字段 HLC 比较，`COALESCE` 兜底 NULL（§4.1）
8. 换账号清库必须原子 + 可确认（§9.2）
9. 不得合并 App 与插件的 `device_id`（投递隔离是可靠性与安全的基础）
10. 协议只增不改：新能力走新方法/新字段，旧端不认识就跳过（§3.0.1）

#### 每阶段完成的标志

不只是「代码写完」，而是：

1. 该阶段的验收用例（见 §11、§9.7）全部通过；
2. `docs/` 里对应文档已同步（`server-protocol.md` 改接口、`ime-push-client.md` 改 App/插件契约）；
3. 资源指标回落到 §10 的目标值（或记录为什么没达到）。

#### 构建与测试命令

```bash
# 插件 dex（本机可做）
cd ime-plugin && bash tools/build.sh      # → out/dsh-kit.dex
cd ime-plugin && bash tools/test.sh       # JVM 测试，当前 69 项

# 服务端（venv 在仓库根的 .venv）
cd server && ../.venv/bin/python -m pytest tests -q
bash ime-plugin/tools/run-server.sh        # 本机联调用：HTTP 8000 + MQTT 1883

# Android（必须在有 SDK 的机器上，本机只有 platform-23）
cd android && ./gradlew assembleRelease

# Windows
cd windows/src-tauri && cargo test
```

#### 本机限制

- **Android App 不能在本机编译**（缺 compileSdk 37 与 build-tools），必须提交后在 PC 上构建，
  产物在 `android/app/build/outputs/apk/release/app-release.apk`；
- 插件 dex 可以在本机构建，但**升级 dex 后必须强停 `com.nirenr.trime`** 才会重新加载；
- 数据库不能降级：`todo.db` 已是 Room v2，阶段 8 会升到 v3，之后回退旧 APK 会直接崩溃。

#### 排障入口

| 现象 | 先看哪里 |
| --- | --- |
| 插件连不上 | `/data/user/0/com.nirenr.trime/files/dsh-kit/logs/kit-YYYYMMDD.log` |
| App 通道状态 | 设置 → 提醒 → 数据通道；DB 的 `meta` 表（`dsh_ime_status` 等） |
| 服务端投递 | `GET /v1/dsh/state`；阶段 13 之后看 `/v1/dsh/metrics` |
| 通知重复/丢失 | 依次查 `msg_id`（服务端幂等）→ ack → `dsh_inbox` → `dsh_claim`（§8.1） |

## 1. 现状盘点：链路上的薄弱点

编号用于后续设计和验收对照。**P0 会直接导致丢通知或卡死，P1 是明显的资源或稳定缺陷，
P2 是可观测/安全/可维护问题。**

### 1.1 服务端

| 编号 | 位置 | 问题 | 级别 |
| --- | --- | --- | --- |
| S1 | `features/dsh/reminder.py:66` `latest_task_fields` | 每 `dsh_reminder_interval_s`（默认 30s）对 `op_log` 全表做 `ROW_NUMBER()` 窗口扫描求 LWW，代价 O(全部 task 历史 op)。`op_log` 只增不减，运行越久越慢 | P0 |
| S2 | `broker.py:359` `publish_nudge` | 每次 push 额外执行一次 `pending_unacked` 的 `COUNT`。高频推送时是写路径上的额外读放大 | P2 |
| S3 | `broker.py:462` `_enqueue` | 同一设备连续 N 次 push 会入 N 个 nudge；设备被唤醒 N 次、发 N 次 pull（每次 HTTP + DB）。无「按设备合并」 | P1 |
| S4 | `broker.py:471` `_publish_loop` | 单消费者串行发布；一次 `broadcast_message` 阻塞，全队列排队。队列 2048 满即丢，只有一条 warning | P2 |
| S5 | `broker.py:492` `_cleanup_loop` | 过期清理 1 小时一次；TTL 默认 72h，过期前消息一直占着表 | P2 |
| S6 | `infra/locking.py:14` | 多进程靠 `ProcessLock` 硬拒绝，表现是启动崩溃 + uvicorn 重启循环，运维看不到原因 | P2 |
| S7 | — | 只暴露 `/v1/dsh/state` 快照，无法做时延直方图/丢弃率告警 | P1 |
| S8 | `usecase.py:244` | 限流键是 `user_id`，多个第三方共用一个账号会互相挤 | P1 |
| S9 | — | 无审计：谁、何时、发了多少条、发给哪台 | P1 |

### 1.2 传输层

| 编号 | 位置 | 问题 | 级别 |
| --- | --- | --- | --- |
| T1 | `Session.java:53` `TICKET_MARGIN_MS` | 设备票据 TTL 10 分钟且**不持久化**，插件每次冷启动必须一次 HTTP 注册/续期才能连 MQTT；每次重连也要先续期 | P1 |
| T2 | `Session.java:452` `scheduleReconnect` | 退避 2s→60s 无 jitter，多设备断网恢复会同时冲击服务端 | P1 |
| T3 | `DshMqtt.kt:84` | `isAutomaticReconnect=true` + 固定 `ticket`。断线超过票据有效期后自动重连必然认证失败，且代码里没有「重连失败 → 重新注册」的路径 → 通道永久卡死 | P0 |
| T4 | `broker.py:50` `_format_bind` | 双栈已修（`[::]` + 必要时补 `0.0.0.0`） | 已解决 |
| T5 | — | 未配 TLS 时票据在公网明文传输 | P2 |

### 1.3 Android App

| 编号 | 位置 | 问题 | 级别 |
| --- | --- | --- | --- |
| A1 | `DshManager.kt:342` `pullUntilIdle` + `DshReminderBridge.kt:37` | **先 ack 再消费，且没有任何本地持久副本**。消息推进内存 `SharedFlow` 后立即 ack；「取到」与「展示」之间进程死亡、或协程处理不过来，这条消息已 ack 且没有第二份 → 永久丢失 | P0 |
| A2 | `DshManager.kt:63` | `MutableSharedFlow(extraBufferCapacity=128)` 无 replay；突发超过 128 条时 `tryEmit` 返回 false，返回值被忽略 → 事件静默丢失（而数据已经 ack） | P0 |
| A3 | `DshManager.kt:237` `startPolling` | `pollJob` 挂在 DshManager 自己的 `scope` 上，一旦 `start()` 就**永久 60s 轮询**，与 Activity/ViewModel 生命周期无关、也不管 MQTT 是否在线 → App 退后台、IME 模式下都在打服务端 | P1 |
| A4 | `DshManager.kt:375-382` | 每次 pull 无条件写 `CURSOR`、`LAST_PULL_AT` 两条 meta，即使一条新消息都没有 | P1 |
| A5 | `DshReminderBridge.kt:48` | 只处理 `type=reminder` 的 notify；通用通知在 App 模式完全不可见（只有 IME 模式的插件会展示） | P0 |
| A6 | `DshManager.kt:188` `noteImeStatus` | 插件每次状态变化都写一条 meta | P2 |
| A7 | `DshMqtt.kt:128` `reachableHost` | 连接路径上做同步 TCP 探测，最坏 2×1.5s 阻塞 | P2 |
| A8 | `KeepAliveService.kt` | 前台服务存在但不承载通道：`KeepAliveManager`/`HeartbeatReceiver` 完全不碰 dsh | P1 |
| A9 | `DshManager.kt:247` | 每次 `boot()` 都重新注册设备（HTTP），虽然幂等 | P2 |
| A10 | — | 没有本地投递队列：没有「先落库再 ack」的概念 | P0 |
| A11 | `DshReminderBridge.kt:36` | `@Singleton` 的 `start()` 没有幂等保护；每次新建 `AppViewModel`（Activity 重建、切回前台）都会多挂一个 collector，同一条消息被并发处理多次 | P1 |
| A12 | `DshManager.kt:343` | `pullUntilIdle` 首行 `if (!enabled) return`。进程被 provider 拉起时通道通常还没 `start()`，插件转发的 nudge 变成空调用。主通道在线时这是**无害**的（插件自己会 pull），但降级通道激活后这里必须能冷启动 | P1 |
| A13 | `DshApi.kt:29` | `revoke()` 已定义但全仓库零调用 —— 用户没有任何入口能撤销插件权限。另外插件仍保留账号密码与明文 `refresh_token` 两条退路，不收口的话撤销设备凭证等于没撤（§5.5） | P0 |


### 1.4 输入法插件

| 编号 | 位置 | 问题 | 级别 |
| --- | --- | --- | --- |
| P1 | `Kit.java:92` | 每个 Lua 入口新建 `DexClassLoader` → 新 Kit 类 → `INSTANCE` 为 null → 新建 `Scheduler`（一条线程）+ 一堆对象。secondary 实例**永不 `shutdown()`**，线程随打开工具页次数累积 | P0 |
| P2 | `Session.java:55` `SAFETY_PULL_MS` | 每 120s 无条件 pull，哪怕 nudge 一直工作正常 | P1 |
| P3 | `NotifyModule.java:180` `record` / `markSeen` | 每条通知对 `notify_seen`(200) 和 `notify_inbox`(50) 各做一次全量 List 复制 + 全量 JSON 序列化写盘 | P1 |
| P4 | `Log.java:74` | 每行日志 open/write/close；`log.i("session", "入站消息 …")` 每条消息一行。轮转只留 1 份 512KB | P1 |
| P5 | `Session.java:340` `attempt` | 冷启动必先 HTTP 注册/续期（票据不持久化），断网时无限重试且收不到通知 | P1 |
| P6 | `Session.java:452` | 重连退避无 jitter | P1 |
| P7 | `NotifyModule.java:56` | 非 `notify` kind 一律 ack 丢弃，第三方无法扩展语义 | P2 |
| P8 | `NotifyModule.java:143` | claim 走 ContentProvider，同步阻塞在 pull 线程上，App 进程被拉起时首条通知会明显慢 | P2 |
| P9 | `Session.java:785` | pull 逐条 apply，每条消息一次展示 + 两次写盘，无批量 | P1 |
| P10 | `Kit.java:192` `cmdReceiver` | 广播 `com.dshkit.CMD` 不校验发送方，任何本机 App 都能发 `dsh.reconnect` / `clip.set` 操纵插件（§6.7 要求同时收紧 App 侧 `setPackage`） | P1 |

### 1.5 端到端

| 编号 | 问题 | 级别 |
| --- | --- | --- |
| E1 | 没有 at-least-once 保证（A1/A10） | P0 |
| E2 | App 与插件各有一套解析/展示逻辑，行为不对称（A5/P7） | P0 |
| E3 | 没有端到端时延指标 | P1 |
| E4 | `msg_id` / `seq` / `dsh_claim` 三层去重的交互没有文档化，容易出现「该显示的没显示」 | P1 |

### 1.6 账号态（详见 §9）

| 编号 | 位置 | 问题 | 级别 |
| --- | --- | --- | --- |
| A15 | `AccountManager.kt:103` | 换账号判定依赖 `serverUrl\|userId`，地址任一字符差异即静默 `resetForAccount()` 清空 op_log，无确认 | P0 |
| A16 | `OpRepositoryImpl.kt:207` | `resetForAccount()` 的备份路径返回值被调用方丢弃，用户无从得知备份存在 | P1 |
| A17 | `TasksViewModel.kt:138` | 登录过期只靠短提示，界面还承诺「本地修改会稍后推送」；没有未同步条数 | P0 |
| A18 | `SessionManager.kt:49` | refresh 网络失败时返回过期 token → push 401 → `sessionManager.clear()` 连有效 refresh_token 一起删 | P0 |
| A19 | `SyncEngine.kt:186` | 400/403/413 直接 `markRejected` 永久丢弃 op，临时性 403 也会让修改作废 | P1 |
| W1 | `commands.rs:1386` | 桌面端与 A15/A16/A19 同构 | P0 |

## 2. 设计原则

0. **主通道自持**。输入法插件是主通道，它的接收、落盘、展示、重试一律在自己的进程内完成，
   关键路径上不允许有对 App 的同步调用。App 只提供凭证、数据同步、以及插件缺席时的兜底。
   （理由见 §3.0，这是全文最重要的约束。）
1. **送达与展示分离**。送达 = 消息进入客户端本地持久队列；
   展示 = 从队列取出并交给系统通知。ack 只表示「已落盘」，不表示「已展示」。
2. **本地闹钟是基准，推送是加速**。任务提醒的准时性不依赖网络；
   推送负责跨设备、远程触发与第三方来源。两条路径最终由 `dsh_claim` 收敛。
3. **静态零流量**。没有待处理事项时，客户端不应产生周期性网络请求。
   连接本身用心跳保活（MQTT keepalive），但不为拉数据而轮询。
4. **推拉分离**。MQTT 只承载「有变化」的信号（不带业务载荷），数据永远走 pull。
   信号可以丢，数据不能丢。
5. **单实例、单写者**。网络、剪贴板、通知在一个进程里只有一个所有者；
   所有本地存储在单一写者上串行。
6. **宁重勿丢**。去重是优化，不是正确性依赖。任何「拿不准对方是否已展示」的时刻，
   选择展示（最坏重复一次）而不是跳过。
7. **每个决策都可观测**。丢弃、降级、跳过都要有计数，且能在面板/`/v1/dsh/state` 看到。
8. **实时性优先于省电**（§0.1）。两条通道都保留长连接能力；省资源只针对空闲期的无效动作。

## 3. 目标架构

### 3.0 设计前提（这一条决定全文）

**这不是一条对称的双端链路，而是一条分级链路。**

原始设计意图：输入法插件是**主通道**，App 是**降级通道**，因为两者在 Android 上的生存能力根本不同。

| | 输入法插件（主通道） | App（降级通道） |
| --- | --- | --- |
| 进程保活 | 系统绑定 IME 服务（`bindService` + 输入法优先级），是整机上最难被杀的一类进程 | 进程随时可能被回收，只能靠前台服务 + `START_STICKY` 争取 |
| 权限 | 宿主已有 `SYSTEM_ALERT_WINDOW` / `POST_NOTIFICATIONS`，可直接弹悬浮窗、发通知 | 同样权限，但进程不在时无从触发 |
| 可用能力 | 剪贴板同步**只有**插件能做（`ClipSource` 挂在输入法进程） | 可能不具备剪贴板同步能力 |
| 生命周期 | 用户一旦启用就一直活着，直到主动关闭 | 只在用户使用 App 或前台服务存活期间活着 |

**因此本设计的第一原则是「插件自持」**：主通道的接收、落库、展示、重试，
**一律不经过 App 进程**，不依赖 ContentProvider 能把 App 拉起来。
App 的存在只为了三件事：签发凭证、数据同步、以及插件缺席时的降级兜底。

一条推论，也是本次设计的核心取舍：
**「绝对稳定」只在主通道上成立；降级通道的目标是「不漏得太久」，不是「和主通道一样可靠」。**
把资源投在让降级通道追上主通道，是错的方向。

### 3.0.1 信任与版本模型

**按「插件可信」设计。** 插件不做完整性校验、不做能力降级，和 Windows 客户端一样
属于可信代码。三端只剩分工差别：

| | App | 输入法插件 | Windows 客户端 |
| --- | --- | --- | --- |
| 数据权威 | ✅ 唯一的 op_log 与同步引擎 | ❌ 只调 provider 读写，不持有数据 | ✅ |
| 通知通道 | 降级通道 | 主通道 | 主通道/对等 |
| 剪贴板 | 不可用 | 承担（双向） | 承担 |

**App 不做分发，插件由用户自己安装。** 这是刻意的边界：

- App 不申请存储权限、不写 `/sdcard`、不改输入法主题文件；
- 插件（dex + 四个 Lua 入口 + 主题钩子）由用户按安装说明自己放到
  `/sdcard/Documents/rime/`，和现在的部署方式一致；
- App 只在设置页提供**状态与指引**：ping 通就显示「已安装 · v0.3.x」（版本来自 ping 应答），
  没检测到就显示安装说明。检测靠插件主动上报，App 不扫描文件系统。

这样 App 的职责收敛成三件事：账号、数据同步、降级通道。少一个权限面，
也少一条「App 升级导致插件被动换版」的耦合。

#### 版本协商（替代「版本绑定」）

插件独立分发会带来版本漂移：用户可能装了旧插件配新 App。处理方式：

1. 插件在 `dsh_status` 里带上 `version`（dex 版本）与 `protocol`（协议版本）；
2. App 收到后与自己的期望值比对：
   - `protocol` 相同 → 正常；
   - 插件 `protocol` 偏旧 → 设置页提示「输入法插件版本过旧，请更新 dex」，
     并降级使用（只走协议子集，不调新方法）；
   - 插件 `protocol` 偏新 → 提示「App 版本过旧」，App 侧忽略不认识的能力，不报错。
3. 协议只增不改：新能力都放在新方法/新字段里，旧端不认识就跳过。
   这条是硬性约定，比任何版本号都可靠。

#### 插件自检 App 是否存在

插件依赖 App 发凭证。App 被卸载或未登录时，插件会反复重试授权、刷日志、空转。
所以插件启动时先查 `com.todo.android` 是否安装：
没装就停在「未授权」状态、不建连接、不做定时重试，只在面板显示原因。
这是目前缺失的一环（`Session.start()` 拿不到授权就会一直按退避重试）。

### 3.1 通道分级与职责

| 职责 | 主通道（插件在） | 降级通道（插件缺席） |
| --- | --- | --- |
| MQTT 长连接 | 插件持有 | App 自己持有 |
| pull / ack | 插件 | App |
| 本地持久队列 | 插件自己的存储（增量写） | App 的 `dsh_inbox`（Room） |
| 通知展示 | 插件直接展示 | App 前台服务 / 打开 App 时展示 |
| 剪贴板同步 | 插件（双向） | **不可用** |
| 任务提醒准时性 | 系统闹钟（App 设的 `AlarmManager`，两个通道共享，离线也能响） | 同左 |
| 数据同步（op） | App（插件只转发 `sync` 信号） | App |

两条通道**以互斥为目标**：稳态下只有一条持有 MQTT 连接，避免双份流量。
但切换允许**短暂重叠**（几百毫秒级），因为追求「任何时刻都恰好一条」会引入切换空档，
与 §0.1 的实时性优先冲突。切换判据是「插件是否真的响应」，不是「是否设成默认输入法」，
具体协议见 §6.7。

```
                    ┌─ 主通道：输入法插件（系统保活，自持）
服务端 ──► MQTT ────┤
                    └─ 降级通道：App（前台服务/闹钟兜底）

        两条通道各自完成：pull → 本地持久化 → ack → 展示
        跨通道只做一件事：dsh_claim（best-effort 去重，超时即放弃，见 §8.1）
```

### 3.2 为什么不做「共享队列」

曾经考虑过让插件把消息写进 App 的 `dsh_inbox` 表，共用一套持久化和展示逻辑。
**否决**，理由正是用户澄清的设计意图：

- 这会把主通道的 at-least-once 绑在「App 进程能被 provider 拉起」上，
  而 App 进程恰恰是链路上最不可靠的一环 —— 等于用弱的一环给强的一环做保证。
- App 进程被拉起时要跑完 Hilt 初始化、Room 打开、设置读取，
  在主通道的收消息路径上引入数百毫秒到数秒的不确定延迟。
- App 崩溃会连带主通道丢消息。

替代方案：**各自持久化，只在展示前做一次轻量认领**。
认领失败或超时不阻塞展示（§8.1），去重是优化，不是正确性依赖。

```
第三方/服务端业务
   │  POST /v1/dsh/push {kind:"notify", ...}         ← 幂等: msg_id
   ▼
[服务端] dsh_messages 落库 ──► nudge 队列(按设备合并) ──► MQTT publish {"t":"nudge"}
   │                                                          │
   │  (设备离线) TTL 内保留                                    ▼
   │                                              [主通道] 插件 MQTT 收到信号
   │                                                          │
   │                                                          ▼
   │                                                    pull(HTTP, 游标)
   │  ◄───────────────────────────────────────────────────────┘
   ▼
[插件] 本地持久队列 ──► ack ──► 插件直接展示 ──(best-effort)──► dsh_claim
                                                          │
[降级通道] App: pull → dsh_inbox → ack → 展示 ──────────────┘
```

**职责边界**：插件自持「连接 + 落库 + 展示」，App 拥有账号、凭证与数据同步。
插件的关键路径上**禁止**出现对 App 的同步调用（provider 只用于授权、状态回写、认领这三件事，
且都可以失败）。

## 4. 服务端设计

### 4.1 提醒扫描改增量索引（S1，P0）

现状：每 `dsh_reminder_interval_s`（默认 30s）对 `op_log` 全表做 `ROW_NUMBER()` 窗口扫描，
按 `(workspace, task, field)` 分区取 LWW 最新值。`op_log` 只增不减，越跑越慢。

#### 索引表结构

```sql
CREATE TABLE dsh_remind_index (
  workspace_id TEXT NOT NULL,
  task_id      TEXT NOT NULL,
  -- 每个字段独立维护「值 + 该值的 HLC」，不能共用一个 HLC
  remind_at    TEXT,
  remind_hlc   TEXT,
  title        TEXT,
  title_hlc    TEXT,
  status       TEXT,
  status_hlc   TEXT,
  deleted      INTEGER NOT NULL DEFAULT 0,
  deleted_hlc  TEXT,
  PRIMARY KEY (workspace_id, task_id)
);
CREATE INDEX idx_dsh_remind_due ON dsh_remind_index(remind_at);
```

`remind_at` 存 **RFC3339 原文**，不存减过 lead 的时间戳 —— `dsh_reminder_lead_s`
是全局可配的，预减会让改配置后整张索引错位。

#### HLC 全序与比较规则（必须严格按这个来）

排序键与 `op_log` 现有 LWW 规则完全一致，四元组从高到低：

```
physical_ms DESC, counter DESC, node_id DESC, op_id DESC
```

落库时编码成**一列可比较的字符串**，字典序 = 全序（省掉四列比较的麻烦）：

```sql
-- 生成：physical_ms 13 位补零，counter 6 位补零，node_id / op_id 原样
printf('%013d:%06d:%s:%s', physical_ms, counter, node_id, op_id)
```

**逐字段独立比较**，这是关键 —— 一个 op 只改 `title`，另一个只改 `remind_at`，
两者互不覆盖：

```sql
-- 收到 field=remind_at 的 op 时
INSERT INTO dsh_remind_index (workspace_id, task_id, remind_at, remind_hlc)
VALUES (?, ?, ?, ?)
ON CONFLICT(workspace_id, task_id) DO UPDATE SET
  remind_at  = excluded.remind_at,
  remind_hlc = excluded.remind_hlc
WHERE excluded.remind_hlc > COALESCE(dsh_remind_index.remind_hlc, '');
```

**`COALESCE` 不能省**：字段从未被写过时 `remind_hlc` 是 NULL，
而 SQL 里 `x > NULL` 求值为 NULL（既不真也不假）→ `WHERE` 不成立 → **首次写入会被吞掉**。
用空串兜底后，任何非空 HLC 都能覆盖「未写过」的状态。

`WHERE` 子句保证**只有全序更大才覆盖**：乱序到达的旧 op 会被自动丢弃，
这与 `op_log` 的 LWW 语义一致，不会因为「后到」而覆盖「更新」。

#### 维护点与需要补的规则

维护位置：`sync_push.usecase.push` 在 op 被接受后（与现有 `schedule_snapshot_if_due` 同处），
只处理 `entity_type='task'` 且 `field IN ('remind_at','title','status','__deleted')` 的 op。

需要明确规定、现在没写的四条规则：

1. **乱序 op 不覆盖**：靠上面 `WHERE ... > COALESCE(...,'')` 保证。
   `NULL` HLC（字段从未被写过）视为最小，任何 op 都能覆盖 —— 这依赖 `COALESCE`，
   不能写成裸比较（否则首次写入永远失败）。
2. **删除用软删除标记**：`__deleted=true` 只置 `deleted=1` + `deleted_hlc`，
   不删行 —— 否则同一 task 后来被恢复（`__deleted=false`）时索引里没有行可更新。
   恢复时按 HLC 比较决定是否清回 0。
3. **task 跨工作区移动**：现在模型不允许移动，索引以 `(workspace_id, task_id)` 为主键即可。
   若将来支持移动，必须同时删除旧 `(ws_old, task_id)` 行 —— 设计里显式记一笔，免得以后踩。
4. **迟到 op 的补投**：客户端可能离线数天再 push 一批旧 op。上面两条已经覆盖：
   索引按 HLC 取最新，旧 op 自动被丢弃，**不需要按时间窗口过滤**。

#### 扫描查询

`lead` 在查询时减，不预存：

```sql
SELECT workspace_id, task_id, remind_at, title
FROM dsh_remind_index
WHERE deleted = 0 AND status <> 'done'
  AND remind_at IS NOT NULL
  AND CAST(strftime('%s', remind_at) AS INTEGER) * 1000 - :lead_ms <= :now
  AND CAST(strftime('%s', remind_at) AS INTEGER) * 1000 - :lead_ms >  :now - :window
```

> `remind_at` 是 RFC3339 文本，SQLite 的 `strftime` 不能直接解析带时区偏移的字符串。
> 实现时二选一：① 额外存一列 `remind_ms`（解析后的毫秒值，**不减 lead**），查询走它；
> ② 在 Python 侧解析。**推荐 ①**，索引列可比较、查询能走 `idx_dsh_remind_due`。

#### 回填（只跑一次）

迁移时用现有 `latest_task_fields()` 的窗口函数算一次全量，逐行写入索引表，
之后不再碰 `op_log`。回填要**按 workspace 分批**（每批 500 个 task），
避免长事务锁住写入。

#### 验收

- `op_log` 10 万行时，单次 sweep 的 SQL 耗时 < 10ms（当前随行数线性增长）；
- 乱序 op 测试：先写 HLC 较大的 op，再写 HLC 较小的，断言索引值不变；
- 多字段独立：只改 `title` 的 op 不影响 `remind_at` 的 HLC。

### 4.2 nudge 按设备合并（S3，P1）

`publish_nudge` 改为写入「每设备一个待发位」：

- `_pending: dict[device_id, seq]`（只保留最大 seq），由一个 100–200ms 的合并窗口冲刷；
- 同一设备在窗口内的 N 条通知只产生 1 个 nudge；
- 设备 pull 一次就能拿到全部（`limit=100`，最多 20 页）。

**为什么不直接放大 nudge 队列**：客户端每收一个 nudge 就发一次 HTTP pull，
N 条通知 = N 次往返，合并后是 1 次。这是链路里最大的一处无效网络开销。

验收：连推 50 条 → 设备侧 pull 次数从 50 降到 1–2。

### 4.3 发布队列与背压（S4，P2）

- 队列从「每消息一条」改成「每设备一个槽」，天然不会因批量推送打满；
- `broadcast_message` 单独 20ms 超时（`asyncio.wait_for`），超时就丢弃并计数，
  不让一个卡住的会话拖住整条队列；
- `nudges_dropped` 从计数升级为**指标 + 阈值告警**（连续 1 分钟丢弃 > 0 即 warn）。

### 4.4 消息保留与清理（S5，P2）

- 清理周期从 1h 降到 10min，单轮批量 500→2000（SQLite 单写者下按事务分批）；
- 新增「未确认消息总量上限」：单用户未确认超过 `dsh_msg_max_pending × 设备数` 时
  按最旧淘汰（已有 `trim_pending`，扩展到广播消息）；
- 明确 TTL 语义：TTL 到 = 允许丢弃，不是「必须投递」。文档补进 §15.5。

### 4.5 单进程护栏（S6，P2）

`ProcessLock` 失败时不要抛裸 `RuntimeError`：在 `create_app` 之前检测，
打印明确的中文错误并 `sys.exit(2)`，避免 uvicorn 重启风暴。
同时 `dsh_mqtt_enabled=true` 时在启动日志里显式打印「broker 与 HTTP 必须同进程」。

### 4.6 指标与审计（S7/S9，P1）

新增 `GET /v1/dsh/metrics`（鉴权同 `/v1/dsh/state`），返回：

```json
{
  "push_total": 12031, "push_duplicate": 12, "push_rejected": 0,
  "nudge_enqueued": 9800, "nudge_coalesced": 2100, "nudge_dropped": 0,
  "deliver_latency_ms": {"p50": 180, "p95": 1400, "p99": 3900},
  "pull_total": 14000, "pull_empty": 9200,
  "devices_online": 2, "queue_depth": 0,
  "reminders": {"sweeps": 2880, "fired": 140, "last_sweep_ms": 4}
}
```

`deliver_latency_ms` 的测量口径：push 被接受 → 收到客户端 ack（`created_at` 与
`dsh_acks` 的时间差）。这是唯一可信的端到端指标，必须有。

审计：`dsh_messages` 增加 `source_kind`（`api` / `reminder` / `internal`）与
`source_ref`（调用方标识，第三方 API 落地后填 key id），只记元数据不记内容。

### 4.7 为对外 API 预留（S8，P1）

本轮不改鉴权模型，但把限流键从 `user_id` 改成「`调用方标识 + user_id`」的复合键，
调用方标识暂时恒为 `account`。这样第三方 API 落地时只需填真实 key id，不用再改限流实现。
配额参数放进 `dsh_push_rate`（每分钟）与新增的 `dsh_push_daily`（每天）两级。

## 5. 传输层设计

### 5.1 票据生命周期（T1，P1）

- 票据 TTL 从 10 分钟提到 **60 分钟**（`dsh_ticket_ttl_min` 保持可配，默认改 60；**值待确认**，见 §13）；
  MQTT 长连接只在 CONNECT 时校验票据，连接存续期间不受影响，60 分钟足以覆盖常规重连；
- 客户端把「device_id + ticket + 过期时刻」持久化到本地（插件用 `Secret` 加密存 prefs，
  App 用加密 meta），冷启动时**先试旧票据直连，失败再走 HTTP 续期**；
- 目标：正常冷启动（票据未过期）**零 HTTP 请求**即可恢复收通知。

### 5.2 重连退避带 jitter（T2/P6，P1）

统一算法（两端同参）：

```
delay = min(base * 2^n, 60s) * (0.7 + random()*0.6)
```

- `base = 2s`，n 为连续失败次数；
- 成功一次连接后 n 归零；
- 另外加「网络变化立即重试一次」：`NetWatcher` 的 `CONNECTED` 事件把 n 归零并立即 attempt
  （现在是直接 kick，没有和退避协同）。

### 5.3 MQTT 自动重连与票据刷新（T3，P0）

**问题**：Paho `isAutomaticReconnect` 会用建连时的 `MqttConnectOptions` 重连，
密码是当时那张票据，过期后必然 421/401，而 Paho 会一直退避重试，我们却以为「在重连」。

**方案**：关掉 `isAutomaticReconnect`，自己接管重连：

- `connectionLost` → `scheduleReconnect()`（带 jitter）；
- 每次重连前解析票据过期时刻，若已过期先续期再连；
- 连续失败 3 次后判定为「凭证层失效」，强制走一次完整续期；
- 续期仍失败 → 状态置 `auth_failed`，在设置页显式提示「需要在 App 里重新授权」，
  而不是永远显示「未连接」。

### 5.4 双栈（T4）

已实现（服务端 `[::]` + 必要时补 `0.0.0.0`；两端 IPv6 优先回落 IPv4）。
本轮只补两件事：

- 客户端把「实际选中的地址族」写进状态，便于排查（`endpoint.family = "ipv6"|"ipv4"`）；
- 服务端启动日志打印实际绑定的地址清单。

### 5.5 凭证撤销与恢复（新增，P0）

**威胁模型**：插件本身可信，但它**持有的数据可能被偷** —— 备份提取、adb、root、
内存 dump。用户必须能随时一键断掉。

**撤销的权威在服务端，不在客户端。** App 只负责发起和展示状态，真正的拦截由服务端做；
这样插件无法用自愈机制绕过（自愈的每一步都要问服务端，而服务端一律拒绝）。

#### 服务端已经具备的条件（已核实，无需改动）

| 接口 | 对已撤销设备的响应 |
| --- | --- |
| `POST /v1/dsh/devices/{id}/revoke` | `mark_revoked` + 清 `credential_hash` + 踢掉在线连接（`runtime.revoke`） |
| `POST /v1/dsh/devices/{id}/credential`（App 签发） | `409 device_revoked`（`usecase.py` `issue_credential`） |
| `POST /v1/dsh/devices/refresh`（插件续期） | `409 device_revoked`（`usecase.py` `refresh_device`） |
| `POST /v1/devices/register`（复用旧 id） | `409 device_revoked` |
| MQTT 鉴权 | revoked 集合在 broker 内立即生效 |

所以撤销之后，插件的每一条自救路径都会撞墙，**不需要在 App 端加拦截标记**。
App 端只需要一个用于界面展示的状态字段（「已撤销」），它不参与拦截。

#### App 侧：一个按钮 + 一条禁令

**按钮**：设置 → 数据通道 → 「撤销输入法权限」。点击后：

1. 调 `POST /v1/dsh/devices/{ime_device_id}/revoke`（`DshApi.revoke()` 已存在，零调用方）；
2. 清本地 `credential` / `credential_exp` / `ime_status`；
3. 记一个 `dsh_ime_revoked_at` 仅用于界面显示；
4. **不要删除 `dsh_ime_device_id`**。

第 4 条是硬约束。`ensureImeDevice()` 的逻辑是「没有 device_id 就生成一个新的并注册」，
一旦撤销时把 device_id 删了，下次启动就会**造出一个全新设备并重新签发凭证**，
撤销当场失效。保留旧 device_id 的结果是：注册/签发/续期全部 409，撤消失效不了。

#### 插件侧：撤销后必须进终态

现在插件遇到 401/409 会 `clearCredential()` + `notifyCredentialInvalid()` + `scheduleReconnect()`。
对 `device_revoked` 这种**永久性**拒绝，重试没有意义，只会每 60 秒打一次服务端。

要求：`device_revoked` 单独处理 —— 清本地凭证与 device_id、停止连接、停止一切定时任务，
进入 `revoked` 终态，面板显示「已在 App 中撤销推送权限」。只有用户重新启用后才离开。

#### 前置条件：插件不能持有比设备凭证更大的凭证

这是全文里最容易忽略的一环，因为**它会让撤销完全失效**：

| 插件本地有什么 | 位置 | 危险度 | 撤销能否管住 |
| --- | --- | --- | --- |
| 设备凭证 | `prefs.json`，`Secret.seal()`（Android Keystore AES/GCM） | 中 | ✅ 能 |
| **refresh_token** | `prefs.json` 的 `refresh` 键，**明文** | **高**（账号级） | ❌ 不能 |
| **账号密码** | `dsh.d/config.json`，**明文** | **最高** | ❌ 不能 |

偷到 `refresh_token` 或账号密码的人可以：换 access token → 自己注册一台新设备 →
或者直接调 `/v1/sync/pull` 把整个账号的数据拉走。这时撤销那台 `dsh-ime-*` 毫无意义。

所以本次要一并收口（属破坏性变更，需要给迁移提示）：

1. **插件只走设备凭证一条路**。`Session.ensureToken()` 里的 `cfg.autoLogin()` 分支废弃，
   `refresh_token` 不再落盘（`saveTokens()` 里删掉 `K_REFRESH` 的写入）；
2. `config.json` 的 `username` / `password` 标记为废弃；读到就警告一次
   「账号密码已明文保存在 config.json，请删除，改用 App 授权」；
3. 已存在的旧 `refresh_token` 在升级后的首次启动里清除。

做完这三条，撤销才真正等于「插件这一侧的通道彻底断掉」。

#### 撤销失败的兜底

断网时点撤销，要区分两个状态，不能混成一个「已撤销」：

| 状态 | 存储 | 界面 | 含义 |
| --- | --- | --- | --- |
| 待确认 | `dsh_ime_revoke_pending=1` | 「已撤销 · 待同步到服务端」 | 本地已停发，但服务端还没收到，被偷的凭证**仍然有效** |
| 已确认 | `dsh_ime_revoked_at=<ts>` | 「已撤销」 | 服务端已吊销，凭证立即失效 |

pending 状态进重试队列，App 下次联网补调，成功后再落 `revoked_at` 并清 pending。
**pending 期间不允许「重新启用」** —— 必须先完成撤销或显式取消，否则会出现
「旧设备没撤干净又发了一张新凭证」的空档。

#### 恢复（重新启用）

撤销是终态，恢复必须显式操作：

1. 用户点「重新启用输入法通道」；
2. 旧 `device_id` 已 revoked 且不能再次注册（409），所以 App **生成一个新的 `dsh-ime-*`**；
3. 注册 + 签发；插件下次 `dsh_authorize` 拿到新的并恢复。

> 注意：App 重装或清数据后 `IME_DEVICE_ID` 会丢，也会生成新设备。这不算漏洞 ——
> 旧设备仍是 revoked，被偷的旧凭证永远无效；只是多了一台新设备而已。

#### 撤销的范围要讲清楚

推送与剪贴板共用同一张设备凭证，所以撤销是**整体**的：通知推送和剪贴板同步一起停。
没有「只停推送保留剪贴板」的粒度。界面上必须写明。

## 6. Android App 设计（降级通道）

本章的一切都以 §3.0 为前提：**App 是降级通道**。目标不是把 App 做得和插件一样可靠，
而是「插件不在时，通知不要漏得太久，并且用户能知道现在是降级状态」。

因此本章优先做三件事：不丢（落库）、能显示（统一通知处理）、少浪费（自适应轮询）；
不追求把前台服务做到系统杀不掉。

### 6.1 消息先落库再 ack（A1/A2/A10，P0）

**当前为什么不丢、又为什么不可靠**：`enabled=false` 时根本不 pull（A12），
所以「无人消费还被 ack」的情形暂时不会发生；但一旦通道启动过，链路就退化成
「内存 buffer + 已 ack」，任何一次进程死亡或突发溢出都是真丢。
下面把它变成结构上不可能丢。

新增表（Room v3）：

```kotlin
@Entity(tableName = "dsh_inbox")
data class DshInboxEntity(
    @PrimaryKey val msgId: String,
    val seq: Long,
    val kind: String,
    val payload: String,      // 原始 JSON
    val receivedAt: Long,
    val displayedAt: Long?,   // null = 未展示
    val displayResult: String?,// shown / skipped / failed
    val attempts: Int,
    val expiresAt: Long,
)
```

`pullUntilIdle` 的新顺序：

1. `pull` 拿到一页；
2. **`inboxDao.insertIgnore(page.messages)`（一个事务）**；
3. 事务提交成功 → 把这一页的 seq 一起 `ack`；
4. 推进游标（内存 + 节流写盘，见 §6.4）；
5. `_messages` 从「传输通道」变成「新消息提示」，**丢了也不影响正确性**——
   展示器改为从 `dsh_inbox` 读未展示记录。

这样得到 at-least-once：只要落库成功就一定会有一次展示尝试；
进程在 2 与 3 之间被杀 → 消息已落库未 ack → 服务端重投 → `insertIgnore` 幂等。

`_messages` 的语义降级后，A2 的静默丢弃不再致命。

### 6.2 消费方与进程解耦（A1，P0）

把「收消息 + 落库」放进 **`DshIngest`（无 ViewModel 依赖的单例）**，
由三个入口保证可达：

1. `KeepAliveService`（前台服务，`onStartCommand` 里 `DshIngest.attach()`）——降级通道的主入口；
2. `AppViewModel.bootstrap()`（用户打开 App 时）；
3. `ImeTaskProvider` 的 `dsh_nudge` / `dsh_sync`：**只在主通道缺席、App 自己在跑 MQTT 时才需要**。
   主通道在线时这个调用应当是一个空操作 —— 主通道自己会 pull，App 不必插手，
   更不应该因为它把 App 进程拉起来又跑一轮 pull（那是双份流量）。
   > 当前 A12 的 `enabled=false` 直接 return 反而接近正确行为，
   > 要改的是「让它在降级通道激活时能冷启动」，而不是「任何情况下都拉」。

`DshIngest` 在落库后通知「展示器」，展示器从 `dsh_inbox` 取未展示记录：

- **在线展示器**（App 进程在）：`DshReminderBridge` 升级为 `DshInboxPresenter`，直接读表展示；
- **补显**（进程刚被拉起、或上次展示失败）：`attach()` 时先跑一次 `flushPending()`，
  把 `displayedAt IS NULL AND expiresAt > now` 的记录展示掉（超过 10 分钟的只记为「已过期未展示」，
  避免开机弹一堆旧通知）。

### 6.3 统一通知处理（A5/E2，P0）

`DshInboxPresenter` 不再限定 `type=reminder`：

| 条件 | 行为 |
| --- | --- |
| `payload.type == "reminder"` | 走任务提醒：认领 `dsh_claim`，`notifier.notifyReminder`，回写 `task_local.lastFiredAt` |
| 其它 `kind == "notify"` | 通用通知：`title`/`body`/`url`/`level` 直接展示，不认领（没有任务语义） |
| `kind` 不认识 | 记 `displayResult=skipped`，仍然从表里标为已处理 |

展示规则（等级、免打扰、静音）与插件 `NotifyModule` 保持**同一份定义**：
把 `Levels` 的映射表抽成文档 §15.5 的单一来源，两端代码各自实现但共用验收用例。

### 6.4 自适应轮询（A3，P1）

把「固定 60s」换成状态机：

| 连接状态 | 周期 pull | 说明 |
| --- | --- | --- |
| MQTT 在线且 5 分钟内收到过 nudge | 不轮询 | 只靠信号 |
| MQTT 在线但 5 分钟无信号 | 15 分钟 | 慢兜底，防信号丢失 |
| MQTT 离线 / IME 模式无信号 | 2 分钟（前 15 分钟）→ 15 分钟 | 快速补投后降频 |
| App 在后台且前台服务未开启 | 15 分钟 + 由本地闹钟保证提醒准时 | 见 §6.5 |

`pullUntilIdle` 在**没有新消息**时不写 `CURSOR`/`LAST_PULL_AT`（A4）：
游标只在推进时写，`lastPullAt` 内存态 + 每 5 分钟最多落一次盘。

### 6.5 前台服务承载通道（A8，P1）

`KeepAliveService` 升级为「可选的通知通道宿主」：

- `onStartCommand` 里 `dsh.start()` + `dshIngest.attach()`；
- 服务被系统杀掉时，`START_STICKY` 会重拉 —— 正好是恢复通道的时机；
- 用户关掉「后台保活」时通道退回「打开 App 才收」，UI 上要说明这个代价。

### 6.6 设备注册与状态写入节流（A6/A9，P2）

- `ensureAppDevice` 缓存注册结果（`device_id` + `ticket` + `expiresAt`），
  未过期直接复用（与 §5.1 同一份持久化）；
- `noteImeStatus` 做脏检查：状态没变就不写 meta；
- `reachableHost` 的探测结果缓存 5 分钟（同一个 host:port 不重复探测）。

### 6.7 通道判活与互斥（新增，P0）

**当前的空窗**：`isTrimeDefaultIme()` 只看「trime 是不是默认输入法」，
据此决定 App 不连 MQTT（`DshManager.kt:209`）。但「设成默认输入法」≠「插件活着」：
插件 dex 加载失败、用户关掉了 dsh-kit 的通道开关、插件崩溃后没重启，
都会让 App 一直等着一个不存在的通道 —— **两条通道同时不工作，且没有任何提示**。

#### 为什么不用「插件定时上报心跳」

这是最直觉的做法，但会**反向消耗资源**：插件写心跳要调 App 的 ContentProvider，
而 Android 的 provider 调用**会把没运行的 App 进程拉起来**。主通道明明正常工作，
却每 60 秒唤醒一次 App —— 和「省资源」直接冲突，也和「App 不做多余的事」冲突。
而且心跳是「自己说自己活着」，卡死、半连接、通知渠道被系统关掉时它照样为真。

#### 方案：App 主动 ping（有序广播 + resultReceiver）

方向反过来 —— **由 App 去问插件**，而不是插件来喂 App：

```
App                                 插件（trime 进程）
 │  sendOrderedBroadcast(PING)          │
 │  resultReceiver, 超时 800ms          │
 ├─────────────────────────────────────►│  cmdReceiver 收到
 │                                      │  session 当前状态
 │◄─────────────────────────────────────┤  setResultCode(200) + JSON{"connected":true,"version":…}
 │  800ms 内收到 200 且 connected=true   │
 │  → 主通道活着，App 让出连接            │
 │  超时或没有 receiver                  │
 │  → 插件不在/不响应，App 接管           │
```

优点：

- **零常驻开销**：没有 ping 就没有任何动作，不像心跳那样空闲也在跑；
- **不会拉起 App**：方向是 App → 插件，插件本来就在跑或不在；
- **不会拉起插件**：动态注册的 receiver 只在进程活着时响应，
  插件进程死了就没人应答 —— 超时即判定不在，正是我们要的信息；
- **是「真问」而不是「自报」**：插件卡死时没人应答，App 能立刻发现。

实现要点：

- 复用现有广播 action `com.dshkit.CMD`，加 `op=ping`；插件主实例的 `cmdReceiver` 已注册；
- **广播必须双向限定**：App 侧 `intent.setPackage("com.nirenr.trime")`（显式指定接收方，
  也避开 Android 8+ 的隐式广播限制）；插件侧在 `onReceive` 里校验发送方 uid 属于
  `com.todo.android`，**非本 App 的指令一律丢弃**。现在 `cmdReceiver` 不校验发送方，
  等于任何本机 App 都能对插件发 `dsh.reconnect` / `clip.set`（§1.4 补一条 P10）；
- 应答里带 `connected`、`version`、`cursor`、`lastPullAt`，一次 ping 顺带完成版本协商（§3.0.1）；
- App 侧封一个 `probeIme(timeoutMs = 800)`，用 `suspendCancellableCoroutine` 包 `resultReceiver`；
- **ping 的时机**：App 启动/通道 boot、网络恢复、用户打开设置页、以及主通道"看起来在线"时每 15 分钟一次。

#### 互斥策略（宁可短暂双连，也不要空档）

按「实时性优先」（§0.1）：

| 情况 | 行为 |
| --- | --- |
| ping 成功且 `connected=true` | App 断开自己的 MQTT，只保留 15 分钟低频兜底 pull |
| ping 超时 / `connected=false` | App 立即建立自己的 MQTT（**不等下一次轮询**），界面切「降级通道」 |
| App 已连、之后 ping 又成功 | App 把自己的连接**降级为备用**（不断开，只是不再作为主展示源），等连续 3 次 ping 成功才真正断开 |

最后一条是刻意的：**不追求「任何时刻只有一条连接」**，因为那要求切换是瞬间完成的，
而 ping 有 800ms 超时窗口。允许短暂双连的代价是几百毫秒的两倍流量，
换来的是「永远不出现两条通道都不工作的空档」—— 与实时性优先一致。
双份投递由 `msg_id` 幂等 + `dsh_claim` 去重兜住（§8.1）。

- 状态文案区分三种：`主通道（输入法）` / `降级通道（App）` / `两条都不通`。
- 两条通道设备独立（`dsh-app-*` / `dsh-ime-*`），任一方卡住不影响另一方的游标。

## 7. 输入法插件设计（主通道）

本章的一条总纲：**插件自持**。接收、落库、展示、重试全在自己的进程里完成，
不把关键路径交给 App。provider 只出现在三处，且每处都允许失败：

| 调用 | 时机 | 失败后果 |
| --- | --- | --- |
| `dsh_authorize` | 首次需要凭证 | 用已持久化的凭证继续；没有则提示未授权 |
| `dsh_credential_update` | 续期后回写 | 只影响 App 下次签发的正确性，不影响当前连接 |
| `dsh_claim_reminder` | 展示任务提醒前 | **超时即自行展示**（§8.1） |

### 7.1 进程内单实例（P1，P0）

问题本质：`INSTANCE` 是类静态字段，而每个 Lua 入口都新建 `DexClassLoader`，
`Kit` 类是新的，静态字段重置为 null。

方案：把「单例判定」从类字段提升到**进程级持有**，二选一：

- **A（推荐）**：用一个约定的全局持有者，例如 `android.app.Application` 的
  `getSystemService` 槽（注册自定义 service name）或 `LuaApplication` 的静态字段里
  存 `System.identityHashCode` 并配合进程级 `FileLock`；
- **B**：`DexClassLoader` 本身做缓存 —— 所有 Lua 入口复用同一个 `DexClassLoader`
  实例（这样 `Kit` 类只有一个，`INSTANCE` 真正是单例）。

B 更彻底，改动在加载入口（`dsh.lua` / 工具页脚本 / 主题钩子里创建 loader 的地方），
需要确认宿主是否允许缓存 loader。**倾向 B，A 作为 B 不可行时的回退。**

另外，无论 A/B，`secondary` 实例都**不应创建 `Scheduler`**：
把 `Scheduler` 的创建推迟到 `primary` 分支（现在构造期就 `new`），
或者 secondary 构造完成后立刻 `sched.shutdown()`。

验收：连续打开工具页 10 次，`ps -T` 里 `dsh-kit` 线程数恒为 1。

### 7.2 自适应安全轮询（P2，P1）

`SAFETY_PULL_MS` 从固定 120s 改为与 App 相同策略（§6.4）：

- 最近 5 分钟收到过 nudge/连接正常 → 15 分钟兜底；
- 未连接 → 不做安全轮询（连接恢复时 `onOnline` 会立即 pull 一次）；
- 面板可见时保持 30s（用户在看着，需要及时）。

### 7.3 本地持久队列（不经过 App，P3/P9，P0）

主通道的 at-least-once 必须由插件自己的存储承担。现状是 `Store`（单文件 JSON）
存 `notify_seen`(200) + `notify_inbox`(50)，每条通知两次全量序列化重写整份文件 ——
既不省，也不满足「先落库再 ack」所需的「一次事务写」。

改为 **append-only JSONL**（不引入 SQLite，保持零新依赖）：

- 路径 `<files>/dsh-kit/queue/YYYYMMDD.jsonl`，每行一条记录：
  `{"op":"msg","seq":123,"msg_id":"…","kind":"notify","payload":{…},"at":…}`
  或 `{"op":"done","seq":123}`；
- **收消息 = 一次 append + fsync**（O(1)），落盘成功后才 ack；
- 启动时重放当天与前一天的文件重建内存状态（`done` 覆盖 `msg`），
  已完成且超过 24h 的文件直接删除；
- `notify_inbox` 的「最近 50 条」改为从队列文件的尾部读取，不再单独维护；
- `notify_seen` 用队列里的 `done` 记录即可，不需要再存 200 个 id。

好处：写放大从「每次全量重写」变成「每次一行追加」；崩溃后能精确知道哪些已展示、哪些没有；
而且这个队列天然就是「先落库再 ack」需要的东西。

验收：连收 20 条通知，插件写盘字节数从「20 × 整份 JSON」降到「20 行追加」，
且进程在任意时刻被杀，重启后未展示的消息都还在。

### 7.4 日志降噪（P4，P1）

- 每条消息的 `入站消息 …` 降为 debug 级，默认不写（配置 `log_level`）；
- `Log` 改为持有 `FileWriter` 复用（现在每行 open/close），并加 200ms 批量刷；
- 轮转保留 3 份，总上限 1.5MB。

### 7.5 持久化票据（P5，P1）

与 §5.1 配套：`device_id`、`ticket`、`ticket_expires_at` 存进 `Store`（ticket 用 `Secret` 加密），
冷启动先试旧票据。

### 7.6 未知 kind 的处理（P7，P2）

本轮不改行为，但改成**显式配置**：`config.json` 增加 `unknown_kind = "ack"`（默认）
/ `"keep"`。选 `keep` 时不 ack（消息留在服务端等插件升级后再处理），
避免第三方扩展语义的消息被老版本插件永久吞掉。

### 7.7 降级点的自我诊断（新增，P2）

主通道要能自己说清「我现在为什么不可靠」，否则用户只看到「未连接」：

- 状态里区分 `auth_failed` / `network_unreachable` / `broker_refused` / `credential_expired`；
- 面板顶部固定一行通道自检结论，例如
  「主通道正常（插件）」/「票据过期，请打开待办 App 重新授权」；
- 状态没变时，每 5 分钟最多写一行摘要日志，避免状态抖动刷屏。

## 8. 端到端一致性与可观测

### 8.1 单一展示者（E1/E4，P0）

三层去重的职责必须明确写入 `server-protocol.md` 与 `ime-push-client.md`：

| 层 | 键 | 作用 | 失效后果 |
| --- | --- | --- | --- |
| 服务端 `msg_id` 唯一 | `msg_id` | 生产端重试幂等 | 重复落库 |
| 服务端 ack + 游标 | `(device_id, seq)` | 至少一次投递 | 重复投递（不是重复展示） |
| 客户端本地队列 | `msg_id` | 重复投递只展示一次 | 重复展示 |
| 客户端 `dsh_claim` | `workspace\|task\|remind_at` | 多端只有一个展示者 | 两边都弹 |

**claim 的定位要改：它是去重优化，不是正确性依赖。**

现状是「claim 失败就不展示」（`NotifyModule.java:143`、`DshReminderBridge.kt:73`），
这等于把主通道的展示能力押在 App 进程能被拉起上 —— 与 §3.0 的原则冲突。改为：

- claim 超时 800ms（不是 1500ms，主通道不该等这么久）；
- **超时或 provider 不可用 → 插件照常展示**（宁可两边都弹一次，也不要两边都不弹）；
- 只有拿到明确的「已被对方认领」响应（`claimed=false`）才跳过；
- App 侧保持原语义（抢不到就不弹），因为它是降级通道，多让一步是合理的。

这样最坏情况是「重复一次」，而不是「丢失」—— 与 §0.1 的目标 1 优先于目标 2 一致。

**claim 表的两个基本防护**（工程需要，与信任无关）：

- 同一 `(workspace, task, remind_at)` 只允许写入一次，重复 claim 返回 `false`；
- 对不存在的 task 直接拒绝，避免历史脏数据把表撑大；
- 保留现有 7 天的 `prune`。

### 8.2 端到端时延与追踪

- 服务端为每条消息生成 `created_at`（已有）；
- 客户端 pull 到消息后，把 `created_at` 与本地 `receivedAt` 的差记入 `dsh_inbox.deliverLagMs`；
- App 面板与插件工具页显示「最近一条通知端到端时延」；
- 服务端 `deliver_latency_ms` 直方图（§4.6）作为权威口径。

### 8.3 失败矩阵

| 失败点 | 表现 | 兜底 |
| --- | --- | --- |
| 服务端 broker 挂 | 无 nudge | 客户端轮询（15 分钟）+ 本地闹钟 |
| MQTT 网络不通 | 状态「未连接」 | 同上 |
| HTTP pull 401 | 游标不动 | 自动续期一次，仍失败则提示重新授权 |
| 客户端进程被杀 | 不 pull | 服务端保留 TTL 72h，下次启动补投 + 前台服务重拉 |
| ack 丢失 | 重复投递 | `dsh_inbox.msgId` 幂等 |
| 落库成功但展示失败 | 通知没弹 | `displayedAt IS NULL` → 下次 `flushPending` 补显 |
| 两端口径不一致 | 重复/漏 | `dsh_claim` + 统一的等级映射表 |
| 插件进程活着但不响应 ping | App 判定主通道失效并接管 | 最坏短暂双连，`msg_id` + `claim` 去重 |

## 9. 账号态与本地数据安全（新增，P0）

这一章和推送链路不是同一类问题，但同样属于「数据不能丢」，而且**后果比丢一条通知严重得多**：
丢通知重发一次即可，丢 op 是用户当天的工作没了。

### 9.0 触发场景

用户报告：登录态过期时没有明确提示 → 继续修改任务 → 发现失效 → 退出登录再重新登录 →
**失效期间的修改全部消失**。

### 9.1 审计结论

先说清楚事实：**同账号、同地址重新登录时，`op_log` 不会被清**。
`logout()` 明确保留本地数据（`AccountManager.kt:90`、桌面端 `commands.rs:1555`），
`sync_account` 也存在 Room 的 meta 表里、不随 session 清除。

但这不代表安全。这条链路上有 5 个缺口，任何一个被触发都会真的丢数据。

| 编号 | 缺口 | 后果 |
| --- | --- | --- |
| A15 | 换账号判定失败时静默清空整个 `op_log`，无确认 | 未推送的修改全丢 |
| A16 | `resetForAccount()` 的备份路径被调用方丢弃 | 有备份但用户永远看不到 |
| A17 | 登录过期没有硬提示，界面还承诺「本地修改会稍后推送」 | 用户在裸奔状态下持续编辑 |
| A18 | 网络抖动会把仍然有效的 `refresh_token` 一起删掉 | 用户被迫重新输密码，且可能触发 A15 |
| A19 | `markRejected` 永久丢弃 op，无恢复途径 | 临时性 403 也能让修改作废 |
| W1 | 桌面端与 A15/A16/A19 同构 | 同上 |

### 9.2 A15/A16：换账号清库必须可见、必须原子

**机制**（`AccountManager.kt:103`）：

```kotlin
val accountKey = "${serverConfig.baseUrl}|${user.userId}"
if (repository.syncAccount() != accountKey) {
    repository.resetForAccount()   // op_log 清空 + metaDao.deleteAll()
}
```

`accountKey` 里包含**服务器地址**。地址任何一个字符差异（大小写、`http`→`https`、
端口写法、有没有 `/v1`）都会让 key 不等 → 用户所有未推送的修改瞬间消失。

更麻烦的是执行顺序（`AccountManager.kt:96-128`）：

```
① resetForAccount()       清 op_log；metaDao.deleteAll() 把 sync_account 也删了
② settingsStore.update()  DataStore IO
③ sessionManager.save()   文件 IO
④ repository.setSyncAccount(accountKey)   ← 到这里才记录新账号
```

① 和 ④ 之间隔着两次独立 IO。**任何一步抛异常或进程被杀，`sync_account` 就是空的，
下次登录必然再清一次库** —— 自我放大。

**修法两条，缺一不可**：

1. **原子化**：把「新的 accountKey」写进 `resetForAccount()` 的同一个事务里
   （先 `metaDao.deleteAll()` 再立刻 `put(KEY_SYNC_ACCOUNT, 新值)`，同一个 `withTransaction`）。
   这样「清了但没记」在结构上不可能发生。
2. **清库必须可见**：`resetForAccount()` 返回的备份路径必须被接收并展示；
   若此时还有 pending op，登录前给出确认：

   > 本地有 N 条尚未同步的修改，切换账号会把它们移出工作区。
   > [先导出备份] [取消] [仍然切换]

   默认动作是**取消**。没有这个确认，任何地址写错都可能是一次静默的数据清空。

### 9.3 A17：登录过期要有硬提示与未同步计数

现状：过期时确实 `publish(AUTH_REQUIRED)`，但 `pushAll` 遇 401 会先
`sessionManager.clear()` 再 publish，此时 session 已空，界面只能显示「未登录」。
而 `TasksViewModel.kt:138` 对未登录的提示是「未登录，本地修改会稍后推送」——
**这句承诺在 A15/A19 触发时是假的**。

要求：

1. 全局常驻横幅（不是一闪而过的 toast）：`登录已过期 · N 条修改待推送`，
   带「重新登录」按钮；
2. `N` 来自 `pendingOps()` 的真实计数，实时更新；
3. 文案改为如实的版本：「修改已保存在本地，重新登录后自动推送」——
   只有在 A15/A19 都被修掉之后，这句话才是真的；
4. **不阻断编辑**（本地优先是产品基调），但必须让用户随时看得见自己没在同步。

### 9.4 A18：401 不该连 refresh_token 一起删

**机制**（`SessionManager.kt:47-52`）：

```kotlin
if (session.accessExpiresAt - nowMs > REFRESH_AHEAD_MS) return session
refresh()
return cached        // ← refresh 失败也返回 cached，可能是过期 token
```

网络抖动时 `refresh()` 返回 false，并且因为 `failure.retryable` **不会清 store**，
但 `ensureFresh()` 仍然把**那张过期的 access token** 返回给上层。
上层拿它 push → 服务端 401 → `SyncEngine.kt:158` 的 `AUTH_CODES` 分支执行
`sessionManager.clear()` → **连还有效的 refresh_token 一起删了**。

用户本来只是地铁里没信号，结果被迫重新输密码；重新登录又踩进 A15 的清库风险。

**修法**：

1. `ensureFresh()` 在 refresh 失败时返回 `null`（而不是过期 token），
   让上层走 `OFFLINE`/`RETRYING`，**不要拿必然失败的 token 去撞 401**；
2. `pushAll` 的 401 分支不无条件清 session，而是：
   - 先强制 `refresh(force = true)` 一次；
   - 仍失败才清，且**只清 access token**，`refresh_token` 保留到服务端明确返回
     `invalid_grant` / `invalid_token` 才清除；
3. 服务端配合：401 的响应体里带明确的错误码，让客户端能区分
   「access 过期」（可刷新）和「refresh 失效」（必须重登）。

### 9.5 A19：rejected 不等于丢弃

`SyncEngine.kt:186-198`：400 / 403 / 413 直接 `markRejected`，op 从 pending 消失。

其中 403 完全可能是暂时的（workspace 成员被移除又加回、权限同步延迟）。
现在的做法让这些修改永久作废，而用户毫不知情。

**修法**：

- `markRejected` 改为**标记 + 保留**：记录 `rejected_at` / `reject_code`，从 pending 移出但行不删；
- 提供「重试被拒操作」入口（批量重投一次），以及「导出为 JSON」兜底；
- 只对**确定不可恢复**的错误（如 schema 校验失败、字段越界）自动丢弃，并且记一条用户可见的通知。

### 9.6 W1：桌面端对应改动

`windows/src-tauri/src/commands.rs:1386-1396` 的结构与 Android 同构：

```rust
let same_account = previous.map(|it| it.server_url == account.server_url
                                   && it.user_id == account.user_id).unwrap_or(false);
let backup = if same_account { None } else { storage::reset_for_account(...)? };
```

要同步做的：

1. `reset_for_account` 里把新的 account 标识写进同一个事务（对应 A15 原子化）；
2. `backup` 变量已经有值，必须接到界面上（对应 A16）；
3. rejected op 保留 + 重试入口（对应 A19）；
4. 登录过期的常驻提示与未同步计数（对应 A17）。

### 9.7 验收

每条都对应用户能复现的操作，必须逐条通过：

| # | 步骤 | 断言 |
| --- | --- | --- |
| 1 | 断网 → 改 3 条任务 → 联网 | 3 条全部推送成功，无需重登 |
| 2 | 手动把 access token 设为过期、refresh 保持有效 | 自动刷新继续工作，**不弹重新登录** |
| 3 | 让 refresh 也失效 → 改 3 条 → 重新登录（同账号同地址） | 登录后 3 条全部推送，一条不少 |
| 4 | 同上，但登录时把地址多写一个 `/` | 应识别为同一服务器（normalize 后相等），不得清库 |
| 5 | 同上，但登录时改成另一个地址 | **弹出确认**，展示 N 条待同步 + 备份路径，默认取消 |
| 6 | 在 ① 与 ④ 之间强杀进程（可用 monkey 或长按返回） | 下次登录不再重复清库（`sync_account` 已原子写入） |
| 7 | 构造一条会被 403 拒绝的 op | 该 op 保留可重试，不消失 |
| 8 | 桌面端重复 3/4/5/7 | 行为一致 |

## 10. 资源预算（验收标准）

资源目标按通道分级设定 —— 主通道值得多花，降级通道必须省。

**主通道（插件在线）**，空闲 24 小时：

| 指标 | 现状（估算） | 目标 |
| --- | --- | --- |
| HTTP 请求数 | 720（120s 安全拉）+ 每次重连的注册/续期 | ≤ 100（15 分钟兜底 ≈ 96 + 少量续期；冷启动零注册） |
| 写盘（prefs / 队列） | 720（每轮 pull 全量重写游标） | ≤ 100（追加写，仅在真有消息时） |
| 常驻线程 | 1 + 每次打开工具页 +1（泄漏） | 恒定 1 |
| 显示到通知 P95 | 未测 | < 1.5s（进程常驻，应快于降级通道） |

**降级通道（仅 App）**，空闲 24 小时：

| 指标 | 现状（估算） | 目标 |
| --- | --- | --- |
| HTTP 请求数 | 1440（60s 轮询） | ≤ 300（自适应：2–15 分钟） |
| 唤醒次数 | 每次轮询都唤醒进程 | 只在前台服务存活期轮询；否则交给闹钟 |
| MQTT 重连认证失败卡死 | 会发生（T3） | 不会，3 次失败即升级为 `auth_failed` |

**服务端**：

| 指标 | 现状 | 目标 |
| --- | --- | --- |
| 提醒扫描 | 每 30s 全量 op_log 窗口排序 | 每 30s 索引查询，稳定 < 10ms（10 万行 op_log 下） |
| 连推 50 条时的 nudge 数 | 50 | ≤ 2（按设备合并） |
| 并发 100 设备空闲 | 未测 | CPU < 2%、内存 < 200MB、队列深度常态 0 |
| 通知端到端 P95 | 未测 | < 2s |

## 11. 测试与验收

- **单元**：本地队列幂等（同 msgId 两次 append 只展示一次）、ack 顺序（落盘失败不 ack）、
  claim 超时后仍展示、nudge 合并窗口、退避 jitter 范围、通道判活（ping 超时即接管、连续 3 次才让出）。
- **集成（服务端）**：`scripts/verify_realtime_sync.py` 扩展成「推 50 条 → 断言合并后
  nudge ≤ 2 → 断言全部 ack → 断言 deliver_latency 有值」。
- **插件 JVM 测试**（`tools/test.sh`，现有 69 项）：补 11 项 —— 票据过期重连、
  append-only 队列重放与崩溃恢复、claim 超时降级、`device_revoked` 终态、
  凭证收口后仅靠设备凭证可用、非 notify kind、退避 jitter、单实例线程数、
  日志降噪、ping 应答、版本协商降级、提醒索引 HLC 比较。
- **端到端四态验收**（每态都断言「至少展示一次、最多展示两次」）：
  ① 主通道正常；② 插件被关但 trime 仍是默认输入法（判活空窗，§6.7）；
  ③ 只有降级通道；④ 断网 30 分钟后恢复（补投）。
- **撤销（§5.5）**：点撤销后断言 ①服务端 `GET /devices` 显示 revoked ②插件进终态且不再重连
  ③用旧凭证调 refresh / pull / clip 一律 409/401 ④App 重装前后都不会复活旧设备
  ⑤重新启用分配新的 `dsh-ime-*`（不复用旧号）。
- **凭证收口**：断言 `prefs.json` 里没有明文 `refresh` 键、`config.json` 里没有可用账号密码
  时插件仍能靠 App 签发的设备凭证正常工作。
- **长跑**：设备空跑 24h，抓 `dumpsys` 的 CPU/网络/唤醒次数与 §10 对比。

## 12. 分阶段实施

顺序按「主通道优先」重排：先把插件的可靠性做实，再补降级通道。
这是因为主通道一旦可靠，降级通道的偶发不可靠就不再是用户可感知的问题。

| 阶段 | 内容 | 涉及 | 风险 |
| --- | --- | --- | --- |
| 1 | **§9 账号态数据安全**（A18 401 不清 refresh + A15 原子化清库 + A17 未同步计数 + A19 rejected 可重试） | App + 桌面端 | 中（涉及 auth 与存储两条主链路） |
| 2 | **5.5 凭证撤销**（App 撤销按钮 + 保留 device_id + 插件 `device_revoked` 终态 + 凭证收口：废弃账号密码与 refresh_token 落盘） | App + 插件 | 中（凭证收口是破坏性变更，要给迁移提示） |
| 3 | 7.3 插件 append-only 本地队列（先落盘再 ack） | 插件 | 中（存储格式变更，需兼容旧 prefs） |
| 4 | 8.1 claim 降级（超时即展示）+ 7.1 单实例 | 插件 | 中 |
| 5 | 5.1–5.3 票据持久化 + 自管重连 + jitter + 凭证 TTL 缩短 | 两端 | 低 |
| 6 | 3.0.1 版本协商（`protocol`/`version` 上报与降级）+ 插件自检 App 是否安装 | App + 插件 | 低 |
| 7 | 6.7 通道判活与互斥（App 主动 ping 插件 + 兜底 pull 不停止） | App + 插件 | 中（新增广播协议） |
| 8 | 6.1/6.2/6.3 降级通道落库 + 统一通知处理 + 补显 | App | 中（Room v3 迁移） |
| 9 | 7.2/6.4 自适应轮询（两端同参） | 两端 | 低 |
| 10 | 4.1 提醒增量索引（逐字段 HLC + 回填迁移） | 服务端 | 中（数据迁移 + 并发正确性） |
| 11 | 4.2/4.3 nudge 合并与背压 | 服务端 | 低 |
| 12 | 7.4/7.7 日志降噪 + 自我诊断 | 插件 | 低 |
| 13 | 4.6 指标 + 8.2 时延面板 + §11 长跑 | 全 | 低 |

每阶段独立可发布、可回滚，三个发布通道互不阻塞：

- **插件 dex**：阶段 3、4、12 单独发，用户按安装说明替换文件即可；
- **App**：阶段 1、2、6、7、8 需要重新构建安装，其中阶段 8 含 Room v3 迁移；
- **服务端**：阶段 10、11 独立部署，与客户端解耦。

阶段 3 上线的同时要把「插件本地队列」的存储格式变更做成向后兼容
（旧的 `prefs.json` 若已存在则迁移一次再删），否则用户升级 dex 会丢游标。

## 13. 决策记录

### 已定（用户确认）

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 插件角色 | 保留展示能力，插件是主通道、自持（§3.0） |
| D2 | 信任模型 | 假定插件可信，不做完整性校验与能力降级（§3.0.1） |
| D3 | 插件分发 | App 不释放插件，用户自己安装；App 只在设置页显示状态与指引 |
| D4 | 撤销方式 | 由服务端权威撤销，App 只发起与展示（§5.5） |
| D5 | 扣什么 | 推送 + 剪贴板共用凭证，撤销是整体 |
| D6 | **优先级** | **长连接的稳定性与实时性优先；省资源只在不损害二者的前提下做**（§0.1） |
| D7 | 降级通道形态 | **保留 MQTT 长连接**，不降级成纯轮询 |
| D8 | 判活方式 | 不用插件定时心跳（会拉起 App），改 App 主动 ping（§6.7） |
| D9 | 提醒索引并发规则 | 逐字段 HLC 比较，乱序 op 不覆盖（§4.1） |
| D10 | 账号态数据安全 | 单独成章（§9），排进阶段 1；桌面端同步（§9.6） |

### 仍待确认（都有建议默认值）

| # | 问题 | 建议默认 | 影响 |
| --- | --- | --- | --- |
| Q1 | 超过 10 分钟的未展示通知 | 静默进通知中心（不弹不出声），标 `displayResult=late` | §6.2 |
| Q2 | 票据 TTL 10 → 60 分钟 | 采纳 | §5.1 |
| Q3 | 设备凭证 30 天 → 24 小时 | 采纳 | §5.5 |
| Q4 | 第三方 API 鉴权形状 | 独立「应用」实体，本轮只预留限流键 | §4.7 |
| Q5 | `dsh_messages` 加 `source_kind` | 采纳 | §4.6 |

### 需要产品判断

1. **降级通道要不要在界面显式标红**？现在是文案区分，用户可能不看。
2. **撤销后能否从插件面板直接重新启用**？（会让「撤销」变弱，倾向不允许）
3. **`op_log` 要不要顺带做保留策略**？索引建好后它仍无限增长（影响备份与 `/v1/history`）。
