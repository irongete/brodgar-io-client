-- 044.2 -- both anchors, and the collection. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. hafen.vr():widget() takes the SAME two anchors the other three kinds take, because
-- 043.2 made the anchor an ARGUMENT rather than a second door. :add(w, p) stands a widget at a point and it
-- holds there, carrying :position(p, a) like any free entity. :add(w, gob) makes it FOLLOW that game object,
-- which is why its place is the gob's -- writing that place back is refused, :offset(x, y, z) is the verb
-- that means "where" there, and it dies with its gob while a free one is untouched. Both are ordinary
-- members of the collection (:list/:count/:find/:remove) and both are in hafen.vr():list() beside the
-- ghosts, the sprites and the objects.
--
-- AND THE TITLE-BAR DRAG IS INERT ON BOTH. A drag begins with the flat UI's hit test, so this is asserted as
-- the hit test itself: every point over each window resolves to it BEFORE it stands and to nothing of it
-- after, while the window is still alive, still in the tree and still answering its reads. Writing the
-- flat-UI place a drag would have written then moves nothing in the world, because a standing widget's place
-- is the entity's.
--
-- 044.1 REFUSED :add(w, gob), NAMING THE POINT. That refusal is retired here, so this suite asserts the new
-- behaviour itself rather than leaving the old spelling to another suite (TESTING.md).
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Only a person can walk, and only walking makes a gob move or despawn. So
-- "it travels with the object" and "it despawned with it" are the two [manual] lines, and the despawn one is
-- a second round (':t044-2 gone') that asserts the outcome rather than asking anyone to judge it, and clears
-- up after itself. ':t044-2 off' takes everything down at any time.
--
-- It re-asserts its own premises -- hafen.vr() and its collections (043.1), the anchor as an argument and a
-- Gob that is gone refused (043.2), standing taking a widget off the flat UI's hit test (044.1) -- because a
-- suite is read alone and must convince alone. Assets are addon-relative and sandboxed, so it ships its own
-- icon.png and tri.gltf rather than borrowing another addon's.
--
-- READ-ONLY: no permissions, no persistent state. It builds its own widgets, stands them, and removes them.

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
local function why(f)
  local ok, err = pcall(f)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function refuses(what, fn, ...)
  local err = why(fn) or "<no error>"
  local ok = true
  for _, want in ipairs({ ... }) do
    if err:find(want, 1, true) == nil then ok = false end
  end
  check(ok, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function near(a, b, tol) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) < (tol or 0.6)) end

local T = 11                              -- world units per tile
local RES = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local S                                   -- what a placing round left standing
local parked = {}                         -- { {id = <gob id>, ent = <anchored widget>, w = <its widget>}, ... }
local atRemoval = {}                      -- id -> what :exists() answered inside GobRemoved
local watching = false

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

local function counters() return hafen.client():profiling():surfaces() end

-- Is the widget the flat UI's hit test landed on `w` itself, or something inside it?
local function within(hit, w)
  for _ = 1, 16 do
    if hit == nil then return false end
    if hit == w then return true end
    hit = hit:parent()
  end
  return false
end

