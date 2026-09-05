-- 128.6 — a per-widget map added later cannot reach one drain only. Self-checking suite.
--
-- The task itself is a checker, and a checker proves itself by being run: green on the tree, non-zero on a
-- seeded violation. What this suite is, is 128's OWN INTEGRATION CHECK, and it duplicates rather than
-- defers — assume no earlier suite is ever run again. All four subsystems the disposal seam now reaches
-- are exercised through the very API each of them hangs behind:
--
--   * a subscription on a widget         (Addon.widgetSubs)
--   * an anchor onto a widget            (Layout.derived, and the drag listener installed for the target)
--   * a drag arming between two widgets  (Addon.gestures, Gesture.arms)
--   * a subscription on an item          (Addon.itemSubs, and the per-item intern caches)
--
-- Each one is set up, proved to work, then killed the way the removal seam never sees — its ANCESTOR is
-- destroyed, so it dies through rdispose alone and never runs remove() itself. Each is then asserted
-- silent, and a fresh one of the same kind asserted to still work: the pair that fails if a retirement
-- dropped the seam rather than one widget's record.
--
-- The item half needs a container whose window really closes. The main inventory and the equipment grid
-- sit in a Hidewnd, which hides and destroys nothing, which is why the two [manual] lines ask for a chest,
-- a cupboard or a basket.

local pass, fail, manual = 0, 0, 0
local S = {}
local STEP   = 0.25       -- seconds per tick; a drain runs every FRAME, so one tick is dozens of them
local SETTLE = 2          -- ticks between a destroy and the drains that retire what named it
local WAIT   = 40         -- ticks (~10 s) an automatic bounded wait gets before it is scored a failure
local MWAIT  = 480        -- ticks (~2 min) a [manual] step is given before the run scores without it

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function quiet(fn)              -- did it NOT raise? a verb on a widget that has gone must answer
  return (pcall(fn))
end

local function at(w)                  -- where a widget sits on the screen, or nil once it is gone
  if w == nil then return nil end
  return w:rootPos()
end

