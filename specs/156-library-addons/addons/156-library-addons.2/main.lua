-- 156.2 -- dependencies mean what they say. Self-checking suite.

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

local function run()
  pass, fail, manual = 0, 0, 0
  local addons = hafen.client():addons()
  local me = addons:get(ADDON.id)
  local info = me:info()
  check("the loader took the optional minimum: I loaded, no reason", info ~= nil and info.status == "loaded" and info.reason == nil, info and (info.status .. "/" .. tostring(info.reason)))
  local absent = addons:get("156-absent")
  check("the absent optional dependency is a handle that does not exist", absent:exists() == false and absent:id() == "156-absent", tostring(absent:exists()))
  check("its api and info are nil", absent:api() == nil and absent:info() == nil, tostring(absent:api()) .. "/" .. tostring(absent:info()))
  check("the collection lists me and not it", addons:count(ADDON.id) == 1 and addons:count("156-absent") == 0, addons:count("156-absent"))
  manual = manual + 1
  out("[manual] hover this suite's row on the Installed tab -- expect: a tooltip line `Optional: 156-absent>=1.0.0`")
  summary()
end
hafen.console():on("t156", function() run() end)
