-- 048.1 — hafen.player():move(p) and gob:click(button, mods). Self-checking suite; see specs/addons/TESTING.md.
--
-- The first two verbs to leave hafen.act(): a verb lives with WHAT IT CHANGES, not with what it costs. Both are
-- PROTECTED (the per-addon "actions" permission) and this suite declares none — so the proof that the door is
-- there, and is the right door, is its REFUSAL naming the verb. The firing half is the two `[manual]` lines,
-- run from the :lua console, whose owner declares every permission.

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

local function run()
  -- This suite's premise: we are in the world, so there is a character to move and a Gob to click.
  local g = hafen.player():gob()
  check(g ~= nil, "the player's Gob is live (this suite's premise)", g)
  local p = nil
  if g then
    local ok, v = pcall(function() return g:position() end)
    p = ok and v or nil
  end

  -- The two doors exist, on the things they change.
  eq("hafen.player():move is a function", type(hafen.player().move), "function")
  eq("gob:click is a function", type(hafen.world():gob():get(-1).click), "function")

  -- Both are protected, and the refusal names the verb rather than a namespace.
  refuses("hafen.player():move refuses this undeclared addon, naming the verb",
          function() hafen.player():move(p) end, "hafen.player():move: this addon did not declare")
  refuses("gob:click refuses this undeclared addon, naming the verb",
          function() g:click(3) end, "gob:click: this addon did not declare")
  -- ...and refuses it BEFORE looking the gob up (D-213): a handle is never nil, so id -1 names a gob that has
  -- never been in view, and the answer is still the permission and not "this gob is gone".
  refuses("gob:click checks the gate before the gob: one never in view still answers the permission",
          function() hafen.world():gob():get(-1):click(3) end, "did not declare")

  -- The gate runs BEFORE the argument check (D-213): a bad Position must still come back as the PERMISSION
  -- error, or an undeclared addon learns it typed the wrong argument and never that it may not act at all.
  refuses("hafen.player():move{x=,y=} answers the permission error, not an argument one",
          function() hafen.player():move({ x = 100, y = 100 }) end, "did not declare")
  refuses("hafen.player():move(x, y) answers the permission error, not an argument one",
          function() hafen.player():move(100, 100) end, "did not declare")

  -- The old doors are shut, each naming where its verb went.
  refuses("hafen.act():moveTo throws naming hafen.player():move(p)",
          function() hafen.act():moveTo() end, "is now hafen.player():move(p)")
  refuses("hafen.act():clickGob throws naming gob:click(button, mods)",
          function() hafen.act():clickGob() end, "is now gob:click(button, mods)")
  refuses("the pre-039 dotted hafen.act.moveTo throws naming its replacement too",
          function() return hafen.act.moveTo end, "is now hafen.player():move(p)")

  -- ...and the REST of hafen.act() still answers: the section EMPTIES over this feature (D-117), it does not
  -- disappear from under the verbs that have not moved yet.
  eq("the rest of hafen.act() is still callable: :enabled()", hafen.act():enabled(), false)

  -- The Player's vocabulary is CLOSED, so an unknown verb reports what does exist — and a closed list that
  -- forgot the verb this task added would teach a stale one (D-125).
  refuses("hafen.player():nosuchverb() lists a vocabulary that now includes :move(p)",
          function() hafen.player():nosuchverb() end, ":move(p)")

  manualCheck(":lua hafen.player():move(hafen.player():gob():position():offset(0, -20))",
              "you walk ~20 units north; a destination off the edge of the screen works too")
  manualCheck(":lua hafen.world():gob():nearest(\"terobjs/tree\"):click(3)",
              "that tree's radial menu opens, exactly as right-clicking it does")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-1", run)   -- the only way in: a suite does not start itself
