package com.todo.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简崩溃记录器：捕获未处理异常，把堆栈写到 `files/last_crash.txt`，
 * 下次启动时由 UI 弹窗展示并支持复制，方便在无法接 adb 的手机上定位闪退。
 *
 * 不采集任何用户数据，只记录时间、线程、机型和堆栈。
 */
object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val header = buildString {
                    appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    appendLine("thread=" + thread.name)
                    appendLine(
                        "device=" + Build.MANUFACTURER + " " + Build.MODEL +
                            " api=" + Build.VERSION.SDK_INT +
                            " target=" + appContext.applicationInfo.targetSdkVersion,
                    )
                    appendLine("version=" + runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull())
                }
                File(appContext.filesDir, FILE_NAME).writeText(header + Log.getStackTraceString(error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
