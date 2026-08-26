-- 113.2 — the ground an object stands on. Self-checking suite.
-- Type :t113 in the world, standing where at least one gob with a collision footprint is in view
-- (a tree, a fence, a wall, a building -- something you cannot walk through). The suite then paints
-- every visible hitbox on the HUD for twenty seconds.

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

-- A refusal is a check too: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- At least one polygon of at least three Positions, each answering p:x() -- the whole shape
-- gob:hitbox() promises.
local function shapeOk(hb)
  if (type(hb) ~= "table") or (#hb < 1) then return false end
  for _, poly in ipairs(hb) do
    if (type(poly) ~= "table") or (#poly < 3) then return false end
    for _, p in ipairs(poly) do
      if p:x() == nil then return false end
    end
  end
  return true
end

-- Every point within BOUND world units of the object's OWN position -- proves it sits on the
-- object rather than at the frame's origin, which a wrong translation would answer instead.
local BOUND = 200
local function onObject(g, hb)
  local gp = g:position()
  if not gp then return false end
  local gx, gy = gp:x(), gp:y()
  for _, poly in ipairs(hb) do
    for _, p in ipairs(poly) do
      local dx, dy = p:x() - gx, p:y() - gy
      if math.sqrt((dx * dx) + (dy * dy)) > BOUND then return false end
    end
  end
  return true
end

-- hb and hb2 are the same shape, point for point, within floating slop.
local function sameShape(hb, hb2)
  if (not hb) or (not hb2) or (#hb ~= #hb2) then return false end
  for i, poly in ipairs(hb) do
    local poly2 = hb2[i]
    if (not poly2) or (#poly ~= #poly2) then return false end
    for o, p in ipairs(poly) do
      local p2 = poly2[o]
      if (math.abs(p:x() - p2:x()) > 0.01) or (math.abs(p:y() - p2:y()) > 0.01) then return false end
    end
  end
  return true
end

-- Paint every visible hitbox on the HUD -- outlines only, so it never hides what is under them.
local function paintHitboxes()
  hafen.ui():overlay():add("t113-hitbox"):draw(function(g)
    local cur = hafen.session():current()
    if not cur then return end
    g:color(80, 220, 255)
    for _, gob in ipairs(cur:world():gob():list()) do
      local hb = gob:hitbox()
      if hb then
        for _, poly in ipairs(hb) do
          local n = #poly
          for i = 1, n do
            local pa = cur:world():worldToScreen(poly[i])
            local pb = cur:world():worldToScreen(poly[(i % n) + 1])
            if pa and pb then g:line(pa.x, pa.y, pb.x, pb.y, 2) end
          end
        end
      end
    end
    g:color()
  end)
  hafen.timer():after(20, function() hafen.ui():overlay():remove("t113-hitbox") end)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t113 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t113 in the world")
    return report()
  end

  -- The gone case, without waiting for a despawn: an id nothing has ever loaded.
  local ghost = s:world():gob():get(1)
  check((ghost:hitbox() == nil) and (not ghost:exists()),
        "an id nothing has loaded answers nil from :hitbox(), and :exists() is false",
        "hitbox=" .. tostring(ghost:hitbox()) .. " exists=" .. tostring(ghost:exists()))

  -- Arity: the verb takes no argument, and the refusal names the rule it broke.
  refuses("gob:hitbox(1) raises, naming arity", function() ghost:hitbox(1) end, "arity")

  -- Everything else needs a resource-drawn gob with a collision footprint the server actually sent
  -- -- retried over a bounded window and scored over what the run reached, per a receiver only the
  -- server gives.
  local WAIT, TRIES, tries = 0.5, 10, 0

  local function attempt()
    tries = tries + 1
    local found, shapeBad, onBad, screenOk = nil, {}, {}, false
    for _, g in ipairs(s:world():gob():list()) do
      local hb = g:hitbox()
      if hb then
        found = found or g
        local ok = shapeOk(hb)
        if not ok then
          shapeBad[#shapeBad + 1] = g:id()
        elseif not onObject(g, hb) then
          onBad[#onBad + 1] = g:id()
        end
        if not screenOk then
          local pt = hb[1] and hb[1][1]
          screenOk = (pt ~= nil) and (s:world():worldToScreen(pt) ~= nil)
        end
      end
    end

    if found or (tries >= TRIES) then
      check(found ~= nil, "at least one visible gob answers a hitbox",
            "none of the visible gobs answered after " .. tries .. " tries")
      if found then
        check(#shapeBad == 0, "every answering hitbox is at least one polygon of at least three Positions",
              table.concat(shapeBad, ", "))
        check(#onBad == 0, "every point sits on its own object rather than at the frame's origin",
              table.concat(onBad, ", "))
        check(screenOk, "s:world():worldToScreen(pt) answers a screen point for one of them",
              "worldToScreen answered nil for the first point tried")

        local hbBefore = found:hitbox()
        check(found:info().hitbox == nil, "gob:info() carries no hitbox field",
              tostring(found:info().hitbox))

        found:scale(3)
        check(sameShape(hbBefore, found:hitbox()),
              "gob:scale(3) leaves the hitbox unchanged -- the drawn size is not the footprint",
              "the hitbox changed under scale(3)")
        found:scale(1)
        check(sameShape(hbBefore, found:hitbox()),
              "gob:scale(1) puts the object back, and its hitbox with it",
              "the hitbox differs from the original after scale(1)")
      end

      paintHitboxes()
      manualCheck("watch the HUD for twenty seconds, standing where a gate, a wall segment or a"
                  .. " cupboard is in view",
                  "the outlines hug the objects' bases, and the one on the asymmetric object turns"
                  .. " the way that object faces")
      report()
    else
      hafen.timer():after(WAIT, attempt)
    end
  end
  attempt()
end

hafen.console():on("t113", run)   -- the only way in: a suite does not start itself
