package com.dshkit;

import android.content.Context;

import com.dshkit.core.Log;

import java.io.File;

/**
 * L2 日志的 Android 入口：私有目录为主，**同时镜像一份到 /sdcard**。
 *
 * 镜像的原因：真机排查时 proot 读不到 app 私有目录，而输入法本身有存储权限，
 * 把同一份日志写到 `/sdcard/Documents/rime/logs/kit-YYYYMMDD.log` 就能直接看。
 * 两边都是滚动日志、都吞异常，写不进去只影响镜像。
 */
public final class KitLog extends Log {

    private final Log mirror;

    public KitLog(Context ctx) {
        super(new File(ctx.getFilesDir(), "dsh-kit"));
        this.mirror = new Log(new File("/sdcard/Documents/rime/logs"));
    }

    @Override
    public void i(String tag, String msg) {
        super.i(tag, msg);
        mirror.i(tag, msg);
    }

    @Override
    public void w(String tag, String msg) {
        super.w(tag, msg);
        mirror.w(tag, msg);
    }

    @Override
    public void e(String tag, String msg) {
        super.e(tag, msg);
        mirror.e(tag, msg);
    }

    @Override
    public void e(String tag, Throwable t) {
        super.e(tag, t);
        mirror.e(tag, t);
    }

    @Override
    public synchronized int clear() {
        int removed = super.clear();
        removed += mirror.clear();
        return removed;
    }
}
