package com.dshkit.core;

/**
 * 通知等级：**只决定提醒方式，不代表通知可以丢**。
 *
 * | 等级 | 提醒方式 |
 * |---|---|
 * | 0 | 静默进通知中心（不重要），不悬浮、不出声、不震动 |
 * | 1–2 | 通知中心（不悬浮、不出声、不震动） |
 * | 3–5 | 悬浮通知（heads-up，不震动不出声） |
 * | 6 | 悬浮通知 + 震动 |
 * | 7 | 悬浮通知 + 震动 + 响声 |
 *
 * 等级与「送达」完全正交：任何等级都会走 pull → 应用 → ack，等级不参与重试/丢弃判断。
 */
public final class Levels {

    public static final int MIN = 0;
    public static final int MAX = 7;
    public static final int DEFAULT = 3;

    public enum Bucket {
        /** 0 级：静默进通知中心（不重要），不打扰但仍可见。 */
        QUIET,
        /** 1–2 级：只进通知中心。 */
        CENTER,
        /** 3–5 级：悬浮通知。 */
        HEADS_UP,
        /** 6 级：悬浮 + 震动。 */
        VIBRATE,
        /** 7 级：悬浮 + 震动 + 响声。 */
        ALERT
    }

    private Levels() {
    }

    /** 把任意输入收敛到 0–7；缺省/非法一律回到默认等级。 */
    public static int normalize(Object raw) {
        if (raw instanceof Number) {
            return clamp(((Number) raw).intValue());
        }
        if (raw instanceof String) {
            try {
                return clamp(Integer.parseInt(((String) raw).trim()));
            } catch (NumberFormatException ignored) {
                return DEFAULT;
            }
        }
        return DEFAULT;
    }

    public static int clamp(int level) {
        if (level < MIN) return MIN;
        if (level > MAX) return MAX;
        return level;
    }

    public static Bucket bucket(int level) {
        int l = clamp(level);
        if (l == 0) return Bucket.QUIET;
        if (l <= 2) return Bucket.CENTER;
        if (l <= 5) return Bucket.HEADS_UP;
        if (l == 6) return Bucket.VIBRATE;
        return Bucket.ALERT;
    }

    public static boolean sound(int level) {
        return bucket(level) == Bucket.ALERT;
    }

    public static boolean vibrate(int level) {
        Bucket b = bucket(level);
        return b == Bucket.VIBRATE || b == Bucket.ALERT;
    }

    public static boolean headsUp(int level) {
        Bucket b = bucket(level);
        return b == Bucket.HEADS_UP || b == Bucket.VIBRATE || b == Bucket.ALERT;
    }

    /** 是否进通知中心：0–7 全部可见；0 级只是「不重要 + 不打扰」。 */
    public static boolean visible(int level) {
        return true;
    }

    /** 0 级：划到「不重要/静默」一栏，不出声不悬浮。 */
    public static boolean unimportant(int level) {
        return bucket(level) == Bucket.QUIET;
    }

    /** 与 payload.level / config.default_level 的解析一致：缺省用默认等级。 */
    public static int fromPayload(java.util.Map<String, Object> payload, int fallback) {
        Object raw = payload == null ? null : payload.get("level");
        if (raw == null) return clamp(fallback);
        return normalize(raw);
    }

    public static String describe(int level) {
        switch (bucket(level)) {
            case QUIET:
                return "L0 静默（不重要）";
            case CENTER:
                return "L" + clamp(level) + " 通知中心";
            case HEADS_UP:
                return "L" + clamp(level) + " 悬浮";
            case VIBRATE:
                return "L6 悬浮+震动";
            default:
                return "L7 悬浮+震动+响铃";
        }
    }
}
