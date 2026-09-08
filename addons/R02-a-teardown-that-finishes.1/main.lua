-- R02 -- a teardown that finishes.
--
-- The teardown is one guarded walk over a list of steps now, and the :lua REPL owner walks the same list.
-- Nothing inside an addon can watch its own teardown, so what is asserted here is every site the block moved
-- onto that walk and can still be read back in one run: the clip that remembers the channel it went to, the
-- radial-menu section the walk now gives back, the entity sweep that destroys each one on its own, and the
-- HUD overlay the REPL owner's sweep had no step for at all. The two manual lines are the half no program in
-- this addon can see -- what a console line owns, across a :reload.
--
-- Run it with :tR02. It starts nothing by itself and leaves nothing behind.

local out, pass, fail, manual = {}, 0, 0, 0

local function line(s) out[#out + 1] = s end

local function ok(desc, cond, got)
  if cond then
    pass = pass + 1
    line("[pass] " .. desc)
  else
    fail = fail + 1
    line("[fail] " .. desc .. " -- got: " .. tostring(got))
  end
end

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function refuses(desc, fn, needle)
  local fine, err = pcall(fn)
  if fine then
    fail = fail + 1
    line("[fail] " .. desc .. " -- got: <no error>")
    return
  end
  local msg = why(err)
  ok(desc, msg:find(needle, 1, true) ~= nil, msg)
end

-- ---------------------------------------------------------------- the checks

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  local s = hafen.session():current()
  if not s then
    hafen.log():write("[fail] the suite needs a character in the world -- got: no current session")
    hafen.log():write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The clip remembers the channel it went to, so the stop reaches THAT one rather than whichever layer is
  -- up when it is asked -- which is what makes a teardown able to silence what it started.
  local blip = hafen.sound():get("sfx/msg")
  blip:play(0.1)
  local sounding = blip:playing()
  blip:stop()
  ok("a clip plays, and :stop() silences it on the channel it went to",
     sounding and not blip:playing(), tostring(sounding) .. " then " .. tostring(blip:playing()))
  ok("hafen.sound():playing() is empty once it is stopped", hafen.sound():playing():count() == 0,
     hafen.sound():playing():count())
  refuses("sound:play refuses a volume outside 0..1", function() blip:play(2) end, "volume must be 0..1")
  refuses("hafen.sound():get refuses a number for a resource name",
          function() return hafen.sound():get(7) end, "the key is a RESOURCE NAME string")

  -- The radial-menu section, whose :visible(false) the walk now gives back: the write is refused with no
  -- ring up, and the read answers nil there, so both halves are asked with nothing open.
  ok("s:flowermenu():visible() answers nil with no ring open", s:flowermenu():visible() == nil,
     tostring(s:flowermenu():visible()))
  refuses("s:flowermenu():visible(0) is refused naming the argument",
          function() s:flowermenu():visible(0) end, "b must be true or false")
  refuses("s:flowermenu():visible(true) is refused with no ring open",
          function() s:flowermenu():visible(true) end, "no radial menu is open on")

  -- The world entities: one destroy is the very call each sweep now makes per entity, guarded on its own.
  local pg = s:player():gob()
  if pg then
    local ghosts = hafen.virtual():ghost()
    local g1 = ghosts:add("gfx/terobjs/arch/logcabin", pg:position())
    local g2 = ghosts:add("gfx/terobjs/arch/logcabin", pg:position())
    local two = ghosts:count()
    ghosts:remove(g1)
    ghosts:remove(g2)
    ok("two ghosts are placed, and each is destroyed on its own",
       (two == 2) and (ghosts:count() == 0) and not g1:exists(),
       two .. " placed, " .. ghosts:count() .. " left")
    refuses("hafen.virtual():ghost():add refuses a resource name that is not a string",
            function() return ghosts:add(7, pg:position()) end, "expects a resource NAME string")
  else
    fail = fail + 2
    line("[fail] the two ghost checks need the player in the world -- got: no player gob")
  end

  -- The HUD overlay: what the REPL owner's own sweep had no step for at all, and now has.
  local ov = hafen.ui():overlay():add("r02probe")
  local one = hafen.ui():overlay():count()
  hafen.ui():overlay():remove("r02probe")
  ok("a HUD overlay is added and taken off again",
     (one == 1) and (hafen.ui():overlay():count() == 0) and not ov:exists(),
     one .. " then " .. hafen.ui():overlay():count())

  -- What no program in this addon can watch: the console owner's own teardown, one :reload away.
  manual = manual + 2
  line("[manual] type: :lua hafen.session():current():world():gob():nearest():overlay():add(\"r02\"):text(\"R02\")"
       .. " -- expect: R02 appears over the nearest object")
  line("[manual] then type: :reload -- expect: the R02 label is gone")

  for _, l in ipairs(out) do hafen.log():write(l) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("tR02", run)
