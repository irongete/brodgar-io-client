-- 048.7 — the two deletions, and the end of the section. Self-checking suite; see specs/testing/addon-suite.md.
--
-- hafen.act() was the one section grouped by PERMISSION rather than by what it acts on. 048 moved nine verbs
-- onto the things they change and deleted the tenth; this task deletes the last two -- flower (047.2 built the
-- better door: hafen.flowermenu():select(label|n) RAISES where flower answered a bare false, takes a ring
-- position as well as a caption, and has :cancel() beside it) and enabled (its answer was a fact about the
-- CALLER's own manifest.json, so it detected nothing) -- and then the section itself.
--
-- The retirement is what makes that safe, and it has TWO levels. hafen.act is a SECTION name, so its row hangs
-- off the hafen table's own __index: reading `hafen.act` AT ALL now throws, instead of the "attempt to call a
-- nil value" a plain deletion leaves one line later, in a message that names neither the verb nor the file. The
-- ten per-verb rows still exist under it (Retired is the feature's before/after INVENTORY -- a spelling that
-- moved with no row is a porting error nobody is told about), and the section row is simply the one thing in
-- front of them all. So the sweep below asserts every verb name through the read a ported addon actually makes,
-- and requires the message it gets back to name THAT verb's replacement.
--
-- Everything here is a deletion, so everything here is assertable: no [manual] line.

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

-- The whole retirement inventory, in the order spec.md's map lists it: the old verb name, and the sentence its
-- replacement is named by. Data, not prose -- which is the point of Retired being data too.
local VERBS = {
  { "moveTo",    "hafen.player():move(p)" },
  { "clickGob",  "gob:click(button, mods)" },
  { "useItemOn", "hafen.player():hand():use(target, mods)" },
  { "item",      "item:use(mods) / :take() / :drop(n) / :transfer(n)" },
  { "place",     "hafen.world():place(p, angle, button, mods)" },
  { "select",    ":select(p1, p2, mods)" },
  { "menu",      "hafen.menugrid():get(name):use()" },
  { "raw",       "widget:send(msg, ...)" },
  { "flower",    "hafen.flowermenu():select(label|n)" },
  { "enabled",   "hafen.act():enabled() is gone" },
}

local function run()
  pass, fail, manual = 0, 0, 0

  -- THE SECTION IS GONE, and it is gone LOUDLY. Both shapes a ported addon can write reach the same row,
  -- because the field read is what throws -- the call never happens.
  refuses("hafen.act() throws as a SECTION, naming where the verbs went",
          function() return hafen.act() end, "hafen.act() is gone: every verb moved to what it changes")
  refuses("...and so does a bare read of hafen.act, before any verb name is looked at",
          function() local x = hafen.act; return x end, "is gone: every verb moved")

  -- THE FULL ROW SWEEP. Ten reads, ten replacements, one loop -- which is what makes the retirement table's
  -- coverage mechanical rather than remembered. `misses` names the verb AND what came back, so one red line is
  -- enough to diagnose without the conversation.
  local misses = {}
  for _, row in ipairs(VERBS) do
    local verb, want = row[1], row[2]
    local ok, err = pcall(function() return hafen.act[verb] end)
    if ok or (tostring(err):find(want, 1, true) == nil) then
      misses[#misses + 1] = verb .. "->" .. (ok and "<no error>" or tostring(err):sub(1, 60))
    end
  end
  check(#misses == 0,
        "all ten hafen.act verb names throw, each naming its own replacement (" .. #VERBS .. " of " .. #VERBS .. ")",
        table.concat(misses, " | "))

  -- ...and the loudness is TARGETED, not blanket: a name this table never registered still reads as plain nil,
  -- which is what keeps a feature probe (`if hafen.something then`) honest.
  check(hafen.nosuchsection == nil,
        "a name that was never retired still reads nil, so a feature probe still works",
        tostring(hafen.nosuchsection))

  -- THE MESSAGE MUST NOT LIE. Every door it sends a caller to is read back here, off the very handles the
  -- message spells -- including gob:click, on hafen.world():gob():get(-1), which interns rather than searches
  -- and so is a real receiver with no world behind it.
  local ghost = hafen.world():gob():get(-1)
  local doors = {
    { "hafen.player():move",        hafen.player().move },
    { "hafen.player():hand",        hafen.player().hand },
    { "gob:click",                  ghost and ghost.click },
    { "hafen.world():place",        hafen.world().place },
    { "hafen.world():select",       hafen.world().select },
    { "hafen.menugrid():get",       hafen.menugrid().get },
    { "hafen.flowermenu():select",  hafen.flowermenu().select },
    { "hafen.flowermenu():cancel",  hafen.flowermenu().cancel },
  }
  local absent = {}
  for _, d in ipairs(doors) do
    if type(d[2]) ~= "function" then absent[#absent + 1] = d[1] .. "=" .. type(d[2]) end
  end
  check(#absent == 0,
        "every replacement the retirement message names RESOLVES LIVE (" .. #doors .. " doors)",
        table.concat(absent, " | "))

  -- flower's replacement is PROTECTED, and it refuses this (undeclared) addon naming the verb. Called with no
  -- argument on purpose: the gate runs before the argument check (D-213), so the permission error is what must
  -- come back -- an addon that may not act at all is told THAT, not that it forgot a petal.
  refuses("hafen.flowermenu():select refuses this undeclared addon, naming the verb",
          function() hafen.flowermenu():select() end, "hafen.flowermenu():select: this addon did not declare")
  refuses("hafen.flowermenu():cancel refuses it too, naming its own verb",
          function() hafen.flowermenu():cancel() end, "hafen.flowermenu():cancel: this addon did not declare")

  -- ...while the READ half of that same section answers for this same undeclared addon in this same run: the
  -- section 048.7 closed the other door onto is whole, and 047's premise still holds where it was left.
  local petals = hafen.flowermenu():list()
  local n, g = hafen.flowermenu():count(), hafen.flowermenu():gob()
  check((type(petals) == "table") and (n == #petals) and ((g == nil) or (type(g:id()) == "number")),
        "hafen.flowermenu() still READS for this same addon: :list() :count() :gob()",
        ("#list=%s count=%s gob=%s"):format(tostring(#petals), tostring(n), tostring(g)))

  -- ActApi is edited heavily by this task -- installAct deleted whole, actFlower and the petal lookup with it --
  -- so its two SURVIVING sections are asserted here. Neither belonged to the dissolved one: each is named for
  -- what it acts on, which is the shape 048 gave the other nine verbs.
  local okc, craft = pcall(function() return hafen.craft():current() end)
  check(okc and ((craft == nil) or (type(craft:name()) == "string")),
        "hafen.craft():current() still answers (nil with no recipe open)",
        okc and tostring(craft) or tostring(craft))
  local oks, spd = pcall(function() return hafen.speed():current() end)
  check(oks and ((spd == nil) or (type(spd) == "number")),
        "hafen.speed():current() still answers (the read half, unprotected)",
        oks and tostring(spd) or tostring(spd))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-7", run)   -- the only way in: a suite does not start itself
