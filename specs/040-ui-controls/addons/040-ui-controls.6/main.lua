-- 040.6 — :slider() and :scrollbar(). Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():slider() builds a real client HSlider and hafen.ui():scrollbar() a bare
-- Scrollbar, both over the SAME :range(min, max)/:value(n)/:onChange(fn) contract -- the slider's :onChange
-- carrying a second FINAL argument the scrollbar's does not, since the engine gives the scrollbar no separate
-- "drag ended" hook. Unlike :progress()'s hard 0..1 (040.3), a write outside :range CLAMPS rather than
-- refuses, because the range itself is an addon-chosen, moving target; narrowing :range with :range(min, max)
-- re-clamps a value that no longer fits WITHOUT firing :onChange, the same direct-field-write discipline
-- 040.3/040.4 pinned for a programmatic :value(v). :range(nil) is refused (R5) like any other required
-- argument, naming the missing bound.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the [manual] line needs -- which closes itself as soon as the slider settles
-- once, or after 90 seconds if it never does.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- How many widgets are in the whole client tree right now.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

-- The shared :range()/:value()/:onChange() contract, proven once per control (slider, then scrollbar) --
-- D-085: each control's own proof is stated here, not borrowed from the other's.
local function checkRanged(name, c)
  local r0 = c:range()
  check((type(r0) == "table") and (r0.min <= r0.max),
        name .. ": widget:range() reads {min=, max=} before anything is ever written",
        tostring(r0 and (tostring(r0.min) .. ".." .. tostring(r0.max))))

  local chained = c:range(0, 10)
  local r1 = c:range()
  check((chained == c) and (r1.min == 0) and (r1.max == 10),
        name .. ": widget:range(min, max) chains and round-trips {min=0, max=10}",
        tostring(r1.min) .. ".." .. tostring(r1.max))

  c:value(4)
  eq(name .. ": widget:value(v) round-trips inside the range", c:value(), 4)
  c:value(999)
  eq(name .. ": widget:value(v) past the max CLAMPS to it, not refused", c:value(), 10)
  c:value(-50)
  eq(name .. ": widget:value(v) past the min CLAMPS to it, not refused", c:value(), 0)
  refuses(name .. ": a non-number widget:value(v) is refused naming the type it wants",
          function() c:value("x") end, "NUMBER")

  c:value(10)
  c:range(0, 5)
  eq(name .. ": narrowing widget:range(...) re-clamps a value that no longer fits", c:value(), 5)

  local fired = 0
  c:onChange(function(...) fired = fired + 1 end)
  c:value(1)
  c:value(3)
  c:range(0, 100)
  eq(name .. ": a programmatic :value(v)/:range(...) does NOT re-enter :onChange (no feedback loop)", fired, 0)

  refuses(name .. ": widget:range(nil) is refused (R5), naming the missing bound",
          function() c:range(nil) end, "min")
  refuses(name .. ": widget:range(1) with only ONE bound is refused, naming the other",
          function() c:range(1) end, "max")
  refuses(name .. ": widget:range(min, max) with min > max is refused, not silently swapped",
          function() c:range(10, 0) end, "exceed")
end

