-- 075.3 — a thing you stand in the world belongs to the world. Self-checking suite.
--
-- The two probes stand for HOLD seconds before they are taken down, which is the window the tab-over
-- checks need: the other character has to be looked at while they are still standing. The summary
-- prints when they come down, so the run is over when you read it.

local pass, fail, manual = 0, 0, 0

local RES  = "gfx/terobjs/arch/logcabin"   -- unmistakable at a glance, which is what a manual check needs
local HOLD = 45                            -- seconds the probes stand, for the two tab-over checks

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- A ghost's visual streams in on a loader thread, so score over what the run reaches: poll a bounded
-- window rather than asking about the scene in the same breath as the placement.
local function waitFor(done, secs, fn)
  local left = secs
  local t
  t = hafen.timer():every(0.2, function()
    left = left - 0.2
    if done() or (left <= 0) then
      t:cancel()
      fn(done())
    end
  end)
end

local function placed()
  return hafen.client():profiling():entities().placed
end

-- Is this gob carrying one of ours, standing in the world?
local function standingAt(gob)
  for _, ov in ipairs(gob:overlay():list()) do
    if (ov:kind() == "ghost") and (ov:gob() == gob) then
      return ov
    end
  end
  return nil
end

local function run()
  pass, fail, manual = 0, 0, 0             -- the manual checks re-run this: one summary per run

  local vr = hafen.vr():ghost()
  local pgob = hafen.player():gob()
  local here = pgob and pgob:position()
  if here == nil then
    check(false, "the player is in the world, with a place to stand something at", here)
    summary()
    return
  end

  local want, before = here:info(), placed()

  -- FREE: it holds the place it was given, and that place is the server's naming of the ground.
  local e = vr:add(RES, here)
  check(e:exists(), "a free entity stands at a Position", e:exists())
  local got = e:position():info()
  check((got ~= nil) and (got.gridId == want.gridId) and (got.x == want.x) and (got.y == want.y),
        "its place reads back the grid and offset it was given",
        got and (got.gridId .. " " .. got.x .. " " .. got.y))
  check(placed() == before + 1, "the client counts one more standing", placed())

  -- ANCHORED: the object it stands on names it, and the id that finds it is the server's.
  local a = vr:add(RES, pgob)
  check(a:exists(), "an anchored entity stands on a gob", a:exists())
  check(standingAt(pgob) ~= nil, "the gob it stands on reports it", standingAt(pgob))

  refuses("a malformed place is refused", function() vr:add(RES, { x = 1, y = 2 }) end,
          "the anchor is a Position OR a Gob")

  manualCheck("with two characters standing together, tab to the other one within " .. HOLD .. "s",
              "the cabin is drawn there too, on the same spot -- it is in the world, not in a login")
  manualCheck("tab to a character standing far away, then back",
              "nothing is drawn there and nothing errors, and it is drawn again when you come back")

  waitFor(function() return e:drawn() end, 8, function(drawn)
    check(drawn, "it is in the scene on screen once its visual lands", drawn)
    hafen.timer():after(HOLD, function()
      vr:remove(e)
      vr:remove(a)
      check(not e:exists(), "the free one is gone after :remove", e:exists())
      check(not a:exists(), "the anchored one is gone with it", a:exists())
      check(standingAt(pgob) == nil, "and the gob reports nothing standing on it", standingAt(pgob))
      check(placed() == before, "the client counts what it counted before", placed())
      summary()
    end)
  end)
end

hafen.slash():register("t075-3", run)   -- the only way in: a suite does not start itself
