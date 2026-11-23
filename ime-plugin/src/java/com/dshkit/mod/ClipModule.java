package com.dshkit.mod;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.dshkit.core.Fingerprint;
import com.dshkit.core.Json;
import com.dshkit.core.Log;
import com.dshkit.core.Protocol;
import com.dshkit.core.Session;

import java.util.Map;

/**
 * L4 模块：剪贴板（双向）。
 *
 * - 本机复制 → {@link ClipSource} 抓到 → {@link #sendLocal} 上行到服务端；
 * - 对端发来 → {@link #apply} 写进本机剪贴板，并让 ClipSource 记住指纹防回流；
 * - 默认不落库（服务端 clip_persist=false），所以这是「能到就到」的通道，不算可靠投递。
 */
public final class ClipModule {

    private final Context ctx;
    private final Log log;
    private final ClipSource source;
    private Session session;
    private volatile Map<String, Object> last;

    public ClipModule(Context ctx, Log log, ClipSource source) {
        this.ctx = ctx;
        this.log = log;
        this.source = source;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    /** 本机复制 → 上行。 */
    public void sendLocal(String text, String fp) {
        if (session == null) return;
        last = Json.obj("dir", "up", "len", text.length(), "fp", fp, "at",
                System.currentTimeMillis());
        session.sendClip(text, fp);
    }

    /** 对端发来 → 写剪贴板。返回 true 表示已处理（可以 ack）。 */
    public boolean apply(String kind, String msgId, String payloadJson, boolean live) {
        if (!"clip".equals(kind)) return false;
        String text = Protocol.clipText(payloadJson);
        if (text == null || text.isEmpty()) {
            log.w("clip", "空剪贴板消息，忽略");
            return true;
        }
        String current = source.currentText();
        if (current.equals(text)) {
            log.i("clip", "本机已经是这段内容，跳过写入");
            source.rememberRemote(text);
            return true;
        }
        try {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("dsh-kit", text));
            source.rememberRemote(text);
            last = Json.obj("dir", "down", "len", text.length(),
                    "fp", Fingerprint.of(text), "at", System.currentTimeMillis());
            if (session != null) session.noteApplied();
            log.i("clip", "已写入剪贴板（" + text.length() + " 字）");
            return true;
        } catch (Throwable t) {
            log.e("clip/write", t);
            return false;
        }
    }

    public Map<String, Object> last() {
        return last == null ? Json.obj() : last;
    }

    public boolean isListening() {
        return source.isStarted();
    }

    public ClipSource source() {
        return source;
    }
}
