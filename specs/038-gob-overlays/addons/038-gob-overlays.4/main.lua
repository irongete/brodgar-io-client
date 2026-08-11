-- 038.4 — the close: churn, the example, and the docs. Self-checking suite; see specs/testing/addon-suite.md.
--
-- 038.1 put the verb on the gob, 038.2 brought the world-space family home and killed follow=, 038.3 made the
-- attachment observable. This task closes the feature, and what is left to PROVE is the churn: the whole
-- feature's claim is that an overlay lives ON the gob, so the things that end it must actually end it.
--
--   * ':reload' (or a disable) removes every overlay THIS addon attached, and leaves the GAME's own alone.
--     No addon can watch its own teardown -- the only one that could hear it is the one going away (D-104) --
--     so this is the one check that spans two runs: the first run parks a marker in the store and the run
--     AFTER the reload reads it back and prints the verdict. That is why ':t038-4' is typed twice below.
--   * A gob that despawns takes its overlays with it, fires GobOverlayRemoved, leaves NO record behind, and
--     never errors on the read afterwards -- ':t038-4 park' / walk / ':t038-4 gone'.
--   * Attach/replace/remove, in a tight loop, stays balanced: every add is matched by a removal, a replace
--     reports BOTH, and the gob ends bare. That is the end-to-end shape of D-105.
--
-- And the docs are code: the snippets in docs/addons/api/gob.md#overlays and conventions.md are transcribed
-- here VERBATIM and run, because a documented example that does not execute is worse than no example.
--
-- READ-ONLY: no permissions, attaches only to the player's own gob (plus the park round's explicit, cleaned-up
-- markers), and the ONE thing it persists is its own teardown marker, in its own account-scoped store, cleared
-- the moment it is read.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local KEEP = { "keep-text", "keep-draw", "keep-world" }   -- the three left behind for the reload check
local CHURN = "churn"                                     -- the key the balance loop cycles
local PARK  = "park"                                      -- the key the despawn round hangs on other gobs
local DOCS  = { "hp", "ring", "mark", "tag" }             -- the keys the documented snippets use

local icon
local adds, removes = 0, 0            -- OUR events, counted for the balance check
local parked, dropped = {}, {}        -- the despawn round: gob ids we hung on, and the ones we were told died

hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

-- The despawn round listens for its own key's removal -- but the round ALSO takes those overlays off by hand,
-- and a removal we asked for fires the same event. So a deliberate one is announced first and swallowed here;
-- what is left is a removal nobody asked for, which is the gob dying. (Without this, the round's own cleanup
-- would report itself as a despawn on the next run: the events arrive a frame later, after the reset.)
local expect = {}

hafen.event():on("GobOverlayAdded", function(e) if not e.native then adds = adds + 1 end end)
hafen.event():on("GobOverlayRemoved", function(e)
  if e.native then return end
  removes = removes + 1
  if e.key ~= PARK then return end
  local id = e.gob:id()
  if (expect[id] or 0) > 0 then expect[id] = expect[id] - 1 else dropped[id] = true end
end)

-- Take our park overlay off a gob, on purpose. Nothing happens to a gob that is already gone -- it took the
-- overlay with it, which is the whole claim.
local function unpark(g)
  if not (g and g:exists() and (g:overlay():get(PARK) ~= nil)) then return end
  expect[g:id()] = (expect[g:id()] or 0) + 1
  g:overlay():remove(PARK)
end

-- How many of the overlays on this gob are OURS. gob:overlay():list() answers ours and the game's; another
-- addon's are never in it (keys are per addon), so this is the whole of "did we leave anything behind".
local function mine(g)
  local n, all = 0, g and g:exists() and g:overlay():list()
  if all then
    for _, ov in ipairs(all) do
      if not ov:native() then n = n + 1 end
    end
  end
  return n
end

local function natives(g)
  local n, all = 0, g and g:exists() and g:overlay():list()
  if all then
    for _, ov in ipairs(all) do
      if ov:native() then n = n + 1 end
    end
  end
  return n
end

-- Any gob in sight carrying one of the GAME's own overlays, and that overlay's key (its resource name).
local function anyNative()
  for _, g in ipairs(hafen.world():gob():list()) do
    local all = g:overlay():list()
    if all then
      for _, ov in ipairs(all) do
        if ov:native() then return g, ov:key() end
      end
    end
  end
end

