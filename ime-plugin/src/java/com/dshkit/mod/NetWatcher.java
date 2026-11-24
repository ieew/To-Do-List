package com.dshkit.mod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;

import com.dshkit.core.Log;
import com.dshkit.core.Session;

/**
 * 网络变化感知：WiFi ↔ 移动数据切换后立刻重连，而不是等 TCP 自己超时。
 *
 * 用 `registerNetworkCallback(NetworkRequest, PendingIntent)`（API 21 就有）而不是
 * `registerDefaultNetworkCallback`（API 24）——编译目标是 android-23，而且这样不用反射。
 */
public final class NetWatcher {

    public static final String ACTION_NET = "com.dshkit.NETWORK_AVAILABLE";

    private final Context ctx;
    private final Log log;
    private final Session session;
    private BroadcastReceiver receiver;
    private android.app.PendingIntent pending;
    private boolean registered;
    private volatile long lastKickAt;

    public NetWatcher(Context ctx, Log log, Session session) {
        this.ctx = ctx;
        this.log = log;
        this.session = session;
    }

    public synchronized void start() {
        if (registered) return;
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long now = System.currentTimeMillis();
                    if (now - lastKickAt < 5000) return; // 连续广播别把重连打爆（Session 里还会再合并一次）
                    lastKickAt = now;
                    log.i("net", "网络可用，触发重连");
                    try {
                        session.kick();
                    } catch (Throwable t) {
                        log.e("net/kick", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_NET);
            // Android 13+（API 33）要求动态注册非系统广播时带 RECEIVER_NOT_EXPORTED，
            // 否则抛 SecurityException。编译目标是 android-23，只能反射调 4 参重载。
            boolean registeredOk = false;
            try {
                java.lang.reflect.Method m = android.content.Context.class.getMethod(
                        "registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(ctx, receiver, filter, Integer.valueOf(4)); // RECEIVER_NOT_EXPORTED
                registeredOk = true;
            } catch (Throwable ignored) {
            }
            if (!registeredOk) {
                ctx.registerReceiver(receiver, filter);
            }

            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                log.w("net", "没有 ConnectivityManager，跳过网络监听");
                return;
            }
            NetworkRequest request = new NetworkRequest.Builder().build();
            Intent intent = new Intent(ACTION_NET);
            intent.setPackage(ctx.getPackageName());
            pending = android.app.PendingIntent.getBroadcast(
                    ctx, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            cm.registerNetworkCallback(request, pending);
            registered = true;
            log.i("net", "网络变化监听已启动");
        } catch (Throwable t) {
            log.e("net/start", t);
        }
    }

    public synchronized void stop() {
        if (!registered) return;
        try {
            if (receiver != null) ctx.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        try {
            if (pending != null) {
                ConnectivityManager cm = (ConnectivityManager)
                        ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(pending);
            }
        } catch (Throwable ignored) {
        }
        receiver = null;
        pending = null;
        registered = false;
    }
}
