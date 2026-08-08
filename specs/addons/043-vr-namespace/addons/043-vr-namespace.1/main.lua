-- 043.1 -- hafen.vr(): the section exists, and the three collections move into it. Self-checking suite;
-- see specs/addons/TESTING.md and specs/addons/043-vr-namespace/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ghost() and hafen.render() are gone as sections; hafen.vr() is the one
-- section for client-only things standing in the 3D world, and :ghost() / :sprite() / :object() are its
-- collections. It is a pure RE-HOME: every collection verb, every entity verb and every refusal answers
-- exactly as it did, only under a new name -- so the suite adds one entity of each kind and drives the
-- whole vocabulary through the new door. The anchor is still Position-only here (:add(what, gob) is
-- 043.2), and :billboard has not yet become :facing (043.5), so both are checked in their CURRENT form.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Nothing renders differently, and no hafen.* read can see a pixel --
-- so "the cabin, the icon and the model look exactly as they did" is the one [manual] line, and it is
-- the real acceptance test of the whole feature.
--
-- This suite ships its own icon.png and tri.gltf so it stands alone: assets are addon-relative and
-- sandboxed, so it cannot borrow another addon's files. tri.gltf is a hand-written glTF of ONE triangle --
-- upright (1 glTF metre = 1 tile tall, base on the ground), double-sided so no winding can hide it, and
-- plain red. A flat ground-lying triangle was the first version and it is invisible from the game camera,
-- which says nothing about hafen.vr():object(); the fixture has to be seeable for the [manual] line to mean
-- anything.

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

