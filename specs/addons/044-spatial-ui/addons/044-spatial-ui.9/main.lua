-- 044.9 -- a free entity stops drawing over ground that has unloaded. Self-checking suite; see
-- specs/addons/TESTING.md and specs/addons/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. Anything hafen.vr() stands at a POINT is in the scene only while the ground under it
-- is drawn. Walk away and the terrain cuts off, and until now the thing kept drawing, hanging over the void
-- until it left the screen; walk back and it was still there. Nothing was broken: a client-only gob is in no
-- OCache, which is the very premise an ANCHORED entity's death rests on, so nothing removed it and nothing
-- hid it either. Now the ground itself answers -- the terrain's own per-cut map, which holds a cut exactly
-- while that cut's mesh is in the scene, so "is the ground under it drawn" is asked of the structure that
-- draws the ground and there is no second rule to drift out of step with what you can see.
--
-- HIDDEN, NOT ENDED, AND NOT A POLICY. The entity keeps its handle, its place, its look and its :exists();
-- only its scene slot goes, and it comes back by itself when the ground does. Ending it would lose an addon's
-- placement the first time the player walked away, and would make :exists() a function of where the camera
-- has been. It is also not a switch: drawing over ground that is not there is never what an addon wanted, so
-- there is nothing to opt into. That leaves :visible() meaning exactly what it always meant -- what YOU told
-- it -- and the new read, <entity>:drawn(), meaning what the world is doing.
--
-- IT REACHES ALL FOUR KINDS, because it lands on the shared entity core: a ghost, a sprite, an object and a
-- standing widget are one placement engine with four visuals. An ANCHORED entity is deliberately untouched --
-- its place is its gob's, so it behaves exactly like the gob it stands on and ends with it (D-102).
--
-- HOW IT PROVES IT WITHOUT ASKING YOU TO WALK. The drawn terrain reaches at most about 75 tiles from you, so
-- the suite stands one of each kind 3 tiles away (drawn ground, by definition -- you are standing on it) and
-- another of each well past the drawn terrain (since 045.1: the nearest ground out there this character has
-- actually walked, so the place is durable and merely not drawn), and asks each one. Then it moves one of the
-- far ones onto your own ground and back off it, which is the same re-check the terrain streaming in and out
-- drives, reached through the other door.
--
-- IT LEAVES THE NEAR GHOST AND THE NEAR PANEL STANDING for the [manual] walk, and takes them down on
-- ':t044-9 off'. ':t044-9' at any time starts over from an empty scene.
--
-- READ-ONLY: no permissions, no persistent state, and every widget it stands is one it built itself.

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local T = 11                              -- world units per tile
local RES = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)
local NEAR = 3 * T                        -- ground you are standing on

