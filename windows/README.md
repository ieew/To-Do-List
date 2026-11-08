# 待办清单 · Windows 端 UI 原型

按 `docs/` 设计文档实现的 Windows 端界面原型：Tauri 2 + Svelte 5 + Vite，设计令牌与 `docs/design-tokens.md` 一致。

> 当前为**视觉与交互原型**：任务数据在内存投影中演示，刷新即还原。Rust 壳已可启动，后续把 `store.svelte.ts` 的内存操作替换为 typed IPC + SQLite op_log/reducer 即可。

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

## 预览截图

| 主界面 | 详情抽屉 |
| --- | --- |
| ![主界面](shots/main.png) | ![详情抽屉](shots/detail.png) |

| 主窗口便利贴 | 设置 |
| --- | --- |
| ![便利贴](shots/sticky.png) | ![设置](shots/settings.png) |

更多截图：`shots/calendar.png`、`shots/stats.png`、`shots/global.png`、`shots/dark.png`、`shots/ai.png`、`shots/floating.png`。

## 已实现内容

- **S1 主窗口**：自定义标题栏、同步状态、可拖拽侧栏（150–300px，双击重置）、清单/标签/成员/AI 入口。
- **任务**：分组（今天/即将到来/已完成）、优先级、截止与提醒、标签 chip、子任务进度、AI 徽章、逾期高亮、拖拽排序。
- **详情抽屉**：320px 固定宽、详情/历史 Tab、字段编辑、标签增删、子任务增删勾选、备注、删除确认、保存生成历史 op。
- **历史时间线**：人工/AI/系统节点样式、全部/仅 AI/仅我筛选、diff、op_id/model/source、撤销生成反向 op（不删除原 op）。
- **全局历史（S4）**：侧栏入口、跨任务时间线、点击任务跳转。
- **便利贴（S2/S3）**：主窗口收起形态、今日进度、快捷添加、置顶、展开；浮動便利贴最多 5 个、可拖拽、堆叠偏移 24px。
- **设置覆盖层**：外观（浅色/暗色、密度、侧栏宽度）、任务、提醒、AI、同步与数据、成员、快捷键、关于；脏状态、保存/放弃。
- **AI 助手**：本地端点、提示词快捷项、生成草案、确认写入（`source="ai"`），历史可撤销。
- **快捷添加与右键菜单**：延期、钉为便利贴、查看历史、删除。
- **键盘**：`Esc`、`Ctrl+N`、`Ctrl+F`、`Ctrl+Shift+T`、`Ctrl+,`、`Ctrl+Enter`、`↑/↓`、`Enter`、`Space`。
- **视觉**：暗色令牌、焦点环、骨架屏、空态、错误提示、`prefers-reduced-motion`、图标全部替换为 `lucide-svelte`（无 emoji 图标）。

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

例如 <http://127.0.0.1:1420/?demo=detail>。

## 目录

```text
windows/
├── src/
│   ├── styles/tokens.css         设计令牌（唯一视觉参数来源）
│   ├── lib/
│   │   ├── types.ts              Task / HistoryOp / Settings 等领域类型
│   │   ├── utils.ts              时间、截止、优先级格式化
│   │   ├── demo-data.ts          演示投影与历史种子
│   │   └── store.svelte.ts       内存投影、op 式历史、撤销、设置
│   ├── components/               TitleBar / Sidebar / TaskRow / DetailDrawer …
│   └── App.svelte                三栏布局、快捷键、窗口形态切换
├── src-tauri/                    Tauri 2 桌面壳（app_info 命令）
├── scripts/                      Edge/Puppeteer 视觉验收脚本
├── desktop/                      已构建的调试版 exe
└── shots/                        验收截图
```

## 与设计文档的对应

| 文档 | 实现 |
| --- | --- |
| `docs/design-tokens.md` | [`src/styles/tokens.css`](src/styles/tokens.css) |
| `docs/windows-frontend-spec.md` | Svelte 5 Runes、无外部状态库、前端不持久化业务数据 |
| `docs/ui-implementation-spec.md` S1 | [`App.svelte`](src/App.svelte)、[`TaskRow.svelte`](src/components/TaskRow.svelte)、[`DetailDrawer.svelte`](src/components/DetailDrawer.svelte) |
| S2 / S3 便利贴 | [`StickyMode.svelte`](src/components/StickyMode.svelte)、[`FloatingSticky.svelte`](src/components/FloatingSticky.svelte) |
| S4 全局历史 | [`GlobalHistory.svelte`](src/components/GlobalHistory.svelte)、[`HistoryTimeline.svelte`](src/components/HistoryTimeline.svelte) |
| 设置与快捷键 | [`SettingsOverlay.svelte`](src/components/SettingsOverlay.svelte) |
| `docs/ai-local-integration.md` | [`AiModal.svelte`](src/components/AiModal.svelte)，写入 `source="ai"`，历史可撤销 |

## 常用命令

```bat
npm run dev          # 浏览器预览
npm run build        # 前端生产构建
npm run check        # svelte-check
npm run tauri dev    # 原生窗口开发
npm run tauri:build  # 原生构建（no bundle）
npm run shoot        # 用 Edge 批量截图（需先启动 dev）
npm run interact     # 端到端交互回归（需先启动 dev）
```

原生构建说明：本机使用 `x86_64-pc-windows-gnu`，Tauri 的 `crate-type` 已收窄为 `rlib`，避免 MinGW/UCRT 下 cdylib 导出序号过大；`scripts/start-desktop.bat` 会把 WinLibs 加入 `PATH`。

## 下一步接 Rust

1. 用 `tauri-specta` 生成 `src/lib/ipc-bindings.generated.ts`，新增 `get_projection`、`update_task_field`、`get_history`、`undo_op` 等命令。
2. 把 [`store.svelte.ts`](src/lib/store.svelte.ts) 的写操作换成 IPC 调用，Rust 侧同一事务写业务表 + `op_log` + `local_hlc` + `pending_ops`。
3. 接入 SQLite WAL 与 HLC/OR-Set reducer，前端只读投影。
4. 便利贴改为独立 `WebviewWindow` + `sticky.html`；通知接 `tauri-plugin-notifications`。
