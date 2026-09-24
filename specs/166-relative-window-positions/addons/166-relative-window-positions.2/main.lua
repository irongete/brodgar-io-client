-- 166.2 — a place the user's hand gives an addon's widget follows the screen. Self-checking suite.

local NAME = "166-relative-window-positions.2/w"   -- the remember row this suite writes, and deletes at the end

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

local session, hud, full, shrunk, rebuilt, c
local built = {}
local finished = false

local function finish()
  if finished then return end
  finished = true
  if hud then pcall(function() hud:size(nil) end) end
  if rebuilt then pcall(function() rebuilt:remember(NAME); rebuilt:remember(nil) end) end   -- the row goes
  for _, widget in ipairs(built) do
    pcall(function() widget:destroy() end)
  end
  built = {}
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Run each step inside pcall: whatever throws, the HUD gets its size back and the row is deleted.
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

-- The four hand steps, each one [manual] line scored by the next, paced by Done and Skip in window 166.2.
local function prompts()
  local still = keep(hafen.ui():window():title("166.2 still"):size(140, 40))
  local panel = keep(hafen.ui():window():title("166.2"):size(360, 120))
  local square = keep(hafen.ui():widget():size(48, 48):position(560, 120))
  square:stock{ bg = { color = { 128, 128, 128, 255 } } }
  square:draggable(square)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  local hint = hafen.ui():label():parent(panel):position(8, 44):text("")
  local hint2 = hafen.ui():label():parent(panel):position(8, 62):text("")
  local step, busy = 0, false
  local placed = {}

  local steps = {
    {
      manual = "drag the HUD window titled 166.2 C by its title and press Done in window 166.2",
      text = { "Drag the HUD window 166.2 C by its title,", "then press Done." },
      begin = function() placed.c = box(c) end,
      score = function(proceed)
        local now = box(c)
        if now.x == placed.c.x and now.y == placed.c.y then return "166.2 C has not moved yet." end
        local want = predict(now, full, shrunk)
        hud:size(shrunk.w, shrunk.h)
        after(0.1, function()
          check(at(box(c), want), "C, dragged by its title, keeps its fraction through a HUD resize: " .. str(want),
            str(box(c)))
          hud:size(nil)
          after(0.1, proceed)
        end)
      end,
    },
    {
      manual = "drag the grey square by pressing on it and press Done in window 166.2",
      text = { "Drag the grey square by pressing on it,", "then press Done." },
      begin = function() placed.square = box(square, true) end,
      score = function(proceed)
        local now = box(square, true)
        if now.x == placed.square.x and now.y == placed.square.y then return "The square has not moved yet." end
        check(true, "the square was dragged to " .. str(now))
        proceed()
      end,
    },
    {
      manual = "drag this window, titled 166.2, by its title and press its Done button",
      text = { "Drag this window by its title,", "then press Done." },
      begin = function() placed.panel = box(panel) end,
      score = function(proceed)
        local now = box(panel)
        if now.x == placed.panel.x and now.y == placed.panel.y then return "This window has not moved yet." end
        check(true, "window 166.2 was dragged to " .. str(now))
        proceed()
      end,
    },
    {
      manual = "resize the game window (drag its border or maximise it) and press Done in window 166.2",
      text = { "Resize the game window: drag its border", "or maximise it. Then press Done." },
      begin = function()
        local size = session:ui():root():size()
        placed.screen = { w = size.w, h = size.h }
        placed.square, placed.panel, placed.still = box(square, true), box(panel), box(still)
      end,
      score = function(proceed)
        local size = session:ui():root():size()
        local screen = { w = size.w, h = size.h }
        if screen.w == placed.screen.w and screen.h == placed.screen.h then
          return "The game window still has its size."
        end
        local wantSquare = predict(placed.square, placed.screen, screen)
        local wantPanel = predict(placed.panel, placed.screen, screen)
        local nowSquare, nowPanel, nowStill = box(square, true), box(panel), box(still)
        check(at(nowSquare, wantSquare) and at(nowPanel, wantPanel)
            and nowStill.x == placed.still.x and nowStill.y == placed.still.y,
          "after the game window resize the square and window 166.2 keep their fractions ("
            .. str(wantSquare) .. " " .. str(wantPanel) .. ") and 166.2 still keeps its pixel " .. str(placed.still),
          str(nowSquare) .. " " .. str(nowPanel) .. " " .. str(nowStill))
        proceed()
      end,
    },
  }

  local function advance()
    step = step + 1
    busy = false
    local current = steps[step]
    if not current then return finish() end
    manualCheck(current.manual, "the next line scores it")
    hint:text(current.text[1])
    hint2:text(current.text[2])
    current.begin()
  end

  done:on("Pressed", function()
    after(0, function()
      local current = steps[step]
      if busy or not current then return end
      busy = true
      local notYet = current.score(guarded(advance))
      if notYet then
        busy = false
        hint:text(notYet)
        hint2:text(current.text[1])
      end
    end)
  end)
  skip:on("Pressed", function()
    after(0, function()
      local current = steps[step]
      if busy or not current then return end
      check(false, current.manual, "skipped")
      advance()
    end)
  end)
  advance()
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
  local x, y = math.floor(full.w * 0.55), math.floor(full.h * 0.45)

  -- A HUD window of this suite's own at :position(x, y), remembered, destroyed and rebuilt under the name.
  local first = hudWindow("166.2 W", 120, 40)
  c = hudWindow("166.2 C", 140, 40)
  after(0.1, function()
    first:remember(NAME)
    first:position(x, y)
    c:position(40, 40)
    after(0.1, function()
      first:destroy()
      rebuilt = hudWindow("166.2 W", 120, 40)
      after(0.1, function()
        rebuilt:remember(NAME)
        after(0.1, function()
          local back = box(rebuilt)
          local want = predict(back, full, shrunk)
          hud:size(shrunk.w, shrunk.h)
          after(0.1, function()
            local now, cNow = box(rebuilt), box(c)
            check(near(back.x, x) and near(back.y, y) and at(now, want),
              "W, remembered at " .. x .. "," .. y .. " and rebuilt, comes back there and keeps its fraction through"
                .. " a HUD resize: " .. str(want), str(back) .. " then " .. str(now))
            check(cNow.x == 40 and cNow.y == 40, "C at :position(40, 40) keeps its pixels through the HUD resize",
              str(cNow))
            hud:size(nil)
            after(0.1, prompts)
          end)
        end)
      end)
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, and a window is
-- built in the addon layer, so the run starts on the next step, where no tree is held.
hafen.console():on("t166", function() after(0, run) end)
