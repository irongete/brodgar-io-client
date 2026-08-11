-- 038.2 — the world-space family, and `follow` is absorbed. Self-checking suite; see specs/testing/addon-suite.md.
--
-- 038.1 put the SCREEN-space kinds on gob:overlay (:draw(fn), :text(s)). This task brings the other space home:
-- :image(asset), :model(asset) and :ghost(res) stand in the 3D WORLD anchored to the gob, with a three-number
-- :offset in world units (z = up). It is the SAME collection and the same key space -- one overlay, two spaces
-- -- so an overlay that names a second kind is refused naming the first, and one that names none is inert.
--
-- What that ABSORBS is `follow = gob`, which is now a HARD CUT from the sprite/object/ghost collections and
-- from the handles' :follow/:offset. The trade is not cosmetic: a follow= sprite is its own
-- client-only gob, and when its target despawned FollowMoving held it at the last position -- a sprite following
-- a felled tree floated there forever with no owner. An overlay is OWNED by a record on the gob, so the gob's
-- own death ends it.
--
-- READ-ONLY: it declares no permissions, attaches only to the player's own gob (the parked round aside, which
-- names the gobs it touched and removes what it attached), and never writes persistent state.

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

local KEY   = "world"                 -- this suite's one key on the player's gob
local PARK  = "038-2-park"            -- the key the despawn round uses on OTHER gobs
local GHOST = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)
local DX    = 22.0                    -- the world-unit x offset the offset check reads back

