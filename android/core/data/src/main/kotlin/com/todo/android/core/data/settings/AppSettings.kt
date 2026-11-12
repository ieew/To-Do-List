package com.todo.android.core.data.settings

import kotlinx.serialization.Serializable

/** 颜色主题：浅色 / 深色 / 纯黑（OLED）/ 跟随系统。 */
object ThemeModes {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val OLED = "oled"
    const val SYSTEM = "system"

    val all = listOf(LIGHT, DARK, OLED, SYSTEM)

    fun normalize(raw: String?): String = if (raw in all) raw!! else SYSTEM
}

object StartupModes {
    const val MAIN = "main"
    const val LAST = "last"
}

/**
 * 后台存活强度：
 * - `off` 完全关闭；
 * - `heartbeat`（默认）15 分钟心跳 + WorkManager 兜底，无常驻通知；
 * - `service` 在心跳之外再加一个常驻前台服务，切掉任务后能快速重拉。
 */
object KeepAliveModes {
    const val OFF = "off"
    const val HEARTBEAT = "heartbeat"
    const val SERVICE = "service"

    val all = listOf(OFF, HEARTBEAT, SERVICE)

    fun normalize(raw: String?): String = if (raw in all) raw!! else HEARTBEAT

    fun isEnabled(mode: String?): Boolean = normalize(mode) != OFF
}

/** 启动时打开的页面偏好。 */
object DndDefaults {
    const val START = "22:00"
    const val END = "08:00"
}

/**
 * 应用设置（存 `preferences` 表的一条 JSON）。
 * 新增字段必须有默认值，避免旧 JSON 解析失败导致整份设置回落默认。
 */
@Serializable
data class AppSettings(
    val theme: String = ThemeModes.SYSTEM,
    val reduceMotion: Boolean = false,
    val startup: String = StartupModes.MAIN,
    val language: String = "zh-CN",
    val syncIntervalMinutes: Int = 5,
    val serverUrl: String = "http://127.0.0.1:8000",
    val notifications: Boolean = true,
    val exactAlarm: Boolean = true,
    val keepAliveMode: String = KeepAliveModes.HEARTBEAT,
    val dndEnabled: Boolean = false,
    val dndStart: String = DndDefaults.START,
    val dndEnd: String = DndDefaults.END,
    val aiEnabled: Boolean = true,
    val aiImageInput: Boolean = true,
    val aiEndpoint: String = "https://api.deepseek.com/v1",
    val aiModel: String = "deepseek-chat",
    val aiConfirmThreshold: Int = 10,
)
