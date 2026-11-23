package com.dshkit.mod;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.provider.Settings;

import com.dshkit.core.Levels;
import com.dshkit.core.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知出口：按等级选渠道。
 *
 * 渠道重要性一旦创建就不能回改，所以每个等级档位一条独立渠道，创建时就定死：
 *
 * | 渠道 | 等级 | importance | 声音 | 震动 |
 * |---|---|---|---|---|
 * | dsh_kit_l_min     | 0   | LOW     | 无 | 无（静默，归到「不重要」） |
 * | dsh_kit_l_center  | 1–2 | DEFAULT | 无 | 无 |
 * | dsh_kit_l_heads   | 3–5 | HIGH    | 无 | 无 |
 * | dsh_kit_l_vibrate | 6   | HIGH    | 无 | 有 |
 * | dsh_kit_l_alert   | 7   | HIGH    | 系统默认 | 有 |
 *
 * 0 级也弹，但用 LOW 渠道，系统会归到「静默/不重要」一栏。
 *
 * NotificationChannel 只有 API 26+ 才有，而编译目标是 android-23，所以统一走反射；
 * API 25 及以下退回 Builder 的 priority/sound/vibrate 设置（那种系统上「静音悬浮」做不到，
 * 只能退化成普通提醒，属于平台限制）。
 */
public final class NotifySink {

    public static final String CH_MIN = "dsh_kit_l_min";
    public static final String CH_CENTER = "dsh_kit_l_center";
    public static final String CH_HEADS = "dsh_kit_l_heads";
    public static final String CH_VIBRATE = "dsh_kit_l_vibrate";
    public static final String CH_ALERT = "dsh_kit_l_alert";
    /** 兼容旧配置：状态常驻渠道（默认不用）。 */
    public static final String CH_STATE = "dsh_kit_state";

    private static final String GROUP = "dsh_kit";

    private static final int IMPORTANCE_MIN = 1;
    private static final int IMPORTANCE_LOW = 2;
    private static final int IMPORTANCE_DEFAULT = 3;
    private static final int IMPORTANCE_HIGH = 4;

    private final Context ctx;
    private final Log log;
    private final List<String> tags = new ArrayList<String>();
    private volatile boolean channelsReady;

    public NotifySink(Context ctx, Log log) {
        this.ctx = ctx;
        this.log = log;
        ensureChannels();
    }

