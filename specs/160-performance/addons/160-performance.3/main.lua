-- 160.3 — Flavor objects. Self-checking suite.
-- Run standing on grass or heath, with a meadow in view.

-- Remembered at load and only remembered: a suite never starts itself. The run prints it first, which
-- is how a value written before a :reload is shown to have survived it.
local flavorAtLoad = hafen.client():options():performance():flavor()

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

local function finish(performance, flavor0)
  performance:flavor(flavor0)
  check(performance:flavor() == flavor0, "flavor restored to the remembered " .. tostring(flavor0), performance:flavor())
  manualCheck("on a summer meadow at flavor(0), listen",
              "the crickets and birds are still heard -- the ambient loops unchanged")
  manualCheck("look at the same meadow at flavor(0), flavor(50) and flavor(100)",
              "the same tufts in the same places, fewer of them at the lower settings, none of them moved")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  hafen.log():write("flavor at load: " .. tostring(flavorAtLoad))
  local performance = hafen.client():options():performance()
  local flavor0 = performance:flavor()
  manualCheck("the run needs ground that seeds flavor objects in view (grass, heath, a pine barren)",
              "the three slot counts below move with the setting")

  -- The baseline is the scene at 100: written first, then sampled until two rounds agree within a
  -- fiftieth -- and, when the remembered value was lower, until the count has risen by a tenth over
  -- the first round or the poll runs out -- so the lazy rebuild settles before the count is taken.
  performance:flavor(100)
  local first, previous = -1, -1
  pollFor(8, function(sample)
    if first < 0 then first = sample end
    local settled = (previous >= 0) and (math.abs(sample - previous) <= previous * 0.02)
    previous = sample
    return settled and ((flavor0 == 100) or (sample >= first * 1.1))
  end, function(baseline)
    -- A fall is a drop past the lower median's own jitter (a fiftieth of the baseline, at least
    -- four slots): sparse ground (a pine barren, a dry flat) seeds a few dozen pieces where a meadow
    -- seeds hundreds, and the check is that the pieces went, not how many there were.
    local jitter = math.max(4, baseline * 0.02)
    performance:flavor(0)
    pollFor(8, function(sample) return sample <= baseline - jitter end, function(fallen, fell)
      check(fell, string.format("flavor(0) drew fewer slots (%d -> %d)", baseline, fallen), fallen)
      performance:flavor(100)
      pollFor(8, function(sample) return sample >= baseline - jitter end, function(risen, rose)
        check(rose, string.format("flavor(100) drew them again (%d -> %d)", fallen, risen), risen)
        -- Between with a margin of a twentieth of the span (at least two slots) on each side, so frame
        -- jitter on a scene that never moved cannot pass it.
        performance:flavor(50)
        local margin = math.max(2, (risen - fallen) * 0.05)
        pollFor(8, function(sample) return (sample > fallen + margin) and (sample < risen - margin) end,
                function(half, between)
          check(between, string.format("flavor(50) is between (%d < %d < %d)", fallen, half, risen), half)
          finish(performance, flavor0)
        end)
      end)
    end)
  end)
end

-- the only way in: a suite does not start itself
hafen.console():on("t160", function() hafen.timer():after(0, run) end)
