# 中文输入法扩展客户端（dsh-kit）设计

> 状态：P0/P1/P2/P3 已真机验证，P4 服务端提醒已上线；本文是实现到现在为止的**设计契约**。
> 协议以 [`server-protocol.md`](./server-protocol.md) 第十五节为准；服务端实现在 `server/app/features/dsh/`。
> 客户端代码不随本仓库分发（它跑在手机输入法进程里，是 Lua 脚本 + 一个 dex），
> 本文只写「设计上必须成立什么」，实现与之冲突时改实现或改本文，不允许两套说法。

## 一、定位与边界

### 1.1 一句话

在手机输入法 **中文输入法2（`com.nirenr.trime`）** 的进程里，用 **Lua 薄壳 + 单个 dex** 跑一套自研框架，
借宿主已有的权限等级做 **通知推送** 与 **剪贴板同步**，并能继续挂新功能。

它不是账号体系的一部分，也不产生新的账号：设备注册、登录、提醒收件人全部复用待办的
`users` / `memberships`（见第十节）。

### 1.2 目标

1. **零安装**：不装新 APK、不改输入法主题、不绑键位；从输入法「工具」里点一下就能用。
2. **一个通道两种能力**：通知（0–7 级提醒强度）与剪贴板（双向、默认不落库）。
3. **可靠性由数据库兜底**：MQTT 只做唤醒（nudge），设备主动 pull 权威数据并 ack，
   nudge 丢失不影响正确性。
4. **分层可扩展**：加一个新模块（`file.*`、待办快操作、AI 入口）不改传输层、不碰宿主 API。
5. **可离线开发**：纯逻辑（JSON、存储、协议、等级、配置）跑在不依赖 Android 的 JVM 上，能在电脑上单测。

### 1.3 非目标

- 不做端到端加密（E2EE 是后续可选项，见第十五节）。
- 不做后台常驻服务：进程被系统杀掉就停，靠下次打开工具或下次键位操作恢复。
- 不做文件/图片传输、不做消息已读回执、不做多端历史漫游 UI。
- 不替代系统推送（FCM/厂商通道）：那需要 APK 与厂商资质，选型理由见 2.2。
- 不接管待办业务数据：dsh 只读写 `dsh_*` 表，**不碰 `op_log`、不参与投影**。

## 二、为什么外挂输入法

### 2.1 宿主提供的东西

| 能力 | 说明 |
| --- | --- |
| 进程长期存在 | 输入法是系统常用组件，比普通后台进程更容易被留住；但**仍会被杀**，不能假设常驻 |
| Lua 运行时 | 宿主内有 LuaJ，`rime/scripts/*.lua` 与 `rime/tools/<名字>/main.lua` 都能执行 |
| dex 加载 | 能从 Lua 里 `DexClassLoader` 加载外部 dex，等于「免安装插件」 |
| 权限 | 通知、剪贴板、网络、开机等权限随宿主授予，不需要另装应用 |
| 界面 | `LuaActivity` 能弹对话框与面板，够做状态页和调试按钮 |

### 2.2 为什么不走系统/厂商推送

- 系统推送要独立 APK + 厂商资质 + 隐私合规，违背「零安装」。
- 输入法内网络连接是本进程的 TCP，不依赖推送 SDK 保活。
- 代价是：进程死了就收不到实时 nudge。**这正是设计用 pull 兜底的原因**——上线/连上/定时都 pull，
  消息在服务端库里，晚到但不丢。

### 2.3 存活模型（必须接受的事实）

1. 工具页每次打开都可能是新进程；键位命令跑在输入法服务进程里，也可能是另一个进程。
2. 每次「工具」调用都会**新建一个 DexClassLoader**：同一份类被加载两次 → 静态字段不同 →
   两个 `Kit`、两条 MQTT 连接、重复通知。必须做单例缓存（见 5.2）。
3. Android 14+（targetSdk 35）拒绝从可写目录加载 dex：外部 dex 必须拷到私有目录并 `setReadOnly()`。
4. 因此框架只承诺 **「拉起后可用、被杀后靠下次拉起恢复」**，不承诺后台秒达。

