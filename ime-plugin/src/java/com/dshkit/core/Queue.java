package com.dshkit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L2 存储：append-only 消息队列（§7.3，P0）。
 *
 * 主通道的 at-least-once 必须由插件自己的存储承担，不能挂在 App 身上：
 * 收消息先一行 append + fsync，**落盘成功才 ack**；进程任意时刻被杀，重启后
 * 未完成的记录都还在，已完成的有了 done 就不会重复展示。
 *
 * 为什么是 JSONL 而不是 SQLite：插件是注入到输入法进程的 dex，多一个依赖就多
 * 一个加载失败点；而队列只需要「追加」和「顺序重放」两件事，JSONL 足够，
 * 且写放大从「每次全量重写整份文件」降到「每次一行」。
 */
public final class Queue {

    /** 队列里的一条消息。done 之后 seq 与 payload 仍保留，供留档查询。 */
    public static final class Entry {
        public final long seq;
        public final String msgId;
        public final String kind;
        public final String payload;
        public final long at;
        public volatile boolean done;
        public volatile boolean shown;

        Entry(long seq, String msgId, String kind, String payload, long at) {
            this.seq = seq;
            this.msgId = msgId == null ? "" : msgId;
            this.kind = kind == null ? "" : kind;
            this.payload = payload == null ? "" : payload;
            this.at = at;
        }
    }

    private static final long KEEP_MS = 24L * 60L * 60L * 1000L;

    private final File dir;
    private final Log log;
    private final SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private final Map<Long, Entry> bySeq = new LinkedHashMap<Long, Entry>();
    private final Set<String> doneIds = new HashSet<String>();
    private long loadedThrough;

    public Queue(File base, Log log) {
        this.dir = new File(base, "queue");
        this.log = log;
        try {
            dir.mkdirs();
        } catch (Throwable ignored) {
        }
        load();
        prune();
    }

    // ------------------------------------------------------------ 写入

    /**
     * 收到一条消息：追加一行并 fsync。返回 false 表示没落盘——调用方**不得 ack**。
     */
    public synchronized boolean append(long seq, String msgId, String kind, String payloadJson) {
        if (seq <= 0) return true; // 没有 seq 的本地消息（自测）不入队
        Map<String, Object> rec = Json.obj(
                "op", "msg",
                "seq", Long.valueOf(seq),
                "id", msgId == null ? "" : msgId,
                "kind", kind == null ? "" : kind,
                "payload", payloadJson == null ? "" : payloadJson,
                "at", Long.valueOf(System.currentTimeMillis()));
        if (!appendLine(rec)) return false;
        if (!bySeq.containsKey(Long.valueOf(seq))) {
            bySeq.put(Long.valueOf(seq), new Entry(seq, msgId, kind, payloadJson,
                    System.currentTimeMillis()));
        }
        return true;
    }

    /** 处理完成（展示过或明确跳过）：写 done 行。重复调用幂等。 */
    public synchronized void markDone(String msgId, boolean shown) {
        if (msgId == null || msgId.isEmpty()) return;
        if (!doneIds.add(msgId)) return; // 已经 done 过，不再写第二行
        for (Entry e : bySeq.values()) {
            if (msgId.equals(e.msgId)) {
                e.done = true;
                e.shown = shown;
            }
        }
        appendLine(Json.obj(
                "op", "done",
                "id", msgId,
                "seq", Long.valueOf(0L),
                "shown", Boolean.valueOf(shown),
                "at", Long.valueOf(System.currentTimeMillis())));
    }

    // ------------------------------------------------------------ 读取

