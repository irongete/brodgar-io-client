-- 043.4 -- seeing it all, and hiding it all. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/043-vr-namespace/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. hafen.vr() answers two questions no single collection can be asked.
-- :list(filter) is EVERYTHING this addon has stood in the world -- all three kinds at once, in the
-- order they were stood, and nobody else's. :visible(b) is the section-wide switch: false takes the
-- lot off screen and destroys nothing, true puts back EXACTLY what was visible -- an entity the addon
-- had hidden on its own handle stays hidden, because the switch is a second boolean beside the
-- entity's own rather than a write over it.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Nothing in hafen.* reads "is this on the screen right now", so the
-- two things only eyes can settle are the two [manual] lines: that :visible(false) really did clear
-- the scene, and that :visible(true) brought back the same things and NOT the one that was hidden on
-- its own handle. Everything else -- the list, its order, its filter, the survival of every handle
-- across the switch, the restore reads, and every refusal -- is asserted.
--
-- THREE ROUNDS, because the middle state is the one a person has to look at:
--   :t043-4       places four things, checks the list, then switches the section OFF
--   :t043-4 on    checks per-handle :visible still works while off, adds a fifth, switches it ON
--   :t043-4 off   takes everything down and checks nothing of ours is left standing
--
-- It re-asserts its own premises -- the hafen.vr() section (043.1) and the anchor-as-argument (043.2)
-- -- because a suite is read alone and must convince alone. Assets are addon-relative and sandboxed,
-- so it ships its own icon.png and tri.gltf rather than borrowing another addon's.
--
-- READ-ONLY: no permissions, no persistent state.

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

local RES = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)
local T   = 11                            -- world units per tile

local icon, mesh                          -- this suite's own assets (hafen.asset, loaded once at Load)
local E                                   -- what the placing round stood: gh, sp, ob, gh2 (+ gh3 later)

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
  mesh = hafen.asset():get("tri.gltf")
end)

-- Take everything this suite stood back down, and leave the section switch as it was found: ON. The
-- switch is STATE, so a round that ends with it off must not be what the next round starts from.
local function clear()
  for _, e in ipairs(hafen.vr():ghost():list())  do hafen.vr():ghost():remove(e)  end
  for _, e in ipairs(hafen.vr():sprite():list()) do hafen.vr():sprite():remove(e) end
  for _, e in ipairs(hafen.vr():object():list()) do hafen.vr():object():remove(e) end
  hafen.vr():visible(true)
  E = nil
end

local onRound, offRound

