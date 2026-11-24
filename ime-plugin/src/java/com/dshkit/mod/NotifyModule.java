package com.dshkit.mod;

import com.dshkit.core.Json;
import com.dshkit.core.Levels;
import com.dshkit.core.Log;
import com.dshkit.core.Session;
import com.dshkit.core.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L4 模块：通知。把服务端下发的 `notify` 消息变成系统提醒。
 *
 * 等级（payload.level，0–7）**只影响提醒方式，不影响送达**：
 * 什么等级都会走到 ack，0 级只是不弹通知，但仍进本地留档。
 *
 * 幂等靠 msg_id：同一条消息重复投递（断网补投 / ack 丢了）只处理一次。
 */
public final class NotifyModule {

    private static final String K_SEEN = "notify_seen";
    private static final String K_INBOX = "notify_inbox";
    private static final int SEEN_LIMIT = 200;
    private static final int INBOX_LIMIT = 50;

    private final Store store;
    private final NotifySink sink;
    private final Log log;
    private final int defaultLevel;
    private Session session;
    private TodoClient todoClient;
    private volatile Map<String, Object> last;

    public NotifyModule(Store store, NotifySink sink, Log log, int defaultLevel) {
        this.store = store;
        this.sink = sink;
        this.log = log;
        this.defaultLevel = Levels.clamp(defaultLevel);
    }

    /** 构造顺序原因：Session 先建、模块后建，再回填。 */
    public void setSession(Session session) {
        this.session = session;
    }

    /** 提醒去重：展示前通过 provider 向 App 认领，避免两边都弹。 */
    public void setTodoClient(TodoClient todoClient) {
        this.todoClient = todoClient;
    }

    /** 返回 true 表示已处理、可以 ack。 */
    public boolean apply(String kind, String msgId, String payloadJson, boolean live) {
        if (!"notify".equals(kind)) {
            // P1 只处理通知；clip 落库分支留给 P2，先 ack 掉避免堵队列。
            log.w("notify", "暂不处理的类型 " + kind + "，先确认掉");
            return true;
        }
        if (msgId != null && !msgId.isEmpty() && alreadySeen(msgId)) {
            log.i("notify", "重复消息，跳过 id=" + msgId);
            return true;
        }

        String title = "通知";
        String body = "";
        String url = "";
        String tag = msgId;
        boolean legacySilent = false;
        int level = defaultLevel;
        boolean reminder = false;
        String taskId = "";
        String workspaceId = "";
        long remindAt = 0L;
        try {
            Map<String, Object> payload = Json.asObject(Json.parse(payloadJson));
            String t = Json.str(payload, "title", "");
            if (!t.isEmpty()) title = t;
            String b = Json.str(payload, "body", "");
            if (b.isEmpty()) b = Json.str(payload, "text", "");
            if (b.isEmpty()) b = Json.str(payload, "message", "");
            body = b;
            url = Json.str(payload, "url", "");
            String tg = Json.str(payload, "tag", "");
            if (!tg.isEmpty()) tag = tg;
            legacySilent = Json.bool(payload, "silent", false);
            level = Levels.fromPayload(payload, defaultLevel);
            // 老格式：silent=true 且没写 level，按「只进通知中心」处理
            if (payload.get("level") == null && legacySilent) level = 1;
            reminder = "reminder".equals(Json.str(payload, "type", ""));
            taskId = Json.str(payload, "task_id", "");
            workspaceId = Json.str(payload, "workspace_id", "");
            remindAt = parseIso(Json.str(payload, "remind_at", ""));
        } catch (Throwable t) {
            body = payloadJson == null ? "" : payloadJson;
            if (body.length() > 120) body = body.substring(0, 120) + "…";
        }
        if (tag == null || tag.isEmpty()) tag = "dsh-" + System.currentTimeMillis();

        // 服务端提醒：App 与插件只允许一方展示。
        if (reminder && !shouldShowReminder(taskId, workspaceId, remindAt)) {
            markSeen(msgId);
            record(msgId, level, title, body, false);
            last = Json.obj("level", level, "title", title, "shown", false);
            log.i("notify", "提醒已由 App 展示，插件跳过 tag=" + tag);
            if (session != null) session.noteApplied();
            return true;
        }

        boolean shown = sink.show(tag, title, body, level, url);
        markSeen(msgId);
        record(msgId, level, title, body, shown);
        last = Json.obj("level", level, "title", title, "shown", shown);
        String mode = Levels.unimportant(level)
                ? "静默进通知中心"
                : (shown ? "已提醒" : "仅留档");
        log.i("notify", "L" + level + " " + mode + " tag=" + tag);
        if (session != null) session.noteApplied();
        return true;
    }

