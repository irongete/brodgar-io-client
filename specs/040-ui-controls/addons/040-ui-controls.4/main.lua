-- 040.4 — check(), and the :value()/:onChange() spine. Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():check() builds a real client CheckBox; widget:image(up, down, hoverUp,
-- hoverDown) completes it as an ICheckBox exactly as :button()'s face setter completes a Button to an
-- IButton (040.2), but with FOUR faces -- a checkbox carries two persistent states (checked/unchecked),
-- each with its own hover, where a button has one gesture. This is where the value spine first answers
-- :onChange(fn), so this is where its rules are pinned: a programmatic :value(v) writes the field directly
-- and must NOT re-enter :onChange (no feedback loop), and setting :onChange twice keeps only the later
-- handler. The user-driven half -- a real click firing :onChange -- is the one thing a program cannot judge.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the [manual] line needs -- which closes itself after its checkbox is clicked
-- three times, or after 90 seconds if it never is.

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

local function nop() end

-- How many widgets are in the whole client tree right now. A rebuild must REPLACE a widget, not add one.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

-- A checkbox THIS ADDON DID NOT BUILD, if one happens to be in the tree right now (the AddOns panel's own
-- per-addon enable checkboxes, whether that window is open or merely built) -- tolerates either.
local function nativeCheck()
  for _, w in ipairs(hafen.ui():all("@CheckBox")) do
    if not w:info().owned then return w end
  end
end

-- Four of the client's own small button faces -- not semantically a checkbox's tick, but valid resources of
-- the same size class, which is all the four-face form needs to prove it builds and reads back correctly.
local UP, DOWN, HOVER_UP, HOVER_DOWN =
  "gfx/hud/buttons/addu", "gfx/hud/buttons/addd", "gfx/hud/buttons/addh", "gfx/hud/buttons/subh"

local base, hold, armed   -- built in phase 1, judged in phase 2 (one tick later)

