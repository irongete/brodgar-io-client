-- 160.4 — Ground blend and tile transitions. Self-checking suite.
-- Run with mixed ground in view: grass beside dirt, sand or forest floor.

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
-- second. A stray gap between frames stands in as 0 rather than raising.
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

-- Re-sample up to `rounds` rounds (~1 s each, so `rounds = 8` bounds the whole poll to about 8 s)
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

local function finish(performance, blend0, trans0)
  performance:groundBlend(blend0):transitions(trans0)
  check((performance:groundBlend() == blend0) and (performance:transitions() == trans0),
        string.format("groundBlend and transitions restored to the remembered %s / %s", tostring(blend0), tostring(trans0)),
        tostring(performance:groundBlend()) .. " / " .. tostring(performance:transitions()))
  manualCheck("look at a meadow at groundBlend(false)",
              "every tile of one type wears one texture -- a uniform meadow, no noise, no patches")
  manualCheck("look at a border between two tile types at transitions(false)",
              "a hard edge, no skirt")
  manualCheck("look at the same ground with both back on",
              "the upstream picture, cliffs and water included")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local performance = hafen.client():options():performance()
  local blend0, trans0 = performance:groundBlend(), performance:transitions()
  manualCheck("the run needs mixed ground in view (grass beside dirt, sand or forest floor)",
              "the slot counts below move with each switch")

  -- The baseline is the scene with both on: written first, then sampled until two rounds agree
  -- within a fiftieth -- and, when either remembered value was off, until the count has risen by a
  -- tenth over the first round or the poll runs out -- so the lazy re-mesh settles before the count
  -- is taken.
  performance:groundBlend(true):transitions(true)
  local first, previous = -1, -1
  pollFor(8, function(sample)
    if first < 0 then first = sample end
    local settled = (previous >= 0) and (math.abs(sample - previous) <= previous * 0.02)
    previous = sample
    return settled and ((blend0 and trans0) or (sample >= first * 1.1))
  end, function(baseline)
    -- A fall is a drop past the lower median's own jitter (a fiftieth of the baseline, at least four
    -- slots); "back" is within that same jitter of the baseline.
    local jitter = math.max(4, baseline * 0.02)
    performance:groundBlend(false)
    pollFor(8, function(sample) return sample <= baseline - jitter end, function(blendOff, fell)
      check(fell, string.format("groundBlend(false) drew fewer slots (%d -> %d)", baseline, blendOff), blendOff)
      performance:groundBlend(true)
      pollFor(8, function(sample) return sample >= baseline - jitter end, function(back1, rose)
        check(rose, string.format("groundBlend(true) drew them again (%d -> %d)", blendOff, back1), back1)
        performance:transitions(false)
        pollFor(8, function(sample) return sample <= baseline - jitter end, function(transOff, fell2)
          check(fell2, string.format("transitions(false) drew fewer slots (%d -> %d)", baseline, transOff), transOff)
          performance:transitions(true)
          pollFor(8, function(sample) return sample >= baseline - jitter end, function(back2, rose2)
            check(rose2, string.format("transitions(true) drew them again (%d -> %d)", transOff, back2), back2)
            -- Both off: the base ground alone, so fewer slots than either switch alone left, by at least
            -- a hundredth of the baseline (two slots at the least) so jitter cannot pass it.
            performance:groundBlend(false):transitions(false)
            local lower = math.min(blendOff, transOff) - math.max(2, baseline * 0.01)
            pollFor(8, function(sample) return sample < lower end, function(bothOff, below)
              check(below, string.format("both off drew fewer than either alone (%d < min(%d, %d))", bothOff, blendOff, transOff), bothOff)
              finish(performance, blend0, trans0)
            end)
          end)
        end)
      end)
    end)
  end)
end

-- the only way in: a suite does not start itself
hafen.console():on("t160", function() hafen.timer():after(0, run) end)
