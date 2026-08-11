-- 035.2 — geometry: pad, and the insets that move content. Self-checking suite; see specs/testing/addon-suite.md.
--
-- This is the task where the automation pays. Everything C2 shipped before it is LOOKS, which only a person can
-- judge; `pad` is the first style property that MOVES something, and where a thing sits is a number. So every
-- claim below is arithmetic the rule itself predicts:
--
--   * a pad grows the window's OUTER size by exactly 2*pad, because the ctor's size is the CONTENT size and the
--     deco frames it -- get that direction backwards and every window silently shrinks;
--   * the content does not move at all while it happens (the frame grows outward around it);
--   * with a border, the window's size is (content + insets + 2*pad) EXACTLY -- an absolute number this suite
--     can name in advance, on any client, because a rule's pixels are raw pixels;
--   * removing the rule restores the previous numbers exactly, not approximately;
--   * a surface that owns no geometry ignores the property and raises nothing.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, destroys its probes and drops its sheet.

local pass, fail, manual = 0, 0, 0

-- This addon's one stylesheet: a selector names a rule on it, and :install() applies what it says.
local sheet = hafen.ui():sheet()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
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

local function xy(p) return ("%d,%d"):format(p.x, p.y) end

-- The chrome is a CHILD of its window (030), so both halves of the geometry are readable from Lua: the deco's
-- own position is the content area's origin inverted (Window.resize2 sets deco.c = contarea().ul.inv()), which
-- makes "where does content start" a number this suite can assert instead of a picture it has to describe.
local function decoOf(w)
  for _, kid in ipairs(w:children()) do
    local t = kid:type()
    if (t == "SkinDeco") or (t == "DefaultDeco") then return kid, t end
  end
end

local function contentStart(w)
  local d = decoOf(w)
  return d and { x = -d:position().x, y = -d:position().y }
end

local function contentOf(w)
  local deco = decoOf(w)
  for _, kid in ipairs(w:children()) do
    if kid ~= deco then return kid end
  end
end

local W, H = 90, 40                       -- the probe window's CONTENT size
local SL = { 12, 40, 12, 12 }             -- panel.png's slice: a top inset tall enough for the caption
local P = 6
local PANEL = "panel.png"
local DARK = { 26, 26, 28, 240 }

local function border()
  return { image = hafen.asset():get(PANEL), slice = SL }
end

-- ---- the run ------------------------------------------------------------------------------------

local w, bare, content
local base, basePos, baseContent, bareSz

local steps = {}

local function finish()
  if w then w:destroy() end
  if bare then bare:destroy() end
  sheet:drop()
  manualCheck("run ':t035-2 look', then open a window (Tab for the inventory) and read its edges",
    "content sits one pad inside the gold frame on every side, nothing overlaps the frame, and the caption"
    .. " still has its own band -- ':t035-2 look' again puts stock geometry back")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- EVERY step waits first. The swap and the repack happen in Window.tick, so a step that ran inline with the
-- write it is checking would read the state of the previous frame -- which is exactly what the first one did.
local function step(i)
  hafen.timer():after(0.35, function()
    local f = steps[i]
    if not f then return finish() end
    f()
    step(i + 1)
  end)
end

-- 1. a pad-only rule dresses the window and grows it outward, leaving the content exactly where it was.
steps[1] = function()
  local _, t = decoOf(w)
  eq("a pad-only rule is enough to dress a window", t, "SkinDeco")
  eq("a pad grows the window by twice itself, the content size unchanged",
     xy(w:size()), ("%d,%d"):format(base.x + 2 * P, base.y + 2 * P))
  eq("...and the content does not move while it happens: the frame grows OUTWARD",
     xy(content:rootPos()), baseContent)
  w:rule():pad(2 * P)                     -- a CHANGED pad: the deco stays, the window re-lays itself out
end

steps[2] = function()
  eq("changing the pad re-lays the window out without rebuilding its chrome",
     xy(w:size()), ("%d,%d"):format(base.x + 4 * P, base.y + 4 * P))
  w:rule():remove()
end

