-- 062.3 — widget:remember(name) makes a place survive the session. Self-checking suite.
--
-- Run :t062-3 in world, with the Equipment window open (Ctrl+E). It stands alone: it arms its own
-- draggable and resizable bindings, since the replay is theirs, and nothing here assumes any other suite
-- was ever run.
--
-- It drives one of the client's own WINDOWS rather than the chat, because a place has to round-trip for
-- the replay to be readable at all, and the chat's own move() does not take the coordinate its position
-- reads back.
--
-- The replay is proved WITHOUT a gesture: a place is written, saved, dropped, and put back by the name.
-- What a program cannot do is press a mouse button or end a session, so the two [manual] steps are the
-- ones that need a hand -- the drag itself, and what a :reload and a relog do to what is saved.
--
-- This suite WRITES PERSISTENT STATE, and that is what it exists to prove: the Equipment window is left
-- armed and remembered under the name "manual", in this addon's own per-character placement file. The
-- automated half cleans up after itself; the "manual" record is what the two steps below read.
--
-- What it leaves behind, on purpose: Equipment carries a "[062.3 move me]" and a "[062.3 size me]" label
-- of this addon's, is draggable and resizable by them, and is remembered. :reload takes the bindings and
-- the labels back; the record is the user's and stays.

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

-- The write that carries a remembered place to disk. Wrapped, so a flush that refuses is one FAIL below
-- rather than the end of the run.
local function flushNow()
  return pcall(function() hafen.store():flush() end)
end

local function summary()
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0

  local equ = hafen.ui():find("window[title=Equipment]")
  if equ == nil then
    check(false, "the Equipment window is open (Ctrl+E) -- this suite drives one of the client's windows",
          "no window[title=Equipment]")
    summary()
    return
  end
  equ:revert()                                   -- a re-run starts from the client's own layout, not the last run's
  local stock = equ:position()

  -- ---- the file this addon never declared -------------------------------------------------------------
  local flushed, ferr = flushNow()
  check(flushed, "hafen.store():flush() answers on an addon that declares no saved variables at all --"
        .. " a remembered place needs no declaration and no handler",
        flushed and "" or why(ferr))

  -- ---- the binding: read, arm, read the name back -----------------------------------------------------
  check(equ:remember() == nil, "remember() reads nil on a widget this addon does not remember",
        tostring(equ:remember()))

  local chained = equ:remember("probe")
  check((chained == equ) and (equ:remember() == "probe"),
        "remembering chains, and the read hands the name back",
        tostring(chained) .. ", " .. tostring(equ:remember()))

  local fired = 0                                -- neither key may fire for a write of this addon's own
  equ:on("Dragged", function() fired = fired + 1 end)
  equ:on("Resized", function() fired = fired + 1 end)

  -- ---- the replay, proved without a gesture -----------------------------------------------------------
  stage(function()
    equ:position(stock.x - 25, stock.y - 15)
    local placed = equ:position()
    flushNow()                                   -- the save a gesture's release rides: the level, under the name
    equ:position(nil)
    local dropped = equ:position()
    equ:remember("probe")                        -- ...and the name puts it back, at the instant it is called
    check(same(equ:position(), placed) and not same(dropped, placed),
          "remember(name) puts the saved place back on the call, and the nil in between had really dropped it",
          shown(placed) .. " -> " .. shown(dropped) .. " -> " .. shown(equ:position()))

    equ:position(stock.x - 60, stock.y)
    check(same(equ:position(), {x = stock.x - 60, y = stock.y}),
          "a position written AFTER :remember is the later level, and it wins", shown(equ:position()))
  end, "the replay: remember(name) puts the saved place back on the call")

  -- ---- forgetting: the name goes, and so does what it held --------------------------------------------
  stage(function()
    equ:remember(nil)
    local gone = equ:remember()
    equ:position(nil)                            -- back to the client's own place
    equ:remember("probe")                        -- ...and nothing is saved under that name any more
    check((gone == nil) and same(equ:position(), stock),
          "remember(nil) deletes the record: the name reads nil, and remembering it again applies nothing",
          tostring(gone) .. ", " .. shown(equ:position()) .. " (stock " .. shown(stock) .. ")")
    equ:remember(nil)
  end, "remember(nil) deletes the record, and remembering that name again applies nothing")

  check(fired == 0, "neither Dragged nor Resized fires for your own write or for :remember -- a handler"
        .. " cannot drive itself", fired)

  -- ---- dropping is not forgetting ---------------------------------------------------------------------
  stage(function()
    equ:remember("keep")
    equ:position(stock.x - 40, stock.y - 20)
    local kept = equ:position()
    flushNow()
    equ:revert()
    check((equ:remember() == nil) and same(equ:position(), stock),
          "widget:revert() drops the binding, and the level with it",
          tostring(equ:remember()) .. ", " .. shown(equ:position()))
    equ:remember("keep")
    check(same(equ:position(), kept),
          "...and the record that name held is still there: dropping is not forgetting",
          shown(equ:position()) .. " (left at " .. shown(kept) .. ")")
    equ:remember(nil)
    equ:position(nil)
  end, "widget:revert() drops the binding and keeps the record")

  -- ---- the refusals -----------------------------------------------------------------------------------
  stage(function()
    local other = hafen.ui():label():text("."):parent(equ)
    equ:remember("dup")
    refuses("a SECOND widget under a name this addon already holds is refused, naming the rule",
            function() other:remember("dup") end, "one name, one widget")
    equ:remember(nil)
    other:destroy()
  end, "a second widget under a name this addon already holds is refused")

  refuses("a name that is not a string is refused, naming what the three arities mean",
          function() equ:remember(42) end, "expects a string")

  -- ---- what it leaves standing for the two [manual] steps ---------------------------------------------
  stage(function()
    local box = equ:size()
    local mover = hafen.ui():label():text("[062.3 move me]"):parent(equ):position(2, box.y)
    local sizer = hafen.ui():label():text("[062.3 size me]"):parent(equ):position(2, box.y + 16)
    equ:pack()                                   -- the window refits around the two labels
    equ:draggable(mover)
    equ:resizable(sizer)
    equ:remember("manual")                       -- nothing to put back the first time; everything after a reload
  end, "the suite leaves Equipment armed and remembered under \"manual\" for the [manual] steps")

  manualCheck("drag Equipment by its [062.3 move me] label and resize it by [062.3 size me], then run"
              .. " :reload, then run :t062-3 again",
              "the reload puts the window back at the client's own place and box, and the last thing this"
              .. " suite does puts it back where YOU left it -- with no handler and nothing declared")
  manualCheck("log out, log back in with the same character, open Equipment (Ctrl+E) and run :t062-3 again",
              "the same place and box you left it at -- read off disk this time, since nothing of the"
              .. " session you dragged it in is left")
  summary()
end

hafen.slash():register("t062-3", run)            -- the only way in: a suite does not start itself
