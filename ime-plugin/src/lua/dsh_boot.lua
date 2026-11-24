--[[
  dsh-kit 自动启动（不弹面板）  v1

  只做一件事：把 Kit 拉起来（MQTT、剪贴板、待办通知都在 Kit 里），
  然后让调用方自己决定要不要出界面。

  三个调用方：
  1. 主题 main.lua 的 onStartInput —— 输入法进程复活后第一次弹键盘时自动触发，
     这样常驻通知被杀掉后能自己回来；
  2. dsh.lua（候选栏 / 脚本按键）—— 拉起后再出状态面板；
  3. 别的 Lua 脚本想确保 Kit 在，也可以直接 dofile 本文件。

  三条硬规矩（同 dsh.lua，都是真机踩出来的）：
  1. LUA_JAVA_OO：Java 方法用**点号**调用；
  2. Android 14+ 拒绝加载可写 dex：私有副本必须 setReadOnly()；
  3. 每点一次脚本会新建一个 DexClassLoader：Boot 实例按 stamp 缓存在 sharedData。

  幂等：已经 boot 过就直接返回；跨 Lua state 靠 sharedData 里的 Boot 缓存复用，
  不会多开 MQTT 连接。
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
            f:write(os.date("%Y-%m-%d %H:%M:%S ") .. "[boot] " .. tostring(s) .. "\n")
            f:close()
        end
    end)
end

-- 同一个 state 里已经拿过 Kit 就不重复做
if dshkit ~= nil then
    return nil
end

local svc = service
if svc == nil then
    -- 主题脚本 / 工具 Activity 环境可能拿不到 service；没有 service 就不自动启动
    log("没有 service，跳过自动启动")
    return nil
end

local ok, err = pcall(function()
    local File = luajava.bindClass("java.io.File")

    local files = svc.getFilesDir()
    local dir = File(files, "dsh-kit")
    dir.mkdirs()
    local private = File(dir, "dsh-kit.dex")
    local src = File(SRC_DEX)
    if not src.exists() then
        error("找不到 " .. SRC_DEX)
    end
    if (not private.exists()) or private.length() ~= src.length() then
        if private.exists() then private.delete() end
        local fi = io.open(SRC_DEX, "rb")
        if not fi then error("读不到 " .. SRC_DEX) end
        local data = fi:read("*a")
        fi:close()
        local fo = io.open(private.getAbsolutePath(), "wb")
        if not fo then error("写不了 " .. private.getAbsolutePath()) end
        fo:write(data)
        fo:close()
    end
    pcall(function() private.setReadOnly() end)

    local stamp = tostring(private.length()) .. ":" .. tostring(private.lastModified())

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
        local opt = File(files, "code_cache/dsh-kit")
        opt.mkdirs()
        local loader = luajava.newInstance(
            "dalvik.system.DexClassLoader",
            private.getAbsolutePath(), opt.getAbsolutePath(), "", svc.getClassLoader()
        )
        local bootCls = loader.loadClass("com.dshkit.Boot")
        boot = luajava.new(bootCls)
        boot.bootService()
        app.setSharedData(CACHE_KEY, boot)
        app.setSharedData(STAMP_KEY, stamp)
        log("新建 dsh-kit（stamp=" .. stamp .. "）")
    end

    dshkit = boot.kit()
    if dshkit == nil then error("Boot.kit() 返回空（boot 失败了？）") end

    pcall(function()                               -- 顺手拷进私有目录
        dshkit.prepareDex(SRC_DEX)
    end)
end)

if not ok then
    log("boot failed: " .. tostring(err))
    return nil
end
log("boot ok v" .. tostring(dshkit.version()))
return dshkit
