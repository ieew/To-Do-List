package com.todo.android.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 颜色/间距/圆角/字号/动效令牌；组件禁止出现裸十六进制颜色。 */
object Tokens {
    // 间距（8px 栅格 + 4px 半格）
    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val xxl = 24.dp
        val xxxl = 32.dp
        val huge = 48.dp
    }

    object Radius {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val full = 999.dp
    }

    object Motion {
        const val FAST = 150
        const val BASE = 250
        const val SLOW = 300
        const val PROGRESS = 400
        const val SKELETON_PULSE = 1200
    }

    object Size {
        val iconXs = 12.dp
        val iconSm = 14.dp
        val iconMd = 16.dp
        val iconLg = 20.dp
        val iconXl = 24.dp
        val touch = 48.dp
        val checkbox = 20.dp
        val miniCheckbox = 18.dp
        val priorityDot = 6.dp
        val progressHeight = 6.dp
        val statRing = 96.dp
    }

    /** 浅色令牌（design-tokens.md 权威值）。 */
    val light = TodoColors(
        primary = Color(0xFF3B82F6),
        primary50 = Color(0xFFEFF6FF),
        primary100 = Color(0xFFDBEAFE),
        success = Color(0xFF10B981),
        success50 = Color(0xFFECFDF5),
        danger = Color(0xFFEF4444),
        danger50 = Color(0xFFFEF2F2),
        dangerBorder = Color(0xFFFCA5A5),
        warning = Color(0xFFF59E0B),
        warning50 = Color(0xFFFEF3C7),
        warningFg = Color(0xFFB45309),
        purple = Color(0xFF8B5CF6),
        purple50 = Color(0xFFF5F3FF),
        purpleFg = Color(0xFF7C3AED),
        aiFg = Color(0xFF6D28D9),
        aiGradientStart = Color(0xFFEDE9FE),
        aiGradientEnd = Color(0xFFDBEAFE),
        bg = Color(0xFFF8FAFC),
        card = Color(0xFFFFFFFF),
        text = Color(0xFF1F2937),
        text2 = Color(0xFF6B7280),
        text3 = Color(0xFF9CA3AF),
        border = Color(0xFFE5E7EB),
        border2 = Color(0xFFF1F5F9),
        track = Color(0xFFE2E8F0),
        dashed = Color(0xFFCBD5E1),
        overlay = Color(0x0F0F172A),
        onPrimary = Color(0xFFFFFFFF),
        isDark = false,
    )

    /** 深色令牌（design-tokens.md 1.4 预置映射）。 */
    val dark = TodoColors(
        primary = Color(0xFF60A5FA),
        primary50 = Color(0x293B82F6),
        primary100 = Color(0x3D3B82F6),
        success = Color(0xFF34D399),
        success50 = Color(0x2910B981),
        danger = Color(0xFFF87171),
        danger50 = Color(0x24EF4444),
        dangerBorder = Color(0x66EF4444),
        warning = Color(0xFFFBBF24),
        warning50 = Color(0x29F59E0B),
        warningFg = Color(0xFFFCD34D),
        purple = Color(0xFFA78BFA),
        purple50 = Color(0x298B5CF6),
        purpleFg = Color(0xFFC4B5FD),
        aiFg = Color(0xFFC4B5FD),
        aiGradientStart = Color(0x294C1D95),
        aiGradientEnd = Color(0x292563EB),
        bg = Color(0xFF0F172A),
        card = Color(0xFF1E293B),
        text = Color(0xFFF1F5F9),
        text2 = Color(0xFF94A3B8),
        text3 = Color(0xFF64748B),
        border = Color(0xFF334155),
        border2 = Color(0xFF1E293B),
        track = Color(0xFF334155),
        dashed = Color(0xFF475569),
        overlay = Color(0x8C0B1220),
        onPrimary = Color(0xFF0B1220),
        isDark = true,
    )

    /** OLED 纯黑：像素熄灭更省电，同时保留可读的层级。 */
    val oled = dark.copy(
        bg = Color(0xFF000000),
        card = Color(0xFF0A0A0A),
        border = Color(0xFF1F1F1F),
        border2 = Color(0xFF141414),
        track = Color(0xFF1A1A1A),
        dashed = Color(0xFF2A2A2A),
        text = Color(0xFFFFFFFF),
        text2 = Color(0xFFA3A3A3),
        text3 = Color(0xFF737373),
        overlay = Color(0x99000000),
    )

    object Type {
        val greeting = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
        val pageTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
        val section = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)

        /** 任务标题默认档（日历/统计/详情等）。 */
        val taskTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)

        /** 清单页专用的任务标题：字号更大，便于一眼扫读。 */
        val taskTitleList = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)

        val body = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp)
        val meta = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)
        val chip = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
        val badge = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp)
        val number = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp)
        val mono = TextStyle(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
        )
    }
}

data class TodoColors(
    val primary: Color,
    val primary50: Color,
    val primary100: Color,
    val success: Color,
    val success50: Color,
    val danger: Color,
    val danger50: Color,
    val dangerBorder: Color,
    val warning: Color,
    val warning50: Color,
    val warningFg: Color,
    val purple: Color,
    val purple50: Color,
    val purpleFg: Color,
    val aiFg: Color,
    val aiGradientStart: Color,
    val aiGradientEnd: Color,
    val bg: Color,
    val card: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val border: Color,
    val border2: Color,
    val track: Color,
    val dashed: Color,
    val overlay: Color,
    val onPrimary: Color,
    val isDark: Boolean,
) {
    fun priorityColor(priority: String): Color = when (priority) {
        "high" -> danger
        "low" -> primary
        else -> warning
    }
}
