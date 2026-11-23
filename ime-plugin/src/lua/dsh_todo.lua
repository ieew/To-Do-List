--[[
  dsh-kit 待办面板入口  v1

  两个调用方：
  1. 候选栏按钮（`preset_keys` 里 `command = "dsh_todo.lua"`）——跑在输入法服务里，有 `service`；
  2. 常驻通知点击——LuaActivity 拉起本脚本，没有 `service`，用 `activity` 兜底。

  最后都落到 `kit.call("todo.toggle")`：有则收起、无则唤出悬浮窗。
  面板是 application context 上的 overlay，Activity 结束后还在。

  三条硬规矩（同 dsh.lua / tool_dsh.lua，都是真机踩出来的）：
  1. LUA_JAVA_OO：Java 方法用**点号**调用（`act.getFilesDir()`）；
  2. Android 14+ 拒绝加载可写 dex：私有副本必须 `setReadOnly()`；
  3. 每点一次工具会新建一个 DexClassLoader：Boot 实例按 stamp 缓存在 sharedData。
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
            f:write(os.date("%Y-%m-%d %H:%M:%S ") .. "[todo] " .. tostring(s) .. "\n")
            f:close()
        end
    end)
end

local svc = service
local act = activity or this
if act == nil then
    pcall(function()
        act = luajava.bindClass("com.androlua.LuaActivity").sActivity
    end)
end

-- 两个上下文都没有：什么也做不了（不该发生）
if svc == nil and act == nil then
    log("没有 service 也没有 activity")
    return nil
end

local ok, err = pcall(function()
    local File = luajava.bindClass("java.io.File")
    local anchor = svc or act

    -- 1) dex 拷到私有目录（只读）
    local files = anchor.getFilesDir()
    local dir = File(files, "dsh-kit")
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

    -- 2) 复用进程里已加载的 Boot
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
        pcall(function() opt = anchor.getCodeCacheDir().getAbsolutePath() end)
        if opt == nil then opt = anchor.getCacheDir().getAbsolutePath() end
        local loader = luajava.newInstance(
            "dalvik.system.DexClassLoader",
            dst.getAbsolutePath(), opt, "", anchor.getClassLoader()
        )
        local bootCls = loader.loadClass("com.dshkit.Boot")
        boot = luajava.new(bootCls)
        if svc ~= nil then
            boot.bootService()
        else
            boot.boot(act)
        end
        app.setSharedData(CACHE_KEY, boot)
        app.setSharedData(STAMP_KEY, stamp)
        log("新建 dsh-kit（stamp=" .. stamp .. "）")
    end

    dshkit = boot.kit()
    if dshkit == nil then error("Boot.kit() 返回空（boot 失败了？）") end

    -- 3) 切换悬浮窗；通知点击拉起的 Activity 立刻收掉，避免停在工具页
    dshkit.call("todo.toggle")
    if act ~= nil then
        pcall(function() act.overridePendingTransition(0, 0) end)
        pcall(function() act.finish() end)
    end
end)

if not ok then
    log("todo boot failed: " .. tostring(err))
    pcall(function()
        if svc ~= nil then
            service.sendMsgAux("dsh 待办加载失败：" .. tostring(err))
        elseif act ~= nil then
            local Toast = luajava.bindClass("android.widget.Toast")
            Toast.makeText(act, "dsh 待办加载失败", Toast.LENGTH_SHORT).show()
            act.finish()
        end
    end)
end

return nil
