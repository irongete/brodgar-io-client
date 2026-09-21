-- 161.1 — Flat terrain: the switch and the height seam. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why. Every string in `want` must be found.
local function refuses(what, fn, want)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  local matched = not ok
  for _, w in ipairs(want) do
    if not (matched and err:find(w, 1, true)) then matched = false end
  end
  check(matched, what, err)
end

-- Several refusals reported as ONE line, so the run stays inside the suite's own line budget.
local function refusesAll(what, cases)
  local bads = {}
  for _, case in ipairs(cases) do
    local ok, err = pcall(case.fn)
    err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
    local matched = not ok
    for _, w in ipairs(case.want) do
      if not (matched and err:find(w, 1, true)) then matched = false end
    end
    if not matched then
      bads[#bads + 1] = case.label .. ": " .. err
    end
  end
  check(#bads == 0, what, table.concat(bads, " | "))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Read once, at file scope, before :t161 is ever run -- a suite never starts itself. This is what
-- proves the written value survives a :reload: the run's first line reports what class init found.
local flatAtLoad = hafen.client():options():performance():flatTerrain()

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

-- The cell of the recorded grid a durable position falls in: tiles are 11 by 11 units.
local function cellOf(durable)
  return { x = math.floor(durable.x / 11), y = math.floor(durable.y / 11) }
end

local function finish(performance, flat0, slots0)
  performance:flatTerrain(flat0)
  eq("the remembered value was written back and reads equal", performance:flatTerrain(), flat0)
  -- The picture's number: the cuts rebuild at the restored setting, then the same sample again.
  hafen.timer():after(4, function()
    sampleSlots(function(slots1)
      local within = (slots0 > 0) and (slots1 > 0) and (math.abs(slots1 - slots0) <= 0.1 * slots0)
      check(within, ("the relief draws what it drew (%d before, %d after)"):format(slots0, slots1),
            ("%s before, %s after"):format(tostring(slots0), tostring(slots1)))
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end)
  end)
end

local function run(slots0)
  local performance = hafen.client():options():performance()
  local session = hafen.session():current()
  local flat0 = performance:flatTerrain()

  check(type(flatAtLoad) == "boolean", "flatTerrain at load: " .. tostring(flatAtLoad), flatAtLoad)
  eq("the read is a boolean", type(performance:flatTerrain()), "boolean")

  performance:flatTerrain(true)
  eq("the round trip: flatTerrain(true) reads true", performance:flatTerrain(), true)
  local chained = performance:flatTerrain(true):flatTerrain(false)
  check(chained == performance and performance:flatTerrain() == false,
        "chaining: flatTerrain(true):flatTerrain(false) returns the handle, then reads false",
        tostring(chained == performance) .. " / " .. tostring(performance:flatTerrain()))

  refusesAll("a non-boolean is refused naming a switch, and an explicit nil is refused rather than read", {
    {label = "flatTerrain(\"no\")", fn = function() performance:flatTerrain("no") end,
     want = {"performance:flatTerrain: on must be true or false"}},
    {label = "flatTerrain(0)", fn = function() performance:flatTerrain(0) end,
     want = {"performance:flatTerrain: on must be true or false"}},
    {label = "flatTerrain(nil)", fn = function() performance:flatTerrain(nil) end, want = {"must not be nil"}},
  })
  refuses("an unknown verb is refused, naming flatTerrain among the handle's verbs",
          function() performance:foo() end, {"performance has no verb 'foo'", "flatTerrain"})

  -- The real height: the API and the record answer the streamed height whatever the panel draws.
  manualCheck("stand where the ground has relief before running :t161",
              "the API height below is not zero, so a flattened read could not pass by chance")
  local world = session and session:world()
  local position = session and session:player():gob():position()
  local before = world and position and world:height(position)
  local grid = world and position and world:grid():at(position)
  local cell = position and cellOf(position:info())
  local recordBefore = grid and cell and grid:height(cell)
  performance:flatTerrain(true)
  hafen.timer():after(0.2, function()
    local after = world and position and world:height(position)
    check(type(before) == "number" and after == before,
          ("the API height is the streamed one (%s)"):format(tostring(before)),
          tostring(before) .. " before, " .. tostring(after) .. " after")
    local recordAfter = grid and cell and grid:height(cell)
    check(type(recordBefore) == "number" and recordAfter == recordBefore,
          ("the recorded height is the streamed one (%s)"):format(tostring(recordBefore)),
          tostring(recordBefore) .. " before, " .. tostring(recordAfter) .. " after")
    finish(performance, flat0, slots0)
  end)
end

-- The picture's number is sampled before any write, so the run opens with the sample.
hafen.console():on("t161", function() hafen.timer():after(0, function() sampleSlots(run) end) end)
