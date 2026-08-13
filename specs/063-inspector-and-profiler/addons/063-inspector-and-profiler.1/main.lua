-- 063.1 -- a press lands where it was painted. Self-checking suite.
--
-- Run :t063-1 in world. It opens ONE small window, walks you through three gestures, and prints the whole
-- verdict 45 seconds later, whatever happened in between. Nothing can press a mouse button from Lua, so
-- the gesture is the maintainer's hand -- but the JUDGEMENT is the program's: a gesture nobody made scores
-- a [fail] naming what was missing, which is what lets a suite over an input event still stand alone.
--
-- What it proves:
--   * a press on a window this addon built is reported in the very pixels its Draw painted in. The green
--     square is drawn at content (40,40)-(88,88); the press must land INSIDE it.
--   * the chrome belongs to the client: dragging the title bar moves the window and reports no press at
--     all, so between the square and the drag the handler hears nothing.
--   * ev:preventDefault() is honoured on a window that HAS chrome: a cancelled press in the caption of the
--     client's own Inventory window leaves that window exactly where it was.
--
-- The window states one step at a time, and each ticks over on its own:
--   1. click the green square (and move nothing yet),
--   2. drag THIS window by its title bar, touching nothing else -- every press heard between the square
--      and the drag is one the caption should never have delivered,
--   3. with the Inventory open (Tab), press its TITLE BAR -- the caption strip at the very top -- and drag
--      right across the screen. It must not follow the mouse.
--      Pressing lower down is a different gesture: a window's frame drags itself from its margins too, and
--      those presses are not the ones this suite cancels, so they are counted and named, never judged.

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

-- A refusal is a check: the call must fail, and fail SAYING why -- every one of the words asked for.
local function refuses(what, fn, ...)
  local want = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- ============================================================================================= the run

local WINDOW = 45                          -- seconds the run waits for the three gestures
local POLL   = 0.5                         -- how often the Inventory window is looked for
local CAP    = 30                          -- the top strip of a window: its caption
local JUDGE  = 4                           -- seconds the cancelled press is judged over (see adopt)
local DRAG_MIN = 20                        -- pointer travel that makes its displacement worth comparing
local SLOP   = 2                           -- a frame's rounding between the two deltas, and nothing more
local TARGET = "window[title=Inventory]"   -- one of the CLIENT's own windows, named rather than guessed
local SQ     = { x = 40, y = 40, w = 48, h = 48 }
local W, H   = 340, 136

local st                                   -- the run in flight, nil between runs

local function inside(x, y)
  return (x ~= nil) and (y ~= nil)
     and (x >= SQ.x) and (x < SQ.x + SQ.w) and (y >= SQ.y) and (y < SQ.y + SQ.h)
end

-- A place that survives the widget being gone (the maintainer may close either window mid-run).
local function place(w)
  local ok, p = pcall(function() return w:position() end)
  return (ok and p) or nil
end

