-- 039.4 — hafen.map(), and the ONE Grid entity. Self-checking suite; see specs/testing/addon-suite.md.
--
-- hafen.map is the last section that was still a table of loose functions with two namespaces bolted to it.
-- It becomes five collections -- :segment() :grid() :marker() :icon() :overlay() -- and the noun names the
-- kind while the verb says how many, which is the whole grammar applied to a subsystem that had four
-- different shapes in it.
--
-- THE HEADLINE IS THAT A GRID STOPS BEING TWO THINGS. The live world publishes MCache.Grid.id and the
-- recorded map interns on the same number, so hafen.world():grid():at(p) and hafen.map():grid():get(id) were
-- never two entities: they are one, with :live() asking whether it is streamed in and :exists() whether it is
-- written down. This suite asserts the identity where both are true, and then the honest asymmetry beside it:
-- ground you explored last year answers the recorded half alone. The mirror -- streamed but not yet written
-- down -- is real and is NOT chased here, because the client records the 3x3 around you whenever you move, so
-- that state lasts a fraction of a second and no typed command can stand in it. What is checkable is the
-- invariant underneath both: every streamed grid is live, and its recorded reads answer exactly when
-- :exists() says they do.
--
-- The other three claims are the ones a rename could quietly break: a marker's :position() IS the old
-- :anchor() and must still land on the TILE the marker reports rather than on its corner; a display toggle is
-- a HOLD and not a boolean, so it gets three verbs instead of an arity; and the icon registry's rule about
-- which argument shape meant what is deleted rather than renamed.
--
-- READ-ONLY apart from one write it undoes in the same run: it drops a pin in the real map database to prove
-- the round trip, then removes it and asserts the removal. It declares no permissions, and every hold it
-- takes is released against the value it read first.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- A whole TABLE of refusals as one verdict line: each row is {what you write, what the message must name}.
-- The line passes only when every row throws AND names its replacement, and a failure lists the offenders --
-- which is what keeps a cut of this size to one line of output instead of a dozen.
local function allRefuse(what, rows)
  local bad = {}
  for _, r in ipairs(rows) do
    local ok, err = pcall(r[1])
    err = ok and "<no error>" or tostring(err)
    if ok or (err:find(r[2], 1, true) == nil) then bad[#bad + 1] = r[3] .. " -> " .. err end
  end
  check(#bad == 0, ("%s (%d of them)"):format(what, #rows), table.concat(bad, " | "))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local TILE, CMAPS = 11, 100     -- MCache.tilesz / MCache.cmaps, the constants every coordinate check derives from
local NAME = "039.4 suite pin"
local SWEEP = 6                 -- half-width, in grids, of the neighbourhood searched for an unstreamed grid

-- ---- the recorded half, staged so the disk has answered ----------------------------------------------
local function recorded(p, gp, seg, grid)
  -- 6. ONE ENTITY, TWO DOORS. The live query and the recorded lookup hand back the same object, and its two
  --    halves both answer true for the ground the player is standing on.
  check((hafen.world():grid():at(p) == grid) and (hafen.map():grid():get(gp.gridId) == grid)
          and (hafen.world():grid():get(gp.gridId) == grid)
          and (grid:live() == true) and (grid:exists() == true),
        "the live door and the recorded door are the SAME Grid object -- streamed and written down at once",
        ("live=%s exists=%s"):format(tostring(grid:live()), tostring(grid:exists())))

  -- 7. the mirror, and it is the common one: explored ground that is not streamed in right now still answers
  --    every recorded read, and still has a durable position, which is what "explored, not loaded" means.
  local sc = grid:segmentCoord()
  if not sc then
    check(false, "the grid under the player publishes its segment coord (the rest of this round needs it)",
          "nil -- the map file's index was busy; run ':t039-4' again")
    return
  end
  local away
  for _, g in ipairs(seg:grid():list{ x = sc.x - SWEEP, y = sc.y - SWEEP,
                                      w = (SWEEP * 2) + 1, h = (SWEEP * 2) + 1 }) do
    if not g:live() then away = g break end
  end
  if away then
    local ap = away:position()
    check((away:exists() == true) and (away:live() == false) and (ap ~= nil) and (ap:durable() == true)
            and (ap:info().gridId == away:id()) and (away:segmentCoord() ~= nil),
          "a saved-but-unstreamed grid answers the recorded half alone, and its position is still durable",
          ("id=%s live=%s durable=%s"):format(away:id(), tostring(away:live()),
                                              tostring(ap and ap:durable())))
  else
    manualCheck("walk a few grids and run ':t039-4' again",
                "every grid within " .. SWEEP .. " of you happens to be streamed in right now, so the"
                .. " saved-but-unstreamed half had nothing to stand on; a few grids from home it runs and"
                .. " passes -- :exists() true, :live() false, and a durable position")
  end

  -- 8. ...and the other half of the same claim, as an INVARIANT over every streamed grid rather than as a
  --    hunt for one particular state. The client writes down the 3x3 around the player whenever it moves,
  --    so "streamed but not yet recorded" lasts a fraction of a second and is not a state anybody can type
  --    a command in -- but what makes the unified entity honest is checkable on all of them at once: each
  --    is :live(), and its recorded half answers exactly when :exists() says it does, never the other way.
  local live = hafen.world():grid():list()
  local wrong, saved = {}, 0
  for _, g in ipairs(live) do
    if g:exists() then saved = saved + 1 end
    if (not g:live()) or (g:exists() ~= (g:segmentCoord() ~= nil))
       or (g:exists() ~= (g:segment() ~= nil)) then
      wrong[#wrong + 1] = g:id()
    end
  end
  check((#live > 0) and (#wrong == 0),
        "every streamed grid is :live(), and its recorded reads answer exactly when :exists() says so",
        ("%d streamed, %d of them recorded, %d disagreeing (%s)"):format(#live, saved, #wrong,
          table.concat(wrong, " ")))

  -- 9. the segment's own door onto that same entity, and the rectangle walk beside it
  check((seg:grid():get(sc) == grid) and (seg:grid():get{ x = sc.x, y = sc.y } == grid)
          and (#seg:grid():list{ x = sc.x - 1, y = sc.y - 1, w = 3, h = 3 } >= 1),
        "seg:grid():get(sc) is that same Grid, and :list(area) walks a rectangle of segment coords",
        tostring(seg:grid():get(sc) == grid) .. " / "
          .. tostring(#seg:grid():list{ x = sc.x - 1, y = sc.y - 1, w = 3, h = 3 }) .. " in a 3x3")

  -- 10. the recorded overlay masks are a collection ON the grid, and their tag space is the SERVER's: a tag
  --     the grid does not carry is plain nil, deliberately unlike the display toggles' closed set below.
  -- A collection owned by an ENTITY is a view, re-derived on every call and holding nothing, so two calls
  -- are two objects on purpose -- what is interned is the members. Asking the same tag through two
  -- separate views must therefore hand back one Mask.
  local masks = grid:overlay()
  local tags = masks:list()
  local one = tags[1] and tags[1]:tag()
  check((#tags == #grid:overlay():list()) and (masks:get("no-such-overlay-tag") == nil)
          and ((one == nil) or (masks:get(one) == grid:overlay():get(one))),
        "grid:overlay() is a VIEW that re-derives, an unknown tag is nil, and a Mask is interned across two",
        ("%d tag(s) recorded here%s"):format(#tags, one and (", first " .. one) or ""))

  -- 11. the segment collection, and its distinguished member
  local sid = seg:id()
  check((hafen.map():segment():get(sid) == seg) and (hafen.map():segment():current() == seg)
          and (hafen.map():segment():count() >= 1),
        "segment:current() and :get(id) are the same interned Segment, and :list() carries it",
        sid .. " of " .. tostring(hafen.map():segment():count()))
end

-- ---- the marker round trip, and the one write this suite makes ---------------------------------------
local function markerRound(p, seg)
  local markers = hafen.map():marker()
  local before = markers:count()
  local m = markers:add(NAME, p)
  if not m then
    manualCheck("stand in the world with the map database up and run ':t039-4' again",
                "three more [pass]: a pin dropped at your feet round-trips through its Position, reads back"
                .. " the colour and flag its chained setters gave it, and is removed again")
    return
  end

  -- 12. a bare pin, found through the collection's own search, and it is the same object
  check((markers:find(NAME) == m) and (markers:count() == before + 1) and (m:segment() == seg),
        "marker:add(name, p) dropped the pin, :find(name) found that same object in this segment",
        tostring(markers:find(NAME) == m))

  -- 13. THE ROUND TRIP, and the tolerance is the point: the position must land on the TILE the marker
  --     reports and not on its corner, so the check derives the tile from the durable form and compares it
  --     to the segment tile coord the marker itself publishes. A check that quantised its input the other
  --     way round would pass on a corner too.
  local mp = m:position()
  local a = mp and mp:info()
  local ag = a and hafen.map():grid():get(a.gridId)
  local asc = ag and ag:segmentCoord()
  local tc = m:segmentTile()
  local at = a and { x = math.floor(a.x / TILE), y = math.floor(a.y / TILE) }
  local again = a and hafen.world():position(a):info()
  check(asc and at and (((asc.x * CMAPS) + at.x) == tc.x) and (((asc.y * CMAPS) + at.y) == tc.y)
          and again and (again.gridId == a.gridId) and (again.x == a.x) and (again.y == a.y),
        "marker:position() lands on the very tile the marker reports, and its durable form round-trips exactly",
        (a == nil) and "the marker's position answered nil (its grid was still loading)"
                    or ("grid %s tile %d,%d vs tc %d,%d"):format(a.gridId, at.x, at.y, tc.x, tc.y))

  -- 14. the pin was created BARE and configured by chaining -- and every setter reads back
  m:color(200, 80, 80):onMap(true)
  local c = m:color()
  check(c and (c.r == 200) and (c.g == 80) and (c.b == 80) and (m:onMap() == true)
          and (m:info().onmap == true),
        "the chained setters took on the bare pin, and each of them reads back through its own bare arity",
        ("r=%s g=%s b=%s onMap=%s"):format(tostring(c and c.r), tostring(c and c.g), tostring(c and c.b),
                                           tostring(m:onMap())))

  -- 15. and the database is left exactly as it was found
  markers:remove(m)
  check((m:exists() == false) and (markers:find(NAME) == nil) and (markers:count() == before),
        "the pin this suite dropped is out of the database again, and the marker says so itself",
        ("exists=%s count=%d vs %d"):format(tostring(m:exists()), markers:count(), before))
end

-- ---- the display toggles: a hold, and never a boolean ------------------------------------------------
local function holdRound()
  local toggles = hafen.map():overlay()
  local all = toggles:list()

  -- 16. the closed set enumerates as objects, three drawn in the world and one on the map window
  local cplot, realm = toggles:get("cplot"), toggles:get("realm")
  check((#all == 4) and (toggles:get("cplot") == cplot)
          and (cplot:where() == "world") and (realm:where() == "map")
          and (type(cplot:shown()) == "boolean"),
        "the client's four display toggles enumerate as interned objects -- three in the WORLD, one on the MAP",
        ("%d rows; cplot=%s realm=%s"):format(#all, cplot:where(), realm:where()))

  -- Pick a toggle the screen is NOT already drawing. A hold's idempotence is invisible on a tag somebody
  -- else already holds: the release would leave it shown either way, and the check would be measuring the
  -- user's checkbox. So the fixture is chosen so the failure can be seen, and said out loud when it cannot.
  local t
  for _, x in ipairs(all) do
    if x:shown() == false then t = x break end
  end
  if not t then
    manualCheck("turn off one of the claim/province overlays in the client's map menu and run ':t039-4' again",
                "three more [pass]: all four overlays are currently drawn by somebody else, so a hold and its"
                .. " release cannot be told apart from doing nothing")
    return
  end
  local tag = t:tag()

  -- 17. the round trip: the hold shows it, the release puts it back, and :shown() and :held() are two
  --     different questions throughout -- one about the screen, one about us.
  t:hold()
  local onWhile, heldWhile = t:shown(), t:held()
  t:release()
  check((onWhile == true) and (heldWhile == true) and (t:shown() == false) and (t:held() == false),
        ("t:hold() draws \"%s\" and t:release() gives it back -- :shown() is the screen, :held() is ours")
          :format(tag),
        ("shown=%s held=%s after=%s"):format(tostring(onWhile), tostring(heldWhile), tostring(t:shown())))

  -- 18. the hold is IDEMPOTENT, which is what makes ONE release -- the teardown's -- the whole undo. With a
  --     second count standing, the single release below would leave the overlay drawn forever with no owner.
  t:hold():hold():release()
  check((t:shown() == false) and (t:held() == false),
        "a second take adds no second count, so one release is the whole undo (the ref count's own rule)",
        ("after two takes and one release: shown=%s"):format(tostring(t:shown())))

  -- 19. ...and a release with nothing held is inert rather than somebody else's -1
  t:release()
  check((t:shown() == false) and (t:held() == false),
        "a release with no hold is inert, never a -1 on a count this addon does not own",
        tostring(t:shown()))
end

-- ---- the icon registry: the argument shape stops carrying meaning ------------------------------------
local function iconRound()
  local icons = hafen.map():icon()
  local cats = icons:list()
  local cat = cats[1]
  if not cat then
    manualCheck("run ':t039-4' once the HUD is up", "one more [pass]: the icon registry is empty until the"
                .. " HUD has built it, so :get(res) had nothing to address")
    return
  end
  -- 20. :get(res) addresses by RESOURCE name and :list/:find search the DISPLAY name -- two verbs, two
  --     questions, and no rule about which argument shape means which. A number is refused either way.
  local byName = icons:find(cat:name())
  check((icons:get(cat:res()) == cat) and (byName ~= nil) and (icons:count() == #cats),
        "icon:get(res) addresses by resource name while :find(needle) searches display names -- no shape rule",
        ("%d categories; first=%s (%s)"):format(#cats, cat:res(), tostring(cat:name())))
end

-- ---- the main run ------------------------------------------------------------------------------------
local function run()
  pass, fail, manual = 0, 0, 0

  -- 1. the section and its five collections are singletons: a panel walking the map calls these every frame
  local map = hafen.map()
  check((map == hafen.map()) and (map:segment() == hafen.map():segment())
          and (map:grid() == hafen.map():grid()) and (map:marker() == hafen.map():marker())
          and (map:icon() == hafen.map():icon()) and (map:overlay() == hafen.map():overlay()),
        "hafen.map() and each of its five collections are handed back by identity, never built per call",
        tostring(map))

  -- 2. every retired SECTION spelling throws naming what replaced it -- a cut is a feature and needs its own
  --    regression, or a port is a hunt through "attempt to call a nil value".
  allRefuse("every retired hafen.map spelling throws naming its replacement", {
    { function() return hafen.map.segment end,  "hafen.map():segment()", "hafen.map.segment" },
    { function() return hafen.map.segments end, "hafen.map():segment():list()", "hafen.map.segments" },
    { function() return hafen.map.grid end,     "hafen.map():grid():get(gridId)", "hafen.map.grid" },
    { function() return hafen.map.markers end,  "hafen.map():marker()", "hafen.map.markers" },
    { function() return hafen.map.icons end,    "hafen.map():icon()", "hafen.map.icons" },
    { function() return hafen.map.overlay end,  "hafen.map():overlay():get(tag)", "hafen.map.overlay" },
    { function() return hafen.map.overlays end, "hafen.map():overlay():list()", "hafen.map.overlays" },
    { function() return hafen.map(1) end,       "takes no arguments", "hafen.map(1)" },
  })

  local me = hafen.player() and hafen.player():gob()
  local p = me and me:position()
  local gp = p and p:info()
  local seg = hafen.map():segment():current()
  local grid = gp and hafen.map():grid():get(gp.gridId)
  if not (p and gp and seg and grid) then
    check(false, "the player and the map database are both up (every check below stands on them)",
          (p == nil) and "no player gob"
                      or "no segment/grid yet -- the map DB streams in a beat after enter-world")
    return summary()
  end

  -- 3. ...and so does every retired ENTITY method, on the two entities whose verbs moved
  local anyMarker = hafen.map():marker():list()[1]
  local rows = {
    { function() return grid.sc end,       "grid:segmentCoord()", "grid:sc" },
    { function() return grid.pos end,      "grid:position()", "grid:pos" },
    { function() return grid.mtime end,    "grid:modified()", "grid:mtime" },
    { function() return grid.overlays end, "grid:overlay():list()", "grid:overlays" },
    { function() return grid:overlay("cplot") end, "COLLECTION", "grid:overlay(tag)" },
    { function() return seg.grids end,     "seg:grid():list(area)", "seg:grids" },
    { function() return seg:grid{ x = 0, y = 0 } end, "COLLECTION", "seg:grid(sc)" },
  }
  if anyMarker then
    rows[#rows + 1] = { function() return anyMarker.tc end,     "marker:segmentTile()", "marker:tc" }
    rows[#rows + 1] = { function() return anyMarker.pos end,    "marker:position()", "marker:pos" }
    rows[#rows + 1] = { function() return anyMarker.anchor end, "marker:position() IS the anchor", "marker:anchor" }
    rows[#rows + 1] = { function() return anyMarker.dist end,   "marker:distance()", "marker:dist" }
    rows[#rows + 1] = { function() return anyMarker.onmap end,  "marker:onMap()", "marker:onmap" }
  end
  allRefuse("every retired Grid, Segment and Marker method throws naming its replacement", rows)

  -- 4. a collection is an object, not a sequence: there is one way to enumerate it and it is :list()
  refuses("# is refused on a collection, naming :count() and :list()",
          function() return #hafen.map():overlay() end, ":count() is how")
  refuses("...and so is indexing one, because :list() is the array you index",
          function() return hafen.map():overlay()[1] end, "not an array")

  -- 5. the two collections that deliberately do NOT enumerate say so, rather than answering an empty list
  refuses("hafen.map():grid() refuses to enumerate the whole database, naming what does work",
          function() return hafen.map():grid():list() end, "does not enumerate")
  refuses("...and a segment refuses too: you ask it for an AREA, never for a list",
          function() return seg:grid():count() end, "does not enumerate")

  markerRound(p, seg)
  holdRound()
  iconRound()

  -- The recorded half is staged: a grid read kicks a disk load and answers nil, so the first sweep of the
  -- neighbourhood is the one that starts them. Wait first, then judge (035.2).
  hafen.timer():after(1.5, function()
    recorded(p, gp, seg, grid)
    summary()
  end)
end

hafen.slash():register("t039-4", run)   -- the only way in: a suite does not start itself
