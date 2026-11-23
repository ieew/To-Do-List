package com.dshkit.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单文件键值存储（JSON），给游标 / 设备号 / 令牌 / 去重表用。
 *
 * - 原子写：先写 `.tmp` 再 rename，掉电不会留半个文件；
 * - 所有方法串行，调用方可以从任意线程用；
 * - 解析失败就当作空表，不抛异常（坏文件不能让输入法起不来）。
 */
public final class Store {

    private final File file;
    private final Map<String, Object> data = new LinkedHashMap<String, Object>();
    private boolean dirty;

    public Store(File file) {
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || !file.exists()) return;
        try {
            InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
                Object parsed = Json.parse(sb.toString());
                data.putAll(Json.asObject(parsed));
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
            data.clear();
        }
    }

    public synchronized String getString(String key, String def) {
        Object v = data.get(key);
        return v instanceof String ? (String) v : def;
    }

    public synchronized long getLong(String key, long def) {
        Object v = data.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public synchronized boolean getBool(String key, boolean def) {
        Object v = data.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public synchronized Map<String, Object> getObject(String key) {
        return Json.asObject(data.get(key));
    }

    public synchronized void put(String key, Object value) {
        if (value == null) {
            if (data.remove(key) != null) dirty = true;
            return;
        }
        if (!value.equals(data.get(key))) {
            data.put(key, value);
            dirty = true;
        }
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** 有变化才落盘；返回是否真的写了。 */
    public synchronized boolean saveIfDirty() {
        if (!dirty) return false;
        return save();
    }

    public synchronized boolean save() {
        if (file == null) return false;
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                writer.write(Json.write(data));
                writer.flush();
            } finally {
                writer.close();
            }
            if (file.exists() && !file.delete()) {
                // 删不掉就退化成直接覆盖
                OutputStreamWriter direct =
                        new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
                try {
                    direct.write(Json.write(data));
                } finally {
                    direct.close();
                }
                dirty = false;
                return true;
            }
            dirty = !tmp.renameTo(file);
            return !dirty;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized String path() {
        return file == null ? "-" : file.getAbsolutePath();
    }

    public synchronized Map<String, Object> snapshot() {
        return new LinkedHashMap<String, Object>(data);
    }
}
