--[[
  dsh-kit 启动入口（L0，IME 脚本模式）  v0.8

  用途：给键位 / 脚本按键用（`command = "dsh.lua"`）。
  输入法自带的「工具」列表不吃 scripts/ 目录，那边用 tools/dsh/main.lua。

  实际的 boot 逻辑搬到了 dsh_boot.lua（主题 onStartInput 钩子也复用它）：
  进程复活 → 主题脚本自动拉起 Kit → 常驻通知回来；这里只负责再出状态面板。

  和工具脚本共用同一个进程级缓存（LuaApplication.setSharedData），
  所以键位和工具不会各起一套 Kit/MQTT 连接。
]]

local SCRIPTS = "/sdcard/Documents/rime/scripts"
local LOG = "/sdcard/Documents/rime/logs/dsh.log"

local function log(s)
    pcall(function()
        local f = io.open(LOG, "a")
        if f then
            f:write(os.date("%Y-%m-%d %H:%M:%S ") .. tostring(s) .. "\n")
            f:close()
        end
    end)
end

pcall(dofile, SCRIPTS .. "/dsh_boot.lua")

if dshkit == nil then
    -- 进程里已有主实例：让它出面板，别再新建一套
    local sent = pcall(function()
        local Intent = luajava.bindClass("android.content.Intent")
        local i = Intent("com.dshkit.ui.PANEL")
        i.setPackage(service.getPackageName())
        service.sendBroadcast(i)
    end)
    log(sent and "已请求主实例出面板" or "没有 service，出不了面板")
    return nil
end

-- 出状态面板，不上屏任何文字
dshkit.call("ui.panel")
return nil
