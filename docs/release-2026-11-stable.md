# 稳定版 2026-11 发布说明

> Tag：`稳定版-2026-11`
> 范围：服务端 · Windows 客户端 · Android App · 中文输入法2 插件（dsh-kit）

这次发版的主线是把三端的数据通道统一到 **dsh 新通道**，并把「手机 App × 输入法」的协作关系定下来：账号只属于 App，插件用设备级凭证自主续期，提醒只由一方展示，剪贴板始终留在插件里。

---

## 一、亮点

- **新数据通道（dsh）三端贯通**：推送、提醒、剪贴板不再是各端各写一套，而是同一条 `nudge → pull → ack` 管道。
- **设备级凭证**：无法持有账号密码的宿主（输入法插件）也能独立续期，凭证可轮换、可吊销、可限流。
- **单一提醒展示者**：同一台手机上，App 与输入法不再重复弹同一条提醒。
- **通道可降级**：输入法不可用 → App 自建 MQTT → HTTP 轮询，逐级兜底，不丢提醒。

## 二、服务端

- 新增 `POST /v1/dsh/devices/{device_id}/credential`：账号侧为设备签发凭证（仅设备所属用户）。
- 新增 `POST /v1/dsh/devices/refresh`：设备用 `device_id + credential` 自助续期，返回新票据、轮换后的凭证与 MQTT 端点，无需 `Authorization`。
- 迁移 `0004_dsh_device_credential.sql`：`dsh_devices` 增加 `credential_hash` / `credential_expires_at` / `credential_rotated_at`。
- 凭证只存 `sha256`，每次续期轮换；吊销设备时同步清空凭证并踢掉 MQTT 连接。
- 新增错误码 `invalid_credential`；续期按设备限流（默认 10 次/分钟）。
- 新增配置：
  - `TODO_SERVER_DSH_DEVICE_CREDENTIAL_DAYS`（默认 30，1–365）
  - `TODO_SERVER_DSH_DEVICE_REFRESH_RATE`（默认 10）
- 补齐 `amqtt` 运行依赖。
- 新增 9 项凭证测试（签发 / 续期 / 轮换 / 过期 / 吊销 / 越权 / 限流 / 凭证不能访问账号接口）。

## 三、Windows 客户端

- dsh 推送与剪贴板：注册设备、MQTT nudge、HTTP pull/ack、剪贴板上行与下行应用。
- 提醒修复：
  - Toast 走 WinRT 并注册 AUMID（含 PowerShell 兜底），`--no-bundle` 运行也能弹。
  - 先弹通知再写 `last_fired_at`，失败可重试。
  - 扫描全部工作区，幂等判定改为 `last_fired_at == remind_at`。
- MQTT 双栈：优先 IPv6，失败回落 IPv4；票据过期（pull 401）主动断开重连，不再卡死在「未连接」。
- 通知点击直达任务详情（`open-task`），日志落在 `data/logs/todo-windows-YYYY-MM-DD.log`。

## 四、Android App

- 新模块 `core:dsh`：
  - `DshManager`：App `dsh-app-*` 与输入法 `dsh-ime-*` 双设备、凭证、游标、pull/ack、提醒认领、通道选择（IME / 自建 / 轮询）。
  - `DshMqtt`：App 自建 MQTT 兜底（TCP 探活后写死可用地址）。
- 数据库升级到 **Room v2**：
  - 新增 `dsh_claim`（跨进程提醒认领，键 `workspace_id|task_id|remind_at`）。
  - `task_local` 增加 `fired_source` / `fired_at`。
  - 自带 `MIGRATION_1_2`，覆盖安装不清库。
- 提醒链路统一：闹钟、补发、通道提醒展示前都先认领，抢不到就不弹。
- 与输入法的新 provider 契约：`dsh_authorize` / `dsh_credential_update` / `dsh_status` / `dsh_nudge` / `dsh_claim_reminder`。
- 设置页「提醒 → 数据通道」：开关、通道形态、连接状态、设备号、立即重连。
- 登录/登出、切换账号时自动重建或停止通道。

## 五、输入法插件（dsh-kit）

- 凭证优先鉴权：`/v1/dsh/devices/refresh` 续期并轮换，**不再保存账号密码**；凭证用 Android Keystore AES/GCM 加密落盘。
- 设备票据直接用于 `pull` / `ack`，不依赖账号 access token。
- 收到 nudge 时转发给 App（`dsh_nudge`），由 App 统一 pull 与展示。
- 提醒兜底：`type=reminder` 先认领；App 已展示或处于免打扰时只记账不弹。
- MQTT 双栈（`DualStackNetworkModule`），IPv6 优先、逐地址尝试。
- 凭证失效自愈：401/409 时清本地凭证并回写空值，App 下次授权时重新签发。
- 新增 Windows 构建脚本 `tools/build.ps1`（javac → 补丁 Paho jar → d8）。

## 六、升级与部署

1. **服务端**：更新代码后重启进程，启动时自动应用 `0004` 迁移；建议同时确认 `TODO_SERVER_DSH_MQTT_PUBLIC_HOST` 指向手机可访问地址。
2. **Android**：直接覆盖安装 `app-release-debugsign.apk`（首次从 v1 升到 v2 会执行 Room 迁移，本地数据保留）。
3. **输入法插件**：
   ```bash
   adb push out/dsh-kit.dex /sdcard/Documents/rime/scripts/dsh-kit.dex
   adb shell am force-stop com.nirenr.trime
   ```
   强停后第一次唤起输入法会加载新 dex。
4. **Windows**：替换 `TodoWindows.exe` 即可；建议在设置里确认「推送与剪贴板」已开启。

## 七、验证结果

- 服务端：`pytest` 95 项通过（含凭证 9 项）；`ruff check` / `format --check` 通过。
- Android：`:app:assembleDebug`、`:app:assembleRelease` 通过；KSP 校验 `KSP VERIFY OK (release)`；`core:data` / `core:network` 单测通过；真机安装启动无崩溃。
- Windows：`cargo test --lib --no-default-features` 34 项通过；`npm run check` 0 错误；release 构建产出 `TodoWindows.exe`。
- 插件：`tools/build.ps1` 构建通过，`out/dsh-kit.dex` 已部署真机。

## 八、已知限制

- 剪贴板同步依赖输入法进程存活；App 自建通道只承担通知/提醒。
- 服务端提醒按 72h 过期，长时间离线会丢弃过期消息。
- 厂商 ROM 的「强行停止」会冻结闹钟与广播，需要用户手动重新打开 App。
- AI 功能仍依赖外部模型端点与用户自备密钥。

## 九、提交与标签

- 主要提交：`07193b3`（新通道实现）、`2e18566`（MQTT 双栈）、`a2c87df`（插件通道稳定性）
- 标签：`稳定版-2026-11`
