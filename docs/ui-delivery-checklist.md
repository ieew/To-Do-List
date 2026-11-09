# UI 交付清单与注意事项

> 交付给 AI 实现时，先读本文件，再读 `design-tokens.md` 与 `ui-implementation-spec.md`。
> 设计稿（HTML 草图）是视觉参照，不是实现代码；禁止直接复制 HTML 到生产代码。

## 一、交付物清单

### 1.1 Windows（Tauri 2 + Svelte 5）

| 交付物 | 路径 | 验收 |
|---|---|---|
| 设计令牌 | `ui/src/lib/styles/tokens.css` | 与 `design-tokens.md` 一致 |
| 主界面 | `ui/src/views/tasks/` | S1 全部组件与交互 |
| 便利贴入口 | `ui/sticky.html` + `ui/src/sticky-main.ts` + `ui/src/StickyApp.svelte` | S2、S3，独立 WebviewWindow |
| 工作区切换器 | `ui/src/components/WorkspaceSwitcher.svelte` | 切换、新建、更多菜单（重命名 / 删除）、成员管理入口 |
| Rust 投影层 | `src-tauri/src/core_domain.rs` + `storage.rs` | HLC / LWW / OR-Set / reducer、op_log |
| 详情抽屉 | `ui/src/views/tasks/DetailDrawer.svelte` | 开合、Tab、历史 |
| 历史时间线 | `ui/src/views/history/` | S4 |
| IPC 封装 | `ui/src/lib/ipc.ts` | 只调生成文件，不直接 import |
| 生成类型 | `ui/src/lib/ipc-bindings.generated.ts` | CI 校验无 diff |
| 图标 | `ui/src/lib/icons.ts` | 映射表见 `design-tokens.md` 七 |

### 1.2 Android（Kotlin + Compose）

| 交付物 | 路径 | 验收 |
|---|---|---|
| 设计令牌 | `core/designsystem/.../Tokens.kt` | 与 `design-tokens.md` 一致 |
| 主界面 | `feature/tasks/` | S5 |
| 详情页 | `feature/tasks/detail/` | S6 |
| 历史页 | `feature/history/` | S7 |
| 桌面卡片 | `app/widget/` | S8 三种尺寸 |
| 图标 | Material Symbols | 映射表见 `design-tokens.md` 七 |

## 二、设计稿中未写全、实现时必须补齐的项

### 2.1 视觉

1. **暗色模式**：设计稿只有浅色。令牌文件必须预留暗色映射（`design-tokens.md` 1.4），MVP 可通过 `prefers-color-scheme` 只读跟随，不做设置页。
2. **图标替换**：设计稿用 emoji/Unicode。生产必须换 `lucide-svelte` / Material Symbols，映射表已给出。禁止直接输出 emoji。
3. **焦点态**：设计稿只做了悬停。所有可交互元素必须补 `:focus-visible`，焦点环 `0 0 0 2px var(--color-primary-50)`。
4. **禁用态**：按钮、复选框、输入框的禁用样式设计稿未画，按 `ui-implementation-spec.md` 3.6 补。
5. **加载态**：设计稿没有加载态。应用启动使用 `index.html` 内联启动占位（Logo + 进度条，见 3.12）；抽屉、历史时间线与应用内重新加载按 3.12 补骨架。
6. **空态**：设计稿只有便利贴空态。其他 4 种空态按 3.11 补。
7. **错误态**：设计稿没有。按 3.13 补 5 种错误呈现。
8. **窄屏**：设计稿按 1120px 固定。主窗口最小 960×600，侧栏 150px 时中栏仍要可用；搜索框 `min-width: 60px`，按钮组允许换行或收纳进 `⋯`。
9. **长文本**：任务标题、标签、成员名必须 `text-overflow: ellipsis`，设计稿已加部分，实现时全量检查。
10. **高 DPI**：所有尺寸用逻辑像素，图标用矢量，不导出位图。

### 2.2 交互

