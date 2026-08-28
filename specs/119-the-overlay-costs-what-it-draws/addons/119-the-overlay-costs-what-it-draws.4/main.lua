-- 119.4 -- a mark costs the cuts it covers. Self-checking suite.
--
-- `overlayMeshes` counts every call `MCache.Grid.getolcut` makes to `makeol`, whether that call lays a mesh
-- or answers null for a cut the mask never touches. Until this task the raster asked for EVERY cut of the
-- drawn terrain -- 25 of them, per overlay, per frame -- so laying one patch moved this counter by 25 no
-- matter that the patch covers one or two. Now `MapView.Overlay.skipcut` asks `MCache.olreaches` first, and
-- only the cuts the mask can reach are asked for at all.
--
-- So the claim is a number against a number: the delta of one freshly laid patch, against the cuts that
-- patch's OWN ring can reach. The bound is worked out here from the ring the suite passes, the way the
-- client works it out -- `PatchOverlay.coverage` marks the ring's bounding box in tiles, one tile proud
-- each way, and `skipcut` tests each cut's 25x25 tiles with a one-tile margin against that. It comes to a
-- handful where the drawn terrain has 25, and the suite says so on its own line so the comparison is not
-- taken on trust.
--
-- The second half is a patch laid FAR outside the drawn terrain, which must build nothing whatever -- and
-- must still build nothing three seconds later, since a cut skipped now and asked for on a later tick
-- would be the same cost arriving late.
--
-- A MARK SKIPPED WHERE IT SHOULD BE DRAWN is the one way this goes wrong, and it would not show in a
-- counter at all -- a cut never asked for costs nothing and draws nothing. So the near patch is put back
-- to `:exists()`, `:drawn()` and a `:tint`, which is the surface saying it is still on the ground.
--
-- Nothing here is protected: reading the counters and laying a patch are both unprotected verbs, so this
-- suite declares no permission keys and every protected verb must still refuse it.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- The drawn terrain is `view = 2` cuts each way around the player's own cut: a 5x5 square of them. That is
-- what every overlay used to pay for, and it is what the bound below must come in under to mean anything.
local DRAWN_CUTS = 25

local prof, patches, me, start, near, far, bound
local restless = false   -- did a settle window run out with the counter still moving?

local function read()
  return prof:render().overlayMeshes
end

-- How many cuts this ring's mask can reach, in one axis. `lo`/`hi` are the ring's own tile bounds; the
-- coverage the client marks is [lo-1, hi+2) and a cut's tested area is [25c-1, 25c+26), so a cut is asked
-- for exactly where those two overlap.
local function axisCuts(lo, hi)
  local n = 0
  for c = math.floor((lo - 27) / 25) - 1, math.floor((hi + 3) / 25) + 1 do
    if ((25 * c + 26) > (lo - 1)) and ((hi + 2) > (25 * c - 1)) then n = n + 1 end
  end
  return n
end

local function cutspan(ring)
  local minx, maxx, miny, maxy
  for _, p in ipairs(ring) do
    local tc = p:tileCoord()
    if not tc then return nil end
    minx = math.min(minx or tc.x, tc.x); maxx = math.max(maxx or tc.x, tc.x)
    miny = math.min(miny or tc.y, tc.y); maxy = math.max(maxy or tc.y, tc.y)
  end
  return axisCuts(minx, maxx) * axisCuts(miny, maxy)
end

local function square(centre, r)
  return { centre:offset(-r, -r), centre:offset(r, -r), centre:offset(r, r), centre:offset(-r, r) }
end

-- Wait until the counter stops moving -- three equal readings a quarter-second apart -- then hand back what
-- it settled at. A scene still streaming in builds cuts of its own, and every overlay over ground whose
-- mesh was just rebuilt is re-laid with it; there is nothing to subtract until that has stopped. A window
-- that runs out with the number still climbing is recorded rather than used, so the log says so.
local function settle(done)
  local last, stable, tries = -1, 0, 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local m = read()
    if m == last then stable = stable + 1 else stable = 0 end
    last = m
    if (stable >= 2) or (tries >= 40) then
      tm:cancel()
      if stable < 2 then restless = true end
      done(m)
    end
  end)
end

local function later(n, done)
  local tries = 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    if tries >= n then tm:cancel(); done(read()) end
  end)
end

local step2, step3, step4
local base_m, far_m, farerr

local function note()
  return restless and " (the counter never went quiet -- was the scene loading?)" or ""
end

-- the quiet baseline, with nothing of ours down
function step2(m)
  base_m = m
  near = patches:add(square(start, 6), start)
  settle(step3)
end

-- ...and what one patch under the character cost
function step3(m)
  local delta = m - base_m
  check((delta >= 1) and (delta <= bound),
        "laying one patch built cut overlay meshes, and only for the cuts its own mask reaches",
        "moved " .. delta .. ", where its ring reaches " .. bound .. " cut(s) and the drawn terrain is "
          .. DRAWN_CUTS .. note())
  check(near:drawn(), "the patch it laid is on the terrain being drawn", near:drawn())
  check(near:exists(), "...and the collection still holds it", near:exists())
  local tinted = pcall(function() near:tint{40, 200, 120} end)
  check(tinted, "...and takes a tint over the ground it is laid on", tinted)
  far_m = read()
  local laid, res = pcall(function() return patches:add(square(start:offset(4000, 4000), 6), start) end)
  if laid then far = res else farerr = tostring(res) end
  later(12, step4)
end

-- ...and one laid where no ground is drawn cost nothing, then or three seconds later
function step4(m)
  check(far and (m == far_m), "a patch laid far outside the drawn terrain built no cut overlay mesh at all",
        far and ("overlayMeshes " .. far_m .. " -> " .. m .. note())
          or ("it could not be laid there at all: " .. tostring(farerr)))
  local moved = start:distance(me:position())
  check((moved ~= nil) and (moved < 1.0), "the character stood still through every reading", moved)
  if near:exists() then patches:remove(near) end
  if far and far:exists() then patches:remove(far) end
  manualCheck("with simple-gob-hider on, walk into a wood where dozens of objects are marked",
              "no hitch at all as you go, and every mark drawn on the ground its own object stands on")
  summary()
end

local function run()
  prof = hafen.client():profiling()
  local m = read()
  if type(m) ~= "number" then
    check(false, "render() answers overlayMeshes as a number", tostring(m))
    summary()
    return
  end
  check(true, "render() answers overlayMeshes as a number", nil)
  local s = hafen.session():current()
  me = s and s:player():gob()
  start = me and me:position()
  if not start then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- the suite owns its own patches
  bound = cutspan(square(start, 6))
  if not bound then
    check(false, "the ring's own cuts are countable from where the character stands",
          "a ring point this character cannot locate")
    summary()
    return
  end
  check(bound < DRAWN_CUTS, "a 12x12 ring reaches fewer cuts than the drawn terrain has, so the bound bites",
        bound .. " cut(s) of " .. DRAWN_CUTS)
  restless = false
  hafen.log():write("[note] laying one patch near and one far, reading overlayMeshes around each"
                      .. " -- stand still for ~15s")
  settle(step2)
end

hafen.console():on("t119", run)   -- the only way in: a suite does not start itself
