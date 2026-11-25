package com.dshkit.mod;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办面板的数据出口：通过 ContentProvider 调待办 App（`com.todo.android`）。
 *
 * 契约见 `To-Do-List/docs/ime-push-client.md` 第十七节。这里只做三件事：
 * 查两类任务、快速添加、勾选完成；不碰删除/编辑/成员。
 *
 * 所有方法都可能抛异常：provider 不存在（App 未装）→ IllegalArgumentException；
 * 调用方被拒 → SecurityException。由面板层转成「待办不可用」。
 */
public final class TodoClient {

    public static final String AUTHORITY = "com.todo.android.ime";
    public static final Uri TASKS_URI = Uri.parse("content://" + AUTHORITY + "/tasks");

    public static final String SCOPE_WAITING = "waiting";
    public static final String SCOPE_UPCOMING = "upcoming";
    public static final int DEFAULT_LIMIT = 20;

    private static final String COL_TASK_ID = "task_id";
    private static final String COL_TITLE = "title";
    private static final String COL_START_AT = "start_at";
    private static final String COL_DUE_AT = "due_at";
    private static final String COL_LIST_NAME = "list_name";
    private static final String COL_OVERDUE = "overdue";

    private final Context ctx;

    public TodoClient(Context ctx) {
        this.ctx = ctx;
    }

    public static final class Task {
        public final String id;
        public final String title;
        public final String listName;
        public final long startAt;
        public final long dueAt;
        public final boolean overdue;

        Task(String id, String title, String listName, long startAt, long dueAt, boolean overdue) {
            this.id = id;
            this.title = title;
            this.listName = listName;
            this.startAt = startAt;
            this.dueAt = dueAt;
            this.overdue = overdue;
        }
    }

