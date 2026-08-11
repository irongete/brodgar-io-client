-- 041.1 — Subs, Sub, and the bus. Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. Every subscription in the API now goes through ONE mechanism (Subs): a keyed
-- multimap with N handlers per key, fired in registration order, error-isolated, and charged to the
-- emitter's own profiling category. hafen.event():on(key, fn) is the first emitter over it and hands back
-- a Sub whose one verb is :off(), idempotent by construction. The bus's key set is CLOSED, so an unknown
-- key throws instead of being accepted and never firing (which is what it did before), and the four
-- lifecycle keys dropped the On prefix that :on already says -- OnLoad is Load, and the old spelling
-- throws naming the new one.
--
-- HOW IT PROVES DELIVERY. Update is the one bus key that fires on its own, every frame, so it is what the
-- delivery, ordering, isolation and unsubscribe checks ride on -- staged over three frames rather than
-- asserted from one call. Load and EnterWorld can only fire once, before any command could be typed, so
-- they are recorded at file-body time and judged by the run.
--
-- ONE EXPECTED NOISE LINE. The isolation check installs a handler that errors ONCE, so the run prints one
-- engine line "handler error: ... deliberate handler error ..." between the checks. That line is the check
-- working, not a failure.
--
-- READ-ONLY: it declares no permissions, builds no widgets, mutates no persistent state, and every
-- subscription it makes is ended by the run that made it.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function nop() end

-- The bus catalogue, as this task ships it: the four lifecycle keys, and the twenty-two that did not move
-- because PascalCase was already the bus's own spelling.
local LIFECYCLE = { "Load", "EnterWorld", "Update", "Disable" }
local UNCHANGED = {
  "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved",
  "MeterAdded", "MeterRemoved", "MeterChanged",
  "BuffAdded", "BuffRemoved", "BuffChanged",
  "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
  "KinChanged", "QuestAdded", "QuestDone", "MarkersChanged",
  "GhostClicked", "SpriteClicked", "ObjectClicked",
}
local RETIRED = {
  { "OnLoad", "Load" }, { "OnEnterWorld", "EnterWorld" },
  { "OnUpdate", "Update" }, { "OnDisable", "Disable" },
}

-- The two moments that happen once, before any slash command could be typed: recorded here, judged below.
-- Recording is not running -- the suite still prints nothing until the maintainer asks for it.
local sawLoad, sawEnter = false, false
hafen.event():on("Load", function() sawLoad = true end)
hafen.event():on("EnterWorld", function() sawEnter = true end)
-- ...and the third, which can only be observed by leaving the world: the [manual] line's own answer.
hafen.event():on("Disable", function()
  hafen.log():write("Disable fired -- the [manual] line of :t041-1")
end)

-- The delivery round's state (Update fires every frame, so this fills in between the stages).
local seen, errored, lastDt = {}, 0, nil
local subA, subBad, subB

local function myProfRow()
  for _, r in ipairs(hafen.client():profiling():addons()) do
    if r.id == "041-unified-events.1" then return r end
  end
end

