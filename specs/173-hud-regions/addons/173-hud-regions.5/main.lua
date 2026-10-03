-- 173.5 — the chat lands where it is placed, and a widget covering the screen keeps its pixels. Self-checking suite.

local NAMES = { "t173a", "t173b" }

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

local session, gameui, chat, grip, panel, landed, dragSub
local holders = {}
local finished = false

local function forget(name)
  local holder = holders[name]
  if holder and holder:parent() then
    pcall(function() holder:remember(nil) end)
    pcall(function() holder:destroy() end)
    return
  end
  -- the name's widget is gone: a stand-in takes the name for one tick, to delete what it saved
  local ok, standin = pcall(function() return hafen.ui():widget():parent(gameui):size(8, 8) end)
  if not ok then return end
  hafen.timer():after(0.2, function()
    pcall(function() standin:remember(name) end)
    pcall(function() standin:remember(nil) end)
    pcall(function() standin:destroy() end)
  end)
end

local function finish()
  if finished then return end
  finished = true
  if dragSub then pcall(function() dragSub:off() end) end
  if chat then
    pcall(function() chat:draggable(nil) end)
    pcall(function() chat:position(nil) end)
    pcall(function() chat:visible(true) end)
  end
  if grip then pcall(function() grip:destroy() end) end
  if gameui then
    for _, name in ipairs(NAMES) do forget(name) end
  end
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

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

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("173.5"):size(560, 70):position(40, 40)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function xy(at)
  return at and (at.x .. "," .. at.y) or "nil"
end

-- Does the chat STAND with its top-left at (x, y) of the HUD? The point two pixels inside it must hit the chat
-- or a widget inside it: the box a click meets, not the number the chat reads.
local function stands(x, y)
  local origin = gameui:rootPos()
  local hit = hafen.ui():hit(origin.x + x + 2, origin.y + y + 2)
  local probe = hit
  while probe do
    if probe == chat then return true, "the chat" end
    probe = probe:parent()
  end
  return false, hit and hit:type() or "nothing"
end

-- One check: the chat reads (x, y) and stands there.
local function readsAndStands(what, x, y)
  local at = chat:position()
  local ok, hit = stands(x, y)
  check(chat:visible() and at.x == x and at.y == y and ok, what,
    "reads " .. xy(at) .. ", hit " .. hit .. ", visible " .. tostring(chat:visible()))
end

local function resized()
  local fine, got = true, {}
  for _, name in ipairs(NAMES) do
    local at = holders[name] and holders[name]:position()
    fine = fine and at ~= nil and at.x == 0 and at.y == -40
    got[#got + 1] = name .. " " .. xy(at)
  end
  check(fine, "after a resize of the game window, both second surfaces still read (0, -40)", table.concat(got, ", "))
  finish()
end

-- A surface of `size` remembers `name`, moves to (0, -40) and is destroyed; a second one of that size,
-- remembering the same name, reads (0, -40).
local function roundTrip(index)
  local name = NAMES[index]
  if not name then
    prompt("Resize the game window (drag its border, or maximise / restore it); press Done.", resized, function()
      manualCheck("resize the game window (skipped)", "both second surfaces still read (0, -40)")
      finish()
    end)
    return
  end
  local hud = gameui:size()
  local grow = (index == 1) and 0 or 8
  local width, height = hud.w + grow, hud.h + grow
  local first = hafen.ui():widget():parent(gameui):size(width, height)
  holders[name] = first
  after(0.2, function()
    first:remember(name)
    first:position(0, -40)
    after(0.2, function()
      first:destroy()
      after(0.3, function()
        local second = hafen.ui():widget():parent(gameui):size(width, height)
        holders[name] = second
        after(0.2, function()
          second:remember(name)
          after(0.2, function()
            local at = second:position()
            check(at.x == 0 and at.y == -40,
              "a surface " .. ((grow == 0) and "exactly @GameUI's size" or "8 larger than @GameUI each way")
                .. " keeps (0, -40) through :remember (" .. name .. ")",
              xy(at) .. " at " .. width .. "x" .. height .. " over a HUD of " .. hud.w .. "x" .. hud.h)
            roundTrip(index + 1)
          end)
        end)
      end)
    end)
  end)
end

local function stock()
  chat:visible(false)
  chat:position(nil)
  chat:visible(true)
  after(0.2, function()
    local at, size, hud = chat:position(), chat:size(), gameui:size()
    local ok, hit = stands(at.x, at.y)
    check(math.abs(at.y + size.h - hud.h) <= 1 and at.x >= 0 and at.y >= 0 and at.x + size.w <= hud.w and ok,
      "after :visible(false), :position(nil) and :visible(true), the chat's bottom is @GameUI's and it stands"
        .. " whole on the screen",
      "reads " .. xy(at) .. " size " .. size.w .. "x" .. size.h .. " in a HUD of " .. hud.w .. "x" .. hud.h
        .. ", hit " .. hit)
    roundTrip(1)
  end)
end

local function dropped()
  if landed then
    local at = chat:position()
    local ok, hit = stands(at.x, at.y)
    check(at.x == landed.x and at.y == landed.y and ok,
      "the chat dragged by its grip reads where it was dropped, and stands there",
      "dropped at " .. xy(landed) .. ", reads " .. xy(at) .. ", hit " .. hit)
  else
    check(false, "the chat dragged by its grip reads where it was dropped, and stands there", "no Dragged fired")
  end
  manualCheck("drag the chat by the red square and let go", "it stays exactly where dropped")
  chat:draggable(nil)
  grip:destroy()
  grip = nil
  stock()
end

local function drag()
  grip = hafen.ui():widget():parent(chat):size(16, 16):position(40, 4)
  grip:stock{ bg = { color = { 200, 40, 40, 255 } } }
  chat:draggable(grip)
  dragSub = chat:on("Dragged", function(drag_event) landed = { x = drag_event:x(), y = drag_event:y() } end)
  prompt("Drag the chat by its red square to the middle of the screen, let go; press Done.", dropped, function()
    manualCheck("drag the chat by the red square (skipped)", "it stays exactly where dropped")
    chat:draggable(nil)
    grip:destroy()
    grip = nil
    stock()
  end)
end

local function toggled()
  readsAndStands("after Ctrl+C hid and showed the chat, it still reads and stands at (200, 260)", 200, 260)
  drag()
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  holders, landed, grip, dragSub = {}, nil, nil, nil
  session = hafen.session():current()
  gameui = session and session:ui():match("@GameUI")
  chat = gameui and session:ui():match("@ChatUI")
  if not (gameui and chat and chat:parent() == gameui) then
    check(false, "a character is in the world with its chat on the HUD", "no HUD or no chat -- run :t173 logged in")
    return finish()
  end
  chat:visible(true)
  chat:position(200, 300)
  after(0.2, function()
    readsAndStands("shown, @ChatUI:position(200, 300) reads it and stands there", 200, 300)
    chat:visible(false)
    chat:position(200, 260)
    chat:visible(true)
    after(0.2, function()
      readsAndStands("placed while hidden at (200, 260), the chat shown reads it and stands there", 200, 260)
      prompt("Press Ctrl+C until the chat has hidden and shown again; press Done.", toggled, function()
        manualCheck("press Ctrl+C until the chat hides and shows (skipped)", "it shows again at (200, 260)")
        drag()
      end)
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t173", function() after(0, run) end)
