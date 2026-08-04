-- 039.2 — the Position type, and hafen.world(). Self-checking suite; see specs/addons/TESTING.md.
--
-- The task does two things and this suite is built around the second:
--
--   * hafen.world() becomes a section, hafen.gob folds into hafen.world():gob() as ONE read-only
--     collection, and every spatial verb takes a Position instead of a pair of numbers.
--   * A POSITION IS COMPUTABLE AND DURABLE AT ONCE, which is the whole reason the type exists. The
--     headline check is that offsetting past the southern edge of the player's grid lands in the NEXT
--     grid -- and it asserts, in the same line, that the naive arithmetic it replaces would NOT have:
--     a grid is 1100 world units, so adding to a within-grid offset is right until it is not.
--
-- The durable half is proved twice, because it has two doors. hafen.json():encode/parse round-trips a
-- Position through its wire form INSIDE this run; hafen.store keeps one across a ':reload' (the parked
-- 'kept' round below), which is the only way to see the marshalling the store actually uses.
--
-- IT PUTS NOTHING BACK because it writes nothing but its OWN saved variable, and declares no
-- permissions -- so the gated verbs are tested by asserting that the gate refuses.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local TILE, SIDE = 11, 100                 -- MCache.tilesz / MCache.cmaps: a grid is 1100 world units
local GRID = TILE * SIDE

-- ---- the parked half: run AFTER a ':reload', on the Position the main run stored --------------------
local function keptRound()
  pass, fail, manual = 0, 0, 0
  local p = hafen.store.kept.home
  if p == nil then
    check(false, "a Position was stored by an earlier ':t039-2' run", "nothing in the store -- run ':t039-2' first")
    return summary()
  end
  -- It must come back a POSITION, not the {gridId, x, y} table it is written as: without the marshalling
  -- the store would have persisted a userdata as a quoted tostring, silently.
  check((type(p) == "userdata") and (type(p.info) == "function") and (p:info() ~= nil),
        "a Position survived the reload AS a Position, not as the table it is written as",
        (type(p) == "table") and "a plain table (the marshalling is missing)" or tostring(p))
  local tc, want = p:tileCoord(), hafen.store.kept.tile
  check(tc and want and (tc.x == want.x) and (tc.y == want.y),
        "...and it resolves to the very tile it named before the reload",
        (tc == nil) and "the stored place is not reachable this session"
                     or ("%d,%d vs %d,%d"):format(tc.x, tc.y, want.x, want.y))
  summary()
end

-- ---- allocation: what a Position costs against the {x, y} table it replaced -------------------------
-- memory() answers whether or not the profiler is armed. A collection mid-measure makes the delta
-- meaningless rather than wrong, so the measurement is retried and reported honestly if it never settles.
local function perCall(fn, n)
  for _ = 1, 3 do
    local m0 = hafen.client:profiling():memory()
    for _ = 1, n do fn() end
    local m1 = hafen.client:profiling():memory()
    if (m1.gcCount == m0.gcCount) and (m1.heapUsed > m0.heapUsed) then
      return (m1.heapUsed - m0.heapUsed) / n
    end
  end
  return nil
end

