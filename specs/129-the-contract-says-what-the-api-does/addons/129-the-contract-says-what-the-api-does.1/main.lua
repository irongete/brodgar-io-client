-- 129.1 -- the four comments say what the mechanism does. Self-checking suite: run with :t129.
--
-- The task changed COMMENTS, and prose is not what a suite can read. So it asserts the BEHAVIOUR those
-- comments now describe: Refusal.MOVED and Refusal.KEYS carry no rows, so the three doors that read them
-- fall through -- a section name and a dotted verb to plain nil, an event key to the emitter's own generic
-- refusal. Beside each is the thing that would be broken if the DOOR were, rather than the row: a live
-- section, a live colon verb, a live key. And the two doors that are not unguarded -- Refusal.MISPLACED,
-- which carries live spellings and fires, and a closed vocabulary, which never needed a row -- still throw.
-- That last pair is what makes "reads nil" a statement about an absent row rather than a dead __index.

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

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg" -- a SPACE where a Lua error has a colon. And the
-- sandbox loads a DebugLib for its instruction hard-stop, so every caught error carries a traceback after
-- it; a verdict is ONE line, so that goes too.
local function why(err)
  err = tostring(err):gsub("\nstack traceback:.*", "")
  return (err:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A read that must give nil WITHOUT raising: that is what an empty map means at these two doors.
local function readsNil(what, fn)
  local ok, v = pcall(fn)
  if not ok then
    check(false, what, "raised: " .. why(v))
  else
    check(v == nil, what, v)
  end
end

-- A read or a call that must RAISE, and raise saying every one of `want`.
local function refuses(what, fn, want)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

local function run()
  -- ---- the hafen table's own __index (Refusal.hafenIndex), and no row behind it ----------------------
  -- hafen.meter went onto the Session in 077.1. The comment said reading it throws naming the replacement.
  readsNil("a retired section name reads nil off the hafen table (hafen.meter)",
           function() return hafen.meter end)
  readsNil("...and so does a name that never existed (hafen.nosuchsection)",
           function() return hafen.nosuchsection end)
  check((hafen.log ~= nil) and (tostring(hafen.log()) == "hafen.log()"),
        "...beside them a live section still answers (hafen.log)", hafen.log)

  -- ---- a section's callable table (Refusal.sectionIndex), and no row behind it -----------------------
  -- The dotted spelling. The comment said it is a separate row, so that both call sites are answered.
  readsNil("a dotted verb reads nil off a section's callable table (hafen.log.write)",
           function() return hafen.log.write end)
  readsNil("...and so does one this API hard-cut (hafen.ui.replace, 032.2)",
           function() return hafen.ui.replace end)
  local okj, a = pcall(function() return hafen.json():parse('{"a":1}').a end)
  check(okj and (a == 1), "...beside them the live colon verb still answers (hafen.json():parse)",
        okj and a or why(a))

  -- ---- the event-key door (Refusal.eventKey), and no row behind it ----------------------------------
  -- The four lifecycle keys dropped their On prefix in 041, and 074.3 replaced one of those again. The
  -- comments said those spellings throw naming their replacement; what they meet is the generic refusal,
  -- whose "see docs/..." tail is the half no replacement message would carry.
  refuses("a retired lifecycle key meets the generic unknown-event refusal (OnLoad)",
          function() hafen.event():on("OnLoad", function() end) end,
          {"unknown event 'OnLoad'", "docs/addons/api/event/bus/"})
  refuses("...and so does the spelling SessionEnteredWorld replaced (EnteredWorld)",
          function() hafen.event():on("EnteredWorld", function() end) end,
          {"unknown event 'EnteredWorld'", "docs/addons/api/event/bus/"})
  local oks, sub = pcall(function() return hafen.event():on("SessionAdded", function() end) end)
  local oko = oks and pcall(function() sub:off() end)
  check(oks and oko, "...beside them a live key still subscribes and sub:off() ends it (SessionAdded)",
        oks and "sub:off() failed" or why(sub))

  -- ---- the doors that are NOT unguarded, so the nils above are about the row -------------------------
  refuses("the one table that does carry rows still fires (hafen.console():run)",
          function() return hafen.console().run end,
          {"has no verb 'run'", "s:console():run(line)"})
  refuses("a closed vocabulary still throws, naming what it answers (hafen.log():nosuch)",
          function() return hafen.log().nosuch end,
          {"hafen.log() has no verb 'nosuch'", ":write()"})

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t129", run)   -- the only way in: a suite does not start itself
