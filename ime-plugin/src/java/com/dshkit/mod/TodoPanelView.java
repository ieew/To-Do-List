package com.dshkit.mod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 待办面板的内容区（不含悬浮窗容器与拖动）。
 *
 * 结构：标题栏（关闭/刷新）→ 等待完成列表 → 即将开始列表 → 快速添加输入行。
 * 所有 provider 调用都在后台线程，结果回主线程渲染；失败统一转成一行提示。
 */
public final class TodoPanelView {

    public interface Listener {
        /** 点关闭。 */
        void onClose();

        /** 标题栏被触摸（交给外层做拖动）。 */
        void onTitleTouch(MotionEvent event);
    }

    private static final int MAX_ROWS = 8;
    private static final int BG = 0xF2202024;
    private static final int PANEL_BG = 0xFF1B1B1F;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int MUTED = 0xFF9E9E9E;
    private static final int ACCENT = 0xFF80CBC4;
    private static final int OVERDUE = 0xFFEF5350;

    private final Context ctx;
    private final TodoClient client;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout waitingBox;
    private LinearLayout upcomingBox;
    private TextView status;
    private EditText input;
    private Button addButton;
    private volatile boolean refreshing;

    public TodoPanelView(Context ctx, TodoClient client, Listener listener) {
        this.ctx = ctx;
        this.client = client;
        this.listener = listener;
    }

