package com.dshkit.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端配置：读 `dsh.d/config.json`（用户可见、可同步），缺项用默认值。
 *
 * 密码只用于**首次登录换令牌**；换到 refresh_token 后会被清出内存，不会写回任何文件。
 * 真机上建议登录一次后从 config.json 删掉 password，只留 refresh_token 在私有目录里。
 */
public final class Config {

    public static final String DEFAULT_PATH = "/sdcard/Documents/rime/scripts/dsh.d/config.json";

    public final String server;
    public final String username;
    public final String password;
    public final String deviceName;
    public final String deviceId;
    public final boolean autoConnect;
    public final boolean silentDefault;
    public final int defaultLevel;
    public final boolean clipEnabled;
    public final int keepAlive;
    public final List<String> ignoreApps;
    public final String source;

    private Config(
            String server,
            String username,
            String password,
            String deviceName,
            String deviceId,
            boolean autoConnect,
            boolean silentDefault,
            int defaultLevel,
            boolean clipEnabled,
            int keepAlive,
            List<String> ignoreApps,
            String source) {
        this.server = trimSlash(server);
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.deviceName = deviceName == null || deviceName.trim().isEmpty() ? "手机" : deviceName.trim();
        this.deviceId = deviceId == null ? "" : deviceId.trim();
        this.autoConnect = autoConnect;
        this.silentDefault = silentDefault;
        this.defaultLevel = Levels.clamp(defaultLevel);
        this.clipEnabled = clipEnabled;
        this.keepAlive = Math.max(15, Math.min(600, keepAlive));
        this.ignoreApps = ignoreApps;
        this.source = source;
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String out = s.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    public boolean hasServer() {
        return server.startsWith("http://") || server.startsWith("https://");
    }

    public boolean hasCredentials() {
        return !username.isEmpty() && !password.isEmpty();
    }

    /** 首次能自动登录的条件；只有 refresh_token 的情况由 Session 自己判断。 */
    public boolean autoLogin() {
        return hasServer() && hasCredentials();
    }

    public static Config defaults() {
        return new Config("", "", "", "手机", "", false, false, Levels.DEFAULT, true, 60,
                new ArrayList<String>(), "默认");
    }

    /** 读取失败、字段缺失都不抛异常；说明写在 {@link #source} 里供面板显示。 */
    public static Config load(File file, Log log) {
        if (file == null || !file.exists()) {
            if (log != null) log.w("config", "没有配置文件：" + (file == null ? "-" : file.getPath()));
            return defaults();
        }
        try {
            InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            StringBuilder sb = new StringBuilder();
            try {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            } finally {
                reader.close();
            }
            Map<String, Object> raw = Json.asObject(Json.parse(sb.toString()));
            Map<String, Object> mqtt = Json.asObject(raw.get("mqtt"));
            List<String> ignore = new ArrayList<String>();
            for (Object item : Json.asArray(raw.get("ignore_apps"))) {
                if (item instanceof String && !((String) item).trim().isEmpty()) {
                    ignore.add(((String) item).trim());
                }
            }
            // 允许把 mqtt 段写进 config：只用于直连调试（正常走 /devices/register 下发）
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            merged.putAll(raw);
            merged.putAll(mqtt);
            return new Config(
                    Json.str(merged, "server", ""),
                    Json.str(merged, "username", ""),
                    Json.str(merged, "password", ""),
                    Json.str(merged, "device_name", "手机"),
                    Json.str(merged, "device_id", ""),
                    Json.bool(merged, "auto_connect", true),
                    Json.bool(merged, "silent", false),
                    Levels.normalize(merged.get("default_level")),
                    Json.bool(merged, "clip_enabled", true),
                    Json.integer(merged, "keep_alive", 60),
                    ignore,
                    file.getPath());
        } catch (Throwable t) {
            if (log != null) log.e("config", t);
            return defaults();
        }
    }

    /** 面板上显示用：绝不带出密码。 */
    public Map<String, Object> publicView() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("server", server);
        out.put("username", username);
        out.put("device_name", deviceName);
        out.put("auto_connect", autoConnect);
        out.put("silent", silentDefault);
        out.put("default_level", Integer.valueOf(defaultLevel));
        out.put("clip_enabled", Boolean.valueOf(clipEnabled));
        out.put("keep_alive", keepAlive);
        out.put("ignore_apps", ignoreApps);
        out.put("source", source);
        return out;
    }

    /** 清掉内存里的密码（登录成功后调用）。 */
    public Config withoutPassword() {
        return new Config(server, username, "", deviceName, deviceId, autoConnect,
                silentDefault, defaultLevel, clipEnabled, keepAlive, ignoreApps, source);
    }
}
