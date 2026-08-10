-- 048.4 — hafen.world():place(p, angle, button, mods) and :select(p1, p2, mods). Self-checking suite; see
-- specs/addons/TESTING.md.
--
-- The world's first protected verbs. Both were in hafen.act(), the one section grouped by what its verbs COST
-- rather than by what they CHANGE — and `place` in particular sat a whole section away from the
-- hafen.world():snapPlace(p) / :snapAngle(a) that exist for no other purpose than preparing its two arguments.
-- So the move is also a repair: the verb and its preparation are now three lines apart.
--
-- The suite declares no permissions, so each verb is proven by its REFUSAL naming the verb, and the gate runs
-- FIRST (D-213) — which means every argument mistake this suite could make comes back as the permission error
-- too, and the argument refusals are `[manual]` :lua lines whose console owner declares every permission.
--
-- RUN IT, FIRE THE TWO :lua LINES, THEN RUN `:t048-4 sent`. What those lines put on the wire is the one thing
-- not left to the eye: the suite RECORDS every outbound "place" and "sel" (041.2's action stream, an observe
-- surface — it only watches and never preventDefaults) and asserts the two encodings that make these messages
-- what they are. `place` lands at a POINT, floored to the server's posres lattice; `sel` names TILES.
--
-- These are messages A PLAYER SENDS TOO — placing a building, dragging a tile-area tool — so the recorder
-- cannot just read the last two and call them ours. `:t048-4` CLEARS it and `:t048-4 sent` asserts there are
-- EXACTLY two of each: the firings are one :lua statement each, so anything else in the buffer is a hand
-- gesture that landed in between, and the count says so instead of asserting against someone else's send.

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

-- Every outbound "place" and "sel", in order. Armed at LOAD, not by a run: a send cannot be observed after the
-- fact. This records and nothing else — it prints nothing, writes nothing, and never cancels a send.
local sentPlace, sentSel = {}, {}

