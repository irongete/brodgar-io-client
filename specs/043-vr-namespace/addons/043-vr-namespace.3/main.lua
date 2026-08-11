-- 043.3 -- the world kinds leave gob:overlay(). Self-checking suite; see specs/testing/addon-suite.md and
-- specs/043-vr-namespace/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. gob:overlay() means ONE thing now: what is DRAWN at this gob. Its three world
-- kinds -- ov:image / ov:model / ov:ghost -- are cut, each raising and naming its hafen.vr() replacement,
-- because what they built was never an engine overlay but a client gob of its own standing in the scene.
-- The verb set that served only them (:scale :alpha :tint :rotate :billboard :spawnData :position) goes
-- with them, which is what makes ov:offset mean exactly one thing (screen pixels) and deletes the whole
-- "refused on a screen-space overlay, naming the kinds" machinery. A hafen.vr() entity anchored to a gob
-- is still surfaced in gob:overlay():list(), READ-ONLY, so "what is at this gob?" keeps one complete
-- answer -- and the game's own overlays read exactly as they did.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Only a person can see that a label is still painted where it was and
-- that the pin still floats overhead; and only the world can supply a gob the GAME hung an overlay on. So
-- the two [manual] lines are "look at your character" and, when no native overlay is in sight, "stand next
-- to a fire and run it again" -- everything else, including every refusal, is asserted.
--
-- It re-asserts its own premises -- the hafen.vr() section (043.1) and the anchor-as-argument (043.2) --
-- because a suite is read alone and must convince alone. Assets are addon-relative and sandboxed, so it
-- ships its own icon.png and tri.gltf rather than borrowing another addon's.
--
-- READ-ONLY: no permissions, no persistent state. ':t043-3' leaves its label and its pin standing for the
-- [manual] look; ':t043-3 off' takes them down and asserts that nothing of its own is left.

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
local function why(f)
  local ok, err = pcall(f)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function refuses(what, fn, ...)
  local err = why(fn) or "<no error>"
  local ok = true
  for _, want in ipairs({ ... }) do
    if err:find(want, 1, true) == nil then ok = false end
  end
  check(ok, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function near(a, b, tol) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) < (tol or 0.5)) end

local RES   = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)
local LABEL = "t043-3.label"                -- our key for a text overlay   (screen space)
local RING  = "t043-3.ring"                 -- our key for a draw overlay   (screen space)

local icon, mesh                            -- this suite's own assets (hafen.asset, loaded once at Load)
local painted = 0                           -- how many times the draw callback has run (it must still run)

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

-- Take down everything this suite put anywhere: its own overlay keys on the player, and its own entities.
local function clear(me)
  if me and me:exists() then me:overlay():remove(LABEL):remove(RING) end
  for _, e in ipairs(hafen.vr():sprite():list()) do hafen.vr():sprite():remove(e) end
  for _, e in ipairs(hafen.vr():ghost():list())  do hafen.vr():ghost():remove(e)  end
  for _, e in ipairs(hafen.vr():object():list()) do hafen.vr():object():remove(e) end
end

-- The one member of gob:overlay():list() whose kind names a hafen.vr() collection, or nil.
local WORLD = { sprite = true, object = true, ghost = true }

local function vrEntry(g)
  for _, ov in ipairs(g:overlay():list()) do
    if WORLD[ov:kind() or ""] then return ov end
  end
  return nil
end

-- The first overlay the GAME itself hung on anything in sight, with the gob it is on.
local function anyNative()
  for _, g in ipairs(hafen.world():gob():list()) do
    for _, ov in ipairs(g:overlay():list()) do
      if ov:native() then return ov, g end
    end
  end
  return nil
end

