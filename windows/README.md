# 待办清单 · Windows 端 UI 原型

按 `docs/` 设计文档实现的 Windows 端界面原型：Tauri 2 + Svelte 5 + Vite，设计令牌与 `docs/design-tokens.md` 一致。

> 数据层已切换为 **Rust + SQLite `op_log` 投影**：主窗口与所有便利贴窗口都通过 Tauri 命令读取同一份投影，写操作生成 op 并广播 `projection-updated`。首次启动只生成默认工作区、默认清单（今天/工作/生活/学习）与常用标签，不写入任何演示任务或协作成员。浏览器预览（`npm run dev`）没有 Tauri 运行时，自动降级为同样的干净内存初始态，仅供视觉与交互验收。

## 启动与首屏

启动链路的目标是**不出现白色或空白窗口**，具体约定：

- **主窗口隐藏创建**：`tauri.conf.json` 里 `"visible": false`；`index.html` 末尾的内联脚本在启动占位进入 DOM 后调用 `plugin:window|show`，`main.ts` 挂载时再显示一次兜底。Rust 侧另起 3 秒兜底线程，前端脚本异常时强制显示，避免窗口永久隐藏。
- **单层启动占位**：`#boot`（Logo + 进度条）一直覆盖到 `bootstrap` 完成、Svelte 挂载之后，再 180ms 淡出；`prefers-reduced-motion` 下直接移除。不再出现「占位 → 骨架 → 内容」多层接力。
- **先取数再渲染**：Tauri 模式 `store.loading` 初值为 `true`，`main.ts` 先 `await store.init()`（上限 3 秒）再 `mount`，不会先渲染一遍种子数据；失败时显示可重试错误。
- **首屏分包**：`StickyMode`、`FloatingSticky`、`CalendarView`、`StatsView`、`GlobalHistory` 按需动态 `import()`，启动 1.2 秒后空闲预取。构建产物 `dist/index.html` 不再预加载浏览器预览专用的便利贴 chunk。
- **主题一致**：`App.svelte` 把 `settings.theme` 写入 `localStorage['todo.theme']`，仅供 `index.html` 首帧占位选色（纯 UI 偏好缓存，业务数据仍只存 Rust 投影）；Rust 在显示窗口前按同一主题设置窗口底色，暗色用户重启不会闪白。
- **便利贴同样隐藏创建**：`StickyApp` 在投影与上下文就绪后 `show()`，再调用 `hide_main_window` 隐藏主窗口完成换手；Rust 侧 2.5 秒兜底（显示便利贴并隐藏主窗口）。细节见 [`sticky-note-spec.md`](../docs/sticky-note-spec.md) 二。

实测（本机 release，1120×720）：无纯白帧；窗口约 0.8s 出现且首帧即启动占位，约 1.0–1.2s 可交互。剩余耗时主要来自 WebView2 初始化，属于 Tauri 的启动下限。完整规格见 [`windows-frontend-spec.md`](../docs/windows-frontend-spec.md) 六。

## 快速查看

### 方式一：浏览器预览（最快）

```bat
cd windows
npm install
npm run dev
```

打开 <http://127.0.0.1:1420>。或直接双击 [`start-preview.bat`](start-preview.bat)。

### 方式二：原生 Windows 窗口

```bat
cd windows
npm install
npm run tauri dev
```

或双击 [`start-desktop.bat`](start-desktop.bat)。需要 Node.js、Rust 与 MinGW/MSVC 链接器；脚本会自动寻找 WinLibs。

已构建的 release 桌面版可直接运行 [`desktop/TodoWindows.exe`](desktop/TodoWindows.exe)（同目录需保留 `WebView2Loader.dll`），或双击 [`run-built.bat`](run-built.bat)。

> 说明：当前机器上 WebView2 的初始逻辑视口为 800×600，原生窗口的三栏布局在该视口下仍可用；若要看设计稿 1120×720 的完整比例，请使用浏览器预览（同一套前端）。

## 数据位置

原生版默认把数据放在**软件同目录下的 `data` 文件夹**：

