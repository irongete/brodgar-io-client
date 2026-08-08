-- 043.5 -- a sprite's FACING, by name. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/043-vr-namespace/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. A sprite is a flat picture, and how it meets the viewer is a property of its
-- own: sprite:facing(mode). Two modes exist -- "fixed" (an upright world quad) and "screen" (a
-- constant-size blit that squares up to the camera) -- and a new sprite is "fixed". Every other word,
-- "camera" INCLUDED, raises naming the two that work: "camera" is a world quad that turns to the
-- viewer, keeping its world size, perspective and occlusion, and it does not exist yet, so accepting
-- the word for the screen blit would teach the wrong thing. The boolean it replaces, :billboard(b),
-- raises naming :facing. Because the mode is a STRING it round-trips through a saved layout as itself.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Nothing in hafen.* reads what is on the screen, so the one thing
-- only eyes can settle is that the two modes still DRAW what they always drew -- one [manual] line.
-- Everything else -- the default, both reads, the chaining write, the rebuild leaving the same sprite
-- in the same place, every refusal and the store round-trip -- is asserted.
--
-- TWO ROUNDS, because the middle state is the one a person has to look at:
--   :t043-5        every assertion, and it leaves one sprite of each facing standing to be looked at
--   :t043-5 off    takes them back down and checks nothing of ours is left standing
--
-- It re-asserts its own premises -- the hafen.vr() section (043.1) and the anchor as an argument
-- (043.2) -- because a suite is read alone and must convince alone. Assets are addon-relative and
-- sandboxed, so it ships its own icon.png rather than borrowing another addon's.
--
-- READ-ONLY: no permissions. It writes one key into its OWN saved variable, which is the round-trip
-- under test, and clears it again before it finishes.

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

local T = 11                              -- world units per tile
local icon                                -- this suite's own asset (hafen.asset, loaded once at Load)
local S                                   -- what the placing round left standing: fx, sc

hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

-- Take everything this suite stood back down. Every kind, because a premise check places a ghost too.
local function clear()
  for _, e in ipairs(hafen.vr():sprite():list()) do hafen.vr():sprite():remove(e) end
  for _, e in ipairs(hafen.vr():ghost():list())  do hafen.vr():ghost():remove(e)  end
  S = nil
end

local offRound

