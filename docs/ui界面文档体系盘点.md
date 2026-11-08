下面是一套可直接交付给 AI 实现的**技术文档包**。我先盘点现有文档和缺口，再给出三份新文档的完整内容，最后单独列注意事项。

---

## 一、文档体系盘点

### 现有文档（8 份）

| 文档 | 覆盖 | 不覆盖 |
|---|---|---|
| `conventions.md` | 分层、错误、测试、CI、命名 | UI 视觉与组件 |
| `collaboration-model.md` | workspace/membership/授权 | UI 如何呈现角色 |
| `subtask-model.md` | 子任务字段与合并 | 子任务 UI 交互 |
| `reminder-and-tag-model.md` | 提醒调度、标签关联 | 提醒/标签 UI 呈现 |
| `ai-local-integration.md` | AI 意图、密钥、撤销 | AI 入口 UI、确认摘要 UI |
| `windows-frontend-spec.md` | Tauri/Svelte/IPC 技术栈 | 具体界面布局 |
| `sticky-note-spec.md` | 便利贴窗口生命周期 | 便利贴视觉与交互细节 |
| `todo-app-plan.md` | 三端架构总览 | UI 规格 |

### 缺口（本次要补）

1. **设计令牌**：颜色、字体、间距、圆角、阴影、动效、图标、z-index、暗色映射
2. **界面实现规格**：屏幕清单、组件规格、交互、状态、文案、无障碍
3. **交付清单与注意事项**：与现有文档的对应、HTML 草图的隐含假设、验收标准

建议新增三份文档，与现有 8 份并列：

```
docs/
├── conventions.md
├── collaboration-model.md
├── subtask-model.md
├── reminder-and-tag-model.md
├── ai-local-integration.md
├── windows-frontend-spec.md
├── sticky-note-spec.md
├── design-tokens.md              ← 新增
├── ui-implementation-spec.md     ← 新增
└── ui-delivery-checklist.md      ← 新增
```

---

## 二、`docs/design-tokens.md`

```markdown
# 设计令牌（Design Tokens）

> 本文件是视觉参数的唯一权威来源。所有 UI 实现必须引用令牌，禁止在组件里写死颜色、间距、圆角、阴影。
> 令牌以 CSS 自定义属性（Windows/Web）与 Kotlin 常量（Android）双份维护，值必须一致。

## 一、颜色

### 1.1 基础色板

| 令牌 | 值 | 用途 |
|---|---|---|
| `--color-primary` | `#3B82F6` | 主色：按钮、链接、选中态、进度条起点 |
| `--color-primary-50` | `#EFF6FF` | 主色浅底：选中行、标签背景、Tab 激活底 |
| `--color-primary-100` | `#DBEAFE` | 主色更浅：AI 徽章渐变终点 |
| `--color-success` | `#10B981` | 完成态：复选框、进度条终点、diff 新增 |
| `--color-success-50` | `#ECFDF5` | 完成浅底 |
| `--color-danger` | `#EF4444` | 高优先级、删除、错误、紧急时间 |
| `--color-warning` | `#F59E0B` | 中优先级 |
| `--color-purple` | `#8B5CF6` | AI 来源、学习清单 |

### 1.2 中性色

| 令牌 | 值 | 用途 |
|---|---|---|
| `--color-bg` | `#F8FAFC` | 页面背景 |
| `--color-card` | `#FFFFFF` | 卡片、窗口内容区 |
| `--color-sidebar` | `#FAFBFC` | 侧栏背景 |
| `--color-text` | `#1F2937` | 主文字 |
| `--color-text-2` | `#6B7280` | 次要文字、图标 |
| `--color-text-3` | `#9CA3AF` | 辅助文字、占位符、禁用 |
| `--color-border` | `#E5E7EB` | 分割线、输入边框 |
| `--color-border-2` | `#F1F5F9` | 更浅分割线、卡片内分隔 |
| `--color-overlay` | `rgba(15,23,42,0.06)` | 悬停遮罩 |
| `--color-stage` | `#0B1220` | 草图层背景（仅设计稿） |

### 1.3 语义色

| 令牌 | 映射 | 用途 |
|---|---|---|
| `--color-priority-high` | `--color-danger` | 高优先级圆点 |
| `--color-priority-mid` | `--color-warning` | 中优先级圆点 |
| `--color-priority-low` | `--color-primary` | 低优先级圆点 |
| `--color-ai-bg` | `linear-gradient(135deg,#EDE9FE,#DBEAFE)` | AI 徽章背景 |
| `--color-ai-fg` | `#6D28D9` | AI 徽章文字 |
| `--color-diff-old` | `--color-text-3` + 删除线 | 历史 diff 旧值 |
| `--color-diff-new` | `--color-success` | 历史 diff 新值 |

### 1.4 暗色模式映射（预留，MVP 不实现）

暗色模式通过 `prefers-color-scheme` 或用户设置切换，令牌值整体替换：

