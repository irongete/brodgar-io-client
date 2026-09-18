-- 156.1 -- hafen.client():addons(): the collection and the handle. Self-checking suite.

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
  check("the collection is one object per addon", addons == hafen.client():addons(), "two objects")
  local me = addons:get(ADDON.id)
  check("my own handle exists and answers my id", me:exists() == true and me:id() == ADDON.id, tostring(me:exists()) .. "/" .. tostring(me:id()))
  local info = me:info()
  check("my info reads loaded, no reason, and spells my manifest",
        info ~= nil and info.status == "loaded" and info.reason == nil and info.version == "0.1.0"
          and info.name == "156.1 suite" and info.author == "brodgar",
        info and (tostring(info.status) .. "/" .. tostring(info.reason) .. "/" .. tostring(info.version) .. "/" .. tostring(info.name)))
  local ghost = addons:get("156-no-such-addon")
  check("an unknown id is a handle that does not exist", ghost:exists() == false and ghost:id() == "156-no-such-addon", tostring(ghost:exists()))
  check("its info and api are nil", ghost:info() == nil and ghost:api() == nil, tostring(ghost:info()) .. "/" .. tostring(ghost:api()))
  check("the same handle twice, and tostring names it", ghost == addons:get("156-no-such-addon") and tostring(ghost) == "Addon(156-no-such-addon)", tostring(ghost))
  local listed = false
  for _, handle in ipairs(addons:list()) do if handle == me then listed = true end end
  check("list holds my handle by identity", listed and addons:count() >= 1, tostring(addons:count()))
  check("a string filter is a substring test on the id", #addons:list("156-library") >= 1 and addons:count("156-no-such") == 0, addons:count("156-no-such"))
  check("a function filter sees the handle", addons:find(function(handle) return handle:id() == ADDON.id end) == me, "not found")
  refuses("pairs on the collection names list()", ":list()", pairs, addons)
  refuses("# on the collection names list()", ":list()", function() return #addons end)
  refuses("get without an id names it", "id is required", function() return addons:get() end)
  refuses("get with a number refuses", "expected an addon id, got number", function() return addons:get(1) end)
  refuses("an unknown verb is refused naming the handle", "has no verb 'nope'", function() return me:nope() end)
  refuses("a surplus argument is refused", "takes no arguments", function() return me:info(1) end)
  check("nothing is exported yet: my api is nil", me:api() == nil, tostring(me:api()))
  summary()
end
hafen.console():on("t156", function() run() end)
