-- 040.5 — :radio() as ONE control. Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():radio() is ONE control, not a group object plus N buttons: :rows{...}
-- builds a real client RadioGroup of RadioButtons, stacked downward from the control's own :position, one
-- row height apart. :value(label) is the one door to which row is checked -- and unlike the checkbox's
-- :value(v) (which writes ACheckBox's field directly), a radio's programmatic write cannot go through the
-- engine's own RadioGroup.check(), because that call ALWAYS fires the group's changed() hook -- the same
-- hook a real click fires. So the write flips the two RadioButtons' own state directly instead, which is
-- what this suite's feedback-loop check proves. RadioGroup/RadioButton themselves never surface: a child
-- reads back as a real "RadioButton" only because :type() has nothing to climb past on a plain engine widget.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the [manual] line needs -- which closes itself after two clicks, or after
-- 90 seconds if it never gets them.

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

-- How many widgets are in the whole client tree right now. Rows added/replaced/removed must show up here.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

local function run()
  -- The window the [manual] line needs is built FIRST, so the tree count below measures exactly what the
  -- assertions build and nothing else.
  local clicks = 0
  local demo = hafen.ui():window():title("040.5 — radio()"):size(160, 100):position(60, 60)
  hafen.ui():radio():parent(demo):position(10, 10)
    :rows{"Small", "Medium", "Large"}
    :onChange(function(pick)
        clicks = clicks + 1
        hafen.log():write("040.5: now " .. pick)
        if clicks >= 2 then
          hafen.timer():after(0, function() if demo:exists() then demo:destroy() end end)
        end
      end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  local base = treeCount()
  local hold = hafen.ui():window():title("040.5 — scratch"):size(240, 220):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). hafen.ui():radio() is a verb on the one section
  --    object, built bare (R4) like every other builder here.
  check(type(hafen.ui().radio) == "function", "hafen.ui() carries :radio() as a verb on the one section object",
        tostring(hafen.ui().radio))
  refuses("the builder takes no arguments (R4)", function() hafen.ui():radio("x") end, "takes no arguments")

  -- 2. ONE CONTROL, IN THE TREE -- not a nineteenth entity: :type() has no single engine class to climb to
  --    (there is no client class named "a radio"), so it reads the container's own, "Widget".
  local r = hafen.ui():radio():parent(hold):position(10, 10)
  eq("a radio control has no single engine class of its own: :type() reads \"Widget\"", r:type(), "Widget")
  eq("widget:info().owned is true for a control this addon built", r:info().owned, true)

  -- 3. :rows{...} BUILDS THE BUTTONS, stacked downward from the control's own position, one row height
  --    apart -- three real client RadioButtons, each with the caption it was given, in order.
  r:rows{"Quality", "Amount", "Name"}
  local kids = r:children()
  eq("three rows build three buttons under one parent", #kids, 3)
  local wantLabels, okType, okText, okStack, y = {"Quality", "Amount", "Name"}, true, true, true, 0
  for i, k in ipairs(kids) do
    if k:type() ~= "RadioButton" then okType = false end
    if k:text() ~= wantLabels[i] then okText = false end
    local p = k:position()
    if (p.x ~= 0) or (p.y ~= y) then okStack = false end
    y = y + k:size().y
  end
  check(okType, "...each one a real client RadioButton (RadioGroup/RadioButton never surface as such)",
        tostring(okType))
  check(okText, "...captioned in the order the rows were given", tostring(okText))
  check(okStack, "...stacked downward from the control's own :position, one row height apart", tostring(okStack))

  -- 4. :size() COVERS THE WHOLE STACK -- its own bounding box over the rows, not one row's.
  local sz, wantW = r:size(), 0
  for _, k in ipairs(kids) do
    local ksz = k:size()
    if ksz.x > wantW then wantW = ksz.x end
  end
  check((sz.x == wantW) and (sz.y == y), ":size() covers the whole stack (the bounding box over its rows)",
        ("got %dx%d, want %dx%d"):format(sz.x, sz.y, wantW, y))

  -- 5. :value() -- nil until a row is checked, then the checked LABEL, both ways, chaining; an unknown
  --    label is refused NAMING the rows rather than failing as a generic lookup error.
  eq("no row is checked before :value(v) is ever written: :value() reads nil", r:value(), nil)
  local chained = r:value("Amount")
  check((chained == r) and (r:value() == "Amount"), "widget:value(v) chains and reads the checked label back",
        tostring(r:value()))
  refuses("widget:value(\"x\") for an unknown label is refused, naming the rows",
          function() r:value("Nope") end, "Quality")

  -- 6. THE FEEDBACK-LOOP GUARANTEE, 040.4's rule again: a programmatic :value(v) must NOT re-enter
  --    :onChange -- it cannot go through the engine's own RadioGroup.check(), which always fires it.
  local fired = 0
  r:onChange(function(v) fired = fired + 1 end)
  r:value("Quality")
  r:value("Name")
  r:value("Amount")
  eq("a programmatic widget:value(v) does NOT re-enter widget:onChange (no feedback loop)", fired, 0)

  -- 7. A REPEATED ROW LABEL IS REFUSED -- :value(label) needs one row per label to mean anything.
  refuses("a repeated row label is refused, since :value(label) needs it to be unique",
          function() r:rows{"A", "A"} end, "repeated")

  -- 8. RE-:rows{} REPLACES THE SET -- the old buttons are gone, not merely hidden, and the previous
  --    selection does not survive a set it is no longer part of.
  local before = treeCount()
  r:rows{"One", "Two"}
  eq("re-:rows{} replaces the whole set: two rows now, not five", #r:children(), 2)
  eq("...and the previous selection does not survive it: :value() reads nil again", r:value(), nil)
  eq("...and the old three buttons are destroyed, not merely hidden (net -1 in the tree)",
     treeCount(), before - 1)

  -- 9. AN EMPTY :rows{} IS AN EMPTY CONTROL, NOT AN ERROR.
  r:rows{}
  eq("an empty :rows{} is an empty control, not an error: no children", #r:children(), 0)
  eq("...and :value() reads nil", r:value(), nil)
  local esz = r:size()
  check((esz.x == 0) and (esz.y == 0), "...and its size collapses to zero", ("%dx%d"):format(esz.x, esz.y))

  -- 10. :rows IS A CAPABILITY, NOT EVERY CONTROL'S: a control with no row source reads nil on it, and a
  --     write refuses naming the builder that has one -- re-asserting "a verb answers where it applies"
  --     on the name this task adds.
  local lbl = hafen.ui():label():parent(hold):position(10, 190):text("x")
  eq("a control with no row source reads widget:rows() as nil, not throwing", lbl:rows(), nil)
  refuses("...and a WRITE refuses, naming the builder that has one",
          function() lbl:rows{"x"} end, "radio")

  -- 11. AND OWNERSHIP STILL HOLDS: the same verbs refuse on a NATIVE widget, naming it native.
  local root = hafen.ui():root()
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native",
          function() root:value("x") end, "NATIVE widget")
  refuses("widget:rows(t) refuses on a NATIVE widget, naming it native",
          function() root:rows{"x"} end, "NATIVE widget")

  -- 12. TEARDOWN GIVES THE TREE BACK -- the control, its rows and the window they were in, all at once.
  local grown = treeCount()
  hold:destroy()
  local after = treeCount()
  check((grown > base) and (after == base),
        "a radio dies with the window it was put in, and the tree ends the size it started",
        ("%d before, %d built, %d after"):format(base, grown, after))

  -- 13. The one thing a program cannot judge: a real click fires :onChange, and picking a new row clears
  --     the one that was checked before it.
  manual = manual + 1
  hafen.log():write("[manual] in the window \"040.5 — radio()\" at 60,60, click \"Small\" then \"Medium\""
                    .. " -- expect: the log prints \"040.5: now Small\" then \"040.5: now Medium\", Small's"
                    .. " tick clearing as soon as Medium is checked (only one row at a time), and the window"
                    .. " closing itself after the second click")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-5", run)   -- the only way in: a suite does not start itself
