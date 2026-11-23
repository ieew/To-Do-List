package com.dshkit;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * L1 桥接层：全项目唯一允许认识 com.osfans.trime.* 的类。
 *
 * 设计约定：
 * - 所有宿主调用都在这里做反射 + 缓存，宿主升级只改本文件；
 * - 反向依赖不存在：Kit/Ui 只依赖 Host 接口语义（本类的公有方法）；
 * - 任何宿主方法缺失都不抛异常，返回 null / false，由上层降级。
 */
public final class TrimeHost {

    private static final String TRIME = "com.osfans.trime.TrimeService";

    private final Object service;
    private final Context context;

    private Method mCommit;
    private Method mShowCustomView;
    private Method mSetKeyboard;
    private Method mSetKeyboardView;
    private Method mGetInputConnection;
    private Method mSetCandidates;
    private Method mSendMsgAux;
    private Method mGetCurrentPkg;

    private TrimeHost(Object service, Context ctx) {
        this.service = service;
        this.context = ctx;
        resolve();
    }

    /** 没有输入法服务时的降级宿主（工具 Activity 里跑的情况）：通知/网络照常，键盘相关全返回 false。 */
    private TrimeHost(Context ctx) {
        this.service = null;
        this.context = ctx;
    }

    public static TrimeHost local(Context ctx) {
        return new TrimeHost(ctx);
    }

    public boolean hasService() {
        return service != null;
    }

    /** 拿到宿主实例；IME 未运行时返回 null。 */
    public static TrimeHost acquire() {
        try {
            Class<?> c = Class.forName(TRIME);
            Object svc = c.getMethod("getInstance").invoke(null);
            if (!(svc instanceof Context)) return null;
            return new TrimeHost(svc, (Context) svc);
        } catch (Throwable t) {
            return null;
        }
    }

    private void resolve() {
        Class<?> c = service.getClass();
        mCommit = find(c, "commitText", CharSequence.class);
        mShowCustomView = find(c, "showCustomView", View.class);
        mSetKeyboard = find(c, "setKeyboard", String.class);
        mSetKeyboardView = find(c, "setKeyboard", View.class);
        mGetInputConnection = find(c, "getCurrentInputConnection");
        mSetCandidates = find(c, "setCandidates", ArrayList.class);
        mSendMsgAux = find(c, "sendMsgAux", String.class);
    }

    private static Method find(Class<?> c, String name, Class<?>... args) {
        try {
            Method m = c.getMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    public Context context() {
        return context;
    }

    public Object raw() {
        return service;
    }

    // ---- 输入 ----

    public InputConnection inputConnection() {
        try {
            return mGetInputConnection == null ? null : (InputConnection) mGetInputConnection.invoke(service);
        } catch (Throwable t) {
            return null;
        }
    }

    public String selectedText() {
        try {
            InputConnection ic = inputConnection();
            if (ic == null) return "";
            CharSequence s = ic.getSelectedText(0);
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public String beforeText(int n) {
        try {
            InputConnection ic = inputConnection();
            if (ic == null) return "";
            CharSequence s = ic.getTextBeforeCursor(n, 0);
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public boolean commit(String text) {
        try {
            if (mCommit == null || text == null) return false;
            mCommit.invoke(service, text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean setCandidates(ArrayList<String> items) {
        try {
            if (mSetCandidates == null) return false;
            mSetCandidates.invoke(service, items);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 界面 ----

    public boolean showCustomView(View v) {
        try {
            if (mShowCustomView == null || v == null) return false;
            mShowCustomView.invoke(service, v);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 收起自定义视图：恢复成当前键盘。 */
    public boolean restoreKeyboard() {
        try {
            String kb = currentKeyboardName();
            if (mSetKeyboard != null && kb != null) {
                mSetKeyboard.invoke(service, kb);
                return true;
            }
            if (mSetKeyboardView != null) {
                mSetKeyboardView.invoke(service, (Object) null);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private String currentKeyboardName() {
        try {
            Class<?> cfg = Class.forName("com.osfans.trime.Config");
            Object v = cfg.getMethod("getKeyboard").invoke(null);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前前台应用包名；宿主没提供这个方法就返回空（ignore_apps 不生效）。 */
    public String currentPackage() {
        if (service == null) return "";
        String[] candidates = {"getCurrentPackageName", "getCurrentPackage", "getInputPackageName"};
        for (String name : candidates) {
            try {
                Method m = service.getClass().getMethod(name);
                Object v = m.invoke(service);
                if (v != null) return v.toString();
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    public boolean toast(String msg) {
        try {
            if (mSendMsgAux == null) {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            mSendMsgAux.invoke(service, msg);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 自检：报告哪些宿主方法反射成功 ----

    public String probe() {
        if (service == null) return "local(no-service)";
        StringBuilder sb = new StringBuilder();
        sb.append("commitText=").append(mCommit != null);
        sb.append(" showCustomView=").append(mShowCustomView != null);
        sb.append(" setKeyboard=").append(mSetKeyboard != null);
        sb.append(" setCandidates=").append(mSetCandidates != null);
        sb.append(" sendMsgAux=").append(mSendMsgAux != null);
        sb.append(" inputConnection=").append(mGetInputConnection != null);
        sb.append(" keyboard=").append(currentKeyboardName());
        return sb.toString();
    }
}
