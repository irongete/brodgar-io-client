-- 040.12 — TableBox, and hafen.ui():table(). Self-checking suite; see specs/testing/addon-suite.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():table() is a real client TableBox, the fifth and last of the
-- model-backed five -- and unlike :list()/:dropdown()/:menu() it takes NO part in the LuaRows bridge: a
-- table row is not a string or an {icon=, text=} pair, it is whatever shape the addon's own :columns(t)
-- of(row) accessors read from it, so :rows(t) is a plain array of arbitrary Lua values (the same shape
-- :grid()'s row source has). :columns(t) names {title=, width=, of=} per column over ColSpec.of and, like
-- :rowHeight(n), is building-only -- the client's own TableBox fixes its columns at construction.
--
-- WHY of(row) IS CHECKED WITH NO TICK DELAY. Every cell is resolved WHOLE, at :rows(t)/:columns(t) time --
-- D-159's discipline again, and for the identical reason: SListBox.update() (inside a TableBox's own
-- MainList) calls makeitem() from the ordinary per-frame tick with none of AddonManager.callLua's error
-- isolation, so of(row) has to run at a verb call that can still refuse cleanly. So the call-count and
-- row-identity checks below run synchronously; only the RENDERED ROW WIDGETS (built lazily by MainList's
-- update()) need a tick to exist, which is what phase 2 waits for (the same two-phase shape 040.9's list
-- suite uses) -- the HEADER widgets do not, since TableBox's own constructor builds them synchronously. For
-- the same reason `run()` defers into phase1 by one tick before touching treeCount() at all: demo's OWN
-- table would otherwise still be lazily materialising its row widgets when the final count runs, and read
-- as a leak that never was one (040.9's own gotcha, same fix). A MainList is itself an SListBox, so its
-- children also carry its own auto Scrollbar alongside the Row widgets -- filtered the same way 040.9's
-- own rowWidgets() filters a bare :list()'s.
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

-- A table's own HEADING widgets -- built synchronously by TableBox's constructor, direct children that are
-- not its inner MainList (the row-holding SListBox).
local function headers(tbl)
  local hs = {}
  for _, k in ipairs(tbl:children()) do
    if k:type() ~= "MainList" then table.insert(hs, k) end
  end
  return hs
end

-- A table's own ROW widgets -- one level inside its MainList, built lazily on tick (judged in phase 2).
-- MainList is an SListBox and so carries its own auto Scrollbar child alongside the Row widgets (040.9's
-- rowWidgets() filters the very same thing on a bare :list()).
local function rowWidgets(tbl)
  for _, k in ipairs(tbl:children()) do
    if k:type() == "MainList" then
      local rows = {}
      for _, r in ipairs(k:children()) do
        if r:type() ~= "Scrollbar" then table.insert(rows, r) end
      end
      return rows
    end
  end
  return {}
end

local base, hold
local dataTbl, armedTbl              -- built in phase1, judged in phase2 (one tick later)

-- PHASE 2 — one tick after phase1, so every table's row widgets actually exist.
local function phase2()
  -- 1. ROW WIDGETS RENDER, ONE PER ROW, EACH WITH ONE CELL PER COLUMN.
  local rws = rowWidgets(dataTbl)
  eq("a 3-row, 2-column table renders exactly 3 row widgets", #rws, 3)
  if #rws == 3 then
    check((#rws[1]:children() == 2) and (#rws[2]:children() == 2) and (#rws[3]:children() == 2),
          "...and each row has exactly one cell per column", tostring(#rws[1]:children()))
  end

  -- 2. :columns(t) AND :rowHeight(n) ARE BOTH BUILDING-ONLY — refused once the control is on screen.
  refuses("once a table is on screen, :columns(t) refuses, naming that it is chosen at build time",
          function() armedTbl:columns{{title = "Y", width = 10, of = function(r) return "" end}} end,
          "already on screen")
  refuses("once a table is on screen, :rowHeight(n) refuses, naming that it is chosen at build time",
          function() armedTbl:rowHeight(50) end, "already on screen")

  hold:destroy()
  eq("everything built here dies with the window it was put in, and the tree ends the size it started",
     treeCount(), base)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function phase1()
  base = treeCount()
  hold = hafen.ui():window():title("040.12 — scratch"):size(320, 260):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). :table() is a verb on the one section object, built
  --    bare (R4) like every other builder here.
  check(type(hafen.ui().table) == "function", "hafen.ui() carries :table() as a verb on the one section object",
        tostring(hafen.ui().table))
  refuses("the builder takes no arguments (R4)", function() hafen.ui():table("x") end, "takes no arguments")

  -- 2. BUILT BARE, WITH NO COLUMNS — a real client TableBox this addon owns.
  local bare = hafen.ui():table():parent(hold):position(10, 10):size(50, 50)
  check((bare:type() == "TableBox") and (bare:info().owned == true) and (bare:parent() == hold)
        and (bare:rowHeight() > 0) and (bare:columns() == nil) and (bare:rows() == nil),
        "hafen.ui():table() builds a real client TableBox this addon owns, with no columns and a positive"
        .. " default row height until :columns(t)/:rows(t) name them",
        ("%s owned=%s rowHeight=%s"):format(bare:type(), tostring(bare:info().owned), tostring(bare:rowHeight())))
  bare:destroy()

  -- 3. A COLUMN DESCRIPTOR MUST BE A TABLE WITH title/width/of — each missing key refused, naming it.
  local badCols = hafen.ui():table():parent(hold):position(10, 10)
  refuses("widget:columns(t) that is not a table is refused, naming the shape",
          function() badCols:columns("x") end, "ARRAY")
  refuses("a column that is not a table is refused, naming the shape", function() badCols:columns{1} end,
          "must be a table")
  refuses("a column missing \"title\" is refused, naming it",
          function() badCols:columns{{width = 10, of = function(r) return "" end}} end, '"title" key')
  refuses("a column missing \"width\" is refused, naming it",
          function() badCols:columns{{title = "X", of = function(r) return "" end}} end, '"width" key')
  refuses("a column with a non-positive \"width\" is refused",
          function() badCols:columns{{title = "X", width = 0, of = function(r) return "" end}} end, "POSITIVE")
  refuses("a column missing \"of\" is refused, naming it",
          function() badCols:columns{{title = "X", width = 10}} end, '"of" key')
  refuses("widget:columns(nil) is refused (R5), not read as an arity", function() badCols:columns(nil) end,
          "must not be nil")
  badCols:destroy()

  -- 4. :columns(t) NAMES title/width/of PER COLUMN, AND RENDERS A HEADER PER COLUMN AT ITS OWN WIDTH —
  --    headers are built SYNCHRONOUSLY, in the same statement, unlike a list's row widgets (040.9).
  local cols = {
    {title = "Name", width = 160, of = function(r) return r.name end},
    {title = "Quality", width = 60, of = function(r) return tostring(r.q) end},
  }
  local headed = hafen.ui():table():parent(hold):position(10, 60):columns(cols)
  eq("widget:columns() reads back the EXACT table :columns(t) was given", headed:columns(), cols)
  local hs = headers(headed)
  eq("a 2-column :columns(t) renders exactly 2 header widgets, synchronously", #hs, 2)
  if #hs == 2 then
    check((hs[1]:size().x == 160) and (hs[2]:size().x == 60),
          "...and each header is exactly its own column's declared width",
          ("%d, %d"):format(hs[1]:size().x, hs[2]:size().x))
  end
  headed:destroy()

  -- 5. of(row) IS CALLED ONCE PER ROW, PER COLUMN — with no tick delay — with the row's OWN raw value (the
  --    exact table :rows(t) was given, by identity).
  local calls, seen = {n = 0, q = 0}, {}
  local rowsCols = {
    {title = "Name", width = 160, of = function(r) calls.n = calls.n + 1; seen[r] = true; return r.name end},
    {title = "Quality", width = 60, of = function(r) calls.q = calls.q + 1; return tostring(r.q) end},
  }
  local rows = {{name = "Bucket", q = 10}, {name = "Sickle", q = 20}, {name = "Hoe", q = 30}}
  dataTbl = hafen.ui():table():parent(hold):position(10, 10):size(220, 90):columns(rowsCols):rows(rows)
  check((calls.n == 3) and (calls.q == 3), "of(row) is called exactly once per row, for EACH column",
        ("name=%d quality=%d"):format(calls.n, calls.q))
  local allSeen = true
  for _, r in ipairs(rows) do if not seen[r] then allSeen = false end end
  check(allSeen, "...and each call's row is the EXACT value :rows(t) was given, by identity", tostring(allSeen))
  eq("widget:rows() reads back the EXACT table :rows(t) was given", dataTbl:rows(), rows)

  -- 6. of(row) MUST RETURN A STRING — a wrong type is refused, naming the column.
  local badOf = hafen.ui():table():parent(hold):position(10, 160)
    :columns{{title = "Bad", width = 40, of = function(r) return 42 end}}
  refuses("of(row) returning a non-string is refused, naming the column", function() badOf:rows{{}} end, "Bad")
  badOf:destroy()

  -- 7. A ROW SOURCE MUST BE AN ARRAY — validated before anything is torn down, mirroring every other
  --    model-backed control's :rows(t); an explicit nil is refused (R5), not read as an arity.
  local badRows = hafen.ui():table():parent(hold):position(10, 160)
    :columns{{title = "X", width = 40, of = function(r) return tostring(r) end}}
  local goodRows = {1, 2}
  badRows:rows(goodRows)
  refuses("widget:rows(t) that is not a table is refused, naming the shape",
          function() badRows:rows("x") end, "ARRAY")
  eq("...and a rejected :rows(t) leaves the existing rows untouched", badRows:rows(), goodRows)
  refuses("widget:rows(nil) is refused (R5), not read as an arity", function() badRows:rows(nil) end,
          "must not be nil")
  badRows:destroy()

  -- 8. RE-:columns{} REPLACES THE SET — while still pending, columns rebuilds under the same Lua handle,
  --    re-resolving the CARRIED-OVER rows against the NEW columns.
  local firstCalls = 0
  local rebuilt = hafen.ui():table():parent(hold):position(10, 160):size(100, 60)
    :columns{{title = "One", width = 30, of = function(r) firstCalls = firstCalls + 1; return r.a end}}
    :rows{{a = "X"}, {a = "Y"}}
  eq("before the rebuild, of(row) already ran once per row against the FIRST columns", firstCalls, 2)
  local secondCalls = 0
  rebuilt:columns{
    {title = "Two", width = 20, of = function(r) return r.a end},
    {title = "Three", width = 20, of = function(r) secondCalls = secondCalls + 1; return r.a .. r.a end},
  }
  check((#headers(rebuilt) == 2) and (#rebuilt:columns() == 2) and (rebuilt:type() == "TableBox"),
        "re-:columns{} replaces the set: the header count follows the NEW columns, and the rebuild stays a"
        .. " client TableBox", ("%d headers, type=%s"):format(#headers(rebuilt), rebuilt:type()))
  eq("...and the rebuild RE-RESOLVES the carried-over rows against the new columns", secondCalls, 2)

  -- 9. :rows{} WITH COLUMNS SET IS AN EMPTY TABLE, NOT AN ERROR.
  local emptyOk = pcall(function() rebuilt:rows({}) end)
  check(emptyOk, "an explicit :rows{} with columns already set is an EMPTY table, not an error",
        tostring(emptyOk))
  eq("...and it still reads back an empty table", #rebuilt:rows(), 0)
  rebuilt:destroy()

  -- 10. THE CONTROL THAT PROVES :columns(t)/:rowHeight(n) ARE BUILDING-ONLY — armed by the tick that
  --     follows, so phase2's refusals are against a control that has genuinely been on screen.
  armedTbl = hafen.ui():table():parent(hold):position(10, 160)
    :columns{{title = "X", width = 20, of = function(r) return "" end}}

  -- 11. OWNERSHIP STILL HOLDS — the write verbs refuse on a NATIVE widget, naming it native (040.1's
  --     provenance test, re-asserted here on this control).
  local root = hafen.ui():root()
  refuses("widget:rows(t) refuses on a NATIVE widget, naming it native", function() root:rows{1} end,
          "NATIVE widget")
  refuses("widget:columns(t) refuses on a NATIVE widget, naming it native",
          function() root:columns{{title = "X", width = 10, of = function(r) return "" end}} end,
          "NATIVE widget")
  refuses("widget:rowHeight(n) refuses on a NATIVE widget, naming it native", function() root:rowHeight(20) end,
          "NATIVE widget")

  -- 12. A CONTROL WITH NO COLUMNS READS nil, AND REFUSES THE WRITE, NAMING THE BUILDER THAT HAS ONE.
  local plainLabel = hafen.ui():label():parent(hold):position(10, 230):text("x")
  eq("...and :columns() reads nil on a control with no columns", plainLabel:columns(), nil)
  refuses(":columns(t) on a control with no columns refuses, naming the builder that has one",
          function() plainLabel:columns{{title = "X", width = 10, of = function(r) return "" end}} end,
          "hafen.ui():table()")

  hafen.timer():after(0.5, phase2)   -- one tick for the row widgets to actually be BUILT; phase 2 closes the run
end

local function run()
  local demo = hafen.ui():window():title("040.12 — table()"):size(240, 150):position(60, 60)
  hafen.ui():table():parent(demo):position(10, 10):size(220, 110)
    :columns{
      {title = "Name", width = 140, of = function(r) return r.name end},
      {title = "Qty", width = 60, of = function(r) return tostring(r.q) end},
    }
    :rows{{name = "Wood", q = 10}, {name = "Stone", q = 5}, {name = "Clay", q = 3}}
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)
  manual = manual + 1
  hafen.log():write("[manual] look at the window \"040.12 — table()\" at 60,60"
                    .. " -- expect: two columns \"Name\"/\"Qty\" with headings, three rows, cells lined up"
                    .. " under their own headings")

  -- One tick for demo's OWN table to have its lazy row-build pass BEFORE `base` is measured in phase1 --
  -- otherwise it fires between `base` and the final count and reads as a leak that never was one (040.9's
  -- own gotcha, same fix).
  hafen.timer():after(0.5, phase1)
end

hafen.slash():register("t040-12", run)   -- the only way in: a suite does not start itself
