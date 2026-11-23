package com.dshkit.jvmtest;

import com.dshkit.core.Config;
import com.dshkit.core.Fingerprint;
import com.dshkit.core.HttpApi;
import com.dshkit.core.Json;
import com.dshkit.core.Log;
import com.dshkit.core.MqttTransport;
import com.dshkit.core.Protocol;
import com.dshkit.core.Scheduler;
import com.dshkit.core.Session;
import com.dshkit.core.Store;

import java.io.File;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 端到端：真服务端 + 真 broker，跑完整「登录 → 注册设备 → 连 MQTT → nudge → pull → 应用 → ack」，
 * 再验证重连与剪贴板双向（两台设备互传）。不碰 Android，所以能在这台手机的 Ubuntu 里跑。
 *
 * 用法：`tools/e2e.sh`（自己起服务端），或手动：
 *   java -cp build/jvm:tools/lib/paho.jar com.dshkit.jvmtest.E2E http://127.0.0.1:8099 alice password123
 */
public final class E2E {

    private static int failed;

    static final class CountingApplier implements Session.Applier {
        final List<String> notifyIds = Collections.synchronizedList(new ArrayList<String>());
        final List<String> payloads = Collections.synchronizedList(new ArrayList<String>());
        final List<String> clips = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public boolean apply(String kind, String msgId, String payloadJson, boolean live) {
            if ("notify".equals(kind)) {
                notifyIds.add(msgId);
                payloads.add(payloadJson);
            } else if ("clip".equals(kind)) {
                clips.add(payloadJson);
            }
            return true;
        }
    }

    static final class Client {
        final String tag;
        final Store store;
        final Scheduler sched;
        final MqttTransport transport;
        final Session session;
        final CountingApplier applier;

        Client(String tag, File dir, Config cfg, Log log, HttpApi api, CountingApplier applier) {
            this.tag = tag;
            this.applier = applier;
            this.store = new Store(new File(dir, "prefs-" + tag + ".json"));
            this.sched = new Scheduler("dsh-e2e-" + tag, log);
            this.transport = new MqttTransport(log);
            this.session = new Session(cfg, api, transport, store, sched, log, applier);
            session.start();
        }

        void stop() {
            try {
                session.stop();
            } catch (Throwable ignored) {
            }
            sched.shutdown();
            transport.close();
        }
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8099";
        String user = args.length > 1 ? args[1] : "dsh-e2e";
        String pass = args.length > 2 ? args[2] : "password123";

        File dir = new File(System.getProperty("java.io.tmpdir"), "dsh-e2e-" + System.nanoTime());
        dir.mkdirs();
        Log log = new Log(dir);
        log.i("e2e", "开始 base=" + base + " user=" + user);

        ensureUser(base, user, pass);
        Config cfg = writeConfig(dir, base, user, pass, "e2e-A");

        Client a = new Client("a", dir, cfg, log, new HttpApi(log), new CountingApplier());
        Client b = null;
        try {
            waitOnline(a.session, 20_000);

            // ---- 通知链路
            a.session.selfTestPush(7);
            waitCount(a.applier.notifyIds, 1, 15_000, "第一条推送");
            check("收到第一条通知", a.applier.notifyIds.size() == 1);
            check("通知正文是 JSON 对象", a.applier.payloads.get(0).contains("dsh-kit 自检"));
            check("L7 等级随消息到达", a.applier.payloads.get(0).contains("\"level\":7"));
            check("L7 默认带声音（客户端映射）", com.dshkit.core.Levels.sound(7));

            a.session.selfTestPush(0);
            waitCount(a.applier.notifyIds, 2, 15_000, "第二条推送（L0）");
            check("收到第二条通知", a.applier.notifyIds.size() == 2);
            check("L0 也照常送达", a.applier.payloads.get(1).contains("\"level\":0"));
            check("L0 静默可见（映射）", com.dshkit.core.Levels.visible(0)
                    && com.dshkit.core.Levels.unimportant(0)
                    && !com.dshkit.core.Levels.headsUp(0));

            Map<String, Object> st = a.session.status();
            check("游标已推进", Json.lng(st, "cursor", 0) > 0);
            check("已确认条数 >= 1", Json.lng(st, "acked", 0) >= 1);
            check("设备已注册", !Json.str(st, "device_id", "").isEmpty());

            // ---- 重连
            a.session.kick();
            waitOnline(a.session, 20_000);
            a.session.selfTestPush(6);
            waitCount(a.applier.notifyIds, 3, 15_000, "重连后的推送");
            check("重连后仍能收到", a.applier.notifyIds.size() == 3);
            check("L6 震动不出声（映射）",
                    com.dshkit.core.Levels.vibrate(6) && !com.dshkit.core.Levels.sound(6));

            // ---- 剪贴板：第二台设备
            Config cfgB = writeConfig(dir, base, user, pass, "e2e-B");
            b = new Client("b", dir, cfgB, log, new HttpApi(log), new CountingApplier());
            waitOnline(b.session, 20_000);

            String clipText = "端到端剪贴板 " + System.nanoTime();
            a.session.sendClip(clipText, Fingerprint.of(clipText));
            waitCount(b.applier.clips, 1, 15_000, "剪贴板到达 B");
            check("B 收到剪贴板", b.applier.clips.size() == 1);
            check("剪贴板正文正确", clipText.equals(Protocol.clipText(b.applier.clips.get(0))));
            check("A 不回声收到自己的剪贴板", a.applier.clips.isEmpty());
        } finally {
            if (b != null) b.stop();
            a.stop();
        }

        System.out.println("\n日志目录：" + dir.getAbsolutePath());
        if (failed > 0) {
            System.out.println("E2E 失败 " + failed + " 项");
            System.exit(1);
        }
        System.out.println("E2E 全绿");
    }

    private static Config writeConfig(File dir, String base, String user, String pass, String name)
            throws Exception {
        File cfgFile = new File(dir, "config-" + name + ".json");
        FileWriter w = new FileWriter(cfgFile);
        w.write("{\"server\":\"" + base + "\",\"username\":\"" + user
                + "\",\"password\":\"" + pass + "\",\"device_name\":\"" + name + "\","
                + "\"auto_connect\":true,\"clip_enabled\":true}");
        w.close();
        return Config.load(cfgFile, null);
    }

    private static void waitOnline(Session session, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if ("online".equals(Json.str(session.status(), "state", ""))) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("等不到上线：" + Json.write(session.status()));
    }

    private static void waitCount(List<String> list, int want, long timeoutMs, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (list.size() >= want) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException(what + " 超时（收到 " + list.size() + " 条）");
    }

    private static void ensureUser(String base, String user, String pass) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/v1/auth/register")
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        String body = "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
        java.io.OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int status = conn.getResponseCode();
        conn.disconnect();
        if (status != 201 && status != 409) {
            throw new IllegalStateException("建账号失败 HTTP " + status);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
