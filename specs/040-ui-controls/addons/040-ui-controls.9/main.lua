-- 040.9 — the row bridge, and :list(). Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():list() is a real client SListBox built over LuaRows, the Lua-array ->
-- items()/makeitem() bridge every later model-backed control (dropdown, menu, table) reuses rather than
-- re-deriving. :rows(t) takes a plain array -- a string becomes a text row, an {icon=, text=} table an
-- icon+text row, and a table may mix both freely, chosen PER ELEMENT. :value()/:value(v) is the selection --
-- the SAME Lua value :rows(t) was given, so it round-trips by == -- and :onChange(fn) fires on a real pick
-- only, the same value spine every other control in this feature already answers. :rowHeight(n) defaults to
-- the client's own label height and, like a face setter, is chosen while the control is being built.
--
-- WHY ROW WIDGETS NEED A TICK. SListBox builds its row widgets lazily, from update() on the normal per-frame
-- tick -- not synchronously inside :rows(t) -- so the checks that inspect what actually got BUILT run one
-- tick later, in phase 2 (the same two-phase shape 040.2's face-setter suite uses for its own arming check).
-- The tree-size BASELINE needs the same tick even earlier: it is measured against demo's own list, whose
-- rows would otherwise still be lazily materialising when the final count runs and read as a leak that
-- never was one -- so run() itself defers into phase 1 by one tick before touching treeCount() at all.
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

-- A list's own ROW widgets, told apart from its native Scrollbar by class -- structural, since the bar is
-- never handed out by a dedicated verb here (unlike 040.8's :scroll()).
local function rowWidgets(list)
  local rows = {}
  for _, k in ipairs(list:children()) do
    if k:type() ~= "Scrollbar" then table.insert(rows, k) end
  end
  return rows
end

-- One row widget's own content -- the TextItem/IconText makeitem() actually built, one level inside the
-- ItemWidget wrapper that carries the click-to-select behaviour.
local function rowContent(row)
  return row:children()[1]
end

local ICON                                                -- loaded in run(), used to build rows in phase 1
local base, hold                                          -- built in phase 1, torn down at the end of phase 2
local strRows, strList, iconRows, iconList, mixRows, mixList, armedList   -- judged in phase 2 (one tick later)

-- PHASE 2 — one tick after the statements below, so every list here has had an update() pass and actually
-- built its row widgets, and armedList has been drawn (its :rowHeight(n) is now refused).
local function phase2()
  -- 1. A STRING TABLE RENDERS TEXT ROWS.
  local srows = rowWidgets(strList)
  eq("a 3-row string table renders exactly 3 row widgets", #srows, 3)
  if #srows == 3 then
    check((rowContent(srows[1]):type() == "TextItem") and (rowContent(srows[2]):type() == "TextItem")
          and (rowContent(srows[3]):type() == "TextItem"),
          "...and each one is a TextItem, the client's own ready-made text row",
          rowContent(srows[1]):type())
  end

  -- 2. AN ICON TABLE RENDERS ICON ROWS.
  local irows = rowWidgets(iconList)
  eq("a 2-row {icon=, text=} table renders exactly 2 row widgets", #irows, 2)
  if #irows == 2 then
    check((rowContent(irows[1]):type() == "IconText") and (rowContent(irows[2]):type() == "IconText"),
          "...and each one is an IconText, the client's own ready-made icon+text row",
          rowContent(irows[1]):type())
  end

  -- 3. A MIXED TABLE TAKES THE RIGHT ROW PER ELEMENT.
  local mrows = rowWidgets(mixList)
  eq("a mixed table (string, icon table, string) renders exactly 3 row widgets", #mrows, 3)
  if #mrows == 3 then
    check((rowContent(mrows[1]):type() == "TextItem") and (rowContent(mrows[2]):type() == "IconText")
          and (rowContent(mrows[3]):type() == "TextItem"),
          "...and the shape is chosen PER ELEMENT: text, icon, text -- in that order",
          ("%s / %s / %s"):format(rowContent(mrows[1]):type(), rowContent(mrows[2]):type(),
                                   rowContent(mrows[3]):type()))
  end

  -- 4. :rowHeight(n) IS BUILDING-ONLY, LIKE A FACE SETTER -- refused once the control is on screen.
  refuses("once a list is on screen, :rowHeight(n) refuses, naming that it is chosen at build time",
          function() armedList:rowHeight(50) end, "already on screen")

  hold:destroy()
  eq("everything built here dies with the window it was put in, and the tree ends the size it started",
     treeCount(), base)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- PHASE 1 — one tick after `run()`, so demo's OWN list has already had its update() pass and its row widgets
-- exist: SListBox builds rows lazily, same as every list this suite builds, so `base` has to be measured
-- once that lazy build is done -- otherwise it fires between `base` and the final count and reads as a leak
-- that never was one.
local function phase1()
  base = treeCount()
  hold = hafen.ui():window():title("040.9 — scratch"):size(300, 260):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). :list() is a verb on the one section object, built bare
  --    (R4) like every other builder here.
  check(type(hafen.ui().list) == "function", "hafen.ui() carries :list() as a verb on the one section object",
        tostring(hafen.ui().list))
  refuses("the builder takes no arguments (R4)", function() hafen.ui():list("x") end, "takes no arguments")

  -- 2. BUILT, AND IN THE TREE -- a real client SListBox this addon owns.
  local bare = hafen.ui():list():parent(hold):position(10, 10):size(50, 50)
  check((bare:type() == "SListBox") and (bare:info().owned == true) and (bare:parent() == hold),
        "hafen.ui():list() builds a real client SListBox this addon owns, parented where it was put",
        ("%s owned=%s"):format(bare:type(), tostring(bare:info().owned)))

  -- 3. A BARE LIST HAS NO ROWS, AND :rows{} IS AN EMPTY LIST -- NOT AN ERROR.
  eq("a bare list's :rows() reads nil -- :rows(t) was never called", bare:rows(), nil)
  eq("...and its :value() reads nil -- nothing is selected", bare:value(), nil)
  local ok = pcall(function() bare:rows({}) end)
  check(ok, "an explicit :rows{} is an EMPTY list, not an error", tostring(ok))
  eq("...and it still reads back an empty table", #bare:rows(), 0)

  -- 4. A STRING TABLE, AN ICON TABLE, AND A MIXED TABLE -- built here, judged in phase 2 once their row
  --    widgets exist.
  strRows = {"Alpha", "Beta", "Gamma"}
  strList = hafen.ui():list():parent(hold):position(10, 70):size(180, 70):rows(strRows)
  eq("widget:rows() reads back the EXACT table :rows(t) was given", strList:rows(), strRows)

  iconRows = {{icon = ICON, text = "Bucket"}, {icon = ICON, text = "Sickle"}}
  iconList = hafen.ui():list():parent(hold):position(10, 150):size(180, 70):rows(iconRows)

  mixRows = {"Alpha", {icon = ICON, text = "Bucket"}, "Gamma"}
  mixList = hafen.ui():list():parent(hold):position(200, 10):size(90, 90):rows(mixRows)

  -- 5. :value()/:value(v) IS THE SELECTION -- the SAME Lua value :rows(t) was given, round-tripped by ==.
  eq("a fresh list's :value() is nil -- nothing selected yet", strList:value(), nil)
  local chained = strList:value(strRows[2])
  check(chained == strList, "widget:value(v) chains, like every other setter", tostring(chained))
  eq("...and widget:value() reads back the exact row that was named", strList:value(), strRows[2])
  strList:value(strRows[1])
  eq("...writing a different row moves the selection", strList:value(), strRows[1])
  refuses("widget:value(v) naming a value that is not one of the current rows is refused",
          function() strList:value("Not a row") end, "not one of its current rows")

  -- 6. A ROW MUST BE A STRING OR AN {icon=, text=} TABLE -- validated before anything is torn down, so a bad
  --    table leaves the existing rows exactly as they were. One control, reused for all four refusals, so a
  --    rejected call never leaves an orphan behind (unlike a fresh hafen.ui():list() built just to fail).
  local badRows = hafen.ui():list():parent(hold):position(10, 225):size(80, 20)
  refuses("a row that is neither a string nor an icon table is refused, naming the two shapes",
          function() badRows:rows{1} end, "STRING or an")
  refuses("a table row with no \"text\" key is refused, naming it",
          function() badRows:rows{{icon = ICON}} end, "text")
  refuses("a table row with no \"icon\" key is refused, naming the text-only door",
          function() badRows:rows{{text = "Bucket"}} end, "icon")
  refuses("widget:rows(nil) is refused (R5), not read as an arity", function() strList:rows(nil) end,
          "must not be nil")
  local before = strList:rows()
  local badOk = pcall(function() strList:rows{{text = "Bucket"}} end)
  check((not badOk) and (strList:rows() == before), "a rejected :rows(t) leaves the existing rows untouched",
        tostring(strList:rows() == before))

  -- 7. :onChange(fn) FIRES FROM A REAL INTERACTION ONLY -- a programmatic :value(v) never re-enters it.
  local fired = 0
  strList:onChange(function(row) fired = fired + 1 end)
  strList:value(strRows[1])
  strList:value(strRows[3])
  eq("a programmatic widget:value(v) does NOT re-enter :onChange (no feedback loop)", fired, 0)

  -- 8. :rowHeight(n) DEFAULTS AND OVERRIDES.
  local dflt = hafen.ui():list():parent(hold):position(200, 110):size(50, 40)
  check(dflt:rowHeight() > 0, "a bare list's :rowHeight() defaults to a positive pixel height",
        tostring(dflt:rowHeight()))
  local custom = hafen.ui():list():parent(hold):position(200, 160):size(50, 40):rowHeight(30)
  eq("...and :rowHeight(n) overrides it, read back exactly", custom:rowHeight(), 30)
  eq("...and the rebuild still reads as the client's own SListBox", custom:type(), "SListBox")
  -- Reused controls again, for the same reason as step 6: a rejected :rowHeight(n) must not orphan the
  -- fresh, unparented control it would otherwise have been built on.
  local badHeight = hafen.ui():list():parent(hold):position(100, 225):size(80, 20)
  refuses("a non-number :rowHeight(n) is refused", function() badHeight:rowHeight("x") end, "NUMBER")
  refuses("a non-positive :rowHeight(n) is refused", function() badHeight:rowHeight(0) end, "POSITIVE")
  local plainLabel = hafen.ui():label():parent(hold):position(190, 225):text("x")
  refuses(":rowHeight(n) on a control with no rows refuses, naming the builder that has one",
          function() plainLabel:rowHeight(10) end, "hafen.ui():list()")
  armedList = hafen.ui():list():parent(hold):position(200, 210):size(50, 40):rows{"A"}

  -- 9. OWNERSHIP STILL HOLDS -- the write verbs refuse on a NATIVE widget, naming it native, re-asserted here
  --    on this control (040.1's provenance test).
  local root = hafen.ui():root()
  refuses("widget:rows(t) refuses on a NATIVE widget, naming it native", function() root:rows{"x"} end,
          "NATIVE widget")
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native", function() root:value("x") end,
          "NATIVE widget")
  refuses("widget:rowHeight(n) refuses on a NATIVE widget, naming it native", function() root:rowHeight(20) end,
          "NATIVE widget")

  -- 10. The one thing a program cannot judge: clicking a row actually selects it.
  manual = manual + 1
  hafen.log():write("[manual] in the window \"040.9 — list()\" at 60,60, click \"Stone\" -- expect: the row"
                    .. " highlights and the log prints \"040.9: picked Stone\"")

  hafen.timer():after(0.5, phase2)   -- one tick for the rows to actually be BUILT; phase 2 closes the run
end

local function run()
  ICON = hafen.asset():get("icon.png")
  check(ICON ~= nil, "the suite ships its own icon PNG, and hafen.asset loaded it (a suite stands alone)",
        tostring(ICON))

  -- The window the [manual] line needs is built FIRST, so `base` (measured in phase 1, once this window's OWN
  -- list has had its lazy row build too) reflects exactly what the assertions build and nothing else.
  local demo = hafen.ui():window():title("040.9 — list()"):size(180, 130):position(60, 60)
  local dlist = hafen.ui():list():parent(demo):position(10, 10):size(160, 100)
    :rows{"Wood", "Stone", "Clay"}
    :onChange(function(row) hafen.log():write("040.9: picked " .. tostring(row)) end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  hafen.timer():after(0.5, phase1)   -- one tick for demo's own rows to be BUILT before `base` is measured
end

hafen.slash():register("t040-9", run)   -- the only way in: a suite does not start itself