1. **复选框与父级点击分离**：设计稿用 `stopPropagation`，生产必须保证勾选不打开详情。
2. **抽屉重复打开**：点击不同任务只切内容，不重播宽度动画；每次打开回到「详情」Tab。
3. **焦点管理**：抽屉打开焦点移入，关闭焦点回到触发任务；`Esc` 关闭时同理。
4. **侧栏宽度持久化**：设计稿用 `localStorage`，生产必须改为客户端 SQLite 本地偏好表（`sticky-note-spec.md` 四），不进 `op_log`。
5. **便利贴上限**：设计稿用 `alert`，生产必须用 UI 提示（轻提示或对话框），不阻塞；Rust 返回 `sticky_limit_reached`。
6. **浮动便利贴**：设计稿是 `position: fixed` 模拟，生产必须是独立 `WebviewWindow`，由 Rust 侧创建销毁，前端只发 `open_sticky_window` / `close_sticky_window`。
7. **主窗口 ⇄ 便利贴**：生产必须是创建隐藏的 `sticky-main` 独立窗口，待其渲染就绪后显示，再调用 `hide_main_window` 隐藏主窗口；展开时关闭 `sticky-main` + `main.show()`。禁止先隐藏主窗口再等待便利贴加载（会露出桌面空白），也禁止同一页面形态切换或 CSS 缩放模拟。
8. **便利贴数据源**：便利贴窗口不得复用主窗口内存 store；必须调用 `bootstrap` 从 Rust 投影取数，并监听 `projection-updated`。
8. **键盘快捷键**：设计稿未实现，按 `ui-implementation-spec.md` 4.2 补全。
9. **滑动手势**：Android 右滑完成、左滑延期/删除，阈值 72dp，回弹 200ms。
10. **拖拽排序**：Windows 用 DnD，Android 用长按拖拽，落点 2px 主色线。

### 2.3 数据与状态

1. **前端不持久化业务数据**：设计稿里任务是硬编码 HTML。生产必须通过 `bootstrap` / `get_projection` 从 Rust `op_log` 投影拉取，前端只存 `$state`，禁止 `localStorage`/`IndexedDB` 存任务（`windows-frontend-spec.md` 三）。
2. **多窗口一致性**：任何写命令提交后 Rust 广播 `projection-updated`，主窗口与所有便利贴窗口重新拉取；禁止窗口间直接传业务数据或轮询 SQLite。
2. **勾选走 op 管道**：设计稿只切 class。生产必须调 `update_task_field`，同事务写业务表 + `op_log` + `local_hlc` + `pending_ops`。
3. **历史撤销**：设计稿只改按钮文案。生产必须生成更高 HLC 的反向 op，走正常上传路径，不删原 op。
4. **AI 徽章**：只在 `source="ai"` 时展示，不得用于装饰或猜测。
5. **权限拒绝**：设计稿未画。生产必须在抽屉顶部显示红条，带 `server_hlc`，op 标记 `REJECTED`，不自动重试。
6. **同步状态**：标题栏「已同步」是静态文字。生产必须绑定同步循环状态：已同步/同步中/离线/重试中。
7. **进度计算**：设计稿是写死 37%。生产必须 `done/total`，总数为 0 时显示 0%，动画 400ms。
8. **工作区不是摆设**：侧栏工作区按钮必须打开真实 popover，读取 `workspace` 投影，支持切换、新建、重命名与删除；新建工作区必须写入与默认工作区一致的 4 个预设清单与 5 个预设标签；切换或删除后重置筛选并刷新所有窗口。
9. **成员不是种子数据**：侧栏与设置页成员必须来自 `membership` 投影，按当前工作区过滤；禁止硬编码「我 / 小明 / 小红」。
10. **AI 密钥可编辑**：密钥输入框不得 `disabled`；支持显示/隐藏、保存、清除、已配置状态；明文只进系统安全存储，不进 SQLite / op_log / localStorage。
11. **首屏初始化**：Tauri 模式必须先 `bootstrap` 再渲染业务数据；主窗口隐藏创建，启动占位覆盖到数据加载完成并淡出，失败显示可重试错误，不得回退到硬编码演示数据。
12. **UI 偏好缓存白名单**：仅允许 `localStorage['todo.theme']` 作为启动首帧配色提示；其余状态（侧栏宽度、便利贴位置、任务数据等）一律走 Rust 本地偏好表或投影，禁止写入 `localStorage`。

### 2.4 工程约束（来自 `conventions.md`）

1. `core-domain` 不得 import 数据库/网络/UI。
2. 组件不得写死十六进制颜色与 px 魔法数字，只用令牌。
3. 错误必须带英文 `code` + 中文 `message`，可重试/不可重试类型区分。
4. 非测试代码禁止 `unwrap`/`expect`；Kotlin 禁止裸 `try-catch`；Python 禁止裸 `except`。
5. IPC 类型由 `tauri-specta` 生成，CI 校验无 diff。
6. 每个 op 信封 schema 校验，拒绝未知字段。
7. 提交信息 Conventional Commits，`type/scope` 英文、描述中文。

## 三、验收清单

### 3.1 视觉

