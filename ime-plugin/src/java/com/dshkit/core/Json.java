package com.dshkit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON：解析 + 序列化，**零 Android 依赖**（可在这台手机的 Ubuntu 里直接单测）。
 *
 * 值模型：String / Long / Double / Boolean / null / Map<String,Object> / List<Object>。
 * 不追求覆盖全部 JSON 边缘情况，够我们的信封和服务端协议用即可：
 * 对象、数组、字符串（含 \\uXXXX）、数字、true/false/null、注释不支持。
 */
public final class Json {

    public static final class ParseException extends Exception {
        ParseException(String msg) {
            super(msg);
        }
    }

    private Json() {
    }

    // ------------------------------------------------------------ 解析

    public static Object parse(String text) throws ParseException {
        if (text == null) throw new ParseException("空文本");
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.eof()) throw new ParseException("多余字符 @" + p.pos);
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() throws ParseException {
            if (eof()) throw new ParseException("意外结束");
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        void expect(String lit) throws ParseException {
            if (!s.startsWith(lit, pos)) throw new ParseException("期望 " + lit + " @" + pos);
            pos += lit.length();
        }

        Map<String, Object> object() throws ParseException {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWs();
            if (!eof() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (eof() || s.charAt(pos) != '"') throw new ParseException("键必须是字符串 @" + pos);
                String key = string();
                skipWs();
                if (eof() || s.charAt(pos) != ':') throw new ParseException("缺少冒号 @" + pos);
                pos++;
                skipWs();
                map.put(key, value());
                skipWs();
                if (eof()) throw new ParseException("对象未闭合");
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new ParseException("对象内意外字符 " + c + " @" + pos);
            }
        }

        List<Object> array() throws ParseException {
            List<Object> list = new ArrayList<Object>();
            pos++; // [
            skipWs();
            if (!eof() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                if (eof()) throw new ParseException("数组未闭合");
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new ParseException("数组内意外字符 " + c + " @" + pos);
            }
        }

        String string() throws ParseException {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new ParseException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) throw new ParseException("转义未完成");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new ParseException("\\u 不完整");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new ParseException("未知转义 \\" + e);
                }
            }
        }

        Object number() throws ParseException {
            int start = pos;
            if (!eof() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean dot = false;
            boolean exp = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' && !dot && !exp) {
                    dot = true;
                    pos++;
                } else if ((c == 'e' || c == 'E') && !exp && pos > start) {
                    exp = true;
                    pos++;
                    if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, pos);
            if (raw.isEmpty()) throw new ParseException("非法字面量 @" + start);
            try {
                if (!dot && !exp) return Long.valueOf(raw);
                return Double.valueOf(raw);
            } catch (NumberFormatException ex) {
                throw new ParseException("非法数字 " + raw);
            }
        }
    }

    // ------------------------------------------------------------ 序列化

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        writeTo(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeTo(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int k = hex.length(); k < 4; k++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------ 取值助手

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) {
        if (v instanceof List) return (List<Object>) v;
        return new ArrayList<Object>();
    }

    public static Object get(Map<String, Object> o, String key) {
        return o == null ? null : o.get(key);
    }

    public static String str(Map<String, Object> o, String key, String def) {
        Object v = get(o, key);
        return v instanceof String ? (String) v : def;
    }

    public static long lng(Map<String, Object> o, String key, long def) {
        Object v = get(o, key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static int integer(Map<String, Object> o, String key, int def) {
        return (int) lng(o, key, def);
    }

    public static boolean bool(Map<String, Object> o, String key, boolean def) {
        Object v = get(o, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return def;
    }

    /** 构造对象：`obj("a", 1, "b", "x")`。 */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    public static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<Object>(items.length);
        for (Object item : items) out.add(item);
        return out;
    }
}
