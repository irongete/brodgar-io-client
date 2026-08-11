-- 038.1 — the verb, the read, and the death of the sweep. Self-checking suite; see specs/testing/addon-suite.md.
--
-- `overlay` is the engine's own word for a thing attached to a gob, and until now the API spent it on a
-- screen-space painter that was not one: hafen.ui.gobOverlay took a FILTER, a 5 Hz sweep matched it against
-- every gob in the world, and the draw matched it again per gob per frame. gob:overlay() puts the verb on the
-- thing (D-044) and the state ON the gob, so nothing is searched, nothing is swept, and the overlay dies with
-- the object it was attached to.
--
-- gob:overlay() IS THE COLLECTION and the KEY is per addon:
--   gob:overlay():list()      every overlay on the gob -- yours, then the GAME's own (ov:native())
--   gob:overlay():get(key)    that one, or nil
--   gob:overlay():add(key)    attach a BARE one, or REPLACE (the same key twice leaves ONE overlay)
--   gob:overlay():remove(key) remove
--
-- The suite also MEASURED the thing the design had to decide rather than assume: a native overlay is keyed by
-- its RESOURCE NAME, and the first run of this suite counted 13 of 33 gobs carrying two overlays of one
-- resource. So the collapse is ordinary -- and because a native overlay is READ-ONLY, a union loses nothing
-- an addon could act on except the multiplicity, which overlay:count() now publishes. (The alternative, the
-- Gob.Overlay id, is -1 whenever the server gave none, so it collides in the same places while being a
-- number nothing else in this API speaks.) Check 9 keeps the census and turns it into an invariant:
-- gob:info().overlays is the RAW list, gob:overlay():list() the keyed one, and sum(ov:count()) reconciles them.
--
-- READ-ONLY: it declares no permissions, attaches only to the player's own gob, and removes what it attached
-- (the parked label included, dropped by ':t038-1 drop').

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

local KEY    = "tag"                  -- deliberately a plain word: another addon may use the very same one
local PARKED = "038.1 label"
local CENSUS = 400                    -- gobs sampled for the native-key census (bounded, and it says so)