- [ ] 所有颜色、间距、圆角、阴影来自令牌，组件内无硬编码
- [ ] 图标全部替换为图标库，无 emoji
- [ ] 焦点环在所有可交互元素上可见
- [ ] 禁用态、空态、加载态、错误态齐全
- [ ] 长文本省略，不撑破布局
- [ ] 最小窗口 960×600 下三栏仍可用
- [ ] 侧栏 150px 与 300px 两端无溢出
- [ ] 工作区切换 popover 可展开、切换、新建、重命名、删除，长名称省略
- [ ] 更多按钮与右键工作区行打开同一操作菜单，菜单不被侧栏或 popover 裁剪
- [ ] 仅剩一个工作区时删除菜单项禁用并显示提示
- [ ] 成员列表来自当前工作区投影，角色徽章为中文，空态与骨架齐全
- [ ] AI 密钥输入可编辑、可显示/隐藏、可保存/清除，状态徽章正确
- [ ] AI 助手默认只显示 API 直连；`SHOW_WEB_BRIDGE=true` 时才显示双 Tab 且切换不残留旧草案
- [ ] 网页桥接源码保留；恢复开关后，复制、打开、粘贴、解析按钮在未配置密钥时仍可用

### 3.2 交互

- [ ] 点击任务打开抽屉，点击复选框不打开
- [ ] `✕`/`Esc`/「删除」都能关闭抽屉，焦点回到触发任务
- [ ] 点击不同任务只切内容，不重播动画
- [ ] 侧栏拖拽 150–300，双击重置 180，刷新后保持
- [ ] 「📌 便利贴」收起主窗口，「↗」恢复
- [ ] 「＋ 新便利贴」创建浮动便利贴，最多 5 个，超出 UI 提示
- [ ] 快捷键全部生效（`Esc`、`Ctrl+N`、`Ctrl+F`、`Ctrl+Shift+T`、`Ctrl+Enter`、`↑↓`、`Enter`、`Space`）
- [ ] Android 右滑完成、左滑延期/删除、下拉同步、长按排序
- [ ] 点击「便利贴」后主窗口隐藏，任务栏出现独立便利贴窗口
- [ ] 原生窗口枚举同时存在 `main` 与 `sticky-main` 两个 HWND
- [ ] 浮动便利贴各自独立窗口，最多 5 个，超额轻提示
- [ ] 便利贴外层为直角，内部按钮/任务行保留小圆角
- [ ] 设置「外观 → 底色透明度」0–100% 生效并持久化；已打开的浮动便利贴即时更新
- [ ] 便利贴跟随浅色/暗色主题，重启不闪浅色
- [ ] 从标题文字、三横把手、header 空白处都能拖动；先操作勾选框/下拉/按钮后再拖仍可拖动；双击 header 不最大化
- [ ] 便利贴底部清单下拉切换后，任务范围、标题、进度标签同步变化；新建任务写入所选清单
- [ ] 浮动便利贴的「＋」展开行内输入，回车添加任务，Esc 收起；独立窗口中不出现无反应按钮
- [ ] 切换工作区后主窗口与便利贴窗口数据同步刷新
- [ ] 新建工作区后侧栏出现今天 / 工作 / 生活 / 学习 4 个清单与「重要」「本周」标签
- [ ] 删除当前工作区后自动切换到剩余工作区，删除最后一个工作区被拦截
- [ ] 菜单键盘 `↑`/`↓`/`Enter`/`Esc` 全部生效，`Esc` 逐层关闭
- [ ] API 直连生成走真实模型请求，失败只提示错误，不回退到硬编码草案
- [ ] 当前版本界面不出现网页桥接入口；源码保留且可通过开关恢复
- [ ] 恢复网页桥接后，可一键复制提示词、打开 DeepSeek、从剪贴板粘贴并解析
- [ ] 恢复网页桥接后，在未配置 API 密钥、`ai_enabled=false` 时仍可作为手动工具使用
- [ ] 解析失败显示具体错误，不进入预览、不写入 op
- [ ] 字段归一化告警在预览页逐条展示

### 3.3 数据

- [ ] 勾选走 `update_task_field`，产生 `op_log` 与 `pending_ops`
- [ ] 撤销生成反向 op，不删原 op
- [ ] AI 徽章只出现在 `source="ai"` 的实体
- [ ] 权限拒绝显示红条，op 标记 `REJECTED`
- [ ] 同步状态绑定真实状态
- [ ] 进度条按 `done/total` 计算
- [ ] 前端无 `localStorage`/`IndexedDB` 存业务数据
- [ ] 所有业务写操作经 Rust 命令写入 `op_log`，投影由 reducer 重建
- [ ] 工作区新建 / 重命名 / 删除与成员增改走 `workspace` / `membership` op
- [ ] 删除工作区只写 `__deleted` 墓碑 op，`op_log` 无物理删除
- [ ] AI 密钥保存/清除不产生 op，SQLite 与日志中无明文
- [ ] API 密钥只在 Rust 侧读取，前端只能拿到 `configured` 状态
- [ ] AI 输出不合 schema 被拒绝，不写入 op
- [ ] 网页桥接解析结果经 Rust 归一化后写入，`source="ai"`，`model="deepseek-web"`
- [ ] 达到 `ai_confirm_threshold` 时二次确认后才写入
- [ ] `windows/src-tauri/ai-core` 的 `cargo test` 通过

