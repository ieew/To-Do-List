# 桌面便利贴功能规格

## 一、定位

- 便利贴是独立的 Tauri `WebviewWindow`，不是主窗口内的页面。
- MVP 只做「任务便利贴」：把已有 `task` 钉为便利贴，不引入独立便签实体。
- 便利贴是 `task` 的一个视图，数据层与主窗口看到的任务完全同构，零数据模型增量。

## 二、窗口配置与生命周期

- 创建配置：`decorations=false`、`transparent=true`、`always_on_top=true`、`skip_taskbar=true`、`resizable=true`。
- 按需创建、取消钉住时销毁，不常驻。同时存在的便利贴窗口上限为 5 个，超过时 UI 提示而非静默创建。
- 独立 Vite 多入口 `sticky.html`，只加载 `sticky-note/` 下极轻量 Svelte 组件，不加载主窗口完整 UI。
- 主窗口与便利贴窗口的 `additional_browser_args` 必须一致，抽为共享常量。
- MVP 不做点击穿透；如后续做，注意 `set_ignore_cursor_events` 会丢失 `WS_EX_TOOLWINDOW`，需手动重新应用。
- 主窗口与便利贴是**两个独立的 `WebviewWindow`**，禁止用同一窗口内的浮层或 CSS 缩放模拟：
  - 收起：`main.hide()`，随后创建独立便利贴窗口。
  - 展开：关闭便利贴窗口，再 `main.show()` + `main.setFocus()`。
  - 二者互斥，同一时刻最多一个主形态便利贴窗口。
- 禁止用频繁 `set_size` 模拟收起/展开；窗口尺寸由 `WebviewWindow` 配置决定。

## 三、数据流

- 便利贴读：通过 Tauri command `get_task_projection(task_id)` 从 Rust 侧获取投影，不直接访问 SQLite。
- 便利贴写：通过 Tauri command `update_task_field(task_id, field, value)` 发给 Rust 侧，走正常 op 生成路径（同一事务：业务表 + `op_log` + `local_hlc` + `pending_ops`）。不产生特殊 op。
- 同步信号：Rust 侧投影变化时 `emit projection-updated` 事件，主窗口和便利贴窗口都监听并重新拉取。禁止窗口间直接通知或轮询 SQLite。

## 四、持久化（本地，不同步）

- 便利贴窗口位置 `x`/`y`、尺寸 `width`/`height`、置顶状态、透明度、被钉的 `task_id` 存客户端本地表。
- 这些状态不进入 `op_log`，不参与同步。便利贴布局是设备本地偏好。

## 五、后续迭代（不在 MVP）

- 独立便签（新实体 `note`，复用 op 与 LWW 机制）。
- 点击穿透。
- 单窗口多便签方案（如便利贴数量需求超过 5 个且内存敏感）。

## 六、跨窗口数据与事件

- 所有窗口（主窗口、主形态便利贴、浮动便利贴）只通过 Tauri command 从 Rust 侧读取投影，禁止窗口间直接传递业务数据，禁止任一窗口把业务数据写入 `localStorage`。
- Rust 侧在任何写命令提交后 emit `projection-updated`，payload 至少包含 `workspace_id`；各窗口监听后重新调用 `bootstrap` / `get_projection`。
- 便利贴窗口 label 规则：主形态 `sticky-main`，浮动 `sticky-<n>`；主窗口 label 固定为 `main`。
- 浮动便利贴上限 5 个，由 Rust 侧计数并拒绝超额创建，返回可展示的错误码 `sticky_limit_reached`。
- 主窗口隐藏时仍保持进程与投影源存活；关闭主窗口（`close`）时一并销毁所有便利贴窗口。
- 便利贴窗口位置、尺寸、置顶状态、绑定的 `task_id` 存客户端本地偏好表，按 label 记录，不进 `op_log`。
- 浏览器预览没有 Tauri 多窗口能力，可降级为页面内浮层（仅供视觉验收），但必须在 README 标注「预览降级」，生产构建不得依赖该路径。