    public synchronized void ensureChannels() {
        if (channelsReady) return;
        if (android.os.Build.VERSION.SDK_INT < 26) {
            channelsReady = true;
            return;
        }
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Constructor<?> ctor = channelClass.getConstructor(
                    String.class, CharSequence.class, int.class);
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            create(nm, channelClass, ctor, CH_MIN, "dsh 静默（不重要）", IMPORTANCE_LOW,
                    "静默进通知中心：不悬浮、不出声、不震动", false, false);
            create(nm, channelClass, ctor, CH_CENTER, "dsh 通知中心", IMPORTANCE_DEFAULT,
                    "只进通知中心：不悬浮、不出声、不震动", false, false);
            create(nm, channelClass, ctor, CH_HEADS, "dsh 悬浮提醒", IMPORTANCE_HIGH,
                    "悬浮显示：不出声、不震动", false, false);
            create(nm, channelClass, ctor, CH_VIBRATE, "dsh 悬浮+震动", IMPORTANCE_HIGH,
                    "悬浮显示并震动：不出声", false, true);
            create(nm, channelClass, ctor, CH_ALERT, "dsh 响铃提醒", IMPORTANCE_HIGH,
                    "悬浮显示 + 震动 + 响声", true, true);
            create(nm, channelClass, ctor, CH_STATE, "dsh 服务状态", IMPORTANCE_MIN,
                    "常驻状态（默认关闭）", false, false);
            channelsReady = true;
        } catch (Throwable t) {
            log.e("notify/channels", t);
        }
    }

    private void create(NotificationManager nm, Class<?> channelClass, Constructor<?> ctor,
                        String id, String name, int importance, String desc,
                        boolean sound, boolean vibrate) {
        try {
            Object exists = nm.getClass().getMethod("getNotificationChannel", String.class)
                    .invoke(nm, id);
            if (exists != null) return; // 已存在：重要性不能改，保持原样
            Object channel = ctor.newInstance(id, name, Integer.valueOf(importance));
            invokeIfPresent(channelClass, channel, "setDescription", new Class[]{String.class},
                    new Object[]{desc});
            invokeIfPresent(channelClass, channel, "enableVibration", new Class[]{boolean.class},
                    new Object[]{Boolean.valueOf(vibrate)});
            if (vibrate) {
                long[] pattern = new long[]{0, 180, 120, 180};
                invokeIfPresent(channelClass, channel, "setVibrationPattern",
                        new Class[]{long[].class}, new Object[]{pattern});
            }
            invokeIfPresent(channelClass, channel, "setShowBadge", new Class[]{boolean.class},
                    new Object[]{Boolean.TRUE});
            invokeIfPresent(channelClass, channel, "setLockscreenVisibility",
                    new Class[]{int.class}, new Object[]{Integer.valueOf(Notification.VISIBILITY_PRIVATE)});
            if (sound) {
                Uri uri = Settings.System.DEFAULT_NOTIFICATION_URI;
                setChannelSound(channelClass, channel, uri);
            } else {
                // 静音渠道必须显式置空，否则会跟随默认铃声
                setChannelSound(channelClass, channel, null);
            }
            nm.getClass().getMethod("createNotificationChannel", channelClass)
                    .invoke(nm, channel);
            log.i("notify", "已建渠道 " + id + " importance=" + importance
                    + " sound=" + sound + " vibrate=" + vibrate);
        } catch (Throwable t) {
            log.e("notify/channel/" + id, t);
        }
    }

    private void setChannelSound(Class<?> channelClass, Object channel, Uri uri) {
        try {
            Class<?> attrClass = Class.forName("android.media.AudioAttributes");
            Method builder = attrClass.getMethod("Builder");
            Object attrBuilder = builder.invoke(null);
            Object attrs = attrBuilder.getClass()
                    .getMethod("setContentType", int.class)
                    .invoke(attrBuilder, Integer.valueOf(AudioAttributes.CONTENT_TYPE_SONIFICATION));
            attrs = attrBuilder.getClass().getMethod("setUsage", int.class)
                    .invoke(attrBuilder, Integer.valueOf(AudioAttributes.USAGE_NOTIFICATION));
            attrs = attrBuilder.getClass().getMethod("build").invoke(attrBuilder);
            Class<?> attrsInterface = Class.forName("android.media.AudioAttributes");
            Method setSound = channelClass.getMethod("setSound", Uri.class, attrsInterface);
            setSound.invoke(channel, uri, attrs);
        } catch (Throwable t) {
            // 老实现（API26）没有两参 setSound，退回一参版本
            try {
                channelClass.getMethod("setSound", Uri.class).invoke(channel, uri);
            } catch (Throwable ignored) {
                log.w("notify", "设置渠道声音失败（忽略）：" + t.getClass().getSimpleName());
            }
        }
    }

    private static void invokeIfPresent(Class<?> cls, Object target, String name,
                                        Class<?>[] types, Object[] args) {
        try {
            cls.getMethod(name, types).invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    /** 能不能弹通知：总开关 + 目标渠道重要性两条都要看。 */
    public boolean canNotify(int level) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            Object enabled = nm.getClass().getMethod("areNotificationsEnabled").invoke(nm);
            if (!Boolean.TRUE.equals(enabled)) return false;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                String channel = channelFor(level);
                Object ch = nm.getClass().getMethod("getNotificationChannel", String.class)
                        .invoke(nm, channel);
                if (ch == null) return true;
                Object importance = ch.getClass().getMethod("getImportance").invoke(ch);
                return !(importance instanceof Integer) || ((Integer) importance).intValue() != 0;
            }
            return true;
        } catch (Throwable t) {
            return true; // 查不出来就当可用，真弹失败下面会记日志
        }
    }

    public static String channelFor(int level) {
        switch (Levels.bucket(level)) {
            case QUIET:
                return CH_MIN;
            case CENTER:
                return CH_CENTER;
            case HEADS_UP:
                return CH_HEADS;
            case VIBRATE:
                return CH_VIBRATE;
            default:
                return CH_ALERT;
        }
    }

    /**
     * 展示一条提醒。返回是否真的弹了（0 级或权限被关时为 false，但消息本身照常 ack）。
     */
    public boolean show(String tag, String title, String text, int level, String url) {
        int lv = Levels.clamp(level);
        ensureChannels();
        if (!canNotify(lv)) {
            log.w("notify", "通知权限/渠道被关闭，L" + lv + " 只留档 tag=" + tag);
            return false;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String channel = channelFor(lv);
            Notification.Builder builder = newBuilder(channel);
            if (builder == null) return false;
            builder.setSmallIcon(android.R.drawable.ic_dialog_info);
            builder.setContentTitle(title == null || title.isEmpty() ? "dsh-kit" : title);
            builder.setContentText(text == null ? "" : text);
            builder.setAutoCancel(true);
            builder.setWhen(System.currentTimeMillis());
            builder.setGroup(GROUP);
            applyLegacyFlags(builder, lv);
            if (lv <= 2) setSilentFlag(builder);
            PendingIntent pending = pendingFor(url);
            if (pending != null) builder.setContentIntent(pending);
            try {
                builder.setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text));
            } catch (Throwable ignored) {
            }
            Notification notification = builder.build();
            synchronized (this) {
                tags.add(tag);
                while (tags.size() > 5) {
                    String old = tags.remove(0);
                    try {
                        nm.cancel(old, old.hashCode());
                    } catch (Throwable ignored) {
                    }
                }
            }
            nm.notify(tag, tag.hashCode(), notification);
            log.i("notify", "已弹出 " + Levels.describe(lv) + " tag=" + tag);
            return true;
        } catch (Throwable t) {
            log.e("notify/show", t);
            return false;
        }
    }

    /** API 26+ 的 setSilent(true)：双保险，确保静默档不会出声。 */
    private void setSilentFlag(Notification.Builder builder) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            builder.getClass().getMethod("setSilent", boolean.class)
                    .invoke(builder, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    /** API 25 及以下没有渠道：优先级/声音/震动只能直接设在通知上。 */
    private void applyLegacyFlags(Notification.Builder builder, int level) {
        if (android.os.Build.VERSION.SDK_INT >= 26) return;
        try {
            switch (Levels.bucket(level)) {
                case QUIET:
                    builder.setPriority(Notification.PRIORITY_MIN);
                    break;
                case CENTER:
                    builder.setPriority(Notification.PRIORITY_LOW);
                    break;
                case HEADS_UP:
                    builder.setPriority(Notification.PRIORITY_HIGH);
                    break;
                case VIBRATE:
                    builder.setPriority(Notification.PRIORITY_HIGH);
                    builder.setVibrate(new long[]{0, 180, 120, 180});
                    break;
                case ALERT:
                    builder.setPriority(Notification.PRIORITY_HIGH);
                    builder.setVibrate(new long[]{0, 180, 120, 180});
                    builder.setDefaults(Notification.DEFAULT_SOUND);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private Notification.Builder newBuilder(String channel) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Constructor<Notification.Builder> ctor = Notification.Builder.class
                        .getConstructor(Context.class, String.class);
                return ctor.newInstance(ctx, channel);
            }
            return new Notification.Builder(ctx);
        } catch (Throwable t) {
            try {
                return new Notification.Builder(ctx);
            } catch (Throwable t2) {
                log.e("notify/builder", t2);
                return null;
            }
        }
    }

    private PendingIntent pendingFor(String url) {
        try {
            Intent intent;
            if (url != null && !url.isEmpty()) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            } else {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setPackage(ctx.getPackageName());
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            log.e("notify/pending", t);
            return null;
        }
    }

    public void cancel(String tag) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && tag != null) nm.cancel(tag, tag.hashCode());
        } catch (Throwable ignored) {
        }
    }
}
