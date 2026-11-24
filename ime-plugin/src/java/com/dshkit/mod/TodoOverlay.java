package com.dshkit.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.dshkit.KitLog;

/**
 * 待办悬浮窗容器。
 *
 * 用 `TYPE_APPLICATION_OVERLAY`（API 26+）挂在屏幕上方，可拖动、位置记住；
 * 面板内容见 {@link TodoPanelView}。显示/隐藏是纯 View 操作，不依赖 Activity。
 *
 * 权限：`SYSTEM_ALERT_WINDOW`（宿主的 manifest 已声明），要在系统设置里授予。
 * 未授予时 {@link #show()} 返回 false，由上层提示用户去开。
 */
public final class TodoOverlay {

    private static final String PREFS = "dsh_todo_overlay";
    private static final String PREF_X = "x";
    private static final String PREF_Y = "y";
    /** 编译目标是 android-23，没有这两个常量；值取自 WindowManager.LayoutParams。 */
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    private static final int TYPE_PHONE = 2002;

    private final Context ctx;
    private final TodoClient client;
    private final KitLog log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private TodoPanelView panel;
    private View root;
    private volatile boolean visible;
    /** 我们的输入框是否在要键盘（此时按键盘高度压缩面板）。 */
    private volatile boolean inputFocus;
    /** 探测到的键盘高度（px），0 表示键盘不可见；探测不到时用输入框聚焦估算。 */
    private volatile int keyboardHeight;
    private boolean heightWatched;

    public TodoOverlay(Context ctx, TodoClient client, KitLog log) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.client = client;
        this.log = log;
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 系统里是否已允许「显示在其他应用上层」。 */
    public boolean canDraw() {
        try {
            if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(ctx);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳系统设置授权的 Intent（由 Activity 启动；Service 需加 NEW_TASK）。 */
    public Intent permissionIntent() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * 面板内的输入框要键盘时才临时去掉 NOT_FOCUSABLE。
     * 平时保持不可聚焦：弹出面板不会抢走输入焦点、不会收掉已经弹出的输入法。
     */
    public void setInputFocus(final boolean want) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wm == null || root == null || lp == null) return;
                    int flags = want
                            ? (lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                            : (lp.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    if (flags == lp.flags) return;
                    lp.flags = flags;
                    wm.updateViewLayout(root, lp);
                } catch (Throwable t) {
                    log.e("todo/overlay/focus", t);
                }
            }
        });
    }

    public void toggle() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (visible) hideInternal();
                else showInternal();
            }
        });
    }

    public void show() {
        main.post(new Runnable() {
            @Override
            public void run() {
                showInternal();
            }
        });
    }

    public void hide() {
        main.post(new Runnable() {
            @Override
            public void run() {
                hideInternal();
            }
        });
    }

    // ---------------------------------------------------------------- 内部

    private void showInternal() {
        try {
            if (!canDraw()) {
                log.w("todo/overlay", "没有悬浮窗权限，无法显示");
                return;
            }
            if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            if (root == null) {
                panel = new TodoPanelView(ctx, client, new TodoPanelView.Listener() {
                    @Override
                    public void onClose() {
                        hideInternal();
                    }

                    @Override
                    public void onTitleTouch(MotionEvent event) {
                        handleDrag(event);
                    }

                    @Override
                    public void onInputFocus(boolean want) {
                        inputFocus = want;
                        setInputFocus(want);
                        recomputeHeight();
                    }
                });
                root = panel.build();
                watchKeyboardOnce();
            }
            if (visible) {
                panel.refresh();
                return;
            }
            lp = layoutParams();
            wm.addView(root, lp);
            visible = true;
            panel.refresh();
            log.i("todo/overlay", "已显示 @" + lp.x + "," + lp.y);
        } catch (Throwable t) {
            log.e("todo/overlay/show", t);
        }
    }

    private void hideInternal() {
        try {
            if (!visible || wm == null || root == null) return;
            wm.removeView(root);
        } catch (Throwable t) {
            log.e("todo/overlay/hide", t);
        } finally {
            visible = false;
        }
    }

    private WindowManager.LayoutParams layoutParams() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int width = Math.min(dm.widthPixels - dp(32), dp(380));
        int height = heightFor(dm);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= 26
                        ? TYPE_APPLICATION_OVERLAY
                        : TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = prefs.getInt(PREF_X, (dm.widthPixels - width) / 2);
        p.y = prefs.getInt(PREF_Y, Math.max(dp(56), dm.heightPixels / 7));
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return p;
    }

    // ---------------------------------------------------------------- 高度

    /**
     * 面板高度：键盘收起时 58% 屏高；键盘展开/我们的输入框聚焦时压缩，
     * 保证面板底部与键盘顶部留 16dp 空隙。
     */
    private int heightFor(DisplayMetrics dm) {
        int screenH = dm.heightPixels;
        int top = prefs.getInt(PREF_Y, Math.max(dp(56), screenH / 7));
        int kbd = keyboardHeight > 0
                ? keyboardHeight
                : (inputFocus ? (int) (screenH * 0.40f) : 0);
        int avail = screenH - top - kbd - dp(16);
        int max = Math.min((int) (screenH * 0.58f), dp(520));
        return Math.max(dp(220), Math.min(max, avail));
    }

    private void recomputeHeight() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wm == null || root == null || lp == null) return;
                    int h = heightFor(ctx.getResources().getDisplayMetrics());
                    if (h == lp.height) return;
                    lp.height = h;
                    wm.updateViewLayout(root, lp);
                    log.i("todo/overlay", "高度调整为 " + h + "px（键盘=" + keyboardHeight + "）");
                } catch (Throwable t) {
                    log.e("todo/overlay/height", t);
                }
            }
        });
    }

    /** 监听布局：从可见区域差值探测键盘高度；探测不到时靠输入框聚焦估算。 */
    private void watchKeyboardOnce() {
        if (heightWatched || root == null) return;
        heightWatched = true;
        try {
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                                android.graphics.Rect r = new android.graphics.Rect();
                                root.getWindowVisibleDisplayFrame(r);
                                int diff = dm.heightPixels - r.bottom;
                                int next = diff > dm.heightPixels / 5 ? diff : 0;
                                if (next != keyboardHeight) {
                                    keyboardHeight = next;
                                    log.i("todo/overlay", "键盘高度=" + next);
                                    recomputeHeight();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log.e("todo/overlay/kbd", t);
        }
    }

    // ---------------------------------------------------------------- 拖动

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean dragging;

    private void handleDrag(MotionEvent event) {
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = lp.x;
                    downY = lp.y;
                    dragging = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return;
                    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    int nx = downX + (int) (event.getRawX() - downRawX);
                    int ny = downY + (int) (event.getRawY() - downRawY);
                    nx = Math.max(-dp(24), Math.min(nx, dm.widthPixels - dp(64)));
                    ny = Math.max(0, Math.min(ny, dm.heightPixels - dp(64)));
                    lp.x = nx;
                    lp.y = ny;
                    if (wm != null && root != null) wm.updateViewLayout(root, lp);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return;
                    dragging = false;
                    prefs.edit().putInt(PREF_X, lp.x).putInt(PREF_Y, lp.y).apply();
                    recomputeHeight();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            log.e("todo/overlay/drag", t);
        }
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