| 令牌 | 暗色值 |
|---|---|
| `--color-bg` | `#0F172A` |
| `--color-card` | `#1E293B` |
| `--color-sidebar` | `#111827` |
| `--color-text` | `#F1F5F9` |
| `--color-text-2` | `#94A3B8` |
| `--color-text-3` | `#64748B` |
| `--color-border` | `#334155` |
| `--color-border-2` | `#1E293B` |

## 二、字体

### 2.1 字体族

| 令牌 | 值 |
|---|---|
| `--font-sans` | `-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif` |
| `--font-mono` | `ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace` |

Android 端对应 `FontFamily.Default` 与 `FontFamily.Monospace`，不要自定义字体文件。

### 2.2 字号阶梯

| 令牌 | 字号/行高 | 字重 | 用途 |
|---|---|---|---|
| `--text-xs` | 10 / 14 | 500 | 徽章、元信息、op_id |
| `--text-sm` | 11 / 16 | 500 | 辅助文字、标签 chip、diff |
| `--text-base` | 12.5 / 18 | 400 | 侧栏项、次要正文 |
| `--text-md` | 13.5 / 20 | 500 | 任务标题（列表） |
| `--text-lg` | 16 / 22 | 600 | 详情标题、便利贴标题 |
| `--text-xl` | 19 / 26 | 700 | 手机问候语 |
| `--text-2xl` | 28 / 36 | 700 | 页面标题（设计稿） |

> 字号单位为 px。Android 端用 `sp`，数值一致。

### 2.3 字重

| 令牌 | 值 | 用途 |
|---|---|---|
| `--weight-regular` | 400 | 正文 |
| `--weight-medium` | 500 | 任务标题、侧栏项 |
| `--weight-semibold` | 600 | 分组标题、按钮、徽章 |
| `--weight-bold` | 700 | 问候语、窗口标题 |

## 三、间距

8px 栅格，令牌为 `--space-{n}`，值 = n × 4px：

| 令牌 | 值 | 典型用途 |
|---|---|---|
| `--space-1` | 4px | 图标与文字间隙 |
| `--space-2` | 8px | 组件内边距、列表项间距 |
| `--space-3` | 12px | 卡片内边距、按钮水平内边距 |
| `--space-4` | 16px | 卡片外边距、窗口内边距 |
| `--space-5` | 20px | 详情面板内边距 |
| `--space-6` | 24px | 区块间距 |
| `--space-8` | 32px | 大区块间距 |
| `--space-12` | 48px | 页面级间距 |

## 四、圆角

| 令牌 | 值 | 用途 |
|---|---|---|
| `--radius-sm` | 8px | 按钮、输入框、侧栏项、Tab |
| `--radius-md` | 12px | 任务卡、进度卡、字段组 |
| `--radius-lg` | 16px | 便利贴、手机卡片、大卡片 |
| `--radius-xl` | 20px | 桌面舞台、手机外框 |
| `--radius-full` | 999px | 复选框、标签 chip、进度条、头像 |

## 五、阴影

| 令牌 | 值 | 用途 |
|---|---|---|
| `--shadow-sm` | `0 1px 2px rgba(15,23,42,0.04)` | 任务卡、手机任务 |
| `--shadow-md` | `0 4px 12px rgba(15,23,42,0.06)` | 进度卡、字段组 |
| `--shadow-lg` | `0 12px 32px rgba(15,23,42,0.10)` | 手机卡片、浮层 |
| `--shadow-xl` | `0 24px 64px rgba(15,23,42,0.16)` | 大窗口、便利贴、抽屉 |

便利贴额外使用内阴影 `0 0 0 1px rgba(255,255,255,0.6) inset` 模拟玻璃高光。

## 六、动效

| 令牌 | 值 | 用途 |
|---|---|---|
| `--ease-standard` | `cubic-bezier(.4,0,.2,1)` | 抽屉、切换、缩放 |
| `--ease-out` | `ease-out` | 悬停、淡入 |
| `--duration-fast` | 150ms | 悬停、颜色变化 |
| `--duration-base` | 250ms | 弹层出现、浮动便利贴 |
| `--duration-slow` | 300ms | 抽屉开合、主窗口⇄便利贴切换 |
| `--duration-progress` | 400ms | 进度条宽度 |

规则：
- 抽屉宽度动画 280ms，用 `--ease-standard`。
- 复选框勾选 200ms，只动 `background` 与 `border-color`，不做弹跳。
- 任务完成移入「已完成」用 400ms 淡出 + 高度折叠，避免列表跳动。
- 尊重 `prefers-reduced-motion`：开启时所有动画降为 0ms。

## 七、图标

### 7.1 尺寸

| 令牌 | 值 | 用途 |
|---|---|---|
| `--icon-xs` | 12px | 行内元信息 |
| `--icon-sm` | 14px | 按钮内图标 |
| `--icon-md` | 16px | 侧栏、Tab |
| `--icon-lg` | 20px | 工具栏 |
| `--icon-xl` | 24px | 空态插画 |

### 7.2 图标库与替换规则