local icon, mesh                      -- our own asset handles (hafen.asset, loaded once at Load)
local parked = {}                     -- gob ids the ':t038-2 park' round put an overlay on
local gone   = {}                     -- of those, the ones the client has since dropped (GobRemoved)
local watching = false

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.glb")
end)

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- How many of THIS addon's overlays does gob:overlay():list() answer, and under which keys?
local function mine(gob)
  local n, keys = 0, {}
  for _, ov in ipairs(gob:overlay():list()) do
    if not ov:native() then n = n + 1 keys[#keys + 1] = ov:key() end
  end
  return n, table.concat(keys, ",")
end

local parkRound, goneRound

local function run(args)
  local sub = args and args[1]
  if sub == "park" then return parkRound() end
  if sub == "gone" then return goneRound() end
  pass, fail, manual = 0, 0, 0        -- a re-run reports its own counts, not the last one's

  local me = hafen.player() and hafen.player():gob()
  if (me == nil) or (not me:exists()) then
    check(false, "the player's own gob is the thing every check below attaches to", "no player gob yet")
    return summary()
  end
  me:overlay():remove(KEY)            -- a re-run starts from a bare gob
  if (icon == nil) or (mesh == nil) then
    check(false, "the suite's own assets (icon.png, tri.glb) loaded", "icon=" .. tostring(icon) .. " mesh=" .. tostring(mesh))
    return summary()
  end

  -- 1. THE HARD CUT, on all three doors, refused NAMING THE REPLACEMENT. A silently-ignored follow= would leave
  --    a sprite standing at 0,0 on the far side of the world with nothing to say why. The old constructors that
  --    carried the option are themselves gone now, so what each door refuses is the SPELLING, still naming the
  --    replacement -- and the replacement it names is this feature's own verb.
  refuses("hafen.render.sprite{follow=} is refused, saying it is GONE",
          function() return hafen.render.sprite end, "gob:overlay():add(key):image(asset)")
  refuses("...and hafen.render.object{follow=}",
          function() return hafen.render.object end, "gob:overlay():add(key):model(asset)")
  refuses("...and hafen.ghost.new{follow=}",
          function() return hafen.ghost.new end, "gob:overlay():add(key):ghost(res)")
  refuses("...and each of them NAMES the replacement, not just the removal",
          function() return hafen.render.sprite end, "hafen.render():sprite():add(imageAsset, p)")

  -- 2. The cut took ONLY the anchor: a FIXED sprite still builds, and its handle has no :follow/:offset left.
  local p = me:position()
  local fixed = hafen.render():sprite():add(icon, p:offset(6, 6)):scale(1)
  check(fixed ~= nil, "a FIXED world sprite still builds -- the cut took the anchor, not the namespace", tostring(fixed))
  check((fixed == nil) or ((fixed.follow == nil) and (fixed.offset == nil)),
        "...and the handle's :follow/:offset are gone, reading as plain nil",
        fixed and (tostring(fixed.follow) .. "/" .. tostring(fixed.offset)))
  if fixed then hafen.render():sprite():remove(fixed) end

  -- 3. THE THREE WORLD KINDS, on the one verb, each read back with its own type. {image} and {model} publish
  --    synchronously; {ghost} streams its .res in a beat later, but the RECORD is there at once (that is the
  --    point of a record: the addon never waits on the loader).
  local ov = me:overlay():add(KEY):image(icon):scale(2):offset(0, 0, 18)
  check(ov ~= nil, "gob:overlay():add(key):image(asset) attaches in the 3D world", tostring(ov))
  eq("...and it reads back as kind 'image'", ov and ov:kind(), "image")
  eq("...whose overlay:res() is the asset it draws", ov and ov:res(), "icon.png")
  eq("...and overlay:native() is false -- it is ours", ov and ov:native(), false)
  check(ov and ov:info() and (ov:info().world == true), "...and overlay:info().world says which space it is in",
        ov and ov:info() and tostring(ov:info().world))
  eq("...and every setter has a bare read: :scale() answers what :scale(2) was given", ov and ov:scale(), 2)

  local om = me:overlay():add(KEY):model(mesh):offset(0, 0, 10)
  eq("...:model(asset) attaches, and reads back as kind 'model'", om and om:kind(), "model")
  eq("...and the SAME KEY across two spaces still leaves ONE overlay", (mine(me)), 1)
  check(om == ov, "...the Overlay object is interned on the KEY, so the handle you held names the new record",
        tostring(om) .. " vs " .. tostring(ov))

  local og = me:overlay():add(KEY):ghost(GHOST):offset(0, 0, 10)
  eq("...:ghost(res) attaches, and reads back as kind 'ghost'", og and og:kind(), "ghost")
  eq("...whose overlay:res() is the .res name it will stream in", og and og:res(), GHOST)

  -- 4. THE COMPOSED VERBS. A world overlay carries the look and facing its entity already had, so absorbing
  --    follow= took nothing away -- and they CHAIN, because each answers the overlay.
  local sp = me:overlay():add(KEY):image(icon):scale(1):offset(0, 0, 18)
  local ok = pcall(function() sp:tint(255, 90, 90):alpha(0.7):scale(2):rotate(1.2) end)
  check(ok, "overlay:tint/:alpha/:scale/:rotate answer on the Overlay object and chain", ok)
  eq("...and :tint() reads back the colour value it was given", sp:tint() and sp:tint()[1], 255)
  eq("...while :tint(nil) is a documented nil: it CLEARS, and does not raise", sp:tint(nil):tint(), nil)
  refuses("...and on a SCREEN-space overlay they are refused naming the kinds",
          function() me:overlay():add("flat"):text("flat"):tint(1, 2, 3) end, "SCREEN-space")
  me:overlay():remove("flat")

  -- 5. THE OFFSET IS THE POSITION, and it is LIVE: setting it moves the thing where it stands, rather than
  --    re-attaching it. overlay:position() is where it actually is -- read as a DIFFERENCE, so the player's
  --    own position cancels out of both samples.
  local moved = me:overlay():add(KEY):image(icon):offset(0, 0, 18)
  local a = moved:position()
  local b = moved:offset(DX, 0, 18):position()
  check(a and b and (math.abs((b:x() - a:x()) - DX) < 1.0),
        ("overlay:position() follows the gob, and :offset shifts it in world units (+%d on x)"):format(DX),
        a and b and ("dx = " .. tostring(b:x() - a:x())) or "no position")
  local mp = moved:position()
  eq("...and :position() is a Position, so it offsets and saves like every other place in this API",
     mp and type(mp:durable()) or "no position", "boolean")

  -- 6. THE REFUSALS THAT ARE VOCABULARY. An overlay says ONE thing; a second kind is an error naming the
  --    first, because picking a winner is how one of them silently stops meaning anything.
  refuses("naming a SECOND kind is refused naming the first",
          function() me:overlay():add(KEY):text("hi"):image(icon) end, "already draws 'text'")
  eq("...and one that has named NO kind is inert rather than refused -- it is attached, and draws nothing",
     me:overlay():add(KEY):offset(0, 0, 1):kind(), nil)

  -- 7. AN OVERLAY IS NOT A FREE ENTITY. hafen.ghost() is the collection of the ghosts an addon PLACED; an
  --    overlay's ghost is reached through gob:overlay, and handing out a second handle with an ending on it
  --    would let an addon kill the visual behind a record that still reads as attached.
  local before = hafen.ghost():count()
  me:overlay():add(KEY):ghost(GHOST)
  eq("an overlay's ghost is never a member of hafen.ghost() -- one door, not two", hafen.ghost():count(), before)

  -- 8. REMOVE. The collection's own verb ends a world overlay exactly as it ends a screen one.
  me:overlay():remove(KEY)
  eq("gob:overlay():remove(key) removes a world overlay too", me:overlay():get(KEY), nil)
  eq("...and the suite leaves nothing of its own on the gob", (mine(me)), 0)

  -- 9. Park the one thing only a person can judge.
  me:overlay():add(KEY):image(icon):scale(2):offset(0, 0, 18)
  manualCheck("look at your character, walk a few steps and turn the camera; then run ':t038-2 drop'",
              "icon.png hangs in the WORLD about 1.5 tiles over your head (it grows/shrinks with the zoom,"
              .. " unlike 038.1's flat label) and travels with you -- then ':t038-2 drop' clears it")
  summary()
end

local function dropRound()
  pass, fail, manual = 0, 0, 0
  local me = hafen.player() and hafen.player():gob()
  if me then me:overlay():remove(KEY) end
  check(me and (me:overlay():get(KEY) == nil), "the parked world overlay is gone",
        (me == nil) and "no player gob" or tostring(me:overlay():get(KEY)))
  summary()
end

-- The despawn round (plan §2b): an overlay DIES WITH ITS GOB. Only a person can make a gob despawn, so this is
-- two commands with a walk between them -- ':t038-2 park' hangs an icon on the nearest few gobs and starts
-- watching GobRemoved, and ':t038-2 gone' reports which of them the client has since dropped.
parkRound = function()
  pass, fail, manual = 0, 0, 0
  if not watching then
    hafen.event():on("GobRemoved", function(g)      -- the payload IS the Gob object; after removal only :id() answers
      for _, id in ipairs(parked) do
        if g:id() == id then gone[#gone + 1] = id end
      end
    end)
    watching = true
  end
  local me = hafen.player() and hafen.player():gob()
  for _, id in ipairs(parked) do
    local g = hafen.world():gob():get(id)
    if g and g:exists() then g:overlay():remove(PARK) end
  end
  parked, gone = {}, {}
  if (me == nil) or (icon == nil) then
    check(false, "a player gob and icon.png are what the park round hangs", "not ready")
    return summary()
  end
  for _, g in ipairs(hafen.world():gob():list()) do
    if (#parked < 3) and (g:id() ~= me:id()) and g:exists() then
      local o = g:overlay():add(PARK):image(icon):scale(2):offset(0, 0, 14)
      if o then parked[#parked + 1] = g:id() end
    end
  end
  check(#parked > 0, ("an icon is hanging on %d nearby game object(s), and GobRemoved is being watched"):format(#parked),
        "no other gob in sight -- stand near some trees/animals and run ':t038-2 park' again")
  manualCheck("walk ~100 tiles away until those objects unload, walk back, then run ':t038-2 gone'",
              "NO icon is left floating where they were (that is the bug follow= had: FollowMoving held the"
              .. " sprite at the target's last position forever), and ':t038-2 gone' reports them despawned")
  summary()
end

goneRound = function()
  pass, fail, manual = 0, 0, 0
  local dead, alive = 0, 0
  for _, id in ipairs(parked) do
    local g = hafen.world():gob():get(id)
    if g:exists() then alive = alive + 1 g:overlay():remove(PARK) else dead = dead + 1 end
  end
  check(#parked > 0, ("the park round hung an icon on %d object(s)"):format(#parked),
        "nothing was parked -- run ':t038-2 park' first")
  if dead > 0 then
    check(#gone >= dead,
          ("%d of the %d parked objects despawned, and GobRemoved fired for each -- that event is where the"
           .. " overlay is destroyed, before the handler ever sees it"):format(dead, #parked),
          ("%d GobRemoved for %d despawned"):format(#gone, dead))
  else
    manualCheck("none of the parked objects has despawned yet -- walk further away and run ':t038-2 gone' again",
                "at least one of them despawns, and no icon is left floating where it stood")
  end
  check(alive == (#parked - dead), ("the %d still-loaded parked object(s) were cleaned up"):format(alive), alive)
  parked, gone = {}, {}
  summary()
end

hafen.slash():register("t038-2", function(args)
  if args and (args[1] == "drop") then return dropRound() end
  return run(args)
end)   -- the only way in: a suite does not start itself