```text
<软件目录>\data\todo.sqlite3        # 主库（WAL 模式）
<软件目录>\data\todo.sqlite3-wal    # WAL 提交日志
<软件目录>\data\todo.sqlite3-shm    # WAL 共享内存索引
```

- 可用环境变量 `TODO_DATA_DIR` 覆盖数据目录；[`start-desktop.bat`](start-desktop.bat) 会把它设为 `windows\data`，方便开发时查看与备份。
- 直接执行 `npm run tauri dev` 时，「软件目录」是可执行文件所在目录，通常是 `src-tauri\target\debug`，或 `CARGO_TARGET_DIR` 指向的目录；启动时控制台会打印实际数据目录。
- 若软件目录不可写（例如安装在 `Program Files`），启动时会自动回退到系统应用数据目录 `%APPDATA%\com.todo.windows`，避免无法启动。
- 首次切换到新路径时，如果 `data` 里还没有数据库，会把旧版本 `%APPDATA%\com.todo.windows` 下的 `todo.sqlite3`（含 `-wal` / `-shm`）复制过来；已有数据不会被覆盖。
- 备份或迁移时，关闭应用后整体拷贝 `data` 文件夹即可。

## 预览截图

| 主界面 | 详情抽屉 |
| --- | --- |
| ![主界面](shots/main.png) | ![详情抽屉](shots/detail.png) |

| 主窗口便利贴 | 设置 |
| --- | --- |
| ![便利贴](shots/sticky.png) | ![设置](shots/settings.png) |

更多截图：`shots/calendar.png`、`shots/stats.png`、`shots/global.png`、`shots/dark.png`、`shots/ai.png`、`shots/floating.png`。

## 已实现内容

- **S1 主窗口**：Windows 11 风格自绘标题栏（左侧图标、标题、同步状态；右侧业务按钮与最小化/最大化/关闭，控制按钮贴窗口右边缘，最大化后切换为还原图标）、可拖拽侧栏（150–300px，双击重置）、清单/标签/成员/AI 入口。
- **Rust 数据层**：SQLite `op_log` 只追加；HLC 全序、字段级 LWW、标签与子任务 OR-Set、确定性投影 reducer；写操作同事务写 `op_log` + 推进 `local_hlc` + 广播 `projection-updated`。
- **多工作区**：侧栏工作区切换 popover，支持切换与新建；`bootstrap` 按 `workspace_id` 读取工作区、成员、清单、标签、任务与历史；`active_workspace_id` 存本地偏好。
- **成员列表**：侧栏与设置页均来自 `membership` 投影，按当前工作区过滤，角色徽章中文化；支持角色变更与移除（本地模拟，未连接服务端）。
- **AI 密钥**：设置页密钥框可编辑、可显示/隐藏、可保存/清除，状态徽章「已配置/未配置」；Windows 落 Credential Manager，明文不写 SQLite / op_log / 日志；API 直连未配置时生成按钮禁用并引导到设置。
- **独立便利贴窗口（S2/S3）**：点击「便利贴」后由 Rust 创建隐藏的 `sticky-main` 独立 `WebviewWindow`，其渲染就绪后显示自身并调用 `hide_main_window` 隐藏主窗口（换手过程不露桌面空白）；浮动便利贴各自独立窗口，最多 5 个；所有窗口从 Rust 投影取数并监听 `projection-updated`。
- **任务**：分组（今天/即将到来/已完成）、优先级、截止与提醒、标签 chip、子任务进度、AI 徽章、逾期高亮、拖拽排序。
- **详情抽屉**：320px 固定宽、详情/历史 Tab、字段编辑、标签增删、子任务增删勾选、备注、删除确认、保存生成历史 op。
- **历史时间线**：人工/AI/系统节点样式、全部/仅 AI/仅我筛选、diff、op_id/model/source、撤销生成反向 op（不删除原 op）。
- **全局历史（S4）**：侧栏入口、跨任务时间线、点击任务跳转。
- **设置覆盖层**：外观（浅色/暗色、密度、侧栏宽度）、任务、提醒、AI、同步与数据、成员、快捷键、关于；脏状态、保存/放弃。
- **AI 助手**：API 直连兼容 OpenAI Chat Completions（DeepSeek / Ollama / LM Studio 等），密钥仅在 Rust 侧读取；草案预览、字段告警、批量阈值二次确认后写入（`source="ai"`），历史可撤销。网页版 DeepSeek 桥接源码保留，当前通过 `SHOW_WEB_BRIDGE = false` 在界面上隐藏，改为 `true` 即可恢复。
- **快捷添加与右键菜单**：延期、钉为便利贴、查看历史、删除。
- **键盘**：`Esc`、`Ctrl+N`、`Ctrl+F`、`Ctrl+Shift+T`、`Ctrl+,`、`Ctrl+Enter`、`↑/↓`、`Enter`、`Space`。
- **视觉**：暗色令牌、焦点环、骨架屏、空态、错误提示、`prefers-reduced-motion`、图标全部替换为 `lucide-svelte`（无 emoji 图标）；应用启动使用内联 Logo 占位并淡出，不出现白色窗口。

