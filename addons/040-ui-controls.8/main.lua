-- 040.8 — :scroll(). Self-checking suite; see specs/addons/TESTING.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():scroll() is a scrolling container over the client's own Scrollport
-- shape: a bar and an inner container (cont), neither handed out by a dedicated verb. :parent(sp) on any
-- control redirects into cont -- THE TRAP THIS TASK EXISTS TO NOT FALL INTO: Widget.add does not route
-- through addchild, and this control (like haven.Scrollport itself) only overrides addchild, so a plain
-- add() would drop the child BESIDE the bar instead of inside the scrolling area, and it would look almost
-- right. The bar itself is a real, OWNED Scrollbar -- found structurally through :children(), never a
-- dedicated verb -- and answers the same :range()/:value()/:onChange() as a bare :scrollbar() (040.6): it
-- comes alive (max > 0) once the content parented into the port outgrows its box, and stays inert (max ==
-- 0) otherwise. The container itself adds no new verb of its own.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the [manual] line needs -- which closes itself after 90 seconds.

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

-- A scrollport's two children, told apart by class: the bar (a real Scrollbar) and its inner container --
-- structural, since neither is a dedicated verb (the container is deliberately never handed out).
local function barAndCont(sp)
  local bar, cont
  for _, k in ipairs(sp:children()) do
    if k:type() == "Scrollbar" then bar = k else cont = k end
  end
  return bar, cont
end

