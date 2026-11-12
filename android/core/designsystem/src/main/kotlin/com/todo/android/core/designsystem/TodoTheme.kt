package com.todo.android.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

enum class ThemeMode {
    LIGHT,
    DARK,
    OLED,
    SYSTEM,
    ;

    companion object {
        fun from(raw: String?): ThemeMode = when (raw) {
            "light" -> LIGHT
            "dark" -> DARK
            "oled" -> OLED
            else -> SYSTEM
        }
    }
}

val LocalTodoColors = staticCompositionLocalOf { Tokens.light }
val LocalReduceMotion = staticCompositionLocalOf { false }

object TodoTheme {
    val colors: TodoColors
        @Composable get() = LocalTodoColors.current

    val reduceMotion: Boolean
        @Composable get() = LocalReduceMotion.current
}

@Composable
fun TodoTheme(
    mode: ThemeMode,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val resolved = when (mode) {
        ThemeMode.LIGHT -> Tokens.light
        ThemeMode.DARK -> Tokens.dark
        ThemeMode.OLED -> Tokens.oled
        ThemeMode.SYSTEM -> if (systemDark) Tokens.dark else Tokens.light
    }
    val colorScheme = resolved.toMaterial(isLight = !resolved.isDark)
    CompositionLocalProvider(
        LocalTodoColors provides resolved,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = todoTypography(resolved),
            content = content,
        )
    }
}

private fun TodoColors.toMaterial(isLight: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary50,
        onPrimaryContainer = primary,
        secondary = purple,
        background = bg,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = border2,
        onSurfaceVariant = text2,
        outline = border,
        error = danger,
        onError = Color.White,
    )
}

private fun todoTypography(colors: TodoColors): Typography {
    fun base(style: TextStyle): TextStyle = style.copy(color = colors.text)
    return Typography(
        bodyLarge = base(Tokens.Type.body),
        bodyMedium = base(Tokens.Type.body),
        bodySmall = base(Tokens.Type.meta),
        titleLarge = base(Tokens.Type.pageTitle),
        titleMedium = base(Tokens.Type.section),
        labelLarge = base(Tokens.Type.taskTitle),
        labelMedium = base(Tokens.Type.chip),
        labelSmall = base(Tokens.Type.badge),
    )
}

fun TodoColors.tagColors(bg: String?, fg: String?): Pair<Color, Color> {
    val background = bg?.let(::parseHex) ?: primary50
    val foreground = fg?.let(::parseHex) ?: primary
    return background to foreground
}

fun parseHex(raw: String, fallback: Color = Tokens.light.primary): Color {
    val text = raw.trim()
    if (!text.startsWith("#")) return fallback
    val hex = text.removePrefix("#")
    return runCatching {
        when (hex.length) {
            6 -> Color(0xFF000000 or hex.toLong(16))
            8 -> Color(hex.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
}