-- Fifteen points spread over a widget's SCREEN rectangle. Taken before it stands: standing re-homes the
-- widget, so its own :position() becomes surface-local and the rectangle would move to the screen corner.
local function rect(w)
  local p, s = w:position(), w:size()
  local pts = {}
  for _, fx in ipairs({ 0.15, 0.35, 0.5, 0.65, 0.85 }) do
    for _, fy in ipairs({ 0.10, 0.5, 0.88 }) do
      pts[#pts + 1] = { math.floor(p.x + (s.x * fx)), math.floor(p.y + (s.y * fy)) }
    end
  end
  return pts
end

-- How many of those points the flat UI resolves to that widget right now.
local function reachable(pts, w)
  local n = 0
  for _, q in ipairs(pts) do
    if within(hafen.ui():at(q[1], q[2]), w) then n = n + 1 end
  end
  return n
end

-- Is handle `h` one of the members `coll:list()` answers?
local function listed(coll, h)
  for _, e in ipairs(coll:list()) do
    if e == h then return true end
  end
  return false
end

-- Take down everything this suite stood, and destroy every widget it built.
local function clear()
  for _, c in ipairs({ hafen.vr():widget(), hafen.vr():ghost(), hafen.vr():sprite(), hafen.vr():object() }) do
    for _, e in ipairs(c:list()) do c:remove(e) end
  end
  for _, r in ipairs(parked) do
    if r.w and r.w:exists() then r.w:destroy() end
  end
  if S then
    for _, k in ipairs({ "free", "rider" }) do
      local w = S[k]
      if w and w:exists() then w:destroy() end
    end
  end
  parked, atRemoval, S = {}, {}, nil
end

local goneRound, offRound

local function run(args)
  local arg1 = args and args[1]
  if arg1 == "gone" then return goneRound() end
  if arg1 == "off" then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists() and icon and mesh) then
    check(false, "the suite is in the world with its own assets loaded (it stands widgets in the 3D scene)",
          ("player=%s icon=%s mesh=%s"):format(tostring(okp and me), tostring(icon), tostring(mesh)))
    return summary()
  end
  clear()                                  -- a re-run starts from an empty scene

  -- ---- 0. the premises this task's claims rest on (043.1, 043.2) ---------------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():widget() == hafen.vr():widget())
        and ((why(function() return hafen.ghost end) or ""):find("hafen.vr()", 1, true) ~= nil),
        "the premise holds: hafen.vr() and its widget collection are each ONE object, and the retired"
        .. " hafen.ghost still raises naming hafen.vr()",
        (why(function() return hafen.ghost end) or "<no error>"))

  local p = me:position()
  local spare = hafen.ui():widget():size(40, 20)

  refuses("a first argument that is not a Widget is refused, naming the builders that make one",
          function() hafen.vr():widget():add("a panel", p) end,
          "hafen.ui():window()", "hafen.ui():widget()", "string")
  refuses("...and the anchor is a Position OR a Gob, anything else refused naming BOTH forms (043.2)",
          function() hafen.vr():widget():add(spare, { x = 1, y = 2 }) end, "Position", "Gob")
  refuses("...and a Gob that has already left the object cache is refused rather than anchored to nothing",
          function() hafen.vr():widget():add(spare, hafen.world():gob():get(-1)) end, "that gob is gone")
  spare:destroy()

  -- ---- 1. FREE: :add(w, p) stands at the point it was given, and carries :position(p, a) ----------------
  local free = hafen.ui():window():title("044.2 free"):size(160, 90):position(360, 250)
  S = { free = free, freeRect = rect(free) }
  S.freeSeen = reachable(S.freeRect, free)

  local fe = hafen.vr():widget():add(free, p:offset(2 * T, 0))
  S.fe = fe
  local q = fe:position()
  check(fe:exists() and (fe:widget() == free) and (q ~= nil)
        and near(q:x(), p:x() + (2 * T)) and near(q:y(), p:y()),
        ":add(w, p) stands the widget at the point it was given and holds it there, handing back the entity"
        .. " that owns it",
        q and ("%.1f,%.1f (want %.1f,%.1f) widget=%s"):format(q:x(), q:y(), p:x() + (2 * T), p:y(),
                                                              tostring(fe:widget() == free)))

  local p2 = p:offset(2 * T, 2 * T)
  fe:position(p2, 1.0)
  local r = fe:position()
  check((r ~= nil) and near(r:x(), p2:x()) and near(r:y(), p2:y()) and near(fe:rotate(), 1.0, 0.01),
        "...and a free one carries :position(p, a): it moves where it is told, and keeps the facing that came"
        .. " with the move",
        r and ("%.1f,%.1f a=%.2f"):format(r:x(), r:y(), fe:rotate()))
  refuses("...while :offset() is refused on a free one -- an offset from nothing is not a place",
          function() fe:offset(0, 0, 4) end, "stands where it was put", "position")

  -- ---- 2. ANCHORED: :add(w, gob), which 044.1 refused --------------------------------------------------
  local rider = hafen.ui():window():title("044.2 rider"):size(150, 90):position(110, 250)
  S.rider = rider
  S.riderRect = rect(rider)
  S.riderSeen = reachable(S.riderRect, rider)

  local ae = hafen.vr():widget():add(rider, me)
  S.ae = ae
  check(ae:exists() and (ae:widget() == rider) and (hafen.vr():widget():count() == 2),
        ":add(w, gob) stands a widget ON a game object -- 044.1's refusal is retired, and ONE collection now"
        .. " takes both anchors, exactly as it does for a ghost, a sprite and an object",
        ("exists=%s widget=%s n=%d"):format(tostring(ae:exists()), tostring(ae:widget() == rider),
                                            hafen.vr():widget():count()))

  local mp, rp = me:position(), ae:position()
  check(rp and near(rp:x(), mp:x(), 11.0) and near(rp:y(), mp:y(), 11.0),
        "...and it was handed no point at all, so the place it reports can only have come from its gob"
        .. " (within a tile)",
        rp and ("%.1f,%.1f (gob at %.1f,%.1f)"):format(rp:x(), rp:y(), mp:x(), mp:y()))
  refuses("writing :position(p) on an anchored widget is refused -- the follow would undo it next frame",
          function() ae:position(p) end, "follows a gob", "offset")
  ae:offset(0, 0, 14)
  local off = ae:offset()
  check((off ~= nil) and near(off.z, 14, 0.01),
        "...while :offset(x, y, z) IS the verb that means where an anchored one sits, and it reads back what"
        .. " it wrote", off and off.z)

  -- ---- 3. the collection answers over both anchors -----------------------------------------------------
  local l = hafen.vr():widget():list()
  check((#l == 2) and (l[1] == fe) and (l[2] == ae)
        and (hafen.vr():widget():find("rider") == ae)
        and (hafen.vr():widget():count(function(e) return e == fe end) == 1),
        ":list() holds both anchors in the order they were stood, :find(text) names one by the caption of the"
        .. " widget standing, and :count(filter) counts",
        ("n=%d find=%s count=%d"):format(#l, tostring(hafen.vr():widget():find("rider") == ae),
                                         hafen.vr():widget():count(function(e) return e == fe end)))

  local tmp = hafen.ui():window():title("044.2 tmp"):size(120, 70):position(620, 250)
  local tRect = rect(tmp)
  local tSeen = reachable(tRect, tmp)
  local te = hafen.vr():widget():add(tmp, p:offset(0, 2 * T))
  local tStood = reachable(tRect, tmp)
  hafen.vr():widget():remove(te)
  check((tSeen > 0) and (tStood == 0) and (reachable(tRect, tmp) == tSeen) and (not te:exists())
        and (hafen.vr():widget():count() == 2),
        ":remove(x) ends one and puts its widget back exactly where it stood from -- the flat UI finds it"
        .. " again at every point it found it at before",
        ("before=%d standing=%d after=%d exists=%s n=%d"):format(tSeen, tStood, reachable(tRect, tmp),
                                                                 tostring(te:exists()),
                                                                 hafen.vr():widget():count()))
  tmp:destroy()

  -- ---- 4. the title-bar drag is inert on BOTH anchors --------------------------------------------------
  check((S.freeSeen > 0) and (S.riderSeen > 0)
        and (reachable(S.freeRect, free) == 0) and (reachable(S.riderRect, rider) == 0),
        "a title-bar drag is inert on both anchors: the flat UI's hit test resolved to each window over its"
        .. " whole rectangle before it stood and finds nothing of it anywhere after -- and that lookup is"
        .. " where the gesture begins",
        ("free %d->%d, rider %d->%d of 15 points"):format(S.freeSeen, reachable(S.freeRect, free),
                                                          S.riderSeen, reachable(S.riderRect, rider)))
  check(free:exists() and rider:exists() and (free:size().x > 0) and (rider:size().x > 0),
        "...and neither window was destroyed or emptied to achieve it: both are alive, still in the tree and"
        .. " still answering their reads",
        ("free=%s rider=%s"):format(tostring(free:exists()), tostring(rider:exists())))

  local was = fe:position()
  free:position(20, 20)                    -- exactly what a title-bar drag writes, written by hand
  local now = fe:position()
  free:position(0, 0)                      -- ...and back to the surface origin, so it is drawn whole
  check((was ~= nil) and (now ~= nil) and near(was:x(), now:x()) and near(was:y(), now:y()),
        "...and writing the flat-UI place a drag would have written moves it nowhere in the world: a standing"
        .. " widget's place is the entity's, not the widget's",
        (was and now) and ("%.1f,%.1f (was %.1f,%.1f)"):format(now:x(), now:y(), was:x(), was:y()))

  -- ---- 5. the fourth kind is a kind: hafen.vr():list() and the read at the gob -------------------------
  local gh = hafen.vr():ghost():add(RES, p:offset(-3 * T, 0))
  local sp = hafen.vr():sprite():add(icon, p:offset(-3 * T, 2 * T))
  local ob = hafen.vr():object():add(mesh, p:offset(-3 * T, -2 * T))
  S.gh, S.sp, S.ob = gh, sp, ob
  local all = hafen.vr():list()
  check((#all == 5) and (all[1] == fe) and (all[2] == ae) and (all[3] == gh) and (all[4] == sp)
        and (all[5] == ob),
        "hafen.vr():list() is everything standing across FOUR kinds now -- the two standing widgets among the"
        .. " ghost, the sprite and the object, in the order they were stood",
        ("n=%d"):format(#all))

  local mine
  for _, ov in ipairs(me:overlay():list()) do
    if ov:kind() == "widget" then mine = ov end
  end
  check(mine ~= nil,
        "an anchored standing widget is listed at its gob READ-ONLY, as every anchored entity is (043.3), and"
        .. " its kind reads \"widget\"", "no vr entry of kind \"widget\" at the player's gob")
  refuses("...and ending it THERE is refused, naming the collection that placed it",
          function() me:overlay():remove(mine) end, "hafen.vr():widget():remove")

  -- ---- 6. park what only a walk can prove --------------------------------------------------------------
  if not watching then
    hafen.event():on("GobRemoved", function(g)     -- the payload IS the Gob; after removal only :id() answers
      for _, r in ipairs(parked) do
        if g:id() == r.id then atRemoval[r.id] = r.ent:exists() end
      end
    end)
    watching = true
  end
  for _, g in ipairs(hafen.world():gob():list()) do
    if (#parked < 2) and (g:id() ~= me:id()) and g:exists() then
      local w = hafen.ui():widget():size(72, 26)
      hafen.ui():label():text("#" .. tostring(g:id())):parent(w):position(4, 4)
      local ok, e = pcall(function() return hafen.vr():widget():add(w, g) end)
      if ok and e then                       -- a gob that left between the list and the add is simply skipped
        parked[#parked + 1] = { id = g:id(), ent = e, w = w }
      else
        w:destroy()
      end
    end
  end
  check(#parked > 0,
        ("a small panel is standing on %d nearby game object(s); the free window is the control a despawn must"
         .. " not touch"):format(#parked),
        "no other gob in sight -- stand near some trees/animals and run ':t044-2' again")

  manualCheck("walk ten paces and turn the camera",
              "the 'rider' window travels WITH YOU (it is anchored to your gob, floating about a tile above"
              .. " it), while the 'free' window holds a couple of tiles from where you started and does not"
              .. " move -- one collection, two anchors")
  manualCheck("walk ~100 tiles away until those nearby objects unload, walk back, then run ':t044-2 gone'"
              .. " (or ':t044-2 off' at any time to take it all down)",
              "no panel is left floating where those objects stood, and ':t044-2 gone' reports each one gone"
              .. " with its gob while the free window is still standing")
  summary()
end

-- THE DESPAWN ROUND -- an anchored widget dies with its gob (D-102), a free one is untouched. Only a person
-- can make a gob despawn, so this is a second command after a walk, and what it reports is asserted.
goneRound = function()
  pass, fail, manual = 0, 0, 0
  if (S == nil) or (#parked == 0) then
    check(false, "':t044-2' has been run first, and parked a panel on some nearby objects",
          "nothing parked -- run ':t044-2' first")
    return summary()
  end
  -- What counts as despawned is the GobRemoved the client RAISED while we were away, recorded as it happened
  -- -- not whether the object is in the cache now. Walk back and the server sends the same object again under
  -- the SAME id, so a snapshot taken here reads "never left" for a gob whose panel this task correctly ended.
  local dead, alive, wrong, late = 0, 0, {}, {}
  for _, r in ipairs(parked) do
    if atRemoval[r.id] ~= nil then
      dead = dead + 1
      if r.ent:exists() then wrong[#wrong + 1] = ("#%d despawned but kept its panel"):format(r.id) end
      if atRemoval[r.id] ~= false then late[#late + 1] = tostring(r.id) end
    else
      alive = alive + 1
      if not r.ent:exists() then wrong[#wrong + 1] = ("#%d never left but lost its panel"):format(r.id) end
    end
  end
  if dead == 0 then
    check(#wrong == 0,
          ("no GobRemoved has fired for either of the %d parked objects yet, and every panel is still standing"
           .. " on one"):format(#parked), table.concat(wrong, "; "))
    manualCheck("walk further away (or another way) until those objects unload, then run ':t044-2 gone' again",
                "at least one of them unloads, and no panel is left floating where it stood")
    return summary()
  end
  check(#wrong == 0,
        ("%d of the %d anchored panels died with their gob (%d never left, and still carry theirs)")
          :format(dead, #parked, alive), table.concat(wrong, "; "))
  check(#late == 0,
        "...and each was already gone INSIDE the GobRemoved handler -- the end rides the event the client"
        .. " already raises, before it reaches Lua",
        "still alive at GobRemoved for gob(s): " .. table.concat(late, ","))
  check(S.fe and S.fe:exists() and listed(hafen.vr():widget(), S.fe) and S.ae and S.ae:exists(),
        "...while the FREE window is untouched -- it was derived from nothing, so nothing but its collection"
        .. " ends it -- and the one riding YOUR gob is still standing too",
        ("free=%s rider=%s"):format(tostring(S.fe and S.fe:exists()), tostring(S.ae and S.ae:exists())))
  return offRound(true)
end

-- THE TAKE-DOWN -- everything goes back, and nothing of this suite is left anywhere.
offRound = function(keep)
  if not keep then pass, fail, manual = 0, 0, 0 end
  if S == nil then
    check(false, "':t044-2' has been run first -- this round takes down what THAT one stood",
          "nothing of this suite is standing")
    return summary()
  end
  local free, rider = S.free, S.rider
  local seen = { free = S.freeSeen or -1, rider = S.riderSeen or -1 }
  local ents, missing = {}, 0
  for _, k in ipairs({ "fe", "ae", "gh", "sp", "ob" }) do            -- built without holes: nil is a MISS
    local e = S[k]
    if e == nil then missing = missing + 1 else ents[#ents + 1] = e end
  end

  clear()

  local gone = 0
  for _, e in ipairs(ents) do
    if not e:exists() then gone = gone + 1 end
  end
  check((missing == 0) and (gone == #ents) and (hafen.vr():widget():count() == 0) and (#hafen.vr():list() == 0)
        and (counters().live == 0),
        "the suite leaves nothing standing: every entity reports exists() false, the widget collection is"
        .. " empty, the whole hafen.vr() section is empty, and no surface is live",
        ("missing=%d gone=%d/%d widgets=%d section=%d live=%s"):format(missing, gone, #ents,
                                                                       hafen.vr():widget():count(),
                                                                       #hafen.vr():list(),
                                                                       tostring(counters().live)))
  check(((free == nil) or (not free:exists())) and ((rider == nil) or (not rider:exists())),
        "...and it destroys the windows it built, leaving the flat UI as it found it",
        ("free=%s rider=%s"):format(tostring(free and free:exists()), tostring(rider and rider:exists())))
  check((seen.free > 0) and (seen.rider > 0),
        "...and the hit-test probes this suite's drag claim rests on were real: each window WAS reachable"
        .. " over its own rectangle before it stood",
        ("free=%d rider=%d of 15"):format(seen.free, seen.rider))
  summary()
end

hafen.slash():register("t044-2", run)   -- the only way in: a suite does not start itself
