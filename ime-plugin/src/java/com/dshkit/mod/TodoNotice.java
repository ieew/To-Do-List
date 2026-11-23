package com.dshkit.mod;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.dshkit.KitLog;

/**
 * 待办面板的常驻通知开关。
 *
 * 打开后挂一条 ongoing 通知；点击 = 用 LuaActivity 跑 `rime/scripts/dsh_todo.lua`，
 * 脚本里调 `todo.toggle` 切换悬浮窗并结束 Activity（悬浮窗用 application context 的
 * WindowManager 添加，Activity 结束不影响它）。
 *
 * 为什么必须经 Activity：注入的 dex 没有 manifest，注册不了 Receiver/Service，
 * 通知点击只能用现存组件——`com.androlua.LuaActivity` 是宿主里唯一 exported 的入口。
 */
public final class TodoNotice {

    public static final String TAG = "dsh_todo_ongoing";
    private static final String PREFS = "dsh_todo";
    private static final String PREF_ON = "notice_on";
    private static final String SCRIPT_PATH = "/sdcard/Documents/rime/scripts/dsh_todo.lua";

    private final Context ctx;
    private final NotifySink sink;
    private final KitLog log;
    private final SharedPreferences prefs;

    public TodoNotice(Context ctx, NotifySink sink, KitLog log) {
        this.ctx = ctx;
        this.sink = sink;
        this.log = log;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ON, false);
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
            Intent intent = new Intent();
            intent.setClassName(ctx.getPackageName(), "com.androlua.LuaActivity");
            intent.putExtra("name", SCRIPT_PATH);
            intent.setData(Uri.parse("file://" + SCRIPT_PATH));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, 0x7010, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            log.e("todo/notice/pending", t);
            return null;
        }
    }
}