-- ---- stage 3: what stopped, what did not, and where the cost landed ---------------------------------
local function stage3()
  local sawA, sawB = false, false
  for _, v in ipairs(seen) do
    if v == "a" then sawA = true else sawB = true end
  end
  check((not sawA) and sawB, "sub:off() stops delivery, and the other handler on the same key goes on"
        .. " firing", ("a=%s b=%s over %d frames"):format(tostring(sawA), tostring(sawB), #seen))

  -- The profiling split must survive the merge: every callback in the client is a Subs.fire now, so a
  -- single charge inside fire would flatten Addon.CATS' five columns into one. A bus handler costs
  -- `events`, exactly as it did before, and nothing this suite did costs `draw` or `widgets`.
  local r = myProfRow()
  if r == nil then
    manualCheck("arm profiling (Options > Client > Enable profiling, or ':profiler on') and run ':t041-1'"
                .. " again", "one more [pass]: the bus handler's cost lands in the events column")
  else
    check((r.calls.events >= 1) and (r.calls.draw == 0) and (r.calls.widgets == 0),
          "a bus handler's cost lands in p:addons()' events column and nowhere else",
          ("events=%s draw=%s widgets=%s"):format(tostring(r.calls.events), tostring(r.calls.draw),
                                                   tostring(r.calls.widgets)))
  end

  subB:off()
  subBad:off()
  manualCheck("run ':reload' (or untick this addon in the AddOns panel)",
              "one line 'Disable fired -- the [manual] line of :t041-1' -- the fourth lifecycle key firing"
              .. " for real, which no program in this env can watch")
  summary()
end

-- ---- stage 2: what one frame of Update delivered, and what off() does -------------------------------
local function stage2()
  check((seen[1] == "a") and (seen[2] == "b") and (type(lastDt) == "number"),
        "two handlers on one key both fire, in registration order, each given the payload",
        ("[1]=%s [2]=%s dt=%s"):format(tostring(seen[1]), tostring(seen[2]), tostring(lastDt)))
  check((errored >= 1) and (seen[2] == "b"),
        "a handler that errors is isolated: the handlers registered after it still ran",
        ("errors=%d, next handler=%s"):format(errored, tostring(seen[2])))

  subA:off()
  local ok = pcall(function() subA:off() end)
  check(ok, "a second sub:off() is a no-op rather than an error", ok)

  seen = {}
  hafen.timer():after(0.2, stage3)
end

-- ---- the run ----------------------------------------------------------------------------------------
local function run()
  pass, fail, manual = 0, 0, 0
  seen, errored, lastDt = {}, 0, nil

  -- 1. The handle: one verb, and a closed vocabulary that says so.
  local s = hafen.event():on("GobAdded", nop)
  check(tostring(s):find("Sub(", 1, true) ~= nil and type(s.off) == "function",
        "hafen.event():on(key, fn) hands back a Sub whose one verb is :off()",
        ("%s, off=%s"):format(tostring(s), type(s.off)))
  refuses("an unknown verb on a Sub throws naming what it does answer",
          function() return s:remove() end, ":off()")
  s:off()

  -- 2. The catalogue: all 26 accepted, each handing back a Sub, and every one of them ended again.
  local bad = nil
  for _, k in ipairs(LIFECYCLE) do
    local ok, sub = pcall(function() return hafen.event():on(k, nop) end)
    if ok and (type(sub.off) == "function") then sub:off() else bad = k end
  end
  check(bad == nil, "all 4 lifecycle keys are accepted without their On prefix", bad)
  bad = nil
  for _, k in ipairs(UNCHANGED) do
    local ok, sub = pcall(function() return hafen.event():on(k, nop) end)
    if ok and (type(sub.off) == "function") then sub:off() else bad = k end
  end
  check(bad == nil, "all 22 unchanged keys are still accepted, spelled exactly as before", bad)

  -- 3. The refusals: a moved key names its replacement, an unknown one is not silently accepted.
  for _, r in ipairs(RETIRED) do
    refuses("the retired '" .. r[1] .. "' throws naming '" .. r[2] .. "'",
            function() hafen.event():on(r[1], nop) end, "is now '" .. r[2] .. "'")
  end
  refuses("an unknown key throws instead of being accepted and never firing",
          function() hafen.event():on("GobAdded ", nop) end, "unknown event 'GobAdded '")

  -- 4. The two moments that already happened, recorded by the file body.
  check(sawLoad, "the Load key fired on this session's load", sawLoad)
  check(sawEnter, "the EnterWorld key fired on entering the world", sawEnter)

  -- 5. Arm the delivery round on the one key that fires by itself, and judge it two stages on.
  subA = hafen.event():on("Update", function(dt) seen[#seen + 1] = "a"; lastDt = dt end)
  subBad = hafen.event():on("Update", function()
    errored = errored + 1
    if errored == 1 then error("041.1 deliberate handler error -- the isolation check") end
  end)
  subB = hafen.event():on("Update", function() seen[#seen + 1] = "b" end)
  hafen.timer():after(0.2, stage2)
end

hafen.slash():register("t041-1", run)   -- the only way in: a suite does not start itself
