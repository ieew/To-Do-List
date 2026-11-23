package com.dshkit.core;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * L2 日志：滚动写入 `<dir>/logs/kit-YYYYMMDD.log`。**零 Android 依赖**。
 *
 * - 单文件上限 512KB，超出后改名 .1 覆盖；
 * - 只记元信息，不记正文（剪贴板/消息内容一律不落日志）；
 * - 任何 IO 异常都吞掉，日志本身不能影响输入法。
 */
public class Log {

    private static final long MAX_BYTES = 512L * 1024L;
    private final File dir;
    private final SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private final SimpleDateFormat ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public Log(File base) {
        this.dir = new File(base, "logs");
        try {
            dir.mkdirs();
        } catch (Throwable ignored) {
        }
    }

    private File file() {
        return new File(dir, "kit-" + day.format(new Date()) + ".log");
    }

    public void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public void e(String tag, String msg) {
        write("E", tag, msg);
    }

    public void e(String tag, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 6; i++) {
            sb.append(" | ").append(st[i].getClassName()).append('.').append(st[i].getMethodName());
        }
        write("E", tag, sb.toString());
    }

    private synchronized void write(String level, String tag, String msg) {
        try {
            File f = file();
            if (f.length() > MAX_BYTES) {
                File old = new File(dir, f.getName() + ".1");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(ts.format(new Date()));
                w.write(' ');
                w.write(level);
                w.write('/');
                w.write(tag);
                w.write(' ');
                w.write(msg == null ? "" : msg);
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public String path() {
        return file().getAbsolutePath();
    }

    /** 删掉本目录下所有 kit-*.log（面板「清空日志」用）。返回删了几个文件。 */
    public synchronized int clear() {
        int removed = 0;
        try {
            File[] files = dir.listFiles();
            if (files == null) return 0;
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("kit-") && name.endsWith(".log")
                        || name.startsWith("kit-") && name.endsWith(".log.1")) {
                    if (f.delete()) removed++;
                }
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }
}
