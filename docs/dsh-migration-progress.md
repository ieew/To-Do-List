# dsh 推送链路改造 · 迁移进度（2026-11-26 归档）

按 [`dsh-push-reliability-design.md`](./dsh-push-reliability-design.md) §12 的阶段表推进，
一次一个阶段。本文件记录「做到哪、证据是什么、还剩什么」，供中断后接手。

## 一、阶段状态

| 阶段 | 内容 | 状态 | 提交 |
| --- | --- | --- | --- |
| 1 | §9 账号态数据安全（A15/A16/A17/A18/A19 + 桌面端 W1） | ✅ 完成，真机验收 | `8d88840` `95639fe` `a477e03` |
| 2 | §5.5 凭证撤销（App 入口 + 插件终态） | ✅ 完成，真机验收 | `f277dfe` |
| 3 | §7.3 插件 append-only 队列 | ✅ 完成，JVM 层验证 | `2d72655` |
| 4 | §8.1 claim 降级 + §7.1 进程内单实例 | ✅ 完成，线程数真机验证 | `644e546` |
| 5 | §5.1–5.3 票据持久化 / 自管重连 / 退避 jitter | ✅ 完成，端到端验证 | `020fdc0` |
| 6 | §3.0.1 版本协商 + 启动自检 App 是否安装 | 🔶 代码完成，**真机验收未做** | 见本节末 |
| 7 | §6.7 通道判活互斥 | ⬜ 未开始 | — |
| 8 | §6.1–6.3 降级通道落库 + 统一通知（Room v3） | ⬜ 未开始 | — |
| 9 | §7.2 / §6.4 自适应轮询（2–15 分钟） | ⬜ 未开始 | — |
| 10 | §4.1 提醒索引（UPSERT 带 COALESCE） | ⬜ 未开始 | — |
| 11 | §4.2 / 4.3 nudge 合并与背压 | ⬜ 未开始 | — |
| 12 | §7.4 / 7.7 日志降噪、自诊断 | ⬜ 未开始 | — |
| 13 | §4.6 指标 + §8.2 时延面板 + 长跑 | ⬜ 未开始 | — |

## 二、已完成的验收证据

### 阶段 1（账号态）

- ① 断网改 3 条 → 联网全推：服务端 `op_log` 365 → 386，免重登。
- ③ 登出再重登同地址：不弹换号确认，386 → 407。
- ⑤ 换地址：弹「切换账号会清空本地未推送的修改」，取消后数据与服务端均未变。
- A17：access 过期 + refresh 已吊销 → 横幅「登录已过期 · N 条修改待推送」，过期态继续建任务 N 就地增长。
- ④⑦ 由单测覆盖（`BaseUrl.normalize` 等价、被拒 op 保留可重试）；⑧ 桌面端无 Rust 工具链，仅代码审阅。

### 阶段 2（撤销）

- 撤销 `dsh-ime-013c95eb` → 服务端 `revoked=1`，凭证三字段全 NULL；界面「已在 App 中撤销推送权限」。
- 重新启用 → 新设备 `dsh-ime-1f30efcb`，旧设备保持 `revoked=1`。
- 服务端 102 项测试全过。

### 阶段 3（队列）

- JVM 86 项（新增 17 项：崩溃重放、半行容错、done 幂等、写放大）。
- 20 条通知写盘：队列 3,420 B vs 旧方案 23,105 B。

### 阶段 4（单实例 + claim）

- 同一 Trime 进程内 3 次 boot（1 主 + 2 次「已有主实例，跳过」），`dsh-kit` 线程数恒为 1。

### 阶段 5（票据 / 重连）

- 两次冷启动之间 `credential_rotated_at` 不变 → 第二次启动零 HTTP。
- 端到端：`POST /v1/dsh/push` → `ack`，`created_at` 到 `acked_at` **197ms**。

### 阶段 6（版本协商）—— 待验收

代码已完成并通过编译：

- 插件：`Kit.appInstalled`（查 `com.todo.android`）、`Session.PROTOCOL = 1`、`status()` 带 `version`/`protocol`、`TodoClient.dshReport(connected, error, version, protocol)`。
- App：`DshStatus.imeVersion/imeProtocol` + `DshProtocol.VERSION`、`noteImeStatus(..., version, protocol)`、`ImeContract.EXTRA_VERSION/EXTRA_PROTOCOL`、设置页两条降级提示。
- 文档：`ime-push-client.md` §21.3。

**没做的**：推新 dex + 装新 release 后，实际看设置页是否正确显示/不显示提示；
也没验证「插件协议偏旧/偏新」两个分支在真机上的表现（可用临时改 `PROTOCOL` 的方式造）。

## 三、接手时的环境备忘

### 工具链（都在仓库内，沙箱只允许写这里）

- JDK：`.toolchain/jdk`
- Android SDK：`.toolchain/android-sdk`（platform 37、build-tools 36.0.0）
- Gradle 缓存：`.toolchain/gradle-home`
- 签名：`.toolchain/keystore/qianxue.jks`（别名 `qianxue`，密码 `Fw34r@3468`，从手机 pull）

### 编译

