-- 125.2 -- the two gestures and the anchor take the same rule as widget:parent: a Widget ARGUMENT that
-- has left the tree stops the write instead of raising. Self-checking suite: run with :t125.
--
-- The fixture is a corpse this suite builds itself -- hafen.ui():widget() then :destroy() -- and it is
-- proven dead before anything rests on it, assuming no other suite is ever run. What is asserted after
-- that is never the absence of an error: it is that NOTHING WAS ARMED (the read answers nil) and that
-- the widget the anchor named did not move.
--
-- widget:send's own stale refusal sits behind the widget.send permission key, which this suite does not
-- declare, so it is not checked here: the call would refuse for the key rather than for the tree, and a
-- check that cannot tell those apart proves nothing. That site is verified by reading it.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- EVERYTHING BELOW RUNS ON THE STEP, not on the console line. A command runs inside the UI of the
-- console it was typed into, holding that character's tree monitor, and building a surface in the addon
-- layer would take a second one. hafen.timer():after(0, fn) at the top of the body is the whole of it.
local function body()
  -- The fixture, proven before anything rests on it.
  local dead = hafen.ui():widget()
  dead:destroy()
  check(dead:exists() == false, "the fixture is a corpse: a surface built and destroyed", dead:exists())

  -- The gestures, on one of the client's own widgets: a handle that died between the step that handed
  -- it over and this call arms nothing, and the READ is what says so.
  local s = hafen.session():current()
  local win = (s ~= nil) and s:ui():match("@CornerMap") or nil
  if win == nil then
    check(false, "a dead handle arms no drag: the call raises nothing and chains", "no @CornerMap on screen")
    check(false, "...and :draggable() reads nil -- it armed nothing, and says so", "no @CornerMap on screen")
    check(false, "a dead handle arms no resize: the call raises nothing and chains", "no @CornerMap on screen")
    check(false, "...and :resizable() reads nil -- it armed nothing, and says so", "no @CornerMap on screen")
  else
    local drag, dgot = pcall(function() return win:draggable(dead) end)
    check(drag and (dgot == win), "a dead handle arms no drag: the call raises nothing and chains",
          drag and tostring(dgot) or dgot)
    check(win:draggable() == nil, "...and :draggable() reads nil -- it armed nothing, and says so",
          win:draggable())
    local size, sgot = pcall(function() return win:resizable(dead) end)
    check(size and (sgot == win), "a dead handle arms no resize: the call raises nothing and chains",
          size and tostring(sgot) or sgot)
    check(win:resizable() == nil, "...and :resizable() reads nil -- it armed nothing, and says so",
          win:resizable())
  end

  -- The other half of the split at both gestures: a value that is not a Widget is a spelling mistake.
  local own = hafen.ui():widget()
  refuses("a handle that is not a Widget still raises, naming Widget",
          function() own:draggable(42) end, "expects a Widget")
  refuses("...and so does one handed to :resizable",
          function() own:resizable(42) end, "expects a Widget")
  own:destroy()

  -- The anchor: a rule naming a widget that has already left installs inert, exactly as one whose
  -- target closes a moment later, and the widget it names stays where the client put it.
  local sheet = hafen.ui():sheet()
  local before = (win ~= nil) and win:position() or nil
  local put, perr = pcall(function()
    sheet:rule("@CornerMap"):anchor{ to = dead }
    sheet:install()
  end)
  check(put, "a rule anchored to a dead widget installs, raising nothing", perr)
  local after = (win ~= nil) and win:position() or nil
  check((before ~= nil) and (after ~= nil) and (before.x == after.x) and (before.y == after.y),
        "...and the widget it names did not move",
        (after == nil) and "no @CornerMap on screen" or (after.x .. ", " .. after.y))
  refuses("anchor.to that is neither is refused, naming \"screen\" or a widget",
          function() sheet:rule("@CornerMap"):anchor{ to = 42 } end, "expected \"screen\" or a widget")
  sheet:release()

  -- The boundary this feature does not cross: a stale RECEIVER keeps every rule it has.
  refuses("a stale receiver's :on(key, fn) still raises, naming the missing tree",
          function() dead:on("Removed", function() end) end, "no longer in the tree")
  refuses("...and :match(selector) still raises, naming the missing tree",
          function() dead:match("*") end, "not in the tree")
  refuses("...and :overlay():add(key) still raises, naming the missing tree",
          function() dead:overlay():add("k") end, "has left the tree")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

local function run()
  hafen.timer():after(0, body)     -- ...and the step is where a widget of the addon layer may be written
end

hafen.console():on("t125", run)   -- the only way in: a suite does not start itself