HTML 草图用 emoji 与 Unicode 符号（🔍、📌、↗、✕、🕘、☰、▦、▤、◐）作为占位。**生产实现必须替换为图标库**：

- Windows/Web：`lucide-svelte`（与 Svelte 5 兼容，tree-shakable）
- Android：`androidx.compose.material.icons`（Material Symbols）

映射表：

| 草图占位 | 图标名 | 用途 |
|---|---|---|
| 🔍 | `Search` | 搜索框 |
| 📌 | `Pin` / `PinOff` | 便利贴置顶 |
| ↗ | `Maximize2` / `ExternalLink` | 展开大界面 |
| ✕ | `X` | 关闭抽屉/便利贴 |
| 🕘 | `History` | 历史入口 |
| ☰ | `List` | 清单 Tab |
| ▦ | `Calendar` | 日历 Tab |
| ▤ | `BarChart3` | 统计 Tab |
| ◐ | `User` | 我的 Tab |
| ＋ | `Plus` | 新建/添加 |
| ▾ | `ChevronDown` | 折叠/下拉 |
| ▸/› | `ChevronRight` | 进入详情 |
| ‹ | `ChevronLeft` | 返回 |
| ⋯ | `MoreHorizontal` | 更多操作 |
| ✦ | `Sparkles` | AI 标识 |
| ⏰ | `Bell` | 提醒 |
| ☑ | `CheckSquare` | 子任务进度 |

> 禁止在用户可见界面直接输出 emoji 作为图标；emoji 仅用于设计稿标注。

## 八、层级 z-index

| 令牌 | 值 | 用途 |
|---|---|---|
| `--z-base` | 0 | 内容 |
| `--z-sticky` | 10 | 吸顶工具栏 |
| `--z-resizer` | 30 | 侧栏拖拽手柄 |
| `--z-drawer` | 40 | 详情抽屉 |
| `--z-popover` | 50 | 下拉、右键菜单 |
| `--z-modal` | 60 | 确认摘要、对话框 |
| `--z-toast` | 70 | 轻提示 |
| `--z-floating` | 1000 | 浮动便利贴（设计稿模拟） |

> 生产环境中便利贴是独立 `WebviewWindow`，不参与主窗口 z-index 体系。

## 九、尺寸约束

| 对象 | 默认 | 最小 | 最大 | 说明 |
|---|---|---|---|---|
| Windows 主窗口 | 1120×720 | 960×600 | 无 | 可缩放 |
| 侧栏 | 180px | 150px | 300px | 可拖拽，双击重置 180 |
| 详情抽屉 | 320px | 320px | 320px | MVP 固定宽，不可拖 |
| 便利贴窗口 | 320×420 | 280×320 | 420×600 | 可缩放 |
| 手机 App | 375×812 | 360×640 | 430×932 | 响应式 |
| 手机 2×2 卡片 | 160×160 | — | — | 系统决定 |
| 手机 4×2 卡片 | 336×160 | — | — | 系统决定 |
| 手机 4×4 卡片 | 336×336 | — | — | 系统决定 |

## 十、令牌实现要求

- Windows/Web：全部令牌定义在 `ui/src/lib/styles/tokens.css`，用 `:root` 声明；组件只引用 `var(--token)`。
- Android：全部令牌定义在 `core/designsystem/src/main/kotlin/.../Tokens.kt`，用 `object Tokens { val colorPrimary = Color(0xFF3B82F6) ... }`。
- CI 校验：新增颜色/间距必须先在本文档登记，再改令牌文件；文档与令牌文件不一致时 CI 失败。
- 禁止在组件中出现十六进制颜色、`px` 魔法数字（允许 `0`、`1px` 边框、`100%`、`100vh`）。
```

---

## 三、`docs/ui-implementation-spec.md`

```markdown
# 界面实现规格

> 本文件覆盖屏幕布局、组件规格、交互、状态、文案、无障碍。视觉参数引用 `design-tokens.md`，数据流引用 `windows-frontend-spec.md` 与 `todo-app-plan.md`。

## 一、屏幕清单

| 屏幕 | 端 | 入口 | 职责 |
|---|---|---|---|
| S1 Windows 主界面 | Windows | 应用启动 | 管理、规划、详情、历史 |
| S2 Windows 便利贴（主窗口收起形态） | Windows | 标题栏「📌 便利贴」 | glance 查看、勾选 |
| S3 Windows 浮动便利贴 | Windows | 标题栏「＋ 新便利贴」/ 托盘 | 并存 glance，最多 5 个 |
| S4 Windows 全局历史 | Windows | 侧栏「历史」 | workspace 级 op 时间线 |
| S5 Android 主界面 | Android | 应用启动 | 今日任务列表 |
| S6 Android 任务详情 | Android | 点击任务 | 编辑字段、子任务、备注 |
| S7 Android 任务历史 | Android | 详情页「查看历史」 | 任务级 op 时间线 |
| S8 Android 桌面卡片 | Android | 系统添加小组件 | glance 查看 |

> MVP 不做：Windows 日历视图、统计视图、Android 全局历史独立页（从主界面 🕘 进入复用 S7 布局但数据为全局）。

