-- 166.1 — the client's windows keep their place relative to the screen. Self-checking suite.

local ID = "166-relative-window-positions.1"

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

local function frac(c, parent, window, was)
  local free = parent - window
  if free <= 0 then return was end
  if c <= MAGNET then return 0 end
  if c >= free - MAGNET then return 1 end
  return math.max(0, math.min(1, c / free))
end

local function place(f, parent, window)
  return math.floor(f * (parent - window) + 0.5)
end

local function near(a, b)
  return math.abs(a - b) <= SLACK
end

-- A window's place: where it stands and its outer box, in design pixels.
local function box(window)
  local position, frame = window:position(), window:chrome().frame
  return { x = position.x, y = position.y, w = frame.w, h = frame.h }
end

local function str(b)
  return b.x .. "," .. b.y
end

-- Where the rule puts a window that stood at `b` in a HUD `from`, once the HUD is `to`.
local function predict(b, from, to)
  return { x = place(frac(b.x, from.w, b.w, 0), to.w, b.w), y = place(frac(b.y, from.h, b.h, 0), to.h, b.h) }
end

local function at(window, want)
  local b = box(window)
  return near(b.x, want.x) and near(b.y, want.y)
end

local hud, sheet, full
local built = {}

local function finish()
  if hud then pcall(function() hud:size(nil) end) end
  if sheet then pcall(function() sheet:release() end) end
  for _, window in ipairs(built) do
    pcall(function() window:destroy() end)
  end
  built = {}
  manualCheck("answer: did the first start of this build open your windows where the old build left them",
    "yes")
  manualCheck("move the action search window, then restart the client in a game window of another size",
    "the inventory and the action search open at the same place relative to the screen")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Run each step inside pcall: whatever throws, the HUD gets its size back.
