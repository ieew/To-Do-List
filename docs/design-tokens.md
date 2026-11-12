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
| `--color-warning-50` | `#FEF3C7` | 所有者徽章浅底、警告提示 |
| `--color-warning-fg` | `#B45309` | 所有者徽章文字 |
| `--color-danger-50` | `#FEF2F2` | 删除按钮浅底、错误提示 |
| `--color-danger-border` | `#FCA5A5` | 删除按钮描边 |

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
| `--color-warning-50` | `rgba(245,158,11,0.16)` |
| `--color-warning-fg` | `#FCD34D` |
| `--color-danger-50` | `rgba(239,68,68,0.14)` |
| `--color-danger-border` | `rgba(239,68,68,0.4)` |

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

**Android 端当前偏差（2026-11-12）**：为提升手机阅读舒适度，`Tokens.Type` 里除「清单页任务标题」外全部 +1sp
（`greeting` 20、`pageTitle` 18、`section` 14、`taskTitle` 14、`body` 13.5、`meta` 11.5、`chip` 12、`badge` 11、
`number` 23、`mono` 12）；清单页任务标题单独用 `taskTitleList` = 16/24。Windows 端仍沿用上表。

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
| `--radius-sticky` | 0px | 便利贴外层卡片（直角） |

便利贴底色透明度使用动态令牌 `--sticky-alpha`（0–1，由设置「外观 → 底色透明度」写入，默认 0.94），不单独建色板。

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

## 十、窗口控制（Windows 11 自绘标题栏）

Windows 标题栏为自绘（`decorations: false`），窗口控制按钮必须位于右上角并遵循 Windows 11 惯例，禁止使用 macOS 交通灯圆点。

| 令牌 | 值 | 说明 |
|---|---|---|
| `--wc-btn-w` | `46px` | 控制按钮宽度 |
| `--wc-icon-size` | `14px` | 图标尺寸，线宽 1.4px |
| `--wc-divider-h` | `16px` | 控制区左侧分隔线高度 |
| `--wc-icon` | `var(--color-text-2)` | 图标默认色 |
| `--wc-hover-bg` | `#E5E7EB` | 按钮 hover 底色 |
| `--wc-hover-fg` | `var(--color-text)` | 按钮 hover 图标色 |
| `--wc-close-hover-bg` | `#E81123` | 关闭按钮 hover 底色 |
| `--wc-close-hover-fg` | `#FFFFFF` | 关闭按钮 hover 图标色 |

暗色映射：`--wc-hover-bg` → `rgba(255,255,255,0.1)`，`--wc-close-hover-bg` → `#C42B1C`。

布局规则：

- 顺序固定为「最小化 → 最大化/还原 → 关闭」，关闭贴最右。
- 按钮高度等于标题栏高度（`--titlebar-h`），无圆角，hover 底色铺满按钮区域。
- 标题栏左侧为应用图标、标题、同步状态；中部为可拖拽空白区；业务按钮组位于控制区左侧，与控制区之间用 1px 分隔线隔开。
- 双击标题空白区切换最大化；最大化后图标与 `aria-label` 切换为还原态。

## 十一、令牌实现要求

- Windows/Web：全部令牌定义在 `windows/src/styles/tokens.css`，用 `:root` 声明；组件只引用 `var(--token)`。
- Android：全部令牌定义在 `core/designsystem/src/main/kotlin/.../Tokens.kt`，用 `object Tokens { val colorPrimary = Color(0xFF3B82F6) ... }`。
- CI 校验：新增颜色/间距必须先在本文档登记，再改令牌文件；文档与令牌文件不一致时 CI 失败。
- 禁止在组件中出现十六进制颜色、`px` 魔法数字（允许 `0`、`1px` 边框、`100%`、`100vh`）。