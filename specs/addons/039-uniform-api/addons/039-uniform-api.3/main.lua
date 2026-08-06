-- 039.3 — gob:overlay() as a collection, and the Overlay builder. Self-checking suite; see specs/addons/TESTING.md.
--
-- 038 gave the API one verb for everything attached to a game object, with the ARITY as the verb and a spec
-- TABLE saying what to draw. This task spends both on the uniform grammar: gob:overlay() is the COLLECTION
-- (:list/:get/:add/:remove) and the spec table becomes SETTERS on the Overlay :add hands back.
--
--   gob:overlay():add("hp"):text("hurt"):color(255, 90, 90):offset(0, -6)
--
-- Two things that follow from the shape rather than from a rule. An overlay is attached BARE, so until it
-- names a kind it draws nothing -- a half-configured overlay never paints, and there is no half-parsed spec to
-- roll back. And every setter has a bare read, so what you wrote is what you can read: the configuration is the
-- overlay's own state, which is why :text("...") relabels a live one instead of replacing it.
--
-- What must NOT have changed is 038's whole claim -- an overlay lives ON the gob and dies with it. Only a
-- person can make a gob despawn, so that half is ':t039-3 park' / walk / ':t039-3 gone', 038.4's precedent and
-- the one place this suite needs a second command (D-085). Everything else is ':t039-3' and nothing else.
--
-- READ-ONLY: no permissions, attaches only to the player's own gob (the park round aside, which names the gobs
-- it touched and takes its overlays back off), and writes nothing persistent.

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

local KEY  = "cfg"                    -- this suite's key on the player's own gob
local PARK = "039-3-park"             -- the key the despawn round hangs on other gobs
local DX   = 22.0                     -- the world-unit x offset the live-offset check reads back

local icon
local adds, removes = 0, 0            -- OUR events since the last reset, for the replace check
local parked, dropped = {}, {}        -- the despawn round: the gobs we hung on, and the ones we were told died
local expect = {}                     -- removals WE asked for, swallowed so they never read as a despawn

hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

hafen.event():on("GobOverlayAdded", function(e) if not e.native then adds = adds + 1 end end)
hafen.event():on("GobOverlayRemoved", function(e)
  if e.native then return end
  removes = removes + 1
  if e.key ~= PARK then return end
  local id = e.gob:id()
  if (expect[id] or 0) > 0 then expect[id] = expect[id] - 1 else dropped[id] = true end
end)

-- How many of the overlays on this gob are OURS, and how many are the game's. Another addon's are never in the
-- list (keys are per addon), so this is the whole of "did we leave anything behind".
local function census(g)
  local ours, theirs = 0, 0
  if g and g:exists() then
    for _, ov in ipairs(g:overlay():list()) do
      if ov:native() then theirs = theirs + 1 else ours = ours + 1 end
    end
  end
  return ours, theirs
end

-- Any gob in sight carrying one of the GAME's own overlays, and that overlay's key (its resource name).
local function anyNative()
  for _, g in ipairs(hafen.world():gob():list()) do
    for _, ov in ipairs(g:overlay():list()) do
      if ov:native() then return g, ov:key(), ov end
    end
  end
end

-- The first gob in sight carrying NO overlay at all -- the case gob:info().overlays has to be absent on.
local function anyBare()
  for _, g in ipairs(hafen.world():gob():list()) do
    local ours, theirs = census(g)
    if (ours == 0) and (theirs == 0) then return g end
  end
end

local parkRound, goneRound