local function run()
  -- The window the [manual] line needs is built FIRST, so the tree count below measures exactly what the
  -- assertions build and nothing else.
  local demo = hafen.ui():window():title("040.6 — slider() / scrollbar()"):size(200, 130):position(60, 60)
  hafen.ui():slider():parent(demo):position(10, 15):size(140, 20):range(0, 100):value(50)
    :onChange(function(v, final)
        hafen.log():write(("040.6: slider now %d, final=%s"):format(v, tostring(final)))
        if final then
          hafen.timer():after(0, function() if demo:exists() then demo:destroy() end end)
        end
      end)
  hafen.ui():scrollbar():parent(demo):position(170, 15):size(14, 90):range(0, 100):value(0)
    :onChange(function(v) hafen.log():write("040.6: scrollbar now " .. v) end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  local base = treeCount()
  local hold = hafen.ui():window():title("040.6 — scratch"):size(240, 260):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). Both builders are verbs on the one section object,
  --    built bare (R4) like every other builder here.
  check(type(hafen.ui().slider) == "function", "hafen.ui() carries :slider() as a verb on the one section object",
        tostring(hafen.ui().slider))
  check(type(hafen.ui().scrollbar) == "function", "hafen.ui() carries :scrollbar() as a verb too",
        tostring(hafen.ui().scrollbar))
  refuses("the slider builder takes no arguments (R4)",
          function() hafen.ui():slider("x") end, "takes no arguments")
  refuses("the scrollbar builder takes no arguments (R4)",
          function() hafen.ui():scrollbar("x") end, "takes no arguments")

  -- 2. A REAL CLIENT HSlider/Scrollbar, in the tree, findable by class, parented to what it was given.
  local s = hafen.ui():slider():parent(hold):position(10, 10)
  local foundS = false
  for _, w in ipairs(hafen.ui():all("@HSlider")) do foundS = foundS or (w == s) end
  check((s:type() == "HSlider") and foundS and (s:parent() == hold),
        "hafen.ui():slider() builds a real HSlider, in the tree, findable by class, parented to what it was given",
        ("type=%s found=%s"):format(s:type(), tostring(foundS)))

  local sb = hafen.ui():scrollbar():parent(hold):position(10, 170)
  local foundSb = false
  for _, w in ipairs(hafen.ui():all("@Scrollbar")) do foundSb = foundSb or (w == sb) end
  check((sb:type() == "Scrollbar") and foundSb and (sb:parent() == hold),
        "hafen.ui():scrollbar() builds a real Scrollbar, in the tree, findable by class, parented to what it"
        .. " was given", ("type=%s found=%s"):format(sb:type(), tostring(foundSb)))

  -- 3. THE SHARED :range()/:value()/:onChange() CONTRACT, proven on EACH control in its own right.
  checkRanged("slider", s)
  checkRanged("scrollbar", sb)

  -- 4. A control with no range (a label) reads :range() as nil, not throwing -- "a verb answers where it
  --    applies" on the name this task adds -- and a write refuses naming the builders that do take one.
  local lbl = hafen.ui():label():parent(hold):position(10, 230):text("x")
  eq("a control with no range reads widget:range() as nil, not throwing", lbl:range(), nil)
  refuses("...and a WRITE refuses, naming the builders that have one",
          function() lbl:range(0, 10) end, "slider")

  -- 5. AND OWNERSHIP STILL HOLDS: the same verbs refuse on a NATIVE widget, naming it native.
  local root = hafen.ui():root()
  refuses("widget:range(min, max) refuses on a NATIVE widget, naming it native",
          function() root:range(0, 10) end, "NATIVE widget")
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native",
          function() root:value(1) end, "NATIVE widget")

  -- 6. TEARDOWN GIVES THE TREE BACK -- both controls and the window they were in, all at once.
  local grown = treeCount()
  hold:destroy()
  local after = treeCount()
  check((grown > base) and (after == base),
        "a slider and a scrollbar die with the window they were put in, and the tree ends the size it started",
        ("%d before, %d built, %d after"):format(base, grown, after))

  -- 7. The one thing a program cannot judge: dragging a REAL slider fires final=false while dragging and
  --    exactly one final=true on release, and a scrollbar drags too.
  manual = manual + 1
  hafen.log():write("[manual] in the window \"040.6 — slider() / scrollbar()\" at 60,60, drag the slider's"
                    .. " thumb back and forth once and release, then drag the scrollbar beside it -- expect:"
                    .. " several \"040.6: slider now N, final=false\" lines while dragging, exactly ONE"
                    .. " \"...final=true\" line on release (which closes the window), \"040.6: scrollbar"
                    .. " now N\" lines as the scrollbar moves, and both controls looking like the client's own")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-6", run)   -- the only way in: a suite does not start itself