## 三、总体架构

```
┌──────────────────────────────────────────────────────────┐
│ L0 挂载壳  scripts/dsh.lua / tools/dsh/main.lua           │
│    定位 dex → DexClassLoader → 反射 Boot → 调 kit.call()   │
└──────────────┬───────────────────────────────────────────┘
               │ kit.call(action, json) → 字符串/JSON
┌──────────────▼───────────────────────────────────────────┐
│ L1 桥接层  Boot · TrimeHost                              │
│     唯一认识 com.osfans.trime.* / AndroLua 的代码         │
├──────────────────────────────────────────────────────────┤
│ L2 内核层  Kit · Config · Store · Log · Scheduler         │
│           EventBus · Json · Protocol · Levels · Fingerprint│
├──────────────────────────────────────────────────────────┤
│ L3 能力层  HttpApi · Transport(接口) · MqttTransport(Paho) │
│           Session（nudge→pull→ack、令牌、重连）            │
├──────────────────────────────────────────────────────────┤
│ L4 模块层  NotifyModule · ClipModule · Ui                 │
│           NotifySink · ClipSource · NetWatcher            │
└──────────────┬───────────────────────────────────────────┘
               │ HTTP /v1/auth/* /v1/dsh/*    MQTT dsh/v1/…
        ┌──────▼───────────────────────────────────┐
        │ 待办服务端（FastAPI + 内嵌 amqtt broker） │
        │ dsh_* 表；op_log 不参与                   │
        └──────────────────────────────────────────┘
```

### 3.1 分层铁律（评审按这条查）

1. 依赖只向下；**L2 及以上出现 `com.osfans.trime` / `com.androlua` 即违规**（L4 的 `Ui`/`NotifySink`
   只允许用 `android.*`，且通过 `Host` 拿 `Context`）。
2. **只有 L1** 碰输入法专属 API；宿主升级只改 L1 一个文件。
3. L4 模块之间不互相调用，一律走 `EventBus`。
4. **L4 不碰 Paho**，只发 `Transport` 消息；换传输（MQTT → WebSocket）不改模块。
5. `core/` 里的纯逻辑（`Json`/`Store`/`Protocol`/`Levels`/`Config`/`Fingerprint`）不 import `android.*`，
   必须能在纯 JVM 上跑测试。

### 3.2 宿主升级只影响 L1

| 不确定点 | 被隔离在 |
| --- | --- |
| `showCustomView` / `setCandidates` / `commitText` 签名 | `TrimeHost` |
| Lua 脚本返回值语义（table→候选，string→上屏） | L0 壳 |
| 脚本目录 `Documents/rime/scripts`、`tools/` | L0 壳一处常量 |
| `LuaApplication.setSharedData` 等 AndroLua 细节 | L0 壳 |

## 四、启动、入口与生命周期

### 4.1 两条入口（都要部署，互不替代）

| 入口 | 路径 | 运行环境 | 用途 |
| --- | --- | --- | --- |
| 工具列表 | `/sdcard/Documents/rime/tools/dsh/main.lua` | AndroLua `LuaActivity` | 用户手动点「dsh」→ 出面板 |
| 键位命令 | `/sdcard/Documents/rime/scripts/dsh.lua` | 输入法服务 Lua 环境 | 绑键时用；默认不要求绑键 |

工具目录是 `tools/<名字>/main.lua` 的**目录结构**，不是 `scripts/` 下的单文件——早期版本放错目录，
点进去是空白页，这是排查清单第一条。

### 4.2 启动序列

```
点工具 / 按 DSH 键
 → L0：sharedData 里有 Boot 且 dex 的 size+mtime 没变？ → 直接 kit.call(...)
   否 → 校验 dex → 拷到 files/dsh-kit/（已存在且版本一致则跳过）→ setReadOnly()
        → DexClassLoader → Class.forName("com.dshkit.Boot") → new → boot(activity)
        → 缓存 Boot + stamp 到 LuaApplication.setSharedData
 → Boot.boot: 建 Kit → 读 config.json → 建 Store/Log/Scheduler → 注册 L4 模块
        → （auto_connect）Session.start()：登录/换令牌 → 注册设备拿票 → 连 broker → pull
 → 返回 Kit；L0 调 ui.panel 显示面板
```

