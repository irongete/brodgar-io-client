-- 119.2 -- registering one overlay re-cuts one overlay. Self-checking suite.
--
-- The claim is a subtraction. `overlayMeshes` counts every cut overlay mesh the client builds, so the
-- COST OF LAYING ONE PATCH is the amount that counter moves while it is laid. Lay five, let the number
-- settle, lay a sixth and keep the delta; lay twenty more, let it settle, lay one more and keep that
-- delta. If registering an overlay re-cut every other overlay in the grid, the second delta would be
-- about four and a half times the first -- it was the whole grid's overlay meshes both times, and there
-- were 27 overlays the second time and 6 the first. Registering one overlay re-cuts one overlay iff the
-- two deltas are EQUAL.
--
-- Taking one up is the same claim from the other side: nothing is built, so the counter does not move at
-- all. And the one thing none of it may change is the surface, so a patch it laid is asked afterwards
-- whether it exists, whether it is drawn, and whether it still takes a tint.
--
-- `RectOverlay.update` -- the third bump this task moved onto the per-overlay sequence -- has no Lua
-- caller to drive from here; it is the client's own drag rectangle. Its half is verified by reading the
-- site: `MCache.RectOverlay.update` bumps `olbump(id)`, the same per-id sequence `add` and `remove` bump
-- and the same one `Grid.getolcut` compares `Cut.olstamp` against.
--
-- Nothing here is protected: reading the counters and laying a patch are both unprotected verbs, so this
-- suite declares no permission keys and every protected verb must still refuse it.
--
-- It takes a few seconds and it measures the scene, so it also checks that the character stood still
-- through both measurements -- a view that moved builds cuts of its own and there would be nothing to
-- subtract.
--
-- WHAT THIS TASK DOES NOT CLAIM. The constant is not small: an overlay's raster asks for a mesh over
-- every cut of the drawn terrain, mask or no mask, and the outline pass over each reaches one tile into
-- the neighbouring grid. So laying a mark still costs, and the manual lines below ask whether that cost
-- GROWS with how many are already down -- which is the whole of what invalidation breadth decides.

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

local prof, patches, me, start
local mine = {}          -- every patch this suite laid, in the order it laid them
local restless = false   -- did any settle window run out with the counter still moving?

local function meshes()
  return prof:render().overlayMeshes
end

-- Lay n patches of our own, each a small square near the character, spread out so no two are the shape.
-- Where they lie does not enter the arithmetic: an overlay's raster asks for a mesh over every cut of the
-- drawn terrain, and the ones its mask does not reach answer an empty mesh -- built, counted, cached.
local function lay(n)
  for _ = 1, n do
    local i = #mine + 1
    local c = start:offset(((i % 7) - 3) * 4, (((i - (i % 7)) / 7) - 2) * 4)
    local ring = { c:offset(-3, -3), c:offset(3, -3), c:offset(3, 3), c:offset(-3, 3) }
    mine[#mine + 1] = patches:add(ring, c)
  end
end

-- Wait until the counter stops moving -- three equal readings a quarter-second apart -- then hand back
-- what it settled at. A scene still streaming in builds cuts of its own, and there is nothing to subtract
-- until it has stopped: a window that runs out with the number still climbing is recorded rather than used.
local function settle(done)
  local last, stable, tries = -1, 0, 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local n = meshes()
    if n == last then stable = stable + 1 else stable = 0 end
    last = n
    if (stable >= 2) or (tries >= 40) then
      tm:cancel()
      if stable < 2 then restless = true end
      done(n)
    end
  end)
end

local function cleanup()
  for _, p in ipairs(mine) do
    if p:exists() then patches:remove(p) end
  end
end

local step2, step3, step4, step5, step6
local base5, base26, d1, d2, beforeRemove

-- five down, settled
function step2(a)
  base5 = a
  lay(1)
  settle(step3)
end

-- ...and the sixth's cost
function step3(b)
  d1 = b - base5
  lay(20)
  settle(step4)
end

-- twenty-six down, settled
function step4(c)
  base26 = c
  lay(1)
  settle(step5)
end

-- ...and the twenty-seventh's cost, which must be the sixth's
function step5(d)
  d2 = d - base26
  check(d1 > 0, "laying one patch builds cut overlay meshes at all", "delta " .. d1)
  check(d1 == d2,
        "one more patch costs the same with 26 down as with 5",
        d1 .. " then " .. d2 .. (restless and " (the counter never went quiet -- was the scene loading?)" or ""))
  beforeRemove = meshes()
  patches:remove(mine[1])
  settle(step6)
end

-- taking one up builds nothing
function step6(e)
  check(e == beforeRemove, "taking one patch up builds nothing",
        beforeRemove .. " -> " .. e)
  local p = mine[#mine]
  check(p:exists(), "a patch it laid still exists", p:exists())
  check(p:drawn(), "...and is still on the terrain being drawn", p:drawn())
  local ok, terr = pcall(function() p:tint{40, 200, 120} end)
  check(ok and (p:tint() ~= nil), "...and still takes a tint", ok and tostring(p:tint()) or terr)
  local moved = start:distance(me:position())
  check((moved ~= nil) and (moved < 1.0),
        "the character stood still through both measurements", moved)
  cleanup()
  manualCheck("with simple-gob-hider on, walk into a wood with a handful marked, then into one with dozens",
              "the SAME hitch both times -- what one mark costs does not grow with how many are down")
  manualCheck("...then look at the ground that streamed in while you walked",
              "the marks drawn on it normally, exactly as on the ground you started from")
  summary()
end

local function run()
  prof = hafen.client():profiling()
  local s = hafen.session():current()
  me = s and s:player():gob()
  start = me and me:position()
  if not start then
    check(false, "a character in the world to lay patches under", "no session, no player gob, or no place")
    summary()
    return
  end
  patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- the suite owns its own patches
  mine = {}
  restless = false
  hafen.log():write("[note] laying 27 patches and reading the counter between them -- stand still for ~10s")
  lay(5)
  settle(step2)
end

hafen.console():on("t119", run)   -- the only way in: a suite does not start itself
