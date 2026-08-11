-- 039.11 -- hafen.char() and hafen.study(): the character sheet as collections of entities.
-- Self-checking suite; see specs/testing/addon-suite.md. Run  :t039-11
--
-- Six new entities (Attr, Skill, Credo, Experience, Food, StudySlot), each interned, each with :info()
-- as the one snapshot escape hatch. The two claims a shape test cannot make on its own are here too:
-- the events hand OBJECTS rather than snapshot tables (watched from load, since a suite cannot make the
-- server push food or study data), and a StudySlot interned on WIDGET identity keeps answering after it
-- leaves the window -- which needs a human to take a curiosity out, so it is the parked 'gone' round.
--
-- It stands alone (D-085): every premise it rests on is asserted here, including the ones other suites
-- also make. It declares no permissions and writes nothing; every read on this page is ungated.

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

-- Count how many of a list of {label, thunk, wantedText} rows throw naming their replacement.
local function named(rows)
  local n, miss = 0, nil
  for _, r in ipairs(rows) do
    local ok, err = pcall(r[2])
    if (not ok) and (tostring(err):find(r[3], 1, true) ~= nil) then
      n = n + 1
    elseif not miss then
      miss = r[1] .. " -> " .. (ok and "<no error>" or tostring(err))
    end
  end
  return n, miss
end

-- Does a snapshot carry every REQUIRED key, no key outside required+optional, and nothing else? That is
-- what "nothing is lost" means for an :info(): a field the flat reader had must still be there, and a
-- field nobody documented must not have appeared.
local function shape(t, required, optional)
  if type(t) ~= "table" then return false, "not a table: " .. type(t) end
  local allowed = {}
  for _, k in ipairs(required) do allowed[k] = true end
  for _, k in ipairs(optional or {}) do allowed[k] = true end
  for k in pairs(t) do
    if not allowed[k] then return false, "unexpected field " .. tostring(k) end
  end
  for _, k in ipairs(required) do
    if t[k] == nil then return false, "missing " .. k end
  end
  return true, "ok"
end

-- The events are watched from LOAD, not from the run: the server pushes food and study data a beat
-- after entering the world and this suite is run by hand much later, so the payload's TYPE has to be
-- recorded when it arrives. Watching is not starting: nothing here runs a check.
local fepSeen, studySeen, studyEmpty = nil, nil, 0
hafen.event():on("FepChanged", function(f)
  if fepSeen then return end
  fepSeen = type(f) .. "/" .. tostring((type(f) == "userdata") and (f:info() ~= nil))
end)
-- Latch the first NON-EMPTY payload, never merely the first: an empty array proves the payload is a
-- table and says nothing about what is IN it, which is the whole claim. An empty-only session reports
-- how many it saw, so the line reads as a precondition rather than as evidence.
hafen.event():on("StudyChanged", function(slots)
  if studySeen then return end
  if slots[1] == nil then
    studyEmpty = studyEmpty + 1
  else
    studySeen = type(slots) .. "/" .. type(slots[1]) .. "/"
                .. tostring((type(slots[1]) == "userdata") and (slots[1]:res() ~= nil))
  end
end)

local RETIRED = {
  { "hafen.char.attr",   function() return hafen.char.attr end,   "hafen.char():attr():get(name)" },
  { "hafen.char.attrs",  function() return hafen.char.attrs end,  "hafen.char():attr():list()" },
  { "hafen.char.lp",     function() return hafen.char.lp end,     "hafen.char():lp(" },
  { "hafen.char.weight", function() return hafen.char.weight end, "hafen.char():weight(" },
  { "hafen.char.food",   function() return hafen.char.food end,   "hafen.char():food(" },
  { "hafen.char.skills", function() return hafen.char.skills end, "hafen.char():skill():list()" },
  { "hafen.char.skill",  function() return hafen.char.skill end,  "hafen.char():skill():find(name)" },
  { "hafen.char.skillsAvailable", function() return hafen.char.skillsAvailable end,
    "hafen.char():skill():available()" },
  { "hafen.char.credos", function() return hafen.char.credos end, "hafen.char():credo():list()" },
  { "hafen.char.experiences", function() return hafen.char.experiences end,
    "hafen.char():experience():list()" },
  { "hafen.study.slots",   function() return hafen.study.slots end,   "hafen.study():slot():list()" },
  { "hafen.study.summary", function() return hafen.study.summary end, "hafen.study():summary(" },
}

local COLLS = {
  { "char", "attr" }, { "char", "skill" }, { "char", "credo" }, { "char", "experience" },
  { "study", "slot" },
}

