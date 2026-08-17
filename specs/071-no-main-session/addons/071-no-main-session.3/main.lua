-- 071.3 -- the words drop the main, and the last one out reaches the login screen. Self-checking suite.
--
-- The gestures are the maintainer's -- nothing in the API types a console command -- so the suite reads
-- `live` around each of them on a bounded timer: it prompts, watches, and scores on the number that
-- gesture should or should not have moved. Every line carries the numbers, so a failure names which
-- gesture did not move what it should have.

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

-- A refusal is a check: the call must fail, and fail SAYING why. Matched against the RAW error and
-- trimmed only for the report -- a message that arrives carrying a traceback has a second `.lua:NN:`
-- in it, and trimming first would let the pattern eat the very words being looked for.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  local raw = ok and "<no error>" or tostring(err)
  check((not ok) and (raw:find(wantMsg, 1, true) ~= nil), what, (raw:gsub("^.-%.lua:%d+:%s*", "")))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function group()
  return hafen.client():profiling():session()
end

-- The three the API cannot read back: a refusal's wording, and where the screen goes when the sessions run out.
local function finish()
  manualCheck("with two sessions up, `:session add <the account that is already logged in>`",
              "refused, naming that account as already live, and `:session list` still shows both")
  manualCheck("`:session anchor main`",
              "refused, naming an ACCOUNT as what to write instead -- not `no such session: main`")
  manualCheck("`:session drop` each session in turn, the one on screen LAST",
              "the screen moves to a remaining session each time, and after the last one the login screen"
                .. " comes back with the client still running")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The pixels the burst raycasts: the character's own screen point and a ring around it, which is ground
-- the drawn session has certainly loaded because it is standing on it. Fired at the start and collected
-- after the first gesture, so the raycasts and an anchor's worth of console traffic overlap.
local ring = {{0, 0}, {40, 0}, {-40, 0}, {0, 40}, {0, -40}, {36, 36}, {-36, 36}, {-36, -36}}
local want, got, placed = 0, 0, 0

local function burst()
  local me = hafen.player():gob()
  local at = me and me:position() and hafen.player():worldToScreen(me:position())
  if not at then
    return
  end
  want = #ring
  for _, d in ipairs(ring) do
    hafen.world():screenToWorld(at.x + d[1], at.y + d[2], function(p)
      got = got + 1
      if p and p:x() and p:y() then placed = placed + 1 end
    end)
  end
end

local STEP1, STEP2 = 20, 60

-- The second gesture MOVES the number, so this one ends the moment it does and only times out on a
-- session that took the whole window to go.
local function step2(before)
  hafen.log():write(("[step 2/2] now run `:session drop <an account that is NOT the one on screen>`"
                     .. " -- live is %d, watching %d s for it to fall to %d"):format(before, STEP2, before - 1))
  local waited, t = 0, nil
  t = hafen.timer():every(1, function()
    waited = waited + 1
    local n = group().live
    if n == before - 1 then
      t:cancel()
      check(true, ("dropping a session that is not on screen took live from %d to %d"):format(before, n))
      finish()
    elseif waited >= STEP2 then
      t:cancel()
      check(false, ("dropping a session that is not on screen takes live from %d to %d"):format(before, before - 1),
            ("live = %d after %d s"):format(n, waited))
      finish()
    end
  end)
end

-- The first gesture must move NOTHING, so there is no transition to wait for: the window is the check.
local function step1(before)
  hafen.log():write(("[step 1/2] now run `:session add <an account that is ALREADY logged in>`"
                     .. " -- live is %d, watching %d s, and it must not move"):format(before, STEP1))
  hafen.timer():after(STEP1, function()
    local b = group()
    check(b.live == before, ("a duplicate `:session add` left live unchanged (%d -> %d)"):format(before, b.live), b.live)
    check((want > 0) and (got == want) and (placed == want),
          ("every raycast came back with a Position (%d/%d answered, %d placed)"):format(got, want, placed),
          ("%d answered, %d placed of %d"):format(got, placed, want))
    -- The 070.2 guarantee, re-proved here because this task rewrote who calls the cycle and the switch:
    -- the raycasts answer on the graphics environment's own thread and must read what the frame published.
    check(b.placedRebuiltOffTick == 0,
          ("no click was answered before the frame said where the sessions stand: placedRebuiltOffTick = %s")
            :format(tostring(b.placedRebuiltOffTick)), b.placedRebuiltOffTick)
    step2(b.live)
  end)
end

local function run()
  -- The whole group, from scratch: no other suite is assumed to have been run.
  local s = group()
  check(type(s.live) == "number" and type(s.groundAnswered) == "number"
        and type(s.groundMissed) == "number" and type(s.placedRebuiltOffTick) == "number",
        "the session group answers with the profiler off: live, groundAnswered, groundMissed, placedRebuiltOffTick",
        tostring(s.live) .. "/" .. tostring(s.groundAnswered) .. "/" .. tostring(s.groundMissed)
          .. "/" .. tostring(s.placedRebuiltOffTick))
  refuses("the session group refuses an argument, naming itself read-only",
          function() hafen.client():profiling():session(1) end, "read-only")
  -- Two sessions is what the gestures below need: one to add a duplicate of, and one that is not the
  -- session on screen to drop. With fewer, the run scores what it reached and says so.
  check(type(s.live) == "number" and s.live >= 2,
        ("two sessions are up, which is what these gestures need: live = %s"):format(tostring(s.live)), s.live)
  if type(s.live) ~= "number" or s.live < 2 then
    finish()
    return
  end
  burst()
  step1(s.live)
end

hafen.slash():register("t071-3", run)   -- the only way in: a suite does not start itself
