-- 062.1 — a handle drags one of the client's widgets. Self-checking suite.
--
-- Run :t062-1 in world. It arms and drops bindings on the chat, drives the position LEVEL a gesture
-- writes on one of the client's own windows (Equipment), and leaves that window armed with a grip of
-- its own so the drag itself can be tried by hand.
--
-- The gesture cannot be driven from Lua -- nothing can make the client deliver a click -- so the press
-- is the [manual] half and everything around it is automated: the binding, the level, the clamp, the
-- refusals, and the event that must NOT fire for your own write.
--
-- What it leaves behind: the Equipment window carries a "[062.1 drag me]" label of this addon's and is
-- armed to be dragged by it, on purpose. :reload takes all of it back.

local pass, fail, manual = 0, 0, 0

local function log(s)
  hafen.log():write(s)
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function why(err)
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- One group of checks. An error inside one is a FAIL of that group and nothing more: the run goes on and
-- still prints its verdict, which a raise out of a slash command would have swallowed.
local function stage(fn, what)
  local ok, err = pcall(fn)
  if not ok then
    check(false, what, why(err))
  end
end

local function shown(c)
  return (c == nil) and "nil" or (c.x .. "," .. c.y)
end

-- How much of `w` is still inside `p` on one axis -- the client's own graspability rule, read back.
local function inside(at, len, span)
  return math.min(at + len, span) - math.max(at, 0)
end

local dragged = 0     -- Dragged fires this run: a programmatic write must leave it at zero
local live = false    -- a run is in flight (the deferred half has not printed yet)

local function summary()
  live = false
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The deferred half: a tick after the programmatic writes above, so a key that fired late would be seen.
local function finish()
  check(dragged == 0, "your OWN widget:position(x, y) fires no Dragged, a tick later included", dragged)
  manualCheck("open Equipment (Ctrl+E) and drag it by its [062.1 drag me] label -- fast, and with the"
              .. " pointer leaving the game window",
              "it follows one-for-one, nothing in the game is clicked while you drag, and it stays"
              .. " where you drop it")
  manualCheck("with Equipment dropped somewhere new, resize the game window, then run :t062-1 again",
              "the window is exactly where you dropped it after the resize, and the re-run leaves it"
              .. " back at its stock place")
  manualCheck("a second owner on one target: :lua local w = hafen.ui():find(\"window[title=Equipment]\")"
              .. " w:draggable(w:all(\"@Label\")[1]) -- drag once, then the same line with"
              .. " w:draggable(nil)",
              "one drag moves it ONCE, not twice; and the console's nil moves nothing, this addon's"
              .. " level holding the same landed place")
  summary()
end

local function run()
  if live then
    log("[fail] this suite is already waiting -- let it print its summary first")
    return
  end
  pass, fail, manual, dragged, live = 0, 0, 0, 0, true

  local chat = hafen.ui():find("@ChatUI")
  local win = hafen.ui():find("window[title=Equipment]")
  if (chat == nil) or (win == nil) then
    check(false, "the chat and the Equipment window are in the tree",
          "chat=" .. tostring(chat) .. ", window[title=Equipment]=" .. tostring(win))
    summary()
    return
  end

  -- ---- the binding: arm, read back by identity, drop, and give back -----------------------------------
  check(chat:draggable() == nil, "draggable() reads nil on a widget this addon has not armed",
        tostring(chat:draggable()))

  local grip = hafen.ui():label():text("."):parent(chat)
  local chained = chat:draggable(grip)
  check((chained == chat) and (chat:draggable() == grip),
        "arming chains, and the read hands back the very handle you passed -- interned, so == holds",
        tostring(chained) .. ", " .. tostring(chat:draggable()))

  chat:draggable(nil)
  local self1 = chat:draggable(chat)
  check((self1 == chat) and (chat:draggable() == chat), "draggable(nil) drops it, and the target as its"
        .. " OWN handle is accepted -- a widget dragged from anywhere inside itself",
        tostring(chat:draggable()))
  chat:draggable(nil)

  chat:draggable(grip)
  chat:revert()
  check((chat:draggable() == nil) and not grip:exists(),
        "widget:revert() drops a live binding from the other side, and the grip it adopted with it",
        tostring(chat:draggable()) .. ", grip alive=" .. tostring(grip:exists()))

  -- ---- the level a gesture writes, on one of the client's own windows ---------------------------------
  win:on("Dragged", function() dragged = dragged + 1 end)
  local stock = win:position()
  win:position(40, 40)
  local at = win:position()
  win:position(nil)
  check((at ~= nil) and (at.x == 40) and (at.y == 40) and (shown(win:position()) ~= "40,40"),
        "the position level a drag writes: (40, 40) reads back, and position(nil) puts the stock place back",
        shown(at) .. " -> " .. shown(win:position()) .. " (stock was " .. shown(stock) .. ")")

  stage(function()
    win:position(9000, 9000)                   -- far off screen: the client's own clamp has the last word
    local off, box, screen = win:position(), win:size(), win:parent():size()
    local keepx, keepy = math.min(100, box.x), math.min(100, box.y)
    win:position(nil)
    check((off.x < 9000) and (inside(off.x, box.x, screen.x) >= keepx - 1)
          and (inside(off.y, box.y, screen.y) >= keepy - 1),
          "a place far outside the screen is clamped: at least min(100 px, its own size) stays inside",
          shown(off) .. " of " .. shown(box) .. " in " .. shown(screen))
  end, "a place far outside the screen is clamped")

  -- ---- the vocabulary, and every refusal --------------------------------------------------------------
  refuses("on(key, fn) names Dragged among the keys a widget answers",
          function() win:on("Nope", function() end) end, "Dragged")
  refuses("a handle that is not a Widget is refused, naming what the three arities mean",
          function() win:draggable(42) end, "expects a Widget")
  local dead = hafen.ui():label():text("x"):parent(win)
  dead:destroy()
  refuses("a handle that has left the tree is refused", function() win:draggable(dead) end,
          "has left the tree")

  stage(function()
    local box = win:size()
    local grip2 = hafen.ui():label():text("[062.1 drag me]"):parent(win):position(2, box.y)
    win:pack()                                 -- the window refits around the grip, so it has room of its own
    local refused, err = pcall(function() win:draggable(win) end)
    err = refused and "<no error>" or why(err)
    local took = pcall(function() win:draggable(grip2) end)
    check((not refused) and (err:find("caption", 1, true) ~= nil) and took
          and (win:draggable() == grip2),
          "the window ITSELF is refused naming the caption, and a grip of its own on it is accepted",
          err .. ", grip accepted=" .. tostring(took))
  end, "the window ITSELF is refused naming the caption")

  hafen.timer():after(0, finish)               -- ...and one tick for a key that might fire late
end

hafen.slash():register("t062-1", run)          -- the only way in: a suite does not start itself