-- ROUND 1 -- every assertion, ending with one sprite of each facing standing for the [manual] look.
local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists() and icon) then
    check(false, "the suite is in the world with its own icon loaded (it places into the 3D scene)",
          ("player=%s icon=%s"):format(tostring(okp and me), tostring(icon)))
    return summary()
  end
  clear()                                  -- a re-run starts from an empty scene

  -- ---- 0. the premises this task's claims rest on (043.1, 043.2) --------------------------------------
  check((hafen.vr() == hafen.vr()) and (hafen.vr():sprite() == hafen.vr():sprite())
        and ((why(function() return hafen.ghost end) or ""):find("hafen.vr()", 1, true) ~= nil)
        and ((why(function() return hafen.render end) or ""):find("hafen.vr()", 1, true) ~= nil),
        "the premise holds: hafen.vr() and its sprite collection are each one object, and hafen.ghost /"
        .. " hafen.render still raise naming hafen.vr()",
        (why(function() return hafen.ghost end) or "<no error>"))

  local p = me:position()
  S = {}
  S.fx = hafen.vr():sprite():add(icon, p:offset(2 * T, 0))       -- 2 tiles EAST, standing still
  check(near(S.fx:position():x(), p:x() + 2 * T) and S.fx:exists(),
        "the premise holds: :add(what, p) stands a sprite where it was put",
        ("x=%s want=%s"):format(tostring(S.fx:position():x()), tostring(p:x() + 2 * T)))

  -- ---- 1. the default, and both modes read back as the strings they are -------------------------------
  check(S.fx:facing() == "fixed",
        "a sprite is placed facing \"fixed\" -- an upright world quad -- and :facing() reads the MODE"
        .. " back as that string", tostring(S.fx:facing()))

  local ret = S.fx:facing("screen")
  check((ret == S.fx) and (S.fx:facing() == "screen"),
        "sprite:facing(\"screen\") writes the mode and hands the sprite back, so a placement chains,"
        .. " and the read answers \"screen\"",
        ("chains=%s mode=%s"):format(tostring(ret == S.fx), tostring(S.fx:facing())))

  -- ---- 2. the write REBUILDS the visual and nothing else ----------------------------------------------
  S.fx:facing("fixed")
  check((S.fx:facing() == "fixed") and S.fx:exists() and near(S.fx:position():x(), p:x() + 2 * T)
        and (S.fx:scale() == 1) and (#hafen.vr():sprite():list() == 1)
        and (hafen.vr():sprite():list()[1] == S.fx),
        "…and writing it back re-mills the visual in place: the same sprite, at the same point, with the"
        .. " same look, still the one member of its collection",
        ("mode=%s exists=%s x=%s n=%d"):format(tostring(S.fx:facing()), tostring(S.fx:exists()),
                                               tostring(S.fx:position():x()),
                                               #hafen.vr():sprite():list()))

  -- ---- 3. every other word is refused, and "camera" is not special ------------------------------------
  refuses("sprite:facing(\"camera\") is REFUSED, naming the two modes that work -- a camera facing is a"
          .. " world quad that turns to the viewer, and it is not one of them",
          function() S.fx:facing("camera") end, "camera", "\"fixed\"", "\"screen\"")
  refuses("…and an unknown mode is refused exactly the same way",
          function() S.fx:facing("upright") end, "upright", "\"fixed\"", "\"screen\"")
  refuses("…and a mode that is not a string is refused, naming the two modes and the type it got",
          function() S.fx:facing(true) end, "\"fixed\"", "\"screen\"", "boolean")
  refuses("…and :facing(nil) is refused -- arity is the verb, so an accidental nil is not a read",
          function() S.fx:facing(nil) end, "must not be nil")
  check(S.fx:facing() == "fixed",
        "…and every one of those refusals left the sprite exactly as it was", tostring(S.fx:facing()))

  -- ---- 4. the boolean it replaces ---------------------------------------------------------------------
  refuses("sprite:billboard(b) raises naming :facing(mode), and says which mode each boolean was",
          function() S.fx:billboard(true) end, "facing", "\"fixed\"", "\"screen\"")
  refuses("…and the bare read raises too, since the metatable answers the NAME",
          function() return S.fx:billboard() end, "facing")

  -- ---- 5. a facing is a string, so a saved layout round-trips it as itself ----------------------------
  local layout = hafen.store():get("layout")
  S.sc = hafen.vr():sprite():add(icon, p:offset(-2 * T, 0)):facing("screen"):scale(2)   -- 2 tiles WEST
  layout.facing = S.sc:facing()
  hafen.store():flush()
  local saved = hafen.store():get("layout").facing
  local back = hafen.vr():sprite():add(icon, p:offset(0, 2 * T)):facing(saved)          -- 2 tiles SOUTH
  check((saved == "screen") and (back:facing() == "screen"),
        "a facing survives a saved layout as itself: the mode string is written, read back, and places a"
        .. " sprite facing the same way -- no boolean in between",
        ("saved=%s replaced=%s"):format(tostring(saved), tostring(back:facing())))
  hafen.vr():sprite():remove(back)
  layout.facing = nil                                    -- leave the suite's own store as it was found
  hafen.store():flush()

  manualCheck("look 2 tiles EAST and 2 tiles WEST of your character, then turn the camera a full circle"
              .. " and zoom in and out; then run ':t043-5 off'",
              "EAST: the icon stands upright in the world, turning away as the camera turns and growing"
              .. " as you zoom in. WEST: the icon squares up to the camera at every angle and stays the"
              .. " same pixel size at every zoom. Both look exactly as they always did")
  summary()
end

-- ROUND 2 -- the take-down: everything this suite stood goes.
offRound = function()
  pass, fail, manual = 0, 0, 0
  local was = S or {}
  clear()
  local gone = 0
  for _, k in ipairs({ "fx", "sc" }) do
    local e = was[k]
    if (e == nil) or (e:exists() == false) then gone = gone + 1 end
  end
  check((gone == 2) and (hafen.vr():sprite():count() == 0) and (#hafen.vr():list() == 0)
        and (hafen.store():get("layout").facing == nil),
        "the suite leaves nothing of its own standing in the world and nothing in its own store",
        ("gone=%d sprites=%d section=%d saved=%s"):format(gone, hafen.vr():sprite():count(),
                                                          #hafen.vr():list(),
                                                          tostring(hafen.store():get("layout").facing)))
  summary()
end

hafen.slash():register("t043-5", run)   -- the only way in: a suite does not start itself
