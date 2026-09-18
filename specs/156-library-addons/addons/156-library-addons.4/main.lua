-- 156.4 -- writing a library: the guide. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local function out(line) hafen.log():write(line) end
local function check(name, ok, got)
  if ok then pass = pass + 1; out("[pass] " .. name)
  else fail = fail + 1; out("[fail] " .. name .. " -- got: " .. tostring(got)) end
end
local function why(err)                       -- LuaJ writes "main.lua:12 msg" (a space) for a Java refusal
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end
local function refuses(name, needle, fn, ...)  -- the call must fail AND say why
  local ok, err = pcall(fn, ...)
  local msg = ok and "<no error>" or why(err)
  check(name, (not ok) and (string.find(msg, needle, 1, true) ~= nil), msg)
end
local function summary()
  out(string.format("[summary] %d pass, %d fail, %d manual", pass, fail, manual))
end

-- the guide's library, verbatim
local open = 0

local function show(text)
  open = open + 1
  local box = hafen.ui():widget():size(240, 28):position(400, 40 + open * 32)
  hafen.ui():label():text(text):parent(box):position(8, 6)
  hafen.timer():after(3, function() box:destroy(); open = open - 1 end)
  return open
end

hafen.client():addons():export({
  show  = show,
  count = function() return open end,
})

-- the guide's consumer, its handle a parameter
local function notify(handle, text)
  local api = handle:api()                              -- the export, or nil
  if api then return api.show(text) else hafen.log():write(text); return "logged" end
end

local function run()
  pass, fail, manual = 0, 0, 0
  hafen.timer():after(0, function()                     -- a widget is built off the console's tree monitor
    local toast = hafen.client():addons():get(ADDON.id)
    local absent = hafen.client():addons():get("156-absent")
    local n = notify(toast, "Hola")
    check("show answers the count of open notices", n == 1, tostring(n))
    check("count reads one while the notice stands", toast:api().count() == 1, toast:api().count())
    check("without the library, notify falls back to the log", notify(absent, "Hola (logged)") == "logged", "no fallback")
    manual = manual + 1
    out("[manual] look at the top of the screen -- expect: a `Hola` notice for three seconds")
    hafen.timer():after(3.5, function()
      check("count reads zero after the notice's life", toast:api().count() == 0, toast:api().count())
      summary()
    end)
  end)
end
hafen.console():on("t156", function() run() end)