local function refuses(what, fn, wantMsg)
  local err = why(fn) or "<no error>"
  check(err:find(wantMsg, 1, true) ~= nil, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function near(a, b) return (a ~= nil) and (b ~= nil) and (math.abs(a - b) < 0.01) end

-- Every verb the old sections' handles answered, driven as a read/write pair and read back. Returns the
-- names that did NOT round-trip, so one [fail] line names the exact verb rather than "something broke".
local function verbRoundTrip(h, p2)
  local bad = {}
  local function want(name, ok) if not ok then bad[#bad + 1] = name end end
  want("position", (function()
    h:position(p2)
    local q = h:position()
    return q and near(q:x(), p2:x()) and near(q:y(), p2:y())
  end)())
  want("rotate",    (function() h:rotate(1.5);   return near(h:rotate(), 1.5) end)())
  want("scale",     (function() h:scale(2);      return near(h:scale(), 2) end)())
  want("alpha",     (function() h:alpha(0.5);    return near(h:alpha(), 0.5) end)())
  want("tint",      (function()
    h:tint(10, 20, 30)
    local c = h:tint()
    return c and (c[1] == 10) and (c[2] == 20) and (c[3] == 30)
  end)())
  want("visible",   (function() h:visible(false); local off = h:visible(); h:visible(true)
                                return (off == false) and (h:visible() == true) end)())
  want("clickable", (function() h:clickable(true); return h:clickable() == true end)())
  want("onClick",   (function() local fn = function() end; h:onClick(fn); return h:onClick() == fn end)())
  want("exists",    h:exists() == true)
  return bad
end

local function run()
  pass, fail, manual = 0, 0, 0

  -- ---- the section itself: called, per-addon singleton, its verbs ARE its collections -----------------
  local vr = hafen.vr()
  local g1, s1, o1 = hafen.vr():ghost(), hafen.vr():sprite(), hafen.vr():object()
  check((vr == hafen.vr()) and (g1 == hafen.vr():ghost()) and (s1 == hafen.vr():sprite())
        and (o1 == hafen.vr():object()),
        "hafen.vr() and its three collections are handed back by identity, call after call",
        ("vr=%s ghost=%s"):format(tostring(vr == hafen.vr()), tostring(g1 == hafen.vr():ghost())))

  refuses("hafen.vr():ghost(x) refuses an argument -- the verb IS the collection",
          function() return hafen.vr():ghost(1) end, "takes no arguments")
  refuses("hafen.vr(nil) refuses an explicit nil, like every other section",
          function() return hafen.vr(nil) end, "takes no arguments")
  refuses("an unknown verb on hafen.vr() throws naming the section (:widget() arrives in 044)",
          function() return hafen.vr():widget() end, "has no verb 'widget'")

  -- ---- the hard cut: both old sections raise, naming hafen.vr ------------------------------------------
  refuses("hafen.ghost raises naming hafen.vr", function() return hafen.ghost end, "hafen.vr():ghost()")
  refuses("hafen.render raises naming hafen.vr", function() return hafen.render end, "hafen.vr()")
  -- a SECTION row fires on the field read, so it beats every sub-spelling that used to hang off it.
  check((why(function() return hafen.ghost.new end) or ""):find("hafen.vr():ghost()", 1, true) ~= nil
        and (why(function() return hafen.render.sprite end) or ""):find("hafen.vr()", 1, true) ~= nil
        and (why(function() return hafen.render():sprite() end) or ""):find("hafen.vr()", 1, true) ~= nil,
        "every old sub-spelling (dotted or colon) raises the section's message before it is reached",
        ("ghost.new -> %s | render():sprite() -> %s")
          :format(why(function() return hafen.ghost.new end) or "<no error>",
                  why(function() return hafen.render():sprite() end) or "<no error>"))

  -- ---- placement needs the world -----------------------------------------------------------------------
  local okp, me = pcall(function() return hafen.player():gob() end)
  local p = okp and me and me:position()
  if not p then
    check(false, "the suite is in the world (the three collections place into the 3D scene)", "no player gob")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  local p2 = p:offset(3, 3)

  local icon = hafen.asset():get("icon.png")
  local mesh = hafen.asset():get("tri.gltf")

  -- ---- each collection still places what it was given, and reads it back --------------------------------
  local RES = "gfx/terobjs/arch/logcabin"
  local gh = hafen.vr():ghost():add(RES, p:offset(6, 0))
  check((gh ~= nil) and gh:exists() and (gh:res() == RES),
        "hafen.vr():ghost():add(res, p) places a .res prop and reads its resource back",
        gh and gh:res())

  local sp = hafen.vr():sprite():add(icon, p:offset(8, 0))
  check((sp ~= nil) and sp:exists() and (sp:image() == icon:path()),
        "hafen.vr():sprite():add(asset, p) stands this addon's PNG and reads its path back",
        sp and sp:image())

  local ob = hafen.vr():object():add(mesh, p:offset(10, 0))
  check((ob ~= nil) and ob:exists() and (ob:mesh() == mesh:path()),
        "hafen.vr():object():add(asset, p) stands this addon's glTF model and reads its path back",
        ob and ob:mesh())

  -- ---- the shared entity vocabulary, on all three kinds -------------------------------------------------
  local bad = {}
  for _, e in ipairs({ { "ghost", gh }, { "sprite", sp }, { "object", ob } }) do
    for _, v in ipairs(verbRoundTrip(e[2], p2)) do bad[#bad + 1] = e[1] .. ":" .. v end
  end
  check(#bad == 0,
        "every verb the old sections answered round-trips on all three kinds"
        .. " (position/rotate/scale/alpha/tint/visible/clickable/onClick/exists)",
        table.concat(bad, ", "))

  -- ---- and the one or two verbs each kind adds of its own -----------------------------------------------
  gh:res(RES)                                   -- the write half of the ghost's own property
  sp:billboard(true)                            -- still :billboard here; it becomes :facing in 043.5
  check((gh:res() == RES) and (sp:billboard() == true) and (why(function() ob:mesh("x") end) ~= nil),
        "the per-kind verbs answer too: ghost:res writes, sprite:billboard round-trips, object:mesh is read-only",
        ("res=%s billboard=%s"):format(tostring(gh:res()), tostring(sp:billboard())))

  -- ---- the collection verbs ----------------------------------------------------------------------------
  check((hafen.vr():ghost():count() == 1) and (hafen.vr():sprite():count() == 1)
        and (hafen.vr():object():count() == 1)
        and (hafen.vr():ghost():list()[1] == gh) and (hafen.vr():sprite():find("icon") == sp)
        and (hafen.vr():object():find("tri") == ob),
        "each collection's :list()/:count()/:find(needle) sees this addon's one entity and no other",
        ("counts %d/%d/%d"):format(hafen.vr():ghost():count(), hafen.vr():sprite():count(),
                                   hafen.vr():object():count()))

  refuses("a handle from another collection is refused rather than silently missed",
          function() hafen.vr():ghost():remove(sp) end, "a ghost this addon placed")
  refuses("a path string into :add is still refused naming hafen.asset (D-012, handle-only)",
          function() return hafen.vr():sprite():add("icon.png", p) end, "hafen.asset")

  -- ---- the retired ENTITY spellings name the new collection --------------------------------------------
  check((why(function() gh:destroy() end) or ""):find("hafen.vr():ghost():remove(g)", 1, true) ~= nil
        and (why(function() sp:destroy() end) or ""):find("hafen.vr():sprite():remove(s)", 1, true) ~= nil
        and (why(function() ob:destroy() end) or ""):find("hafen.vr():object():remove(o)", 1, true) ~= nil,
        "the retired :destroy() on each handle raises naming its hafen.vr() collection",
        (why(function() gh:destroy() end) or "<no error>"))

  -- ---- and the collection ends what it placed ----------------------------------------------------------
  hafen.vr():ghost():remove(gh)
  hafen.vr():sprite():remove(sp)
  hafen.vr():object():remove(ob)
  check((not gh:exists()) and (not sp:exists()) and (not ob:exists())
        and (hafen.vr():ghost():count() == 0) and (hafen.vr():sprite():count() == 0)
        and (hafen.vr():object():count() == 0),
        "the collection that placed each one ends it: :exists() false and every count back to 0",
        ("counts %d/%d/%d"):format(hafen.vr():ghost():count(), hafen.vr():sprite():count(),
                                   hafen.vr():object():count()))

  manualCheck("run :t043-1-show, then look just east of your character",
    "THREE things stand a few paces apart: a log cabin (ghost), an upright icon.png quad (sprite) and a"
    .. " red triangle ~3 tiles tall (object) -- all as they rendered before this task; :t043-1-hide clears them")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t043-1", run)   -- the only way in: a suite does not start itself

-- The [manual] line's scene, kept out of the automated run so nothing it leaves behind can perturb a count.
local shown = {}

hafen.slash():register("t043-1-show", function()
  local p = hafen.player():gob():position()
  shown = {
    hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p:offset(6, 0)),
    hafen.vr():sprite():add(hafen.asset():get("icon.png"), p:offset(8, 0)):scale(3),
    hafen.vr():object():add(hafen.asset():get("tri.gltf"), p:offset(10, 0)):scale(3),
  }
  hafen.log():write("placed a ghost, a sprite and an object a few paces east -- :t043-1-hide clears them")
end)

hafen.slash():register("t043-1-hide", function()
  if shown[1] then hafen.vr():ghost():remove(shown[1]) end
  if shown[2] then hafen.vr():sprite():remove(shown[2]) end
  if shown[3] then hafen.vr():object():remove(shown[3]) end
  shown = {}
  hafen.log():write("cleared")
end)