### 3.4 CI

- [ ] 令牌文档与令牌文件一致
- [ ] `ipc-bindings.generated.ts` 无 diff
- [ ] lint + format 通过
- [ ] 三端 reducer 一致性测试通过
- [ ] 迁移 runner 任意旧版本升级测试通过

### 3.5 启动与窗口

- [ ] 冷启动不出现纯白窗口，窗口第一帧为启动占位或已就绪界面
- [ ] 启动占位覆盖到数据就绪后淡出，不出现「占位 → 骨架 → 内容」多层接力
- [ ] 暗色主题重启时，启动占位与窗口底色均为暗色
- [ ] 前端脚本抛错/资源缺失时，主窗口仍能在 3 秒内显示
- [ ] 点击「便利贴」时主窗口不闪现桌面空白，便利贴出现后再隐藏主窗口
- [ ] 入口 HTML 不 `modulepreload` 浏览器预览专用组件，首屏 chunk 不含 `StickyMode` / `FloatingSticky`

## 四、常见坑

1. **抽屉动画抖动**：内容宽度必须固定 320px，只动外层容器宽度，否则文字会重排。
2. **复选框冒泡**：用 `event.stopPropagation()`，不要用 `pointer-events: none`，否则复选框不可点。
3. **侧栏拖拽丢焦**：用 `setPointerCapture`，鼠标快速移出手柄不中断。
4. **`conic-gradient` 兼容**：Tauri 2 的 WebView2 与 Android WebView 均支持，但若目标环境更旧，用 SVG 圆环替代。
5. **`backdrop-filter` 性能**：便利贴与卡片用了毛玻璃，低端机可能掉帧；提供 `@supports not (backdrop-filter: blur(1px))` 降级为纯色。
6. **浮动便利贴 z-index**：设计稿用 1000，生产是独立窗口，不参与主窗口层级。
7. **主窗口 hide 后事件丢失**：隐藏主窗口不销毁 WebView，Rust 仍是投影源；所有窗口监听 `projection-updated`，不要依赖主窗口转发。
8. **窗口 label 冲突**：`sticky-main` 只能存在一个；创建前先检查已存在窗口并聚焦，避免重复创建。
9. **密钥回显**：保存后只返回 `configured=true`，输入框必须清空；禁止把明文写进 `$state` 长期保存或日志。
10. **op_log 重放顺序**：投影必须按 HLC 全序裁决，不能依赖 SQLite 插入顺序；`seq` 只表达交付顺序。
7. **`prefers-reduced-motion`**：必须实现，否则无障碍验收不过。
8. **历史 diff 换行**：`overflow-wrap: anywhere`，长 op_id 不撑破面板。
9. **手机卡片刷新**：Android 小组件用 `RemoteViews`，不支持 Compose；复杂布局用 `Glance`，但 `Glance` 与 Compose 不能混用，需单独实现。
10. **AI 确认摘要**：必须在写入 op 之前弹，不能先写后撤。
11. **隐藏窗口忘了显示**：主窗口/便利贴都改为 `visible=false` 创建，前端必须在首帧就绪后 `show()`；同时保留 Rust 兜底（主窗口 3s、便利贴 2.5s），否则前端一崩就「双击没反应」。
12. **先藏后显示**：便利贴换手必须「先显示便利贴、再隐藏主窗口」，顺序反了会露出桌面空白。
13. **启动占位多层接力**：不要在 `#boot` 之后再用骨架屏或 `boot-mask` 覆盖一次；一个启动占位 + 一次淡出即可。

## 五、交付顺序建议

1. 令牌文件 + 图标映射（先固化视觉）
2. Windows 主界面静态布局（S1）
3. 详情抽屉 + 历史时间线（S1 右栏）
4. 侧栏拖拽 + 持久化
5. 便利贴切换 + 浮动便利贴（S2、S3）
6. Android 主界面 + 详情（S5、S6）
7. Android 历史（S7）
8. Android 桌面卡片（S8）
9. 空态/加载/错误态补全
10. 键盘快捷键 + 无障碍
11. CI 门禁接入