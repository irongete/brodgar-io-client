-- 043.2 -- the anchor becomes an argument. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/043-vr-namespace/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. hafen.vr():<kind>():add(what, p) stands a thing at a point and it holds there;
-- hafen.vr():<kind>():add(what, gob) makes the same thing FOLLOW that game object. One collection, one
-- verb, two anchors -- not a second door. Anything that is neither a Position nor a Gob is refused naming
-- BOTH forms. An anchored entity's :position() is the gob's live point (it was never given one), writing
-- that position back is refused (a FollowMoving would undo it next frame), and the entity DIES WITH ITS
-- GOB -- riding the GobRemoved the client already raises, D-102 -- while a free one is untouched. Both
-- kinds of anchor are ordinary members of the collection's :list().
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Only a person can walk, and only walking makes a gob move or despawn.
-- So "it follows you" and "it despawned with the tree" are the two [manual] lines, and the despawn one is
-- a second round (':t043-2 gone') that then asserts the outcome rather than asking anyone to judge it.
-- What IS automated is the part a walk cannot fake: an anchored entity has a place it was never given and
-- it equals its gob's, and the moment the gob goes it reads as gone BEFORE GobRemoved reaches Lua.
--
-- It re-asserts its own premise -- the hafen.vr() section and its three collections (043.1) -- because a
-- suite is read alone and must convince alone. Assets are addon-relative and sandboxed, so it ships its
-- own icon.png and tri.gltf rather than borrowing another addon's.
--
-- READ-ONLY: no permissions, no persistent state. It attaches only to the player's own gob and to a few
-- nearby objects, and every round removes what it placed.

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

local function near(a, b, tol) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) < (tol or 0.5)) end

local RES = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local followMe                            -- the sprite riding the player, for the "walk" [manual] line
local control                             -- a FREE ghost: the thing a despawn must NOT touch
local parked = {}                         -- { {id = <gob id>, ent = <anchored sprite>}, ... }
local atRemoval = {}                       -- id -> what :exists() answered inside GobRemoved
local watching = false

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

-- Remove every entity this suite is holding, whatever round placed it.
local function clear()
  for _, e in ipairs(hafen.vr():sprite():list()) do hafen.vr():sprite():remove(e) end
  for _, e in ipairs(hafen.vr():ghost():list())  do hafen.vr():ghost():remove(e)  end
  for _, e in ipairs(hafen.vr():object():list()) do hafen.vr():object():remove(e) end
  followMe, control, parked, atRemoval = nil, nil, {}, {}
end

-- Is handle `h` one of the members `coll:list()` answers?
local function listed(coll, h)
  for _, e in ipairs(coll:list()) do
    if e == h then return true end
  end
  return false
end

local goneRound