    public boolean canNotify(int level) {
        return sink.canNotify(level);
    }

    /**
     * 提醒是否由插件展示：
     * - App 关闭通知 / 处于免打扰 → 不展示（但仍 ack，避免堵队列）；
     * - 通过 provider 认领，抢不到说明 App 已经弹过。
     */
    private boolean shouldShowReminder(String taskId, String workspaceId, long remindAt) {
        Session s = session;
        if (s != null) {
            if (!s.notificationsEnabled()) {
                log.i("notify", "App 已关闭通知，插件静默记录");
                return false;
            }
            if (s.inDnd(System.currentTimeMillis())) {
                log.i("notify", "免打扰时段，插件静默记录");
                return false;
            }
        }
        if (todoClient == null || taskId == null || taskId.isEmpty() || remindAt <= 0) return true;
        boolean claimed = todoClient.dshClaimReminder(taskId, workspaceId, remindAt);
        if (!claimed) log.i("notify", "提醒已被 App 认领 task=" + taskId);
        return claimed;
    }

    /** RFC3339 → 毫秒；解析失败返回 0（不做跨端认领，按普通通知处理）。 */
    private static long parseIso(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        try {
            return java.time.OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 本地测试：不经过服务端，直接按等级弹一条，用来验证渠道/权限。 */
    public void selfTest(int level) {
        int lv = Levels.clamp(level);
        boolean shown = sink.show("dsh-selftest",
                "dsh-kit 测试 L" + lv, Levels.describe(lv), lv, "");
        record("selftest", lv, "dsh-kit 测试 L" + lv, Levels.describe(lv), shown);
        last = Json.obj("level", lv, "title", "测试", "shown", shown);
    }

    public Map<String, Object> last() {
        return last == null ? Json.obj() : last;
    }

    public List<Object> inbox() {
        return Json.asArray(store.snapshot().get(K_INBOX));
    }

    // ------------------------------------------------------------ 去重 / 留档

    private boolean alreadySeen(String msgId) {
        for (Object item : Json.asArray(store.snapshot().get(K_SEEN))) {
            if (msgId.equals(item)) return true;
        }
        return false;
    }

    private void markSeen(String msgId) {
        if (msgId == null || msgId.isEmpty()) return;
        List<Object> seen = new ArrayList<Object>(Json.asArray(store.snapshot().get(K_SEEN)));
        seen.add(msgId);
        while (seen.size() > SEEN_LIMIT) seen.remove(0);
        store.put(K_SEEN, seen);
        store.saveIfDirty();
    }

    /** 所有等级都留档（含 0 级），保证「不提醒」不等于「没收到」。 */
    private void record(String msgId, int level, String title, String body, boolean shown) {
        List<Object> inbox = new ArrayList<Object>(Json.asArray(store.snapshot().get(K_INBOX)));
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", msgId == null ? "" : msgId);
        item.put("level", Integer.valueOf(level));
        item.put("title", title);
        item.put("body", body);
        item.put("shown", Boolean.valueOf(shown));
        item.put("at", Long.valueOf(System.currentTimeMillis()));
        inbox.add(item);
        while (inbox.size() > INBOX_LIMIT) inbox.remove(0);
        store.put(K_INBOX, inbox);
        store.saveIfDirty();
    }
}
