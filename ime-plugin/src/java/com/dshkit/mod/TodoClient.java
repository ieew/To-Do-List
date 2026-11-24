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
}
