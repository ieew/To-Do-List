package com.dshkit.mod;

import android.content.Context;
import android.content.SharedPreferences;

import com.dshkit.KitLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 主题自启动钩子：往当前主题的 main.lua 里加/删 `onStartInput`。
 *
 * 为什么需要：进程被杀后常驻通知会被系统收走，要把 dsh-kit 拉回来只能靠输入法
 * 自己触发。主题脚本支持 `onStartInput` 回调，第一次弹键盘时执行，
 * 在那里 dofile dsh_boot.lua 就能把 Kit 拉起来。
 *
 * 给别人用时不该让人手工改主题，所以工具页放一个「自启动」开关：
 * 打开 = 写进当前主题，关闭 = 从当前主题删掉。状态只看主题文件里有没有标记块。
 *
 * 主题目录和 trime 配置里的 `theme` 键对齐；trime 有 MANAGE_EXTERNAL_STORAGE，
 * 可以直接读写 /sdcard。写之前先备份一份 main.lua.dsh-bak。
 */
public final class ThemeHook {

    public static final String THEMES_DIR = "/sdcard/Documents/rime/themes";
    /** trime 主配置：`theme` 键记录当前主题名。 */
    private static final String PREFS = "com.nirenr.trime_preferences";
    private static final String KEY_THEME = "theme";
    private static final String BEGIN = "-- dsh-kit auto-boot begin";
    private static final String END = "-- dsh-kit auto-boot end";
    private static final String BLOCK = BEGIN + "\n"
            + "-- 输入法进程复活时自动拉起 dsh-kit（dsh-kit 工具页「自启动」开关维护这个块）\n"
            + "function onStartInput()\n"
            + "    if dshkit ~= nil then return end\n"
            + "    pcall(dofile, \"/sdcard/Documents/rime/scripts/dsh_boot.lua\")\n"
            + "end\n"
            + END + "\n";

    private final Context ctx;
    private final KitLog log;

    public ThemeHook(Context ctx, KitLog log) {
        this.ctx = ctx;
        this.log = log;
    }

    /** 当前主题名（trime 配置里的 `theme`），读不到返回 null。 */
    public String currentTheme() {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String t = p.getString(KEY_THEME, null);
            return (t == null || t.trim().isEmpty()) ? null : t.trim();
        } catch (Throwable t) {
            log.e("theme/hook", t);
            return null;
        }
    }

    public File mainFile() {
        String theme = currentTheme();
        if (theme == null) return null;
        return new File(new File(THEMES_DIR, theme), "main.lua");
    }

    /** 主题 main.lua 里有没有我们写的标记块。 */
    public boolean isInstalled() {
        String text = readMain();
        return text != null && text.contains(END);
    }

    /** 把钩子写进当前主题（幂等：已有标记块就只替换，不重复追加）。 */
    public boolean install() {
        File main = mainFile();
        if (main == null) {
            log.w("theme/hook", "读不到当前主题（trime 配置没有 theme）");
            return false;
        }
        try {
            String text = readMain();
            if (text == null) text = "";
            if (text.contains(END)) {
                text = removeBlock(text);
            }
            text = text + (text.endsWith("\n") || text.isEmpty() ? "" : "\n") + "\n" + BLOCK;
            return writeMain(main, text);
        } catch (Throwable t) {
            log.e("theme/hook/install", t);
            return false;
        }
    }

    /** 从当前主题删掉钩子；没有就返回 true（幂等）。 */
    public boolean uninstall() {
        File main = mainFile();
        if (main == null) return false;
        try {
            String text = readMain();
            if (text == null || !text.contains(END)) return true;
            return writeMain(main, removeBlock(text));
        } catch (Throwable t) {
            log.e("theme/hook/uninstall", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 内部

    private String readMain() {
        File main = mainFile();
        if (main == null || !main.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(main);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            log.e("theme/hook/read", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean writeMain(File main, String text) {
        File dir = main.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            log.w("theme/hook", "主题目录不存在: " + (dir == null ? "?" : dir.getAbsolutePath()));
            return false;
        }
        File bak = new File(dir, "main.lua.dsh-bak");
        try {
            // 先备份原文件（每次覆盖上一次备份，保留最近一份）
            if (main.isFile()) {
                FileInputStream in = new FileInputStream(main);
                FileOutputStream out = new FileOutputStream(bak);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    in.close();
                    out.close();
                }
            }
            FileOutputStream out = new FileOutputStream(main);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            log.i("theme/hook", "已写入 " + main.getAbsolutePath() + "（" + text.length() + " 字）");
            return true;
        } catch (Throwable t) {
            log.e("theme/hook/write", t);
            return false;
        }
    }

    /** 删掉 begin..end 之间的块（含标记行本身），保留其余内容原样。 */
    private static String removeBlock(String text) {
        int b = text.indexOf(BEGIN);
        if (b < 0) return text;
        int e = text.indexOf(END, b);
        if (e < 0) return text;
        e += END.length();
        // 连同一行的换行一起删，别留空行
        if (e < text.length() && text.charAt(e) == '\r') e++;
        if (e < text.length() && text.charAt(e) == '\n') e++;
        // 追加时在块前留了一个分隔空行，这里收回它（只回收一个换行，保留文件原本结尾）
        if (b > 0 && text.charAt(b - 1) == '\n') {
            b--;
            if (b > 0 && text.charAt(b - 1) == '\r') b--;
        }
        return text.substring(0, b) + text.substring(e);
    }
}