local function run()
  pass, fail, manual = 0, 0, 0        -- a re-run reports its own counts, not the last one's

  local me = hafen.player() and hafen.player():gob()
  if (me == nil) or (not me:exists()) then
    check(false, "the player's own gob is what every check below attaches to", "no player gob yet")
    return summary()
  end
  me:overlay():remove(KEY)            -- a re-run starts from a bare gob

  -- 1. THE CUT. All four old arities are one refusal, and it names every verb that replaced them.
  refuses("the read-one arity throws naming :get", function() return me:overlay(KEY) end, "gob:overlay():get(key)")
  refuses("...the attach arity, naming :add", function() me:overlay(KEY, { text = "x" }) end, "gob:overlay():add(key)")
  refuses("...the remove arity, naming :remove", function() me:overlay(KEY, nil) end, "gob:overlay():remove(key)")
  refuses("...and gob:overlays(), naming :list", function() return me:overlays() end, "gob:overlay():list()")
  refuses("the collection is an OBJECT, not a sequence: # names :count() and :list()",
          function() return #me:overlay() end, ":count()")

  -- 2. BARE. :add attaches and hands back an overlay that has not yet said what it draws -- so it paints
  --    nothing, and there is no half-parsed spec anywhere to roll back.
  local bare = me:overlay():add(KEY)
  check(bare:exists(), "gob:overlay():add(key) attaches at once and hands back the Overlay", bare:exists())
  eq("...and a bare one names no kind, so it draws nothing", bare:kind(), nil)
  eq("...while the collection already counts it", me:overlay():count(), select(2, census(me)) + 1)

  -- 3. THE SETTERS. Each answers the overlay, so one statement configures the whole thing; each has a bare
  --    read, so what you wrote is what you can read back.
  local ov = me:overlay():add(KEY):text("hurt"):color(255, 90, 90):offset(0, -6)
  check(ov == me:overlay():get(KEY), "the setters chain, and the collection reads that same overlay back",
        tostring(ov))
  eq("...:text() reads back what :text(s) was given", ov:text(), "hurt")
  eq("...:color() reads back a colour value, so it passes straight into another setter", ov:color()[1], 255)
  eq("...:offset() reads back the screen pixels it was given", ov:offset().y, -6)
  eq("...and the kind it named is what it is", ov:kind(), "text")
  eq("...a live one RELABELS rather than replacing: the configuration is the overlay's own state",
     ov:text("healed"):text(), "healed")
  refuses("...and a SECOND, different kind is refused naming the first",
          function() ov:draw(function() end) end, "already draws 'text'")
  refuses("...as is a world-space verb on a screen-space overlay, naming the kinds",
          function() ov:scale(2) end, "SCREEN-space")

  -- 4. ov:pos() is gone, and the one position verb hands back a Position.
  refuses("overlay:pos() throws naming overlay:position()", function() return ov:pos() end, "overlay:position()")

  -- 5. THE WORLD-SPACE HALF, and the offset is LIVE: setting it moves the thing where it stands rather than
  --    re-attaching it. Read as a DIFFERENCE, so the player's own position cancels out of both samples.
  if icon then
    local w = me:overlay():add(KEY):image(icon):scale(2):offset(0, 0, 18)
    eq("...:image(asset) stands it in the 3D world", w:kind(), "image")
    eq("...and the same key across two spaces still leaves ONE overlay", (census(me)), 1)
    local a = w:position()
    local b = w:offset(DX, 0, 18):position()
    check(a and b and (math.abs((b:x() - a:x()) - DX) < 1.0),
          ("overlay:position() is a Position, and :offset moves it live in world units (+%d on x)"):format(DX),
          a and b and ("dx = " .. tostring(b:x() - a:x())) or "no position")
  else
    check(false, "the suite's own icon.png loaded (a suite stands alone, so it ships its own asset)", "nil")
  end

  -- 6. THE GAME'S OWN. Read-only through the collection's own verbs, keyed by resource name, and still a
  --    UNION over that name -- which is the whole reason ov:count() exists.
  local ng, nk, nov = anyNative()
  if ng then
    eq("one of the game's own overlays reads native = true", nov:native(), true)
    check((nov:count() or 0) >= 1, "...and ov:count() still publishes how many engine overlays it stands for",
          nov:count())
    refuses("...an :add onto its key raises naming the key", function() ng:overlay():add(nk) end, nk)
    refuses("...and so does a :remove of it -- never a silent no-op",
            function() ng:overlay():remove(nk) end, nk)
    local raw, sum = ng:info().overlays, 0
    for _, o in ipairs(ng:overlay():list()) do
      if o:native() then sum = sum + (o:count() or 0) end
    end
    eq("...and gob:info().overlays is the raw uncollapsed list, reconciling with the keyed read",
       raw and #raw or nil, sum)
  else
    manualCheck("nothing in sight carries one of the game's own overlays -- stand near a lit fire, a growing"
                .. " crop or a curiosity and run ':t039-3' again",
                "the five native lines print instead of this one")
  end

  -- 7. ABSENT, NOT EMPTY. Like every GobInfo field, `overlays` is missing rather than an empty table when the
  --    gob carries none -- the one defect 038's in-game round found, and it is a docs claim as much as a code
  --    one. It has to be asked of a gob known to carry NOTHING, or it proves nothing either way.
  local bg = anyBare()
  if bg then
    eq("gob:info().overlays is ABSENT, not empty, on a gob that carries none", bg:info().overlays, nil)
    eq("...and the collection answers an EMPTY LIST for the same gob, which is not the same thing",
       #bg:overlay():list(), 0)
  else
    manualCheck("every gob in sight carries an overlay, so the absent-not-empty check has no subject --"
                .. " walk somewhere emptier and run ':t039-3' again", "the two absent-not-empty lines print")
  end

  -- 8. THE REPLACE, which is the one thing the events have to keep saying (D-105): the key survives, the thing
  --    under it does not, so a handler keeping its own set stays balanced. Counted from a LATER frame -- the
  --    events are queued onto the tick, so a reset inline would zero them in the wrong one, and the gob is
  --    cleared HERE so that every event the count sees belongs to the count.
  me:overlay():remove(KEY)
  hafen.timer():after(0.5, function()
    adds, removes = 0, 0
    me:overlay():add(KEY):text("one")
    me:overlay():add(KEY):text("two")
    eq("an :add on a live key leaves ONE overlay, not two", (census(me)), 1)
    eq("...and the read collapses on the key: it is the second one", me:overlay():get(KEY):text(), "two")
    me:overlay():remove(KEY)

    hafen.timer():after(0.5, function()
      eq("the replace fired the removal AND the add, beside the first add and the last removal", adds, 2)
      eq("...so every add was matched by a removal", removes, 2)
      eq("the suite leaves nothing of its own on the gob", (census(me)), 0)
      manualCheck("run ':t039-3 park', walk ~100 tiles away until those objects unload, walk back, then"
                  .. " ':t039-3 gone'",
                  "the gone round reports each parked object's overlay destroyed with a GobOverlayRemoved, no"
                  .. " icon left floating where it stood, and a returning object BARE")
      summary()
    end)
  end)
end

-- ---- the despawn round: an overlay still dies with its gob ------------------------------------------------

-- A deliberate removal fires the same event a despawn does, so it is announced here and swallowed by the
-- handler above. What is left is a removal nobody asked for, which is the gob dying.
local function unpark(g)
  if not (g and g:exists() and (g:overlay():get(PARK) ~= nil)) then return end
  expect[g:id()] = (expect[g:id()] or 0) + 1
  g:overlay():remove(PARK)
end

parkRound = function()
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
  check(n > 0, ("a world overlay is hanging on %d nearby object(s), and GobOverlayRemoved is being watched")
                 :format(n),
        "nothing else in sight -- stand near some trees and run ':t039-3 park' again")
  manualCheck("walk ~100 tiles away until those objects unload, walk back, then ':t039-3 gone'",
              "no icon is left floating where they stood -- a world overlay's visual is its own object in the"
              .. " scene, and it has to be destroyed with its target rather than held at its last position")
  summary()
end

-- "It still exists" is NOT the test: a gob that unloaded and streamed back in exists again, and is the most
-- interesting case of all. The EVENT is the signal, and a returning object reading bare is the claim.
goneRound = function()
  pass, fail, manual = 0, 0, 0
  local total, told, trip, stillGone, errs, bare = 0, 0, 0, 0, 0, 0
  for id in pairs(parked) do
    total = total + 1
    local g = hafen.world():gob():get(id)
    local ok, v = pcall(function() return g:overlay():get(PARK) end)
    if not ok then errs = errs + 1 end
    if dropped[id] then
      told = told + 1
      if g:exists() then trip = trip + 1 else stillGone = stillGone + 1 end
      if ok and (v == nil) and ((census(g)) == 0) then bare = bare + 1 end
    end
    unpark(g)
  end
  check(total > 0, ("the park round hung an overlay on %d object(s)"):format(total),
        "nothing was parked -- run ':t039-3 park' first")
  eq("reading the collection through a gob that despawned never errors -- it answers an empty one", errs, 0)
  if told > 0 then
    check(true, ("%d of the %d parked objects despawned, and GobOverlayRemoved fired for each -- the overlay is"
                 .. " destroyed from the GobRemoved drain, before the event reaches Lua"):format(told, total))
    eq("...and NO record was kept behind: each of them reads bare, still gone or streamed back", bare, told)
    if trip > 0 then
      check(true, ("%d of them came BACK, and a returning object is bare -- nothing is kept in case it returns,"
                   .. " so re-attaching is the addon's own call from GobAdded"):format(trip))
    else
      manualCheck(("walk back to where the %d despawned object(s) stood, then ':t039-3 gone' again")
                    :format(stillGone),
                  "a returning object is reported BARE -- the record went with the gob, it was not kept")
    end
  else
    manualCheck("none of the parked objects has despawned yet -- walk further and run ':t039-3 gone' again",
                "at least one despawns, its GobOverlayRemoved is reported, and no icon is left floating")
  end
  parked, dropped = {}, {}
  summary()
end

hafen.slash():register("t039-3", function(args)
  local sub = args and args[1]
  if sub == "park" then return parkRound() end
  if sub == "gone" then return goneRound() end
  return run()
end)   -- the only way in: a suite does not start itself