## 二、全局布局

### 2.1 Windows 主界面（S1）

三栏 flex 布局，高度撑满窗口内容区（标题栏 40px 以下）：

[侧栏 180px 可调] [拖拽手柄 8px] [中栏 flex:1] [详情抽屉 320px 可开合]

- 标题栏 40px：左侧应用图标、标题、同步状态；右侧按钮组「📌 便利贴」「＋ 新便利贴」「⚙️ 设置」，最右为 Windows 11 风格窗口控制按钮（最小化 `—`、最大化/还原 `□`/`❐`、关闭 `✕`）。
- 窗口控制按钮：46px 宽、与标题栏等高、贴窗口右边缘、无圆角；hover 底色 `#E5E7EB`，关闭 hover 底色 `#E81123` + 白色图标。禁止使用 macOS 交通灯圆点。
- 双击标题空白区切换最大化；最大化后按钮图标切换为还原态，`aria-label` 同步为「还原窗口」。
- 侧栏：工作区切换、清单、标签、历史入口、成员、AI 入口。
- 中栏：工具栏（搜索、新建、视图切换）、进度卡、任务分组列表。
- 详情抽屉：默认关闭；点击任务打开，`✕`/`Esc`/「删除」关闭。

### 2.2 Windows 便利贴（S2）

主窗口收起后，主窗口内容以 `opacity:0` + `scale(.96)` 隐藏，中央浮现 340×440 便利贴。**不是新窗口**，是同一窗口内的形态切换。

生产实现对应 `sticky-note-spec.md` 的独立 `WebviewWindow`，但**主窗口收起形态**是 MVP 的简化交互：收起后主窗口隐藏，便利贴窗口出现；展开时便利贴窗口销毁、主窗口恢复。二者互斥，同时最多一个。

### 2.3 Windows 浮动便利贴（S3）

独立 `WebviewWindow`，固定右下角，每多开一个偏移 24px，最多 5 个。生产环境由 Rust 侧创建，前端只发 `create_sticky(task_id)` 命令。

### 2.4 Android 主界面（S5）

单列纵向滚动：
- 状态栏 44px
- 问候语 + 日期
- 今日进度卡（含 🕘 历史入口）
- 快速添加
- 「今天」分组
- 「即将到来」分组
- 底部 Tab 栏 4 项

### 2.5 Android 任务详情（S6）

单列纵向滚动：
- 导航栏：`‹ 今天` + `⋯`
- 标题卡：复选框 + 标题 + 来源徽章 + 标签
- 字段卡：优先级、截止、提醒、清单、标签（每行右侧 `›`）
- 子任务卡：进度 + 子任务列表 + `＋`
- 备注卡
- 历史入口卡：`🕘 查看历史 · N 条变更 · 含 M 条 AI`
- 底部固定操作栏：删除（描边红）、保存（主色）

## 三、组件规格

### 3.1 复选框（Checkbox）

| 状态 | 样式 |
|---|---|
| 默认 | 20×20，圆形，1.5px 边框 `--color-border`，白底 |
| 悬停 | 边框变 `--color-primary` |
| 完成 | 背景 `--color-success`，边框同色，白色对勾（5×10，2px 描边，旋转 45°） |
| 禁用 | 背景 `--color-border-2`，边框 `--color-border`，不响应点击 |
| 焦点 | 外圈 `0 0 0 2px var(--color-primary-50)` |

- 便利贴内复选框 17×17，手机卡片内 13×13，子任务内 14×14（详情）/18×18（手机详情）。
- 复选框点击**不得**触发父级任务行点击，必须 `stopPropagation`。
- 勾选动作走 `update_task_field(task_id, "status", "done")`，不直接改本地投影。

### 3.2 优先级圆点（Priority Dot）

- 6×6 圆形，高=`--color-danger`，中=`--color-warning`，低=`--color-primary`。
- 与文字间距 4px，垂直居中。
- 不可点击，仅展示。

### 3.3 标签 Chip

- 高度 16px，水平内边距 7px，圆角 999px，字号 11px，字重 500。
- 背景与文字色成对出现：`--tag-bg` / `--tag-fg`，默认 `--color-primary-50` / `--color-primary`。
- 左侧 5×5 圆点与文字同色。
- 标签不可点击（MVP），点击整行进入详情编辑。

### 3.4 AI 徽章

- 高度 14px，内边距 `1px 6px`，圆角 999px，字号 10px，字重 600。
- 背景 `linear-gradient(135deg,#EDE9FE,#DBEAFE)`，文字 `#6D28D9`。
- 图标 `Sparkles` 12px + 文案「AI」或「由 AI 创建」。
- 只在 `source="ai"` 的实体上展示，不得用于装饰。

### 3.5 进度条

- 高 6px，圆角 999px，底 `#E2E8F0`。
- 填充 `linear-gradient(90deg, var(--color-primary), var(--color-success))`。
- 宽度动画 400ms `--ease-standard`。
- 进度环（手机卡片）：38×38，`conic-gradient(var(--color-primary) 0% 37%, #E2E8F0 37% 100%)`，内圆 30×30 白底，中心文字 9px/700。
- 进度百分比 = 已完成数 / 总数，四舍五入到整数；总数为 0 时显示 0%。