hafen.event():action():on("place", function(ev) sentPlace[#sentPlace + 1] = ev:args() end)
hafen.event():action():on("sel", function(ev) sentSel[#sentSel + 1] = ev:args() end)

local function run()
  pass, fail, manual = 0, 0, 0
  -- Clear the recorder: from here to `:t048-4 sent`, every "place" and "sel" on the wire should be one of the
  -- four this run is about to ask for. That is what makes the count an assertion rather than a guess.
  sentPlace, sentSel = {}, {}

  local me = hafen.player():gob()
  local here = me and me:position() or nil

  -- Both verbs are on the world, and they are functions rather than a name that happens to read nil.
  check((type(hafen.world().place) == "function") and (type(hafen.world().select) == "function"),
        "hafen.world() answers both new verbs: :place and :select are functions",
        ("place=%s select=%s"):format(type(hafen.world().place), type(hafen.world().select)))

  -- Each is PROTECTED, and each refusal names ITS OWN verb -- not a section and not one shared string, so a
  -- caller reads the exact spelling it has to declare for. Perfectly good arguments are handed in, so nothing
  -- but the gate can be what refuses.
  refuses("hafen.world():place refuses this undeclared addon, naming the verb",
          function() hafen.world():place(here, 0) end, "hafen.world():place: this addon did not declare")
  refuses("hafen.world():select refuses this undeclared addon, naming the verb",
          function() hafen.world():select(here, here) end, "hafen.world():select: this addon did not declare")
  -- ...and the gate runs BEFORE the arguments are looked at (D-213): an addon that may not act at all must
  -- learn THAT, not that it forgot the angle. Which is also why this suite can never see the argument
  -- refusals -- they are the [manual] lines below.
  refuses("hafen.world():place() with no arguments still answers the permission error first",
          function() hafen.world():place() end, "did not declare")

  -- The old doors are shut, each naming where it went -- under BOTH field reads (D-216): the colon call every
  -- shipped addon actually wrote, and the pre-039 dotted one.
  refuses("hafen.act():place throws naming hafen.world():place",
          function() hafen.act():place() end, "is now hafen.world():place(p, angle, button, mods)")
  refuses("the pre-039 dotted hafen.act.place throws naming it too",
          function() return hafen.act.place end, "is now hafen.world():place(p, angle, button, mods)")
  refuses("hafen.act():select throws naming hafen.world():select",
          function() hafen.act():select() end, "is now hafen.world():select(p1, p2, mods)")
  refuses("the pre-039 dotted hafen.act.select throws naming it too",
          function() return hafen.act.select end, "is now hafen.world():select(p1, p2, mods)")

  -- ...and the REST of hafen.act() still answers: the section EMPTIES over this feature (D-117), it does not
  -- disappear from under the verbs that have not moved yet.
  eq("the rest of hafen.act() is still callable: :enabled()", hafen.act():enabled(), false)

  -- This task's own premise: the neighbours place is joining did not move, and did not change. snapPlace with
  -- no `fine` is the TILE CENTRE, which on an 11-unit tile means both components sit exactly at x.5 + 5.
  local sp = here and hafen.world():snapPlace(here) or nil
  check((sp ~= nil) and (type(sp.x) == "function") and (math.abs((sp:x() % 11) - 5.5) < 1e-6)
        and (math.abs((sp:y() % 11) - 5.5) < 1e-6),
        "hafen.world():snapPlace(p) still answers a Position snapped to the tile centre",
        (sp == nil) and "no player position yet -- run this in the world"
                     or ("%.3f, %.3f"):format(sp:x(), sp:y()))
  -- ...and snapAngle still snaps to the client's 45 degree placement-angle grid, in radians.
  check((hafen.world():snapAngle(0.1) == 0)
        and (math.abs(hafen.world():snapAngle(0.8) - (math.pi / 4)) < 1e-9),
        "hafen.world():snapAngle(a) still answers the 45 degree grid, in radians",
        ("0.1->%s  0.8->%s"):format(tostring(hafen.world():snapAngle(0.1)),
                                    tostring(hafen.world():snapAngle(0.8))))

  -- The two argument refusals this suite is structurally blind to, and the two firings whose wire it asserts.
  manualCheck(":lua hafen.world():place(hafen.player():gob():position())",
              "\"hafen.world():place: angle is required\" -- there is no neutral facing to default to. The"
              .. " console owner declares the permission, so it reaches the argument; this suite cannot")
  manualCheck(":lua hafen.world():select({x = 1, y = 2}, hafen.player():gob():position())",
              "\"hafen.world():select: p1 must be a Position\" -- a bare {x, y} table is a widget's pixel"
              .. " pair, not a place in the world")
  manualCheck("with NOTHING on your cursor, fire these two lines and then run  :t048-4 sent  -- placing or"
              .. " area-selecting BY HAND in between puts your own message in the buffer and the count check"
              .. " will say so.  (1)  :lua local p = hafen.player():gob():position();"
              .. " hafen.world():place(hafen.world():snapPlace(p), 0);"
              .. " hafen.world():place(hafen.world():snapPlace(p):offset(11, 0), math.pi / 2)   (2)  :lua"
              .. " local p = hafen.player():gob():position(); hafen.world():select(p:offset(-11, -11),"
              .. " p:offset(11, 11)); hafen.world():select(p:offset(-11, -11), p:offset(11, 11), 4)",
              "no error from either (a `local ...` chunk is a statement, so the console echoes nothing), a"
              .. " ~3x3 tile selection flickers around you, and nothing is placed -- with an empty cursor the"
              .. " server ignores \"place\", which is exactly why the wire is what gets asserted")
  manualCheck("ONLY AFTER  :t048-4 sent  is green: start building something so an object is on your cursor,"
              .. " then  :lua local p = hafen.player():gob():position();"
              .. " hafen.world():place(hafen.world():snapPlace(p), 0)",
              "the object lands snapped to the tile grid at your feet, facing north -- the same message the"
              .. " assertions just read, this time watched")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- `:t048-4 sent` — what the two `[manual]` firings ACTUALLY put on the wire. The suite cannot send (it declares
-- no permissions), but it can watch, so the encodings are assertions rather than an eyeball.
--
-- It asserts EXACTLY TWO of each, and that is the load-bearing part: "place" and "sel" are messages a PLAYER
-- sends too, so a third one in the buffer means a hand gesture landed between the run and here and the pair is
-- no longer identifiable. Reading "the last two" regardless would assert this task's claims against someone
-- else's send and report the mismatch as a broken encoding, which is the one wrong answer available.
local function runSent()
  pass, fail, manual = 0, 0, 0

  local p1, p2 = sentPlace[1], sentPlace[2]
  check(#sentPlace == 2,
        "hafen.world():place sent the MapView \"place\" -- twice, and only twice, since the run cleared the"
        .. " buffer",
        (#sentPlace == 0) and "none seen: run :t048-4 first, then fire the :lua place line"
                           or ("%d recorded -- a place you did BY HAND is in there too; re-run :t048-4 and"
                               .. " fire the line without placing anything yourself"):format(#sentPlace))
  check((p1 ~= nil) and (p2 ~= nil) and (#p1 == 4) and (#p2 == 4),
        "...and each carried four arguments, {rc, angle, button, mods}",
        (p2 == nil) and "not seen: fire the :lua place line first"
                     or ("%d then %d arguments"):format(p1 and #p1 or -1, #p2))
  if (p1 ~= nil) and (p2 ~= nil) and (#p1 == 4) and (#p2 == 4) then
    -- The angle is the SERVER's encoding, round(angle*32768/PI), not radians: 0 stays 0 and a quarter turn
    -- is 16384. That is the one number in this message a caller could not have written itself.
    check((p1[2] == 0) and (p2[2] == 16384),
          "...and the angle is the server's round(angle*32768/PI): 0 rad -> 0, pi/2 rad -> 16384",
          ("%s then %s"):format(tostring(p1[2]), tostring(p2[2])))
    eq("...and button defaults to 1 (confirm)", p2[3], 1)
    eq("...and mods defaults to 0", p2[4], 0)
    -- The point is floored to posres (11/1024 world units per server unit), NOT to tiles and NOT sent raw:
    -- one tile east is 11 world units, which is 1024 server units. Nothing else it could be floored to
    -- gives that number, so this single delta pins the lattice.
    local dx = (type(p1[1]) == "table") and (type(p2[1]) == "table") and (p2[1].x - p1[1].x) or nil
    check((dx ~= nil) and (math.abs(dx - 1024) <= 1),
          "...and the point is floored to POSRES: one tile east is 1024 server units apart, not 1 or 11",
          tostring(dx))
  end

  local s1, s2 = sentSel[1], sentSel[2]
  check(#sentSel == 2,
        "hafen.world():select sent the MapView \"sel\" -- twice, and only twice",
        (#sentSel == 0) and "none seen: run :t048-4 first, then fire the :lua select line"
                         or ("%d recorded -- an area-select you dragged BY HAND is in there too; re-run"
                             .. " :t048-4 and fire the line without selecting anything yourself"):format(#sentSel))
  check((s1 ~= nil) and (s2 ~= nil) and (#s1 == 3) and (#s2 == 3),
        "...and each carried three arguments, {tc1, tc2, mods}",
        (s2 == nil) and "not seen: fire the :lua select line first"
                     or ("%d then %d arguments"):format(s1 and #s1 or -1, #s2))
  if (s1 ~= nil) and (s2 ~= nil) and (#s1 == 3) and (#s2 == 3) then
    -- The corners are floored to TILES: ±11 world units either way spans 22 units, which is 2 tiles. Sent
    -- raw the delta would be 22 and in posres units 2048, so this delta pins the other lattice.
    local a, b = s1[1], s1[2]
    check((type(a) == "table") and (type(b) == "table") and ((b.x - a.x) == 2) and ((b.y - a.y) == 2),
          "...and the corners are floored to TILE coords: 22 world units across is 2 tiles, not 22 or 2048",
          ("(%s,%s)..(%s,%s)"):format(tostring(a and a.x), tostring(a and a.y),
                                      tostring(b and b.x), tostring(b and b.y)))
    eq("...and mods defaults to 0 on the first send", s1[3], 0)
    eq("...and the mods you pass reaches the wire on the second", s2[3], 4)
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-4", function(args)   -- the only way in: a suite does not start itself
  if args[1] == "sent" then runSent() else run() end
end)