```bash
# App（必须带 ANDROID_USER_HOME，否则报 /home/qianxue/.android 只读）
cd android && export JAVA_HOME=$PWD/../.toolchain/jdk \
  ANDROID_HOME=$PWD/../.toolchain/android-sdk ANDROID_SDK_ROOT=$ANDROID_HOME \
  ANDROID_USER_HOME=$PWD/../.toolchain/android-home \
  GRADLE_USER_HOME=$PWD/../.toolchain/gradle-home && ./gradlew :app:assembleRelease

# 插件
cd ime-plugin && export JAVA_HOME=$PWD/../.toolchain/jdk PATH=$JAVA_HOME/bin:$PATH \
  AJAR=$PWD/../.toolchain/android-sdk/platforms/android-37.0/android.jar \
  R8=$PWD/../.toolchain/android-sdk/cmdline-tools/latest/lib/r8.jar && bash tools/build.sh
```

**不要并行跑两个 Gradle**：共用 `GRADLE_USER_HOME` 会互相等锁，表现为卡在 `kspDebugKotlin` 十几分钟。

### 装机

```bash
cd android/app/build/outputs/apk/release
zipalign -p -f 4 app-release-unsigned.apk app-aligned.apk   # 名称以实际产物为准
apksigner sign --ks .toolchain/keystore/qianxue.jks --ks-key-alias qianxue \
  --ks-pass 'pass:Fw34r@3468' --key-pass 'pass:Fw34r@3468' --out app-signed.apk app-aligned.apk
adb install -r app-signed.apk       # 同签名覆盖安装，保留数据
```

### adb

- 设备：小米 `houji` 23127PN0CC / Android 16，无线调试。IP 和端口会变（192.168.1.103 → .111，端口 37033 → 35527 → …）。
- 沙箱内 `$HOME` 只读，需 `export HOME=$PWD/.toolchain/android-home` 再起 adb。
- 每条 bash 都是独立进程，`adb connect` 与后续命令必须写在同一条命令里。
- `.toolchain/adb.sh` 里的 `D()` 只是 `adb shell` 包装：**`push` / `pull` 不能用 `D`**，要用 `$ADB -t $T push`。
- 用户约定：**每次截图或操作手机后立刻 `adb shell input keyevent 3` 回桌面**。

### 服务端

- 跑在 `0.0.0.0:8001`，DB `server/data/server.sqlite3`。改库前先 `cp` 备份到 `.toolchain/`。
- `server/.venv` 没有 pytest。测试用独立环境：`.toolchain/pyenv`（用 uv 建的，`UV_CACHE_DIR=.toolchain/uv-cache`）。

```bash
cd server && ../.toolchain/pyenv/bin/python -m pytest tests -q     # 102 项
```

- 登录换 JWT：`POST http://127.0.0.1:8001/v1/auth/login`，`{"username":"qianxue","password":"Fw34r@3468"}`，token 在 `tokens.access_token`。

## 四、后续阶段的已知坑

1. **阶段 8 会把 Room 升到 v3**，之后装回旧 APK 会崩。那一步之后覆盖安装要谨慎，必要时先备份。
2. **阶段 10 的提醒索引 UPSERT 必须带 `COALESCE(t.remind_hlc, '')`**：漏掉会让首次写入被 NULL 比较静默吞掉——索引永远为空、提醒永不触发，且不报错。
3. **插件私有日志读不到**：`Kit` 的日志在 Trime 私有目录 `files/dsh-kit/logs/kit-*.log`，release 包装机后 `run-as` 进不去。判断插件走了哪条分支只能靠服务端 DB 时间戳或 `/sdcard/Documents/rime/logs/dsh.log`（只有 boot/工具层日志）。阶段 7 的判活逻辑可能需要给插件加一条「关键状态也写 sdcard」的出口。
4. **别用 `svc wifi disable` 模拟断网**：无线 adb 跑在 WLAN 上，关掉就把自己踢下线，而且输入法的无线调试不会自动恢复。用 `settings put global http_proxy 127.0.0.1:9` 造「网络黑洞」，但记得用完立刻 `settings put global http_proxy :0`——它会影响手机上所有 App（用户已报障过一次）。
5. **中文输入法会让 `input text` 变中文**：Trime（小鹤音形）和 Gboard 中文模式都会把 ASCII 转成候选。测试创建任务用纯数字标题 + `keyevent 66`。
6. **App 输入框真实坐标** `[174,805][1074,858]`，点击点 `(500, 830)`；截图预览是缩放的，adb 坐标按 1200×2670 算。

## 五、未验证清单（诚实记录）

| 项 | 状态 |
| --- | --- |
| 阶段 6 真机验收 | 未做（代码与编译已完成） |
| 插件侧撤销终态日志 | 未取得（私有目录不可读） |
| claim 800ms 超时的真机触发 | 未做 |
| App 断网重连 + jitter 分布 | 未做（只有代码审阅） |
| 桌面端 W1 的 `cargo test` | 未做（本机无 Rust 工具链） |
| §9.7 验收 ⑧（桌面端一致性）、② 完整真机复现 | 未做 / 用组合证据替代 |

## 六、下一步建议

1. 先补阶段 6 的真机验收：编 release → 签名 → 装机 → 设置页「数据通道」看是否出现版本提示（`protocol=1` 时应无提示）；再临时把插件 `Session.PROTOCOL` 改成 `0` 编译推开，确认出现「输入法插件版本过高/过旧」的对应提示。
2. 然后进入阶段 7（§6.7 通道判活）：App 主动 ping 插件、超时接管、连续 3 次失败让出。验收需要看两端的连接状态迁移，建议先解决上面的「插件日志可见性」问题。
