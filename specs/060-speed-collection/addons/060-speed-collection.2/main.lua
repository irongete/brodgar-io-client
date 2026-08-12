-- 060.2 — hafen.speed(), as the reference page now states it. Self-checking suite.
--
-- This addon declares NO permission at all, and that is the claim: hafen.speed():set() must refuse,
-- naming the key "speed.set" -- and the refusal must not carry a retired key, which is what proves the
-- rename reached the gate and not only the docs. Everything around it walks the spellings the page
-- shows -- :list :count :find :get (by index and by name) :current :info, sp:available -- so the page
-- and the client are checked against each other, and re-asserts the retired spellings.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The message a call raised, with the "file.lua:12:" prefix trimmed, or nil when it did not raise.
local function errOf(fn)
  local ok, err = pcall(fn)
  if ok then
    return nil
  end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local err = errOf(fn) or "<no error>"
  check(err:find(wantMsg, 1, true) ~= nil, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  local speed = hafen.speed()

  -- The gate. This addon asked for nothing, so the write is refused before anything is sent, and the
  -- key it names is the one the catalogue and the permissions page carry today.
  local gate = errOf(function() speed:set(1) end) or "<no error>"
  check(gate:find("speed.set", 1, true) ~= nil, "an addon that declared nothing is refused, naming speed.set", gate)
  check(gate:find("speed.current", 1, true) == nil, "the refusal carries no retired key", gate)

  -- :list() is EXACTLY the selectable speeds, crawl first -- so everything in it is :available().
  local list = speed:list()
  local ok, why = #list > 0, "the speed selector is not up"
  for i, sp in ipairs(list) do
    if sp:index() ~= i - 1 then
      ok, why = false, "index " .. sp:index() .. " sits at place " .. i
    elseif not sp:available() then
      ok, why = false, "index " .. sp:index() .. " is in list() but not :available()"
    end
  end
  check(ok, "list() is the selectable speeds, ascending from 0, each available", why)

  -- :count() and :find() enumerate that same set.
  local first = speed:find(function() return true end)
  check((speed:count() == #list) and (first == list[1]), "count() and find() agree with list()",
        speed:count() .. " vs " .. #list)

  -- :get(key) addresses all four, selectable or not, by index or by whole display name in either case.
  local pick = speed:get(2)
  local byName = pick and speed:get(pick:name())
  local byShout = pick and speed:get(pick:name():upper())
  check((speed:get(0) ~= nil) and (speed:get(3) ~= nil) and (byName == pick) and (byShout == pick)
        and (speed:get(2) == pick), "get(key) reaches all four by index and by whole name, either case",
        tostring(pick))

  -- :current() is the very member list() holds -- identity is the "am I on this one" test.
  local cur = speed:current()
  check((cur ~= nil) and (cur == speed:get(cur:index())) and (cur == list[cur:index() + 1]),
        "current() is == the member of list() it names", tostring(cur))

  -- A key of the right shape that names no speed is a plain nil; a key of another type raises.
  local miss = (speed:get(9) == nil) and (speed:get("nope") == nil)
  local bad = errOf(function() return speed:get({}) end) or "<no error>"
  check(miss and (bad:find("index 0..3", 1, true) ~= nil), "a miss is nil; a key of another type raises", bad)

  -- The Speed object's live reads, against the selector this run actually found.
  local sp = speed:get(1)
  check((sp:index() == 1) and (type(sp:name()) == "string") and (sp:exists() == true)
        and (sp:available() == (1 < speed:count())),
        "sp:index()/:name()/:available()/:exists() read the live selector", tostring(sp))

  -- :info() is the documented snapshot, and its four fields.
  local info = cur and cur:info() or {}
  check((info.index == (cur and cur:index())) and (type(info.name) == "string")
        and (info.available == true) and (info.current == true),
        "info() carries index, name, available, current", tostring(info.index) .. " " .. tostring(info.name))

  -- The hard cut: each retired spelling raises, naming what to write instead.
  refuses("the retired :max() names :list()", function() return speed:max() end, "list()")
  local nm = errOf(function() return speed:name(2) end) or "<no error>"
  local cn = errOf(function() return speed:current(2) end) or "<no error>"
  check((nm:find(":get", 1, true) ~= nil) and (cn:find(":set", 1, true) ~= nil),
        "the retired :name(n) names :get, and :current(n) names :set", nm .. " || " .. cn)
  refuses("# is refused on the collection, naming :count()", function() return #speed end, ":count()")

  manualCheck("run :hello and read its speed line",
              "the speed you are on with its name and index, then the selectable ones listed")
  manualCheck("enable walker, then run :walker speed 1",
              "the character drops to Walk and the HUD's speed selector moves to the second icon")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The selector streams in a beat after entering the world. Wait a bounded moment for it rather than
-- scoring a run against a HUD that is not up yet.
local function start(tries)
  if (hafen.speed():count() == 0) and (tries > 0) then
    hafen.timer():after(0.5, function() start(tries - 1) end)
    return
  end
  run()
end

hafen.slash():register("t060-2", function() start(4) end)   -- the only way in: a suite does not start itself
