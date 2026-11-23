package com.dshkit.core;

import java.security.MessageDigest;

/**
 * 剪贴板指纹：同一段文本在主设备/对端之间用它判重和防回流。
 *
 * 用 SHA-256 前 16 个 hex（64 位），碰撞概率对我们的场景足够低，日志里也好认。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] raw = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int v = raw[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 超过上限就不传（设计上限 32 KiB，按 UTF-8 字节算）。 */
    public static boolean fits(String text, int maxBytes) {
        if (text == null) return true;
        try {
            return text.getBytes("UTF-8").length <= maxBytes;
        } catch (Throwable t) {
            return text.length() <= maxBytes;
        }
    }
}
