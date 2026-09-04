-- 128.4 — the disposal seam retires a widget's gesture bindings. Self-checking suite.
--
-- The one thing here a program CAN read back is the lifetime of an arming, and that is what this suite is
-- built around: `w:draggable()` answers the grip your addon armed, so a grip that dies has to take the
-- arming with it and be read back as nothing. Before the retirement it read back a widget that no longer
-- existed. The rest guards the seam around it — one grip pressing two targets goes on pressing the one that
-- lived, the very next arming still reads back, and a drop on a target that has gone raises nothing.

local pass, fail, manual = 0, 0, 0
local S = {}
local LEFT = {}                       -- what is deliberately left on screen for the [manual] line
local SETTLE = 10                     -- ticks between a destroy and the drain that retires it

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A drop on a widget that has gone is a chaining no-op, so the check is that it did NOT raise.
local function raisesNothing(what, fn)
  local ok, err = pcall(fn)
  check(ok, what, ok and "<no error>" or tostring(err))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function drop(w)
  if (w ~= nil) and w:exists() then pcall(function() w:destroy() end) end
end

local function finish()
  if S.timer then S.timer:cancel() end
  for _, w in ipairs({S.box, S.t2, S.grip}) do drop(w) end
  S = {}
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function stage1()               -- the target died as a DESCENDANT, and the drains have run
  if S.n < SETTLE then return end
  check(not S.t1:exists(), "the target died with the parent that was destroyed", S.t1:exists())
  check(S.t2:draggable() == S.grip, "the grip still serves the target that lived", S.t2:draggable())
  raisesNothing("dropping the arming on the dead target raises nothing",
                function() S.t1:draggable(nil) end)
  S.grip:destroy()                    -- ...and now the arming ends from the other end
  S.n, S.stage = 0, 2
end

local function stage2()
  if S.n < SETTLE then return end
  check(S.t2:exists() and (S.t2:draggable() == nil),
        "the arming ends when the grip dies, and its live target reads back nothing", S.t2:draggable())
  local ui = hafen.ui()
  local win = ui:window():title("128.4"):position(220, 220)
  LEFT[#LEFT + 1] = win                 -- registered before anything can raise, so a bad round cleans up
  local grip = ui:button():parent(win):position(0, 0):size(130):text("drag me")
  win:pack()                            -- the window is exactly that button: no height of ours to clip its art
  win:draggable(grip)
  check(win:draggable() == grip, "a fresh arm on a new widget still reads back", win:draggable())
  manualCheck("press 'drag me' in the window titled 128.4 and move the pointer",
              "the window follows the pointer")
  finish()
end

local stages = {stage1, stage2}

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
  S.box = ui:widget():name("t1284-box"):size(200, 100):position(60, 60)
  S.t1 = ui:widget():parent(S.box):name("t1284-target"):size(40, 20):position(10, 10)
  S.t2 = ui:widget():name("t1284-other"):size(40, 20):position(300, 60)
  S.grip = ui:widget():name("t1284-grip"):size(20, 20):position(360, 60)
  S.t1:draggable(S.grip)
  S.t2:draggable(S.grip)              -- one grip, two targets: each binding stands on its own
  check(S.t1:draggable() == S.grip, "a target reads back the grip it was armed with", S.t1:draggable())
  check(S.t2:draggable() == S.grip, "one grip arms a second target too", S.t2:draggable())
  S.box:destroy()                     -- the target dies as a DESCENDANT: rdispose only, never remove()
  S.n, S.stage = 0, 1
  S.timer = hafen.timer():every(0, tick)
end

local function run()
  if S.timer then S.timer:cancel() end
  pass, fail, manual = 0, 0, 0        -- a second :t128 scores its own run, not both
  for _, w in ipairs(LEFT) do drop(w) end
  LEFT = {}
  S = {}
  hafen.timer():after(0, start)       -- the step, where a widget of the layer's tree may be built
end

hafen.console():on("t128", run)   -- the only way in: a suite does not start itself
