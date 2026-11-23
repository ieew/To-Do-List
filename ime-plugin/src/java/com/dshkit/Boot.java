package com.dshkit;

import android.app.Activity;
import android.content.Context;

/**
 * 给 Lua 的入口垫片。
 *
 * 为什么需要它：LuaJ 里 `Class.forName(...)` 拿到的是 java.lang.Class 实例，
 * 在它上面调**被加载类自己的静态方法**并不可靠（Class 的方法才是实例方法）。
 * 这里用一个公开无参构造 + 实例方法，Lua 侧只碰实例调用，稳。
 *
 * 用法（Lua）：
 * <pre>
 * local cls = Class.forName("com.dshkit.Boot", true, loader)
 * local boot = cls:newInstance()
 * local kit = boot:bootService()          -- IME 脚本：自己找 TrimeService
 * local kit = boot:boot(activity)         -- 工具 Activity：用当前 Activity 兜底
 * kit:call("ui.panel")  /  boot:attach(activity)
 * </pre>
 */
public final class Boot {

    private Kit kit;

    public Boot() {
    }

    /** IME 脚本入口（scripts/dsh.lua）：必须能拿到 TrimeService。 */
    public Boot bootService() {
        this.kit = Kit.boot();
        return this;
    }

    /** 工具 Activity 入口（tools/<名>/main.lua）：拿不到服务就用这个 Context 降级。 */
    public Boot boot(Context ctx) {
        this.kit = Kit.bootFromContext(ctx);
        return this;
    }

    public void attach(Activity activity) {
        if (kit != null) kit.attachActivity(activity);
    }

    public Kit kit() {
        return kit;
    }

    public String version() {
        return Kit.VERSION;
    }
}
