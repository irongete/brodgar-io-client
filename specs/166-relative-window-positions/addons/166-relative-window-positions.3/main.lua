-- 166.3 — widget:remember keeps a place relative to the screen. Self-checking suite.

-- The remember rows this suite writes, and deletes at the end: a window on the HUD, and a widget inside one.
local NAME = "166-relative-window-positions.3/w"
local NAME_IN = "166-relative-window-positions.3/in"

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

-- The rule, as the client keeps it: a fraction of the free space per axis, clamped to 0..1, a gap of 10 px or
-- less to an edge taken as the edge, and an axis with no free space keeping the fraction it had.
local MAGNET, SLACK = 10, 2

local function frac(c, parent, widget, was)
  local free = parent - widget
  if free <= 0 then return was end
  if c <= MAGNET then return 0 end
  if c >= free - MAGNET then return 1 end
  return math.max(0, math.min(1, c / free))
end

local function place(f, parent, widget)
  return math.floor(f * (parent - widget) + 0.5)
end

local function near(a, b)
  return math.abs(a - b) <= SLACK
end

-- A widget's place: where it stands and its outer box, in design pixels. A window's box is its frame.
local function box(widget, bare)
  local position = widget:position()
  local size = bare and widget:size() or widget:chrome().frame
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

local session, hud, full, shrunk
local built = {}
local finished = false

-- The HUD gets its size back, every window goes, and a fresh HUD window takes each name and deletes its row.
local function finish()
  if finished then return end
  finished = true
  if hud then pcall(function() hud:size(nil) end) end
  for _, widget in ipairs(built) do
    pcall(function() widget:destroy() end)
  end
  built = {}
  local made, sweeper = pcall(function() return hafen.ui():window():title("166.3 sweep"):size(60, 20):parent(hud) end)
  hafen.timer():after(0.1, function()
    if made and sweeper then
      pcall(function()
        for _, name in ipairs({ NAME, NAME_IN }) do
          sweeper:remember(name)
          sweeper:remember(nil)
        end
      end)
      pcall(function() sweeper:destroy() end)
    end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

-- Run each step inside pcall: whatever throws, the HUD gets its size back and the rows are deleted.
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

local function hudWindow(title, w, h)
  return keep(hafen.ui():window():title(title):size(w, h):parent(hud))
end

local function innerWidget(panel)
  return keep(hafen.ui():widget():size(40, 20):parent(panel))
end

-- A name left over from an interrupted run: take it and delete it, so the run starts with no row.
local function clean(widget, name)
  widget:remember(name)
  widget:remember(nil)
  widget:remember(name)
end

-- After remember(nil), a fresh window under the name keeps its default place.
local function forgotten(saved)
  local fresh = hudWindow("166.3 W", 120, 40)
  after(0.1, function()
    local before = box(fresh)
    fresh:remember(NAME)
    after(0.1, function()
      local now = box(fresh)
      check(now.x == before.x and now.y == before.y and not at(before, saved) and fresh:remember() == NAME,
        "after remember(nil) a fresh window under the name keeps its default place " .. str(before)
          .. ", not the deleted " .. str(saved), str(now) .. " named " .. tostring(fresh:remember()))
      fresh:remember(nil)
      finish()
    end)
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  session = hafen.session():current()
  hud = session and session:ui():match("@GameUI")
  if not hud then
    check(false, "a character is in the world", "no HUD -- run :t166 logged in")
    return finish()
  end
  local size = hud:size()
  full = { w = size.w, h = size.h }
  shrunk = { w = full.w - 200, h = full.h - 150 }
  local x, y = math.floor(full.w * 0.7), math.floor(full.h * 0.3)

  -- At full size: a HUD window remembered at x, y, and a widget remembered at 50, 30 inside a window.
  local first = hudWindow("166.3 W", 120, 40)
  local panel = hudWindow("166.3 P", 300, 160)
  local inner = innerWidget(panel)
  after(0.1, function()
    clean(first, NAME)
    clean(inner, NAME_IN)
    first:position(x, y)
    inner:position(50, 30)
    after(0.1, function()
      local saved = box(first)
      first:destroy()
      inner:destroy()
      -- Rebuilt under the names with the HUD shrunk.
      hud:size(shrunk.w, shrunk.h)
      after(0.1, function()
        local rebuilt = hudWindow("166.3 W", 120, 40)
        local innerAgain = innerWidget(panel)
        after(0.1, function()
          local innerBefore = box(innerAgain, true)
          rebuilt:remember(NAME)
          innerAgain:remember(NAME_IN)
          after(0.1, function()
            local want, now = predict(saved, full, shrunk), box(rebuilt)
            check(at(now, want) and not at(now, saved),
              "W, remembered at " .. str(saved) .. " and rebuilt with the HUD shrunk, lands at its fraction "
                .. str(want) .. ", not the old pixels", str(now))
            local innerNow = box(innerAgain, true)
            check(innerNow.x == 50 and innerNow.y == 30 and not (innerBefore.x == 50 and innerBefore.y == 30),
              "a widget remembered inside a window comes back at the same pixels in it: 50,30",
              str(innerBefore) .. " then " .. str(innerNow))
            hud:size(nil)
            after(0.1, function()
              local back = box(rebuilt)
              check(at(back, saved), "back at full size, W stands at its old place " .. str(saved), str(back))
              rebuilt:remember(nil)
              innerAgain:remember(nil)
              rebuilt:destroy()
              forgotten(saved)
            end)
          end)
        end)
      end)
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, and a window is
-- built in the addon layer, so the run starts on the next step, where no tree is held.
hafen.console():on("t166", function() after(0, run) end)