### 3.6 按钮

| 类型 | 样式 | 用途 |
|---|---|---|
| 常规 | 白底、1px `--color-border`、`--color-text-2`、圆角 8 | 次要操作 |
| 主色 | `--color-primary` 底、白字、阴影 `0 2px 8px rgba(59,130,246,.25)` | 保存、新建 |
| 危险描边 | `#FEF2F2` 底、`#FCA5A5` 边、`--color-danger` 字 | 删除 |
| 标题栏按钮 | `rgba(255,255,255,.7)` 底、`--color-border` 边、11.5px | 切换便利贴 |

- 高度：常规 30px，主操作 32px，手机底部 40px。
- 悬停：常规按钮边框变 `#BFDBFE`、文字变主色；主色按钮亮度 +4%。
- 按下：`transform: scale(0.98)`，100ms。
- 禁用：`opacity:.5`，`cursor: not-allowed`，不响应点击。

### 3.7 任务行

| 区域 | 规格 |
|---|---|
| 内边距 | 10px 12px，圆角 12px |
| 布局 | 复选框 20px + 8px 间距 + 任务体 |
| 标题 | 13.5px/500，行高 1.4 |
| 元信息 | 11px，`--color-text-3`，间距 8px，可换行 |
| 悬停 | 背景 `#F8FAFC`，边框 `--color-border-2` |
| 选中 | 背景 `--color-primary-50`，边框 `#BFDBFE`，左侧 3px 主色内阴影 |
| 完成 | 标题删除线 + `--color-text-3`，复选框绿底 |

- 相邻任务间距 2px。
- 左滑/右滑手势（Android）：右滑完成，左滑露出「延期」「删除」。
- Windows 右键菜单：延期到明天、删除、钉为便利贴、查看历史。
- 拖拽排序：HTML5 DnD 或 `@dnd-kit`，落点用 2px 主色线提示。

### 3.8 详情抽屉

- 宽度 0 → 320px，280ms `--ease-standard`。
- 内容宽度固定 320px，避免动画过程中文字重排。
- 头部：Tab「详情/历史」+ `✕` 关闭按钮 28×28。
- 打开时给对应任务行加 `.active`；关闭时移除。
- 关闭方式：`✕`、`Esc`、底部「删除」。
- 打开不同任务时只切换内容，不重新播放宽度动画。
- 每次打开默认回到「详情」Tab。

### 3.9 侧栏与拖拽手柄

- 手柄位于侧栏右边缘，宽 8px，`cursor: col-resize`，`z-index: 30`。
- 默认透明；悬停/拖拽中显示 3px 主色圆角线，高 56px。
- 拖拽中 `body` 加 `.resizing`，全局 `cursor: col-resize`，禁用文本选择。
- 宽度范围 150–300px，默认 180px，双击重置 180px。
- 宽度持久化：设计稿用 `localStorage`；生产实现必须用客户端本地表（`sticky-note-spec.md` 的本地偏好表），不写 `localStorage`、不进 `op_log`。
- 侧栏文字全部 `text-overflow: ellipsis`，窄到极限不撑破。

### 3.10 历史时间线

- 左侧 1.5px 竖线，节点 11×11 圆点，白底 2px 描边。
- 人工 op：主色描边；AI op：紫色描边 + `#F5F3FF` 底；系统快照：灰色描边。
- 每条包含：时间、操作者/AI 徽章、动作、diff、op_id/model/source。
- diff 用等宽字体，旧值删除线灰色，新值绿色加粗。
- 「撤销此操作」按钮只出现在可撤销 op 上；点击后文案变「已生成撤销 op」并禁用。
- 顶部固定说明：「日志只追加，历史记录不可删除，只能以更高 HLC 的反向 op 撤销。」

### 3.11 空态

| 场景 | 图标 | 文案 | 操作 |
|---|---|---|---|
| 便利贴今日清空 | ☕ 24px | 「今天清空啦，休息一下」 | 无 |
| 任务列表为空 | `CheckSquare` 24px | 「今天还没有待办」 | 「＋ 添加一个」按钮 |
| 搜索无结果 | `Search` 24px | 「没有找到匹配的任务」 | 「清除搜索」链接 |
| 历史为空 | `History` 24px | 「这条任务还没有变更记录」 | 无 |
| 成员列表为空 | `Users` 24px | 「还没有协作成员」 | 「邀请成员」按钮 |

### 3.12 加载态

- 首屏：骨架屏，任务行占位 3 条，进度卡占位 1 块，300ms 后淡入真实内容。
- 抽屉内容：打开时若投影未就绪，显示 3 条骨架行，不显示空白。
- 历史时间线：显示 2 条骨架节点。
- 骨架屏颜色：`--color-border-2` 到 `--color-border` 之间脉冲，1.2s 循环。
- 禁止使用全屏 spinner。

