-- 040.7 — :entry(). Self-checking suite; see specs/addons/TESTING.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():entry() builds a real client TextEntry whose content is :value(s), the
-- ONE door (decision A) -- entry:text() is RETIRED, throwing and naming :value(), through the existing
-- Retired table. :onSubmit(fn) (Enter) is a separate name from :onChange(fn) (every keystroke), and a
-- programmatic :value(v) never re-enters either -- the same feedback-loop guarantee 040.3-040.6 pinned for
-- every other value-bearing control. This is the one control whose [manual] line is NOT optional: a
-- TextEntry takes keyboard focus, and typing into it must not also reach the game.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the [manual] line needs -- which the maintainer closes by hand.

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

local function run()
  local base = treeCount()
  local hold = hafen.ui():window():title("040.7 — scratch"):size(240, 120):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). :entry() is a verb on the one section object, built
  --    bare (R4) like every other builder here.
  check(type(hafen.ui().entry) == "function", "hafen.ui() carries :entry() as a verb on the one section object",
        tostring(hafen.ui().entry))
  refuses("the entry builder takes no arguments (R4)",
          function() hafen.ui():entry("x") end, "takes no arguments")

  -- 2. A REAL CLIENT TextEntry, in the tree, findable by class, parented to what it was given.
  local e = hafen.ui():entry():parent(hold):position(10, 10):size(200, 20)
  local found = false
  for _, w in ipairs(hafen.ui():all("@TextEntry")) do found = found or (w == e) end
  check((e:type() == "TextEntry") and found and (e:parent() == hold) and (e:role() == "textentry"),
        "hafen.ui():entry() builds a real TextEntry, in the tree, findable by class and role, parented to what"
        .. " it was given", ("type=%s role=%s found=%s"):format(e:type(), tostring(e:role()), tostring(found)))

  -- 3. :value() ROUND-TRIPS, starting empty.
  eq("a fresh entry's widget:value() reads the empty string", e:value(), "")
  local chained = e:value("gonzalo")
  check(chained == e, "widget:value(s) chains", tostring(chained))
  eq("widget:value(s) round-trips what it was given", e:value(), "gonzalo")

  -- 4. entry:text(s) -- the WRITE -- IS RETIRED, throwing and naming :value(s): the content has ONE door to
  --    write it through. The READ half is NOT retired: widget:text() keeps answering best-effort on an entry
  --    exactly as it always has on every text-bearing widget (docs/addons/api/ui/widget.md), and it must still
  --    match what :value() reads -- a tree-walking introspector (widgetstack) calls :text() on every widget it
  --    finds and must never see it throw.
  eq("entry:text() (the read) is NOT retired -- it still answers best-effort, same as :value()", e:text(), "gonzalo")
  refuses("entry:text(s) (the write) throws naming :value(s) -- one door to WRITE it through",
          function() e:text("nope") end, "value")
  eq("...and the refused write left the value exactly as it was", e:value(), "gonzalo")

  e:value("")
  eq("widget:value(\"\") round-trips back to empty", e:value(), "")

  -- 5. :onChange(fn) and :onSubmit(fn) are TWO SEPARATE SLOTS -- installing one leaves the other exactly as
  --    it was, and a programmatic :value(v) re-enters NEITHER (the feedback-loop guarantee every value-bearing
  --    control in this feature has to keep).
  local changed, submitted = 0, 0
  local function onChangeFn(v) changed = changed + 1 end
  local function onSubmitFn(v) submitted = submitted + 1 end
  e:onChange(onChangeFn)
  eq("widget:onChange() reads back exactly the function just installed", e:onChange(), onChangeFn)
  eq("widget:onSubmit() still reads nil -- installing :onChange did not touch it", e:onSubmit(), nil)
  e:onSubmit(onSubmitFn)
  eq("widget:onSubmit() reads back exactly the function just installed", e:onSubmit(), onSubmitFn)
  eq("widget:onChange() is UNCHANGED by installing :onSubmit -- two slots, not one", e:onChange(), onChangeFn)

  e:value("typed by script")
  e:value("typed again")
  eq("a programmatic widget:value(v) does NOT re-enter :onChange (no feedback loop)", changed, 0)
  eq("a programmatic widget:value(v) does NOT re-enter :onSubmit either", submitted, 0)

  -- 6. Refusals on the value itself: a STRING is required (in LuaJ a number is technically a string, so it is
  --    named out explicitly, exactly as :rows(t) on a radio does for its labels).
  refuses("widget:value(v) refuses a boolean, naming the type it wants",
          function() e:value(true) end, "STRING")
  refuses("widget:value(v) refuses a number too -- a number IS a Lua string, but the wrong kind of one",
          function() e:value(42) end, "STRING")
  refuses("widget:onChange(fn) refuses a non-function",
          function() e:onChange("nope") end, "function")
  refuses("widget:onSubmit(fn) refuses a non-function",
          function() e:onSubmit("nope") end, "function")

  -- 7. A control with neither verb (a label) reads :onSubmit() as nil, not throwing -- "a verb answers where
  --    it applies" -- and a write refuses naming the builder that has one.
  local lbl = hafen.ui():label():parent(hold):position(10, 40):text("x")
  eq("a control with no submit reads widget:onSubmit() as nil, not throwing", lbl:onSubmit(), nil)
  refuses("...and a WRITE refuses, naming hafen.ui():entry()",
          function() lbl:onSubmit(function() end) end, "entry")

  -- 8. AND OWNERSHIP STILL HOLDS: the same verbs refuse on a NATIVE widget, naming it native.
  local root = hafen.ui():root()
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native",
          function() root:value("x") end, "NATIVE widget")
  refuses("widget:onSubmit(fn) refuses on a NATIVE widget, naming it native",
          function() root:onSubmit(function() end) end, "NATIVE widget")

  -- 9. TEARDOWN GIVES THE TREE BACK -- the entry dies with the window it was put in.
  local grown = treeCount()
  hold:destroy()
  local after = treeCount()
  check((grown > base) and (after == base),
        "an entry dies with the window it was put in, and the tree ends the size it started",
        ("%d before, %d built, %d after"):format(base, grown, after))

  -- 10. WHAT A PROGRAM CANNOT JUDGE. This control is the one whose manual check is NOT optional: a TextEntry
  --     takes keyboard focus, and typing into it must be proven, by hand, to go nowhere else.
  local demo = hafen.ui():window():title("040.7 — entry()"):size(220, 70):position(60, 60)
  local live = hafen.ui():entry():parent(demo):position(10, 10):size(200, 20)
    :onChange(function(v) hafen.log():write("040.7: onChange now \"" .. v .. "\"") end)
    :onSubmit(function(v)
        hafen.log():write("040.7: onSubmit \"" .. v .. "\"")
        hafen.timer():after(0, function() if demo:exists() then demo:destroy() end end)
      end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  manual = manual + 1
  hafen.log():write("[manual] in the window \"040.7 — entry()\" at 60,60, click the text field, type a few"
                    .. " letters and press Enter -- expect: one \"040.7: onChange now \\\"...\\\"\" line PER"
                    .. " KEYSTROKE with the growing text, then exactly one \"040.7: onSubmit \\\"...\\\"\" line"
                    .. " on Enter (which closes the window), and the field looking like the client's own")

  manual = manual + 1
  hafen.log():write("[manual] NOT OPTIONAL: click a fresh entry, type letters that are also movement keys or"
                    .. " hotkeys (e.g. w a s d, or a chat key), then press Enter -- expect: your character does"
                    .. " NOT move, no hotkey fires and no chat box opens -- every keystroke lands in the field"
                    .. " ONLY, exactly the letters you typed appear in it, and nothing reaches the game")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-7", run)   -- the only way in: a suite does not start itself