## 演示状态参数

浏览器预览可用查询参数直接打开某个界面，便于逐屏查看：

| 参数 | 效果 |
| --- | --- |
| `?demo=detail` | 打开首条任务详情 |
| `?demo=history` | 打开首条任务历史 |
| `?demo=global` | 打开全局历史 |
| `?demo=settings` | 打开设置 |
| `?demo=ai` | 打开 AI 助手 |
| `?demo=sticky` | 主窗口收起为便利贴 |
| `?demo=floating` | 打开 2 个浮动便利贴 |
| `?demo=calendar` | 日历视图 |
| `?demo=stats` | 统计视图 |
| `?demo=dark` | 暗色模式 |
| `?skeleton=1` | 演示任务列表骨架屏（默认直接出内容，无模拟加载延迟） |

例如 <http://127.0.0.1:1420/?demo=detail>。

## 目录

```text
windows/
├── src/
│   ├── styles/tokens.css         设计令牌（唯一视觉参数来源）
│   ├── lib/
│   │   ├── types.ts              Task / Workspace / Member / HistoryOp / Settings 等领域类型
│   │   ├── utils.ts              时间、截止、优先级格式化
│   │   ├── demo-data.ts          浏览器预览用的干净初始数据（Tauri 模式不加载）
│   │   ├── ipc.ts                Tauri 命令薄封装 + 浏览器降级路由
│   │   └── store.svelte.ts       投影缓存、工作区切换、写操作、设置
│   ├── components/               TitleBar / Sidebar / WorkspaceSwitcher / TaskRow / DetailDrawer …
│   ├── StickyApp.svelte          独立便利贴窗口的轻量入口
│   ├── sticky-main.ts            sticky.html 的启动脚本
│   ├── main.ts                   主窗口入口：显示窗口、等待 bootstrap、淡出启动占位、空闲预取
│   └── App.svelte                主窗口三栏布局、快捷键、窗口控制
├── index.html                    主窗口入口（内联启动占位 + 主题恢复 + 尽早 show）
├── sticky.html                   便利贴独立 Vite 入口
├── src-tauri/src/
│   ├── lib.rs                    Tauri Builder、命令注册、窗口生命周期
│   ├── core_domain.rs            HLC / op / LWW / OR-Set / reducer
│   ├── storage.rs                SQLite schema、迁移、op_log、本地偏好
│   ├── commands.rs               bootstrap / task / workspace / history / settings / ai key
│   ├── ai.rs                     AI 命令层：上下文、keyring、HTTP 调用、错误映射
│   └── windows.rs                sticky 窗口创建、关闭、主窗口显示隐藏
│   ai-core/                      纯 AI 逻辑：提示词、JSON 提取、schema 归一化、端点拼接
├── scripts/                      Edge/Puppeteer 视觉验收与原生窗口枚举脚本
├── desktop/                      已构建的调试版 exe
└── shots/                        验收截图
```

## 与设计文档的对应