-- (Edited by 045.1, stated in its tasks.md rather than discovered.) The far set used to be a flat
-- p:offset(400*T, 400*T) -- a raw coordinate over ground nobody has recorded, which since 045.1 is refused at
-- :add and at :position(p): what a free entity holds is a DURABLE place, and that point has none. What this
-- task needs of it is unchanged -- ground well past the ~75 tiles the terrain draws -- so it now asks for the
-- nearest place out there that this character HAS walked: durable, and simply not drawn.
local function farPlace(p)
  local dirs = { {1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {-1,1}, {1,-1}, {-1,-1} }
  for _, d in ipairs({ 120, 160, 200, 260, 320, 400 }) do
    for _, v in ipairs(dirs) do
      local q = p:offset(v[1] * d * T, v[2] * d * T)
      if q:durable() then return q end
    end
  end
  return nil
end

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local S                                   -- everything a run is holding

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

local function counters() return hafen.client():profiling():surfaces() end

local KINDS = { "ghost", "sprite", "object", "widget" }

local function coll(k)
  if k == "ghost" then return hafen.vr():ghost() end
  if k == "sprite" then return hafen.vr():sprite() end
  if k == "object" then return hafen.vr():object() end
  return hafen.vr():widget()
end

-- Stand one of each kind around `p`. `built` collects the widgets so the cleanup can destroy them: an entity
-- ending puts its widget back where it stood from, it never destroys it.
local function standAll(p, built)
  local w = hafen.ui():window():title("044.9"):size(150, 56)
  w:on("Draw", function(ev) ev:g():text("044.9", 8, 10) end)
  built[#built + 1] = w
  return {
    ghost  = hafen.vr():ghost():add(RES, p),
    sprite = hafen.vr():sprite():add(icon, p:offset(0, 2 * T)),
    object = hafen.vr():object():add(mesh, p:offset(0, -2 * T)),
    widget = hafen.vr():widget():add(w, p:offset(2 * T, 0)):facing("camera"),
    w      = w,
  }
end

-- Which of the four are in the scene right now -- as two word lists, so a [fail] line names the kind that
-- disagreed rather than saying "false".
local function drawnList(set)
  local yes, no = {}, {}
  for _, k in ipairs(KINDS) do
    local e = set[k]
    if (e ~= nil) and e:drawn() then yes[#yes + 1] = k else no[#no + 1] = k end
  end
  return ((#yes == 0) and "none" or table.concat(yes, " ")),
         ((#no == 0) and "none" or table.concat(no, " "))
end

local function allDrawn(set)
  for _, k in ipairs(KINDS) do
    if (set[k] == nil) or (not set[k]:drawn()) then return false end
  end
  return true
end

local function noneDrawn(set)
  for _, k in ipairs(KINDS) do
    if (set[k] == nil) or set[k]:drawn() then return false end
  end
  return true
end

local function allExist(set)
  for _, k in ipairs(KINDS) do
    if (set[k] == nil) or (not set[k]:exists()) then return false end
  end
  return true
end

-- Take down every entity this suite stood, and destroy every widget it built.
local function clear()
  for _, k in ipairs(KINDS) do
    local c = coll(k)
    for _, e in ipairs(c:list()) do c:remove(e) end
  end
  if S then
    for _, w in ipairs(S.built) do
      if w and w:exists() then w:destroy() end
    end
  end
  S = nil
end

local phase2, phase3, offRound

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  clear()
  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands things in the 3D scene)", tostring(okp and me))
    return summary()
  end
  if (icon == nil) or (mesh == nil) then
    check(false, "the suite's own image and model loaded (hafen.asset)", "icon or mesh missing")
    return summary()
  end

  S = { built = {}, live0 = counters().live }   -- what the rest of the client is holding, before we add ours
  local p = me:position()
  S.p = p
  S.farp = farPlace(p)
  if S.farp == nil then
    check(false, "this character has recorded ground 120-400 tiles out in at least one of eight directions,"
          .. " which is what a place past the drawn terrain is made of since 045.1",
          "nothing durable found out there -- walk a few screens from here and run ':t044-9' again")
    return summary()
  end
  S.near = standAll(p:offset(NEAR, 0), S.built)
  S.far = standAll(S.farp, S.built)
  -- ...and one ANCHORED to you, which the rule must not touch: its place is a gob's, so it lives and dies
  -- exactly as that gob does (D-102) and the ground under it is never a question of its own.
  local aw = hafen.ui():widget():size(40, 20)
  S.built[#S.built + 1] = aw
  S.anch, S.anchW = hafen.vr():widget():add(aw, me), aw
  hafen.timer():after(1.5, function() phase2() end)   -- a ghost's visual streams in on a loader thread
end

phase2 = function()
  if S == nil then return end
  local nd, nn = drawnList(S.near)
  check(allDrawn(S.near),
        "all four kinds stand on ground that IS drawn and are in the scene: a ghost, a sprite, an object and"
        .. " a standing widget, three tiles from where you are. One placement engine with four visuals, so"
        .. " the rule lands on the shared entity core and reaches every one of them rather than the one kind"
        .. " it was noticed on",
        ("drawn: %s -- not drawn: %s"):format(nd, nn))

  local fd, fn = drawnList(S.far)
  check(noneDrawn(S.far) and allExist(S.far),
        "...and all four placed well past the drawn terrain, over ground that is not being drawn, are NOT in the"
        .. " scene -- while"
        .. " every one of them still :exists(). That is the whole change: a client-only gob is in no OCache,"
        .. " so nothing ever removed it and it went on drawing over the void; now the terrain's own cut map"
        .. " answers, and a thing with no ground under it is simply not drawn",
        ("drawn: %s -- not drawn: %s; all exist=%s"):format(fd, fn, tostring(allExist(S.far))))

  check(S.far.ghost:visible() and S.far.widget:visible() and (coll("ghost"):count() == 2)
        and (coll("widget"):count() == 3) and (#hafen.vr():list() == 9),
        "and none of it touched a thing the addon wrote: :visible() still reads back the true it was given,"
        .. " the collections still count every one, and hafen.vr():list() still holds all nine. Being out of"
        .. " the scene is the world's answer, never a write over yours -- so walking away cannot quietly"
        .. " change what your addon reads back",
        ("far ghost visible=%s far widget visible=%s ghosts=%d widgets=%d vr:list=%d"):format(
          tostring(S.far.ghost:visible()), tostring(S.far.widget:visible()), coll("ghost"):count(),
          coll("widget"):count(), #hafen.vr():list()))

  -- The standing widget's own machinery agrees, and it is the one kind that can be asked twice: 044.4's
  -- corner map is recorded by the visual every frame the quad is drawn, so a panel that is not in the scene
  -- has no corners at all -- and 044.7's culled counter reads that very clock.
  local sx = S.far.widget:screen(0, 0)
  local nx = S.near.widget:screen(0, 0)
  local ct = counters()
  check((sx == nil) and (nx ~= nil) and ((ct.live - S.live0) == 3) and (ct.culled >= 1),
        "the standing widget agrees through its own machinery: the panel with no ground under it projects no"
        .. " corners at all (widget:screen(x, y) answers nil), which is the same clock 044.7's culling reads,"
        .. " so it counts among the culled -- while its surface is still LIVE and the widget on it still"
        .. " alive. Not drawn is not destroyed",
        ("far :screen()=%s near :screen()=%s live=%d (was %d) culled=%d"):format(tostring(sx), tostring(nx),
          ct.live, S.live0, ct.culled))

  check(S.anch:drawn() and S.anch:exists(),
        "an ANCHORED entity is untouched by the rule: standing on your own gob it is drawn, because its place"
        .. " is that gob's and not a point of its own -- so it behaves exactly like the object it stands on"
        .. " and ends with it (D-102), which is the death a free entity never had and still does not need",
        ("drawn=%s exists=%s"):format(tostring(S.anch:drawn()), tostring(S.anch:exists())))

  refuses("<entity>:drawn() is a READ and refuses to be written, naming what to write instead",
          function() S.near.ghost:drawn(false) end, "does not write it")

  -- The two switches that DO belong to the addon, over a thing with no ground under it.
  S.far.ghost:visible(false)
  S.far.ghost:visible(true)
  check((not S.far.ghost:drawn()) and S.far.ghost:visible(),
        "your own :visible(false) then :visible(true) over ground that is not there is remembered and does"
        .. " not resurrect it: the two answers are independent booleans, ANDed, so what you wrote is what"
        .. " comes back the moment the ground does -- never sooner, and never lost",
        ("drawn=%s visible=%s"):format(tostring(S.far.ghost:drawn()), tostring(S.far.ghost:visible())))

  hafen.vr():visible(false)
  local offd = allDrawn(S.near) or S.anch:drawn()
  hafen.vr():visible(true)
  check((not offd) and allDrawn(S.near) and S.anch:drawn() and noneDrawn(S.far),
        "hafen.vr():visible(false) takes the whole section off screen and hafen.vr():visible(true) restores"
        .. " exactly what was visible -- the near four and the anchored one come back, the far four do not."
        .. " Three booleans, one AND, and the section switch overwrites neither of the other two (043.4)",
        ("anything drawn while off=%s; back: near=%s anchored=%s far still out=%s"):format(tostring(offd),
          tostring(allDrawn(S.near)), tostring(S.anch:drawn()), tostring(noneDrawn(S.far))))

  -- ...and now move one of the far ones onto your own ground. That is the same re-check the terrain drives
  -- when a cut streams in, reached through the other door -- which leaves the walk itself, and only the
  -- walk, for a human.
  S.far.sprite:position(S.p:offset(0, NEAR))
  hafen.timer():after(0.6, function() phase3() end)
end

phase3 = function()
  if S == nil then return end
  check(S.far.sprite:drawn(),
        "moving one of them onto ground that IS drawn puts it straight into the scene with nothing else done"
        .. " to it -- the ground question is re-asked by the write itself, exactly as the terrain streaming"
        .. " back in re-asks it",
        ("drawn=%s exists=%s visible=%s"):format(tostring(S.far.sprite:drawn()),
          tostring(S.far.sprite:exists()), tostring(S.far.sprite:visible())))

  S.far.sprite:position(S.farp)
  check(not S.far.sprite:drawn(),
        "...and putting it back out over nothing takes it out again, so the two directions are one rule and"
        .. " not a one-way trip: a free entity is in the scene exactly while the ground under it is drawn",
        ("drawn=%s"):format(tostring(S.far.sprite:drawn())))

  -- Leave the near ghost and the near panel standing for the walk; take down and destroy everything else, so
  -- nothing of this suite is left on the flat UI while the maintainer walks.
  for _, k in ipairs(KINDS) do
    local c = coll(k)
    for _, e in ipairs(c:list()) do
      if (e ~= S.near.ghost) and (e ~= S.near.widget) then c:remove(e) end
    end
  end
  for _, w in ipairs(S.built) do
    if (w ~= S.near.w) and w:exists() then w:destroy() end
  end

  manualCheck("a log cabin and a '044.9' panel are standing three tiles from where you were. Walk away in a"
              .. " straight line until the ground they stand on has cut off well behind the edge of the drawn"
              .. " terrain (about two screens), look back at where they were, then walk back to them. Finish"
              .. " with ':t044-9 off'",
              "they are GONE while their ground is gone -- nothing hanging in the void past the edge of the"
              .. " terrain -- and both are back, whole and in the same place, the moment the ground under"
              .. " them is drawn again")
  manualCheck("then run ':reload' and ':t044-9' once more, and paste back that run's [summary] line",
              "the same counts as this run -- ':reload' having left nothing of the previous one standing")
  summary()
end

-- ':t044-9 off' takes down what the run left standing for the [manual] walk.
offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-9' has been run first -- this round takes down what THAT one left standing",
          "nothing of this suite is standing")
    return summary()
  end
  local w, live0 = S.near.w, S.live0
  clear()
  local ct = counters()
  check((ct.live == live0) and (#hafen.vr():list() == 0) and (not w:exists()),
        "nothing of this suite is left standing: hafen.vr():list() is empty across all four kinds and the"
        .. " live-surface count is back where the run found it, so every texture it allocated has been freed",
        ("live=%d (was %d before the run) vr:list=%d panel destroyed=%s"):format(ct.live, live0,
          #hafen.vr():list(), tostring(not w:exists())))
  summary()
end

hafen.slash():register("t044-9", run)   -- the only way in: a suite does not start itself