local parked = nil   -- a StudySlot held across two commands, for the 'gone' round

-- The parked round: run it after taking the parked curiosity OUT of the study window.
local function goneRound()
  if not parked then
    check(false, "a removed StudySlot keeps answering and reports :exists() false",
          "nothing parked -- run :t039-11 first, with a curiosity in study")
  else
    local res, lp = parked:res(), parked:lp()
    check((not parked:exists()) and (res ~= nil),
          ("a removed StudySlot keeps answering (%s, lp=%s) and reports :exists() false")
          :format(tostring(res), tostring(lp)), parked:exists())
    check(hafen.study():slot():find(res or "\0") == nil, "and the window no longer lists it",
          "still listed")
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run(args)
  if args and (args[1] == "gone") then return goneRound() end
  local chr, study = hafen.char(), hafen.study()

  -- 1. both sections are per-addon singletons over a callable table, and both refuse an unknown verb
  local same = 0
  for _, s in ipairs({ "char", "study" }) do
    if (hafen[s]() == hafen[s]()) and (type(hafen[s]) == "table") and (type(hafen[s]()) == "userdata") then
      same = same + 1
    end
  end
  check(same == 2, "each section is one callable table over one userdata object (" .. same .. "/2)")
  refuses("a section refuses an unknown verb", function() return chr:nosuchverb() end, "has no verb")

  -- 2. every retired spelling throws naming its replacement
  local n, miss = named(RETIRED)
  check(n == #RETIRED, "every retired spelling throws naming its replacement (" .. n .. "/"
        .. #RETIRED .. ")", miss)

  -- 3. the five collections are minted once, are objects rather than sequences, and take no arguments
  local one, obj = 0, 0
  for _, c in ipairs(COLLS) do
    local get = function() return hafen[c[1]]()[c[2]](hafen[c[1]]()) end
    if get() == get() then one = one + 1 end
    local okLen = not pcall(function() return #get() end)
    local okIdx = not pcall(function() return get()[1] end)
    local okArg = not pcall(function() return hafen[c[1]]()[c[2]](hafen[c[1]](), 1) end)
    if okLen and okIdx and okArg then obj = obj + 1 end
  end
  check(one == #COLLS, "every collection is the same object every call (" .. one .. "/5)")
  check(obj == #COLLS, "and none is a sequence or takes an argument (" .. obj .. "/5)")

  -- 4. the attribute key set is CLOSED, and a known name is never nil
  local attr = chr:attr()
  check((attr:get("str") == attr:get("str")) and (attr:get("str") ~= attr:get("agi"))
        and (attr:get("psy"):name() == "psy"),
        "an attribute is interned by name and is never nil")
  refuses("an unknown attribute name is refused, naming the nine",
          function() return attr:get("strength") end, "str, agi, int, con, prc, csm, dex, wil, psy")
  refuses("a number is not an attribute name", function() return attr:get(3) end,
          "expected an attribute name")

  -- 5. the four keyless collections carry no :get -- a needle is a search, never an address
  local noget = 0
  for _, c in ipairs({ { chr, "skill" }, { chr, "credo" }, { chr, "experience" }, { study, "slot" } }) do
    local coll = c[1][c[2]](c[1])
    if not pcall(function() return coll:get("x") end) then noget = noget + 1 end
  end
  check(noget == 4, "the four keyless collections have no :get (" .. noget .. "/4)")

  -- 6. the live sheet: a populated attribute reads back, and :info() is exactly {base, comp}
  local a1 = attr:list()[1]
  if a1 then
    local ok, why = shape(a1:info(), { "base", "comp" })
    check(ok and (a1:base() == a1:info().base) and (a1:composite() == a1:info().comp)
          and (attr:get(a1:name()) == a1),
          ("%d attribute(s) populated; %s reads base=%s composite=%s and :info() loses nothing")
          :format(attr:count(), a1:name(), tostring(a1:base()), tostring(a1:composite())), why)
  else
    check(false, "an attribute reads back and :info() loses nothing",
          "no attribute populated -- are you in the world?")
  end

  -- 7. skills: interned, searchable by display name, and the buyable ones are a verb away with a cost
  local s1 = chr:skill():list()[1]
  if s1 then
    local ok, why = shape(s1:info(), { "name", "cost", "known" }, { "res" })
    local avail = chr:skill():available()
    check(ok and s1:known() and (chr:skill():find(s1:name()) == s1)
          and ((avail[1] == nil) or ((type(avail[1]:cost()) == "number") and (not avail[1]:known()))),
          ("%d skill(s) known, %d buyable; '%s' is one interned object and :info() loses nothing")
          :format(chr:skill():count(), #avail, tostring(s1:name())), why)
  else
    check(false, "a skill is one interned object and :info() loses nothing",
          "no known skill -- is the Lore and Skills window up?")
  end

  -- 8. credos: interned, and the pursued one is a MEMBER of the same collection, not a shape apart
  local c1, pursuing = chr:credo():list()[1], chr:credo():pursuing()
  if c1 then
    local ok, why = shape(c1:info(), { "name", "acquired", "pursuing" },
                          { "res", "level", "levelTotal", "quest", "questTotal", "questId" })
    check(ok and (chr:credo():find(c1:name()) == c1)
          and ((pursuing == nil) or (pursuing:pursuing() and (pursuing:level() ~= nil)))
          and (type(chr:credo():cost()) == "number"),
          ("%d credo(s), pursuing=%s, cost=%s; the pursued one is a member like any other")
          :format(chr:credo():count(), pursuing and tostring(pursuing:name()) or "none",
                  tostring(chr:credo():cost())), why)
  else
    check(false, "a credo is interned and the pursued one is a member like any other",
          "no credo listed -- is the Lore and Skills window up?")
  end

  -- 9. lore: interned by RESOURCE, and mtime survives the rename of the verb that reads it
  local e1 = chr:experience():list()[1]
  if e1 then
    local ok, why = shape(e1:info(), { "res", "score", "mtime" }, { "name" })
    check(ok and (e1:modified() == e1:info().mtime) and (chr:experience():find(e1:res()) == e1),
          ("%d lore entr(ies); '%s' is interned by resource and :info() loses nothing")
          :format(chr:experience():count(), tostring(e1:name())), why)
  else
    check(false, "a lore entry is interned by resource and :info() loses nothing",
          "no lore seen -- is the Lore and Skills window up?")
  end

  -- 10. food: one interned object, and every flat verb agrees with the snapshot it hands out
  local food = chr:food()
  if food then
    local i = food:info()
    check((food == chr:food()) and (i.fep ~= nil) and (food:cap() == i.fep.cap)
          and (food:total() == i.fep.total) and (#food:feps() == #i.fep.entries)
          and ((i.hunger == nil) or ((food:hunger() == i.hunger.level)
                                     and (food:efficacy() == i.hunger.efficacy))),
          ("food is one object: %d fep group(s), total %s of %s, hunger %s")
          :format(#food:feps(), tostring(food:total()), tostring(food:cap()),
                  tostring(food:label() or food:hunger())),
          "a verb and :info() disagree")
  else
    check(false, "food is one object and every verb agrees with :info()",
          "no base-attributes tab -- are you in the world?")
  end

  -- 11. study: the slots are interned, and the totals still read beside them
  local slots, sum = study:slot():list(), study:summary()
  parked = slots[1]
  if parked then
    local ok, why = shape(parked:info(), { "res" },
                          { "name", "lp", "attention", "cost", "time", "progress" })
    check(ok and (study:slot():list()[1] == parked) and parked:exists()
          and (parked:lp() == parked:info().lp) and (sum ~= nil) and (sum.lp ~= nil),
          ("%d study slot(s), totals lp=%s att=%s; '%s' is interned and :info() loses nothing")
          :format(#slots, tostring(sum and sum.lp), tostring(sum and sum.attention),
                  tostring(parked:name() or parked:res())), why)
  else
    check(false, "a study slot is interned and :info() loses nothing",
          "the study window holds nothing -- put a curiosity in it")
  end

  -- 12. the two events hand OBJECTS, not snapshot tables (recorded from load; they fire at login)
  check(fepSeen == "userdata/true", "FepChanged carried a Food object with a live :info()",
        fepSeen or "not seen this session -- eat something, then run this again")
  check(studySeen == "table/userdata/true",
        "StudyChanged carried an array whose members are StudySlot objects (" .. tostring(studySeen)
        .. ")",
        studySeen or (studyEmpty .. " empty payload(s) seen and no other -- put a curiosity into the"
                      .. " study window, then run this again"))

  -- 13. the one claim only a human can set up: widget identity outliving the window
  manualCheck("take the curiosity '" .. (parked and tostring(parked:name() or parked:res()) or "<none>")
              .. "' out of the study window, then run  :t039-11 gone",
              "2 more [pass]: it still reads its own res and lp, :exists() is false, and the window"
              .. " no longer lists it")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-11", run)   -- the only way in: a suite does not start itself
