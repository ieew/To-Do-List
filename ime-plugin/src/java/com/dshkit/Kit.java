package com.dshkit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import com.dshkit.core.Config;
import com.dshkit.core.EventBus;
import com.dshkit.core.HttpApi;
import com.dshkit.core.Json;
import com.dshkit.core.Levels;
import com.dshkit.core.Log;
import com.dshkit.core.MqttTransport;
import com.dshkit.core.Queue;
import com.dshkit.core.Scheduler;
import com.dshkit.core.Session;
import com.dshkit.core.Store;
import com.dshkit.mod.ClipModule;
import com.dshkit.mod.ClipSource;
import com.dshkit.mod.NetWatcher;
import com.dshkit.mod.NotifyModule;
import com.dshkit.mod.NotifySink;
import com.dshkit.mod.ThemeHook;
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

    public static final String VERSION = "0.3.22";

    private static Kit INSTANCE;

    private final TrimeHost host;
    private final KitLog log;
    private final Store store;
    private final Queue queue;
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
    private final ThemeHook themeHook;
    private final SharedPreferences todoPrefs;
    private volatile boolean clipOn = true;
    private final long bootAt = System.currentTimeMillis();
    /** 进程级主实例：每个 Lua 入口会新建 DexClassLoader，宿主缓存又存不了 Java 对象，
     *  所以同时可能有几个 Kit。只让第一个启动网络/剪贴板/通知，其余当空壳。 */
    private final boolean primary;
    /** §3.0.1：插件靠 App 发凭证，App 没装就别空转。启动时查一次，查不到就停在未授权。 */
    private final boolean appInstalled;
    private BroadcastReceiver cmdReceiver;
    private Ui ui;

    private Kit(TrimeHost host, File base) {
        String booted = null;
        try {
            booted = System.getProperty("dshkit.kit.primary");
        } catch (Throwable ignored) {
        }
        this.primary = booted == null;
        if (this.primary) {
            try {
                System.setProperty("dshkit.kit.primary", "1");
            } catch (Throwable ignored) {
            }
        }
        this.host = host;
        this.log = new KitLog(host.context());
        this.store = new Store(new File(base, "prefs.json"));
        this.queue = new Queue(base, log);
        this.config = Config.load(new File(Config.DEFAULT_PATH), log);

        this.appInstalled = isPackageInstalled(host.context(), "com.todo.android");
        this.sched = new Scheduler("dsh-kit", log, primary);
        this.bus = new EventBus(sched.executor(), log);
        this.api = new HttpApi(log);
        this.transport = new MqttTransport(log);

        Context ctx = host.context();
        this.notifySink = new NotifySink(ctx, log);
        this.notifyModule = new NotifyModule(store, notifySink, log, config.defaultLevel);
        this.notifyModule.setQueue(queue);

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
                }, queue);
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
        // dsh 通道：插件不保存账号密码，凭证与状态都通过 provider 与 App 交换。
        this.session.setAuthorizer(new Session.Authorizer() {
            @Override
            public Map<String, Object> fetch() {
                return todoClient.dshAuthorize();
            }

            @Override
            public void update(String credential, long expiresAt) {
                todoClient.dshUpdateCredential(credential, expiresAt);
            }

            @Override
            public void report(boolean connected, String error) {
                todoClient.dshReport(connected, error, VERSION, Session.PROTOCOL);
            }

            @Override
            public void nudge() {
                todoClient.dshNudge();
            }

            @Override
            public void sync(long seq, String workspacesCsv) {
                todoClient.dshSync(seq, workspacesCsv);
            }
        });
        this.notifyModule.setTodoClient(todoClient);
        this.todoOverlay = new TodoOverlay(ctx, todoClient, log);
        this.todoNotice = new TodoNotice(ctx, notifySink, log, todoOverlay);
        this.themeHook = new ThemeHook(ctx, log);
        this.todoPrefs = ctx.getSharedPreferences("dsh_todo", Context.MODE_PRIVATE);
        this.session.setListener(new Session.Listener() {
            @Override
            public void onSessionChanged(Map<String, Object> status) {
                // 面板自己会定期刷；这里只落一条状态日志，避免刷屏。
                log.i("session", "状态 " + Json.write(status));
                if (primary) cachePrimaryStatus(status);
            }
        });
        this.ui = new Ui(this);
        if (this.primary) registerCmdReceiver();
    }

    /** 面板广播的 action：Lua 入口没有 Kit 时（已有主实例）用它让主实例出面板。 */
    public static final String ACTION_PANEL = "com.dshkit.ui.PANEL";
    /** 附加实例把网络命令（重连/同步）转发给主实例。 */
    public static final String ACTION_CMD = "com.dshkit.CMD";
    private static final String STATUS_PREFS = "dsh_kit_primary";
    private static final String STATUS_JSON = "status";
    private static final String STATUS_CLIP = "clip";
    private static final String STATUS_AT = "at";

    /** 主实例注册一个命令接收器：别的 Lua 入口发广播就能操作这个唯一实例。 */
    private void registerCmdReceiver() {
        cmdReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    if (intent == null) return;
                    log.i("web/cmd", "收到 " + intent.getAction());
                    if (ACTION_PANEL.equals(intent.getAction())) ui.panel();
                    if (ACTION_CMD.equals(intent.getAction())) {
                        String cmd = intent.getStringExtra("action");
                        log.i("web/cmd", "附加实例转发命令 " + cmd);
                        if ("dsh.sync".equals(cmd)) session.syncNow();
                        else if ("dsh.reconnect".equals(cmd) || "dsh.login".equals(cmd)) session.kick();
                        else if (cmd != null && cmd.startsWith("clip.set:")) setClipOn(cmd.endsWith(":on"));
                    }
                } catch (Throwable t) {
                    log.e("cmd/recv", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PANEL);
        filter.addAction(ACTION_CMD);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                java.lang.reflect.Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                int flags = Build.VERSION.SDK_INT >= 33 ? 4 : 0;
                m.invoke(host.context(), cmdReceiver, filter, flags);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            host.context().registerReceiver(cmdReceiver, filter);
        } catch (Throwable t) {
            log.e("cmd/register", t);
        }
    }

    public boolean isPrimary() {
        return primary;
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
        if (INSTANCE.primary && !INSTANCE.appInstalled) {
            // 反复重试授权只会刷日志、耗电，而且用户看不出原因。
            INSTANCE.log.w("boot", "未检测到待办 App（com.todo.android）：停在未授权状态，不建连接");
        } else if (INSTANCE.primary) {
            INSTANCE.session.start();
            // 剪贴板：config.json 的 clip_enabled 与工具页开关取交（默认开）
            INSTANCE.clipOn = INSTANCE.config.clipEnabled
                    && INSTANCE.todoPrefs.getBoolean("clip_on", true);
            if (INSTANCE.clipOn) INSTANCE.clipSource.start();
            INSTANCE.netWatcher.start();
            INSTANCE.todoNotice.sync();
        } else {
            INSTANCE.log.i("boot", "附加实例：不启动网络/剪贴板/通知（由主实例负责）");
        }
        return INSTANCE;
    }

    /** App 是否安装：只查包管理器，不扫描文件系统（§3.0.1）。 */
    public boolean appInstalled() {
        return appInstalled;
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
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
            if (primary) {
                clipOn = fresh.clipEnabled && todoPrefs.getBoolean("clip_on", true);
                if (clipOn) {
                    if (!clipSource.isStarted()) clipSource.start();
                } else {
                    clipSource.stop();
                }
            }
            log.i("config", "重载：source=" + fresh.source
                    + " server=" + fresh.server + " user=" + fresh.username);
            if (!fresh.hasServer()) {
                host.toast("配置已重载，但 server 没填（dsh.d/config.json）");
                return;
            }
            if (!primary) {
                host.toast("配置已重载（附加实例不重连，主实例下次启动生效）");
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

    /** 主题自启动钩子（工具页「自启动」开关）。 */
    public ThemeHook themeHook() {
        return themeHook;
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
                "notice", Boolean.valueOf(todoNotice.isActive()),
                "autoStart", Boolean.valueOf(themeHook.isInstalled()),
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
            if (action.equals("theme.hook")) {
                boolean on = !"off".equalsIgnoreCase(args == null ? "" : args.trim());
                boolean ok = on ? themeHook.install() : themeHook.uninstall();
                if (on) host.toast(ok ? "自启动钩子已写入主题" : "写主题失败：看日志 theme/hook");
                else host.toast(ok ? "自启动钩子已移除" : "删主题钩子失败：看日志 theme/hook");
                return Boolean.valueOf(ok);
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
            if (action.equals("clip.set")) {
                boolean on = args != null && !args.trim().isEmpty()
                        ? "on".equalsIgnoreCase(args.trim())
                        : !clipOn();
                if (primary) setClipOn(on);
                else forwardCmd("clip.set:" + (on ? "on" : "off"));
                return null;
            }
            if (action.equals("dsh.sync")) {
                // 附加实例不碰网络：转发给主实例执行
                if (primary) session.syncNow();
                else forwardCmd("dsh.sync");
                return null;
            }
            if (action.equals("dsh.reconnect") || action.equals("dsh.login")) {
                if (primary) session.kick();
                else forwardCmd(action);
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
        boolean fromPrimary = true;
        Map<String, Object> st;
        if (primary) {
            st = session.status();
        } else {
            // 工具页是额外的 Kit 实例：自己没连网，显示主实例最后上报的状态，
            // 不然会出现「传输 已停止 / 剪贴板 未启动」这种误导。
            st = cachedStatus();
            fromPrimary = st != null;
            if (st == null) st = new java.util.LinkedHashMap<String, Object>();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("版本 ").append(VERSION).append("  SDK ").append(Build.VERSION.SDK_INT).append('\n');
        if (!primary) sb.append("附加实例：网络/剪贴板/通知由主实例负责\n");
        sb.append("进程 ").append(c.getPackageName()).append('\n');
        sb.append("宿主 ").append(shorten(host.probe(), 60)).append('\n');
        sb.append("传输 ").append(fromPrimary ? stateCn(Json.str(st, "state", "?")) : "等主实例上报")
                .append("  设备 ").append(orDash(Json.str(st, "device_id", ""))).append('\n');
        sb.append("服务端 ").append(orDash(Json.str(st, "server", ""))).append('\n');
        sb.append("Broker ").append(orDash(Json.str(st, "broker", ""))).append('\n');
        sb.append("游标 ").append(Json.lng(st, "cursor", 0))
                .append("  拉了 ").append(Json.lng(st, "pulled", 0))
                .append("  确认 ").append(Json.lng(st, "acked", 0)).append('\n');
        boolean clipStarted = primary ? clipSource.isStarted() : cachedClipStarted();
        Map<String, Object> lastClip = clipModule.last();
        sb.append("剪贴板 ").append(config.clipEnabled
                        ? (clipStarted ? "同步中" : (primary ? "未启动" : "由主实例负责"))
                        : "已关闭")
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
        if (primary) {
            sb.append("启动 ").append(System.currentTimeMillis() - bootAt).append("ms 前");
        } else {
            long age = cachedStatusAgeMs();
            sb.append(age >= 0 ? "主实例状态 " + (age / 1000) + "s 前上报" : "主实例状态 未上报");
        }
        return sb.toString();
    }

    /** 主实例把最新状态写进 prefs，供工具页（附加实例）显示真实连接状态。 */
    /** 剪贴板同步是否真的在跑（config 开关 + 工具页开关）。附加实例读偏好。 */
    public boolean clipOn() {
        if (!primary) return config.clipEnabled && todoPrefs.getBoolean("clip_on", true);
        return clipOn && clipSource.isStarted();
    }

    /** 工具页「剪贴板同步」开关：写偏好并立即启停监听（主实例才真正启停）。 */
    public void setClipOn(boolean on) {
        clipOn = on;
        todoPrefs.edit().putBoolean("clip_on", on).apply();
        if (!primary) return;
        if (on) clipSource.start();
        else clipSource.stop();
        log.i("clip", on ? "已开启剪贴板同步" : "已关闭剪贴板同步");
    }

    private void cachePrimaryStatus(Map<String, Object> status) {
        try {
            host.context().getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(STATUS_JSON, Json.write(status))
                    .putBoolean(STATUS_CLIP, clipSource != null && clipSource.isStarted())
                    .putLong(STATUS_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 附加实例读主实例最后上报的状态；没有则返回 null。 */
    private Map<String, Object> cachedStatus() {
        try {
            String raw = host.context().getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                    .getString(STATUS_JSON, null);
            if (raw == null || raw.isEmpty()) return null;
            Map<String, Object> map = Json.asObject(Json.parse(raw));
            return map.isEmpty() ? null : map;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean cachedClipStarted() {
        try {
            return host.context().getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(STATUS_CLIP, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private long cachedStatusAgeMs() {
        try {
            long at = host.context().getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                    .getLong(STATUS_AT, 0L);
            return at <= 0 ? -1 : System.currentTimeMillis() - at;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 附加实例把网络命令转给主实例执行。 */
    private void forwardCmd(String action) {
        try {
            Intent i = new Intent(ACTION_CMD);
            i.setPackage(host.context().getPackageName());
            i.putExtra("action", action);
            host.context().sendBroadcast(i);
            log.i("web/cmd", "附加实例转发 " + action);
        } catch (Throwable t) {
            log.e("cmd/forward", t);
        }
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
