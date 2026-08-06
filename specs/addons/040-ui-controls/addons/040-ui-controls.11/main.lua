-- 040.11 — GridList, and hafen.ui():grid(). Self-checking suite; see specs/addons/TESTING.md.
--
-- THE ODD ONE OUT OF THE MODEL-BACKED FIVE. haven.GridList does not build row WIDGETS the way SListWidget
-- does -- it DRAWS cells (drawitem(GOut, item)) -- so this control takes no part in the LuaRows bridge
-- :list()/:dropdown()/:menu() share: :rows(t) is a plain array of arbitrary Lua values, and :onCell(g, item,
-- w, h) paints one through the SAME g wrapper widget:onDraw(fn) hands a surface. :cell(w, h) is the cell box,
-- and -- like :rowHeight(n) -- building-only: GridList.Group.itemsz is final, so a different one is a
-- different widget under the same Lua handle.
--
-- WHY THE DRAW-DEPENDENT CHECKS WAIT A TICK. :onCell only fires from an actual draw pass, so anything that
-- reads what it was CALLED WITH runs one tick after the statements that build the control (phase 2, the same
-- two-phase shape 040.2's face-setter and 040.9's row-bridge suites use). Every check tracks DISTINCT items
-- seen in a set rather than a call COUNT, since a control redraws every frame between "build" and "check" and
-- the exact frame count elapsed is not something this suite controls.
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

local ICON
local base, hold

-- Built in phase1, judged in phase2 (one tick later, so at least one draw pass has run :onCell).
local rowsRows, rowsSeen, gWorks
local emptyCount
local isoRows, isoSeen
local rebuildGrid, rebuildBefore, rebuildAfter, rebuildSeen40
local armedGrid

