-- 161.2 — Cliffs as standing walls. Self-checking suite.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- render().drawSlots, the median of ten reads 0.1 s apart, handed to `done`.
local function sampleSlots(done)
  local reads = {}
  local function one()
    local render = hafen.client():profiling():render()
    reads[#reads + 1] = (render and render.drawSlots) or 0
    if #reads < 10 then
      hafen.timer():after(0.1, one)
    else
      table.sort(reads)
      done((reads[5] + reads[6]) / 2)
    end
  end
  one()
end

-- A write that must not be refused: the switch is the surface the walls hang on.
local function write(performance, flag)
  local ok, err = pcall(function() performance:flatTerrain(flag) end)
  check(ok and performance:flatTerrain() == flag,
        ("flatTerrain(%s) is accepted and reads back"):format(tostring(flag)),
        ok and tostring(performance:flatTerrain()) or tostring(err))
end

local function run()
  local performance = hafen.client():options():performance()
  local flat0 = performance:flatTerrain()

  manualCheck("stand at a cliff line before running :t161, with hills in view",
              "the run has ridge tiles to wall; on flat ground both samples below are the same picture")
  write(performance, true)
  -- The cuts in view rebuild lazily, cut by cut: 4 s is what the view takes.
  hafen.timer():after(4, function()
    sampleSlots(function(flat)
      manualCheck("look at the cliff line while flat",
                  "the cliff keeps its shape and its height, standing on the plane in the tileset's "
                  .. "cliff texture, flat ground on both sides, no ramp; a corner, an end and a "
                  .. "diagonal cliff each walled with no gap and no doubled wall; a cliff on a cut "
                  .. "border walled once; nothing walled where there is no cliff; caves as before; "
                  .. "seen from above and, walking round, from behind")
      write(performance, false)
      hafen.timer():after(4, function()
        sampleSlots(function(relief)
          local within = (flat > 0) and (relief > 0)
                         and (math.abs(flat - relief) <= 0.2 * math.max(flat, relief))
          check(within, ("the flat world draws (%d flat, %d relief)"):format(flat, relief),
                ("%s flat, %s relief"):format(tostring(flat), tostring(relief)))
          manualCheck("look at the cliff line again, and at stderr",
                      "the real cliffs are back; no `ridge crash` warning was issued while walking "
                      .. "the cliff line either way")
          performance:flatTerrain(flat0)
          eq("the remembered value was written back and reads equal", performance:flatTerrain(), flat0)
          hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
        end)
      end)
    end)
  end)
end

-- The only way in: a suite does not start itself. Deferred off the console handler's monitor.
hafen.console():on("t161", function() hafen.timer():after(0, run) end)