steps[3] = function()
  eq("dropping the pad restores the exact numbers it found",
     xy(w:size()) .. " @ " .. xy(w:position()), base.s .. " @ " .. basePos)
  w:rule():bg{ color = DARK }:border(border()):pad(P)
end

-- 2. the border's own insets ARE the frame margins, so the whole geometry is predictable from the rule alone.
steps[4] = function()
  eq("a framed window's size is content + insets + 2*pad, exactly",
     xy(w:size()), ("%d,%d"):format(W + SL[1] + SL[3] + 2 * P, H + SL[2] + SL[4] + 2 * P))
  eq("content starts one pad inside the border's own insets",
     xy(contentStart(w)), ("%d,%d"):format(SL[1] + P, SL[2] + P))
  eq("...and it STILL has not moved on screen", xy(content:rootPos()), baseContent)
  w:rule():remove()
end

steps[5] = function()
  eq("dropping a framed rule restores the stock geometry exactly",
     xy(w:size()) .. " @ " .. xy(w:position()), base.s .. " @ " .. basePos)
  sheet:load{ ["window.frame"] = { pad = P } }:install()     -- the SITE half: every window, not one named by hand
end

steps[6] = function()
  eq("a window.frame site rule pads every window the same way",
     xy(w:size()), ("%d,%d"):format(base.x + 2 * P, base.y + 2 * P))
  sheet:load{ ["chat"] = { pad = 8 } }:install()             -- a text site: it owns no geometry, so this moves nothing
end

steps[7] = function()
  eq("a pad on a site that owns no geometry is inert, and the window is stock again",
     xy(w:size()) .. " @ " .. xy(w:position()), base.s .. " @ " .. basePos)
  eq("a widget with no chrome to re-lay-out ignores a pad rather than erroring",
     xy(bare:size()) .. " @ " .. xy(bare:position()), bareSz)
end

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t035-2 reports its own counts, not the login's
  sheet:drop()

  -- the refusals: a pad is a number of pixels, and there is no meaning to give a negative one.
  refuses("a pad that is not a number is refused",
          function() sheet:load{ ["window.frame"] = { pad = "6" } }:install() end, "expected a number")
  refuses("a negative pad is refused",
          function() sheet:load{ ["window.frame"] = { pad = -2 } }:install() end, "cannot be negative")
  refuses("an unknown property now names pad among the ones this client ships",
          function() sheet:load{ ["window.frame"] = { padding = 6 } }:install() end, "\"pad\"")

  w = hafen.ui():window()
    :title("035.2 probe")
    :size(W, H)
    :position(8, 8)
  bare = hafen.ui():widget()
    :size(40, 20)
    :position(8, 240)
  content = contentOf(w)
  base = { x = w:size().x, y = w:size().y, s = xy(w:size()) }
  basePos = xy(w:position())
  baseContent = xy(content:rootPos())
  bareSz = xy(bare:size()) .. " @ " .. xy(bare:position())

  eq("a widget nothing styles resolves nothing", w:style(), nil)
  w:rule():pad(P)
  eq("widget:style() reports pad beside the paint properties", w:style() and w:style().pad, P)
  eq("widget:rule():pad() reads the same pad back through the other door", w:rule():pad(), P)
  bare:rule():pad(8)

  step(1)
end

-- ---- the parked state, for the [manual] line ------------------------------------------------------

local looking = false

local function look()
  looking = not looking
  if looking then
    sheet:load{ ["window.frame"] = { bg = { color = DARK }, border = border(), pad = P } }:install()
    hafen.log():write(":t035-2 look -> the framed theme is ON, geometry included. Look at where content sits, then"
      .. " ':t035-2 look' again (or :reload, or disabling this addon) for stock geometry.")
  else
    sheet:drop()
    hafen.log():write(":t035-2 look -> stock geometry restored.")
  end
end

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. This one keeps a
-- dressed, re-laid-out window alive for its whole round and bumps Fonts.gen() a dozen times doing it, so when
-- suites started themselves it reddened lines in the OTHER suite: 035.1 counted this window's SkinDeco among
-- the client's, and 034.2's textcache probe saw a second key for its own string. Its round stages ~2.5s.
hafen.slash():register("t035-2", function(args)
  if (args and args[1]) == "look" then look() else run() end
end)
