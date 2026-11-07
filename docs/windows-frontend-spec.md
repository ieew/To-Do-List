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
- 前端禁止将业务数据持久化到 `localStorage` / `IndexedDB`。唯一持久化在 Rust 侧 SQLite。
- 前端禁止实现任何合并、HLC、权限裁决逻辑。

## 四、IPC 类型共享

- 使用 `tauri-specta` 生成 TypeScript 类型化客户端。
- 所有 `#[tauri::command]` 加 `#[specta::specta]`；所有跨 IPC DTO 和错误枚举派生 `specta::Type`。
- 生成文件 `src/lib/ipc-bindings.generated.ts` 纳入 git。
- CI 中运行生成步骤并检查 `git diff` 为空；Rust DTO 变更但前端未重新生成时 CI 失败。
- 前端组件通过 `src/lib/ipc.ts` 的薄封装调用生成的 `commands.xxx()`，不直接 import 生成文件，以便集中处理 trace 和错误映射。

## 五、目录结构

- `src-tauri/src/commands/` 按功能纵向切分（`task.rs` / `sync.rs` / `history.rs` / `sticky.rs`）。
- `ui/src/views/` 按功能纵向切分（`tasks/` / `history/` / `sticky-note/`）。
- `ui/src/lib/state/` 按域拆分 `.svelte.ts` 全局状态模块。
- 禁止横向巨型层（一个 `services/` 或 `utils/` 装所有逻辑）。