-- ---- the main run ----------------------------------------------------------------------------------
local function run(args)
  if args and (args[1] == "kept") then return keptRound() end
  pass, fail, manual = 0, 0, 0

  -- 1. The section and its collection are SINGLETONS: a draw callback naming either allocates nothing.
  local w = hafen.world()
  check((w == hafen.world()) and (hafen.act() == hafen.act()) and (w:gob() == w:gob()),
        "hafen.world(), hafen.act() and hafen.world():gob() are each the same object every call",
        tostring(w))

  -- 2. The gob collection is an object, not a sequence -- and it is the ONE by-id door.
  refuses("# is refused on the gob collection, naming :count()/:list()",
          function() return #w:gob() end, ":count() is how many")
  refuses("...and so is indexing it, naming :list()",
          function() return w:gob()[1] end, "not an array")

  -- 3. :get() is NEVER nil, even for an id that never existed -- the anchor-before-it-streams-in rule.
  local ghost = w:gob():get(1)                 -- an id that (almost certainly) never existed
  check((ghost ~= nil) and (ghost:exists() == false) and (ghost:id() == 1)
          and (ghost:position() == nil),
        "gob():get(<unknown id>) is never nil: :id() answers, :exists() is false, :position() is nil",
        tostring(ghost))

  -- 4. Every retired spelling THROWS, and the message names its replacement (§2.10). A deleted field
  --    would read as plain nil and fail one call later saying nothing.
  local RETIRED = {
    { "hafen.gob", function() return hafen.gob end, "hafen.world():gob():get(id)" },
    { "hafen.world.gobs", function() return hafen.world.gobs end, "hafen.world():gob():list(filter)" },
    { "hafen.world.gridPos", function() return hafen.world.gridPos end, "a Position IS the anchor" },
    { "hafen.world.fromGridPos", function() return hafen.world.fromGridPos end, "hafen.world():position(saved)" },
    { "hafen.world.worldToTile", function() return hafen.world.worldToTile end, "p:tileCoord()" },
    { "hafen.world.placeGrid", function() return hafen.world.placeGrid end, "posGran()" },
    { "hafen.world.placeAngle", function() return hafen.world.placeAngle end, "angGran()" },
    { "hafen.act.moveTo", function() return hafen.act.moveTo end, "hafen.act():moveTo(p)" },
  }
  local silent, unnamed = {}, {}
  for _, r in ipairs(RETIRED) do
    local ok, err = pcall(r[2])
    if ok then silent[#silent + 1] = r[1]
    elseif tostring(err):find(r[3], 1, true) == nil then unnamed[#unnamed + 1] = r[1] end
  end
  check((#silent == 0) and (#unnamed == 0),
        ("all %d retired spellings throw, and each names its replacement"):format(#RETIRED),
        ("read as nil: %s | said nothing useful: %s"):format(table.concat(silent, " "),
                                                             table.concat(unnamed, " ")))

  -- 5. ...including the two on the Gob object itself.
  local me = hafen.player() and hafen.player():gob()
  if not me then
    check(false, "the player's Gob is up (every check below stands on it)", "no player gob")
    return summary()
  end
  refuses("gob:pos() throws naming gob:position()", function() return me.pos end, "gob:position()")
  refuses("gob:isplayer() throws naming gob:isPlayer()", function() return me.isplayer end, "gob:isPlayer()")

  -- 6. The two placement settings survive on the door that also WRITES them (the cut was a duplicate).
  local iface = hafen.client:options():interface()
  check((type(iface:posGran()) == "number") and (type(iface:angGran()) == "number"),
        "the interface options still read the placement grain the cut verbs duplicated",
        ("posGran=%s angGran=%s"):format(tostring(iface:posGran()), tostring(iface:angGran())))

  local p = me:position()
  if not p then
    check(false, "the player has a position (every check below stands on it)", "no position yet")
    return summary()
  end

  -- 7. A Position is a VALUE: two of them naming one point are two objects, and the numbers round-trip.
  local a, b = w:position(1234.5, 678.25), w:position(1234.5, 678.25)
  check((a ~= b) and (a:x() == 1234.5) and (a:y() == 678.25),
        "a Position is a value: not interned, and its components come back exactly",
        ("%s == %s -> %s"):format(tostring(a), tostring(b), tostring(a == b)))

  -- 8. THE HEADLINE. Offsetting past the southern edge of this grid lands in the NEXT grid -- and the
  --    same line asserts that the arithmetic it replaces would have left the grid instead: the within-
  --    grid offset plus the delta is past 1100, which is a bug on a grid-relative coordinate and the
  --    whole reason the engine owns this.
  local gi = p:info()
  if not gi then
    check(false, "the player's own position is durable (the offset check stands on it)",
          "not durable here -- the ground under the player is neither streamed nor recorded")
    return summary()
  end
  local dy = (GRID - gi.y) + TILE                    -- one tile past this grid's southern edge
  local over = p:offset(0, dy)
  local oi = over and over:info()
  check(oi and (oi.gridId ~= gi.gridId) and (oi.y >= 0) and (oi.y < GRID) and ((gi.y + dy) > GRID),
        "p:offset crosses a grid boundary: the new place is a DIFFERENT grid, where gi.y + dy would have"
          .. " run past the end of this one",
        (oi == nil) and "the ground one tile south of this grid is not recorded"
                     or ("%s @%.0f -> %s @%.0f (naive %.0f > %d)"):format(gi.gridId, gi.y, oi.gridId,
                                                                          oi.y, gi.y + dy, GRID))

  -- 9. The wire form round-trips through hafen.json, which is the same marshalling the store uses.
  local text = hafen.json():encode(p)
  local backP = hafen.json():parse(text)
  local btc, ptc = (type(backP) == "userdata") and backP:tileCoord() or nil, p:tileCoord()
  check(btc and ptc and (btc.x == ptc.x) and (btc.y == ptc.y),
        "a Position encodes to its durable form and parses back AS a Position, on the same tile",
        (type(backP) ~= "userdata") and ("came back a " .. type(backP) .. ": " .. text)
                                     or ("%s vs %d,%d"):format(text, ptc.x, ptc.y))

  -- 10. A place with no grid to anchor to cannot be saved, and says so rather than losing it quietly.
  local nowhere = w:position(9e8, 9e8)
  check((nowhere:durable() == false) and (nowhere:info() == nil),
        "a place on ground never visited is not durable, and has no wire form",
        tostring(nowhere:info()))
  refuses("...and encoding one is refused naming :durable(), not written as null",
          function() return hafen.json():encode(nowhere) end, "durable()")

  -- 11. DURABLE MEANS EXPLORED, NOT LOADED. Look outward for ground that is NOT streamed in and ask
  --     whether it is still durable -- which can only be answered by the recorded map.
  local streamed = {}
  for _, g in ipairs(w:grid():list()) do streamed[g.id] = true end
  local off, offId
  for d = 2, 12 do
    for _, v in ipairs({ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) do
      local q = p:offset(d * GRID * v[1], d * GRID * v[2])
      local qi = q and q:info()
      if qi and (not streamed[qi.gridId]) then off, offId = q, qi.gridId; break end
    end
    if off then break end
  end
  if off then
    check(off:durable() and (not streamed[offId]) and (hafen.map.grid(offId) ~= nil),
          "a place on ground that is EXPLORED but not streamed is durable -- the recorded map answered,"
            .. " the live one could not",
          offId)
  else
    manualCheck("walk about a hundred tiles in one direction, then run ':t039-2' again",
                "nothing within twelve grids of you is both explored and off-stream, so the recorded"
                  .. " half of durability had nothing to answer for; after the walk the check runs"
                  .. " itself and prints [pass] with a grid id")
  end

  -- 12. A place is a TYPE now, so a plain {x, y} table is refused rather than read as a coordinate.
  refuses("a terrain read refuses a plain {x, y} table, naming what a Position is",
          function() return w:tile({ x = 1, y = 2 }) end, "must be a Position")
  check((w:tile(p) ~= nil) and (type(w:height(p)) == "number") and (w:grid():at(p) ~= nil),
        "...and it answers for a real one: tile, height and the grid under the player",
        tostring(w:tile(p) and (w:tile(p).name or w:tile(p).id)))

  -- 13. The gate is unchanged and still refuses this suite, which declares no permissions.
  check(hafen.act():enabled() == false, "hafen.act():enabled() reports the grant without throwing",
        tostring(hafen.act():enabled()))
  refuses("...and a gated verb refuses, naming itself", function() hafen.act():moveTo(p) end,
          "hafen.act():moveTo")

  -- 14. What a Position costs against the {x, y} table it replaced. Reported, not assumed.
  local posB = perCall(function() return me:position() end, 20000)
  local tabB = perCall(function() local q = me:position(); return { x = q:x(), y = q:y() } end, 20000)
  if posB and tabB then
    check(posB <= (4 * tabB),
          ("gob:position() allocates %.0f bytes a call, against %.0f for the same read plus the {x, y}"
            .. " table it replaced"):format(posB, tabB),
          ("%.0f vs %.0f"):format(posB, tabB))
  else
    manualCheck("run ':t039-2' again",
                "the heap moved under a collection while the allocation was being measured, so the"
                  .. " number would have been noise; a second run usually settles")
  end

  -- 15. Store the Position for the parked round, plus the tile it names, and tell the maintainer how to
  --     close it: a suite cannot watch its own reload.
  hafen.store.kept.home = p
  hafen.store.kept.tile = p:tileCoord()
  hafen.store.flush()

  -- 16. A GobAdded/GobRemoved handler still receives a live Gob -- staged, because only the world can
  --     fire one. The summary closes the run from inside the wake.
  local seen, bad = 0, nil
  local subA = hafen.event():on("GobAdded", function(g)
    seen = seen + 1
    if (type(g) ~= "userdata") or (type(g.id) ~= "function") or (g:id() == nil) then bad = tostring(g) end
  end)
  local subR = hafen.event():on("GobRemoved", function(g)
    seen = seen + 1
    if (type(g) ~= "userdata") or (type(g.id) ~= "function") or (g:id() == nil) then bad = tostring(g) end
    if g:exists() then bad = "a removed gob still reports :exists()" end
  end)
  hafen.timer():after(2.5, function()
    subA:off(); subR:off()
    if seen > 0 then
      check(bad == nil, ("a GobAdded/GobRemoved handler received a live Gob (%d fired)"):format(seen), bad)
    else
      manualCheck("walk a few steps so objects stream in and out, then run ':t039-2' again",
                  "nothing spawned or despawned in the two seconds after the run, so the event payload"
                    .. " had nothing to be; after the walk the check runs itself and prints [pass]")
    end
    hafen.log():write("[note] now run ':reload' and then ':t039-2 kept' -- a suite cannot watch its own reload")
    summary()
  end)
end

hafen.slash():register("t039-2", run)   -- the only way in: a suite does not start itself