local function samePlace(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local function shown(p)
  return p and (p.x .. "," .. p.y) or "nil"
end

local function mousePos()
  local ok, m = pcall(function()
    local mo = hafen.ui():mouse()
    return { x = mo:x(), y = mo:y() }
  end)
  return (ok and m and m.x and m.y) and m or nil
end

-- Step 3's target: the client's own Inventory window, whether it was already open or Tab opens it during
-- the run. Named, not guessed -- an addon's window would answer the same code path and prove less. Hidden
-- is not open: a window the client is merely holding cannot be pressed.
--
-- The cancelled press is judged over the seconds right after it, not over the whole run. A window's frame
-- swallows a press anywhere its background box reaches -- the margin beside the contents, the strip under
-- the caption -- and drags itself from there too; those presses are BELOW the caption, so this suite does
-- not cancel them and must not be fooled by one. A press lower down is counted and named instead.
--
-- And what is judged is FOLLOWING THE MOUSE, which is what a drag is and what the check says: the client
-- moves a dragged window by exactly the pointer's own displacement, so a cancel that leaked shows the
-- window's delta matching the mouse's, 1:1, from the first frame the pointer moves. That is a fact about
-- the gesture rather than a distance somebody picked, and it does not blur into a window nudged a pixel by
-- something else in the client -- which is why BOTH distances are printed on the line, pass or fail.
local function adopt()
  local ok, w = pcall(function() return hafen.ui():find(TARGET) end)
  if (not ok) or (w == nil) then return end
  local okv, vis = pcall(function() return w:visible() end)
  if not (okv and vis) then return end
  st.bw = w
  st.bsub = w:on("MouseDown", function(ev)
    if not st then return end
    if ev:y() >= CAP then
      st.bloose = st.bloose + 1            -- below the caption: not this suite's to cancel
      return
    end
    st.bpress = st.bpress + 1
    ev:preventDefault()
    if not st.bmark then                   -- the FIRST cancelled press is the one judged
      st.bmark, st.by = st.t, ev:y()
      st.bpos0, st.bm0 = place(st.bw), mousePos()
    end
  end)
end

-- One step at a time, so "touch nothing else" is an instruction with a moment attached rather than a rule
-- to remember for 45 seconds.
local STEP = {
  "1/3  click the green square -- and move nothing yet",
  "2/3  now drag ME by my TITLE BAR -- click nothing else",
  "3/3  Tab, then drag the Inventory by ITS title bar",
}

local function draw(ev)
  if not st then return end                -- the run is over; the window goes with it
  local g, w, h = ev:g(), ev:w(), ev:h()
  g:color(0, 0, 0, 190); g:frect(0, 0, w, h); g:color()
  g:color(60, 220, 90); g:frect(SQ.x, SQ.y, SQ.w, SQ.h); g:color()
  g:color(10, 40, 10); g:text("click", SQ.x + 9, SQ.y + 17); g:color()
  g:color(230, 230, 160)
  g:text(STEP[st.phase], 6, 8)
  g:color(170, 170, 170)
  g:text(("verdict in %d s -- the whole block prints in the log")
    :format(math.max(0, math.ceil(WINDOW - st.t))), 6, h - 18)
  g:color()
  g:color(120, 120, 120); g:rect(0, 0, w, h); g:color()
end

local function finish()
  if st.upd then st.upd:off() end
  if st.bsub then st.bsub:off() end

  -- ---- the surface this addon painted: one coordinate system, and a chrome that is not its own ---------
  check(inside(st.sqx, st.sqy),
        "a press on the green square is reported INSIDE it -- the content pixels Draw painted it in",
        (st.presses == 0) and ("no press in " .. WINDOW .. " s") or (st.sqx .. "," .. st.sqy))
  check(st.moved, "dragging the title bar moves the window: the chrome is the client's",
        ("the window did not move in %d s"):format(WINDOW))
  local dragGot
  if not st.moved then
    dragGot = ("no drag in %d s"):format(WINDOW)
  elseif st.dragPhase ~= 2 then
    dragGot = "the window moved before the green square was clicked"
  else
    dragGot = ("%d press(es) reported after the square, before the drag"):format(st.p2)
  end
  check(st.moved and (st.dragPhase == 2) and (st.p2 == 0),
        "...and not one press was reported between the square and that drag -- the caption is not ours",
        dragGot)

  -- ---- one of the CLIENT's windows: a cancelled press has to stop the client itself -------------------
  check(st.bw ~= nil, "the client's own Inventory window was open, to subscribe on",
        ("no visible %s in %d s -- Tab opens it"):format(TARGET, WINDOW))
  check(st.bpress > 0,
        ("a press in the top %d px of the Inventory reached the subscription"):format(CAP),
        st.bw and (("no press above y=%d in %d s (%d lower down)"):format(CAP, WINDOW, st.bloose))
               or "no window to press")
  local followGot
  if st.bpress == 0 then
    followGot = "no press to cancel"
  elseif st.bmmax < DRAG_MIN then
    followGot = ("the mouse moved %d px after the press at y=%d -- press the title and DRAG")
                :format(st.bmmax, st.by or -1)
  else
    followGot = ("it tracked the mouse 1:1, %s -> %s"):format(shown(st.bpos0), shown(st.bwas))
  end
  check((st.bpress > 0) and (st.bmmax >= DRAG_MIN) and (not st.bfollow),
        ("...and ev:preventDefault() stopped the client: the Inventory did not follow the mouse"
         .. " (mouse %d px, window %d px)"):format(st.bmmax, st.bwmax),
        followGot)

  -- ---- the vocabulary a surface answers ---------------------------------------------------------------
  local probe = hafen.ui():window():title("063.1")
  refuses("an unknown key is refused, naming the key and listing what a surface does answer",
          function() probe:on("Nope", function() end) end, "'Nope'", "Draw")
  pcall(function() probe:destroy() end)

  manualCheck("run :profiler, click each of its six tabs, then click its caption",
              "a click on a TAB switches to that tab; a click on the caption switches none and drags the"
              .. " window instead")
  manualCheck("drag WidgetStack's window by its title bar (:widgetstack brings it up)",
              "the window moves and no Inspector window opens")
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))

  pcall(function() st.win:destroy() end)
  st = nil
