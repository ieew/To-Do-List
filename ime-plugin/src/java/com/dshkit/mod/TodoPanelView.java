package com.dshkit.mod;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

        /** 输入框需要/不需要键盘焦点（悬浮窗平时不可聚焦，避免抢输入法）。 */
        void onInputFocus(boolean want);
    }

    private static final int MAX_ROWS = 8;
    // 与待办 App（core/designsystem Tokens.light）同一套令牌：白卡片、蓝主色、灰阶文字。
    private static final int PANEL_BG = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1F2937;
    private static final int TEXT2 = 0xFF6B7280;
    private static final int TEXT3 = 0xFF9CA3AF;
    private static final int PRIMARY = 0xFF3B82F6;
    private static final int BORDER = 0xFFE5E7EB;
    private static final int BORDER2 = 0xFFF1F5F9;
    private static final int INPUT_BG = 0xFFF8FAFC;
    private static final int DANGER = 0xFFEF4444;
    private static final int DANGER_SOFT = 0xFFFEF2F2;
    private static final int OVERLAY_10 = 0x0F0F172A;

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
    /** 最近一次成功渲染的数据；刷新失败时用它们占住列表，不把内容清空。 */
    private List<TodoClient.Task> lastWaiting = new ArrayList<TodoClient.Task>();
    private List<TodoClient.Task> lastUpcoming = new ArrayList<TodoClient.Task>();

    public TodoPanelView(Context ctx, TodoClient client, Listener listener) {
        this.ctx = ctx;
        this.client = client;
        this.listener = listener;
    }

    public View build() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(panelBg());
        int pad = dp(16);
        root.setPadding(pad, dp(14), pad, dp(12));

        root.addView(buildTitleBar());

        status = new TextView(ctx);
        status.setTextColor(TEXT2);
        status.setTextSize(12);
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
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
        title.setTextColor(TEXT);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        bar.addView(title);

        bar.addView(touchButton("刷新", new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }));

        bar.addView(touchButton("关闭", new Runnable() {
            @Override
            public void run() {
                listener.onClose();
            }
        }));

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
        head.setTextColor(TEXT3);
        head.setTextSize(12);
        head.setPadding(dp(2), dp(6), 0, dp(3));
        box.addView(head);
        return box;
    }

    private void showKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    private View buildInputRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        input = new EditText(ctx);
        input.setHint("快速添加任务");
        input.setHintTextColor(TEXT3);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackground(round(INPUT_BG, 12));
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
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
        // 平时窗口不可聚焦（弹面板不抢输入法）；点输入框才临时切可聚焦并唤键盘。
        input.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    listener.onInputFocus(true);
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            input.requestFocus();
                            showKeyboard();
                        }
                    }, 80);
                }
                return false;
            }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) listener.onInputFocus(false);
            }
        });
        row.addView(input);

        addButton = new Button(ctx);
        addButton.setText("添加");
        addButton.setTextSize(13);
        addButton.setAllCaps(false);
        addButton.setTextColor(0xFFFFFFFF);
        addButton.setBackground(round(PRIMARY, 12));
        addButton.setPadding(dp(20), dp(9), dp(20), dp(9));
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
        lastWaiting = waiting;
        lastUpcoming = upcoming;
        status.setVisibility(View.GONE);
        waitingBox.removeViews(1, Math.max(0, waitingBox.getChildCount() - 1));
        upcomingBox.removeViews(1, Math.max(0, upcomingBox.getChildCount() - 1));

        if (waiting.isEmpty()) {
            waitingBox.addView(emptyLine("没有要完成的任务"));
        } else {
            for (int i = 0; i < waiting.size() && i < MAX_ROWS; i++) {
                waitingBox.addView(taskRow(waiting.get(i)));
                if (i < waiting.size() - 1 && i < MAX_ROWS - 1) waitingBox.addView(divider());
            }
            if (waiting.size() > MAX_ROWS) waitingBox.addView(moreLine(waiting.size() - MAX_ROWS));
        }

        if (upcoming.isEmpty()) {
            upcomingBox.addView(emptyLine("36 小时内没有新任务"));
        } else {
            for (int i = 0; i < upcoming.size() && i < MAX_ROWS; i++) {
                upcomingBox.addView(taskRow(upcoming.get(i)));
                if (i < upcoming.size() - 1 && i < MAX_ROWS - 1) upcomingBox.addView(divider());
            }
            if (upcoming.size() > MAX_ROWS) upcomingBox.addView(moreLine(upcoming.size() - MAX_ROWS));
        }
    }

    private View taskRow(final TodoClient.Task task) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), dp(6), dp(7));

        CheckBox check = new CheckBox(ctx);
        check.setChecked(false);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(PRIMARY));
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
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);

        String meta = metaLine(task);
        if (!meta.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(meta);
            sub.setTextColor(task.overdue ? DANGER : TEXT2);
            sub.setTextSize(11);
            texts.addView(sub);
        }
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showTaskMenu(v, task);
                return true;
            }
        });
        row.addView(texts);
        return row;
    }

    /**
     * 长按任务：弹快捷操作（完成 / 复制标题 / 取消）。
     * 只用现有 provider 能力（set_done），不需要 App 端改接口。
     */
    private void showTaskMenu(View anchor, final TodoClient.Task task) {
        try {
            final LinearLayout menu = new LinearLayout(ctx);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setBackground(round(0xFFFFFFFF, 12));
            menu.setPadding(dp(4), dp(4), dp(4), dp(4));
            menu.setElevation(dp(10));

            final PopupWindow popup = new PopupWindow(menu, dp(170),
                    LinearLayout.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(round(0x00000000, 0));
            popup.setOutsideTouchable(true);
            popup.setElevation(dp(10));

            TextView done = menuItem("完成");
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    markDone(task.id, true);
                }
            });
            menu.addView(done);

            TextView copy = menuItem("复制标题");
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    copyText(task.title);
                }
            });
            menu.addView(copy);

            TextView del = menuItem("删除");
            del.setTextColor(DANGER);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    confirmDelete(anchor, task);
                }
            });
            menu.addView(del);

            TextView cancel = menuItem("取消");
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                }
            });
            menu.addView(cancel);

            popup.showAsDropDown(anchor, dp(8), -anchor.getHeight());
        } catch (Throwable t) {
            showError(t);
        }
    }

    private TextView menuItem(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(TEXT);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackground(round(0x00000000, 8));
        return tv;
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dsh-todo", text == null ? "" : text));
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 删除前二次确认：居中白卡片，取消 / 红色删除。 */
    private void confirmDelete(final View anchor, final TodoClient.Task task) {
        try {
            final LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(round(0xFFFFFFFF, 16));
            box.setPadding(dp(18), dp(16), dp(18), dp(12));
            box.setElevation(dp(12));

            TextView title = new TextView(ctx);
            title.setText("删除任务？");
            title.setTextColor(TEXT);
            title.setTextSize(16);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            box.addView(title);

            TextView body = new TextView(ctx);
            body.setText("「" + task.title + "」删除后会从待办里消失。");
            body.setTextColor(TEXT2);
            body.setTextSize(13);
            body.setPadding(0, dp(8), 0, dp(12));
            box.addView(body);

            LinearLayout btns = new LinearLayout(ctx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.END);

            final PopupWindow popup = new PopupWindow(box, dp(280),
                    LinearLayout.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(round(0x00000000, 0));
            popup.setOutsideTouchable(true);
            popup.setElevation(dp(12));

            Button cancel = textButton("取消");
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                }
            });
            btns.addView(cancel);

            Button del = textButton("删除");
            del.setTextColor(0xFFFFFFFF);
            del.setBackground(round(DANGER, 10));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    doDelete(task);
                }
            });
            btns.addView(del);
            box.addView(btns);

            popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void doDelete(final TodoClient.Task task) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.delete(task.id);
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
        }, "dsh-todo-delete").start();
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
                            if (input != null) input.clearFocus();
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
        if (root == null) return;
        String msg;
        if (t instanceof SecurityException) {
            msg = "待办 App 拒绝调用（先装新版待办 App）";
        } else if (t instanceof IllegalArgumentException || t instanceof NullPointerException) {
            msg = "未安装待办 App，或版本太旧没有面板接口";
        } else {
            msg = "待办不可用：" + t.getClass().getSimpleName();
        }
        // 保留最近一次成功的数据：刷新失败只提示、不清空，避免"一点刷新任务全没了"。
        render(lastWaiting, lastUpcoming);
        status.setText(msg);
        status.setTextColor(DANGER);
        status.setBackground(round(DANGER_SOFT, 8));
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
        status.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 小工具

    private TextView emptyLine(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(TEXT3);
        tv.setTextSize(12);
        tv.setPadding(dp(28), dp(4), 0, dp(4));
        return tv;
    }

    private TextView moreLine(int more) {
        TextView tv = new TextView(ctx);
        tv.setText("还有 " + more + " 条，去 App 里看");
        tv.setTextColor(TEXT3);
        tv.setTextSize(11);
        tv.setPadding(dp(28), dp(2), 0, dp(2));
        return tv;
    }

    /** 标题栏按钮：视觉 56×36 的浅灰底按钮，外套 64×48 透明触控区，点偏也能命中。 */
    private View touchButton(String text, final Runnable action) {
        FrameLayout wrap = new FrameLayout(ctx);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(dp(64), dp(48));
        wrapLp.setMargins(dp(9), 0, 0, 0);
        wrap.setLayoutParams(wrapLp);

        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(TEXT2);
        b.setBackground(round(OVERLAY_10, 10));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(dp(56), dp(36), Gravity.CENTER);
        b.setLayoutParams(lp);
        wrap.addView(b);

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        };
        b.setOnClickListener(click);
        wrap.setOnClickListener(click);
        return wrap;
    }

    /** 通用文字按钮（确认框用）：紧凑尺寸。 */
    private Button textButton(String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(TEXT2);
        b.setBackground(round(OVERLAY_10, 10));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        return b;
    }

    /** 卡片底：白底 + 1dp 描边，对应 App 的 card + border。 */
    private GradientDrawable panelBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(PANEL_BG);
        d.setCornerRadius(dp(20));
        d.setStroke(dp(1), BORDER);
        return d;
    }

    private View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(BORDER2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(10), 0, dp(10), 0);
        v.setLayoutParams(lp);
        return v;
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
