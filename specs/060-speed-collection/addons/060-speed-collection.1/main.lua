-- 060.1 — hafen.speed() becomes the collection of speeds, and picking one is :set. Self-checking suite.
--
-- It declares the "speed.set" permission, which is the write half's own key: enabling this addon asks for it,
-- and reaching :set's ARGUMENT refusals at all is the proof the grant landed.

local pass, fail, manual = 0, 0, 0

local function strip(s)
  return (tostring(s):gsub("^.-%.lua:%d+:%s*", ""))
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. strip(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or strip(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The write, and its read-back. The SERVER owns Speedget.cur -- :set only sends what a click sends -- so
-- :current() catches up a round trip later. Poll it for up to two seconds, then put back what we found.
local function roundTrip(before, done)
  local speed = hafen.speed()
  local target = nil
  for _, sp in ipairs(speed:list()) do
    if sp ~= before then target = sp; break end
  end
  if not target then
    check(false, ":set picks another speed and :current() catches up (then restores)",
          "only one speed is selectable, so there is nothing else to pick")
    return done()
  end
  speed:set(target)
  local tries, t = 0, nil
  t = hafen.timer():every(0.25, function()
    tries = tries + 1
    local now = hafen.speed():current()
    local landed = (now == target)
    if landed or (tries >= 8) then
      t:cancel()
      check(landed, ("hafen.speed():set(%s) lands and :current() == it (then %s restored)")
            :format(tostring(target:name()), tostring(before:name())),
            "still " .. tostring(now and now:name()) .. " after 2s")
      pcall(function() hafen.speed():set(before) end)
      done()
    end
  end)
end

local function run()
  local speed = hafen.speed()

  -- §2.1: the section object IS the collection, one per addon. Everything below needs the selector, so this
  -- guard is the same check.
  check((speed == hafen.speed()) and (speed:get(0) ~= nil),
        "hafen.speed() IS the collection, the same object every call, and the selector is up",
        (speed ~= hafen.speed()) and "two different objects" or "no speed selector -- run this in the world")
  if speed:get(0) == nil then
    return finish()
  end

  -- :list() is EXACTLY the selectable speeds, crawl->sprint.
  local list = speed:list()
  local why = (#list == 0) and "the list is empty" or ""
  for i, sp in ipairs(list) do
    if sp:index() ~= (i - 1) then
      why = ("member %d has index %d"):format(i, sp:index())
    elseif not sp:available() then
      why = ("%s is in :list() but not :available()"):format(tostring(sp:name()))
    end
  end
  check(why == "", "every member of :list() is :available(), indices ascending from 0", why)
  check((speed:count() == #list) and (speed:find(list[1]:name()) == list[1]),
        ":count() and :find(name) enumerate the same set as :list()",
        ("count=%d, #list=%d"):format(speed:count(), #list))

  -- :get addresses a speed by its key -- all four, selectable or not -- and hands back an interned object.
  local function listed(sp)
    for _, m in ipairs(list) do
      if m == sp then return true end
    end
    return false
  end
  local sprint = speed:get(3)
  check((sprint ~= nil) and (sprint == speed:get(3)) and (sprint:index() == 3)
        and (sprint:available() == listed(sprint)),
        ":get(3) answers one interned Speed whether or not it is in :list()",
        tostring(sprint) .. " available=" .. tostring(sprint and sprint:available()))
  local byName, names = true, {}
  for i = 0, 3 do
    local sp = speed:get(i)
    names[#names + 1] = tostring(sp:name())
    if speed:get(sp:name()) ~= sp then byName = false end   -- the name it answers ADDRESSES it
  end
  check(byName and (speed:get("run") == speed:get(2)) and (speed:get("RUN") == speed:get(2))
        and (speed:get("nope") == nil),
        ':get(sp:name()) == sp and :get("run") == :get(2): a whole name, case-insensitive; a miss is nil',
        table.concat(names, "/") .. ", get(\"run\")=" .. tostring(speed:get("run")))

  -- The distinguished member IS a member, so `==` is the "am I on this one" test.
  local cur = speed:current()
  check((cur ~= nil) and listed(cur) and (speed:get(cur:index()) == cur),
        ":current() is == the member of :list() it names", tostring(cur))
  local t = cur:info()
  check((type(t) == "table") and (t.index == cur:index()) and (t.name == cur:name())
        and (t.available == cur:available()) and (t.current == true),
        "sp:info() carries index, name, available and current",
        type(t) == "table" and ("%s/%s/%s/%s"):format(tostring(t.index), tostring(t.name),
          tostring(t.available), tostring(t.current)) or type(t))

  -- The hard cut: each retired spelling raises naming its replacement.
  refuses("hafen.speed():max() is retired, naming :list()", function() return speed:max() end, ":list()")
  refuses("hafen.speed():name(n) is retired, naming :get", function() return speed:name(2) end, ":get")
  refuses("hafen.speed():current(n) is retired, naming :set", function() return speed:current(2) end, ":set")

  -- ...and every bad argument to the write is refused, each saying why.
  local bad = {}
  local function mustRaise(label, fn, want)
    local ok, err = pcall(fn)
    err = ok and "<no error>" or strip(err)
    if ok or (err:find(want, 1, true) == nil) then
      bad[#bad + 1] = label .. " -> " .. err
    end
  end
  mustRaise(":set(9)", function() speed:set(9) end, "0..3")
  mustRaise(':set("nope")', function() speed:set("nope") end, "no speed is called")
  mustRaise(":set(nil)", function() speed:set(nil) end, "must not be nil")
  mustRaise(":set({})", function() speed:set({}) end, "expected a Speed object")
  check(#bad == 0, ':set refuses 9, "nope", nil and a table, each saying why', table.concat(bad, " | "))
  refuses("# is refused on the collection", function() return #speed end, ":count() is how")

  -- A speed the server has locked is refused, and the refusal lists the ones you CAN pick.
  local locked = nil
  for i = 0, 3 do
    local sp = speed:get(i)
    if (sp ~= nil) and not sp:available() then locked = sp; break end
  end
  if locked then
    local ok, err = pcall(function() speed:set(locked) end)
    err = ok and "<no error>" or strip(err)
    check((not ok) and (err:find("not selectable", 1, true) ~= nil)
          and (err:find(list[1]:name(), 1, true) ~= nil),
          ("%s is not selectable, and :set refuses it naming the ones that are"):format(tostring(locked:name())),
          err)
  else
    manualCheck("no speed was locked this run -- re-run while sprint is locked",
                "refused, listing the selectable speeds")
  end

  roundTrip(cur, finish)
end

hafen.slash():register("t060-1", run)   -- the only way in: a suite does not start itself
