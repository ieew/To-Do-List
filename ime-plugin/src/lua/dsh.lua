--[[
  dsh-kit 启动入口（L0，IME 脚本模式）  v0.7

  用途：给键位 / 脚本按键用（`command = "dsh.lua"`）。
  输入法自带的「工具」列表不吃 scripts/ 目录，那边用 tools/dsh/main.lua。

  和工具脚本共用同一个进程级缓存（LuaApplication.setSharedData），
  所以键位和工具不会各起一套 Kit/MQTT 连接。

  三条硬规矩：点号调 Java 方法；私有 dex 要 setReadOnly；Boot 实例按 stamp 缓存。
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
            f:write(os.date("%Y-%m-%d %H:%M:%S ") .. tostring(s) .. "\n")
            f:close()
        end
    end)
end

if dshkit == nil then
    local ok, err = pcall(function()
        local svc = service
        if svc == nil then
            error("service 为空：输入法没在运行（工具列表请用 tools/dsh）")
        end

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
                app.setSharedData(CACHE_KEY, nil)
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
        pcall(function() service.sendMsgAux("dsh-kit 加载失败：" .. tostring(err)) end)
        return nil
    end
    log("boot ok v" .. tostring(dshkit.version()))
end

-- 出状态面板，不上屏任何文字
dshkit.call("ui.panel")
return nil
