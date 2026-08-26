-- 109.4 — the base moving is the event. Self-checking suite.
-- Type :t109 in the world. One character is enough; nothing here needs a permission.
--
-- The notice that a session's coordinate space moved now hangs on that session's proved BASE rather than
-- on the minimap's session location, which moves one edge earlier. Two things have to hold. The pass that
-- re-homes everything standing in the world must still be an EVENT -- the base is derived every frame, so
-- hanging the notice on it is exactly where a poll would come from -- and a thing standing at a place this
-- character cannot locate must wait without losing anything, which is the state the re-base window puts
-- every one of them into.

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

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local WARM = 1.0       -- let the visual stream in and the two creates drain before counting
local STILL = 1.5      -- seconds of standing still, the window the passes below are counted over
-- What a per-frame notice would cost across that window, taken at the slowest frame rate anyone plays at:
-- 30 fps is 45 passes, and 60 is ninety. The bar is well under half of the cheapest possible poll, so the
-- handful of passes the terrain's own cut changes cost while you stand cannot reach it either way.
local POLL = 20
local RES = "gfx/terobjs/arch/logcabin"

-- A durable place no character has ever been: a grid id the server never minted. It is a legal durable
-- form -- the shape :info() hands out -- and it is the one way to reach the cannot-locate state without
-- walking into a cave, which is what the manual step is for.
local FAR = { gridId = "109000000000000004", x = 550, y = 550 }

local function counters()
  local ok, e = pcall(function() return hafen.client():profiling():entities() end)
  return ok and e or nil
end

local st = {}

local function score()
  local after = counters()
  local e = st.entity

  -- 1. the pass is an EVENT, not a frame. The base it now hangs on is derived every tick, so a notice
  --    fired on the derivation rather than on the change would show up here as a pass per frame.
  local climbed = after and st.before and (after.passes - st.before.passes)
  check((climbed ~= nil) and (climbed < POLL),
        ("the pass runs on an edge, not on the frame: %s passes across %ss of standing still")
          :format(tostring(climbed), tostring(STILL)),
        (climbed == nil) and "entities() answered no passes"
          or (climbed .. " passes -- at or past the " .. POLL .. " a per-frame notice would cost"))

  -- 2. the thing standing at a place this character cannot locate is counted as waiting, and it has lost
  --    nothing: it exists, it reports the very place it was given, and it says it is not drawn.
  check((after ~= nil) and (after.waiting ~= nil) and (after.waiting >= 1),
        "one standing at a place this character cannot locate is counted as waiting",
        after and tostring(after.waiting) or "no counters")
  check(e:exists() and (e:drawn() == false),
        "it exists and says it is not drawn, rather than being lost",
        ("exists=%s drawn=%s"):format(tostring(e:exists()), tostring(e:drawn())))
  local info = e:position():info()
  check(info and (info.gridId == FAR.gridId) and (info.x == FAR.x) and (info.y == FAR.y),
        "its durable place reads back unchanged while it waits",
        info and (info.gridId .. "@" .. tostring(info.x) .. "," .. tostring(info.y)) or "no durable form")
  check(e:position():x() == nil,
        "and the coordinate this character would have for it is nil, not a number",
        tostring(e:position():x()))

  -- 3. a place under the character's own feet resolves and IS drawn -- the same pass, the other verdict,
  --    so a run in which nothing could ever be drawn cannot pass check 2 by accident.
  local near = st.near
  check((near ~= nil) and near:drawn(),
        "one standing where the character actually is comes out drawn",
        (near == nil) and "no durable place under the character" or tostring(near and near:drawn()))

  hafen.vr():entity():remove(e)
  if near then hafen.vr():entity():remove(near) end
  check(not e:exists(), "the suite leaves nothing standing behind", "it is still there")

  manualCheck("walk into a house and straight back out, and then stand still",
              "the world comes back by itself within a second of stepping out, without you moving a"
              .. " step: that is the re-based frame being proved and waking this pass")
  report()
end

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  local me = s and s:player():gob()
  if not me then
    check(false, "a character is in the world", "none -- run :t109 in the world")
    return report()
  end
  local before = counters()
  if not (before and before.passes) then
    check(false, "the entity counters answer", "no passes key -- is this build the one just made?")
    return report()
  end

  -- Stand one at the unreachable place and one under the character's own feet: the pass has to give a
  -- different verdict for each, and both are asked of the same run.
  local far = s:world():position(FAR)
  local okfar, entity = pcall(function() return hafen.vr():ghost():add(RES, far) end)
  if not okfar then
    check(false, "standing one at a place this character cannot locate is legal", tostring(entity))
    return report()
  end
  local oknear, near = pcall(function() return hafen.vr():ghost():add(RES, me:position()) end)
  st = { entity = entity, near = oknear and near or nil }
  -- A beat before the window opens, for two reasons that want the same wait: a ghost is not drawn while
  -- its visual is still streaming in, and standing one raises the pass's own flag, so the passes those
  -- two creates cost belong before the count and not inside it.
  hafen.timer():after(WARM, function()
    st.before = counters()
    hafen.timer():after(STILL, score)
  end)
end

hafen.console():on("t109", run)   -- the only way in: a suite does not start itself