    /** 未完成的记录，按 seq 升序。启动时重放的就是这批。 */
    public synchronized List<Entry> pending() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : bySeq.values()) {
            if (!e.done) out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
            }
        });
        return out;
    }

    /** 最近 n 条已完成记录（新的在后），供面板「最近通知」用。 */
    public synchronized List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : bySeq.values()) {
            if (e.done) out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.at < b.at ? -1 : (a.at > b.at ? 1 : 0);
            }
        });
        if (out.size() > limit) return new ArrayList<Entry>(out.subList(out.size() - limit, out.size()));
        return out;
    }

    /** 是否见过这个 msg_id（幂等判据：重复投递只处理一次）。 */
    public synchronized boolean contains(String msgId) {
        if (msgId == null || msgId.isEmpty()) return false;
        if (doneIds.contains(msgId)) return true;
        for (Entry e : bySeq.values()) {
            if (msgId.equals(e.msgId)) return true;
        }
        return false;
    }

    public synchronized int size() {
        return bySeq.size();
    }

    // ------------------------------------------------------------ 维护

    /** 删除 24 小时前的已完成文件；有未完成记录的文件一律留着。 */
    public synchronized void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".jsonl")) continue;
            long dayStart = dayStartOf(name.substring(0, name.length() - 6));
            if (dayStart <= 0 || dayStart > cutoff) continue;
            if (hasPendingIn(f)) continue;
            if (!f.delete()) log.w("queue", "清理旧队列文件失败 " + name);
        }
    }

    // ------------------------------------------------------------ 内部

    private boolean hasPendingIn(File f) {
        for (Entry e : bySeq.values()) {
            if (e.done) continue;
            if (fileFor(e.at).getName().equals(f.getName())) return true;
        }
        return false;
    }

    private long dayStartOf(String yyyymmdd) {
        try {
            Date d = day.parse(yyyymmdd);
            return d == null ? 0L : d.getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private File fileFor(long at) {
        return new File(dir, day.format(new Date(at)) + ".jsonl");
    }

    private boolean appendLine(Map<String, Object> rec) {
        FileOutputStream out = null;
        try {
            dir.mkdirs();
            out = new FileOutputStream(fileFor(System.currentTimeMillis()), true);
            Writer w = new OutputStreamWriter(out, "UTF-8");
            w.write(Json.write(rec));
            w.write("\n");
            w.flush();
            out.getFD().sync(); // 落盘才算数：这一步之前不 ack
            return true;
        } catch (Throwable t) {
            log.e("queue", "追加失败：" + t);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void load() {
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> ordered = new ArrayList<File>();
        for (File f : files) {
            if (f.getName().endsWith(".jsonl")) ordered.add(f);
        }
        Collections.sort(ordered, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File f : ordered) replayFile(f);
        loadedThrough = System.currentTimeMillis();
    }

    private void replayFile(File f) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                Map<String, Object> rec;
                try {
                    rec = Json.asObject(Json.parse(line));
                } catch (Throwable t) {
                    // 半行（掉电写到一半）只影响最后一条，跳过即可
                    continue;
                }
                String op = Json.str(rec, "op", "");
                String id = Json.str(rec, "id", "");
                if ("msg".equals(op)) {
                    long seq = Json.lng(rec, "seq", 0L);
                    if (seq <= 0) continue;
                    Entry e = new Entry(seq, id, Json.str(rec, "kind", ""),
                            Json.str(rec, "payload", ""), Json.lng(rec, "at", 0L));
                    if (doneIds.contains(id)) e.done = true;
                    bySeq.put(Long.valueOf(seq), e);
                } else if ("done".equals(op)) {
                    doneIds.add(id);
                    long seq = Json.lng(rec, "seq", 0L);
                    Entry e = seq > 0 ? bySeq.get(Long.valueOf(seq)) : null;
                    if (e == null) {
                        for (Entry cand : bySeq.values()) {
                            if (id.equals(cand.msgId)) {
                                e = cand;
                                break;
                            }
                        }
                    }
                    if (e != null) {
                        e.done = true;
                        e.shown = Json.bool(rec, "shown", true);
                    }
                }
            }
        } catch (Throwable t) {
            log.w("queue", "重放 " + f.getName() + " 失败：" + t.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
