package com.dshkit.mod;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;

import com.dshkit.KitLog;

import java.lang.reflect.Method;

/**
 * 待办面板的常驻通知开关。
 *
 * 打开后挂一条 ongoing 通知；点击 = 直接发一条本地广播，由本类在输入法进程里
 * 动态注册的接收器切换悬浮窗。不经过 Activity，通知点击不再闪输入法界面。
 *
 * 为什么能动态注册：dex 注入在宿主进程里，`Context.registerReceiver` 不需要
 * manifest 声明 Receiver，只要进程活着就能收。代价是进程被杀后点击通知无反应
 * （广播不会拉起进程）；输入法常驻、下次启动注入时重新注册，影响可忽略。
 * 注册失败（例如部分 ROM 对 flags 较真）时退回 LuaActivity 方案。
 */
public final class TodoNotice {

    public static final String TAG = "dsh_todo_ongoing";
    /** 通知点击广播；动作名带包内前缀，避免和别的应用撞车。 */
    public static final String ACTION_TOGGLE = "com.dshkit.todo.TOGGLE";
    private static final String PREFS = "dsh_todo";
    private static final String PREF_ON = "notice_on";
    private static final String PREF_TODO_ENABLED = "enabled";
    private static final String SCRIPT_PATH = "/sdcard/Documents/rime/scripts/dsh_todo.lua";
    /** Android 13+ 动态注册必须带 flags：RECEIVER_NOT_EXPORTED（发送方是宿主自己）。 */
    private static final int FLAG_RECEIVER_NOT_EXPORTED = 4;

    private final Context ctx;
    private final NotifySink sink;
    private final KitLog log;
    private final TodoOverlay overlay;
    private final SharedPreferences prefs;
    private boolean receiverRegistered;

    private final BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            try {
                log.i("todo/notice", "收到切换广播");
                if (!prefs.getBoolean(PREF_TODO_ENABLED, false)) {
                    toast("待办面板没启用：去输入法工具页打开");
                    return;
                }
                if (!overlay.canDraw()) {
                    toast("没有悬浮窗权限：去系统设置允许输入法「显示在其他应用上层」");
                    return;
                }
                overlay.toggle();
            } catch (Throwable t) {
                log.e("todo/notice/recv", t);
            }
        }
    };

    public TodoNotice(Context ctx, NotifySink sink, KitLog log, TodoOverlay overlay) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.sink = sink;
        this.log = log;
        this.overlay = overlay;
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.receiverRegistered = registerToggleReceiver();
        log.i("todo/notice", receiverRegistered
                ? "切换广播已注册，通知点击不再经 Activity"
                : "切换广播注册失败，通知点击退回 LuaActivity");
    }

    /** 用户意图（偏好里记的开/关）。进程重启后 sync() 靠它决定要不要重新挂通知。 */
    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ON, false);
    }

    /**
     * 真实状态：偏好开着 **且** 通知此刻确实挂在通知栏里。
     *
     * 工具页的开关必须看这个。进程被杀后系统会把通知一起收走，而偏好还在，
     * 只看偏好就会出现「通知没了、开关还勾着」。
     */
    public boolean isActive() {
        return isEnabled() && isShowing();
    }

    /** 查通知栏里有没有我们的常驻通知。查不到（老系统/异常）就当开着，避免误报关。 */
    private boolean isShowing() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return true;
            StatusBarNotification[] list = nm.getActiveNotifications();
            if (list == null) return false;
            String pkg = ctx.getPackageName();
            for (StatusBarNotification sbn : list) {
                if (TAG.equals(sbn.getTag()) && pkg.equals(sbn.getPackageName())) return true;
            }
            return false;
        } catch (Throwable t) {
            log.e("todo/notice/check", t);
            return true;
        }
    }

    /** 返回是否成功落地（权限被关时 false，但偏好照记，下次启动再试）。 */
    public boolean setEnabled(boolean on) {
        prefs.edit().putBoolean(PREF_ON, on).apply();
        if (!on) {
            sink.cancel(TAG);
            log.i("todo/notice", "已关闭常驻通知");
            return true;
        }
        return show();
    }

    /** 进程重启后恢复：上次开着就重新挂上。 */
    public void sync() {
        if (!isEnabled()) return;
        show();
    }

    // ---------------------------------------------------------------- 内部

    private boolean show() {
        if (!sink.canNotify(1)) {
            log.w("todo/notice", "通知权限被关，常驻通知挂不上");
            return false;
        }
        PendingIntent pending = pendingIntent();
        if (pending == null) return false;
        return sink.showOngoing(TAG, "待办", "点一下打开 / 收起待办面板", pending);
    }

    private PendingIntent pendingIntent() {
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            if (receiverRegistered) {
                Intent intent = new Intent(ACTION_TOGGLE);
                intent.setPackage(ctx.getPackageName());
                return PendingIntent.getBroadcast(ctx, 0x7010, intent, flags);
            }
            // 兜底：动态注册失败时退回 LuaActivity（会有界面一闪，但至少能用）。
            Intent intent = new Intent();
            intent.setClassName(ctx.getPackageName(), "com.androlua.LuaActivity");
            intent.putExtra("name", SCRIPT_PATH);
            intent.setData(Uri.parse("file://" + SCRIPT_PATH));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, 0x7010, intent, flags);
        } catch (Throwable t) {
            log.e("todo/notice/pending", t);
            return null;
        }
    }

    /**
     * 动态注册切换广播。
     *
     * Android 13（targetSdk 33+）要求带 flags；Android 14 起老接口会抛 SecurityException。
     * 编译目标是 android-23，没有带 flags 的重载，所以用反射调 API 26+ 的三参方法；
     * 反射不可用时退回老接口。
     */
    private boolean registerToggleReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= 26) {
            int flags = Build.VERSION.SDK_INT >= 33 ? FLAG_RECEIVER_NOT_EXPORTED : 0;
            try {
                invokeRegister(filter, flags);
                return true;
            } catch (Throwable ignored) {
                // 落到老接口兜底
            }
        }
        try {
            ctx.registerReceiver(toggleReceiver, filter);
            return true;
        } catch (Throwable t) {
            log.e("todo/notice/register", t);
            return false;
        }
    }

    private void invokeRegister(IntentFilter filter, int flags) throws Exception {
        Method m = Context.class.getMethod("registerReceiver",
                BroadcastReceiver.class, IntentFilter.class, int.class);
        m.invoke(ctx, toggleReceiver, filter, flags);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
