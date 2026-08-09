-- 045.1 -- a free entity holds its DURABLE place, and a place with no durable form is refused. Self-checking
-- suite; see specs/addons/TESTING.md and specs/addons/045-durable-places/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. A thing you stand at a point should be at that point tomorrow. Until now it was not:
-- a free hafen.vr() entity kept the SESSION coordinate it was handed, and that coordinate space is re-based
-- every time the server drops the map -- walk into a cave or a house and the same numbers name different
-- ground -- so the thing went on holding a number that had quietly stopped meaning anywhere. Now what the
-- entity holds is the ANCHOR: the server's own grid id plus the offset inside that grid, which is the form a
-- place survives in. The session coordinate becomes a cache derived from it. Both forms already live in one
-- Position type (039), so nothing new is invented; what changed is which of the two the entity keeps.
--
-- SO THE TWO READS SPLIT, AND THAT ASYMMETRY IS THE CONTRACT. <entity>:position():info() is the place -- it
-- reads the same before and after -- while :x()/:y() are only ever this session's answer to "where is that
-- right now", free to differ and free to be nil. This suite pins the first half: what an entity hands back is
-- exactly the place it was given, through all four kinds, and a place rebuilt from :info() stands identically.
--
-- AND A PLACE WITH NO DURABLE FORM IS NOW REFUSED, at :add and at :position(p) alike. A raw coordinate over
-- ground no client has ever recorded cannot be held by anything: the client cannot invent a grid id for it.
-- Accepting it is what produced an entity pinned to a number that would lie, so it is a hard refusal that
-- names why, not a warning.
--
-- WHAT IT DOES NOT COVER. The walk itself -- into a cave and back, with the place surviving it -- is 045.2's,
-- which also makes a place this session cannot locate legal instead of refused. Here that case still raises.
--
-- READ-ONLY: no permissions, no persistent state, and every widget it stands is one it built itself.

local pass, fail = 0, 0

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

local T = 11                              -- world units per tile
local RES = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)
local LOST = 8000 * T                     -- ground no character has ever walked: no grid id exists for it

local KINDS = { "ghost", "sprite", "object", "widget" }

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local S                                   -- everything a run is holding

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

local function coll(k)
  if k == "ghost" then return hafen.vr():ghost() end
  if k == "sprite" then return hafen.vr():sprite() end
  if k == "object" then return hafen.vr():object() end
  return hafen.vr():widget()
end

-- Do two durable forms name the same place? gridId is a 64-bit id as a decimal string; the offsets are world
-- units within that grid, so "the same" is the same grid and the same offset to well under a tile.
local function sameInfo(a, b, tol)
  return (a ~= nil) and (b ~= nil) and (a.gridId == b.gridId)
     and (math.abs(a.x - b.x) < tol) and (math.abs(a.y - b.y) < tol)
end

local function infoStr(i)
  if i == nil then return "nil" end
  return ("%s@%.2f,%.2f"):format(tostring(i.gridId), i.x, i.y)
end