任何一步失败：L0 只弹 `AlertDialog` + 写 `/sdcard/Documents/rime/logs/dsh.log`，**不抛到宿主**，
不能让输入法崩。

### 4.3 生命周期映射

| 事件 | 行为 |
| --- | --- |
| 面板打开 | 保证 Kit 在跑；`ui.panel` 显示状态并 1.5s 自动刷新 |
| 面板关闭 | 只是关界面，Session 继续（进程活着就继续收） |
| 输入法进程被杀 | 框架停；下次拉起时 `Session.start()` 先 pull，补齐期间消息 |
| 切网/断线 | `NetWatcher` 触发 + 传输层状态回调 → 指数退避重连，重连前重新注册拿新票 |
| `core.shutdown` | 主动停 Session、注销监听，用于调试与「真·停止」 |

## 五、单例与并发约束

### 5.1 一个进程只能有一个 Kit

- `Boot` 实例缓存键：`LuaApplication.setSharedData("dsh.boot", boot)` +
  `("dsh.stamp", dexSize + ":" + dexMtime)`。
- 版本变化（重新部署 dex）时 stamp 不匹配 → 丢弃旧 Boot、新加载一份。
- **已知残留**：升级 dex 前已经打开的工具页仍持有旧 Boot，会出现「旧代码 + 新 dex」的错觉；
  升级后强制停一次输入法最稳。

### 5.2 线程模型

- 网络、pull、剪贴板发送都在单一工作线程（`Scheduler` 串行队列）上执行；
- UI 回调必须 `handler.post` 回主线程；
- 通知发送、剪贴板读写都在主线程由模块自己切。

### 5.3 坑（真机踩过的，改 L0/L1 前必读）

| 现象 | 原因 | 对策 |
| --- | --- | --- |
| `IllegalArgumentException: … getFilesDir()` | 这个 LuaJ 开了 `LUA_JAVA_OO`，Java 方法要用**点号**调用，冒号会把对象当第一个参数 | L0 全部改成 `act.getFilesDir()` |
| `SecurityException: Writable dex file … not allowed` | Android 14+ 禁止加载可写 dex | 私有副本 + `setReadOnly()` |
| `main.lua:88 attempt to call nil` | `Boot.boot()` 返回的是 `Boot`，不是 `Kit` | `boot.kit()` 再调 |
| `MissingResourceException: … nls.logcat` | d8 不带 `.properties`，Paho 的 `ResourceBundle` 找不到 | 自带 `logcat` / `messages` 的 `ResourceBundle` 子类 |
| `no NetworkModule installed for scheme "tcp"` | Paho 用 ServiceLoader，dex 里没有 `META-INF/services` | 自带 `NetworkModuleService`，构建时从 jar 里删掉 Paho 的实现 |
| `SecurityException: … RECEIVER_EXPORTED` | Android 14 要求动态注册广播显式声明导出性 | 反射调用 4 参 `registerReceiver(..., 4)` |
| 重复通知 / 两条连接 | 多个 DexClassLoader 各有一份静态字段 | 5.1 的单例缓存 |

## 六、L0 ↔ dex 的 ABI

只有一个入口，保证壳足够薄：

```
kit.call(action: string, argsJson: string) -> string   // 返回 JSON 或纯文本
```

| action | 作用 |
| --- | --- |
| `core.ping` / `core.info` / `core.config` / `core.log` | 探活、状态摘要、当前生效配置、导出日志 |
| `core.shutdown` | 停 Session、注销监听 |
| `ui.panel` / `ui.close` / `ui.toast` | 面板与提示 |
| `dsh.status` / `dsh.sync` / `dsh.reconnect` / `dsh.login` / `dsh.stop` | 状态、立即 pull、重连、重新登录、停通道 |
| `notify.test` / `notify.selftest` | 按 0/1/3/6/7 各弹一条；自检渠道是否存在 |
| `clip.status` / `clip.send` / `clip.test` / `clip.peek` | 剪贴板状态、发送指定文本、测试、读当前内容 |