local function run()
  -- The window the [manual] line needs is built FIRST, so `base` below measures exactly what the
  -- assertions build and nothing else.
  local demo = hafen.ui():window():title("040.8 — scroll()"):size(160, 120):position(60, 60)
  local dsp = hafen.ui():scroll():parent(demo):position(10, 10):size(120, 90)
  for i = 1, 8 do
    hafen.ui():label():text("row " .. i):size(80, 14):position(0, (i - 1) * 14):parent(dsp)
  end
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  local base = treeCount()
  local hold = hafen.ui():window():title("040.8 — scratch"):size(260, 260):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). :scroll() is a verb on the one section object, built
  --    bare (R4) like every other builder here.
  check(type(hafen.ui().scroll) == "function", "hafen.ui() carries :scroll() as a verb on the one section object",
        tostring(hafen.ui().scroll))
  refuses("the builder takes no arguments (R4)", function() hafen.ui():scroll("x") end, "takes no arguments")

  -- 2. ONE CONTROL, IN THE TREE -- no single engine class of its own (Scrollport+Scrollbar is two classes
  --    glued into one control), so :type() reads the container's own, "Widget", exactly as :radio() does.
  local sp = hafen.ui():scroll():parent(hold):position(10, 10):size(100, 60)
  eq("a scroll control has no single engine class of its own: :type() reads \"Widget\"", sp:type(), "Widget")
  eq("widget:info().owned is true for a control this addon built", sp:info().owned, true)
  eq("it is parented to what it was given", sp:parent(), hold)

  -- 3. THE COMPOSITE'S OWN TWO CHILDREN: exactly a bar and its (never handed-out) inner container -- and
  --    the bar is a real, OWNED Scrollbar, which is what lets :range()/:value()/:onChange() reach it at all
  --    (an unowned/native widget refuses those verbs by name, per 040.6).
  eq("the composite has exactly two children of its own: the bar and its inner container", #sp:children(), 2)
  local bar, cont = barAndCont(sp)
  check(bar ~= nil, "one of the two is a real Scrollbar", tostring(bar))
  check(cont ~= nil, "...and the other is its inner container", tostring(cont))
  eq("the bar is OWNED (a real Owned.Control, not a borrowed/native widget)", bar:info().owned, true)

  -- 4. THE TRAP: a child :parent(sp)'d lands INSIDE cont, never beside the bar -- asserted through
  --    :parent() from the CHILD, not by eye.
  local child = hafen.ui():label():text("row"):size(40, 14):position(5, 5):parent(sp)
  check(child:parent() ~= sp, "a child parented to the port is NOT a direct child of it...", tostring(child:parent()))
  check(child:parent() == cont, "...it is inside the port's own inner container", tostring(child:parent()))
  eq("...and that container's parent is the port itself (two levels deep, not one)", child:parent():parent(), sp)
  eq("the composite's OWN two children are unchanged: the child did not land beside the bar",
     #sp:children(), 2)

  -- 5. CONTENT SHORTER THAN THE BOX LEAVES THE BAR INERT (max == min == 0) -- a fresh port so the proof
  --    starts from nothing already inside it.
  local sp2 = hafen.ui():scroll():parent(hold):position(10, 80):size(60, 60)
  local bar2 = barAndCont(sp2)
  hafen.ui():separator():size(10, 10):position(0, 0):parent(sp2)   -- shorter than the 60px box
  local r2 = bar2:range()
  check((r2.min == 0) and (r2.max == 0), "content shorter than the port leaves the bar's range empty (inert)",
        tostring(r2.min) .. ".." .. tostring(r2.max))

  -- 6. CONTENT TALLER THAN THE BOX MAKES THE BAR LIVE (max > min), and its :value()/:onChange() answer
  --    exactly like a bare :scrollbar() (040.6): round-trips, clamps outside the range, and a programmatic
  --    write never re-enters :onChange.
  local sp3 = hafen.ui():scroll():parent(hold):position(80, 80):size(60, 60)
  local bar3 = barAndCont(sp3)
  hafen.ui():separator():size(10, 300):position(0, 0):parent(sp3)   -- taller than the 60px box
  local r3 = bar3:range()
  check(r3.max > r3.min, "content taller than the port makes the bar's range LIVE (max > min)",
        tostring(r3.min) .. ".." .. tostring(r3.max))

  bar3:value(10)
  eq("the bar's widget:value(v) round-trips inside its range", bar3:value(), 10)
  bar3:value(99999)
  eq("...and CLAMPS to the max rather than refusing, like a bare :scrollbar()", bar3:value(), r3.max)
  bar3:value(-99999)
  eq("...and clamps to the min (0) the same way", bar3:value(), r3.min)

  local fired = 0
  bar3:onChange(function(...) fired = fired + 1 end)
  bar3:value(5)
  bar3:value(20)
  eq("a programmatic widget:value(v) on the bar does NOT re-enter :onChange (no feedback loop)", fired, 0)

  -- 7. THE CONTAINER CLIPS TO THE PORT'S BOX: cont's own size stays the port's, not the tall content's --
  --    the geometry Widget.draw(g, true) clips every child to (spec 040 risks/gotchas).
  local contSz, spSz, barSz = cont:size(), sp:size(), bar:size()
  local expW = spSz.x - barSz.x
  check((contSz.x == expW) and (contSz.y == spSz.y),
        "the inner container's own box is the port's, not the (taller) content's -- what clips a child to it",
        ("cont %dx%d, port %dx%d, bar width %d"):format(contSz.x, contSz.y, spSz.x, spSz.y, barSz.x))

  -- 8. AND OWNERSHIP STILL HOLDS: the bar's own verbs refuse on a NATIVE widget, naming it native --
  --    re-asserted here because it is what lets the bar answer them AT ALL when it is ours.
  local root = hafen.ui():root()
  refuses("widget:range(min, max) refuses on a NATIVE widget, naming it native",
          function() root:range(0, 10) end, "NATIVE widget")
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native",
          function() root:value(1) end, "NATIVE widget")

  -- 9. TEARDOWN GIVES THE TREE BACK -- every port, its bar, its container and everything parented into it,
  --    all at once.
  local grown = treeCount()
  hold:destroy()
  local after = treeCount()
  check((grown > base) and (after == base),
        "every scroll control dies with the window it was put in, and the tree ends the size it started",
        ("%d before, %d built, %d after"):format(base, grown, after))

  -- 10. The one thing a program cannot judge: wheeling over a real one actually scrolls it.
  manual = manual + 1
  hafen.log():write("[manual] in the window \"040.8 — scroll()\" at 60,60, hover the scrolling box and turn"
                    .. " the mouse wheel, then drag its bar directly -- expect: the eight \"row N\" labels"
                    .. " scroll smoothly either way, clipped to the box (no row ever draws outside it), and"
                    .. " the bar looks like the client's own")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-8", run)   -- the only way in: a suite does not start itself
