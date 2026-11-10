# Windows 端前端与 IPC 规格（Tauri 2）

## 一、技术栈裁定

- Windows 端采用 **Tauri 2 + Rust 后端 + Svelte 5 前端**，撤销早期 WinUI 3 方案。
- 通知使用 `tauri-plugin-notifications`，打包使用 Tauri bundler。

## 二、框架

- 前端使用 Svelte 5 + Vite SPA 模式。不使用 SvelteKit 的 SSR；如使用 SvelteKit，必须配置静态适配器并禁用 SSR。
- 不使用 React 或 Vue。选择理由：编译时优化带来最低运行时内存开销，与高负载电脑的低资源目标一致。

## 三、状态管理

- 使用 Svelte 5 原生 Runes（`$state` / `$derived`），不引入 Zustand / Pinia / Redux 等外部状态库。
- 前端状态分层：
  - 组件局部 UI 状态：`$state` 在组件内。
  - 全局 UI 状态（主题、侧栏、便利贴置顶）：`$state` 在 `.svelte.ts` 模块中。
  - 投影数据（任务、标签、历史、同步状态）：Rust 是唯一真相源；前端通过 typed commands 拉取，存入 `$state` 或 `$derived`，拉取即覆盖。
- 同步层只存在于 Rust：前端不实现 push/pull、HLC 校正、令牌刷新或权限判断。前端通过 `BootstrapResponse.sync` 获取初始状态，并监听 `sync-status-changed` / `sync-notice`；`projection-updated` 携带 `workspaceId = null` 时刷新整个投影。
- 前端禁止将业务数据持久化到 `localStorage` / `IndexedDB`。唯一持久化在 Rust 侧 SQLite。
- 允许用 `localStorage` 缓存**纯 UI 偏好**，用于启动首帧避免闪变（当前白名单仅 `todo.theme`）；任务、工作区、成员、历史、密钥等一律不得写入，仍只经 Rust 投影。
- 前端禁止实现任何合并、HLC、权限裁决逻辑。
- 视图范围（`activeListId` / `activeTagId` / `viewTasks` / `nowMs` / `currentTime`）属于前端派生状态，由 `$derived` 计算，不写库；虚拟清单 `ALL_LIST_ID = 'all'` 只存在于前端。完整规则见 `task-time-and-scope-model.md`。

## 四、IPC 类型共享

- 使用 `tauri-specta` 生成 TypeScript 类型化客户端。
- 所有 `#[tauri::command]` 加 `#[specta::specta]`；所有跨 IPC DTO 和错误枚举派生 `specta::Type`。
- 生成文件 `src/lib/ipc-bindings.generated.ts` 纳入 git。
- CI 中运行生成步骤并检查 `git diff` 为空；Rust DTO 变更但前端未重新生成时 CI 失败。
- 前端组件通过 `src/lib/ipc.ts` 的薄封装调用生成的 `commands.xxx()`，不直接 import 生成文件，以便集中处理 trace 和错误映射。
- 工作区级命令：`create_workspace`、`rename_workspace`、`delete_workspace`。新建返回 `Workspace`；删除返回删除后的当前工作区 id，前端据此立即切换作用域。
- 工作区级变更（新建 / 重命名 / 删除）广播 `projection-updated` 时 payload 的 `workspaceId` 为 `null`，表示所有窗口都要刷新；任务、成员等作用域内变更仍携带具体 `workspaceId`。
- `create_workspace` 必须在同一事务内写入工作区、成员关系、4 个预设清单与 5 个预设标签；前端不得自行拼装预设数据写库。
- `create_task` 接受 `startAt`，未传时 Rust 以创建时刻作为开始时间；`update_task_field` 接受 `startAt` 并映射为 `start_at`。

## 五、目录结构

- `src-tauri/src/commands.rs` 按功能纵向切分命令；`src-tauri/src/sync/` 负责会话、wire 映射、HTTP 客户端、同步引擎与后台循环。
- `src/components/` 按界面区块拆分；`src/lib/` 存放 IPC、类型、派生状态与演示数据。
- 禁止横向巨型层（一个 `services/` 或 `utils/` 装所有逻辑）。

## 六、启动与首屏

> 目标：WebView2 初始化期间用户不能看到白色或空白窗口；从「窗口出现」到「可交互」只允许一次过渡。

- **窗口可见性**：主窗口必须 `"visible": false` 创建，禁止创建即显示。显示时机为「启动占位已进入 DOM」：
  - `index.html` 末尾内联脚本调用 `plugin:window|show`（不依赖模块加载，先于主入口执行）；
  - `main.ts` 挂载时再显式 `show()` 一次，作为脚本部分失败时的兜底；
  - Rust `setup()` 起 3 秒兜底线程，窗口仍不可见则强制显示。任何前端异常都不得让窗口永久隐藏。
- **启动占位唯一**：`index.html` 内联 `#boot`（Logo + 进度条，背景与主题一致），覆盖到 `bootstrap` 完成且 Svelte 首次挂载之后，再淡出（约 180ms；`prefers-reduced-motion` 下直接移除）。禁止「启动占位 → 骨架屏 → 内容」的多层接力，也不使用全屏 spinner。
- **初始化顺序**：Tauri 模式下 `store.loading` 初值为 `true`；`main.ts` 先 `await store.init()`（上限 3 秒）再 `mount`，确保不渲染种子/演示数据；失败落到 `App.svelte` 的可重试错误覆盖层。
- **主题一致**：`settings.theme` 写入 `localStorage['todo.theme']` 供首帧占位选色（见第三节白名单）；Rust 在显示窗口前按同一主题调用 `set_background_color`，避免「浅色占位 → 暗色界面」闪变。
- **首屏分包**：非首屏组件（`CalendarView`、`StatsView`、`GlobalHistory`）与浏览器预览专用组件（`StickyMode`、`FloatingSticky`）必须动态 `import()`；构建产物入口 HTML 不得 `modulepreload` 这些 chunk。启动 1.2 秒后可空闲预取。
- **便利贴窗口**：同样隐藏创建，渲染就绪后再显示并完成主窗口换手，见 `sticky-note-spec.md` 二。
- **验收**：冷启动无纯白帧；窗口首帧为启动占位或已就绪界面；暗色主题重启无浅色闪变；前端脚本抛错时窗口仍能在 3 秒内显示。