local function guarded(fn)
  return function()
    local ok, err = pcall(fn)
    if not ok then
      check(false, "the suite ran to its end", tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function window(title, w, h)
  local built_window = hafen.ui():window():title(title):size(w, h):parent(hud)
  built[#built + 1] = built_window
  return built_window
end

local function run()
  local session = hafen.session():current()
  hud = session and session:ui():match("@GameUI")
  if not hud then
    check(false, "a character is in the world", "no HUD -- run :t166 logged in")
    return finish()
  end
  local size = hud:size()
  full = { w = size.w, h = size.h }
  local shrunk = { w = full.w - 200, h = full.h - 150 }

  local inventory = session:ui():inventory():parent()
  local equipment = session:ui():match("window[title=Equipment]")
  local charsheet = session:ui():match("window[title=Character Sheet]")
  local c = window("166.1 C", 100, 50)
  local a, d
  local resizedStarted = false

  local function resized()
    local placed = { C = box(c), inv = box(inventory) }
    local s = { inv = box(inventory), equ = box(equipment), chr = box(charsheet) }
    local fraction = { inv = predict(s.inv, full, shrunk), equ = predict(s.equ, full, shrunk), chr = predict(s.chr, full, shrunk) }
    hud:size(shrunk.w, shrunk.h)
    after(0.1, function()
      local nowA, nowC = box(a), box(c)
      check(near(nowA.x + nowA.w, shrunk.w) and near(nowA.y + nowA.h, shrunk.h),
        "A, moved by hand into the corner, stands whole in the bottom-right corner of the shrunk HUD",
        "bottom-right " .. (nowA.x + nowA.w) .. "," .. (nowA.y + nowA.h) .. " of " .. shrunk.w .. "," .. shrunk.h)
      check(nowC.x == placed.C.x and nowC.y == placed.C.y,
        "C, a window of this addon nobody moved, keeps its pixel " .. str(placed.C), str(nowC))
      local ok = at(inventory, fraction.inv) and at(equipment, fraction.equ) and at(charsheet, fraction.chr)
      check(ok, "Inventory, Equipment and Character Sheet keep their fraction of the free space: "
        .. str(fraction.inv) .. " " .. str(fraction.equ) .. " " .. str(fraction.chr),
        str(box(inventory)) .. " " .. str(box(equipment)) .. " " .. str(box(charsheet)))
      local inv, dd = box(inventory), d:rootPos()
      local ir = inventory:rootPos()
      local moved = (inv.x ~= placed.inv.x) or (inv.y ~= placed.inv.y)
      check(moved and near(dd.x + box(d).w, ir.x + inv.w) and near(dd.y, ir.y),
        "D, anchored to the Inventory's top-right, is on that corner a tick after the resize moved it",
        moved and ("D " .. dd.x .. "," .. dd.y .. " Inventory " .. ir.x .. "," .. ir.y)
          or "the Inventory did not move: place it away from the top-left corner and run again")
      hud:size(nil)
      after(0.1, function()
        local back = { inv = predict(s.inv, full, full), equ = predict(s.equ, full, full), chr = predict(s.chr, full, full) }
        local okBack = at(inventory, back.inv) and at(equipment, back.equ) and at(charsheet, back.chr)
        check(okBack, "back at full size each is at the pixel it left: " .. str(back.inv) .. " "
          .. str(back.equ) .. " " .. str(back.chr),
          str(box(inventory)) .. " " .. str(box(equipment)) .. " " .. str(box(charsheet)))
        hud:size(math.max(40, s.chr.w - 50), full.h)
        after(0.1, function()
          hud:size(nil)
          after(0.1, function()
            check(at(charsheet, back.chr),
              "with the HUD narrower than the Character Sheet and back, the sheet is at " .. str(back.chr),
              str(box(charsheet)))
            equipment:position(40, 40)
            hud:size(shrunk.w, shrunk.h)
            after(0.1, function()
              local held = box(equipment)
              equipment:position(nil)
              local dropped = box(equipment)
              check(held.x == 40 and held.y == 40 and near(dropped.x, fraction.equ.x) and near(dropped.y, fraction.equ.y),
                "Equipment at :position(40, 40) keeps that pixel through the resize, and :position(nil) lands it at "
                  .. str(fraction.equ), str(held) .. " then " .. str(dropped))
              hud:size(nil)
              after(0.1, finish)
            end)
          end)
        end)
      end)
    end)
  end

  -- D hangs off the Inventory's top-right corner by a sheet rule, and is built before anything moves.
  sheet = hafen.ui():sheet()
  sheet:rule("[name=" .. ID .. "/d]"):anchor{ to = inventory, at = "topright" }
  sheet:install()
  d = hafen.ui():window():name("d"):title("166.1 D"):size(60, 30):parent(hud)
  built[#built + 1] = d

  -- The one step a program cannot take: an addon's window follows the screen only once a hand has moved it.
  -- The suite waits for you: nothing runs until you press Done, and a press too early just says so.
  a = window("166.1 A", 300, 90)
  local done = hafen.ui():button():parent(a):position(8, 8):size(80):text("Done")
  hafen.ui():button():parent(a):position(96, 8):size(80):text("Skip"):on("Pressed", function()
    after(0, function()
      if resizedStarted then return end
      resizedStarted = true
      check(false, "A landed on the bottom-right corner", "skipped")
      resized()
    end)
  end)
  local hint = hafen.ui():label():parent(a):position(8, 44):text("Drag me into the bottom-right corner,")
  hafen.ui():label():parent(a):position(8, 62):text("touching it or partly off it. Then press Done.")
  manualCheck("drag the window titled 166.1 A into the bottom-right corner of the screen and press its Done button",
    "the next line scores it; the rest runs by itself")
  done:on("Pressed", function()
    after(0, function()
      if resizedStarted then return end
      local now = box(a)
      local gapX, gapY = full.w - (now.x + now.w), full.h - (now.y + now.h)
      if (gapX > MAGNET) or (gapY > MAGNET) then
        hint:text("Not in the corner yet: " .. math.max(gapX, 0) .. " px, " .. math.max(gapY, 0) .. " px to go.")
        return
      end
      resizedStarted = true
      local rule = ((gapX < 0) or (gapY < 0)) and "the clamp" or "the magnet"
      check(true, "A landed on the bottom-right corner: gaps " .. gapX .. "," .. gapY .. " (" .. rule .. ")")
      resized()
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, and a window is
-- built in the addon layer, so the run starts on the next step, where no tree is held.
hafen.console():on("t166", function() after(0, run) end)