local function run(args)
  if args and (args[1] == "gone") then return goneRound() end
  pass, fail, manual = 0, 0, 0          -- a re-run reports its own counts, not the last one's

  -- ---- the premise: the section this task adds an argument to (043.1) ---------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():sprite() == hafen.vr():sprite())
        and (why(function() return hafen.ghost end) or ""):find("hafen.vr():ghost()", 1, true) ~= nil
        and (why(function() return hafen.render end) or ""):find("hafen.vr()", 1, true) ~= nil,
        "the premise holds: hafen.vr() and its collections are one object each, and hafen.ghost/hafen.render"
        .. " still raise naming hafen.vr()",
        (why(function() return hafen.ghost end) or "<no error>"))

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists() and icon and mesh) then
    check(false, "the suite is in the world with its own assets loaded (it places into the 3D scene)",
          ("player=%s icon=%s mesh=%s"):format(tostring(okp and me), tostring(icon), tostring(mesh)))
    return summary()
  end
  clear()                                -- a re-run starts from an empty scene

  local p = me:position()

  -- ---- 1. ONE verb, TWO anchors -- on every collection --------------------------------------------------
  local fixed  = hafen.vr():sprite():add(icon, p:offset(8, 0))
  local fSprite = hafen.vr():sprite():add(icon, me)
  local fGhost  = hafen.vr():ghost():add(RES, me)
  local fObject = hafen.vr():object():add(mesh, me)
  check(fixed:exists() and fSprite:exists() and fGhost:exists() and fObject:exists(),
        "hafen.vr():sprite/ghost/object():add(what, gob) each place a thing anchored to a game object,"
        .. " exactly as :add(what, p) places one at a point",
        ("%s/%s/%s/%s"):format(tostring(fixed:exists()), tostring(fSprite:exists()),
                               tostring(fGhost:exists()), tostring(fObject:exists())))

  -- ---- 2. a place it WAS given, and a place it was NEVER given ------------------------------------------
  local q = fixed:position()
  check(near(q:x(), p:x() + 8) and near(q:y(), p:y()),
        "…:add(what, p) stands it at the point it was given, and holds it there",
        q and ("%.1f,%.1f (want %.1f,%.1f)"):format(q:x(), q:y(), p:x() + 8, p:y()))

  -- An anchored :add is handed NO point at all, so any place it reports can only have come from the gob.
  -- Read within a tile, because the entity's is the live interpolated point and the gob's is its server rc.
  local mp = me:position()
  local bad = {}
  for _, e in ipairs({ { "sprite", fSprite }, { "ghost", fGhost }, { "object", fObject } }) do
    local r = e[2]:position()
    if not (r and near(r:x(), mp:x(), 11.0) and near(r:y(), mp:y(), 11.0)) then
      bad[#bad + 1] = e[1] .. "@" .. (r and ("%.1f,%.1f"):format(r:x(), r:y()) or "nil")
    end
  end
  check(#bad == 0,
        "…while an ANCHORED one was given no point at all, and :position() answers its gob's own (within a tile)",
        ("gob at %.1f,%.1f; off: %s"):format(mp:x(), mp:y(), table.concat(bad, " ")))

  -- ---- 3. the anchor is a Position OR a Gob, and nothing else -------------------------------------------
  refuses("anything that is neither is refused naming BOTH accepted forms",
          function() return hafen.vr():sprite():add(icon, { x = 1, y = 2 }) end, "Position", "Gob")
  refuses("…and a Gob that has already left the object cache is refused rather than anchored to nothing",
          function() return hafen.vr():sprite():add(icon, hafen.world():gob():get(-1)) end, "that gob is gone")

  -- ---- 4. the place of an anchored thing is its gob's, so writing it back is refused --------------------
  refuses("writing :position(p) on an anchored entity is refused -- the follow would undo it next frame",
          function() fSprite:position(p) end, "follows a gob", "rotate")
  check(fSprite:rotate(1.5) and near(fSprite:rotate(), 1.5),
        "…while its OWN facing still writes: an anchored thing keeps :rotate(a), only the point is the gob's",
        fSprite:rotate())

  -- ---- 5. both kinds of anchor are ordinary members of the collection -----------------------------------
  check((hafen.vr():sprite():count() == 2) and listed(hafen.vr():sprite(), fixed)
        and listed(hafen.vr():sprite(), fSprite)
        and (hafen.vr():ghost():count() == 1) and (hafen.vr():object():count() == 1),
        "the collection's :list() holds both kinds of anchor -- a follower is a member like any other",
        ("sprites=%d ghosts=%d objects=%d"):format(hafen.vr():sprite():count(), hafen.vr():ghost():count(),
                                                   hafen.vr():object():count()))

  hafen.vr():object():remove(fObject)
  check((not fObject:exists()) and (hafen.vr():object():count() == 0),
        "…and the collection that placed an anchored one still ends it on demand",
        hafen.vr():object():count())

  -- ---- 6. park what only a walk can prove ---------------------------------------------------------------
  hafen.vr():sprite():remove(fixed)
  hafen.vr():ghost():remove(fGhost)
  followMe = fSprite:scale(2):rotate(0)                 -- big enough to see riding the player
  control  = hafen.vr():ghost():add(RES, p:offset(12, 0))   -- FREE: the despawn must not touch it

  if not watching then
    hafen.event():on("GobRemoved", function(g)          -- the payload IS the Gob; after removal only :id() answers
      for _, r in ipairs(parked) do
        if g:id() == r.id then atRemoval[r.id] = r.ent:exists() end
      end
    end)
    watching = true
  end
  for _, g in ipairs(hafen.world():gob():list()) do
    if (#parked < 3) and (g:id() ~= me:id()) and g:exists() then
      parked[#parked + 1] = { id = g:id(), ent = hafen.vr():sprite():add(icon, g):scale(2) }
    end
  end
  check(#parked > 0,
        ("an icon is riding %d nearby game object(s), and a FREE cabin stands 12 paces east as the control")
          :format(#parked),
        "no other gob in sight -- stand near some trees/animals and run ':t043-2' again")

  manualCheck("walk ten paces and turn the camera",
              "an icon.png quad ~2 tiles tall stands ON your character and TRAVELS WITH YOU (it is anchored to"
              .. " your gob), while the log cabin 12 paces east stays exactly where it was put -- one"
              .. " collection, two anchors")
  manualCheck("walk ~100 tiles away until those nearby objects unload, walk back, then run ':t043-2 gone'",
              "NO icon is left floating where they stood, and ':t043-2 gone' reports each one gone with its"
              .. " gob while the free cabin is still there")
  summary()
end

-- The despawn round (D-102, generalized from the overlay to the free anchor): an anchored entity dies with
-- its gob. Only a person can make a gob despawn, so this is a second command after a walk -- and what it
-- reports is asserted, not eyeballed.
goneRound = function()
  pass, fail, manual = 0, 0, 0
  if #parked == 0 then
    check(false, "the first round parked an icon on some nearby objects", "nothing parked -- run ':t043-2' first")
    return summary()
  end
  -- What counts as despawned is the GobRemoved the client RAISED while we were away, recorded as it happened
  -- -- not whether the object is in the cache now. Walk back and the server sends the same object again under
  -- the SAME id, so a snapshot taken here reads "never left" for a gob whose icon this task correctly ended.
  local dead, alive, wrong, late = 0, 0, {}, {}
  for _, r in ipairs(parked) do
    if atRemoval[r.id] ~= nil then                     -- GobRemoved fired for it at some point
      dead = dead + 1
      if r.ent:exists() then wrong[#wrong + 1] = ("#%d despawned but kept its icon"):format(r.id) end
      if atRemoval[r.id] ~= false then late[#late + 1] = tostring(r.id) end
    else
      alive = alive + 1
      if not r.ent:exists() then wrong[#wrong + 1] = ("#%d never left but lost its icon"):format(r.id) end
    end
  end
  if dead == 0 then
    check(#wrong == 0,
          ("no GobRemoved has fired for any of the %d parked objects yet, and all %d icons are still riding them")
            :format(#parked, #parked),
          table.concat(wrong, "; "))
    manualCheck("walk further away (or another way) until those objects unload, then run ':t043-2 gone' again",
                "at least one of them unloads, and no icon is left floating where it stood")
    return summary()
  end
  check(#wrong == 0,
        ("%d of the %d anchored icons died with their gob (%d never left, and still carry theirs)")
          :format(dead, #parked, alive),
        table.concat(wrong, "; "))
  check(#late == 0,
        "…and each was already gone INSIDE the GobRemoved handler -- the end rides the event the client"
        .. " already raises, before it reaches Lua",
        "still alive at GobRemoved for gob(s): " .. table.concat(late, ","))
  check(control and control:exists() and listed(hafen.vr():ghost(), control),
        "…while the FREE cabin is untouched: it was derived from nothing, so nothing but its collection ends it",
        control and tostring(control:exists()))
  check(hafen.vr():sprite():count() == (alive + 1),
        "…and the collection lost exactly the dead ones -- the follower on the player is still a member",
        ("sprites=%d, want %d"):format(hafen.vr():sprite():count(), alive + 1))
  clear()
  check((hafen.vr():sprite():count() == 0) and (hafen.vr():ghost():count() == 0),
        "the suite leaves nothing of its own standing in the world",
        ("sprites=%d ghosts=%d"):format(hafen.vr():sprite():count(), hafen.vr():ghost():count()))
  summary()
end

hafen.slash():register("t043-2", run)   -- the only way in: a suite does not start itself
