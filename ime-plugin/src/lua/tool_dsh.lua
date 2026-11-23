--[[
  dsh-kit 工具入口（输入法 → 工具 → dsh）  v4

  放在 /sdcard/Documents/rime/tools/dsh/main.lua
  工具列表只认 tools/<名字>/main.lua 这种目录结构。

  三条硬规矩（都是真机上踩出来的）：
  1. 这个 LuaJ 开了 LUA_JAVA_OO：Java 对象方法用**点号**（`act.getFilesDir()`），
     冒号会把对象自己当参数，抛 IllegalArgumentException。
  2. Android 14+ 拒绝加载**可写**的 dex：拷到私有目录后必须 `setReadOnly()`。
  3. 每点一次工具会新建一个 DexClassLoader，同一份类会得到**不同的静态字段** →
     Kit 单例失效、会跑出多个 MQTT 连接。所以 Boot 实例缓存在
     LuaApplication.setSharedData 里，按 dex 的 size+mtime 校验版本。
]]

local SCRIPTS = "/sdcard/Documents/rime/scripts"
local SRC_DEX = SCRIPTS .. "/dsh-kit.dex"
local LOG = "/sdcard/Documents/rime/logs/dsh.log"
local CACHE_KEY = "dsh.boot"
local STAMP_KEY = "dsh.stamp"

local function log(s)
    pcall(function()
        local f = io.open(LOG, "a")
        if f then
            f:write(os.date("%Y-%m-%d %H:%M:%S ") .. "[tool] " .. tostring(s) .. "\n")
            f:close()
        end
    end)
end

local function showFatal(act, msg)
    log("boot failed: " .. tostring(msg))
    pcall(function()
        local Builder = luajava.bindClass("android.app.AlertDialog$Builder")
        local b = luajava.new(Builder, act)
        b.setTitle("dsh-kit 启动失败")
        b.setMessage(tostring(msg) .. "\n\n日志: " .. LOG)
        b.show()
    end)
end

local act = activity or this
if act == nil then
    pcall(function()
        act = luajava.bindClass("com.androlua.LuaActivity").sActivity
    end)
end
if act == nil then
    log("没有 activity，无法启动")
    return nil
end

local ok, err = pcall(function()
    local File = luajava.bindClass("java.io.File")

    -- 1) dex 拷到应用私有目录（只读）
    local dir = File(act.getFilesDir(), "dsh-kit")
    dir.mkdirs()
    local dst = File(dir, "dsh-kit.dex")
    local src = File(SRC_DEX)
    if not src.exists() then
        error("找不到 " .. SRC_DEX)
    end
    if (not dst.exists()) or dst.length() ~= src.length() then
        if dst.exists() then dst.delete() end
        local fi = io.open(SRC_DEX, "rb")
        if not fi then error("读不到 " .. SRC_DEX) end
        local data = fi:read("*a")
        fi:close()
        local fo = io.open(dst.getAbsolutePath(), "wb")
        if not fo then error("写不了 " .. dst.getAbsolutePath()) end
        fo:write(data)
        fo:close()
    end
    pcall(function() dst.setReadOnly() end)

    local stamp = tostring(dst.length()) .. ":" .. tostring(dst.lastModified())

    -- 2) 复用进程里已有的 Boot（不然每开一次工具就多一个 Kit + 一条 MQTT 连接）
    local app = luajava.bindClass("com.androlua.LuaApplication").getInstance()
    local boot = nil
    local cachedStamp = app.getSharedData(STAMP_KEY)
    if cachedStamp ~= nil and tostring(cachedStamp) == stamp then
        boot = app.getSharedData(CACHE_KEY)
    end
    if boot ~= nil then
        log("复用已加载的 dsh-kit（stamp=" .. stamp .. "）")
    else
        local old = app.getSharedData(CACHE_KEY)
        if old ~= nil then
            pcall(function() old.kit().shutdown() end)
            pcall(function() app.setSharedData(CACHE_KEY, nil) end)
        end

        local opt
        pcall(function() opt = act.getCodeCacheDir().getAbsolutePath() end)
        if opt == nil then opt = act.getCacheDir().getAbsolutePath() end
        local loader = luajava.newInstance(
            "dalvik.system.DexClassLoader",
            dst.getAbsolutePath(), opt, "", act.getClassLoader()
        )
        local bootCls = loader.loadClass("com.dshkit.Boot")
        boot = luajava.new(bootCls)
        boot.boot(act)                     -- Boot.boot 返回 Boot 自己
        app.setSharedData(CACHE_KEY, boot)
        app.setSharedData(STAMP_KEY, stamp)
        log("新建 dsh-kit（stamp=" .. stamp .. "）")
    end

    dshkit = boot.kit()
    if dshkit == nil then error("Boot.kit() 返回空（boot 失败了？）") end
    boot.attach(act)
    dshkit.call("dsh.sync")
end)

if not ok then
    showFatal(act, err)
    return nil
end

log("tool boot ok v" .. tostring(dshkit.version()))
return nil
