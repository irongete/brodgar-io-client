-- 160.2 — Weather, tree effects and smoke plumes. Self-checking suite.

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

-- The lower median of ten render().drawSlots reads a tenth of a second apart: one round, about a
-- second. The world is already up by the time this is called (a plume was found in it), so drawSlots
-- is never absent; a stray gap between frames still stands in as 0 rather than raising.
local function sampleDrawSlots(fn)
  local samples, n = {}, 0
  local poll
  poll = hafen.timer():every(0.1, function()
    n = n + 1
    local render = hafen.client():profiling():render()
    samples[n] = (render and render.drawSlots) or 0
    if n >= 10 then
      poll:cancel()
      table.sort(samples)
      fn(samples[5])
    end
  end)
end

-- Re-sample up to `rounds` rounds (~1 s each, so `rounds = 6` bounds the whole poll to about 6 s)
-- until test(sample) holds. fn(lastSample, whetherItHeld).
local function pollFor(rounds, test, fn)
  local round = 0
  local function attempt()
    round = round + 1
    sampleDrawSlots(function(sample)
      local ok = test(sample)
      if ok or (round >= rounds) then
        fn(sample, ok)
      else
        attempt()
      end
    end)
  end
  attempt()
end

local function finish()
  manualCheck("treeEffects(false) then treeEffects(true), watching trees and bushes on the next tick",
              "false: they stand still and keep their random tilt; true: they sway again -- no relog either way")
  manualCheck("in matching weather, toggle rain(false/true), snow(false/true), clouds(false/true) and"
              .. " seasonTint(false/true), then wetGround(false) once rain has wet the ground",
              "rain and snow stop and resume, splashes included, the same frame; clouds(false) drops the"
              .. " moving shadows; seasonTint(false) drops the tint; wetGround(false) drops the sheen;"
              .. " each restores within a frame")
  manualCheck("at a scent trail, run smoke(false)",
              "the trail keeps its smoke -- only other plumes (kilns, furnaces, ovens, chimneys, fires) are withheld")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function findPlume()
  local session = hafen.session():current()
  if not session then return nil end
  return session:world():gob():find(function(gob) return gob:overlay():find("gfx/fx/ismoke") ~= nil end)
end

local function run()
  local performance = hafen.client():options():performance()

  -- Remember the seven values this suite touches.
  local treeEffects0 = performance:treeEffects()
  local smoke0 = performance:smoke()
  local clouds0, rain0, snow0 = performance:clouds(), performance:rain(), performance:snow()
  local wetGround0, seasonTint0 = performance:wetGround(), performance:seasonTint()

  local function checkSwitches()
    performance:treeEffects(false):clouds(false):rain(false):snow(false):wetGround(false):seasonTint(false)
    local allFalse = performance:treeEffects() == false and performance:clouds() == false
                      and performance:rain() == false and performance:snow() == false
                      and performance:wetGround() == false and performance:seasonTint() == false
    performance:treeEffects(treeEffects0):clouds(clouds0):rain(rain0):snow(snow0)
    performance:wetGround(wetGround0):seasonTint(seasonTint0)
    local allRestored = performance:treeEffects() == treeEffects0 and performance:clouds() == clouds0
                         and performance:rain() == rain0 and performance:snow() == snow0
                         and performance:wetGround() == wetGround0 and performance:seasonTint() == seasonTint0
    check(allFalse and allRestored,
          "treeEffects and the five weather switches read back false after the write, their own value after the restore",
          string.format("false=%s restored=%s", tostring(allFalse), tostring(allRestored)))
    finish()
  end

  local plume = findPlume()
  if not plume then
    manualCheck("stand near a burning kiln, furnace or oven and rerun",
                "a gob carrying a gfx/fx/ismoke plume overlay is in view")
    checkSwitches()
    return
  end

  sampleDrawSlots(function(baseline)
    performance:smoke(false)
    pollFor(6, function(sample) return sample < baseline end, function(fallen, fell)
      check(fell, string.format("smoke(false) withheld the plume (%d -> %d slots)", baseline, fallen), fallen)
      local overlay = plume:overlay():find("gfx/fx/ismoke")
      check((overlay ~= nil) and (overlay:native() == true),
            "the plume stays listed and native while withheld",
            overlay and ("native=" .. tostring(overlay:native())) or "not listed")
      performance:smoke(true)
      pollFor(6, function(sample) return sample > fallen end, function(risen, rose)
        check(rose, string.format("smoke(true) brought it back without the server (%d -> %d)", fallen, risen), risen)
        performance:smoke(smoke0)
        checkSwitches()
      end)
    end)
  end)
end

-- the only way in: a suite does not start itself
hafen.console():on("t160", function() hafen.timer():after(0, run) end)