| 文档 | 实现 |
| --- | --- |
| `docs/design-tokens.md` | [`src/styles/tokens.css`](src/styles/tokens.css) |
| `docs/windows-frontend-spec.md` | Svelte 5 Runes、无外部状态库、前端不持久化业务数据 |
| `docs/ui-implementation-spec.md` S1 | [`App.svelte`](src/App.svelte)、[`TaskRow.svelte`](src/components/TaskRow.svelte)、[`DetailDrawer.svelte`](src/components/DetailDrawer.svelte) |
| S2 / S3 便利贴 | [`sticky.html`](sticky.html)、[`StickyApp.svelte`](src/StickyApp.svelte)、[`StickyMode.svelte`](src/components/StickyMode.svelte)、[`FloatingSticky.svelte`](src/components/FloatingSticky.svelte) |
| S4 全局历史 | [`GlobalHistory.svelte`](src/components/GlobalHistory.svelte)、[`HistoryTimeline.svelte`](src/components/HistoryTimeline.svelte) |
| 工作区与成员 | [`WorkspaceSwitcher.svelte`](src/components/WorkspaceSwitcher.svelte)、[`Sidebar.svelte`](src/components/Sidebar.svelte)、[`SettingsOverlay.svelte`](src/components/SettingsOverlay.svelte) |
| 设置与快捷键 | [`SettingsOverlay.svelte`](src/components/SettingsOverlay.svelte) |
| `docs/ai-local-integration.md` | [`AiModal.svelte`](src/components/AiModal.svelte)，写入 `source="ai"`，历史可撤销；密钥走 Rust + Credential Manager |

## 常用命令

```bat
npm run dev             # 浏览器预览
npm run build           # 前端生产构建（含 index.html + sticky.html 多入口）
npm run check           # svelte-check
npm run tauri dev       # 原生窗口开发
npm run tauri:build     # 原生构建（no bundle）
npm run shoot           # 用 Edge 批量截图（需先启动 dev）
npm run interact        # 端到端交互回归（需先启动 dev）
npm run verify:changes  # 工作区/成员/密钥/独立便利贴专项验收（需先启动 dev）
npm run verify:native   # 原生双窗口 + Rust 投影 DOM 自检（需先构建调试版）
cd src-tauri/ai-core && cargo test   # AI 提示词/JSON 解析/schema 归一化单元测试
```

原生构建说明：本机使用 `x86_64-pc-windows-gnu`，Tauri 的 `crate-type` 已收窄为 `rlib`，避免 MinGW/UCRT 下 cdylib 导出序号过大；`start-desktop.bat` / `build-desktop.bat` 会把 WinLibs 加入 `PATH`，并把 `CARGO_TARGET_DIR` 指到 `%TEMP%\todo-target`。在中文路径下直接 `cargo check` 可能触发 MinGW 临时文件链接失败，请沿用脚本环境。`src-tauri/build.rs` 会自动把图标复制到 `%TEMP%\todo-build-icons` 并通过 `TAURI_CONFIG` 覆盖 `bundle.icon`，避免 `windres` 读取中文路径下的 `.ico` 失败。

## 双窗口自检

`npm run verify:native` 会以 `TODO_VERIFY_WINDOWS=1` 启动调试版，自动创建主形态便利贴窗口（创建时隐藏，由 `StickyApp` 就绪后自行显示），读取实际 WebviewWindow 列表与两个窗口的 DOM 状态后退出。预期输出：

```text
windows=2
main
sticky-main
mainDom=http://127.0.0.1:1420/#MAIN_OK:8
stickyDom=http://127.0.0.1:1420/sticky.html#STICKY_OK:4
```

`MAIN_OK` / `STICKY_OK` 表示两个窗口都成功从 Rust `op_log` 投影加载了任务；`STICKY_ERROR` 表示便利贴初始化投影失败。该自检钩子只在 `debug_assertions` 构建中存在。

## 后续工作

1. 用 `tauri-specta` 生成 `src/lib/ipc-bindings.generated.ts`，替换手写 DTO，CI 校验生成结果无 diff。
2. 接入服务端 `/v1/sync/push`、`/v1/sync/pull`，把本地 `pending_ops` 推送并按 cursor 拉取；当前 `syncNow` 仍为本地模拟。
3. 通知接 `tauri-plugin-notifications`，实现 `remind_at` 调度与补发。
4. Android 端按同一套 `op_log` reducer 与 `shared/testvectors/` 实现。