local function clear(g, keys)
  if not (g and g:exists()) then return end
  for _, k in ipairs(keys) do g:overlay():remove(k) end
end

-- ---- 1. the teardown verdict, read back from the run before the reload -----------------------------------

-- A suite cannot watch its own teardown, so it leaves a marker and the NEXT run reads it. The marker names the
-- gob and the keys, so the check asserts through the very door the attach used -- not through a count some
-- other teardown path also drives to zero (037.4's lesson).
local function teardownVerdict()
  local p = hafen.store():get("state").pending
  if p == nil then return false end
  hafen.store():get("state").pending = nil
  hafen.store():flush()
  local g = hafen.world():gob():get(p.gob)
  if not g:exists() then
    manualCheck("the gob the last run marked (" .. tostring(p.gob) .. ") is not loaded now -- run ':t038-4'"
                .. " again to re-park the marker, then ':reload', then ':t038-4'",
                "the two teardown lines print instead of this one")
    return true
  end
  local left = 0
  for _, k in ipairs(p.keys) do
    if g:overlay():get(k) ~= nil then left = left + 1 end
  end
  eq("':reload' removed every overlay this addon had attached -- " .. #p.keys .. " of them, in both spaces",
     left, 0)
  -- Not just those three keys: nothing of this addon's survived on that gob at all. The game's own are counted
  -- separately and reported, never asserted equal -- most of them are transient sprites that come and go on
  -- their own schedule, so a strict comparison here would measure the world, not the teardown. What DOES pin
  -- the "leaves the natives alone" half is the refusal in the round below: they are not ours to remove.
  check(mine(g) == 0,
        ("...and nothing of this addon's survived on that gob (the game's own: %d before the reload, %d now)")
          :format(p.natives, natives(g)), mine(g))
  return true
end

-- ---- 2. the docs are code -------------------------------------------------------------------------------

-- Every snippet below is TRANSCRIBED from docs/addons/api/gob.md#overlays and conventions.md. If one of them
-- stops running, the page is wrong, and this is the line that says so.
local function docsRound(me)
  local ok, err = pcall(function()
    -- gob.md, "Overlays":
    me:overlay():add("hp"):text("hurt"):color(255, 90, 90):offset(0, -6)
    me:overlay():add("ring"):draw(function(g, gob, sx, sy) g:frect(sx - 2, sy - 2, 4, 4) end)
    me:overlay():add("mark"):image(icon):scale(2):offset(0, 0, 18)
    me:overlay():remove("hp")
  end)
  check(ok, "the four snippets on gob.md#overlays run exactly as written", err)
  eq("...and the documented remove really removed that one", me:overlay():get("hp"), nil)
  local ring, mark = me:overlay():get("ring"), me:overlay():get("mark")
  eq("...while the other two are still there, each answering its documented kind",
     ring and ring:kind(), "draw")
  eq("...and the world-space one too", mark and mark:kind(), "image")

  -- gob.md, "The verbs on a world overlay" -- the chain is the claim, so assert it CHAINS.
  local ov = me:overlay():add("mark"):image(icon):offset(0, 0, 20)
  local chained = ov:tint(255, 90, 90):alpha(0.7):scale(2)
  check(chained == ov, "the composed verbs chain, each answering the overlay (ov:tint(...):alpha(0.7):scale(2))",
        tostring(chained))

  -- guides/custom-ui.md, "Overlays" -- the filter form's replacement, in the two lines the page shows.
  local tagged = 0
  local function tag(gob)
    if gob:isPlayer() then gob:overlay():add("tag"):text("player"):color(0, 255, 0) tagged = tagged + 1 end
  end
  for _, g in ipairs(hafen.world():gob():list()) do tag(g) end
  check(tagged > 0, ("the custom-ui.md tagging loop runs and labelled %d player body/bodies"):format(tagged),
        "no player gob in sight, not even your own")

  for _, g in ipairs(hafen.world():gob():list()) do g:overlay():remove("tag") end
  clear(me, DOCS)
  eq("the docs round leaves nothing of its own behind", mine(me), 0)
end

-- ---- 3. churn: every add matched by a removal ------------------------------------------------------------

local function churn(me, n)
  for i = 1, n do
    me:overlay():add(CHURN):text("c" .. i)                       -- add
    me:overlay():add(CHURN):text("c" .. i):color(1, 2, 3)        -- REPLACE: a removal AND an add
    me:overlay():remove(CHURN)                                   -- remove
  end
end

-- ---- the round ------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local hadMarker = teardownVerdict()

  local me = hafen.player() and hafen.player():gob()
  if (me == nil) or (not me:exists()) then
    check(false, "the player's own gob is what every check below attaches to", "no player gob yet")
    return summary()
  end
  clear(me, DOCS) clear(me, KEEP) me:overlay():remove(CHURN)   -- a re-run starts from a bare gob
  if icon == nil then
    check(false, "the suite's own icon.png loaded (a suite stands alone, so it ships its own asset)", "nil")
    return summary()
  end

  docsRound(me)

  -- THE OTHER HALF OF "teardown leaves the natives alone": they are not ours to remove in the first place, so
  -- no sweep of ours can take one. Re-asserted here rather than borrowed from 038.1's suite, because it is
  -- this task's premise and a suite must convince alone (D-085).
  local ng, nk = anyNative()
  if ng then
    refuses("the game's own overlays are not this addon's to remove -- a remove of one raises, naming the key",
            function() ng:overlay():remove(nk) end, nk)
    refuses("...and a native key is not ours to attach onto either",
            function() ng:overlay():add(nk) end, nk)
    eq("...and after both refusals the game's overlay is still there, untouched", ng:overlay():get(nk) ~= nil, true)
    -- gob.md: the raw, UNCOLLAPSED view is still there beside the keyed one -- and the honest test of that
    -- sentence is 038.1's invariant, not a type check: the raw list must reconcile with sum(ov:count()) over
    -- the keyed natives, since keying by resource name is exactly what collapses them. (The first version
    -- asserted `type(info.overlays) == "table"` on the PLAYER's gob and went red for the right reason: like
    -- every GobInfo field it is ABSENT, not empty, when the gob carries none. The page now says so.)
    local raw, sum = ng:info().overlays, 0
    for _, ov in ipairs(ng:overlay():list()) do
      if ov:native() then sum = sum + (ov:count() or 0) end
    end
    eq("gob:info().overlays is the raw uncollapsed list, and it reconciles with the keyed read",
       raw and #raw or nil, sum)
  else
    manualCheck("nothing in sight carries one of the game's own overlays -- stand near a lit fire, a growing"
                .. " crop or a curiosity and run ':t038-4' again",
                "the three 'the game's own overlays are not this addon's to remove' lines replace this one")
  end

  -- The counters can only be zeroed from a LATER frame. Everything above queued events of its own, and the
  -- events are delivered on the tick, not inside the verb -- so resetting inline would zero the counters and
  -- then let the docs round's own burst land on top of the churn's. (The dry run bit exactly here: 85, not 80.)
  hafen.timer():after(0.5, function()
    -- 40 cycles = 40 adds + 40 replaces (a removal AND an add each) + 40 removals = 80 and 80.
    adds, removes = 0, 0
    churn(me, 40)
    eq("nothing is left on the gob after 40 attach/replace/remove cycles", mine(me), 0)
    eq("...and the last removal really removed", me:overlay():get(CHURN), nil)

    hafen.timer():after(0.5, function()
      -- The events are queued onto the tick and drained one frame's worth at a time (D-106); nothing here
      -- re-attaches from a handler, so one drain carries the whole burst.
      eq("every attach in the churn was reported exactly once", adds, 80)
      eq("...and every one of them was matched by a removal, so a handler keeping its own set never drifts",
         removes, 80)

      -- Park the marker the NEXT run reads back: one overlay per space, so the teardown check covers both the
      -- screen-space record (bookkeeping on the gob) and the world-space one (its own client gob in the scene).
      me:overlay():add(KEEP[1]):text("reload me"):color(200, 200, 90)
      me:overlay():add(KEEP[2]):draw(function(g, gob, sx, sy) g:rect(sx - 4, sy - 4, 8, 8) end)
      me:overlay():add(KEEP[3]):image(icon):scale(2):offset(0, 0, 22)
      eq("three overlays are parked for the teardown check, in both spaces", mine(me), 3)
      hafen.store():get("state").pending = { gob = me:id(), keys = KEEP, natives = natives(me) }
      hafen.store():flush()

      if not hadMarker then
        manualCheck("type ':reload', then ':t038-4' again -- no addon can watch its own teardown, so this is"
                    .. " the one check that spans two runs",
                    "the first two lines of that run are '[pass] :reload removed every overlay this addon had"
                    .. " attached' and '[pass] ...and nothing of this addon's survived on that gob'")
      end
      manualCheck("run ':t038-4 park', walk ~100 tiles away until those objects unload, walk back,"
                  .. " then ':t038-4 gone'", "the despawn round reports each object's overlay destroyed with a"
                  .. " GobOverlayRemoved, no icon left floating where it stood, and a returning object BARE")
      manualCheck("type ':tagger', then ':tagger pin', then ':tagger read'; then ':reload'; then disable"
                  .. " 'tagger' in the AddOns panel",
                  "green names over the players and a tinted icon floating over the nearest object; ':tagger"
                  .. " read' lists yours AND the game's own; both survive ':reload' only if you re-arm them"
                  .. " (':tagger' again), and disabling clears everything tagger drew")
      summary()
    end)
  end)
end

-- ---- the despawn round ----------------------------------------------------------------------------------

local function parkRound()
  pass, fail, manual = 0, 0, 0
  for id in pairs(parked) do unpark(hafen.world():gob():get(id)) end
  parked, dropped = {}, {}
  if icon == nil then
    check(false, "the suite's own icon.png loaded", "nil")
    return summary()
  end
  local me = hafen.player() and hafen.player():gob()
  local n = 0
  for _, g in ipairs(hafen.world():gob():list()) do
    if (n < 3) and g:exists() and (not me or (g:id() ~= me:id())) then
      g:overlay():add(PARK):image(icon):scale(2):offset(0, 0, 14)
      parked[g:id()] = true
      n = n + 1
    end
  end
  check(n > 0, ("a world overlay is hanging on %d nearby object(s), and GobOverlayRemoved is being watched"):format(n),
        "nothing else in sight -- stand near some trees and run ':t038-4 park' again")
  manualCheck("walk ~100 tiles away until those objects unload, walk back, then ':t038-4 gone'",
              "no icon is left floating where they stood -- that is the bug follow= had, and the reason a"
              .. " world overlay has to be destroyed with its target rather than held at its last position")
  summary()
end

-- WHICH gobs despawned is what `dropped` says -- and "it still exists" is NOT the test, because a gob that
-- unloaded and streamed back in exists again and is the most interesting case of all. The event is the signal.
local function goneRound()
  pass, fail, manual = 0, 0, 0
  local total, told, trip, stillGone, errs, bare = 0, 0, 0, 0, 0, 0
  for id in pairs(parked) do
    total = total + 1
    local g = hafen.world():gob():get(id)
    -- The read after the despawn must be a clean nil, never an error: a Gob is a re-resolving view, and the
    -- thing it points at going away is a moment, not a mistake.
    local ok, v = pcall(function() return g:overlay():get(PARK) end)
    if not ok then errs = errs + 1 end
    if dropped[id] then
      told = told + 1
      if g:exists() then trip = trip + 1 else stillGone = stillGone + 1 end
      -- Still gone or back again, NOTHING was kept: the whole read is empty of ours, not merely the one key.
      if ok and (v == nil) and (mine(g) == 0) then bare = bare + 1 end
    end
    unpark(g)
  end
  check(total > 0, ("the park round hung an overlay on %d object(s)"):format(total),
        "nothing was parked -- run ':t038-4 park' first")
  eq("reading an overlay through a gob that despawned never errors -- it answers nil", errs, 0)
  if told > 0 then
    check(true, ("%d of the %d parked objects despawned, and GobOverlayRemoved fired for each -- the overlay"
                 .. " is destroyed from the GobRemoved drain, before the event reaches Lua"):format(told, total))
    eq("...and NO record was kept behind: each of them reads bare, still gone or streamed back", bare, told)
    if trip > 0 then
      check(true, ("%d of them came BACK, and a returning object is bare -- nothing is kept in case it"
                   .. " returns, so re-attaching is the addon's own call from GobAdded"):format(trip))
    else
      manualCheck(("walk back to where the %d despawned object(s) stood, then run ':t038-4 gone' again")
                    :format(stillGone),
                  "a returning object is reported BARE -- the record went with the gob, it was not kept")
    end
  else
    manualCheck("none of the parked objects has despawned yet -- walk further and run ':t038-4 gone' again",
                "at least one despawns, its GobOverlayRemoved is reported, and no icon is left floating")
  end
  parked, dropped = {}, {}
  summary()
end

hafen.slash():register("t038-4", function(args)
  local sub = args and args[1]
  if sub == "park" then return parkRound() end
  if sub == "gone" then return goneRound() end
  return run()
end)