约束：

- 参数一律 JSON 字符串，不传 Java 对象（Lua 与 Java 之间只过字符串）。
- 返回值必须是**可打印字符串**；错误也返回字符串（`{"ok":false,"error":…}`），不抛异常。
- action 未知返回 `{"ok":false,"error":"unknown_action"}`，不崩。

## 七、消息与可靠性

### 7.1 两段式：nudge → pull → ack

```
服务端 push（写库 + 发 nudge）──MQTT──▶ 设备收到 {"t":"nudge","seq":N,"n":count}
                                           │
                                           ▼
                                    GET /v1/dsh/pull?since=cursor&limit=…
                                           │
                                           ▼
                        逐条应用（通知/剪贴板）→ 推进游标 → POST /v1/dsh/ack
```

铁律：

1. **顺序固定为「应用 → ack → 推进游标」**：ack 失败则本轮重试；重试幂等（`msg_id` 去重），
   不会因为重试重复弹通知，也不会丢消息。
2. **数据库是唯一真相源**：nudge 只带 `{t,seq,n}`，丢了不影响正确性。
3. pull 分页 100，最多 20 页；每页应用完再取下一页。
4. 未知 `kind` 也要 ack 掉（否则堵住队列），只记日志不弹通知。
5. `payload.level` 决定提醒方式，**不决定是否 ack**：0 级照样 pull/ack/入库。

### 7.2 兜底与重连

| 机制 | 参数 | 说明 |
| --- | --- | --- |
| 连上先 pull | 一次 | 覆盖离线期间的消息 |
| 定时兜底 pull | 120s | 防 nudge 丢失/漏收 |
| 断线重连 | 2s 起，×2，上限 60s | 每次重连前重新注册设备拿新票 |
| MQTT keepalive | `config.keep_alive`（默认 60s，限 15–600） | 与 Paho 的 ping 对应 |

### 7.3 凭据生命周期

- **access token**（待办 JWT，15 分钟）与 **refresh token** 存私有 `prefs.json`；
  密码只在首次登录用，`config.json` 里可以留，但**建议登录一次后删掉**。
- **设备是一等公民**：`POST /v1/dsh/devices/register` 返回 `device_id` + 短票据（默认 10 分钟）+ MQTT 参数。
- **设备票据只放内存**，不落盘；每次重连重新注册续期。
- 吊销（`POST /v1/dsh/devices/{id}/revoke`）后票据立即失效、连接被踢，重新注册返回 `409`；
  恢复要换新 `device_id`。

### 7.4 本地状态（`prefs.json`）

| 键 | 含义 |
| --- | --- |
| `device_id` | 本机设备 id（`dsh-xxxxxxxx`） |
| `cursor` | pull 游标（已确认的最大 seq） |
| `access` / `access_exp` | 待办 access token 与过期时间 |
| `refresh` / `refresh_exp` | refresh token 与过期时间 |

原子写：写临时文件 → `rename`。损坏时备份为 `prefs.json.bad` 并重建。

## 八、通知：0–7 级

等级**只决定提醒方式，不代表通知可以丢**。0 级也会进通知中心，只是不打扰。

| 等级 | 渠道 | 提醒方式 |
| --- | --- | --- |
| 0 | `dsh_kit_l_min`（LOW） | 静默进通知中心，归入「不重要」；不悬浮、不出声、不震动 |
| 1–2 | `dsh_kit_l_center`（DEFAULT） | 通知中心；不悬浮、不出声、不震动 |
| 3–5 | `dsh_kit_l_heads`（HIGH） | 悬浮通知 |
| 6 | `dsh_kit_l_vibrate`（HIGH） | 悬浮 + 震动 |
| 7 | `dsh_kit_l_alert`（HIGH） | 悬浮 + 震动 + 响声 |

实现要点：

