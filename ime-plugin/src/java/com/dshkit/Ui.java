package com.dshkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