### 3.13 错误态

| 错误 | 呈现 |
|---|---|
| 权限被拒（403） | 抽屉顶部红色条：「权限已变更，此操作未生效」，带 `server_hlc` |
| 网络错误（可重试） | 窗口底部轻提示：「网络不稳定，正在重试」，带重试按钮 |
| 时钟漂移 | 抽屉顶部黄色条：「本机时间偏差较大，已按服务器时间校正」 |
| AI 输出不合 schema | 轻提示：「AI 返回格式不正确，已忽略」，不写入 op |
| AI 批量超阈值 | 确认摘要对话框，列出影响条数，用户确认后才写入 |

- 错误必须携带英文 `code` 与中文 `message`（`conventions.md` 要求）。
- 可重试错误与不可重试错误在类型上区分，UI 上：可重试给「重试」按钮，不可重试给「知道了」。

### 3.14 确认摘要对话框（AI 批量）

- 触发：AI 意图影响 ≥10 条任务/子任务。
- 内容：标题「AI 想修改 N 条任务」、列表（最多 5 条摘要 + 「还有 N 条」）、操作「取消」「确认写入」。
- 确认后走正常 op 管道，`source="ai"`。
- 对话框不阻塞同步循环。

## 四、交互规格

### 4.1 鼠标（Windows）

| 操作 | 行为 |
|---|---|
| 点击任务行 | 打开详情抽屉，高亮该行 |
| 点击复选框 | 切换完成态，不打开详情 |
| 点击 `✕` | 关闭抽屉 |
| `Esc` | 关闭抽屉 |
| 拖动侧栏手柄 | 调节宽度 150–300 |
| 双击手柄 | 重置 180 |
| 右键任务 | 上下文菜单：延期、删除、钉为便利贴、查看历史 |
| 拖动任务 | 排序，落点 2px 主色线 |
| 点击「📌 便利贴」 | 主窗口收起为便利贴 |
| 点击「＋ 新便利贴」 | 创建浮动便利贴，最多 5 个 |
| 点击浮动便利贴 `✕` | 关闭该便利贴 |
| 点击浮动便利贴「展开大界面」 | 关闭该便利贴并恢复主窗口 |

### 4.2 键盘（Windows）

| 快捷键 | 行为 |
|---|---|
| `Esc` | 关闭抽屉 / 关闭对话框 |
| `Ctrl/Cmd + N` | 新建任务 |
| `Ctrl/Cmd + F` | 聚焦搜索 |
| `Ctrl/Cmd + Shift + T` | 主窗口 ⇄ 便利贴切换 |
| `Ctrl/Cmd + Enter` | 保存详情 |
| `↑` / `↓` | 在任务列表内移动焦点 |
| `Enter` | 打开焦点任务的详情 |
| `Space` | 切换焦点任务的完成态 |
| `Tab` / `Shift+Tab` | 焦点循环 |

- 所有可点击元素必须可 Tab 聚焦，焦点环 `0 0 0 2px var(--color-primary-50)`。
- 抽屉打开时焦点移入抽屉，关闭时焦点回到触发任务。

### 4.3 触摸（Android）

| 手势 | 行为 |
|---|---|
| 点击任务 | 进入详情 |
| 点击复选框 | 切换完成态 |
| 右滑任务 | 标记完成 |
| 左滑任务 | 露出「延期」「删除」 |
| 下拉列表 | 触发同步 |
| 长按任务 | 拖拽排序 |
| 点击卡片任务 | 深链进入详情 |
| 点击卡片「＋」 | 打开快速添加 |

- 滑动阈值 72dp，回弹动画 200ms。
- 滑动露出按钮高度撑满任务行，圆角与任务行一致。

## 五、文案规格

### 5.1 语言

- 用户可见文案：中文。
- 标识符、API 字段、日志：英文（`conventions.md`）。
- 日期格式：`10月24日 星期四`；时间：`18:00`；相对时间：`今天`、`明天`、`周四`、`周六`。
- 数字与中文之间不加空格（「3 / 8 完成」除外，用于进度）。

### 5.2 固定文案

| 位置 | 文案 |
|---|---|
| 窗口标题 | 待办清单 |
| 同步状态 | 已同步 / 同步中 / 离线 / 重试中 |
| 主界面问候 | 早上好，今天也要高效呀（按时间段：早上好/下午好/晚上好） |
| 进度卡 | 今日进度 / `{done} / {total} 完成 · {pct}%` |
| 分组 | 今天 / 即将到来 / 已完成 |
| 快速添加 | 添加一个新待办… / 添加待办… |
| 详情 Tab | 详情 / 历史 |
| 详情按钮 | 删除 / 保存 |
| 历史说明 | 日志只追加：历史记录不可删除，只能以更高 HLC 的反向 op 撤销。AI 操作与手动操作在数据层等价。 |
| 历史筛选 | 全部 / 仅 AI / 仅我 |
| 撤销按钮 | 撤销此操作 / 已生成撤销 op |
| 便利贴空态 | 今天清空啦，休息一下 |
| 便利贴底部 | 今天 ▾ / 展开大界面 ↗ |
| AI 徽章 | ✦ AI / ✦ 由 AI 创建 |
| 权限错误 | 权限已变更，此操作未生效 |
| 网络错误 | 网络不稳定，正在重试 |
| 时钟错误 | 本机时间偏差较大，已按服务器时间校正 |
| AI schema 错误 | AI 返回格式不正确，已忽略 |
| AI 批量确认 | AI 想修改 {n} 条任务 |

