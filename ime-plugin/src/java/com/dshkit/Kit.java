package com.dshkit;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.dshkit.core.Config;
import com.dshkit.core.EventBus;
import com.dshkit.core.HttpApi;
import com.dshkit.core.Json;
import com.dshkit.core.Levels;
import com.dshkit.core.Log;
import com.dshkit.core.MqttTransport;
import com.dshkit.core.Scheduler;
import com.dshkit.core.Session;
import com.dshkit.core.Store;
import com.dshkit.mod.ClipModule;
import com.dshkit.mod.ClipSource;
import com.dshkit.mod.NetWatcher;
import com.dshkit.mod.NotifyModule;
import com.dshkit.mod.NotifySink;
import com.dshkit.mod.TodoClient;
import com.dshkit.mod.TodoNotice;
import com.dshkit.mod.TodoOverlay;

import java.io.File;
import java.util.Map;

/**
 * L1 门面 + 组合根：把 L2 内核（Store/Scheduler/EventBus/Log）、
 * L3 能力（HttpApi / MqttTransport / Session）、L4 模块（Notify）接起来。
 *
 * 对外只有一个稳定 ABI：{@link #call(String, String)}。Lua 侧永远只碰 kit:call。
 */
public final class Kit {

    public static final String VERSION = "0.3.13";

    private static Kit INSTANCE;

    private final TrimeHost host;
    private final KitLog log;
    private final Store store;
    private volatile Config config;
    private final Scheduler sched;
    private final EventBus bus;
    private final HttpApi api;
    private final MqttTransport transport;
    private final Session session;
    private final NotifySink notifySink;
    private final NotifyModule notifyModule;
    private final ClipSource clipSource;
    private final ClipModule clipModule;
    private final NetWatcher netWatcher;
    private final TodoClient todoClient;
    private final TodoOverlay todoOverlay;
    private final TodoNotice todoNotice;
    private final SharedPreferences todoPrefs;
    private final long bootAt = System.currentTimeMillis();
    private Ui ui;

    private Kit(TrimeHost host, File base) {
        this.host = host;
        this.log = new KitLog(host.context());
        this.store = new Store(new File(base, "prefs.json"));
        this.config = Config.load(new File(Config.DEFAULT_PATH), log);

        this.sched = new Scheduler("dsh-kit", log);
        this.bus = new EventBus(sched.executor(), log);
        this.api = new HttpApi(log);
        this.transport = new MqttTransport(log);

        Context ctx = host.context();
        this.notifySink = new NotifySink(ctx, log);
        this.notifyModule = new NotifyModule(store, notifySink, log, config.defaultLevel);

        this.session = new Session(config, api, transport, store, sched, log,
                new Session.Applier() {
                    @Override
                    public boolean apply(String kind, String msgId, String payloadJson,
                                         boolean live) {
                        if ("clip".equals(kind)) {
                            return clipModule.apply(kind, msgId, payloadJson, live);
                        }
                        return notifyModule.apply(kind, msgId, payloadJson, live);
                    }
                });
        this.notifyModule.setSession(session);

        this.clipSource = new ClipSource(ctx, log, config.ignoreApps,
                new ClipSource.Listener() {
                    @Override
                    public void onLocalClip(String text, String fp) {
                        clipModule.sendLocal(text, fp);
                    }
                },
                new ClipSource.PackageProvider() {
                    @Override
                    public String currentPackage() {
                        return host.currentPackage();
                    }
                });
        this.clipModule = new ClipModule(ctx, log, clipSource);
        this.clipModule.setSession(session);
        this.netWatcher = new NetWatcher(ctx, log, session);
        this.todoClient = new TodoClient(ctx);
        this.todoOverlay = new TodoOverlay(ctx, todoClient, log);
        this.todoNotice = new TodoNotice(ctx, notifySink, log, todoOverlay);
        this.todoPrefs = ctx.getSharedPreferences("dsh_todo", Context.MODE_PRIVATE);
        this.session.setListener(new Session.Listener() {
            @Override
            public void onSessionChanged(Map<String, Object> status) {
                // 面板自己会定期刷；这里只落一条状态日志，避免刷屏。
                log.i("session", "状态 " + Json.write(status));
            }
        });
        this.ui = new Ui(this);
    }

