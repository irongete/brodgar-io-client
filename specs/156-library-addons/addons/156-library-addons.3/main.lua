-- 156.3 -- the export door: export, api, what crosses. Self-checking suite.

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

local t = {
  add  = function(x, y) return x + y end,
  echo = function(x) return x end,
  call = function(callback) return callback() end,
  same = function(a, b) return a == b end,
  boom = function() error("kaboom") end,
  sub  = { k = "v" },
  n    = 1,
}
local exported = false
local function run()
  pass, fail, manual = 0, 0, 0
  local addons = hafen.client():addons()
  local me = addons:get(ADDON.id)
  local timer = hafen.timer():after(600, function() end)
  if not exported then
    refuses("a handle in the export is refused naming its key and kind", "'icon' is a Timer", function() return addons:export({ icon = timer }) end)
    refuses("export wants a table", "t must be a table, got string", function() return addons:export("x") end)
    check("export chains", addons:export(t) == addons, "not the collection")
    exported = true
  else
    pass = pass + 3; out("[pass] (export refusals and the export itself: proved on the first run)")
  end
  refuses("a second export refuses", "already exported", function() return addons:export(t) end)
  local api = me:api()
  check("api is my copy, the same table twice, its functions wrappers",
        api ~= t and api == me:api() and rawequal(api.add, t.add) == false and api.add == api.add, tostring(api))
  check("a call goes through the door: add(1, 2)", api.add(1, 2) == 3, api.add(1, 2))
  refuses("the copy is read-only", "export is read-only", function() api.x = 1 end)
  local keys = 0
  for _ in pairs(api) do keys = keys + 1 end
  t.later = 1
  check("pairs walks it, and a later write to t is not in it", keys == 7 and api.later == nil and api.n == 1, keys)
  check("a nested table is a copy, read-only too", api.sub ~= t.sub and api.sub.k == "v" and pcall(function() api.sub.k = "w" end) == false, tostring(api.sub))
  local arg = { a = 1 }
  local back = api.echo(arg)
  check("an argument table crosses as a copy, both ways", back ~= arg and back.a == 1 and getmetatable(back) == "read-only", tostring(back))
  refuses("a handle argument is refused naming position and kind", "argument 1 is a Timer", api.echo, timer)
  do  -- LuaJ's error("kaboom") already carries its own "chunk.lua:LINE:" prefix, which why()'s strip would eat
      -- along with our label (it removes through the FIRST such prefix, wherever it falls); assert on the raw message.
    local ok, err = pcall(api.boom)
    check("an error inside is prefixed with the label",
          (not ok) and (tostring(err):find(ADDON.id .. ".boom: ", 1, true) ~= nil), tostring(err))
  end
  local callback = function() return "x" end
  check("a callback crosses and runs; the same function is the same wrapper", api.call(callback) == "x" and api.same(callback, callback) == true, tostring(api.call(callback)))
  timer:cancel()
  summary()
end
hafen.console():on("t156", function() run() end)
