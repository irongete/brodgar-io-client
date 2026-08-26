-- 112.6 — an anchor that crosses trees applies one monitor at a time. Self-checking suite.
-- Run it with :t112 while a character is in the world. Nothing here is manual.
--
-- The shape under test: an anchor names ANY widget, and this client holds a tree per session beside the
-- addon layer's — so a follower and its target are freely in different trees. Re-deriving one used to
-- take the follower's tree monitor with the target's still held, which is the nesting 112.2 refuses and
-- the shape the client deadlocked in. Every write below therefore proves two things at once: that the
-- follower moved to the place its anchor names, and that the call it moved from did not refuse.
--
-- Everything runs from hafen.timer():after — the engine step, holding no tree monitor. A console line
-- runs under the monitor of the tree whose chat dispatched it, which is family A and not this task.

local ID = "112-one-tree-monitor-at-a-time.6"

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The message a pcall came back with, with the file:line prefix taken off.
local function why(ok, err)
  if ok then
    return "<no error>"
  end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function place(p)
  return (p == nil) and "<nowhere>" or (p.x .. "," .. p.y)
end

-- Two {x=, y=} reads within a pixel of each other. A design pixel goes to the screen and back through
-- an interface scale that need not be a whole number, so nothing here compares for equality.
local function same(a, b)
  return (a ~= nil) and (b ~= nil) and (math.abs(a.x - b.x) <= 1) and (math.abs(a.y - b.y) <= 1)
end

-- Did the widget move by exactly (dx, dy) between these two reads?
local function moved(was, now, dx, dy)
  if (was == nil) or (now == nil) then
    return false
  end
  return (math.abs((now.x - was.x) - dx) <= 1) and (math.abs((now.y - was.y) - dy) <= 1)
end

local DX, DY = 32, 24        -- the delta every target is moved by, and every follower must repeat
local LINKS = 11             -- a chain longer than MAXDEPTH (8), so the far end must NOT follow
local STEP = 0.25            -- long enough for a build, an install and a removal drain to land

local st = {}

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

-- Give everything back: the sheet's levels first, then the two boxes and what is inside them.
local function cleanup()
  pcall(function() hafen.ui():sheet():release() end)
  local boxes = {st.boxL, st.boxS}
  for i = 1, 2 do
    local w = boxes[i]
    if (w ~= nil) and w:exists() then
      pcall(function() w:destroy() end)
    end
  end
end

-- 4. The link that was destroyed: the cascade must skip it rather than throw, the link before it must
--    still follow, and the link after it must be left exactly where it stood.
local function phase4()
  local was2, was4 = st.c[2]:position(), st.c[4]:position()
  local from = st.c[1]:position()
  local ok, err = pcall(function() st.c[1]:position(from.x + DX, from.y) end)
  check(ok, "a chain with a destroyed link re-derives instead of throwing", why(ok, err))
  check(moved(was2, st.c[2]:position(), DX, 0),
        "...the link before the destroyed one still follows", place(st.c[2]:position()))
  check(moved(was4, st.c[4]:position(), 0, 0),
        "...and the one after it is left where it stood", place(st.c[4]:position()))
  cleanup()
  report()
end

-- 3. Take the third link out. Its own `derived` entry goes with it on the removal seam's drain, which
--    runs on the step — hence the frame between this and phase4.
local function phase3()
  st.c[3]:destroy()
  hafen.timer():after(STEP, phase4)
end