- **渠道重要性创建后不可改**，所以一档一条渠道，渠道 id 与名字一次定死；改档位等于换渠道。
- `dsh_kit_state`（MIN）保留给「框架自身状态」这类内部提示，不用于业务消息。
- 等级来源优先级：`payload.level` > 服务端顶层 `level`（服务端已合并进 payload）>
  `config.default_level`（默认 3）；老格式 `payload.silent=true` 视为 1 级。
- 纯逻辑在 `Levels`，Android 侧只负责落地；`tools/test.sh` 覆盖 0/1/2/…/7 的映射。
- API 25 及以下没有渠道概念：退回 `priority` + `setSound`/`setVibrate`，此时「静音悬浮」做不到。
- 同一 `msg_id` 只弹一次：`Fingerprint` 记录最近投递，服务端重复投递不重复打扰。
- 点击通知默认打开输入法/面板；回到具体任务需要服务端带 `payload.task_id`（P4 已带，跳转未实现）。

## 九、剪贴板

- **本机复制 → 发布**：`ClipSource` 监听 `OnPrimaryClipChanged`，取文本，取当前前台包名
  （`ignore_apps` 命中则不传），算指纹，`POST /v1/dsh/clip`；
- **服务端 → 其他在线设备**：默认 `clip_persist=false`，只做 MQTT 直传，对端离线就丢；
- **对端来 → 写入本地**：`ClipModule` 写剪贴板并记指纹，`ClipSource` 看到同一指纹丢弃（防回流）；
- 只传文本，上限 32 KiB；同段文字 2 秒内重复触发只发一次；
- `clip_enabled` 总开关（默认 true）；`clip.status` / `clip.test` 用于面板自检；
- 目标语义：用户 token 调用时 `device_id` 是**接收方**；设备票据调用时来源设备被排除。

## 十、账号与设备（复用待办账号）

这是 P4 之后定下的硬约束：**推送通道没有自己的账号体系**。

1. 登录：`POST /v1/auth/login`，账号就是待办账号；没有「dsh 用户」概念。
2. 设备：必须在该账号下用待办 JWT 注册；`dsh_devices` 只是附加表，`user_id` 外键指向待办用户。
3. 鉴权：
   - 设备管理、push、state → `Authorization: Bearer <access_token>`；
   - pull / ack / MQTT → 设备票据（HMAC，由服务端 `jwt_secret` 派生，校验不读库）。
4. 多设备：同一账号可注册多台（手机、CLI、网页），push 不带 `device_id` = 广播全部设备，各自 ack。
5. 吊销：立即踢线 + 票据失效，设备不能再用。
6. 服务端提醒（P4）的收件人 = 工作区成员，走同一套账号；`dsh_reminder_fires` 只保证不重复推。

## 十一、界面

### 11.1 面板（`Ui`）

工具入口打开后显示一个可滚动面板：

- 顶部：连接状态、服务器、设备 id、游标、MQTT 状态、错误摘要；
- 按钮：**重连 / 测试通知 / 剪贴测试 / 复制日志 / 刷新 / 清空日志 / 关闭**；
- 面板可见时每 1.5s 自动刷新状态；
- 「测试通知」依次按 0/1/3/6/7 弹 5 条，用于逐档验证渠道与系统权限；
- 「复制日志」把最近日志送到剪贴板，方便贴给排查方。

### 11.2 键位（可选）

默认不要求绑键。要绑就用 `rime/scripts/dsh.lua`（`dsh` 命令 → `ui.panel`）。
**不修改主题文件**，主题升级不受影响。

## 十二、安全

| 面 | 做法 |
| --- | --- |
| 账号密码 | 只用于首次登录；换到 refresh_token 后建议从 `config.json` 删除 password |
| 令牌 | access/refresh 存私有目录 `files/dsh-kit/prefs.json`；设备票据只在内存 |
| dex | 拷进私有目录并 `setReadOnly()`；只从固定路径加载，不远程拉 dex |
| MQTT | 每设备独立 `username=device_id`、`password=票据`，只允许订/发自己的 `in|out` |
| 日志 | 不含密码/票据；日志可直接外发用于排查（仍建议检查路径与设备名） |
| 剪贴板 | 默认不落库；`ignore_apps` 可屏蔽银行类应用；只传文本、限长 |

## 十三、目录与配置