### 5.3 文案规则

- 按钮用动词开头：「保存」「删除」「添加」「撤销」。
- 空态用完整句子，不用「无数据」「暂无」。
- 错误文案说明「发生了什么 + 已采取什么 + 用户可做什么」。
- 禁止在文案中出现 op_id、HLC、schema 等实现术语，除非在历史面板的等宽元信息区。

## 六、无障碍

- 所有交互元素最小点击区域 44×44（移动端）/ 32×32（桌面端）。
- 图标按钮必须有 `aria-label` 或 `contentDescription`。
- 复选框用 `role="checkbox"` + `aria-checked`，不用纯 div。
- 抽屉用 `role="dialog"` + `aria-modal="true"` + `aria-labelledby` 指向标题。
- 历史时间线用 `<ol>`，每条 `<li>`，AI 条目加 `aria-label="AI 操作"`。
- 颜色对比度：正文 ≥ 4.5:1，大字号 ≥ 3:1；`--color-text-3` 只用于辅助信息，不用于正文。
- 尊重 `prefers-reduced-motion`。
- 焦点顺序与视觉顺序一致。

## 七、与现有文档的对应

| 本规格章节 | 依赖文档 | 约束 |
|---|---|---|
| 3.1 复选框 | `todo-app-plan.md` 2.1 | 勾选走 `update_task_field`，不直接改投影 |
| 3.8 抽屉 | `windows-frontend-spec.md` 五 | 数据从 Rust command 拉取，前端不持久化 |
| 3.9 侧栏宽度 | `sticky-note-spec.md` 四 | 本地偏好进客户端 SQLite，不进 `op_log` |
| 3.10 历史 | `todo-app-plan.md` 2.2 | 撤销 = 更高 HLC 反向 op，不删原 op |
| 3.14 确认摘要 | `ai-local-integration.md` 四 | 批量 ≥10 条必须确认 |
| 4.1 右键「钉为便利贴」 | `sticky-note-spec.md` 二 | 上限 5 个，超出 UI 提示 |
| 2.3 浮动便利贴 | `sticky-note-spec.md` 二 | 独立 `WebviewWindow`，按需创建销毁 |
```

---

## 四、`docs/ui-delivery-checklist.md`

```markdown
# UI 交付清单与注意事项

> 交付给 AI 实现时，先读本文件，再读 `design-tokens.md` 与 `ui-implementation-spec.md`。
> 设计稿（HTML 草图）是视觉参照，不是实现代码；禁止直接复制 HTML 到生产代码。

## 一、交付物清单

### 1.1 Windows（Tauri 2 + Svelte 5）

| 交付物 | 路径 | 验收 |
|---|---|---|
| 设计令牌 | `ui/src/lib/styles/tokens.css` | 与 `design-tokens.md` 一致 |
| 主界面 | `ui/src/views/tasks/` | S1 全部组件与交互 |
| 便利贴入口 | `ui/sticky.html` + `ui/src/views/sticky-note/` | S2、S3 |
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
5. **骨架屏**：设计稿没有加载态。首屏、抽屉、历史时间线按 3.12 补骨架。
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
5. **便利贴上限**：设计稿用 `alert`，生产必须用 UI 提示（轻提示或对话框），不阻塞。
6. **浮动便利贴**：设计稿是 `position: fixed` 模拟，生产是独立 `WebviewWindow`，由 Rust 侧创建销毁，前端只发命令。
7. **主窗口 ⇄ 便利贴**：设计稿是同一页面形态切换；生产是主窗口隐藏 + 便利贴窗口创建，或反之。二者互斥，同时最多一个主形态便利贴。
8. **键盘快捷键**：设计稿未实现，按 `ui-implementation-spec.md` 4.2 补全。
9. **滑动手势**：Android 右滑完成、左滑延期/删除，阈值 72dp，回弹 200ms。
10. **拖拽排序**：Windows 用 DnD，Android 用长按拖拽，落点 2px 主色线。

### 2.3 数据与状态