    /** IME 脚本入口：通过 Class.forName(...,loader).boot() 调用，自己找 TrimeService。 */
    public static synchronized Kit boot() {
        return bootInternal(null);
    }

    /**
     * 工具 Activity 入口（输入法「工具」列表 → tools/<名>/main.lua）：
     * 拿不到 TrimeService 时用普通 Context 降级启动——通知/网络/剪贴板照常，面板换 Activity 页面。
     */
    public static synchronized Kit bootFromContext(Context ctx) {
        return bootInternal(ctx);
    }

    private static Kit bootInternal(Context fallback) {
        if (INSTANCE != null) return INSTANCE;
        TrimeHost h = TrimeHost.acquire();
        if (h == null) {
            if (fallback == null) {
                throw new IllegalStateException("取不到 TrimeService 实例（输入法未运行？）");
            }
            h = TrimeHost.local(fallback);
        }
        File base = new File(h.context().getFilesDir(), "dsh-kit");
        base.mkdirs();
        INSTANCE = new Kit(h, base);
        INSTANCE.log.i("boot", "v" + VERSION + " sdk=" + Build.VERSION.SDK_INT
                + " pkg=" + h.context().getPackageName()
                + " host=" + (h.hasService() ? "service" : "local"));
        INSTANCE.log.i("probe", h.probe());
        INSTANCE.log.i("config", "source=" + INSTANCE.config.source
                + " server=" + INSTANCE.config.server + " user=" + INSTANCE.config.username);
        INSTANCE.session.start();
        if (INSTANCE.config.clipEnabled) INSTANCE.clipSource.start();
        INSTANCE.netWatcher.start();
        INSTANCE.todoNotice.sync();
        return INSTANCE;
    }

    /** 工具入口用：把状态面板显示在 Activity 里（而不是顶掉键盘）。 */
    public void attachActivity(Activity activity) {
        ui.attachActivity(activity);
    }

    public TrimeHost host() {
        return host;
    }

    public KitLog log() {
        return log;
    }

    public Config config() {
        return config;
    }

    /**
     * 重载配置：重新读 `dsh.d/config.json`，换掉配置对象并重连。
     * 工具页的「重载配置」按钮走这里，改完地址不用强停输入法。
     */
    public void reloadConfig() {
        try {
            Config fresh = Config.load(new File(Config.DEFAULT_PATH), log);
            this.config = fresh;
            api.setServer(fresh.server);
            session.setConfig(fresh);
            if (fresh.clipEnabled) {
                if (!clipSource.isStarted()) clipSource.start();
            } else {
                clipSource.stop();
            }
            log.i("config", "重载：source=" + fresh.source
                    + " server=" + fresh.server + " user=" + fresh.username);
            if (!fresh.hasServer()) {
                host.toast("配置已重载，但 server 没填（dsh.d/config.json）");
                return;
            }
            host.toast("配置已重载：" + fresh.server + "，正在重连");
            session.kick();
        } catch (Throwable t) {
            log.e("config/reload", t);
            host.toast("重载失败：" + t.getClass().getSimpleName());
        }
    }

    public Session session() {
        return session;
    }

    public NotifyModule notifyModule() {
        return notifyModule;
    }

    public TodoClient todoClient() {
        return todoClient;
    }

    public TodoOverlay todoOverlay() {
        return todoOverlay;
    }

    public TodoNotice todoNotice() {
        return todoNotice;
    }

    public boolean todoEnabled() {
        return todoPrefs.getBoolean("enabled", false);
    }

    public void setTodoEnabled(boolean on) {
        todoPrefs.edit().putBoolean("enabled", on).apply();
        log.i("todo", "面板开关=" + on);
    }

    private Map<String, Object> todoStatus() {
        return Json.obj(
                "enabled", Boolean.valueOf(todoEnabled()),
                "overlay", Boolean.valueOf(todoOverlay.isVisible()),
                "canDraw", Boolean.valueOf(todoOverlay.canDraw()),
                "notice", Boolean.valueOf(todoNotice.isEnabled()),
                "provider", Boolean.valueOf(todoClient.available()));
    }

    public String version() {
        return VERSION;
    }

