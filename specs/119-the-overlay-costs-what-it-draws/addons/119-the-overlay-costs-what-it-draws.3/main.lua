-- 119.3 -- nobody pays for an outline nobody draws. Self-checking suite.
--
-- The whole claim is one comparison. `overlayMeshes` and `overlayOutlines` count the two meshes a cut's
-- overlay is built from, and until this task the client built both for every overlay in every cut: the
-- outline was a full tile-laying pass, per cut, for a mesh that could never reach the screen unless the
-- overlay carried an outline material. A patch does not carry one -- `PatchOverlay.omat()` is null, the
-- rim is the carve's own smoothstep -- so laying one must move `overlayMeshes` and must not move
-- `overlayOutlines` AT ALL. Both numbers are read from one quiet baseline, so the second half is a plain
-- zero rather than a ratio to argue about.
--
-- ...and not built late either. `Grid.getolcut` writes its stamp with the base mesh, so a cut it has
-- already stamped is never revisited; if the outline were merely deferred rather than skipped, it would
-- appear on some later tick while the terrain around the patch is still being cut. So the counter is read
-- again several seconds after the scene has gone quiet, and must still be exactly where it started.
--
-- THE `getols` HALF CHANGES WHAT NO LUA CALL CAN OBSERVE, and is verified by reading the site rather than
-- faked into a check here: `MCache.getols` collects into a `LinkedHashSet` where it collected into an
-- `ArrayList` it re-scanned with `contains` per candidate. A LinkedHashSet answers the same overlays in
-- insertion order, which is the order the list gave, and `MapView.oltick` -- its one caller -- only walks
-- them. What IS observable is that it still answers the patch at all: an overlay `getols` dropped would
-- get no raster, so no cut of it would ever be built, and the first check below would read a delta of zero.
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

local prof, patches, me, start, patch
local restless = false   -- did a settle window run out with the counters still moving?

local function read()
  local r = prof:render()
  return r.overlayMeshes, r.overlayOutlines
end

-- Wait until BOTH counters stop moving -- three equal readings a quarter-second apart -- then hand back
-- what they settled at. A scene still streaming in builds cuts of its own, and an overlay of the client's
-- own that does carry an outline material would move the second number while it does; there is nothing to
-- subtract until that has stopped. A window that runs out with a number still climbing is recorded rather
-- than used, so the log says so instead of blaming the code.
local function settle(done)
  local lastm, lasto, stable, tries = -1, -1, 0, 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local m, o = read()
    if (m == lastm) and (o == lasto) then stable = stable + 1 else stable = 0 end
    lastm, lasto = m, o
    if (stable >= 2) or (tries >= 40) then
      tm:cancel()
      if stable < 2 then restless = true end
      done(m, o)
    end
  end)
end

-- Read once more after a stretch of quiet: an outline that was deferred rather than skipped would land in
-- it, while the terrain around a freshly laid patch is still being cut.
local function later(n, done)
  local tries = 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    if tries >= n then
      tm:cancel()
      done(read())
    end
  end)
end

local step2, step3, step4
local base_m, base_o, laid_m, laid_o

-- the quiet baseline, with nothing of ours down
function step2(m, o)
  base_m, base_o = m, o
  local ring = { start:offset(-6, -6), start:offset(6, -6), start:offset(6, 6), start:offset(-6, 6) }
  patch = patches:add(ring, start)
  settle(step3)
end

-- ...and what one patch cost, in both currencies
function step3(m, o)
  laid_m, laid_o = m, o
  local note = restless and " (the counters never went quiet -- was the scene loading?)" or ""
  check(laid_m > base_m, "laying one patch built cut overlay meshes",
        "overlayMeshes " .. base_m .. " -> " .. laid_m .. note)
  check(laid_o == base_o, "...and not one outline mesh beside them, since a patch has no outline material",
        "overlayOutlines " .. base_o .. " -> " .. laid_o .. note)
  check(patch:drawn(), "the patch it laid is on the terrain being drawn", patch:drawn())
  later(12, step4)
end

-- ...and the outline is not built late either
function step4(m, o)
  check(o == base_o, "no outline mesh appeared over the three seconds after that",
        "overlayOutlines " .. base_o .. " -> " .. o
          .. " (overlayMeshes " .. laid_m .. " -> " .. m .. ")")
  local moved = start:distance(me:position())
  check((moved ~= nil) and (moved < 1.0),
        "the character stood still through every reading", moved)
  if patch:exists() then patches:remove(patch) end
  manualCheck("with a personal claim of yours in view, turn on Display personal claims",
              "the claim drawn with its outline border, exactly as before -- a claim DOES carry an outline material")
  summary()
end

local function run()
  prof = hafen.client():profiling()
  local m, o = read()
  if (type(m) ~= "number") or (type(o) ~= "number") then
    check(false, "render() answers overlayMeshes and overlayOutlines as numbers",
          tostring(m) .. "/" .. tostring(o))
    summary()
    return
  end
  check(true, "render() answers overlayMeshes and overlayOutlines as numbers", nil)
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
  restless = false
  hafen.log():write("[note] laying one patch and reading both counters around it -- stand still for ~10s")
  settle(step2)
end

hafen.console():on("t119", run)   -- the only way in: a suite does not start itself