1. **前端不持久化业务数据**：设计稿里任务是硬编码 HTML。生产必须从 Rust command / Room 拉取投影，前端只存 `$state`，禁止 `localStorage`/`IndexedDB` 存任务（`windows-frontend-spec.md` 三）。
2. **勾选走 op 管道**：设计稿只切 class。生产必须调 `update_task_field`，同事务写业务表 + `op_log` + `local_hlc` + `pending_ops`。
3. **历史撤销**：设计稿只改按钮文案。生产必须生成更高 HLC 的反向 op，走正常上传路径，不删原 op。
4. **AI 徽章**：只在 `source="ai"` 时展示，不得用于装饰或猜测。
5. **权限拒绝**：设计稿未画。生产必须在抽屉顶部显示红条，带 `server_hlc`，op 标记 `REJECTED`，不自动重试。
6. **同步状态**：标题栏「已同步」是静态文字。生产必须绑定同步循环状态：已同步/同步中/离线/重试中。
7. **进度计算**：设计稿是写死 37%。生产必须 `done/total`，总数为 0 时显示 0%，动画 400ms。

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

### 3.2 交互

- [ ] 点击任务打开抽屉，点击复选框不打开
- [ ] `✕`/`Esc`/「删除」都能关闭抽屉，焦点回到触发任务
- [ ] 点击不同任务只切内容，不重播动画
- [ ] 侧栏拖拽 150–300，双击重置 180，刷新后保持
- [ ] 「📌 便利贴」收起主窗口，「↗」恢复
- [ ] 「＋ 新便利贴」创建浮动便利贴，最多 5 个，超出 UI 提示
- [ ] 快捷键全部生效（`Esc`、`Ctrl+N`、`Ctrl+F`、`Ctrl+Shift+T`、`Ctrl+Enter`、`↑↓`、`Enter`、`Space`）
- [ ] Android 右滑完成、左滑延期/删除、下拉同步、长按排序

### 3.3 数据

- [ ] 勾选走 `update_task_field`，产生 `op_log` 与 `pending_ops`
- [ ] 撤销生成反向 op，不删原 op
- [ ] AI 徽章只出现在 `source="ai"` 的实体
- [ ] 权限拒绝显示红条，op 标记 `REJECTED`
- [ ] 同步状态绑定真实状态
- [ ] 进度条按 `done/total` 计算
- [ ] 前端无 `localStorage`/`IndexedDB` 存业务数据

### 3.4 CI

- [ ] 令牌文档与令牌文件一致
- [ ] `ipc-bindings.generated.ts` 无 diff
- [ ] lint + format 通过
- [ ] 三端 reducer 一致性测试通过
- [ ] 迁移 runner 任意旧版本升级测试通过

## 四、常见坑

1. **抽屉动画抖动**：内容宽度必须固定 320px，只动外层容器宽度，否则文字会重排。
2. **复选框冒泡**：用 `event.stopPropagation()`，不要用 `pointer-events: none`，否则复选框不可点。
3. **侧栏拖拽丢焦**：用 `setPointerCapture`，鼠标快速移出手柄不中断。
4. **`conic-gradient` 兼容**：Tauri 2 的 WebView2 与 Android WebView 均支持，但若目标环境更旧，用 SVG 圆环替代。
5. **`backdrop-filter` 性能**：便利贴与卡片用了毛玻璃，低端机可能掉帧；提供 `@supports not (backdrop-filter: blur(1px))` 降级为纯色。
6. **浮动便利贴 z-index**：设计稿用 1000，生产是独立窗口，不参与主窗口层级。
7. **`prefers-reduced-motion`**：必须实现，否则无障碍验收不过。
8. **历史 diff 换行**：`overflow-wrap: anywhere`，长 op_id 不撑破面板。
9. **手机卡片刷新**：Android 小组件用 `RemoteViews`，不支持 Compose；复杂布局用 `Glance`，但 `Glance` 与 Compose 不能混用，需单独实现。
10. **AI 确认摘要**：必须在写入 op 之前弹，不能先写后撤。

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
```

---

## 五、给 AI 的交付提示

把三份新文档和现有 8 份一起给 AI 时，建议附上这段开场：

> 你将要实现一个待办清单 App 的 UI。请先读 `conventions.md`（工程规范，唯一权威）、`design-tokens.md`（视觉参数）、`ui-implementation-spec.md`（界面与交互）、`ui-delivery-checklist.md`（交付与坑），再读 `todo-app-plan.md`、`windows-frontend-spec.md`、`sticky-note-spec.md` 了解数据流与窗口模型。设计稿 HTML 只作视觉参照，禁止直接复制。所有颜色、间距、圆角、阴影必须引用令牌；所有业务数据必须从 Rust command / Room 拉取，前端不持久化；勾选、编辑、撤销必须走 op 管道。遇到文档冲突，以 `conventions.md` 为准。

### 三份新文档解决的缺漏

- **设计令牌**：把 HTML 里散落的 CSS 变量固化成唯一权威，补了暗色映射、图标映射、z-index、尺寸约束、动效降级。
- **界面规格**：把四个端的屏幕、组件、交互、状态、文案、无障碍全部写成可验收条目，补了空态/加载态/错误态/焦点态/键盘快捷键/滑动手势。
- **交付清单**：明确设计稿的隐含假设（localStorage、fixed 定位、硬编码数据、emoji 图标）在生产中必须替换为什么，并给出验收清单和 10 个常见坑。

这样 AI 拿到的是一份**可执行、可验收、与现有架构不冲突**的规格，而不是一份需要猜的 HTML。