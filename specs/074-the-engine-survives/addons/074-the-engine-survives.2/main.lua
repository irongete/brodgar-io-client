-- 074.2 — the engine stops reloading, and an addon outlives a switch. Self-checking suite.
--
-- Everything here rests on ONE claim: this file body ran once, for the client, and nothing runs it again
-- when you tab to another character. So the suite keeps two numbers in two different homes and reports
-- both. `updates` is a Lua upvalue of THIS environment, climbing on every Update -- rebuild the
-- environment and it starts at zero. `loads` is a saved variable, incremented right here in the body --
-- load the addon a second time and it climbs. Tab between two characters and neither may move except the
-- one that is supposed to.

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

-- THE FILE BODY. Every line of it runs exactly once per load of this addon, and that is the point.
local store = hafen.store():get("history")
store.loads = (store.loads or 0) + 1
local loads = store.loads

local updates = 0                       -- the upvalue: this Lua environment's own life, in frames
hafen.event():on("Update", function() updates = updates + 1 end)

-- What the previous run of this command saw, if there was one. The comparison against it is where the
-- whole claim lands: a run that finds the last run's numbers is a run in the same Lua environment.
local prev = nil

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.client():profiling():session()

  check(s.engineReloads == 0,
        "engineReloads is 0 -- nothing rebuilt the addon layer unasked", s.engineReloads)
  check(s.addonsLive > 0,
        ("addonsLive counts the addons running (%d)"):format(s.addonsLive), s.addonsLive)
  check(updates > 0,
        ("the layer's own Update pump is running for this addon (%d frames)"):format(updates), updates)
  check((type(store.loads) == "number") and (store.loads == loads) and (loads >= 1),
        ("the account-scope saved variable holds this load's own count (%d)"):format(loads), store.loads)
  refuses("client:profiling():session() refuses an argument, saying it is read-only",
          function() hafen.client():profiling():session(1) end, "read-only")

  -- From the second run on there is something to compare against, and this ONE line is the task: same
  -- environment (the frame count carried on from where the last run left it), same load (the body did not
  -- run again), same addon count. Tab between two characters between the runs and none of it may move.
  if prev ~= nil then
    check((prev.updates > 0) and (updates > prev.updates)
            and (prev.loads == loads) and (prev.addonsLive == s.addonsLive),
          ("nothing was rebuilt since the last run (%d -> %d frames, load %d, %d addons)")
            :format(prev.updates, updates, loads, s.addonsLive),
          ("%d -> %d frames, load %d -> %d, %d -> %d addons")
            :format(prev.updates, updates, prev.loads, loads, prev.addonsLive, s.addonsLive))
  end
  prev = { updates = updates, loads = loads, addonsLive = s.addonsLive }

  manualCheck(("`:session add <a second account>`, let it reach the world, tab to it and BACK, re-run me"
               .. " (this run: %d frames, load %d)"):format(updates, loads),
              ("the frame count is HIGHER than %d and the load count is STILL %d -- the addon was never"
               .. " reloaded"):format(updates, loads))
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t074-2", run)   -- the only way in: a suite does not start itself