-- A place well beyond the drawn terrain (which reaches ~75 tiles) that this character HAS recorded, so it is
-- durable and merely not drawn -- the state 044.9's rule is about, and the only one of the two far cases this
-- task still accepts. Probed outward because which ground a character has walked is its own business.
local function farRecorded(p)
  local dirs = { {1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {-1,1}, {1,-1}, {-1,-1} }
  for _, d in ipairs({ 120, 160, 200, 260, 320, 400 }) do
    for _, v in ipairs(dirs) do
      local q = p:offset(v[1] * d * T, v[2] * d * T)
      if q:durable() then return q, d end
    end
  end
  return nil, 0
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

local function panel(title)
  local w = hafen.ui():window():title(title):size(150, 56)
  w:on("Draw", function(ev) ev:g():text(title, 8, 10) end)
  S.built[#S.built + 1] = w
  return w
end

local phase2, phase3

local function run()
  pass, fail = 0, 0                        -- a re-run reports its own counts, not the last one's

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
  S = { built = {} }

  local p = me:position()
  local pinfo = p:info()
  check(p:durable() and (pinfo ~= nil) and (pinfo.gridId ~= nil),
        "the premise, stated where it can fail: the ground you are standing on has a durable form -- a"
        .. " server-published grid id and an offset inside that grid, which is what a place has to be reduced"
        .. " to before anything can hold it across the map being dropped",
        ("durable=%s info=%s"):format(tostring(p:durable()), infoStr(pinfo)))

  -- One of each kind, each at its own point a couple of tiles apart, so a [fail] names the kind that
  -- disagreed. The shared entity core is what this lands on, so all four must answer the same way.
  local at = {
    ghost  = p,
    sprite = p:offset(0, 2 * T),
    object = p:offset(0, -2 * T),
    widget = p:offset(2 * T, 0),
  }
  S.e = {
    ghost  = hafen.vr():ghost():add(RES, at.ghost),
    sprite = hafen.vr():sprite():add(icon, at.sprite),
    object = hafen.vr():object():add(mesh, at.object),
    widget = hafen.vr():widget():add(panel("045.1"), at.widget):facing("camera"),
  }
  S.p, S.at = p, at

  local bad, held = nil, {}
  for _, k in ipairs(KINDS) do
    local got = S.e[k]:position()
    held[k] = got:info()
    if not (sameInfo(held[k], at[k]:info(), 0.001) and (type(got:x()) == "number")) then
      bad = bad or k
    end
  end
  check(bad == nil,
        "all four kinds hand back the PLACE they were given, not a coordinate: a ghost, a sprite, an object"
        .. " and a standing widget each read :position():info() as the very grid id and offset the Position"
        .. " they were placed with names -- one placement engine with four visuals, so what it holds lands on"
        .. " the shared core and reaches every one of them",
        ("first disagreement: %s (held %s, placed at %s)"):format(tostring(bad),
          infoStr(bad and held[bad]), infoStr(bad and at[bad]:info())))

  check(sameInfo(held.ghost, pinfo, T) and (type(S.e.ghost:position():x()) == "number"),
        "...and the one stood at your own feet reads the grid YOU are standing on, offset to the same tile,"
        .. " while :x() is still a plain number: the durable form is what it keeps, the session coordinate is"
        .. " what it derives, and both are answered by the one Position type",
        ("ghost %s vs player %s, x=%s"):format(infoStr(held.ghost), infoStr(pinfo),
          tostring(S.e.ghost:position():x())))

  -- Round-trip: through the door a saved place comes back in by, which is the whole point of holding one.
  local rebuilt = hafen.world():position(S.e.ghost:position():info())
  S.e.twin = hafen.vr():ghost():add(RES, rebuilt)
  check(sameInfo(S.e.twin:position():info(), held.ghost, 0.001)
        and (math.abs(S.e.twin:position():x() - S.e.ghost:position():x()) < 0.001),
        "a place taken out through :info() and rebuilt with hafen.world():position(saved) stands the next one"
        .. " in exactly the same spot -- same grid, same offset, and the same session coordinate derived back"
        .. " out of it. The two directions of the anchor agree, which is what makes a stored place a place",
        ("rebuilt %s x=%s vs original %s x=%s"):format(infoStr(S.e.twin:position():info()),
          tostring(S.e.twin:position():x()), infoStr(held.ghost), tostring(S.e.ghost:position():x())))

  -- The hard cut: a raw coordinate over ground nobody has recorded has no durable form at all.
  local lost = p:offset(LOST, LOST)
  check((not lost:durable()) and (lost:info() == nil) and (type(lost:x()) == "number"),
        "the premise of the refusal below: a point 8000 tiles out is a perfectly good session coordinate and"
        .. " has NO durable form -- p:durable() is false and p:info() is nil, because no client has ever"
        .. " recorded that ground and a grid id cannot be invented for it",
        ("durable=%s info=%s x=%s"):format(tostring(lost:durable()), infoStr(lost:info()),
          tostring(lost:x())))

  refuses("standing a thing at a place with no durable form is refused, naming why: it would be pinned to a"
          .. " session number that stops meaning anywhere the moment the map is dropped",
          function() hafen.vr():ghost():add(RES, lost) end, "no durable form")
  refuses("...and moving one there is refused by the same words, so the two doors that hold a place answer"
          .. " alike and a thing already standing cannot be walked onto ground it cannot be held on",
          function() S.e.ghost:position(lost) end, "no durable form")
  check(sameInfo(S.e.ghost:position():info(), held.ghost, 0.001),
        "...and the refused move left it exactly where it was: a refusal writes nothing",
        infoStr(S.e.ghost:position():info()))

  -- An ANCHORED entity has no place of its own and is untouched by all of it (its place is its gob's).
  local aw = hafen.ui():widget():size(40, 20)
  S.built[#S.built + 1] = aw
  S.anch = hafen.vr():widget():add(aw, me)
  check((type(S.anch:position():x()) == "number") and S.anch:exists(),
        "a gob-anchored entity is untouched: it holds no place of its own -- its place is the gob's, so"
        .. " :position() still answers the live world coordinate that gob is at",
        ("x=%s exists=%s"):format(tostring(S.anch:position():x()), tostring(S.anch:exists())))
  refuses("...and its :position(p) is still refused for the reason it always was -- it follows a gob -- and"
          .. " not for anything this task changed",
          function() S.anch:position(p) end, "follows a gob")

  hafen.timer():after(1.5, function() phase2() end)   -- a ghost's visual streams in on a loader thread
end

phase2 = function()
  if S == nil then return end
  local out = {}
  for _, k in ipairs(KINDS) do
    if not S.e[k]:drawn() then out[#out + 1] = k end
  end
  check((#out == 0) and S.anch:drawn(),
        "044.9's rule is unchanged with the place underneath it swapped: all four standing on ground that IS"
        .. " drawn are in the scene, and so is the anchored one",
        ("not drawn: %s; anchored drawn=%s"):format(
          ((#out == 0) and "none" or table.concat(out, " ")), tostring(S.anch:drawn())))

  local far, dist = farRecorded(S.p)
  if far == nil then
    check(false, "this character has recorded ground 120-400 tiles away in at least one of eight directions,"
          .. " which is what a durable place that is NOT drawn is made of",
          "nothing durable found out there -- walk a few screens from here and run :t045-1 again")
    return phase3()
  end
  S.far, S.dist = far, dist
  S.e.sprite:position(far)
  check((not S.e.sprite:drawn()) and S.e.sprite:exists()
        and sameInfo(S.e.sprite:position():info(), far:info(), 0.001),
        ("moved onto ground this character HAS walked but is %d tiles away, it is out of the scene and still"):format(dist)
        .. " holds that place exactly -- not drawn is not lost, which is the state the whole feature is about:"
        .. " the place is kept while the ground under it is somewhere else",
        ("drawn=%s exists=%s info=%s (wanted %s)"):format(tostring(S.e.sprite:drawn()),
          tostring(S.e.sprite:exists()), infoStr(S.e.sprite:position():info()), infoStr(far:info())))

  S.e.sprite:position(S.at.sprite)
  hafen.timer():after(0.6, function() phase3() end)
end

phase3 = function()
  if S == nil then return end
  if S.far ~= nil then
    check(S.e.sprite:drawn() and sameInfo(S.e.sprite:position():info(), S.at.sprite:info(), 0.001),
          "and bringing it back to drawn ground puts it straight into the scene holding the new place -- the"
          .. " two directions are one rule, with the anchor written and read on both",
          ("drawn=%s info=%s"):format(tostring(S.e.sprite:drawn()),
            infoStr(S.e.sprite:position():info())))
  end

  local built = S.built
  clear()
  local left = #hafen.vr():list()
  local alive = 0
  for _, w in ipairs(built) do
    if w:exists() then alive = alive + 1 end
  end
  check((left == 0) and (alive == 0),
        "nothing of this suite is left standing: every entity it stood is gone across all four kinds and"
        .. " every widget it built is destroyed",
        ("vr:list=%d widgets still alive=%d"):format(left, alive))
  summary()
end

hafen.slash():register("t045-1", run)   -- the only way in: a suite does not start itself