local function same(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local function fmt(p)
  if p == nil then return "nil" end
  return "{" .. tostring(p.x) .. "," .. tostring(p.y) .. "}"
end

local function drop(w)
  if (w ~= nil) and w:exists() then pcall(function() w:destroy() end) end
end

-- Named, never listed by value: a nil in the middle of a table constructor ends ipairs there, and half
-- these fields are nil on any round that stopped early — which is exactly the round that must still tidy up.
local BUILT = {"boxA", "chA", "fresh", "boxB", "tgt", "box2", "tgt2", "flw",
               "boxC", "t1", "t2", "grip", "w3", "grip3"}
local SUBS = {"subA", "subF", "subI", "subJ"}

local function finish()
  if S.timer then S.timer:cancel() end
  if S.sheet then pcall(function() S.sheet:release() end) end
  for _, k in ipairs(BUILT) do drop(S[k]) end
  for _, k in ipairs(SUBS) do
    if S[k] ~= nil then pcall(function() S[k]:off() end) end
  end
  S = {}
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function step(to)               -- move to the stage named, with its own tick count
  S.n, S.stage = 0, to
end

-- ---- A: a subscription on a widget ------------------------------------------------------------------

local function a1()                   -- it fires while the widget lives
  if S.upA > 0 then
    check(true, "a subscription on a widget fires while it lives")
    S.stillA = S.upA
    S.boxA:destroy()                  -- the subscribed widget dies as a DESCENDANT: rdispose, never remove()
    step(2)
  elseif S.n > WAIT then
    check(false, "a subscription on a widget fires while it lives", S.upA)
    finish()
  end
end

local function a2()                   -- ...and the death leaves it silent, announced once, still endable
  if S.n < SETTLE then return end
  local endable = quiet(function() S.subA:off() S.subA:off() end)
  check((S.upA == S.stillA) and (S.rmA == 1) and endable and not S.chA:exists(),
        "...and the widget dying as a descendant leaves it silent, announced once, and still endable",
        "updates+" .. (S.upA - S.stillA) .. " removed=" .. S.rmA .. " off=" .. tostring(endable))
  S.subA = nil
  S.fresh = hafen.ui():widget():name("t1286-fresh"):size(10, 10):position(20, 20)
  S.upF = 0
  S.subF = S.fresh:on("Update", function() S.upF = S.upF + 1 end)
  step(3)
end

local function a3()                   -- ...and the machinery it wrote to is unharmed
  if S.upF > 0 then
    check(true, "a fresh subscription on a fresh widget fires")
  elseif S.n <= WAIT then
    return
  else
    check(false, "a fresh subscription on a fresh widget fires", S.upF)
  end
  local ui = hafen.ui()
  S.sheet = ui:sheet()
  S.boxB = ui:widget():name("t1286-boxB"):size(200, 100):position(100, 100)
  S.tgt = ui:widget():parent(S.boxB):name("t1286-target"):size(40, 20):position(10, 10)
  S.flw = ui:widget():name("t1286-follower"):size(30, 30)   -- no :position — the rule is what moves it
  S.rule = S.sheet:rule("[name=" .. tostring(S.flw:name()) .. "]")   -- read back: a name is <addon>/<name>
  S.rule:anchor{ to = S.tgt, at = "topleft" }
  S.sheet:install()
  step(4)
end

-- ---- B: an anchor onto a widget ---------------------------------------------------------------------

local function b1()                   -- the anchor is applied: corner on corner with its target
  if same(at(S.flw), at(S.tgt)) then
    check(true, "an anchor is applied: the follower sits on its target")
    S.p0 = at(S.flw)
    S.boxB:destroy()                  -- the anchor's TARGET dies as a descendant
    step(5)
  elseif S.n > WAIT then
    check(false, "an anchor is applied: the follower sits on its target",
          fmt(at(S.flw)) .. " want " .. fmt(at(S.tgt)))
    finish()
  end
end

local function b2()                   -- ...and the follower is left exactly where its dead anchor left it
  if S.n < SETTLE then return end
  check((not S.tgt:exists()) and same(at(S.flw), S.p0),
        "...and the target dying as a descendant leaves the follower exactly where it stood",
        fmt(at(S.flw)) .. " want " .. fmt(S.p0))
  local ui = hafen.ui()
  S.box2 = ui:widget():name("t1286-box2"):size(200, 100):position(320, 300)
  S.tgt2 = ui:widget():parent(S.box2):name("t1286-target2"):size(40, 20):position(20, 20)
  S.rule:anchor{ to = S.tgt2, at = "topleft" }
  S.sheet:install()
  step(6)
end

local function b3()                   -- a fresh anchor onto a new target, and then that target moves
  if same(at(S.flw), at(S.tgt2)) then
    S.before = at(S.flw)
    S.tgt2:position(60, 45)
    step(7)
  elseif S.n > WAIT then
    check(false, "a fresh anchor tracks its new target's move",
          fmt(at(S.flw)) .. " never reached " .. fmt(at(S.tgt2)))
    finish()
  end
end

local function b4()                   -- ...and the follower went with it
  if same(at(S.flw), at(S.tgt2)) and not same(at(S.flw), S.before) then
    check(true, "a fresh anchor tracks its new target's move")
  elseif S.n <= WAIT then
    return
  else
    check(false, "a fresh anchor tracks its new target's move",
          fmt(at(S.flw)) .. " want " .. fmt(at(S.tgt2)) .. ", was " .. fmt(S.before))
  end
  local ui = hafen.ui()
  S.boxC = ui:widget():name("t1286-boxC"):size(200, 100):position(60, 420)
  S.t1 = ui:widget():parent(S.boxC):name("t1286-t1"):size(40, 20):position(10, 10)
  S.t2 = ui:widget():name("t1286-t2"):size(40, 20):position(320, 420)
  S.grip = ui:widget():name("t1286-grip"):size(20, 20):position(380, 420)
  S.t1:draggable(S.grip)
  S.t2:draggable(S.grip)              -- one grip, two targets: each binding stands on its own
  check(S.t1:draggable() == S.grip, "a target reads back the grip it was armed with", S.t1:draggable())
  S.boxC:destroy()                    -- the armed TARGET dies as a descendant
  step(8)
end

-- ---- C: a drag arming between two widgets -----------------------------------------------------------

local function c1()                   -- the dead target's binding goes; the grip's other one does not
  if S.n < SETTLE then return end
  local droppable = quiet(function() S.t1:draggable(nil) end)
  check((not S.t1:exists()) and (S.t2:draggable() == S.grip) and droppable,
        "...and the target dying as a descendant leaves the grip serving the one that lived, and droppable",
        tostring(S.t2:draggable()) .. " drop=" .. tostring(droppable))
  local ui = hafen.ui()
  S.w3 = ui:widget():name("t1286-w3"):size(40, 20):position(320, 470)
  S.grip3 = ui:widget():name("t1286-grip3"):size(20, 20):position(380, 470)
  S.w3:draggable(S.grip3)
  check(S.w3:draggable() == S.grip3, "a fresh arm on a fresh widget reads back", S.w3:draggable())
  S.known = {}
  for _, w in ipairs(S.s:ui():matchAll("inventory")) do S.known[w] = true end
  manualCheck("open a container holding at least one item (a chest, a cupboard, a basket)",
              "the suite's next line names an item of its own")
  step(9)
end

-- ---- D: a subscription on an item -------------------------------------------------------------------

local function itemsOf(w)
  if w == nil then return {} end
  local ok, list = pcall(function() return w:items():list() end)
  return ok and list or {}
end

-- The container the maintainer opens is the one that was NOT there when we asked, and that test is the
-- whole filter: a tree with nothing open already holds containers of its own — the backpack, the worn
-- equipment grid, a ContentsWindow per stack — none of which anyone can close on cue.
local function d1()
  for _, w in ipairs(S.s:ui():matchAll("inventory")) do
    if not S.known[w] then
      local it = itemsOf(w)[1]
      if it ~= nil then
        S.item = it
        S.subI = it:on("Changed", function() end)
        check(S.subI ~= nil, "an item in the container the maintainer opened is subscribed",
              tostring(w:type()) .. " holding " .. tostring(it:res()))
        manualCheck("close that container's window",
                    "the suite's next line reads [pass] ...and the item dying with it leaves the "
                      .. "subscription ended and still endable")
        step(10)
        return
      end
    end
  end
  if S.n > MWAIT then
    check(false, "an item in the container the maintainer opened is subscribed", "<no container opened>")
    finish()
  end
end

local function d2()                   -- the item was DESTROYED with its window, never removed from it
  if S.item:exists() then
    if S.n < MWAIT then return end
    check(false, "...and the item dying with it leaves the subscription ended and still endable",
          "<the container is still open>")
    finish()
    return
  end
  local endable = quiet(function() S.subI:off() S.subI:off() end)
  check(endable, "...and the item dying with it leaves the subscription ended and still endable", endable)
  S.subI = nil
  local live = itemsOf(S.s:ui():inventory())[1]
  if live == nil then
    check(false, "a fresh subscription on a live item works", "<no item in the backpack>")
  else
    S.subJ = live:on("Changed", function() end)
    check(S.subJ ~= nil, "a fresh subscription on a live item works", S.subJ)
  end
  finish()
end

local stages = {a1, a2, a3, b1, b2, b3, b4, c1, d1, d2}

local function tick()
  S.n = S.n + 1
  local fn = stages[S.stage]
  if fn == nil then finish() else fn() end
end

local function start()
  S.s = hafen.session():current()
  if S.s == nil then
    check(false, "a character is logged in", "<no session>")
    finish()
    return
  end
  local ui = hafen.ui()
  S.boxA = ui:widget():name("t1286-boxA"):size(200, 100):position(100, 20)
  S.chA = ui:widget():parent(S.boxA):name("t1286-child"):size(10, 10):position(10, 10)
  S.upA, S.rmA = 0, 0
  S.subA = S.chA:on("Update", function() S.upA = S.upA + 1 end)
  S.chA:on("Removed", function() S.rmA = S.rmA + 1 end)
  step(1)
  S.timer = hafen.timer():every(STEP, tick)
end

local function run()
  if S.timer then S.timer:cancel() end
  if S.sheet then pcall(function() S.sheet:release() end) end
  pass, fail, manual = 0, 0, 0        -- a second :t128 scores its own run, not both
  S = {}
  hafen.timer():after(0, start)       -- the step, where a widget of the layer's tree may be built
end

hafen.console():on("t128", run)   -- the only way in: a suite does not start itself