-- 2. The whole of the task: two anchors crossing the layer/session boundary in opposite directions, and
--    a chain deeper than the cascade follows.
local function phase2()
  -- The two boxes stand at the same place in their own trees and every root in this client is the
  -- screen, so a follower anchored topleft with no offset reads back the very pair its target does.
  local wasL, wasS = st.folL:position(), st.folS:position()
  local fromS, fromL = st.tgtS:position(), st.tgtL:position()
  check(same(wasL, fromS), "a follower in the layer sits on its session-tree target's corner",
        place(wasL) .. " vs " .. place(fromS))
  check(same(wasS, fromL), "a follower in the character's tree sits on its layer target's corner",
        place(wasS) .. " vs " .. place(fromL))

  -- layer follower <- session-tree target
  local ok1, err1 = pcall(function() st.tgtS:position(fromS.x + DX, fromS.y + DY) end)
  check(ok1, "moving a session-tree target does not refuse a second tree monitor", why(ok1, err1))
  check(moved(wasL, st.folL:position(), DX, DY),
        "...and its follower in the addon layer re-derived, in the same call", place(st.folL:position()))

  -- session-tree follower <- layer target: the same edge, the other way about
  local ok2, err2 = pcall(function() st.tgtL:position(fromL.x + DX, fromL.y + DY) end)
  check(ok2, "moving a layer target does not refuse a second tree monitor", why(ok2, err2))
  check(moved(wasS, st.folS:position(), DX, DY),
        "...and its follower in the character's tree re-derived, in the same call",
        place(st.folS:position()))

  -- MAXDEPTH: eight links follow one write, and the chain ends where a cycle would not
  local was9, wasEnd = st.c[9]:position(), st.c[LINKS]:position()
  local from1 = st.c[1]:position()
  local ok3, err3 = pcall(function() st.c[1]:position(from1.x + DX, from1.y) end)
  check(ok3, "a chain of anchors re-derives without refusing", why(ok3, err3))
  check(moved(was9, st.c[9]:position(), DX, 0),
        "...the 8th link down still follows the write", place(st.c[9]:position()))
  check(moved(wasEnd, st.c[LINKS]:position(), 0, 0),
        "...and the 10th does not, so MAXDEPTH still ends what a cycle would not",
        place(st.c[LINKS]:position()))

  hafen.timer():after(STEP, phase3)
end

-- 1. The rules. Installed a frame after the build, so every surface is armed and in its tree before the
--    sheet's own sweep walks it.
local function phase1()
  local sheet = hafen.ui():sheet()
  sheet:rule("[name=" .. ID .. "/folL]"):anchor{ to = st.tgtS, at = "topleft" }
  sheet:rule("[name=" .. ID .. "/folS]"):anchor{ to = st.tgtL, at = "topleft" }
  for i = 2, LINKS do
    sheet:rule("[name=" .. ID .. "/c" .. i .. "]")
         :anchor{ to = st.c[i - 1], at = "topleft", offset = {8, 0} }
  end
  local ok, err = pcall(function() sheet:install() end)
  check(ok, "a sheet whose anchors cross trees installs", why(ok, err))
  hafen.timer():after(STEP, phase2)
end

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end

  -- Two containers, one per tree, at the same place. Everything under test hangs inside them and so is
  -- out of reach of the client's own off-screen clamp, which answers only for a widget the root or the
  -- HUD holds directly.
  st.boxL = hafen.ui():widget():size(420, 300):position(80, 80)
  st.boxS = hafen.ui():widget():parent(s:ui():root()):size(420, 300):position(80, 80)

  st.tgtS = hafen.ui():widget():parent(st.boxS):name("tgtS"):size(8, 8):position(10, 10)
  st.folL = hafen.ui():widget():parent(st.boxL):name("folL"):size(8, 8):position(0, 0)
  st.tgtL = hafen.ui():widget():parent(st.boxL):name("tgtL"):size(8, 8):position(10, 120)
  st.folS = hafen.ui():widget():parent(st.boxS):name("folS"):size(8, 8):position(0, 0)

  st.c = {}
  for i = 1, LINKS do
    st.c[i] = hafen.ui():widget():parent(st.boxL):name("c" .. i):size(6, 6):position(10, 200)
  end

  hafen.timer():after(STEP, phase1)
end

local function run()
  pass, fail = 0, 0                   -- a second :t112 scores its own run, not both
  cleanup()                           -- ...and starts from trees with none of the last one's surfaces
  st = {}
  hafen.timer():after(0, start)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