local parked                          -- the gob the ':t038-1 drop' round has to clean up

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- How many of THIS addon's overlays (i.e. not the game's) does gob:overlay():list() answer?
local function mine(gob)
  local n, keys = 0, {}
  for _, ov in ipairs(gob:overlay():list()) do
    if not ov:native() then n = n + 1 keys[#keys + 1] = ov:key() end
  end
  return n, table.concat(keys, ",")
end

-- The first gob in the world carrying one of the GAME's own overlays, and that overlay's key.
local function anyNative()
  for _, g in ipairs(hafen.world():gob():list()) do
    for _, ov in ipairs(g:overlay():list()) do
      if ov:native() then return g, ov:key(), ov end
    end
  end
end

local dropRound

local function run(args)
  if (args and args[1]) == "drop" then return dropRound() end
  pass, fail, manual = 0, 0, 0        -- a re-run reports its own counts, not the last one's

  local me = hafen.player() and hafen.player():gob()
  if (me == nil) or (not me:exists()) then
    check(false, "the player's own gob is the thing every check below attaches to", "no player gob yet")
    return summary()
  end
  if parked then me:overlay():remove(KEY) parked = nil end   -- a re-run starts from a bare gob

  -- 1. THE HARD CUTS, asserted as a refusal rather than described (TESTING.md).
  eq("the hard cut is cut: hafen.ui.gobOverlay", hafen.ui.gobOverlay, nil)
  refuses("the hard cut is cut: gob:overlays() throws naming the collection",
          function() return me:overlays() end, "gob:overlay():list()")
  refuses("...and so does the old four-arity form, naming every collection verb that replaced it",
          function() return me:overlay(KEY, { text = "x" }) end, "gob:overlay():add(key)")
  check(type(me.overlay) == "function", "...and gob:overlay is what replaced them all", type(me.overlay))

  -- 2. THE COLLECTION -- it answers even with nothing of ours on the gob, and it is not an array.
  local before = me:overlay():list()
  check(type(before) == "table", "gob:overlay():list() answers every overlay on the gob", type(before))
  eq("...and this addon has attached none of them yet", (mine(me)), 0)
  refuses("the collection is an OBJECT, not a sequence: # is refused naming :count() and :list()",
          function() return #me:overlay() end, ":count()")

  -- 3. ATTACH, and CONFIGURE. :add hands back a BARE overlay; the setters say what it draws, and each one
  --    answers the overlay, so the whole thing is one statement.
  local bare = me:overlay():add(KEY)
  check(bare ~= nil, "gob:overlay():add(key) attaches and hands back the Overlay", tostring(bare))
  eq("...and a bare one draws NOTHING until it says what it is", bare:kind(), nil)
  check(bare:exists(), "...though it is already attached (a half-configured overlay is attached, not painted)",
        bare:exists())
  local ov = me:overlay():add(KEY):text(PARKED):color(255, 220, 120):offset(0, -6)
  check(ov ~= nil, "the setters chain and answer the overlay", tostring(ov))
  eq("...and a setter's bare read hands back what it was given", ov:text(), PARKED)
  eq("overlay:key() is the name you gave it", ov:key(), KEY)
  eq("overlay:native() is false on one of ours", ov:native(), false)
  eq("overlay:res() is nil for a screen-space overlay (it draws Lua, not a .res)", ov:res(), nil)
  check(ov:exists(), "overlay:exists() while it is attached", ov:exists())
  eq("overlay:count() is 1 for one of ours -- a key of yours names exactly one overlay", ov:count(), 1)
  check(ov:gob() == me, "overlay:gob() is the very Gob it hangs on (interned, so == holds)", tostring(ov:gob()))
  local info = ov:info()
  check(info and (info.key == KEY) and (info.native == false) and (info.kind == "text"),
        "overlay:info() is the snapshot: key, native, kind",
        info and ("key=%s native=%s kind=%s"):format(tostring(info.key), tostring(info.native),
                                                     tostring(info.kind)) or "nil")

  -- 4. READ ONE BACK, and it is the SAME object (interned per gob+key, D-045's shape).
  check(me:overlay():get(KEY) == ov, "gob:overlay():get(key) reads that one back, interned",
        tostring(me:overlay():get(KEY)))
  eq("gob:overlay():get(<a key nobody attached>) is plain nil", me:overlay():get("no-such-key"), nil)

  -- 5. IDEMPOTENCE. The same key twice REPLACES: one overlay, the second one's.
  me:overlay():add(KEY):draw(function(g, gob, sx, sy) g:frect(sx - 2, sy - 2, 4, 4) end)
  local n, keys = mine(me)
  eq("the same key twice leaves ONE overlay, not two", n, 1)
  eq("...and it is the SECOND one (an :add on a live key REPLACES)", me:overlay():get(KEY):info().kind, "draw")
  check(keys == KEY, "gob:overlay():list() lists our one key and nothing else of ours", keys)

  -- 6. REMOVE, and everything that pointed at it goes quiet.
  me:overlay():remove(KEY)
  eq("gob:overlay():remove(key) removes it", me:overlay():get(KEY), nil)
  eq("...the Overlay object you held stops existing with it", ov:exists(), false)
  eq("...and gob:overlay():list() no longer lists it", (mine(me)), 0)

  -- 7. THE REFUSALS. An overlay says ONE thing, and a silent no-op is the one failure nothing else would
  --    ever report.
  local two = me:overlay():add(KEY):text("a")
  refuses("a second, DIFFERENT kind is refused naming the first",
          function() two:draw(function() end) end, "already draws 'text'")
  eq("...and the refused setter left the overlay exactly as it was", two:text(), "a")
  refuses("...and 'draw' has to be a function",
          function() me:overlay():add(KEY):draw("paint it") end, "expects a function")
  me:overlay():remove(KEY)
  eq("...and the removal leaves nothing of ours attached", (mine(me)), 0)

  -- 8. THE GAME'S OWN. Read-only: an attach onto a native key, and a remove of one, both RAISE naming it.
  local ng, nk, nov = anyNative()
  if ng then
    eq("one of the game's own overlays reads native = true", nov:native(), true)
    eq("...and its key IS its resource name, which is what overlay:res() answers", nov:res(), nk)
    check(nov:exists(), "...and it exists while the game has it on that gob", nov:exists())
    check((nov:count() or 0) >= 1,
          "...and overlay:count() says how many engine overlays that one key stands for (it is a UNION)",
          nov:count())
    refuses("an :add onto a native key RAISES naming the key",
            function() ng:overlay():add(nk) end, nk)
    refuses("...and so does a :remove of one -- never a silent no-op",
            function() ng:overlay():remove(nk) end, nk)
  else
    manualCheck("no gob in sight carries one of the game's own overlays; stand near a lit fire, a growing"
                .. " crop or a curiosity and run ':t038-1' again",
                "the five native-side checks run and pass (they are skipped, not failed, here)")
  end

  -- 9. THE CENSUS, and the invariant it forced. A native overlay is keyed by its RESOURCE NAME, and the
  --    first run of this suite MEASURED what that costs: 13 of 33 gobs carrying overlays had two of one
  --    resource. So the collapse is ordinary, not a corner case -- and since a native overlay is read-only,
  --    a union loses nothing an addon could act on EXCEPT the multiplicity, which overlay:count() therefore
  --    publishes. gob:info().overlays is the RAW list (one entry per engine overlay) and gob:overlay():list()
  --    the keyed one, so the check is that the counts RECONCILE on every gob: sum(ov:count()) == #raw.
  local seen, dup, worst, bad = 0, 0, 0, 0
  for _, g in ipairs(hafen.world():gob():list()) do
    if seen >= CENSUS then break end
    local raw = g:info() and g:info().overlays
    raw = raw and #raw or 0
    if raw > 0 then
      seen = seen + 1
      local keyed, counted = 0, 0
      for _, o in ipairs(g:overlay():list()) do
        if o:native() then keyed = keyed + 1 counted = counted + (o:count() or 0) end
      end
      if raw > keyed then dup = dup + 1 if (raw - keyed) > worst then worst = raw - keyed end end
      if counted ~= raw then bad = bad + 1 end
    end
  end
  check(bad == 0,
        ("the game's overlays collapse by resource and the collapse is PUBLISHED: %d of %d gobs with overlays"
         .. " carry two of one resource (worst by %d), and overlay:count() reconciles with the raw list on"
         .. " every one of them"):format(dup, seen, worst),
        ("%d of %d gob(s) where sum(ov:count()) does not equal the raw overlay list"):format(bad, seen))
  check(dup > 0,
        ("...and the collapse is REAL rather than theoretical here -- %d of the %d gobs sampled carry two"
         .. " overlays of one resource, which is why count() exists"):format(dup, seen),
        "no gob in sight carries two overlays of one resource, so the reconciliation above proved nothing"
        .. " stronger than 1 == 1 (walk somewhere busier and re-run)")

  -- 10. grid:overlay() is a DIFFERENT collection and is untouched -- the map database records which tiles a claim
  --     covers, and that read is not this one. (Staged: the tags live in resources that may still be loading.)
  local gp = me:position() and me:position():info()
  hafen.timer():after(1.5, function()
    local grid = gp and hafen.map():grid():get(gp.gridId)
    local tags = grid and grid:overlay():list()
    check(type(tags) == "table",
          "grid:overlay() on the MAP database still answers -- a different collection, untouched by this cut",
          (grid == nil) and "no recorded grid under the player yet" or tostring(tags))

    -- 11. Park a label on the player's own gob for the two things only a person can judge.
    parked = me
    me:overlay():add(KEY):text(PARKED):color(255, 220, 120):offset(0, -6)
    manualCheck("look at your own character, then move the camera and walk a few steps",
                "'038.1 label' stands just above your head in pale yellow and STAYS there as the camera turns"
                .. " and as you walk -- it is attached to the gob, not painted at a screen coordinate")
    manualCheck("run ':lua hafen.player():gob():overlay():get(\"tag\")'",
                "nil -- keys are PER ADDON: the console is another addon, so it cannot see the \"tag\" this"
                .. " suite is holding on that very gob (':lua #hafen.player():gob():overlay():list()' counts the"
                .. " game's own overlays and nothing of ours). Then run ':t038-1 drop'")
    summary()
  end)
end

dropRound = function()
  pass, fail, manual = 0, 0, 0
  local gob = parked
  if gob then gob:overlay():remove(KEY) end
  parked = nil
  check(gob and (gob:overlay():get(KEY) == nil),
        "the parked label is gone and the suite left nothing attached to any gob",
        (gob == nil) and "nothing was parked -- run ':t038-1' first" or tostring(gob:overlay():get(KEY)))
  summary()
end

hafen.slash():register("t038-1", run)   -- the only way in: a suite does not start itself