    public View build() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(BG, 18));
        int pad = dp(12);
        root.setPadding(pad, dp(10), pad, pad);

        root.addView(buildTitleBar());

        status = new TextView(ctx);
        status.setTextColor(MUTED);
        status.setTextSize(11);
        status.setPadding(0, dp(2), 0, dp(4));
        status.setVisibility(View.GONE);
        root.addView(status);

        waitingBox = section("等待完成");
        upcomingBox = section("即将开始 · 36 小时");
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout lists = new LinearLayout(ctx);
        lists.setOrientation(LinearLayout.VERTICAL);
        lists.addView(waitingBox);
        lists.addView(upcomingBox);
        scroll.addView(lists);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scroll);

        root.addView(buildInputRow());
        refresh();
        return root;
    }

    /** 面板可见时重新拉数据。 */
    public void refresh() {
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<TodoClient.Task> waiting =
                            client.query(TodoClient.SCOPE_WAITING, TodoClient.DEFAULT_LIMIT);
                    final List<TodoClient.Task> upcoming =
                            client.query(TodoClient.SCOPE_UPCOMING, TodoClient.DEFAULT_LIMIT);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            render(waiting, upcoming);
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            showError(t);
                        }
                    });
                } finally {
                    refreshing = false;
                }
            }
        }, "dsh-todo-query").start();
    }

    // ---------------------------------------------------------------- 构建

    private View buildTitleBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(ctx);
        title.setText("待办");
        title.setTextColor(ACCENT);
        title.setTextSize(16);
        title.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        bar.addView(title);

        Button refresh = iconButton("⟳");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        bar.addView(refresh);

        Button close = iconButton("×");
        close.setTextSize(20);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onClose();
            }
        });
        bar.addView(close);

        // 整个标题栏空白处拖动；按钮自己消费点击，不触发拖动
        bar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                listener.onTitleTouch(event);
                return true;
            }
        });
        return bar;
    }

    private LinearLayout section(String name) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(2));

        TextView head = new TextView(ctx);
        head.setText(name);
        head.setTextColor(MUTED);
        head.setTextSize(11);
        head.setPadding(dp(2), dp(4), 0, dp(2));
        box.addView(head);
        return box;
    }

    private View buildInputRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        input = new EditText(ctx);
        input.setHint("快速添加任务");
        input.setHintTextColor(0xFF777777);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackground(round(0x33FFFFFF, 20));
        input.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputLp);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        row.addView(input);

        addButton = new Button(ctx);
        addButton.setText("添加");
        addButton.setTextSize(13);
        addButton.setAllCaps(false);
        addButton.setTextColor(Color.parseColor("#FF1B1B1F"));
        addButton.setBackground(round(ACCENT, 20));
        addButton.setPadding(dp(16), 0, dp(16), 0);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        row.addView(addButton);
        return row;
    }

    // ---------------------------------------------------------------- 渲染

    private void render(List<TodoClient.Task> waiting, List<TodoClient.Task> upcoming) {
        if (root == null) return;
        status.setVisibility(View.GONE);
        waitingBox.removeViews(1, Math.max(0, waitingBox.getChildCount() - 1));
        upcomingBox.removeViews(1, Math.max(0, upcomingBox.getChildCount() - 1));

        if (waiting.isEmpty()) {
            waitingBox.addView(emptyLine("没有要完成的任务"));
        } else {
            for (int i = 0; i < waiting.size() && i < MAX_ROWS; i++) {
                waitingBox.addView(taskRow(waiting.get(i)));
            }
            if (waiting.size() > MAX_ROWS) waitingBox.addView(moreLine(waiting.size() - MAX_ROWS));
        }

        if (upcoming.isEmpty()) {
            upcomingBox.addView(emptyLine("36 小时内没有新任务"));
        } else {
            for (int i = 0; i < upcoming.size() && i < MAX_ROWS; i++) {
                upcomingBox.addView(taskRow(upcoming.get(i)));
            }
            if (upcoming.size() > MAX_ROWS) upcomingBox.addView(moreLine(upcoming.size() - MAX_ROWS));
        }
    }

    private View taskRow(final TodoClient.Task task) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), dp(6), dp(3));

        CheckBox check = new CheckBox(ctx);
        check.setChecked(false);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                markDone(task.id, true);
            }
        });
        row.addView(check);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textsLp);

        TextView title = new TextView(ctx);
        title.setText(task.title);
        title.setTextColor(TEXT);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);

        String meta = metaLine(task);
        if (!meta.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(meta);
            sub.setTextColor(task.overdue ? OVERDUE : MUTED);
            sub.setTextSize(10);
            texts.addView(sub);
        }
        row.addView(texts);
        return row;
    }

    private String metaLine(TodoClient.Task task) {
        StringBuilder sb = new StringBuilder();
        if (task.listName != null && !task.listName.isEmpty()) sb.append(task.listName);
        if (task.dueAt > 0L) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(task.overdue ? "逾期 " : "截止 ").append(fmt(task.dueAt));
        } else if (task.startAt > 0L) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(fmt(task.startAt)).append(" 开始");
        }
        return sb.toString();
    }

    private static String fmt(long ms) {
        try {
            long now = System.currentTimeMillis();
            if (ms >= now && ms - now < 24L * 60 * 60 * 1000) {
                return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
            }
            return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 动作

    private void submit() {
        if (input == null) return;
        final String title = input.getText() == null ? "" : input.getText().toString().trim();
        if (title.isEmpty()) return;
        input.setText("");
        addButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.add(title);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            addButton.setEnabled(true);
                            refresh();
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            addButton.setEnabled(true);
                            showError(t);
                        }
                    });
                }
            }
        }, "dsh-todo-add").start();
    }

    private void markDone(final String taskId, final boolean done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.setDone(taskId, done);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } catch (final Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            showError(t);
                        }
                    });
                }
            }
        }, "dsh-todo-done").start();
    }

    private void showError(Throwable t) {
        if (status == null) return;
        String msg;
        if (t instanceof SecurityException) {
            msg = "待办 App 拒绝调用（先装新版待办 App）";
        } else if (t instanceof IllegalArgumentException || t instanceof NullPointerException) {
            msg = "未安装待办 App，或版本太旧没有面板接口";
        } else {
            msg = "待办不可用：" + t.getClass().getSimpleName();
        }
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
        if (waitingBox != null && waitingBox.getChildCount() > 1) {
            waitingBox.removeViews(1, waitingBox.getChildCount() - 1);
        }
        if (upcomingBox != null && upcomingBox.getChildCount() > 1) {
            upcomingBox.removeViews(1, upcomingBox.getChildCount() - 1);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private TextView emptyLine(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF777777);
        tv.setTextSize(12);
        tv.setPadding(dp(28), dp(4), 0, dp(4));
        return tv;
    }

    private TextView moreLine(int more) {
        TextView tv = new TextView(ctx);
        tv.setText("还有 " + more + " 条，去 App 里看");
        tv.setTextColor(0xFF777777);
        tv.setTextSize(11);
        tv.setPadding(dp(28), dp(2), 0, dp(2));
        return tv;
    }

    private Button iconButton(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(round(0x00000000, 20));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
