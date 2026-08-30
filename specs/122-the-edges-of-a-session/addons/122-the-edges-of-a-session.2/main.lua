-- 122.2 — a background session is silent from its first sound. Self-checking suite.
--
-- What the task changed is a monitor: the channel that carries a session's sound now writes its level
-- and its mute under the same lock the channel is BUILT under, so a session first silenced before it
-- ever played anything stays silenced when it finally does. Nothing in the API can hear a background
-- character, so the two ends of that change are checked from two different sides.
--
-- The half a program can read: the level setter is the other writer of that lock, and the one an addon
-- can reach. A lock added to a setter is a setter that can stop writing -- swallow the value, write it
-- somewhere nobody reads, or take the first write and drop the next -- and none of that shows up
-- anywhere except in what the level reads back afterwards. So the round trip is TWO consecutive
-- writes, each read back on the line after it, because "the first write took and the second was
-- dropped" is precisely the shape of the bug this task closes on the muting side.
--
-- The half a program cannot: whether a character you are not looking at makes a noise. That is the two
-- [manual] lines, and the order in them is the whole point -- a login left in the background from
-- BEFORE it enters the world is the one case the old code got wrong, and one that has been on screen
-- once is the case it got right. Doing it in the other order proves nothing.
--
-- The levels here are the user's own and persist, so this suite writes only exact binary fractions,
-- reads back with a tolerance, and puts the level it found back before it prints its summary.

local pass, fail, manual = 0, 0, 0

local EPS = 1e-9

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The first line of it, without the chunk and line LuaJ writes in front: a bridge refusal arrives with a
-- Java traceback stapled on, and a verdict line is one line.
local function why(err)
  local msg = tostring(err):gsub("\n.*$", "")
  return (msg:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check too: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- LuaJ's string.format ignores a precision, so a level is rounded by hand or it prints as a raw double.
local function lvl(v)
  if type(v) ~= "number" then return tostring(v) end
  return tostring(math.floor((v * 100) + 0.5) / 100)
end

local function near(a, b)
  return (type(a) == "number") and (type(b) == "number") and (math.abs(a - b) < EPS)
end

local audio = nil

-- The one protected verb this suite calls: the manifest declares client.settings for it, so enabling
-- the addon is where it is granted. A refusal travels into the verdict line naming the key.
local function writelevel(v)
  local ok, err = pcall(function() audio:uiVolume(v) end)
  if ok then return true, audio:uiVolume() end
  return false, why(err)
end

-- Two levels to write, both exact in binary and both different from the one the client is already on,
-- so a setter that quietly writes nothing cannot pass by accident.
local function probes(found)
  local out = {}
  for _, v in ipairs({0.25, 0.5, 0.75}) do
    if not near(found, v) then out[#out + 1] = v end
  end
  return out[1], out[2]
end

local function run()
  pass, fail, manual = 0, 0, 0
  audio = hafen.client():options():audio()

  local found = audio:uiVolume()
  if type(found) ~= "number" then
    check(false, "run it with the client up: the audio levels read as numbers", found)
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  local p1, p2 = probes(found)

  local ok1, got1 = writelevel(p1)
  check(ok1 and near(got1, p1),
        "uiVolume(v) writes through the synchronized setter and reads back (" .. lvl(p1) .. ")",
        ok1 and lvl(got1) or got1)

  local ok2, got2 = writelevel(p2)
  check(ok2 and near(got2, p2),
        "and the write after it is not dropped (" .. lvl(p2) .. ")",
        ok2 and lvl(got2) or got2)

  refuses("a level above the range is refused, naming the range",
          function() audio:uiVolume(1.5) end, "must be between 0.0 and 1.0")
  refuses("a level below the range is refused, naming the range",
          function() audio:uiVolume(-0.5) end, "must be between 0.0 and 1.0")

  local okr, gotr = writelevel(found)
  check(okr and near(gotr, found),
        "and the level the client was on is back (" .. lvl(found) .. ")",
        okr and lvl(gotr) or gotr)

  manualCheck("run ':session add <an account that is not the one on screen>' and listen while it loads"
              .. " into the world, without switching to it",
              "nothing audible from it at all -- no login blip, no chat ping, no alert")
  manualCheck("then switch to that character and back to this one",
              "it is audible while it holds the screen and silent again the moment it does not")
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t122", run)   -- the only way in: a suite does not start itself