    /**
     * 把外部 dex 拷进私有目录，供下次加载用（外部目录做 opt 在部分 ROM 上会失败）。
     * 返回私有副本路径；失败返回 null。
     */
    public String prepareDex(String srcPath) {
        try {
            File dir = new File(host.context().getFilesDir(), "dsh-kit");
            dir.mkdirs();
            File src = new File(srcPath);
            if (!src.exists()) return null;
            File dst = new File(dir, "dsh-kit.dex");
            if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) {
                // Android 14+ 拒绝加载可写的 dex，老副本也要补一次只读
                dst.setReadOnly();
                return dst.getAbsolutePath();
            }
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
            // Android 14+：可写的 dex 不给 DexClassLoader 加载（SecurityException: Writable dex file）
            dst.setReadOnly();
            log.i("prepareDex", "copied " + dst.length() + "B -> " + dst.getAbsolutePath());
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            log.e("prepareDex", t);
            return null;
        }
    }

    // ---------------- 稳定 ABI ----------------

    /** 返回 null 表示"已处理，无需上屏"。 */
    public Object call(String action) {
        return call(action, "");
    }

    public Object call(String action, String args) {
        long t0 = System.currentTimeMillis();
        try {
            if (action == null) return null;
            if (action.equals("core.ping")) return "pong";
            if (action.equals("core.info")) return statusText();
            if (action.equals("core.config")) return Json.write(config.publicView());
            if (action.equals("core.log")) {
                log.i("lua", args);
                return null;
            }
            if (action.equals("core.shutdown")) {
                shutdown();
                return null;
            }
            if (action.equals("ui.toast")) {
                host.toast(args);
                return null;
            }
            if (action.equals("ui.panel")) {
                ui.panel();
                return null;
            }
            if (action.equals("ui.close")) {
                host.restoreKeyboard();
                return null;
            }
            if (action.equals("todo.status")) {
                return Json.write(todoStatus());
            }
            if (action.equals("todo.toggle") || action.equals("todo.panel")) {
                if (todoReady()) todoOverlay.toggle();
                return null;
            }
            if (action.equals("todo.show")) {
                if (todoReady()) todoOverlay.show();
                return null;
            }
            if (action.equals("todo.hide")) {
                todoOverlay.hide();
                return null;
            }
            if (action.equals("todo.enable")) {
                setTodoEnabled(true);
                host.toast("待办面板已启用");
                return null;
            }
            if (action.equals("todo.disable")) {
                setTodoEnabled(false);
                todoOverlay.hide();
                host.toast("待办面板已停用");
                return null;
            }
            if (action.equals("todo.notice")) {
                boolean on = !"off".equalsIgnoreCase(args == null ? "" : args.trim());
                boolean ok = todoNotice.setEnabled(on);
                if (on) host.toast(ok ? "常驻通知已开，点击可唤出面板" : "通知权限被关闭，开不了");
                else host.toast("常驻通知已关");
                return null;
            }
            if (action.equals("todo.add")) {
                try {
                    String id = todoClient.add(args == null ? "" : args.trim());
                    host.toast(id != null && !id.isEmpty() ? "已添加待办" : "添加失败");
                } catch (Throwable t) {
                    log.e("todo/add", t);
                    host.toast(todoErrorText(t));
                }
                return null;
            }
            if (action.equals("clip.peek")) {
                return peekClip();
            }
            if (action.equals("dsh.status")) {
                return Json.write(session.status());
            }
            if (action.equals("dsh.sync")) {
                session.syncNow();
                return null;
            }
            if (action.equals("dsh.reconnect") || action.equals("dsh.login")) {
                session.kick();
                return null;
            }
            if (action.equals("dsh.stop")) {
                session.stop();
                return null;
            }
            if (action.equals("notify.test")) {
                notifyModule.selfTest(levelArg(args, 7));
                return null;
            }
            if (action.equals("notify.selftest")) {
                session.selfTestPush(levelArg(args, 7));
                return null;
            }
            if (action.equals("clip.status")) {
                return Json.write(Json.obj(
                        "enabled", Boolean.valueOf(config.clipEnabled),
                        "listening", Boolean.valueOf(clipSource.isStarted()),
                        "last", clipModule.last()));
            }
            if (action.equals("clip.send")) {
                String text = args == null ? "" : args;
                clipModule.sendLocal(text, com.dshkit.core.Fingerprint.of(text));
                return null;
            }
            if (action.equals("clip.test")) {
                // 写进剪贴板，让监听器自己触发上行（比直接调 sendClip 多验一段）
                String text = "dsh-kit 剪贴板测试 " + System.currentTimeMillis();
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        host.context().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-kit", text));
                }
                return null;
            }
            log.w("call", "unknown action: " + action);
            return null;
        } catch (Throwable t) {
            log.e("call/" + action, t);
            return null;
        } finally {
            if (System.currentTimeMillis() - t0 > 120) {
                log.w("slow", action + " " + (System.currentTimeMillis() - t0) + "ms");
            }
        }
    }

    private boolean todoReady() {
        if (!todoEnabled()) {
            host.toast("待办面板没启用：去输入法工具页打开");
            return false;
        }
        if (!todoOverlay.canDraw()) {
            host.toast("没有悬浮窗权限：去系统设置允许输入法「显示在其他应用上层」");
            return false;
        }
        return true;
    }

    private static String todoErrorText(Throwable t) {
        if (t instanceof SecurityException) return "待办 App 拒绝调用";
        if (t instanceof IllegalArgumentException) return "没装待办 App 或版本太旧";
        return "待办不可用：" + t.getClass().getSimpleName();
    }

    /** `kit:call("notify.test", "7")`：参数是等级，缺省 7。 */
    private static int levelArg(String args, int fallback) {
        if (args == null || args.trim().isEmpty()) return fallback;
        return Levels.normalize(args.trim());
    }

    public void shutdown() {
        try {
            session.stop();
        } catch (Throwable ignored) {
        }
        try {
            transport.close();
        } catch (Throwable ignored) {
        }
        try {
            clipSource.stop();
        } catch (Throwable ignored) {
        }
        try {
            netWatcher.stop();
        } catch (Throwable ignored) {
        }
        try {
            sched.shutdown();
        } catch (Throwable ignored) {
        }
        log.i("shutdown", "已关闭");
        INSTANCE = null;
    }

    // ---------------- 状态 ----------------

    public String statusText() {
        Context c = host.context();
        Map<String, Object> st = session.status();
        StringBuilder sb = new StringBuilder();
        sb.append("版本 ").append(VERSION).append("  SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("进程 ").append(c.getPackageName()).append('\n');
        sb.append("宿主 ").append(shorten(host.probe(), 60)).append('\n');
        sb.append("传输 ").append(stateCn(Json.str(st, "state", "?")))
                .append("  设备 ").append(orDash(Json.str(st, "device_id", ""))).append('\n');
        sb.append("服务端 ").append(orDash(Json.str(st, "server", ""))).append('\n');
        sb.append("Broker ").append(orDash(Json.str(st, "broker", ""))).append('\n');
        sb.append("游标 ").append(Json.lng(st, "cursor", 0))
                .append("  拉了 ").append(Json.lng(st, "pulled", 0))
                .append("  确认 ").append(Json.lng(st, "acked", 0)).append('\n');
        Map<String, Object> lastClip = clipModule.last();
        sb.append("剪贴板 ").append(config.clipEnabled
                        ? (clipSource.isStarted() ? "同步中" : "未启动") : "已关闭")
                .append(!lastClip.isEmpty()
                        ? ("  最近 " + Json.str(lastClip, "dir", "") + " "
                        + Json.lng(lastClip, "len", 0) + "字") : "")
                .append('\n');
        sb.append("通知 ").append(notifySink.canNotify(7) ? "可用" : "被关闭（去系统设置开）")
                .append("  默认 L").append(config.defaultLevel)
                .append("  剪贴板 ").append(peekClip().isEmpty() ? "空" : "可读").append('\n');
        Map<String, Object> last = notifyModule.last();
        if (!last.isEmpty()) {
            sb.append("最近通知 L").append(Json.lng(last, "level", 0))
                    .append("  ").append(shorten(Json.str(last, "title", ""), 24)).append('\n');
        }
        String err = Json.str(st, "last_error", "");
        if (!err.isEmpty()) sb.append("最近错误 ").append(shorten(err, 80)).append('\n');
        sb.append("启动 ").append(System.currentTimeMillis() - bootAt).append("ms 前");
        return sb.toString();
    }

    private static String stateCn(String state) {
        if ("online".equals(state)) return "在线";
        if ("offline".equals(state)) return "离线";
        if ("stopped".equals(state)) return "已停止";
        return state;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String peekClip() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    host.context().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null) return "";
            if (cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(host.context());
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
