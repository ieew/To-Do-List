package com.dshkit.mod;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.dshkit.core.Fingerprint;
import com.dshkit.core.Log;

import java.util.List;

/**
 * 剪贴板监听（本机 → 对端）。
 *
 * 三条防线：
 * 1. **防回流**：对端写进来的内容记下指纹，本机监听器看到同一指纹就直接丢；
 * 2. **短时去重**：同一段文字 2 秒内重复触发只发一次（有些应用会连续 setPrimaryClip）；
 * 3. **白/黑名单与大小**：`ignore_apps` 命中的包不传；超过 32 KiB 的文本不传。
 *
 * 安全性：只传文本，不传图片；`ignore_apps` 用来跳过银行/密码类应用。
 */
public final class ClipSource {

    public interface Listener {
        void onLocalClip(String text, String fp);
    }

    public interface PackageProvider {
        String currentPackage();
    }

    public static final int MAX_BYTES = 32 * 1024;

    private final Context ctx;
    private final Log log;
    private final List<String> ignoreApps;
    private final Listener listener;
    private final PackageProvider packageProvider;
    private ClipboardManager cm;
    private ClipboardManager.OnPrimaryClipChangedListener watcher;
    private volatile boolean started;
    private volatile String lastRemoteFp = "";
    private volatile String lastSentFp = "";
    private volatile long lastSentAt;

    public ClipSource(Context ctx, Log log, List<String> ignoreApps, Listener listener,
                      PackageProvider packageProvider) {
        this.ctx = ctx;
        this.log = log;
        this.ignoreApps = ignoreApps;
        this.listener = listener;
        this.packageProvider = packageProvider;
    }

    public synchronized void start() {
        if (started) return;
        try {
            cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                log.w("clip", "没有 ClipboardManager，剪贴板同步关闭");
                return;
            }
            watcher = new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    try {
                        onChanged();
                    } catch (Throwable t) {
                        log.e("clip/listener", t);
                    }
                }
            };
            cm.addPrimaryClipChangedListener(watcher);
            started = true;
            log.i("clip", "剪贴板监听已启动");
        } catch (Throwable t) {
            log.e("clip/start", t);
        }
    }

    public synchronized void stop() {
        if (!started || cm == null || watcher == null) {
            started = false;
            return;
        }
        try {
            cm.removePrimaryClipChangedListener(watcher);
        } catch (Throwable ignored) {
        }
        started = false;
        log.i("clip", "剪贴板监听已停止");
    }

    public boolean isStarted() {
        return started;
    }

    /** 对端写进来的内容：记住指纹，监听器再看到就丢（防回流）。 */
    public void rememberRemote(String text) {
        lastRemoteFp = Fingerprint.of(text);
    }

    /** 当前剪贴板文本；没有就返回空串。 */
    public String currentText() {
        try {
            ClipboardManager manager = cm != null ? cm
                    : (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null || manager.getPrimaryClip() == null) return "";
            ClipData clip = manager.getPrimaryClip();
            if (clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(ctx);
            return text == null ? "" : text.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void onChanged() {
        if (!started) return;
        String text = currentText();
        if (text.isEmpty()) return;
        String fp = Fingerprint.of(text);
        if (fp.equals(lastRemoteFp)) {
            log.i("clip", "对端写入的内容回环，跳过");
            return;
        }
        long now = System.currentTimeMillis();
        if (fp.equals(lastSentFp) && now - lastSentAt < 2000) {
            return;
        }
        if (!Fingerprint.fits(text, MAX_BYTES)) {
            log.w("clip", "文本超过 " + MAX_BYTES + " 字节，不传");
            return;
        }
        String pkg = "";
        try {
            pkg = packageProvider == null ? "" : packageProvider.currentPackage();
        } catch (Throwable ignored) {
        }
        pkg = pkg == null ? "" : pkg;
        if (!pkg.isEmpty() && ignoreApps != null && ignoreApps.contains(pkg)) {
            log.i("clip", "命中 ignore_apps，跳过：" + pkg);
            return;
        }
        lastSentFp = fp;
        lastSentAt = now;
        if (listener != null) listener.onLocalClip(text, fp);
    }
}
