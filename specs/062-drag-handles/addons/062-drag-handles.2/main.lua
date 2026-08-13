-- 062.2 — a handle resizes one, without moving its origin. Self-checking suite.
--
-- Run :t062-2 in world. It stands alone: nothing here assumes any other suite was ever run.
--
-- It arms and drops resizable bindings, drives the SIZE level a resize gesture writes on the chat and on
-- one of the client's own windows (Equipment), and shows the one claim this task exists for -- the same
-- write is INERT on the main inventory's wrapper, which packs itself around its grid before the call
-- returns, and RAISES NOTHING. Running both in one suite is what makes that legible.
--
-- The gesture cannot be driven from Lua -- nothing can make the client deliver a press -- so the corner
-- drag is the [manual] half and everything around it is automated: the binding, the level, the refusals,
-- and the key that must NOT fire for your own write.
--
-- What it leaves behind, on purpose: the Equipment window carries a "[062.2 size me]" label of this
-- addon's and is armed to be resized by it, and the chat is left 30 px narrower for the second [manual].
-- :reload takes all of it back.

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

local function same(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local resized = 0     -- Resized fires this run: a programmatic write must leave it at zero
local live = false    -- a run is in flight (the deferred half has not printed yet)

local function summary()
  live = false
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The deferred half: a tick after the programmatic writes above, so a key that fired late would be seen.
local function finish()
  check(resized == 0, "your OWN widget:size(w, h) fires no Resized, a tick later included", resized)
  manualCheck("open Equipment (Ctrl+E) and drag its [062.2 size me] label -- out and down, then far back"
              .. " up and left, in one press",
              "the box follows the pointer one-for-one, its TOP-LEFT corner never moves, and shrinking"
              .. " bottoms out at a tiny window with its frame still drawn rather than vanishing")
  manualCheck("resize the game window, then run :lua hafen.ui():find(\"@ChatUI\"):size() and after it"
              .. " :lua hafen.ui():find(\"@ChatUI\"):size(nil)",
              "the chat is still the 30 px narrower box this suite left, the client's own re-layout"
              .. " notwithstanding; the nil then puts the stock box back")
  summary()
end

local function run()
  if live then
    log("[fail] this suite is already waiting -- let it print its summary first")
    return
  end
  pass, fail, manual, resized, live = 0, 0, 0, 0, true

  local chat = hafen.ui():find("@ChatUI")
  local equ = hafen.ui():find("window[title=Equipment]")
  local inv = hafen.ui():find("window[title=Inventory]")
  if (chat == nil) or (equ == nil) or (inv == nil) then
    check(false, "the chat and the Inventory and Equipment windows are in the tree",
          "chat=" .. tostring(chat) .. ", Inventory=" .. tostring(inv) .. ", Equipment=" .. tostring(equ))
    summary()
    return
  end

  -- ---- the binding: arm, read back by identity, drop, and give back -----------------------------------
  check(chat:resizable() == nil, "resizable() reads nil on a widget this addon has not armed",
        tostring(chat:resizable()))

  local grip = hafen.ui():label():text("."):parent(chat)
  local chained = chat:resizable(grip)
  check((chained == chat) and (chat:resizable() == grip),
        "arming chains, and the read hands back the very handle you passed -- interned, so == holds",
        tostring(chained) .. ", " .. tostring(chat:resizable()))

  chat:resizable(nil)
  local self1 = equ:resizable(equ)
  check((chat:resizable() == nil) and (self1 == equ) and (equ:resizable() == equ),
        "resizable(nil) drops it, and a window as its OWN handle is accepted -- nothing in the client's"
        .. " chrome resizes one from the whole frame, so it says something a caption does not",
        "dropped=" .. tostring(chat:resizable()) .. ", window itself=" .. tostring(equ:resizable()))
  equ:resizable(nil)

  chat:resizable(grip)
  chat:revert()
  check((chat:resizable() == nil) and not grip:exists(),
        "widget:revert() drops a live binding from the other side, and the grip it adopted with it",
        tostring(chat:resizable()) .. ", grip alive=" .. tostring(grip:exists()))

  -- ---- the size level a resize gesture writes ---------------------------------------------------------
  chat:on("Resized", function() resized = resized + 1 end)
  local stock = chat:size()
  chat:size(stock.x - 30, stock.y)
  local at = chat:size()
  chat:size(nil)
  check(same(at, {x = stock.x - 30, y = stock.y}) and same(chat:size(), stock),
        "the size level a resize writes: the pair reads back on a bare widget, and size(nil) restores it",
        shown(at) .. " -> " .. shown(chat:size()) .. " (stock was " .. shown(stock) .. ")")

  stage(function()
    local box = equ:size()                       -- a window's OUTER box; the write sets its CONTENT
    equ:size(box.x, box.y)                       -- ...so asking for the outer box makes it strictly bigger
    local grown = equ:size()
    equ:size(nil)
    check((grown.x > box.x) and (grown.y > box.y) and same(equ:size(), box),
          "on a window the write is the CONTENT size and the read is the OUTER box, and size(nil) gives"
          .. " that outer box back exactly",
          shown(box) .. " -> " .. shown(grown) .. " -> " .. shown(equ:size()))
  end, "on a window the write is the CONTENT size and the read is the OUTER box")

  stage(function()
    local box = inv:size()                       -- the main inventory's wrapper packs around its grid
    local ok, err = pcall(function() inv:size(box.x + 120, box.y + 120) end)
    local after = inv:size()
    inv:size(nil)
    check(ok and same(after, box),
          "a size on a window that packs itself around its contents is INERT, not an error: it is undone"
          .. " before the call returns and the box is unchanged",
          (ok and "no error" or why(err)) .. ", " .. shown(box) .. " -> " .. shown(after))
  end, "a size on a window that packs itself around its contents is INERT, not an error")

  -- ---- the vocabulary, and every refusal --------------------------------------------------------------
  refuses("on(key, fn) names Resized among the keys a widget answers",
          function() equ:on("Nope", function() end) end, "Resized")
  refuses("a handle that is not a Widget is refused, naming what the three arities mean",
          function() equ:resizable(42) end, "expects a Widget")
  local dead = hafen.ui():label():text("x"):parent(equ)
  dead:destroy()
  refuses("a handle that has left the tree is refused", function() equ:resizable(dead) end,
          "has left the tree")

  -- ---- what it leaves standing for the two [manual] steps ---------------------------------------------
  stage(function()
    local box = equ:size()
    local sizer = hafen.ui():label():text("[062.2 size me]"):parent(equ):position(2, box.y)
    equ:pack()                                   -- the window refits around the label, so it has room of its own
    equ:resizable(sizer)
    chat:size(stock.x - 30, stock.y)             -- ...and a level the client's own re-layout must not eat
  end, "the suite leaves Equipment armed and the chat 30 px narrower for the [manual] steps")

  hafen.timer():after(0, finish)                 -- ...and one tick for a key that might fire late
end

hafen.slash():register("t062-2", run)            -- the only way in: a suite does not start itself
