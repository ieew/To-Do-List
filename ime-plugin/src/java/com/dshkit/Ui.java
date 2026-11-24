package com.dshkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * L4 界面：把状态面板塞进键盘区（宿主 showCustomView）。
 *
 * P1 起显示传输状态/游标/最近错误，并给三个动作：重连、测试通知、手动同步。
 * 面板可见时每 1.5s 自动刷新，关闭就停。
 */
public final class Ui {

    private static final long REFRESH_MS = 1500L;

    private final Kit kit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView status;
    private TextView logLine;
    private volatile boolean visible;
    private static final int[] TEST_LEVELS = {0, 1, 3, 6, 7};
    private int testIndex;

    Ui(Kit kit) {
        this.kit = kit;
    }

    public void panel() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    build();
                    visible = true;
                    kit.host().showCustomView(root);
                    main.postDelayed(tick, REFRESH_MS);
                } catch (Throwable t) {
                    kit.log().e("panel", t);
                }
            }
        });
    }

    public void hide() {
        visible = false;
    }

    /**
     * 工具模式：从输入法「工具」列表打开时，脚本跑在 AndroLua 的 Activity 里，
     * 这时候没法 showCustomView 顶键盘，就把同样的状态页画进 Activity。
     */
    public void attachActivity(final Activity activity) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final LinearLayout box = new LinearLayout(activity);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setBackgroundColor(Color.parseColor("#FF1B1B1F"));
                    int pad = dp(activity, 12);
                    box.setPadding(pad, pad, pad, pad);

                    TextView title = new TextView(activity);
                    title.setText("dsh-kit " + Kit.VERSION + " · 工具模式");
                    title.setTextColor(Color.parseColor("#FF80CBC4"));
                    title.setTextSize(18);
                    box.addView(title);

                    box.addView(todoSwitches(activity));

                    final TextView st = new TextView(activity);
                    st.setTextColor(Color.parseColor("#FFE8E8E8"));
                    st.setTextSize(14);
                    st.setLineSpacing(dp(activity, 3), 1f);
                    st.setTextIsSelectable(true);
                    ScrollView sc = new ScrollView(activity);
                    sc.addView(st);
                    sc.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                    box.addView(sc);

                    final TextView path = new TextView(activity);
                    path.setTextColor(Color.parseColor("#FF9E9E9E"));
                    path.setTextSize(10);
                    path.setText("日志: " + kit.log().path());
                    box.addView(path);

                    LinearLayout bar = new LinearLayout(activity);
                    bar.setOrientation(LinearLayout.HORIZONTAL);
                    bar.setGravity(Gravity.END);
                    bar.addView(actButton(activity, "重连", new Runnable() {
                        @Override
                        public void run() {
                            kit.session().kick();
                        }
                    }));
                    bar.addView(actButton(activity, "测试通知", new Runnable() {
                        @Override
                        public void run() {
                            int level = TEST_LEVELS[testIndex % TEST_LEVELS.length];
                            testIndex++;
                            kit.notifyModule().selfTest(level);
                            kit.host().toast("已按 L" + level + " 发一条测试通知");
                        }
                    }));
                    bar.addView(actButton(activity, "剪贴测试", new Runnable() {
                        @Override
                        public void run() {
                            kit.call("clip.test");
                            kit.host().toast("已写入剪贴板，看对端是否收到");
                        }
                    }));
                    bar.addView(actButton(activity, "复制日志", new Runnable() {
                        @Override
                        public void run() {
                            copyDiagnostics();
                        }
                    }));
                    bar.addView(actButton(activity, "清空日志", new Runnable() {
                        @Override
                        public void run() {
                            int n = kit.log().clear();
                            kit.host().toast("已清空 " + n + " 个日志文件");
                        }
                    }));
                    bar.addView(actButton(activity, "关闭", new Runnable() {
                        @Override
                        public void run() {
                            activity.finish();
                        }
                    }));
                    HorizontalScrollView barScroll = new HorizontalScrollView(activity);
                    barScroll.setHorizontalScrollBarEnabled(false);
                    barScroll.addView(bar);
                    box.addView(barScroll);

                    activity.setContentView(box);
                    // AndroLua 的 LuaActivity 在 onCreate 里会按 isSetViewed 决定要不要盖我们的 View，
                    // 这里直接把它标上，免得被默认的空 ListView 顶掉。
                    try {
                        java.lang.reflect.Field f = activity.getClass()
                                .getField("isSetViewed");
                        f.setBoolean(activity, true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        activity.setTitle("dsh-kit " + Kit.VERSION);
                    } catch (Throwable ignored) {
                    }
                    refreshActivity(activity, st);
                } catch (Throwable t) {
                    kit.log().e("ui/activity", t);
                }
            }
        });
    }

    /** 工具页顶部的待办开关：常驻通知、启用面板、悬浮窗权限、候选栏配置。 */
    private View todoSwitches(final Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(activity, 4), 0, dp(activity, 4));

        final CheckBox notice = new CheckBox(activity);
        notice.setText("常驻通知（点通知唤出/收起待办面板）");
        notice.setTextColor(Color.parseColor("#FFE8E8E8"));
        notice.setTextSize(13);
        // 看真实状态：进程被杀后通知已经被系统收走，偏好却还是「开」，
        // 只看偏好就会出现「通知没了、开关还勾着」。
        notice.setChecked(kit.todoNotice().isActive());
        notice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                kit.call("todo.notice", notice.isChecked() ? "on" : "off");
                // 开不起来（通知权限被关等）时把勾回滚到真实状态
                final CheckBox box = notice;
                box.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        box.setChecked(kit.todoNotice().isActive());
                    }
                }, 300);
            }
        });
        box.addView(notice);

        // 给当前主题写 onStartInput 钩子：换人用/换主题时不用手动改 main.lua
        final CheckBox autoStart = new CheckBox(activity);
        final String theme = kit.themeHook().currentTheme();
        autoStart.setText(theme == null
                ? "输入法启动时自动拉起（读不到当前主题）"
                : "输入法启动时自动拉起（主题 " + theme + " 加钩子）");
        autoStart.setTextColor(Color.parseColor("#FFE8E8E8"));
        autoStart.setTextSize(13);
        autoStart.setEnabled(theme != null);
        autoStart.setChecked(kit.themeHook().isInstalled());
        autoStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean want = autoStart.isChecked();
                boolean ok = want ? kit.themeHook().install() : kit.themeHook().uninstall();
                if (!ok) {
                    kit.host().toast(want ? "钩子没装上，看日志 theme/hook" : "钩子没删掉，看日志 theme/hook");
                } else {
                    kit.host().toast(want ? "已写入主题，下次进程重启生效" : "已从主题移除");
                }
                final CheckBox box2 = autoStart;
                box2.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        box2.setChecked(kit.themeHook().isInstalled());
                    }
                }, 300);
            }
        });
        box.addView(autoStart);

        final CheckBox enabled = new CheckBox(activity);
        enabled.setText("启用待办面板（候选栏按钮 / 通知入口）");
        enabled.setTextColor(Color.parseColor("#FFE8E8E8"));
        enabled.setTextSize(13);
        enabled.setChecked(kit.todoEnabled());
        enabled.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                kit.call(enabled.isChecked() ? "todo.enable" : "todo.disable");
            }
        });
        box.addView(enabled);

        TextView perm = new TextView(activity);
        perm.setTextSize(12);
        perm.setTextColor(Color.parseColor("#FF80CBC4"));
        boolean canDraw = kit.todoOverlay().canDraw();
        perm.setText(canDraw ? "悬浮窗权限：已授予" : "悬浮窗权限：未授予，点这里去开");
        perm.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        perm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (kit.todoOverlay().canDraw()) return;
                Intent intent = kit.todoOverlay().permissionIntent();
                if (intent == null) return;
                try {
                    activity.startActivity(intent);
                } catch (Throwable t) {
                    kit.log().e("ui/todo/perm", t);
                }
            }
        });
        box.addView(perm);

        Button cfg = actButton(activity, "复制候选栏配置", new Runnable() {
            @Override
            public void run() {
                copyToolbarSnippet();
            }
        });
        cfg.setTextSize(11);
        box.addView(cfg);

        Button reload = actButton(activity, "重载配置（重新读 config.json）", new Runnable() {
            @Override
            public void run() {
                kit.reloadConfig();
            }
        });
        reload.setTextSize(11);
        box.addView(reload);
        return box;
    }

    /** 候选栏按钮不改主题：只把配置片段送到剪贴板，用户自己决定加不加。 */
    private void copyToolbarSnippet() {
        String snippet = "preset_keys = {\n"
                + "  DshTodo = { label = \"待办\", command = \"dsh_todo.lua\" },\n"
                + "}\n"
                + "-- 再把它加进主题 styles/<样式>/main.lua 的 toolbar.keys，例如：\n"
                + "toolbar.keys = { \"DshTodo\", ... }";
        try {
            ClipboardManager cm = (ClipboardManager) ctx().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dsh-todo", snippet));
            kit.host().toast("配置已复制，粘到自己主题里");
        } catch (Throwable t) {
            kit.log().e("ui/copySnippet", t);
        }
    }

    private Button actButton(final Activity activity, String text, final Runnable action) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable t) {
                    kit.log().e("ui/act/" + text, t);
                }
            }
        });
        return b;
    }

    private void refreshActivity(final Activity activity, final TextView st) {
        if (activity.isFinishing()) return;
        try {
            st.setText(kit.statusText());
        } catch (Throwable t) {
            kit.log().e("ui/refreshActivity", t);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshActivity(activity, st);
            }
        }, REFRESH_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            render();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private Context ctx() {
        return kit.host().context();
    }

    private static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    private void build() {
        Context c = ctx();
        if (root == null) {
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.parseColor("#F2202024"));
            root.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));

            TextView title = new TextView(c);
            title.setText("dsh-kit  " + Kit.VERSION);
            title.setTextColor(Color.parseColor("#FF80CBC4"));
            title.setTextSize(15);
            title.setPadding(0, 0, 0, dp(c, 4));
            root.addView(title);

            status = new TextView(c);
            status.setTextColor(Color.parseColor("#FFE0E0E0"));
            status.setTextSize(12);
            status.setLineSpacing(dp(c, 2), 1f);
            ScrollView sc = new ScrollView(c);
            sc.addView(status);
            sc.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(sc);

            logLine = new TextView(c);
            logLine.setTextColor(Color.parseColor("#FF9E9E9E"));
            logLine.setTextSize(10);
            root.addView(logLine);

            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.END);
            bar.setPadding(0, dp(c, 4), 0, 0);

            bar.addView(button("重连", new Runnable() {
                @Override
                public void run() {
                    kit.session().kick();
                    render();
                }
            }));
            bar.addView(button("测试通知", new Runnable() {
                @Override
                public void run() {
                    // 依次试 0/1/3/6/7，覆盖「不弹 / 通知中心 / 悬浮 / 震动 / 响铃」五档
                    int level = TEST_LEVELS[testIndex % TEST_LEVELS.length];
                    testIndex++;
                    kit.notifyModule().selfTest(level);
                    kit.host().toast("已按 L" + level + " 发一条测试通知");
                }
            }));
            bar.addView(button("剪贴测试", new Runnable() {
                @Override
                public void run() {
                    kit.call("clip.test");
                    kit.host().toast("已写入剪贴板，看对端是否收到");
                }
            }));
            bar.addView(button("复制日志", new Runnable() {
                @Override
                public void run() {
                    copyDiagnostics();
                }
            }));
            bar.addView(button("刷新", new Runnable() {
                @Override
                public void run() {
                    render();
                }
            }));
            bar.addView(button("清空日志", new Runnable() {
                @Override
                public void run() {
                    int n = kit.log().clear();
                    kit.host().toast("已清空 " + n + " 个日志文件");
                }
            }));
            bar.addView(button("关闭", new Runnable() {
                @Override
                public void run() {
                    visible = false;
                    kit.host().restoreKeyboard();
                }
            }));

            HorizontalScrollView barScroll = new HorizontalScrollView(c);
            barScroll.setHorizontalScrollBarEnabled(false);
            barScroll.addView(bar);
            root.addView(barScroll);
        }
        render();
    }

    private Button button(String text, final Runnable action) {
        Button b = new Button(ctx());
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(dp(ctx(), 6), 0, dp(ctx(), 6), 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable t) {
                    kit.log().e("ui/" + text, t);
                }
            }
        });
        return b;
    }

    private void render() {
        try {
            status.setText(kit.statusText());
            logLine.setText("日志: " + kit.log().path());
        } catch (Throwable t) {
            kit.log().e("render", t);
        }
    }

    /** 复制「路径 + 最近若干行日志」到剪贴板，真机排查时直接粘出来就行。 */
    private void copyDiagnostics() {
        String path = kit.log().path();
        String text = tail(path, 80);
        try {
            ClipboardManager cm =
                    (ClipboardManager) ctx().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dsh-kit", path + "\n" + text));
            kit.host().toast("日志已复制（" + text.length() + " 字）");
        } catch (Throwable t) {
            kit.log().e("copyLog", t);
            kit.host().toast("复制失败：" + t.getClass().getSimpleName());
        }
    }

    private static String tail(String path, int lines) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(path));
            java.util.ArrayDeque<String> keep = new java.util.ArrayDeque<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                keep.addLast(line);
                if (keep.size() > lines) keep.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (String item : keep) sb.append(item).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "(读不到日志: " + t.getClass().getSimpleName() + ")";
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
