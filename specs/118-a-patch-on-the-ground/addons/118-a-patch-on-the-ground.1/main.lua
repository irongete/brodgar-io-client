-- 118.1 -- a patch lies on the ground. Self-checking suite.
--
-- It declares NO permissions: every call below succeeding is what unprotected means here. A patch has no
-- server id, never reaches the wire and grants nothing.

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

-- A refusal is a check: the call must fail, and fail SAYING why. The strip covers both shapes LuaJ writes --
-- "@chunk.lua:12: msg" for a Lua error and "@chunk.lua:12 msg" for one raised across the bridge.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- A square ring of Positions of side 2r, centred on p -- built with p:offset, which is the durable arithmetic
-- a grid boundary makes necessary and the shape gob:hitbox() already hands back one ring at a time.
local function square(p, r)
  return { p:offset(-r, -r), p:offset(r, -r), p:offset(r, r), p:offset(-r, r) }
end

local function run()
  local s = hafen.session():current()
  local pg = s and s:player():gob()
  local here = pg and pg:position()
  if not here then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  local w = s:world()
  local patches = hafen.vr():patch()
  -- The suite owns its own patches, so it starts from nothing however many times it is run.
  for _, old in ipairs(patches:list()) do patches:remove(old) end

  local p1 = patches:add(square(here, 8), here)
  check(p1 ~= nil and p1:exists(), "a ring of Positions and a place lay a patch", p1)
  eq("the collection holds exactly it", patches:count(), 1)
  check(patches:find(function(x) return x == p1 end) == p1, "and :find reaches it")
  local held = patches:list()
  check((#held == 1) and (held[1] == p1), "and :list is that one patch", #held)
  local info = p1:info()
  check((info.kind == "patch") and (info.ring ~= nil) and (#info.ring == 4),
        "its :info names the kind and carries the ring", tostring(info.kind) .. "/" .. tostring(info.ring and #info.ring))

  -- A place recorded in another part of the world: the patch is held whole and simply is not drawn. The
  -- ring is built in that same grid, so its points are offsets from the anchor by arithmetic alone.
  local function far(dx, dy) return w:position({ gridId = "1", x = 100 + dx, y = 100 + dy }) end
  local away = far(0, 0)
  local p2 = patches:add({ far(-3, -3), far(3, -3), far(3, 3), far(-3, 3) }, away)
  check(p2 ~= nil and p2:exists(), "a patch at a place this character cannot locate still exists", p2)
  check((away:x() == nil) and (not p2:drawn()),
        "and is not drawn: it waits whole rather than drawing part of itself", p2 and p2:drawn())
  patches:remove(p2)
  check((not p2:exists()) and (patches:count() == 1), "the collection ends one it placed", patches:count())

  refuses("two points are refused naming the three-point minimum",
          function() patches:add({ here:offset(-4, -4), here:offset(4, -4) }, here) end,
          "at least three points")
  refuses("a concave ring is refused naming convex",
          function()
            patches:add({ here:offset(0, 0), here:offset(8, 4), here:offset(0, 8), here:offset(2, 4) }, here)
          end, "convex")
  refuses("a ring over the edge limit is refused naming that number",
          function()
            local big = {}
            for i = 0, 39 do
              local t = (i * math.pi * 2) / 40
              big[i + 1] = here:offset(6 * math.cos(t), 6 * math.sin(t))
            end
            patches:add(big, here)
          end, "at most 32")
  refuses("a Gob anchor is refused naming the Position a patch is laid at",
          function() patches:add(square(here, 4), pg) end, "laid at a Position")

  -- Drawn is the world's answer, so it is asked on a bounded window rather than once: a patch enters the
  -- scene when the ground under it has resolved there, and that is a cut arriving, not a call returning.
  local tries, tm = 0, nil
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    if p1:drawn() or (tries >= 12) then
      tm:cancel()
      check(p1:drawn(), "the patch is drawn once its ground has resolved", p1:drawn())
      manualCheck("stand on a slope with the patch under you, zooming in and out",
                  "a white square 16 units across in the ring's own shape, edge crisp and not a staircase of"
                  .. " square tiles, lying flush on the slope with no gap and no flicker at any zoom")
      summary()
    end
  end)
end

hafen.console():on("t118", run)   -- the only way in: a suite does not start itself
