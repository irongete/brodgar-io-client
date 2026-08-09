-- 045.2 -- the place that is not here yet, and the moment the numbers move. Self-checking suite; see
-- specs/addons/TESTING.md and specs/addons/045-durable-places/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. Since 045.1 a free hafen.vr() entity holds a DURABLE place -- a grid id plus the
-- offset inside that grid -- and its session coordinate is a cache derived from it. 045.2 finishes the
-- thought in both directions. A place this session cannot LOCATE stops being a refusal: the entity is
-- created, it is simply not drawn, it reads back the grid it was given, its :x() is nil, and it enters the
-- scene by itself the moment that ground resolves. Nothing retries and nothing gives up -- there is no
-- bounded chain here, because "the player has not walked there" is not a blocker that clears on notify.
--
-- AND THE NUMBERS MOVE ON THEIR OWN. The server re-bases the whole session coordinate space whenever it
-- drops the map -- walk into a cave and the same numbers name different ground. 044.9's terrain-cut event
-- catches most of that, but the location the derivation reads (MiniMap's sessloc) is re-resolved a frame or
-- more LATER, so an entity whose ground came back while the player stood still would wait for a cut change
-- that never comes. So that assignment is the second event, guarded by an equality test on the segment and
-- the tile origin -- because tick mints a fresh location every frame, and notifying on all of them would be
-- the per-frame poll 042 deleted. p:entities().passes is that guard's witness.
--
-- WHAT IS STILL REFUSED, and this suite states it where it can fail: a place with NO durable form at all --
-- a raw coordinate over ground nobody has recorded. That one has no grid id to hold it by, so it is refused
-- at :add and at :position(p) alike. Legal-but-unreached and un-holdable are different answers.
--
-- TWO PHASES, AND ONLY THE WALK IS MANUAL. The first run leaves one ghost standing where you are and records
-- its :info(); walk into a cave (or a house) and back out, run ':t045-2' again, and the second run compares.
-- ':t045-2 off' takes down whatever is left standing -- and is also how you start the pair over, since with
-- something still standing ':t045-2' is the comparison round.
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
-- A grid id no server ever minted: the client cannot locate it, cannot invent a coordinate for it, and must
-- neither raise about it nor forget it. It is what "a place you have not reached" looks like from Lua.
local FAKE = "7455009834552831177"
local UNREACHED = { gridId = FAKE, x = 500.0, y = 500.0 }

local KINDS = { "ghost", "sprite", "object", "widget" }

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local S                                   -- everything the current round is holding
local W                                   -- what was left standing for the walk, and its place before it

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

local function counters() return hafen.client():profiling():entities() end

local function coll(k)
  if k == "ghost" then return hafen.vr():ghost() end
  if k == "sprite" then return hafen.vr():sprite() end
  if k == "object" then return hafen.vr():object() end
  return hafen.vr():widget()
end

-- Stand one of each kind at `p`. All four, because the change is four edited create paths on one shared
-- core: a null coordinate missed in any one of them is a thing standing at the map origin.
local function standAll(p, built)
  local w = hafen.ui():window():title("045.2"):size(150, 56)
  w:on("Draw", function(ev) ev:g():text("045.2", 8, 10) end)
  built[#built + 1] = w
  return {
    ghost  = hafen.vr():ghost():add(RES, p),
    sprite = hafen.vr():sprite():add(icon, p),
    object = hafen.vr():object():add(mesh, p),
    widget = hafen.vr():widget():add(w, p):facing("camera"),
    w      = w,
  }
end

-- Which of the four are in the scene right now, as two word lists, so a [fail] names the kind that disagreed.
local function drawnList(set)
  local yes, no = {}, {}
  for _, k in ipairs(KINDS) do
    local e = set[k]
    if (e ~= nil) and e:drawn() then yes[#yes + 1] = k else no[#no + 1] = k end
  end
  return ((#yes == 0) and "none" or table.concat(yes, " ")),
         ((#no == 0) and "none" or table.concat(no, " "))
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

-- Every one of the four reads the place it was given back out, and answers nil for where that is here.
local function allHoldUnreached(set)
  for _, k in ipairs(KINDS) do
    local e = set[k]
    if e == nil then return false, k .. ": missing" end
    local q = e:position()
    local i = q:info()
    if (i == nil) or (i.gridId ~= FAKE) or (math.abs(i.x - UNREACHED.x) > 0.01)
       or (math.abs(i.y - UNREACHED.y) > 0.01) then
      return false, k .. ": info=" .. tostring(i and i.gridId) .. "@" .. tostring(i and i.x)
    end
    if q:x() ~= nil then return false, k .. ": x()=" .. tostring(q:x()) end
  end
  return true, "all four"
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

local phase2, phase3, walkRound, offRound

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  if (W ~= nil) and W.e:exists() then return walkRound() end
  pass, fail, manual = 0, 0, 0              -- a re-run reports its own counts, not the last one's

  clear()
  W = nil
  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands things in the 3D scene)", tostring(okp and me))
    return summary()
  end
  if (icon == nil) or (mesh == nil) then
    check(false, "the suite's own image and model loaded (hafen.asset)", "icon or mesh missing")
    return summary()
  end

  local p = me:position()
  check(p:durable(), "the premise, stated where it can fail: the place you are standing on CAN be held --"
        .. " it has a grid id, so everything below is about places that are legal and merely elsewhere,"
        .. " never about a place that cannot be kept at all",
        ("durable=%s info=%s"):format(tostring(p:durable()), tostring(p:info() and p:info().gridId)))
  if not p:durable() then
    return summary()   -- everything below rebuilds places through p:info(); there is nothing to rebuild from
  end

  local far = hafen.world():position(UNREACHED)
  check(far:durable() and (far:x() == nil) and (far:y() == nil) and (far:info().gridId == FAKE),
        "a Position rebuilt from a grid id this session has never seen is DURABLE and simply has no"
        .. " coordinate here: it can be held, saved and compared, and p:x() reports the honest nil. Those"
        .. " are two different questions, and only one of them is about now",
        ("durable=%s x=%s gridId=%s"):format(tostring(far:durable()), tostring(far:x()),
          tostring(far:info().gridId)))

  S = { built = {}, p = p }
  local okadd, set = pcall(standAll, far, S.built)
  check(okadd, "all four kinds are added at that unreachable place and NOTHING is raised on the way in --"
        .. " a ghost, a sprite, an object and a standing widget. Until now :add refused it; the entity was"
        .. " the thing that could not exist, when the only thing missing was a coordinate",
        okadd and "-" or tostring(set))
  if not okadd then
    S.far = nil
    return summary()
  end
  S.far = set
  -- ...and one at the player's OWN place, rebuilt through the durable form rather than kept as numbers, so
  -- the near and the far case differ in one thing only: whether this session can locate that grid.
  S.near = hafen.vr():ghost():add(RES, hafen.world():position(p:info()))
  hafen.timer():after(1.5, function() phase2() end)   -- a ghost's visual streams in on a loader thread
end

phase2 = function()
  if (S == nil) or (S.far == nil) then return end
  local fd, fn = drawnList(S.far)
  check(noneDrawn(S.far) and allExist(S.far),
        "every one of them EXISTS and none of them is drawn: the entity is real, holds its place and takes"
        .. " every verb, while the scene has nothing to put it in. Not an error, not a retry, not a"
        .. " placeholder somewhere wrong -- simply a thing waiting for its ground",
        ("drawn: %s -- not drawn: %s; all exist=%s"):format(fd, fn, tostring(allExist(S.far))))

  local held, why = allHoldUnreached(S.far)
  check(held, "and all four read the place they were GIVEN back out -- the same grid id, the same offset"
        .. " inside it -- while :x() answers nil. That asymmetry is the whole contract: :info() is what"
        .. " survives, :x() is only ever this session's answer to it",
        why)

  local ct = counters()
  check((ct.waiting >= 4) and (ct.placed >= 5),
        "the layer says so too: four things standing at a place it cannot locate are counted as waiting,"
        .. " among the free entities it is holding",
        ("placed=%d waiting=%d passes=%d"):format(ct.placed, ct.waiting, ct.passes))

  check(S.near:drawn() and S.near:exists(),
        "...while one placed at YOUR own grid id -- the same durable form, rebuilt through"
        .. " hafen.world():position(info) -- stands immediately, with nothing else done to it. The common"
        .. " case did not change: ground you can see is ground that answers its own id",
        ("drawn=%s exists=%s"):format(tostring(S.near:drawn()), tostring(S.near:exists())))

  -- 044.9's three booleans, over a thing that is waiting rather than over one whose terrain went. This
  -- suite is read alone, so the rule it leans on is asserted here rather than left in the older one.
  S.far.ghost:visible(false)
  S.far.ghost:visible(true)
  hafen.vr():visible(false)
  local offd = S.near:drawn()
  hafen.vr():visible(true)
  check((not offd) and S.near:drawn() and S.far.ghost:visible() and (not S.far.ghost:drawn())
        and (#hafen.vr():list() == 5),
        "and nothing about waiting touches what the addon wrote: :visible(false)/:visible(true) over a"
        .. " place that is not here is remembered and does not resurrect it, the section switch takes the"
        .. " lot off and restores exactly what was showing, and the collections still hold all five. Three"
        .. " independent booleans, one AND (044.9)",
        ("anything drawn while off=%s; near back=%s far visible=%s far drawn=%s list=%d"):format(
          tostring(offd), tostring(S.near:drawn()), tostring(S.far.ghost:visible()),
          tostring(S.far.ghost:drawn()), #hafen.vr():list()))

  -- The other edge, and it did NOT move: a place with no durable form at all.
  local raw = S.p:offset(2000 * T, 2000 * T)
  check(not raw:durable(), "the second premise: a raw coordinate two thousand tiles out is not durable --"
        .. " nobody has recorded that ground, so there is no grid id to hold it by",
        ("durable=%s"):format(tostring(raw:durable())))
  refuses("a place with NO durable form is still refused at :add, naming why -- unreached and un-holdable"
          .. " are different answers, and only the first one waits",
          function() hafen.vr():ghost():add(RES, raw) end, "no durable form")
  refuses("...and still refused at <entity>:position(p), the second of the two doors that HOLD a place",
          function() S.near:position(raw) end, "no durable form")

  -- ...and the same door, given a place that is merely elsewhere, moves the thing there and waits.
  S.near:position(hafen.world():position(UNREACHED))
  local q = S.near:position()
  local awayDrawn, awayX, awayGrid = S.near:drawn(), q:x(), q:info().gridId
  S.near:position(hafen.world():position(S.p:info()))
  check((not awayDrawn) and (awayX == nil) and (awayGrid == FAKE) and S.near:drawn()
        and (S.near:position():info().gridId == S.p:info().gridId),
        "moving a standing thing TO a place this session cannot locate takes it out of the scene and it"
        .. " holds the new place; moving it back puts it straight in. One rule for :add and :position(p),"
        .. " so where a thing may be put and where it may be moved can never disagree",
        ("away: drawn=%s x=%s grid=%s; back: drawn=%s grid=%s"):format(tostring(awayDrawn), tostring(awayX),
          tostring(awayGrid), tostring(S.near:drawn()), tostring(S.near:position():info().gridId)))

  S.passes0 = counters().passes
  hafen.timer():after(1.2, function() phase3() end)   -- an idle stretch: the tap must say nothing
end

phase3 = function()
  if S == nil then return end
  local ct = counters()
  check(ct.passes == S.passes0,
        "the second event is an EVENT: across a stretch of idle ticks the re-derivation ran not once,"
        .. " though the location behind it is re-resolved every single frame. The guard is an equality test"
        .. " on the segment and the tile origin, which is the difference between a tap and the per-frame"
        .. " poll 042 deleted",
        ("passes=%d (was %d) over ~1.2s -- stand still while this runs; walking moves it a handful of times")
          :format(ct.passes, S.passes0))

  -- Leave the near ghost standing, and remember where. Everything else comes down, so nothing of this suite
  -- is on the flat UI or in the scene while the maintainer walks.
  local keep = S.near
  for _, k in ipairs(KINDS) do
    local c = coll(k)
    for _, e in ipairs(c:list()) do
      if e ~= keep then c:remove(e) end
    end
  end
  for _, w in ipairs(S.built) do
    if w:exists() then w:destroy() end
  end
  local q = keep:position()
  W = { e = keep, info = q:info(), x0 = q:x(), y0 = q:y() }
  S = nil

  manualCheck("a log cabin is standing where you are. Walk into a cave (or into a house) so the map is"
              .. " dropped, come back out to it, and run ':t045-2' again -- that run compares the place it"
              .. " is holding against the one recorded just now and prints its own [summary]. Finish with"
              .. " ':t045-2 off'",
              "the cabin is gone while you are inside, is standing in the same spot when you come back out,"
              .. " and the second run is all [pass]")
  summary()
end

-- The re-run after the walk: the whole point of the feature, as an assertion rather than an eyeball.
walkRound = function()
  pass, fail, manual = 0, 0, 0
  local e, was = W.e, W.info
  local q = e:position()
  local now = q:info()
  local same = (now ~= nil) and (now.gridId == was.gridId)
                and (math.abs(now.x - was.x) < 0.01) and (math.abs(now.y - was.y) < 0.01)
  check(same, "the place SURVIVED the map being dropped: the same grid id and the same offset inside it,"
        .. " before the walk and after it. That is the durable form doing the only job it has -- the"
        .. " entity never held the numbers that changed",
        ("before %s@%.2f,%.2f -- after %s@%.2f,%.2f"):format(was.gridId, was.x, was.y,
          tostring(now and now.gridId), (now and now.x) or -1, (now and now.y) or -1))

  check(e:drawn() and e:exists(),
        "...and it put itself back in the scene, with nothing done to it and no command run while you were"
        .. " away. The coordinate space moving IS the event, so coming back out of a cave is the same"
        .. " moment the thing is re-derived",
        ("drawn=%s exists=%s"):format(tostring(e:drawn()), tostring(e:exists())))

  local x1 = q:x()
  local moved = (W.x0 ~= nil) and (x1 ~= nil) and ((math.abs(x1 - W.x0) > 0.01)
                                                   or (math.abs((q:y() or 0) - (W.y0 or 0)) > 0.01))
  check(x1 ~= nil, "the session coordinate is a CACHE of the place and is free to differ -- reported, never"
        .. " failed. Same spot on the ground, and whether the numbers naming it are the same is the"
        .. " server's business, not the addon's",
        ("x,y before %s,%s -- after %s,%s (re-based=%s)"):format(tostring(W.x0), tostring(W.y0),
          tostring(x1), tostring(q:y()), tostring(moved)))

  local ct = counters()
  check((ct.waiting == 0) and (ct.passes > 0),
        "nothing is left waiting, and the drain did run while you were away -- the two halves of the same"
        .. " sentence",
        ("placed=%d waiting=%d passes=%d"):format(ct.placed, ct.waiting, ct.passes))

  hafen.vr():ghost():remove(e)
  W = nil
  check(#hafen.vr():list() == 0,
        "nothing of this suite is left standing: the collections are empty across all four kinds",
        ("vr:list=%d"):format(#hafen.vr():list()))
  summary()
end

-- ':t045-2 off' takes down whatever a run left standing.
offRound = function()
  pass, fail, manual = 0, 0, 0
  clear()
  if (W ~= nil) and W.e:exists() then hafen.vr():ghost():remove(W.e) end
  W = nil
  check(#hafen.vr():list() == 0,
        "nothing of this suite is left standing: hafen.vr():list() is empty across all four kinds",
        ("vr:list=%d"):format(#hafen.vr():list()))
  summary()
end

hafen.slash():register("t045-2", run)   -- the only way in: a suite does not start itself