-- PHASE 2 — one tick after the statements below, so `armed` has been drawn and its face is no longer a
-- choice. Everything here needs that tick; nothing else in the run does.
local function phase2()
  refuses("once a checkbox is on screen the face setter refuses, naming that a face is chosen at build time",
          function() armed:image(UP, DOWN, HOVER_UP, HOVER_DOWN) end, "already on screen")
  hold:destroy()
  eq("everything built here dies with the window it was put in, rebuilt controls included", treeCount(), base)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  -- The window the [manual] line needs is built FIRST, so the tree count below measures exactly what the
  -- assertions build and nothing else.
  -- The close is DEFERRED a tick rather than called straight from :onChange: a checkbox fires from
  -- Window.mousedown's own propagation, which still runs parent.setfocus(this)/raise() on `this` AFTER
  -- the click returns -- destroying the window synchronously nulls its parent out from under that and
  -- crashes the UI thread. hafen.timer():after(0, fn) runs the destroy on the next tick instead, once
  -- the window's own mousedown has finished with itself.
  local clicks = 0
  local demo = hafen.ui():window():title("040.4 — check()"):size(160, 50):position(60, 60)
  hafen.ui():check():parent(demo):position(10, 15):text("Show grid")
    :onChange(function(v)
        clicks = clicks + 1
        hafen.log():write("040.4: now " .. tostring(v))
        if clicks >= 3 then
          hafen.timer():after(0, function() if demo:exists() then demo:destroy() end end)
        end
      end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  base = treeCount()
  hold = hafen.ui():window():title("040.4 — scratch"):size(240, 170):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). hafen.ui():check() is a verb on the one section
  --    object, built bare (R4) like every other builder here.
  check(type(hafen.ui().check) == "function", "hafen.ui() carries :check() as a verb on the one section object",
        tostring(hafen.ui().check))
  refuses("the builder takes no arguments (R4)", function() hafen.ui():check("x") end, "takes no arguments")

  -- 2. A REAL CLIENT CheckBox, in the tree, findable by class -- and :text(s) writes its caption, the write
  --    half of a verb that already reads on every text-bearing widget.
  local c = hafen.ui():check():parent(hold):position(10, 10):text("Hide empty")
  local found = false
  for _, w in ipairs(hafen.ui():all("@CheckBox")) do found = found or (w == c) end
  check((c:type() == "CheckBox") and (c:text() == "Hide empty") and found and (c:parent() == hold),
        "hafen.ui():check() builds a real CheckBox, in the tree, findable by class, with its caption written",
        ("type=%s text=%s found=%s"):format(c:type(), tostring(c:text()), tostring(found)))

  -- 3. THE VALUE SPINE'S FIRST RULES. :value() round-trips both ways and chains, starting false; an
  --    explicit nil is refused (R5), not read as an arity; a non-boolean write names the type it wants.
  check((c:value() == false) and (c:value(true) == c) and (c:value() == true)
        and (c:value(false) == c) and (c:value() == false),
        "widget:value(v) round-trips both ways on a checkbox, starts false, and chains",
        tostring(c:value()))
  refuses("widget:value(nil) is refused (R5), not read as an arity",
          function() c:value(nil) end, "must not be nil")
  refuses("a non-boolean write is refused naming the type it wants",
          function() c:value("yes") end, "BOOLEAN")

  -- 4. THE FEEDBACK-LOOP GUARANTEE: a programmatic :value(v) writes the field directly and must NOT
  --    re-enter :onChange -- the rule this task pins for every later value-bearing control.
  local fired = 0
  local h1 = function(v) fired = fired + 1 end
  c:onChange(h1)
  c:value(true)
  c:value(false)
  c:value(true)
  eq("a programmatic widget:value(v) does NOT re-enter widget:onChange (no feedback loop)", fired, 0)

  -- 5. :onChange SET A SECOND TIME KEEPS THE LATER HANDLER ONLY -- read back by IDENTITY, which needs no
  --    click to prove: the installed function is exactly the one most recently given, never both.
  local h2 = function(v) fired = fired + 100 end
  c:onChange(h2)
  eq("widget:onChange(fn) set a second time keeps ONLY the later handler (read back by identity)",
     c:onChange(), h2)
  check(c:onChange() ~= h1, "...and the first handler is gone, not merely shadowed",
        tostring(c:onChange() == h1))

  -- 6. A FRESH CHECKBOX HAS NO HANDLER YET, and a control with no value at all (a label) reads nil on both
  --    verbs too -- re-asserting 040.3's "answers where it applies" on the two this task adds.
  local bare = hafen.ui():check():parent(hold):position(10, 40)
  eq("a fresh checkbox has no handler yet: widget:onChange() reads nil", bare:onChange(), nil)
  local lbl = hafen.ui():label():parent(hold):position(10, 70):text("x")
  eq("a control with no value reads widget:onChange() as nil too, not throwing", lbl:onChange(), nil)
  refuses("...and a WRITE refuses, naming that it holds nothing",
          function() lbl:onChange(nop) end, "holds nothing")

  -- 7. THE FOUR-FACE FORM. widget:image(up, down, hoverUp, hoverDown) completes the SAME builder as an
  --    ICheckBox -- exactly four faces, no more, no fewer -- and the checked state and the :onChange
  --    handler installed before it outlive the rebuild, same as a button's :onPress (040.2).
  local ic = hafen.ui():check():parent(hold):position(10, 100):value(true):onChange(h1)
  refuses("three faces are not enough: the fourth (hoverDown) is not optional",
          function() ic:image(UP, DOWN, HOVER_UP) end, "hoverDown")
  refuses("five faces are one too many: a checkbox takes exactly four",
          function() ic:image(UP, DOWN, HOVER_UP, HOVER_DOWN, UP) end, "exactly FOUR")
  local chained = ic:image(UP, DOWN, HOVER_UP, HOVER_DOWN)
  check((chained == ic) and (ic:type() == "ICheckBox") and (ic:value() == true) and (ic:onChange() == h1),
        "the four-face form chains, switches the class to ICheckBox, and carries :value()/:onChange across",
        ("type=%s value=%s handler=%s"):format(ic:type(), tostring(ic:value()), tostring(ic:onChange() == h1)))
  local faces = ic:image()
  check((faces.up == UP) and (faces.down == DOWN) and (faces.hoverUp == HOVER_UP)
        and (faces.hoverDown == HOVER_DOWN),
        "widget:image() reads the four faces back exactly as they were named",
        ("%s/%s/%s/%s"):format(tostring(faces.up), tostring(faces.down), tostring(faces.hoverUp),
                                tostring(faces.hoverDown)))
  eq("an image checkbox has no caption: widget:text() reads nil rather than throwing", ic:text(), nil)
  refuses("...and widget:text(s) refuses on it, naming what it shows instead",
          function() ic:text("nope") end, "PICTURE")

  -- 8. AND IT IS STILL A CONTROL VERB ON A CONTROL YOU OWN -- the 040.1 provenance test, re-asserted on
  --    both new verbs, since neither existed on a checkbox before this task.
  local root = hafen.ui():root()
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native",
          function() root:value(true) end, "NATIVE widget")
  refuses("widget:onChange(fn) refuses on a NATIVE widget, naming it native",
          function() root:onChange(nop) end, "NATIVE widget")
  local nat = nativeCheck()
  if nat ~= nil then
    refuses("a checkbox the CLIENT built refuses widget:value(v), naming it native",
            function() nat:value(true) end, "NATIVE widget")
  end

  -- 9. What phase 2 judges: one checkbox left on screen, armed by the next tick.
  armed = hafen.ui():check():parent(hold):position(10, 130):text("Armed")

  -- 10. The one thing a program cannot judge: that a real click fires :onChange and the tick looks native.
  manual = manual + 1
  hafen.log():write("[manual] click the checkbox \"Show grid\" in the window \"040.4 — check()\" at 60,60,"
                    .. " three times -- expect: the log printing \"040.4: now true\", \"040.4: now false\","
                    .. " \"040.4: now true\" in turn, the tick mark looking like the client's own, and the"
                    .. " window closing itself after the third click")

  hafen.timer():after(0.5, phase2)   -- one tick to arm what is on screen; phase 2 closes the run
end

hafen.slash():register("t040-4", run)   -- the only way in: a suite does not start itself