-- ROUND 1 -- place four things, read the section as a whole, then switch it off for the [manual] look.
local function run(args)
  local a1 = args and args[1]
  if a1 == "on"  then return onRound()  end
  if a1 == "off" then return offRound() end
  pass, fail, manual = 0, 0, 0            -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists() and icon and mesh) then
    check(false, "the suite is in the world with its own assets loaded (it places into the 3D scene)",
          ("player=%s icon=%s mesh=%s"):format(tostring(okp and me), tostring(icon), tostring(mesh)))
    return summary()
  end
  clear()                                 -- a re-run starts from an empty scene and a section that shows

  -- ---- 0. the premises this task's claims rest on (043.1, 043.2) ----------------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():ghost() == hafen.vr():ghost())
        and ((why(function() return hafen.ghost end) or ""):find("hafen.vr()", 1, true) ~= nil)
        and ((why(function() return hafen.render end) or ""):find("hafen.vr()", 1, true) ~= nil),
        "the premise holds: hafen.vr() and each of its collections is one object, and hafen.ghost /"
        .. " hafen.render still raise naming hafen.vr()",
        (why(function() return hafen.ghost end) or "<no error>"))

  -- Four things, in this order, so the list's order is a claim and not an accident: ghost, sprite,
  -- object, ghost. The last one is the one that gets hidden on its own handle.
  local p = me:position()
  E = {}
  E.gh  = hafen.vr():ghost():add(RES, p:offset(3 * T, 0))       -- 3 tiles EAST, standing still
  E.sp  = hafen.vr():sprite():add(icon, me):offset(0, 0, 18)    -- riding your head, following you
  E.ob  = hafen.vr():object():add(mesh, p:offset(0, 3 * T))     -- 3 tiles SOUTH, standing still
  E.gh2 = hafen.vr():ghost():add(RES, p:offset(-3 * T, 0))      -- 3 tiles WEST, standing still

  check(near(E.gh:position():x(), p:x() + 3 * T) and near(E.sp:position():x(), p:x(), 3)
        and ((why(function() E.sp:position(p) end) or ""):find("follows a gob", 1, true) ~= nil),
        "the premise holds: :add(what, p) stands where it was put, :add(what, gob) follows that gob,"
        .. " and :position(p) on a follower is refused",
        ("gh.x=%s sp.x=%s me.x=%s"):format(tostring(E.gh:position():x()), tostring(E.sp:position():x()),
                                           tostring(p:x())))

  -- ---- 1. :list() -- everything, across the kinds, in the order it was stood ----------------------------
  local l = hafen.vr():list()
  check((#l == 4) and (l[1] == E.gh) and (l[2] == E.sp) and (l[3] == E.ob) and (l[4] == E.gh2),
        "hafen.vr():list() is everything standing, across the three kinds at once, in the order it was"
        .. " stood (ghost, sprite, object, ghost) -- not grouped by kind",
        ("n=%d [%s %s %s %s]"):format(#l, tostring(l[1] == E.gh), tostring(l[2] == E.sp),
                                      tostring(l[3] == E.ob), tostring(l[4] == E.gh2)))

  local gl, sl, ol = hafen.vr():ghost():list(), hafen.vr():sprite():list(), hafen.vr():object():list()
  local inSection, mine, missing, foreign = {}, {}, 0, 0
  for _, e in ipairs(l) do inSection[e] = true end
  for _, k in ipairs({ "gh", "sp", "ob", "gh2" }) do mine[E[k]] = true end
  for _, sub in ipairs({ gl, sl, ol }) do
    for _, e in ipairs(sub) do if not inSection[e] then missing = missing + 1 end end
  end
  for _, e in ipairs(l) do if not mine[e] then foreign = foreign + 1 end end
  check((#gl + #sl + #ol == #l) and (missing == 0) and (foreign == 0),
        "…and it agrees exactly with the three per-kind :list()s summed, and holds only THIS addon's"
        .. " entities -- every member is one this suite placed",
        ("ghosts=%d sprites=%d objects=%d section=%d missing=%d foreign=%d")
          :format(#gl, #sl, #ol, #l, missing, foreign))

  local byName, byFn = hafen.vr():list("logcabin"), hafen.vr():list(function(e) return e == E.ob end)
  check((#byName == 2) and (byName[1] == E.gh) and (byName[2] == E.gh2)
        and (#byFn == 1) and (byFn[1] == E.ob),
        "…and it takes the canonical filter, the same one a per-kind list takes: a string matches the"
        .. " visual name, a function is a predicate over the entity",
        ("byName=%d byFn=%d"):format(#byName, #byFn))
  refuses("…and a filter that is neither is refused, naming the three forms",
          function() hafen.vr():list(true) end, "expected nothing, a string or a function")

  -- ---- 2. the section switch, read, and the two ways of calling it wrong --------------------------------
  check(hafen.vr():visible() == true,
        "hafen.vr():visible() reads the section switch as a property, and a section starts showing",
        tostring(hafen.vr():visible()))
  refuses("hafen.vr():visible(nil) is refused -- arity is the verb, so an accidental nil is not a read",
          function() hafen.vr():visible(nil) end, "must not be nil")
  refuses("…and a DOT call on the section is refused, naming the colon form",
          function() hafen.vr().list() end, "COLON call")

  -- ---- 3. one hidden on its own handle -- the entity the restore rule is about --------------------------
  E.gh2:visible(false)
  check((E.gh2:visible() == false) and (E.gh:visible() == true) and E.gh2:exists()
        and (#hafen.vr():list() == 4),
        "a per-handle :visible(false) hides that one and only that one -- and it is still STANDING, so"
        .. " the section list still holds it",
        ("gh2=%s gh=%s n=%d"):format(tostring(E.gh2:visible()), tostring(E.gh:visible()),
                                     #hafen.vr():list()))

  -- ---- 4. the whole section off: nothing is destroyed, nothing is overwritten ---------------------------
  hafen.vr():visible(false)
  local alive, answering = 0, 0
  for _, e in ipairs({ E.gh, E.sp, E.ob, E.gh2 }) do
    if e:exists() then alive = alive + 1 end
    if pcall(function() return e:position(), e:scale(), e:alpha(), e:rotate() end) then
      answering = answering + 1
    end
  end
  check((hafen.vr():visible() == false) and (alive == 4) and (answering == 4)
        and (#hafen.vr():list() == 4),
        "hafen.vr():visible(false) destroys nothing: all four still :exists(), every handle still"
        .. " answers, and the list is unchanged",
        ("switch=%s alive=%d answering=%d n=%d"):format(tostring(hafen.vr():visible()), alive,
                                                        answering, #hafen.vr():list()))
  check((E.gh:visible() == true) and (E.sp:visible() == true) and (E.ob:visible() == true)
        and (E.gh2:visible() == false),
        "…and it did not overwrite what each entity was told: the per-handle :visible reads exactly as"
        .. " it did before the switch, which is what the restore has to go on",
        ("gh=%s sp=%s ob=%s gh2=%s"):format(tostring(E.gh:visible()), tostring(E.sp:visible()),
                                            tostring(E.ob:visible()), tostring(E.gh2:visible())))

  manualCheck("turn the camera a full circle and look around you, then run ':t043-4 on'",
              "NOTHING this suite stood is on screen: no log cabin 3 tiles east, no icon over your"
              .. " head, no white triangle 3 tiles south. Your character, the terrain and everything"
              .. " else are untouched")
  summary()
end

-- ROUND 2 -- what still works while the section is off, and what comes back when it is switched on.
onRound = function()
  pass, fail, manual = 0, 0, 0
  if not (E and E.gh and E.gh:exists()) then
    check(false, "':t043-4 on' continues the placing round", "nothing is standing -- run ':t043-4' first")
    return summary()
  end

  -- ---- 5. per-handle :visible is independent of the section switch --------------------------------------
  E.sp:visible(false)
  local off = E.sp:visible()
  E.sp:visible(true)
  check((off == false) and (E.sp:visible() == true) and (hafen.vr():visible() == false),
        "per-handle :visible keeps working while the section is off -- it reads and writes the entity's"
        .. " own state, and writing it does not switch the section back on",
        ("off=%s back=%s switch=%s"):format(tostring(off), tostring(E.sp:visible()),
                                            tostring(hafen.vr():visible())))

  -- ---- 6. something placed while the section is off -----------------------------------------------------
  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world", "no player gob")
    return summary()
  end
  E.gh3 = hafen.vr():ghost():add(RES, me:position():offset(0, -3 * T))   -- 3 tiles NORTH
  check(E.gh3:exists() and (E.gh3:visible() == true) and (#hafen.vr():list() == 5),
        "a thing placed while the section is off is still placed: it exists, its own :visible() is true,"
        .. " and it joins the section list -- the switch hides, it does not refuse",
        ("exists=%s visible=%s n=%d"):format(tostring(E.gh3:exists()), tostring(E.gh3:visible()),
                                             #hafen.vr():list()))

  -- ---- 7. …and back on: what WAS visible, not everything -------------------------------------------------
  local ret = hafen.vr():visible(true)
  check((ret == hafen.vr()) and (hafen.vr():visible() == true) and E.gh:visible() and E.sp:visible()
        and E.ob:visible() and E.gh3:visible() and (E.gh2:visible() == false),
        "hafen.vr():visible(true) restores what WAS visible rather than turning everything on -- the one"
        .. " hidden on its own handle is still hidden -- and the write hands back the section, so it chains",
        ("chains=%s switch=%s gh=%s sp=%s ob=%s gh3=%s gh2=%s")
          :format(tostring(ret == hafen.vr()), tostring(hafen.vr():visible()), tostring(E.gh:visible()),
                  tostring(E.sp:visible()), tostring(E.ob:visible()), tostring(E.gh3:visible()),
                  tostring(E.gh2:visible())))
  hafen.vr():visible(true)
  check((#hafen.vr():list() == 5) and (E.gh2:visible() == false) and E.gh:visible(),
        "…and switching an already-showing section on again changes nothing",
        ("n=%d gh2=%s"):format(#hafen.vr():list(), tostring(E.gh2:visible())))

  manualCheck("look around you again, then run ':t043-4 off'",
              "the log cabin 3 tiles east, the icon over your head and the white triangle 3 tiles south"
              .. " are all back exactly where they were, PLUS a second cabin 3 tiles north that was"
              .. " placed while the section was off. There is NO cabin 3 tiles west -- that one was"
              .. " hidden on its own handle and stays hidden")
  summary()
end

-- ROUND 3 -- the take-down: everything this suite stood goes, and the section switch is left as found.
offRound = function()
  pass, fail, manual = 0, 0, 0
  local was = E or {}
  clear()
  local gone = 0
  for _, k in ipairs({ "gh", "sp", "ob", "gh2", "gh3" }) do
    local e = was[k]
    if (e == nil) or (e:exists() == false) then gone = gone + 1 end
  end
  check((gone == 5) and (#hafen.vr():list() == 0) and (hafen.vr():ghost():count() == 0)
        and (hafen.vr():sprite():count() == 0) and (hafen.vr():object():count() == 0)
        and (hafen.vr():visible() == true),
        "the suite leaves nothing of its own standing in the world, and leaves the section switch back"
        .. " on -- a hidden entity is removed by its collection like any other",
        ("gone=%d n=%d ghosts=%d sprites=%d objects=%d switch=%s")
          :format(gone, #hafen.vr():list(), hafen.vr():ghost():count(), hafen.vr():sprite():count(),
                  hafen.vr():object():count(), tostring(hafen.vr():visible())))
  summary()
end

hafen.slash():register("t043-4", run)   -- the only way in: a suite does not start itself