end

local function update(dt)
  if not st then return end
  st.t = st.t + dt
  st.pos0 = st.pos0 or place(st.win)
  if not st.moved then
    local p = place(st.win)
    if p and st.pos0 and not samePlace(p, st.pos0) then
      st.moved = true
      st.dragPhase = st.phase                  -- which step the drag actually landed in
      if st.phase == 2 then st.phase = 3 end   -- the drag landed: stop counting, and ask for the last step
    end
  end
  if not st.bw then
    st.poll = st.poll + dt
    if st.poll >= POLL then
      st.poll = 0
      pcall(adopt)
    end
  end
  -- The cancelled press, judged while it is live: a cancel that failed has the window under the mouse from
  -- the same instant, so what is watched is these seconds and not the whole run. DRAG_MIN is only how far
  -- the pointer has to travel for its own displacement to be worth comparing against -- the comparison
  -- itself is the 1:1 tracking above, and SLOP is a frame's worth of rounding, not a tolerance for drift.
  if st.bmark and (st.t - st.bmark <= JUDGE) then
    local p, m = place(st.bw), mousePos()
    if p and m and st.bpos0 and st.bm0 then
      st.bwas = p
      local wx, wy = p.x - st.bpos0.x, p.y - st.bpos0.y
      local mx, my = m.x - st.bm0.x, m.y - st.bm0.y
      st.bwmax = math.max(st.bwmax, math.abs(wx) + math.abs(wy))
      st.bmmax = math.max(st.bmmax, math.abs(mx) + math.abs(my))
      if (math.abs(mx) + math.abs(my) >= DRAG_MIN)
         and (math.abs(wx - mx) <= SLOP) and (math.abs(wy - my) <= SLOP) then
        st.bfollow = true
      end
    end
  end
  -- ...which is also why a press made in the last seconds gets its own window before the verdict prints.
  if (st.t >= WINDOW) and ((not st.bmark) or (st.t > st.bmark + JUDGE)) then
    finish()
  end
end

local function run()
  if st then
    log(("063.1: already running -- %d s left"):format(math.max(0, math.ceil(WINDOW - st.t))))
    return
  end
  pass, fail, manual = 0, 0, 0
  st = { t = 0, poll = 0, phase = 1, presses = 0, p2 = 0, bpress = 0, bloose = 0,
         bwmax = 0, bmmax = 0, moved = false, bfollow = false }

  st.win = hafen.ui():window()
    :title("063.1")
    :size(W, H)
    :position(140, 140)
  -- widget:on(key, fn) hands back a SUB, not the widget, so neither of these can sit mid-chain above.
  st.win:on("Draw", draw)
  st.win:on("MouseDown", function(ev)
    if not st then return end
    local x, y = ev:x(), ev:y()
    st.presses = st.presses + 1
    if st.sqx == nil then st.sqx, st.sqy = x, y end
    if st.phase == 1 then
      st.phase = 2                    -- the first press ends step 1, whether or not it hit the square
    elseif st.phase == 2 then
      st.p2 = st.p2 + 1               -- step 2 says touch nothing: what is heard here is what is judged
    end
    ev:preventDefault()   -- consume: an uncancelled press falls through to the frame, which would drag it
  end)

  st.upd = hafen.event():on("Update", update)
  log(("063.1: one step at a time, in the window -- the verdict prints here in %d s"):format(WINDOW))
end

hafen.slash():register("t063-1", run)      -- the only way in: a suite does not start itself