-- PHASE 2 — one tick after phase1, so every grid here has actually been DRAWN at least once and :onCell has
-- fired for whatever it holds.
local function phase2()
  -- 1. :onCell(g, item, w, h) FIRES ONCE PER ROW, with the ROW'S OWN item (== the table :rows(t) was given,
  --    by identity) and the control's own :cell() box.
  local allSeen = true
  for _, item in ipairs(rowsRows) do
    if rowsSeen[item] ~= true then allSeen = false end
  end
  check(allSeen, "widget:onCell(g, item, w, h) fires for every row, with that row's own item and the"
        .. " control's :cell() box", tostring(allSeen))
  check(gWorks, "...and g is the SAME wrapper widget:onDraw(fn) gets -- g:color/g:frect succeed inside it",
        tostring(gWorks))

  -- 2. AN EMPTY :rows{} DRAWS NOTHING -- no :onCell call at all, ever.
  eq("an empty :rows{} draws no cells -- :onCell is never called", emptyCount, 0)

  -- 3. widget:cell(w, h) CALLED AGAIN WHILE STILL PENDING REBUILDS THE BOX -- this IS the layout changing:
  --    the same control now delivers the NEW box to :onCell.
  check((rebuildBefore.w == 10) and (rebuildBefore.h == 10) and (rebuildAfter.w == 40) and (rebuildAfter.h == 40)
        and (rebuildGrid:type() == "GridList"),
        "widget:cell(w, h) changes the layout: read back before/after, and the rebuild stays a GridList",
        ("%dx%d -> %dx%d"):format(rebuildBefore.w, rebuildBefore.h, rebuildAfter.w, rebuildAfter.h))
  check(rebuildSeen40, "...and :onCell actually receives the NEW box -- the rebuild carried rows/:onCell across",
        tostring(rebuildSeen40))

  -- 4. AN :onCell THAT ERRORS IS ISOLATED, PER CELL -- row 3's handler threw (once), and every OTHER row --
  --    including the two AFTER it in the same draw pass -- was still painted.
  check((isoSeen[1] == true) and (isoSeen[2] == true) and (isoSeen[4] == true) and (isoSeen[5] == true)
        and (isoSeen[3] == nil),
        "a row whose :onCell errors is isolated -- the OTHER rows, including the ones after it, are still"
        .. " painted the same frame",
        ("1=%s 2=%s 3=%s 4=%s 5=%s"):format(tostring(isoSeen[1]), tostring(isoSeen[2]), tostring(isoSeen[3]),
                                             tostring(isoSeen[4]), tostring(isoSeen[5])))

  -- 5. :cell(w, h) IS BUILDING-ONLY, LIKE :rowHeight(n) -- refused once the control is on screen.
  refuses("once a grid is on screen, :cell(w, h) refuses, naming that it is chosen at build time",
          function() armedGrid:cell(50, 50) end, "already on screen")

  hold:destroy()
  eq("everything built here dies with the window it was put in, and the tree ends the size it started",
     treeCount(), base)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function phase1()
  base = treeCount()
  hold = hafen.ui():window():title("040.11 — scratch"):size(320, 200):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). :grid() is a verb on the one section object, built bare
  --    (R4) like every other builder here.
  check(type(hafen.ui().grid) == "function", "hafen.ui() carries :grid() as a verb on the one section object",
        tostring(hafen.ui().grid))
  refuses("the builder takes no arguments (R4)", function() hafen.ui():grid("x") end, "takes no arguments")

  -- 2. BUILT BARE, WITH THE CLIENT'S OWN DEFAULTS -- a real client GridList this addon owns.
  local bare = hafen.ui():grid()
  local bp, bs, bc = bare:position(), bare:size(), bare:cell()
  check((bare:type() == "GridList") and (bare:info().owned == true) and (bare:parent() == hafen.ui():root())
        and (bp.x == 100) and (bp.y == 100) and (bs.x > 0) and (bs.y > 0) and (bc.w > 0) and (bc.h > 0)
        and (bare:rows() == nil),
        "hafen.ui():grid() builds a real client GridList this addon owns: the client's default place, its"
        .. " own default box and cell, no rows yet",
        ("%s owned=%s %d,%d %dx%d cell=%dx%d"):format(bare:type(), tostring(bare:info().owned), bp.x, bp.y,
                                                        bs.x, bs.y, bc.w, bc.h))
  bare:destroy()

  -- 3. AN EXPLICIT :rows{} IS AN EMPTY LIST -- NOT AN ERROR.
  local rowsOk = hafen.ui():grid():parent(hold):position(10, 10)
  local ok = pcall(function() rowsOk:rows({}) end)
  check(ok, "an explicit :rows{} is an EMPTY list, not an error", tostring(ok))
  eq("...and it still reads back an empty table", #rowsOk:rows(), 0)
  rowsOk:destroy()

  -- 4. :rows(t) READS BACK EXACTLY THE TABLE GIVEN -- identity, like every other model-backed control.
  rowsRows = {{id = 1}, {id = 2}, {id = 3}}
  rowsSeen = {}
  gWorks = false
  local rowsGrid = hafen.ui():grid():parent(hold):position(10, 10):cell(24, 24):rows(rowsRows)
    :onCell(function(g, item, w, h)
        rowsSeen[item] = (w == 24) and (h == 24)
        local gok = pcall(function() g:color(10, 20, 30); g:frect(0, 0, w, h); g:color() end)
        if gok then gWorks = true end
      end)
  eq("widget:rows() reads back the EXACT table :rows(t) was given", rowsGrid:rows(), rowsRows)
  eq("...and widget:cell() reads back the EXACT box :cell(w, h) was given", rowsGrid:cell().w, 24)

  -- 5. A ROW SOURCE MUST BE AN ARRAY -- validated before anything is torn down (mirrors every other
  --    model-backed control's :rows(t)); an explicit nil is refused (R5), not read as an arity.
  local badRows = hafen.ui():grid():parent(hold):position(10, 40)
  refuses("widget:rows(t) that is not a table is refused, naming the shape", function() badRows:rows("x") end,
          "ARRAY")
  refuses("widget:rows(nil) is refused (R5), not read as an arity", function() badRows:rows(nil) end,
          "must not be nil")
  badRows:destroy()

  -- 6. :cell(w, h) VALIDATES ITS ARGUMENTS, THE SAME SHAPE :rowHeight(n) HAS.
  local badCell = hafen.ui():grid():parent(hold):position(10, 60)
  refuses("a non-number :cell(w, h) is refused", function() badCell:cell("x", 10) end, "NUMBER")
  refuses("a non-positive :cell(w, h) is refused", function() badCell:cell(0, 10) end, "POSITIVE")
  refuses("widget:cell(nil, 10) is refused (R5), not read as an arity", function() badCell:cell(nil, 10) end,
          "must not be nil")
  badCell:destroy()

  -- 7. widget:cell(w, h) CALLED AGAIN WHILE STILL PENDING REBUILDS THE BOX -- and carries the rows/:onCell
  --    handler it already had across the rebuild, judged in phase2 once a frame has drawn the NEW box.
  local rebuildRows = {{id = 9}}
  rebuildSeen40 = false
  rebuildGrid = hafen.ui():grid():parent(hold):position(200, 10):cell(10, 10):rows(rebuildRows)
    :onCell(function(g, item, w, h) if (w == 40) and (h == 40) then rebuildSeen40 = true end end)
  rebuildBefore = rebuildGrid:cell()
  rebuildGrid:cell(40, 40)
  rebuildAfter = rebuildGrid:cell()

  -- 8. AN EMPTY :rows{} DRAWS NO CELLS AT ALL -- judged in phase2, over however many frames elapse.
  emptyCount = 0
  hafen.ui():grid():parent(hold):position(200, 60):rows({})
    :onCell(function() emptyCount = emptyCount + 1 end)

  -- 9. A ROW WHOSE :onCell ERRORS IS ISOLATED -- the OTHER rows (drawn the SAME frame, after it) are still
  --    painted. Errors only ONCE: the claim is that one throw does not take the rest of that frame down with
  --    it, not that the log should fill up -- a second frame proves nothing more and only adds noise.
  isoRows = {{id = 1}, {id = 2}, {id = 3, crash = true}, {id = 4}, {id = 5}}
  isoSeen = {}
  local isoCrashed = false
  hafen.ui():grid():parent(hold):position(10, 90):cell(16, 16):rows(isoRows)
    :onCell(function(g, item, w, h)
        if item.crash then
          if not isoCrashed then
            isoCrashed = true
            error("040.11: this cell fails on purpose, once, to prove the others still get painted")
          end
          return
        end
        isoSeen[item.id] = true
      end)

  -- 10. A CONTROL WITH NO CELLS READS nil, AND REFUSES THE WRITE, NAMING THE BUILDER THAT HAS ONE.
  local plainLabel = hafen.ui():label():parent(hold):position(10, 120):text("x")
  eq("...and :cell() reads nil on a control with no cells", plainLabel:cell(), nil)
  eq("...and :onCell() reads nil on a control with no cells", plainLabel:onCell(), nil)
  refuses(":cell(w, h) on a control with no cells refuses, naming the builder that has one",
          function() plainLabel:cell(10, 10) end, "hafen.ui():grid()")
  refuses(":onCell(fn) on a control with no cells refuses, naming the builder that has one",
          function() plainLabel:onCell(function() end) end, "hafen.ui():grid()")

  -- 11. OWNERSHIP STILL HOLDS -- the write verbs refuse on a NATIVE widget, naming it native (040.1's
  --     provenance test, re-asserted here on this control).
  local root = hafen.ui():root()
  refuses("widget:rows(t) refuses on a NATIVE widget, naming it native", function() root:rows({1}) end,
          "NATIVE widget")
  refuses("widget:cell(w, h) refuses on a NATIVE widget, naming it native", function() root:cell(10, 10) end,
          "NATIVE widget")
  refuses("widget:onCell(fn) refuses on a NATIVE widget, naming it native",
          function() root:onCell(function() end) end, "NATIVE widget")

  -- 12. THE CONTROL THAT PROVES :cell(w, h) IS BUILDING-ONLY -- armed by the very tick that follows, so
  --     phase2's refusal is against a control that has genuinely been on screen.
  armedGrid = hafen.ui():grid():parent(hold):position(200, 90):rows({{id = 1}})

  hafen.timer():after(0.5, phase2)   -- one tick for :onCell to actually FIRE; phase 2 closes the run
end

local function run()
  ICON = hafen.asset():get("icon.png")
  check(ICON ~= nil, "the suite ships its own icon PNG, and hafen.asset loaded it (a suite stands alone)",
        tostring(ICON))

  -- The window the [manual] line needs, with a real icon grid -- six icons over a box narrow enough to force
  -- more than one across the width.
  local demo = hafen.ui():window():title("040.11 — grid()"):size(160, 110):position(60, 60)
  local items = {}
  for i = 1, 6 do items[i] = {icon = ICON} end
  hafen.ui():grid():parent(demo):position(10, 10):size(140, 90):cell(32, 32):rows(items)
    :onCell(function(g, item, w, h) g:image(item.icon, 0, 0, w, h) end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)
  manual = manual + 1
  hafen.log():write("[manual] look at the window \"040.11 — grid()\" at 60,60"
                    .. " -- expect: six icons laid out in a grid, wrapping across the width")

  phase1()
end

hafen.slash():register("t040-11", run)   -- the only way in: a suite does not start itself