```
/sdcard/Documents/rime/
├── scripts/dsh.lua                    键位入口（可选）
├── scripts/dsh-kit.dex                部署的 dex（约 280 KB，含 Paho）
├── scripts/dsh.d/config.json          用户可见配置
├── tools/dsh/main.lua                 工具入口（必须这个目录结构）
└── logs/dsh.log                       L0 启动日志；logs/kit-YYYYMMDD.log 框架日志镜像

/data/data/com.nirenr.trime/
├── files/dsh-kit/dsh-kit.dex          dex 运行副本（只读）
├── files/dsh-kit/prefs.json           游标、令牌、deviceId
├── files/dsh-kit/logs/                滚动日志
└── files/dsh-kit/paho/                Paho 在途消息持久化
```

`config.json` 键：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `server` | `http://127.0.0.1:8000` | 待办服务端地址 |
| `username` / `password` | 空 | 待办账号 |
| `device_name` | `手机` | 设备显示名 |
| `device_id` | 空 | 复用已有设备时填；空则新注册 |
| `auto_connect` | `true` | 启动即连 |
| `default_level` | `3` | 消息没带 level 时用 |
| `clip_enabled` | `true` | 剪贴板同步总开关 |
| `keep_alive` | `60` | MQTT keepalive 秒 |
| `ignore_apps` | `[]` | 剪贴板忽略的包名 |

## 十四、测试与验收

### 14.1 三层测试

| 层 | 内容 |
| --- | --- |
| 纯 JVM | `Json` / `Store` / `Protocol` / `Levels` / `Config` / `Fingerprint`，69 项 |
| 端到端（电脑） | 起临时服务端 + 内嵌 broker，跑登录、注册、push→pull→ack、断线重连、剪贴板不回流 |
| 真机 | 工具入口出面板；收到 L0/L6/L7 三档；剪贴板双向；杀进程后重开能补收 |

### 14.2 验收清单

- [ ] 输入法「工具」→ `dsh` 打开面板，状态显示「已连接」
- [ ] `notify.test` 五档渠道行为符合表格（尤其 L0 静默、L7 有声音）
- [ ] 断网 30s 后恢复，自动重连且补齐这期间的消息
- [ ] 手机复制文本，对端（CLI/网页）立即收到；对端回写，手机剪贴板变化且不回流
- [ ] 强制停止输入法再打开，`cursor` 未丢、不重复弹旧通知
- [ ] 吊销设备后，手机重连被拒并在日志里看到原因

## 十五、已知限制与后续

| 限制 | 现状 | 后续 |
| --- | --- | --- |
| 进程死了收不到 | 靠 pull 兜底 | 可选：前台服务/无障碍保活（代价大，暂不做） |
| 没有 E2EE | 服务端可见明文 | 可加端到端公钥，服务端只转发密文 |
| 剪贴板默认不落库 | 对端离线丢失 | 服务端已有 `clip_persist` 开关 |
| 通知点击只在面板 | 未跳转到具体任务 | 服务端已带 `task_id`，客户端可加 URI 跳转 |
| 面板不能改配置 | 改 `config.json` 后重开 | 可加表单编辑（注意别回写密码） |
| 只支持 Android | 输入法宿主限定 | 无计划 |

## 十六、排障顺序

1. 点工具没反应 → 看 `/sdcard/Documents/rime/logs/dsh.log`，确认 `tools/dsh/main.lua` 存在。
2. 出「启动失败」弹窗 → 按 5.3 的坑表对号入座（点号调用 / 可写 dex / 缺类）。
3. 面板显示未连接 → `core.info` 看 `server` 是否可达；`/v1/dsh/*` 是否被反代挡住。
4. 能连但不收通知 → 服务端 `GET /v1/dsh/state` 看 `pending`；`dsh.sync` 手动 pull；
   注意 0 级本来就静默，只在通知中心「不重要」里。
5. 通知重复 → 检查是否多份 dex/多个 Boot；强停输入法再试。
6. 剪贴板单向 → 对端设备是否在线；`clip_enabled`；`ignore_apps` 是否命中当前应用。
