-- 167.1 — a place an addon writes on the screen follows the screen. Self-checking suite.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The rule, as the client keeps it: where the centre stands as a fraction of the parent per axis, a gap of 10 px
-- or less to an edge taken as that edge (0 or 1), and an axis with no free space keeping the fraction it had.
-- Back again, a fraction stands the widget whole on the parent when it fits.
local MAGNET, SLACK = 10, 2

local function frac(c, parent, widget, was)
  local free = parent - widget
  if free <= 0 then return was end
  if c <= MAGNET then return 0 end
  if c >= free - MAGNET then return 1 end
  return (c + widget / 2) / parent
end

local function place(f, parent, widget)
  if f <= 0 then return 0 end
  if f >= 1 then return parent - widget end
  local c = math.floor(f * parent - widget / 2 + 0.5)
  return math.max(math.min(0, parent - widget), math.min(math.max(0, parent - widget), c))
end

local function near(a, b)
  return math.abs(a - b) <= SLACK
end

-- A widget's place and box, in design pixels.
local function box(widget)
  local position, size = widget:position(), widget:size()
  return { x = position.x, y = position.y, w = size.w, h = size.h }
end

local function str(b)
  return b.x .. "," .. b.y
end

-- Where the rule puts a widget that stood at `b` in a parent `from`, once the parent is `to`.
local function predict(b, from, to)
  return { x = place(frac(b.x, from.w, b.w, 0), to.w, b.w), y = place(frac(b.y, from.h, b.h, 0), to.h, b.h) }
end

local function at(b, want)
  return near(b.x, want.x) and near(b.y, want.y)
end

local session, hud
local built = {}
local finished = false

local function finish()
  if finished then return end
  finished = true
  if hud then pcall(function() hud:size(nil) end) end
  for _, widget in ipairs(built) do
    pcall(function() widget:destroy() end)
  end
  built = {}
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Each step inside pcall: whatever throws, the HUD gets its size back and the widgets go.
local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function keep(widget)
  built[#built + 1] = widget
  return widget
end

local function square(parent, w, h)
  local widget = keep(hafen.ui():widget():size(w, h))
  if parent then widget:parent(parent) end
  widget:stock{ bg = { color = { 128, 128, 128, 200 } } }
  return widget
end

-- The manual half: the layer follows the game window itself, which only the maintainer can resize.
local function layerStep()
  local screen0 = session:ui():root():size()
  local from = { w = screen0.w, h = screen0.h }
  local middle = square(nil, 100, 40):position(math.floor(from.w * 0.3), math.floor(from.h * 0.6))
  local corner = square(nil, 100, 40):position(from.w - 100, 0)
  local placed = { middle = box(middle), corner = box(corner) }

  local panel = keep(hafen.ui():window():title("167"):size(300, 70))
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  local hint = hafen.ui():label():parent(panel):position(8, 44):text("Resize the game window, then press Done.")
  manualCheck("resize the game window (drag its border or maximise it) and press Done in window 167",
    "the next line scores it")

  done:on("Pressed", function()
    after(0, function()
      local screen = session:ui():root():size()
      local to = { w = screen.w, h = screen.h }
      if to.w == from.w and to.h == from.h then
        hint:text("The game window still has its size.")
        return
      end
      local wantMiddle = predict(placed.middle, from, to)
      local nowMiddle, nowCorner = box(middle), box(corner)
      check(at(nowMiddle, wantMiddle) and at(nowCorner, { x = to.w - 100, y = 0 }),
        "in the layer, a :position keeps its fraction (" .. str(wantMiddle) .. ") and one against the top-right"
          .. " corner stays on it (" .. (to.w - 100) .. ",0)",
        str(nowMiddle) .. " " .. str(nowCorner))
      finish()
    end)
  end)
  skip:on("Pressed", function()
    after(0, function()
      check(false, "the layer follows a resize of the game window", "skipped")
      finish()
    end)
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  built = {}
  session = hafen.session():current()
  hud = session and session:ui():match("@GameUI")
  if not hud then
    check(false, "a character is in the world", "no HUD -- run :t167 logged in")
    return finish()
  end
  local size = hud:size()
  local full = { w = size.w, h = size.h }
  local shrunk = { w = full.w - 200, h = full.h - 150 }
  local x, y = math.floor(full.w * 0.55), math.floor(full.h * 0.45)

  -- On the HUD: one in the middle, one against the bottom-right corner, one sized after its place, and a place
  -- inside a surface, which is not the screen.
  local middle = square(hud, 100, 40):position(x, y)
  local corner = square(hud, 100, 40):position(full.w - 100, full.h - 40)
  local grown = square(hud, 40, 40):position(math.floor(full.w * 0.2), math.floor(full.h * 0.25))
  grown:size(200, 120)
  local frame = square(hud, 200, 100):position(20, 20)
  local inside = square(frame, 20, 20):position(150, 60)

  after(0.1, function()
    local placed = { middle = box(middle), grown = box(grown) }
    check(placed.middle.x == x and placed.middle.y == y, "a :position on the HUD reads back as written: " .. x .. "," .. y,
      str(placed.middle))
    local wantMiddle = predict(placed.middle, full, shrunk)
    local wantGrown = predict(placed.grown, full, shrunk)
    hud:size(shrunk.w, shrunk.h)
    after(0.1, function()
      check(at(box(middle), wantMiddle), "through a HUD resize the middle one keeps its fraction: " .. str(wantMiddle),
        str(box(middle)))
      local cornerNow = box(corner)
      check(at(cornerNow, { x = shrunk.w - 100, y = shrunk.h - 40 }),
        "the one against the bottom-right corner stays on it: " .. (shrunk.w - 100) .. "," .. (shrunk.h - 40),
        str(cornerNow))
      check(at(box(grown), wantGrown), "a :size after the :position takes the fraction again: " .. str(wantGrown),
        str(box(grown)))
      local insideNow = box(inside)
      check(insideNow.x == 150 and insideNow.y == 60, "a place inside a surface keeps its pixels: 150,60",
        str(insideNow))
      hud:size(nil)
      after(0.1, function()
        check(at(box(middle), placed.middle), "and the middle one is back at " .. str(placed.middle)
          .. " when the HUD is", str(box(middle)))
        layerStep()
      end)
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, and the window
-- is built in the addon layer, so the run starts on the next step, where no tree is held.
hafen.console():on("t167", function() after(0, run) end)