    /** 读一类任务；scope 见 {@link #SCOPE_WAITING}/{@link #SCOPE_UPCOMING}。 */
    public List<Task> query(String scope, int limit) {
        Uri uri = TASKS_URI.buildUpon()
                .appendQueryParameter("scope", scope)
                .appendQueryParameter("limit", String.valueOf(limit))
                .build();
        List<Task> out = new ArrayList<Task>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c == null) throw new IllegalStateException("待办 App 没有返回数据");
            int iId = c.getColumnIndex(COL_TASK_ID);
            int iTitle = c.getColumnIndex(COL_TITLE);
            int iStart = c.getColumnIndex(COL_START_AT);
            int iDue = c.getColumnIndex(COL_DUE_AT);
            int iList = c.getColumnIndex(COL_LIST_NAME);
            int iOverdue = c.getColumnIndex(COL_OVERDUE);
            while (c.moveToNext()) {
                out.add(new Task(
                        iId < 0 ? "" : c.getString(iId),
                        iTitle < 0 ? "" : c.getString(iTitle),
                        iList < 0 ? "" : c.getString(iList),
                        iStart < 0 ? 0L : c.getLong(iStart),
                        iDue < 0 ? 0L : c.getLong(iDue),
                        iOverdue >= 0 && c.getInt(iOverdue) == 1));
            }
            return out;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 快速添加；返回新任务 id（不需要时忽略）。 */
    public String add(String title) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        Uri uri = ctx.getContentResolver().insert(TASKS_URI, cv);
        return uri == null ? null : uri.getLastPathSegment();
    }

    /** 勾选/取消完成。 */
    public boolean setDone(String taskId, boolean done) {
        ContentResolver cr = ctx.getContentResolver();
        Bundle input = new Bundle();
        input.putBoolean("done", done);
        Bundle out = cr.call(TASKS_URI, "set_done", taskId, input);
        return out != null && out.getBoolean("ok", false);
    }

    /** 删除任务（App 端写 `__deleted` 墓碑）。插件侧调用前必须二次确认。 */
    public boolean delete(String taskId) {
        ContentResolver cr = ctx.getContentResolver();
        Bundle input = new Bundle();
        Bundle out = cr.call(TASKS_URI, "delete_task", taskId, input);
        return out != null && out.getBoolean("ok", false);
    }

    /** 探测 App 是否提供了这个 provider（供面板提示用）。 */
    public boolean available() {
        try {
            query(SCOPE_WAITING, 1);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ dsh 数据通道

    /** 取 App 授权信息；未登录/未启用返回 null（不抛异常，按「不可用」处理）。 */
    public java.util.Map<String, Object> dshAuthorize() {
        Bundle out = dshCall("dsh_authorize", null);
        if (out == null) return null;
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        for (String key : out.keySet()) {
            map.put(key, out.get(key));
        }
        return map;
    }

    /** 续期后回写轮换后的凭证。 */
    public void dshUpdateCredential(String credential, long expiresAt) {
        Bundle input = new Bundle();
        input.putString("credential", credential);
        input.putLong("credential_expires_at", expiresAt);
        dshCall("dsh_credential_update", input);
    }

    /** 回写 MQTT 连接状态（设置页展示）。 */
    public void dshReport(boolean connected, String error) {
        Bundle input = new Bundle();
        input.putBoolean("connected", connected);
        input.putString("error", error == null ? "" : error);
        dshCall("dsh_status", input);
    }

    /** 收到 nudge 时唤醒 App 立即 pull。 */
    public void dshNudge() {
        dshCall("dsh_nudge", new Bundle());
    }

    /** 数据变更信号：把 seq 与受影响工作区转给 App，由它跑 op 同步。 */
    public void dshSync(long seq, String workspacesCsv) {
        Bundle input = new Bundle();
        input.putLong("sync_seq", seq);
        input.putString("sync_ws", workspacesCsv == null ? "" : workspacesCsv);
        dshCall("dsh_sync", input);
    }

    /** §8.1：主通道认领最多等这么久，超时就自己展示（宁可重复，不要都不弹）。 */
    private static final long CLAIM_TIMEOUT_MS = 800L;

    /**
     * 展示提醒前认领：返回 false 表示**明确被告知**另一方（App）已经弹过，插件应跳过。
     *
     * claim 是去重优化，不是正确性依赖（§8.1）。provider 不可用、超时、返回缺字段，
     * 一律当作「没抢到但也没人认领」→ 插件自己展示。把展示能力押在 App 进程能被拉起上，
     * 等于让主通道跟着最弱的一环走，这与 §3.0 的分级前提冲突。
     *
     * 这里额外加 800ms 超时：ContentResolver.call 会跨进程等 App 响应，而 App 可能正在冷启动，
     * 不设上限会把「立即弹」拖成「十几秒后才弹」。
     */
    public boolean dshClaimReminder(String taskId, String workspaceId, long remindAt) {
        final Bundle input = new Bundle();
        input.putString("task_id", taskId);
        input.putString("workspace_id", workspaceId);
        input.putLong("remind_at", remindAt);
        final Bundle[] box = new Bundle[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                box[0] = dshCall("dsh_claim_reminder", input);
            }
        }, "dsh-claim");
        worker.setDaemon(true);
        long start = System.currentTimeMillis();
        worker.start();
        try {
            worker.join(CLAIM_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        Bundle out = box[0];
        if (out == null) {
            long waited = System.currentTimeMillis() - start;
            if (waited >= CLAIM_TIMEOUT_MS) {
                // 没有 log 就没法解释「为什么两边都弹了」，这条日志是后续排障的入口
                android.util.Log.i("dsh-kit", "claim 超时 " + waited + "ms，插件自行展示 task=" + taskId);
            }
            return true;
        }
        return out.getBoolean("claimed", true);
    }

    private Bundle dshCall(String method, Bundle extras) {
        try {
            return ctx.getContentResolver().call(TASKS_URI, method, null, extras);
        } catch (Throwable t) {
            return null;
        }
    }
}
