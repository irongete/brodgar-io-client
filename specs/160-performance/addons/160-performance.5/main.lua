-- 160.5 — Crop and forageable sprouts. Self-checking suite.
-- Run standing in a field of crops, with a clump forageable in view if one is about.

-- Remembered at load and only remembered: a suite never starts itself. The run prints them first,
-- which is how a value written before a :reload is shown to have survived it.
local cropsAtLoad = hafen.client():options():performance():crops()
local forageAtLoad = hafen.client():options():performance():forage()

local SETTINGS = {
  { "flavor", 100 }, { "crops", 100 }, { "forage", 100 },
  { "groundBlend", true }, { "transitions", true }, { "treeEffects", true }, { "smoke", true },
  { "clouds", true }, { "rain", true }, { "snow", true }, { "wetGround", true }, { "seasonTint", true },
}

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
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The lower median of ten render() reads of `key` a tenth of a second apart: one round, about a
-- second. A stray gap between frames stands in as 0 rather than raising. The sprouts are read as
-- `treeLeaves`, one leaf per sprout: the same mesh at twenty places is one instanced batch, so
-- drawSlots barely moves when a field goes from four sprouts a tile to one. The closing check is
-- drawSlots, the scene as drawn.
local function sample(key, fn)
  local samples, n = {}, 0
  local poll
  poll = hafen.timer():every(0.1, function()
    n = n + 1
    local render = hafen.client():profiling():render()
    samples[n] = (render and render[key]) or 0
    if n >= 10 then
      poll:cancel()
      table.sort(samples)
      fn(samples[5])
    end
  end)
end

-- Re-sample up to `rounds` rounds (~1 s each, so `rounds = 8` bounds the whole poll to about 8 s)
-- until test(sample) holds. fn(lastSample, whetherItHeld).
local function pollFor(key, rounds, test, fn)
  local round = 0
  local function attempt()
    round = round + 1
    sample(key, function(sample)
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

-- Sample until two rounds agree within a fiftieth, so a deferred re-creation or a lazy rebuild
-- settles before the count is taken.
local function settle(key, fn)
  local previous = -1
  pollFor(key, 8, function(sample)
    local settled = (previous >= 0) and (math.abs(sample - previous) <= previous * 0.02)
    previous = sample
    return settled
  end, function(sample) fn(sample) end)
end

local function within(sample, reference, fraction)
  return math.abs(sample - reference) <= reference * fraction
end

local function named(prefix, exclude)
  return function(gob)
    local name = gob:name()
    return name ~= nil and name:find(prefix, 1, true) ~= nil
       and (exclude == nil or name:find(exclude, 1, true) == nil)
  end
end

local function findGob(session, prefix, exclude)
  return session:world():gob():find(named(prefix, exclude))
end

local function countGobs(session, prefix, exclude)
  return #session:world():gob():list(named(prefix, exclude))
end

-- Drive `verb` over `gob` from 100 to 1 and back, scoring the fall and the rise against a settled
-- baseline at 100. A fall is a drop past the lower median's own jitter (`fraction` of the baseline, at
-- least `minJitter` slots): a field loses a few slots per tile, a single clump a handful, against a scene
-- of thousands, and the check is that the sprouts went, not how many there were.
-- fn(fell, baseline, fallen) once the setting is back at 100.
local function drive(performance, verb, gob, label, fraction, minJitter, fn)
  local name0 = gob:name()
  performance[verb](performance, 100)
  settle("treeLeaves", function(baseline)
    local jitter = math.max(minJitter, baseline * fraction)
    performance[verb](performance, 1)
    pollFor("treeLeaves", 8, function(sample) return sample <= baseline - jitter end, function(fallen, fell)
      if fell then
        check(true, string.format("%s(1) drew fewer sprouts (%d -> %d leaves)", verb, baseline, fallen))
        check(gob:exists() and gob:name() == name0, "the " .. label .. " is the same object", tostring(gob:name()))
      end
      performance[verb](performance, 100)
      pollFor("treeLeaves", 8, function(sample) return within(sample, baseline, 0.1) end, function(risen, rose)
        if fell then
          check(rose, string.format("%s(100) drew them again (%d -> %d leaves)", verb, fallen, risen), risen)
        end
        fn(fell, baseline, fallen)
      end)
    end)
  end)
