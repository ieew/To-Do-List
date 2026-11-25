package com.dshkit.jvmtest;

import com.dshkit.core.Config;
import com.dshkit.core.Fingerprint;
import com.dshkit.core.Json;
import com.dshkit.core.Levels;
import com.dshkit.core.Protocol;
import com.dshkit.core.Queue;
import com.dshkit.core.Log;
import com.dshkit.core.Store;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 纯 JVM 单测：Json / Store / Protocol / Config。
 *
 * 不需要 Android，也不需要联网；`tools/test.sh` 编译并运行。
 */
public final class CoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testJsonRoundTrip();
        testJsonEscapes();
        testJsonErrors();
        testStorePersist();
        testQueueAppendAndReplay();
        testQueueSurvivesHalfLine();
        testQueueDoneIsIdempotent();
        testQueueWriteVolume();
        testProtocolNudge();
        testProtocolClip();
        testProtocolEnvelope();
        testProtocolTopic();
        testConfigParse();
        testProtocolRejectsGarbage();
        testLevels();
        testFingerprint();

        System.out.println("\n通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ Json

    private static void testJsonRoundTrip() throws Exception {
        String raw = "{\"a\":1,\"b\":[true,false,null,\"x\"],\"c\":{\"d\":2.5}}";
        Map<String, Object> o = Json.asObject(Json.parse(raw));
        check("json.a", Json.lng(o, "a", 0) == 1);
        List<Object> b = Json.asArray(o.get("b"));
        check("json.b.len", b.size() == 4);
        check("json.b[0]", Boolean.TRUE.equals(b.get(0)));
        check("json.b[2]", b.get(2) == null);
        check("json.c.d", Json.asObject(o.get("c")).get("d").equals(2.5));
        String again = Json.write(o);
        check("json.rewrite", Json.lng(Json.asObject(Json.parse(again)), "a", 0) == 1);
    }

    private static void testJsonEscapes() throws Exception {
        String raw = "{\"s\":\"a\\\"b\\\\c\\n\\t\\u4e2d\"}";
        Map<String, Object> o = Json.asObject(Json.parse(raw));
        String s = Json.str(o, "s", "");
        check("json.escape", s.equals("a\"b\\c\n\t中"));
        String out = Json.write(Json.obj("s", s));
        check("json.escape-round", Json.str(Json.asObject(Json.parse(out)), "s", "").equals(s));
    }

    private static void testJsonErrors() {
        String[] bad = {"", "{", "[1,", "{\"a\"}", "tru", "{a:1}"};
        for (String text : bad) {
            boolean threw = false;
            try {
                Json.parse(text);
            } catch (Json.ParseException e) {
                threw = true;
            }
            check("json.bad[" + text + "]", threw);
        }
    }

    // ------------------------------------------------------------ Store

    private static void testStorePersist() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-store-" + System.nanoTime());
        File file = new File(dir, "prefs.json");
        Store a = new Store(file);
        a.put("cursor", 42L);
        a.put("device_id", "dsh-abc");
        check("store.save", a.save());
        Store b = new Store(file);
        check("store.cursor", b.getLong("cursor", 0) == 42L);
        check("store.device", "dsh-abc".equals(b.getString("device_id", "")));
        b.put("cursor", 43L);
        b.saveIfDirty();
        Store c = new Store(file);
        check("store.update", c.getLong("cursor", 0) == 43L);
        // 坏文件不崩
        java.io.FileWriter w = new java.io.FileWriter(file);
        w.write("{not json");
        w.close();
        Store d = new Store(file);
        check("store.broken", d.getLong("cursor", 7) == 7);
        file.delete();
    }

    // ------------------------------------------------------------ Protocol

    private static void testProtocolNudge() {
        Protocol.Nudge n = Protocol.parseNudge("{\"t\":\"nudge\",\"seq\":1024,\"n\":3}");
        check("nudge", n != null && n.seq == 1024 && n.pending == 3);
        check("nudge.missing-t", Protocol.parseNudge("{\"seq\":1}") == null);
        check("nudge.garbage", Protocol.parseNudge("<html>") == null);
    }

    private static void testProtocolClip() {
        Protocol.Clip c = Protocol.parseClip("{\"t\":\"clip\",\"id\":\"msg-1\",\"p\":\"hello\"}");
        check("clip", c != null && "msg-1".equals(c.id) && "\"hello\"".equals(c.payloadJson));
        Protocol.Clip obj = Protocol.parseClip("{\"t\":\"clip\",\"id\":\"m\",\"p\":{\"k\":1}}");
        check("clip.obj", obj != null && obj.payloadJson.contains("\"k\":1"));
    }

    private static void testProtocolEnvelope() throws Exception {
        String env = Protocol.envelope("presence", "id-1", 123L, true, Json.obj("online", 1));
        Map<String, Object> o = Json.asObject(Json.parse(env));
        check("env.v", Json.lng(o, "v", 0) == 1);
        check("env.t", "presence".equals(Json.str(o, "t", "")));
        check("env.ack", Json.bool(o, "ack", false));
        check("env.p", Json.lng(Json.asObject(o.get("p")), "online", 0) == 1);
    }

    private static void testProtocolTopic() {
        check("topic.in", Protocol.topicIn("u-1", "dsh-1").equals("dsh/v1/u-1/dev/dsh-1/in"));
        check("topic.uid", "u-1".equals(Protocol.userIdOfTopic("dsh/v1/u-1/dev/dsh-1/in")));
        check("topic.bad", Protocol.userIdOfTopic("dsh/v1/u-1/in") == null);
    }

    private static void testConfigParse() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-cfg-" + System.nanoTime());
        dir.mkdirs();
        File file = new File(dir, "config.json");
        java.io.FileWriter w = new java.io.FileWriter(file);
        w.write("{\"server\":\"http://127.0.0.1:8099/\",\"username\":\"alice\","
                + "\"password\":\"pw\",\"device_name\":\"测试机\",\"auto_connect\":true,"
                + "\"ignore_apps\":[\"com.bank\"]}");
        w.close();
        Config cfg = Config.load(file, null);
        check("cfg.server", "http://127.0.0.1:8099".equals(cfg.server));
        check("cfg.user", "alice".equals(cfg.username));
        check("cfg.auto", cfg.autoConnect);
        check("cfg.ignore", cfg.ignoreApps.size() == 1);
        check("cfg.public", !Json.write(cfg.publicView()).contains("pw"));
        Config missing = Config.load(new File(dir, "nope.json"), null);
        check("cfg.missing", !missing.hasServer());
        file.delete();
        dir.delete();
    }

    private static void testProtocolRejectsGarbage() {
        check("garbage.nudge", Protocol.parseNudge("{}") == null);
        check("garbage.clip", Protocol.parseClip("[]") == null);
    }

    // ------------------------------------------------------------ 通知等级

    private static void testLevels() {
        check("level.null", Levels.normalize(null) == Levels.DEFAULT);
        check("level.str", Levels.normalize("7") == 7);
        check("level.clamp-high", Levels.normalize(99) == 7);
        check("level.clamp-low", Levels.normalize(-3) == 0);
        check("level.garbage", Levels.normalize("abc") == Levels.DEFAULT);

        check("level.quiet", Levels.bucket(0) == Levels.Bucket.QUIET);
        check("level.center1", Levels.bucket(1) == Levels.Bucket.CENTER);
        check("level.center2", Levels.bucket(2) == Levels.Bucket.CENTER);
        check("level.heads3", Levels.bucket(3) == Levels.Bucket.HEADS_UP);
        check("level.heads5", Levels.bucket(5) == Levels.Bucket.HEADS_UP);
        check("level.vibrate6", Levels.bucket(6) == Levels.Bucket.VIBRATE);
        check("level.alert7", Levels.bucket(7) == Levels.Bucket.ALERT);

        check("level.sound-only-7", !Levels.sound(6) && Levels.sound(7));
        check("level.vibrate-6-7",
                !Levels.vibrate(5) && Levels.vibrate(6) && Levels.vibrate(7));
        check("level.heads-3-to-7",
                !Levels.headsUp(2) && Levels.headsUp(3) && Levels.headsUp(5)
                        && Levels.headsUp(6) && Levels.headsUp(7));
        check("level.visible-all", Levels.visible(0) && Levels.visible(1) && Levels.visible(7));
        check("level.unimportant", Levels.unimportant(0) && !Levels.unimportant(1));
        check("level.l0-quiet",
                !Levels.headsUp(0) && !Levels.sound(0) && !Levels.vibrate(0));

        Map<String, Object> withLevel = Json.obj("level", 5);
        check("level.payload", Levels.fromPayload(withLevel, 3) == 5);
        check("level.payload-default", Levels.fromPayload(Json.obj(), 2) == 2);
    }

    // ------------------------------------------------------------ 剪贴板

    private static void testFingerprint() {
        check("fp.same", Fingerprint.of("你好").equals(Fingerprint.of("你好")));
        check("fp.diff", !Fingerprint.of("你好").equals(Fingerprint.of("你好!")));
        check("fp.empty", Fingerprint.of("").isEmpty());
        check("fp.len", Fingerprint.of("abc").length() == 16);
        check("fp.fit-ok", Fingerprint.fits("x", 1024));
        char[] big = new char[40000];
        java.util.Arrays.fill(big, 'a');
        check("fp.fit-too-big", !Fingerprint.fits(new String(big), 32 * 1024));

        check("clip.text-string", "你好".equals(Protocol.clipText("\"你好\"")));
        check("clip.text-object", "你好".equals(Protocol.clipText("{\"text\":\"你好\"}")));
        check("clip.text-p", "hi".equals(Protocol.clipText("{\"p\":\"hi\",\"fp\":\"abc\"}")));
        Protocol.Clip c = Protocol.parseClip("{\"t\":\"clip\",\"id\":\"m\",\"p\":\"x\",\"fp\":\"fp1\"}");
        check("clip.fp", c != null && "fp1".equals(c.fp));
    }

    // ------------------------------------------------------------ 断言


    // ------------------------------------------------------------ Queue（§7.3）

    private static void testQueueAppendAndReplay() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-queue-" + System.nanoTime());
        Log log = new Log(dir);
        Queue q = new Queue(dir, log);
        check("q.append", q.append(11, "m-11", "notify", "{\"title\":\"a\"}"));
        check("q.append2", q.append(12, "m-12", "notify", "{\"title\":\"b\"}"));
        check("q.pending-two", q.pending().size() == 2);
        check("q.contains", q.contains("m-11"));

        q.markDone("m-11", true);
        check("q.pending-one", q.pending().size() == 1);
        check("q.pending-seq", q.pending().get(0).seq == 12L);

        // 重开：模拟进程被杀后冷启动，未完成的那条必须还在
        Queue again = new Queue(dir, log);
        check("q.replay-pending", again.pending().size() == 1);
        check("q.replay-seq", again.pending().get(0).seq == 12L);
        check("q.replay-payload", again.pending().get(0).payload.indexOf("b") > 0);
        check("q.recent-one", again.recent(10).size() == 1);
        check("q.recent-shown", again.recent(10).get(0).shown);
    }

    private static void testQueueSurvivesHalfLine() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-queue-half-" + System.nanoTime());
        Log log = new Log(dir);
        Queue q = new Queue(dir, log);
        q.append(21, "m-21", "notify", "{}");
        // 掉电写到一半：手动追加一段不完整 JSON
        File[] files = new File(dir, "queue").listFiles();
        java.io.FileWriter fw = new java.io.FileWriter(files[0], true);
        fw.write("{\"op\":\"msg\",\"seq\":22,\"id\":\"m-2");
        fw.close();
        Queue again = new Queue(dir, log);
        check("q.half-line-keeps-good", again.pending().size() == 1);
        check("q.half-line-ok-seq", again.pending().get(0).seq == 21L);
    }

    private static void testQueueDoneIsIdempotent() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-queue-done-" + System.nanoTime());
        Log log = new Log(dir);
        Queue q = new Queue(dir, log);
        q.append(31, "m-31", "notify", "{}");
        q.markDone("m-31", true);
        q.markDone("m-31", true); // 重复调用不应写第二行
        File f = new File(dir, "queue").listFiles()[0];
        int lines = 0;
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
        while (r.readLine() != null) lines++;
        r.close();
        check("q.done-idempotent", lines == 2);
        check("q.done-no-pending", q.pending().isEmpty());
    }


    /**
     * §7.3 验收：连收 20 条通知，写盘量应远低于「每轮全量重写 prefs.json」。
     * 旧实现每条消息写两次整份文件（notify_seen + notify_inbox），且文件随条数线性变长；
     * 队列每条只追加一行，写入量与已积累的条数无关。
     */
    private static void testQueueWriteVolume() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-qvol-" + System.nanoTime());
        Log log = new Log(dir);
        Queue q = new Queue(dir, log);
        long queueBytes = 0;
        for (int i = 0; i < 20; i++) {
            long before = dirSize(new File(dir, "queue"));
            q.append(100 + i, "vol-" + i, "notify",
                    "{\"title\":\"提醒 " + i + "\",\"body\":\"该喝水了，顺便看看今天的三个待办\"}");
            queueBytes += dirSize(new File(dir, "queue")) - before;
        }
        // 旧方案：seen + inbox 各写一次整份 JSON
        File oldDir = new File(System.getProperty("java.io.tmpdir"), "dsh-oldvol-" + System.nanoTime());
        Store st = new Store(new File(oldDir, "prefs.json"));
        List<Object> seen = new java.util.ArrayList<Object>();
        List<Object> inbox = new java.util.ArrayList<Object>();
        long storeBytes = 0;
        File pj = new File(oldDir, "prefs.json");
        for (int i = 0; i < 20; i++) {
            seen.add("vol-" + i);
            inbox.add(Json.obj("id", "vol-" + i, "level", Integer.valueOf(2),
                    "title", "提醒 " + i, "body", "该喝水了，顺便看看今天的三个待办",
                    "shown", Boolean.TRUE, "at", Long.valueOf(System.currentTimeMillis())));
            st.put("notify_seen", seen);
            st.put("notify_inbox", inbox);
            st.saveIfDirty();
            // 旧实现每次都是「整份文件覆盖写」，写入量 = 当前完整 JSON 的长度
            storeBytes += Json.write(st.snapshot()).length();
        }
        System.out.println("  [指标] 20 条通知写盘：队列 " + queueBytes + " B，旧方案 " + storeBytes + " B");
        check("q.volume-less", queueBytes < storeBytes);
        check("q.volume-linear", queueBytes < 20L * 400L);
    }

    private static long dirSize(File d) {
        File[] fs = d.listFiles();
        if (fs == null) return 0L;
        long n = 0L;
        for (File f : fs) n += f.length();
        return n;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
