-- 128.3 — the disposal seam retires a widget's layout record. Self-checking suite.
--
-- NON-REGRESSION, and it says so: no verb reports what the layout layer is tracking, or the listener it
-- installed on an anchor's target, and a widget that has been disposed announces nothing either way. What
-- this suite guards is the seam the retirement now runs inside — an anchor is applied, its target dies as a
-- DESCENDANT (a destroy the removal seam never sees), the follower is left exactly where it stood, and the
-- very next anchor still tracks the target it names. It fails if the retirement dropped the seam rather
-- than one widget's record.

local pass, fail, manual = 0, 0, 0
local S = {}
local WAIT = 120                      -- ticks a bounded wait gets before it is scored as a failure

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
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

local function finish()
  if S.timer then S.timer:cancel() end
  if S.sheet then pcall(function() S.sheet:release() end) end
  for _, w in ipairs({S.box, S.box2, S.flw}) do
    if (w ~= nil) and w:exists() then pcall(function() w:destroy() end) end
  end
  S = {}
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function stage1()               -- the anchor is applied: corner on corner with its target
  if same(at(S.flw), at(S.tgt)) then
    check(true, "the anchor is applied: the follower sits on its target")
    S.p0 = at(S.flw)
    S.box:destroy()                   -- the target dies as a DESCENDANT: rdispose only, never remove()
    S.n, S.stage = 0, 2
  elseif S.n > WAIT then
    check(false, "the anchor is applied: the follower sits on its target",
          fmt(at(S.flw)) .. " want " .. fmt(at(S.tgt)))
    finish()
  end
end

local function stage2()               -- ...and the drains have run
  if S.n < 10 then return end
  check(not S.tgt:exists(), "the target died with the parent that was destroyed", S.tgt:exists())
  check(same(at(S.flw), S.p0), "the follower stays exactly where its dead anchor left it",
        fmt(at(S.flw)) .. " want " .. fmt(S.p0))
  local ui = hafen.ui()
  S.box2 = ui:widget():name("box2"):size(200, 100):position(300, 300)
  S.tgt2 = ui:widget():parent(S.box2):name("target2"):size(40, 20):position(20, 20)
  S.rule:anchor{ to = S.tgt2, at = "topleft" }
  S.sheet:install()
  S.n, S.stage = 0, 3
end

local function stage3()               -- the seam still works: a fresh anchor onto a new target
  if same(at(S.flw), at(S.tgt2)) then
    check(true, "a fresh anchor onto a new target is applied")
    S.before = at(S.flw)
    S.tgt2:position(60, 40)           -- ...and now move the target under it
    S.n, S.stage = 0, 4
  elseif S.n > WAIT then
    check(false, "a fresh anchor onto a new target is applied",
          fmt(at(S.flw)) .. " want " .. fmt(at(S.tgt2)))
    finish()
  end
end

local function stage4()               -- the follower follows: it moved, and it moved to the target
  if same(at(S.flw), at(S.tgt2)) and not same(at(S.flw), S.before) then
    check(true, "the follower tracks the new target's move")
    S.p1 = at(S.flw)
    S.box2:destroy()                  -- and a second death retires the same way
    S.n, S.stage = 0, 5
  elseif S.n > WAIT then
    check(false, "the follower tracks the new target's move",
          fmt(at(S.flw)) .. " want " .. fmt(at(S.tgt2)) .. ", was " .. fmt(S.before))
    finish()
  end
end

local function stage5()
  if S.n < 10 then return end
  check(not S.tgt2:exists(), "the second target died with its parent too", S.tgt2:exists())
  check(same(at(S.flw), S.p1), "the follower stays put a second time",
        fmt(at(S.flw)) .. " want " .. fmt(S.p1))
  finish()
end

local stages = {stage1, stage2, stage3, stage4, stage5}

local function tick()
  S.n = S.n + 1
  local fn = stages[S.stage]
  if fn == nil then
    finish()
    return
  end
  fn()
end

local function start()
  local ui = hafen.ui()
  S.sheet = ui:sheet()
  S.box = ui:widget():name("box"):size(200, 100):position(100, 100)
  S.tgt = ui:widget():parent(S.box):name("target"):size(40, 20):position(10, 10)
  S.flw = ui:widget():name("follower"):size(30, 30)   -- no :position — the VERB level outranks the rule
  check(not same(at(S.flw), at(S.tgt)),
        "the follower starts somewhere the anchor has to move it from", fmt(at(S.flw)))
  S.rule = S.sheet:rule("[name=" .. tostring(S.flw:name()) .. "]")
  S.rule:anchor{ to = S.tgt, at = "topleft" }
  S.sheet:install()
  S.n, S.stage = 0, 1
  S.timer = hafen.timer():every(0, tick)
end

local function run()
  if S.timer then S.timer:cancel() end
  pass, fail, manual = 0, 0, 0        -- a second :t128 scores its own run, not both
  S = {}
  hafen.timer():after(0, start)       -- the step, where a widget of the layer's tree may be built
end

hafen.console():on("t128", run)   -- the only way in: a suite does not start itself