end

-- The feature's closing check: every control at its default draws the scene the run began with, when
-- the run began at the defaults. Then the twelve are restored.
local function finish(performance, remembered, firstSample)
  local allDefault = true
  for _, setting in ipairs(SETTINGS) do
    if remembered[setting[1]] ~= setting[2] then allDefault = false end
  end
  for _, setting in ipairs(SETTINGS) do
    performance[setting[1]](performance, setting[2])
  end
  settle("drawSlots", function(sample)
    if allDefault then
      check(within(sample, firstSample, 0.1),
            string.format("the defaults draw the same scene (%d -> %d)", firstSample, sample), sample)
    else
      manualCheck("set every control to its default and rerun", "the defaults draw the same scene")
    end
    for _, setting in ipairs(SETTINGS) do
      performance[setting[1]](performance, remembered[setting[1]])
    end
    local restored = true
    for _, setting in ipairs(SETTINGS) do
      if performance[setting[1]](performance) ~= remembered[setting[1]] then restored = false end
    end
    check(restored, "the twelve settings are restored to what they were", restored)
    manualCheck("look at a field at crops(1), a trellis among it if there is one",
                "one sprout per tile, its stage still readable, one sprout centred on the trellis; the same crop names on hover")
    manualCheck("look at the same field at crops(100)", "the full field, the same layout as before the run")
    manualCheck("with every control at its default, look around",
                "the world as it was before the feature: the flavor objects, the ground, swaying trees, smoke, weather")
    manualCheck("on the day find-updates lists lib/plants, run the client",
                "one warning about the copy and the full field drawn whatever crops() reads; no error")
    summary()
  end)
end

local function run()
  hafen.log():write("crops at load: " .. tostring(cropsAtLoad) .. ", forage at load: " .. tostring(forageAtLoad))
  local performance = hafen.client():options():performance()
  local remembered = {}
  for _, setting in ipairs(SETTINGS) do
    remembered[setting[1]] = performance[setting[1]](performance)
  end
  local session = hafen.session():current()
  if not session then
    manualCheck("log a character in and rerun", "the checks below run")
    summary()
    return
  end
  settle("drawSlots", function(firstSample)
    local function forageStep()
      local herb = findGob(session, "gfx/terobjs/herbs/")
      if herb then
        drive(performance, "forage", herb, "forageable", 0.002, 2, function(fell)
          if not fell then
            manualCheck("stand near a forageable that grows as a clump and rerun", "forage(1) draws fewer sprouts")
          end
          finish(performance, remembered, firstSample)
        end)
      else
        manualCheck("stand near a forageable that grows as a clump and rerun", "forage(1) draws fewer sprouts")
        finish(performance, remembered, firstSample)
      end
    end
    local crop = findGob(session, "gfx/terobjs/plants/", "trellis")
    if crop then
      drive(performance, "crops", crop, "crop", 0.002, 4, function(fell, baseline, fallen)
        if not fell then
          check(false, "crops(1) drew fewer sprouts",
                string.format("no fall within 8 s (%d -> %d leaves, %d crops in view)", baseline, fallen,
                              countGobs(session, "gfx/terobjs/plants/", "trellis")))
        end
        forageStep()
      end)
    else
      manualCheck("stand in a field and rerun", "crops(1) draws fewer sprouts and crops(100) draws them again")
      forageStep()
    end
  end)
end

-- the only way in: a suite does not start itself
hafen.console():on("t160", function() hafen.timer():after(0, run) end)