local offRound

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0           -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists() and icon and mesh) then
    check(false, "the suite is in the world with its own assets loaded (it places into the 3D scene)",
          ("player=%s icon=%s mesh=%s"):format(tostring(okp and me), tostring(icon), tostring(mesh)))
    return summary()
  end
  clear(me)                              -- a re-run starts from an empty gob and an empty scene

  -- ---- 0. the premises this task's claims rest on (043.1, 043.2) ----------------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():sprite() == hafen.vr():sprite())
        and ((why(function() return hafen.ghost end) or ""):find("hafen.vr():ghost()", 1, true) ~= nil)
        and ((why(function() return hafen.render end) or ""):find("hafen.vr()", 1, true) ~= nil),
        "the premise holds: hafen.vr() and its collections are one object each, and hafen.ghost/hafen.render"
        .. " still raise naming hafen.vr()",
        (why(function() return hafen.ghost end) or "<no error>"))

  -- ---- 1. what STAYS: gob:overlay() is what is DRAWN at the gob -----------------------------------------
  local lb = me:overlay():add(LABEL):text("t043-3"):color(120, 220, 120):offset(3, -6)
  local rg = me:overlay():add(RING):draw(function(g, gob, sx, sy)
                                     painted = painted + 1
                                     g:color(120, 220, 120, 160)
                                     g:rect(sx - 6, sy - 6, 12, 12)
                                   end)
  local off, col = lb:offset(), lb:color()
  check((lb:kind() == "text") and (lb:text() == "t043-3") and (rg:kind() == "draw")
        and (col[1] == 120) and (col[2] == 220) and (col[3] == 120),
        "ov:text / ov:draw / ov:color are untouched: an overlay still says what it paints at the gob's point",
        ("kind=%s/%s text=%s color=%s"):format(tostring(lb:kind()), tostring(rg:kind()), tostring(lb:text()),
                                               tostring(col and col[1])))
  check((off.x == 3) and (off.y == -6) and (off.z == nil),
        "ov:offset(x, y) is SCREEN PIXELS and reads back as two components -- there is no z on it any more",
        ("x=%s y=%s z=%s"):format(tostring(off.x), tostring(off.y), tostring(off.z)))
  refuses("…and the three-number world form is refused, naming the verb that moves a thing in the world",
          function() lb:offset(0, 0, 18) end, "SCREEN PIXELS", "hafen.vr():sprite():add(asset, gob)")

  -- ---- 2. what GOES: the three world kinds, each naming its replacement ---------------------------------
  refuses("ov:image(asset) raises naming hafen.vr():sprite():add(asset, gob)",
          function() lb:image(icon) end, "hafen.vr():sprite():add(asset, gob)")
  refuses("ov:model(asset) raises naming hafen.vr():object():add(asset, gob)",
          function() lb:model(mesh) end, "hafen.vr():object():add(asset, gob)")
  refuses("ov:ghost(res) raises naming hafen.vr():ghost():add(res, gob)",
          function() lb:ghost(RES) end, "hafen.vr():ghost():add(res, gob)")

  -- ---- 3. …and the verb set that served only them, so the "which kind is this?" machinery is gone -------
  local left = {}
  for _, v in ipairs({ "scale", "alpha", "tint", "rotate", "billboard", "spawnData", "position" }) do
    local err = why(function() return lb[v] end)
    if not (err and err:find("hafen.vr()", 1, true)) then left[#left + 1] = v .. "=" .. tostring(err) end
  end
  check(#left == 0,
        "the world verb set (:scale :alpha :tint :rotate :billboard :spawnData :position) is gone from the"
        .. " Overlay object, each raising and naming hafen.vr()",
        table.concat(left, "; "))

  -- ---- 4. the world offset moved WITH the kinds: it is a verb on the handle now -------------------------
  local pin = hafen.vr():sprite():add(icon, me):scale(2):offset(0, 0, 18)
  local free = hafen.vr():ghost():add(RES, me:position():offset(10, 0))
  local po = pin:offset()
  check(near(po.x, 0) and near(po.y, 0) and near(po.z, 18),
        "sprite:offset(x, y, z) sets where an ANCHORED thing sits relative to its gob, in world units (z up)",
        ("x=%.1f y=%.1f z=%.1f"):format(po.x, po.y, po.z))
  refuses("…and it is refused on a FREE one, naming :position(p) -- an offset from nothing is not a place",
          function() free:offset(0, 0, 18) end, "stands where it was put", "position(p)")

  -- ---- 5. the anchored thing is listed at its gob, READ-ONLY --------------------------------------------
  local e = vrEntry(me)
  if not e then
    check(false, "a hafen.vr() entity anchored to a gob appears in that gob's gob:overlay():list()",
          "no entry with a vr kind in " .. tostring(#me:overlay():list()) .. " member(s)")
    return summary()
  end
  check((e:native() == false) and (e:kind() == "sprite") and (e:res() == pin:image()) and (e:count() == 1)
        and e:exists() and (me:overlay():get(e:key()) == e),
        "…as a read-only entry: :native() false, :kind() names the collection that owns it, :res() its image,"
        .. " :count() 1, and :get(key) hands back the same object",
        ("native=%s kind=%s res=%s count=%s"):format(tostring(e:native()), tostring(e:kind()),
                                                     tostring(e:res()), tostring(e:count())))
  local i = e:info()
  check((i.native == false) and (i.kind == "sprite") and (i.world == true) and (i.count == 1)
        and (i.key == e:key()) and (lb:info().world == false),
        "…and ov:info() says which space it is in: world=true for the thing standing in the scene,"
        .. " world=false for the painter at the gob's screen point",
        ("native=%s kind=%s world=%s count=%s"):format(tostring(i.native), tostring(i.kind),
                                                       tostring(i.world), tostring(i.count)))
  refuses("every WRITE through that entry is refused, naming the collection that owns it",
          function() e:text("no") end, "READ-ONLY", "hafen.vr():sprite()")
  refuses("…including a removal: the collection placed it, so the collection ends it",
          function() me:overlay():remove(e) end, "hafen.vr():sprite():remove")
  refuses("…and its key is not one you may attach your own overlay under",
          function() me:overlay():add(e:key()) end, "hafen.vr():sprite()")
  check(pin:exists() and (hafen.vr():sprite():count() == 1),
        "…and none of those refusals touched the thing itself: it is still standing, still its collection's",
        ("exists=%s sprites=%d"):format(tostring(pin:exists()), hafen.vr():sprite():count()))

  -- ---- 6. the game's own overlays read exactly as they did ----------------------------------------------
  local nov, ng = anyNative()
  if nov then
    local ni = nov:info()
    check((nov:native() == true) and (nov:res() == nov:key()) and (nov:count() >= 1) and (nov:kind() == nil)
          and (ni.native == true) and (ni.res == nov:key()) and (ni.count == nov:count())
          and (why(function() nov:text("no") end) or ""):find("READ-ONLY", 1, true) ~= nil,
          ("the GAME's own overlays are unchanged: '%s' on gob %d keys by resource, :count() is the union,"
           .. " :info() is {key, native, res, count} and every write is refused")
            :format(nov:key(), ng:id()),
          ("native=%s res=%s count=%s kind=%s"):format(tostring(nov:native()), tostring(nov:res()),
                                                       tostring(nov:count()), tostring(nov:kind())))
  else
    manualCheck("stand next to a fire, a growing crop or a curiosity and run ':t043-3' again",
                "the run finds one of the GAME's own overlays and asserts it reads as it always did"
                .. " (keyed by resource name, :count() the union, every write refused)")
  end

  -- ---- 7. one complete answer, in three groups ---------------------------------------------------------
  local mine_, vr_, nat_ = 0, 0, 0
  for _, ov in ipairs(me:overlay():list()) do
    if ov:native() then nat_ = nat_ + 1
    elseif WORLD[ov:kind() or ""] then vr_ = vr_ + 1
    else mine_ = mine_ + 1 end
  end
  check((mine_ == 2) and (vr_ == 1),
        ("gob:overlay():list() answers all of it at once: 2 painters of ours, 1 thing of ours standing there,"
         .. " %d of the game's own"):format(nat_),
        ("mine=%d vr=%d native=%d"):format(mine_, vr_, nat_))

  manualCheck("look at your character (turn the camera, walk a few paces), then run ':t043-3 off'",
              "a green 't043-3' label with a green square around it rides your head EXACTLY as before, and"
              .. " icon.png floats ~1.6 tiles above you and travels with you; a log cabin stands 10 paces"
              .. " east; ':t043-3 off' removes all three and reports the draw callback still ran")
  summary()
end

-- The take-down round: everything this suite left standing goes, and what only a painted frame can prove
-- (that the screen-space draw callback still runs at the gob) is reported as the count it reached.
offRound = function()
  pass, fail, manual = 0, 0, 0
  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world", "no player gob")
    return summary()
  end
  check(painted > 0,
        ("ov:draw(fn) still paints at the gob's projected point -- the callback ran %d time(s)"):format(painted),
        "0 -- the draw overlay never painted")
  clear(me)
  local ours = 0                              -- the game's own may well be on your body; only OURS must be gone
  for _, ov in ipairs(me:overlay():list()) do
    if not ov:native() then ours = ours + 1 end
  end
  check((ours == 0) and (hafen.vr():sprite():count() == 0)
        and (hafen.vr():ghost():count() == 0) and (hafen.vr():object():count() == 0),
        "the suite leaves nothing of its own on the gob or standing in the world",
        ("ours=%d sprites=%d ghosts=%d"):format(ours, hafen.vr():sprite():count(),
                                                hafen.vr():ghost():count()))
  painted = 0
  summary()
end

hafen.slash():register("t043-3", run)   -- the only way in: a suite does not start itself
