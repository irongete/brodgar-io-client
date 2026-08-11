-- 040.10 — :dropdown() and :menu(). Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():dropdown() is a real client SDropBox and hafen.ui():menu() a real
-- SListMenu, both over the SAME LuaRows bridge :list() (040.9) ships -- a mechanism, not a rebuild per
-- consumer (D-108). A dropdown HOLDS a pick: :value()/:value(v) round-trips the SAME Lua value :rows(t) was
-- given, and :onChange(fn) fires from a real pick only -- a programmatic :value(v) never re-enters it, the
-- same D-153 rule 040.4 pinned, reached here through SDropBox's own change() rather than a bare field write
-- (see CDropdown's own doc for why the mechanism differs). A menu FIRES and holds nothing: :value() reads nil
-- on it always, and :onSelect(fn) -- not :onChange -- carries the picked row. Both take :rows(t), refuse an
-- explicit :rows(nil) (R5), default and override :rowHeight(n), and are owned exactly like every other
-- control in this feature -- the anonymous-subclass question the task poses does not arise, because neither
-- adapter goes through the engine's own SDropBox.of(...)/SListMenu.of(...) factories; each subclasses its
-- engine class directly, exactly as CList already does for SListBox (040.9).
--
-- NO TICK DELAY NEEDED, unlike 040.9. SListBox builds its ROW WIDGETS lazily from update() on the per-frame
-- tick, which is why that suite runs in two phases. Nothing asserted here depends on a row WIDGET existing:
-- :rows()/:value()/:onChange/:onSelect are all synchronous field state, and a dropdown's pick specifically
-- goes through SDropBox.change(), which rebuilds its own closed-box widget SYNCHRONOUSLY, inside :value(v)
-- itself. So this suite runs start to finish in one tick.
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

local function run()
  -- The window the [manual] line needs, built FIRST so `base` reflects exactly what the assertions build.
  local demo = hafen.ui():window():title("040.10 — dropdown() / menu()"):size(180, 170):position(60, 60)
  local ddemo = hafen.ui():dropdown():parent(demo):position(10, 10):size(150, 20)
    :rows{"Wood", "Stone", "Clay"}
    :onChange(function(row) hafen.log():write("040.10: dropdown picked " .. tostring(row)) end)
  local mdemo = hafen.ui():menu():parent(demo):position(10, 40):size(150, 90)
    :rows{"Rename", "Delete", "Move"}
    :onSelect(function(row) hafen.log():write("040.10: menu picked " .. tostring(row)) end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  local base = treeCount()
  local hold = hafen.ui():window():title("040.10 — scratch"):size(260, 170):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). Both verbs are on the one section object, built bare (R4).
  check(type(hafen.ui().dropdown) == "function", "hafen.ui() carries :dropdown() as a verb on the one section"
        .. " object", tostring(hafen.ui().dropdown))
  check(type(hafen.ui().menu) == "function", "hafen.ui() carries :menu() as a verb on the one section object",
        tostring(hafen.ui().menu))
  refuses("hafen.ui():dropdown() takes no arguments (R4)", function() hafen.ui():dropdown("x") end,
          "takes no arguments")
  refuses("hafen.ui():menu() takes no arguments (R4)", function() hafen.ui():menu("x") end, "takes no arguments")

  -- 2. BUILT, AND IN THE TREE -- real client widgets this addon owns.
  local dd = hafen.ui():dropdown():parent(hold):position(10, 10):size(120, 20)
  check((dd:type() == "SDropBox") and (dd:info().owned == true) and (dd:parent() == hold),
        "hafen.ui():dropdown() builds a real client SDropBox this addon owns, parented where it was put",
        ("%s owned=%s"):format(dd:type(), tostring(dd:info().owned)))
  local mn = hafen.ui():menu():parent(hold):position(10, 40):size(120, 60)
  check((mn:type() == "SListMenu") and (mn:info().owned == true) and (mn:parent() == hold),
        "hafen.ui():menu() builds a real client SListMenu this addon owns, parented where it was put",
        ("%s owned=%s"):format(mn:type(), tostring(mn:info().owned)))

  -- 3. BARE STATE, AND AN EMPTY :rows{} IS AN EMPTY CONTROL -- NOT AN ERROR.
  eq("a bare dropdown's :rows() reads nil -- :rows(t) was never called", dd:rows(), nil)
  eq("a bare dropdown's :value() reads nil -- nothing picked", dd:value(), nil)
  eq("a bare menu's :rows() reads nil", mn:rows(), nil)
  eq("a menu never answers :value() -- it fires and holds nothing", mn:value(), nil)
  check(pcall(function() dd:rows({}) end), "an explicit dropdown :rows{} is an EMPTY list, not an error", "")
  check(pcall(function() mn:rows({}) end), "an explicit menu :rows{} is an EMPTY list, not an error", "")

  -- 4. :value()/:value(v) IS THE DROPDOWN'S PICK -- the SAME Lua value :rows(t) was given.
  local rows = {"Wood", "Stone", "Clay"}
  dd:rows(rows)
  eq("dropdown widget:rows() reads back the EXACT table :rows(t) was given", dd:rows(), rows)
  eq("a fresh :rows(t) still reads :value() as nil -- nothing picked yet", dd:value(), nil)
  local chained = dd:value(rows[2])
  check(chained == dd, "dropdown widget:value(v) chains, like every other setter", tostring(chained))
  eq("...and widget:value() reads back the exact row that was named", dd:value(), rows[2])
  dd:value(rows[1])
  eq("...writing a different row moves the pick", dd:value(), rows[1])
  refuses("dropdown widget:value(v) naming a value that is not one of the current rows is refused",
          function() dd:value("Not a row") end, "not one of its current rows")
  dd:rows({"Other"})
  eq("replacing :rows(t) drops the previous pick -- it may not name a row in the new set", dd:value(), nil)

  -- 5. :onChange(fn) FIRES FROM A REAL INTERACTION ONLY -- a programmatic :value(v) never re-enters it.
  dd:rows(rows)
  local fired = 0
  dd:onChange(function(row) fired = fired + 1 end)
  dd:value(rows[1])
  dd:value(rows[3])
  eq("a programmatic dropdown :value(v) does NOT re-enter :onChange (no feedback loop)", fired, 0)

  -- 6. THE MENU HOLDS NOTHING, AND :onSelect(fn) -- NOT :onChange -- CARRIES THE ROW.
  local mrows = {"Rename", "Delete", "Move"}
  mn:rows(mrows)
  eq("menu widget:rows() reads back the EXACT table :rows(t) was given", mn:rows(), mrows)
  eq("a menu still answers no :value() once rows are set", mn:value(), nil)
  check(type(mn.onSelect) == "function", "a menu carries :onSelect(fn) as a verb", tostring(mn.onSelect))
  mn:onSelect(function(row) end)
  check(type(mn:onSelect()) == "function", "menu widget:onSelect(fn) installs and reads back the handler",
        tostring(mn:onSelect()))
  refuses("menu widget:onSelect(fn) requires a function", function() mn:onSelect("x") end, "function")
  eq("a dropdown answers no :onSelect -- that name is the menu's", dd:onSelect(), nil)
  refuses("writing :onSelect(fn) on a dropdown refuses, naming the menu builder",
          function() dd:onSelect(function() end) end, "hafen.ui():menu()")
  eq("a menu answers no :onChange -- it holds nothing to report a change against", mn:onChange(), nil)
  refuses("writing :onChange(fn) on a menu refuses, naming that it holds nothing",
          function() mn:onChange(function() end) end, "holds nothing")

  -- 7. widget:rows(nil) IS REFUSED (R5), NOT READ AS AN ARITY -- for both.
  refuses("dropdown widget:rows(nil) is refused (R5)", function() dd:rows(nil) end, "must not be nil")
  refuses("menu widget:rows(nil) is refused (R5)", function() mn:rows(nil) end, "must not be nil")

  -- 8. :rowHeight(n) DEFAULTS AND OVERRIDES -- the SAME building-only mechanism 040.9's :list() answers on.
  check(dd:rowHeight() > 0, "a bare dropdown's :rowHeight() defaults to a positive pixel height",
        tostring(dd:rowHeight()))
  local customDD = hafen.ui():dropdown():parent(hold):position(10, 65):size(120, 20):rowHeight(30)
  eq("...and :rowHeight(n) overrides it, read back exactly", customDD:rowHeight(), 30)
  eq("...and the rebuild still reads as the client's own SDropBox", customDD:type(), "SDropBox")
  check(mn:rowHeight() > 0, "a bare menu's :rowHeight() defaults to a positive pixel height",
        tostring(mn:rowHeight()))
  local customMN = hafen.ui():menu():parent(hold):position(10, 95):size(120, 60):rowHeight(24)
  eq("...and :rowHeight(n) overrides it, read back exactly", customMN:rowHeight(), 24)
  eq("...and the rebuild still reads as the client's own SListMenu", customMN:type(), "SListMenu")
  local plainLabel = hafen.ui():label():parent(hold):position(140, 65):text("x")
  refuses(":rowHeight(n) on a control with no rows refuses, naming a builder that has one",
          function() plainLabel:rowHeight(10) end, "hafen.ui():list()")

  -- 9. OWNERSHIP STILL HOLDS -- the write verbs refuse on a NATIVE widget, naming it native (040.1's provenance
  --    test, re-asserted here since neither adapter goes through an of(...) factory that might have skipped it).
  local root = hafen.ui():root()
  refuses("widget:rows(t) refuses on a NATIVE widget, naming it native", function() root:rows{"x"} end,
          "NATIVE widget")
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native", function() root:value("x") end,
          "NATIVE widget")
  refuses("widget:onSelect(fn) refuses on a NATIVE widget, naming it native",
          function() root:onSelect(function() end) end, "NATIVE widget")

  hold:destroy()
  eq("everything built here dies with the window it was put in, and the tree ends the size it started",
     treeCount(), base)

  -- 10. What a program cannot judge: the popup actually opening, and a real click landing.
  manual = manual + 2
  hafen.log():write("[manual] in the window \"040.10 — dropdown() / menu()\" at 60,60, open the dropdown and"
                    .. " pick \"Stone\" -- expect: the list opens below it, closes on the pick, and the log"
                    .. " prints \"040.10: dropdown picked Stone\"")
  hafen.log():write("[manual] in the same window, press the \"Delete\" row of the menu -- expect: the log"
                    .. " prints \"040.10: menu picked Delete\"")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-10", run)   -- the only way in: a suite does not start itself
