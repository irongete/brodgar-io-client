-- 039.12 -- hafen.party() and hafen.fight(): the party roster and combat as entities.
-- Self-checking suite; see specs/testing/addon-suite.md. Run  :t039-12
--
-- The headline is one verb in two places: a party member and the creature you are fighting both hand
-- back a LIVE gob, which nothing in this API could do since the gob went OOP. Both need a state the
-- maintainer has to be in, so each prints its evidence when the state is there and an explicit
-- [manual] naming the command to re-run when it is not -- never a silent skip.
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

-- Does a snapshot carry every REQUIRED key, no key outside required+optional, and nothing else?
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

-- ASCII only in a runtime string: a deck hotkey label is server text and carries a shift arrow.
local function ascii(s)
  return (tostring(s):gsub("[\128-\255]", "?"))
end

local RETIRED = {
  { "hafen.party.members", function() return hafen.party.members end, "hafen.party():list()" },
  { "hafen.party.member",  function() return hafen.party.member end,  "hafen.party():get(gobId)" },
  { "hafen.party.leader",  function() return hafen.party.leader end,  "hafen.party():leader()" },
  { "hafen.fight.maneuvers", function() return hafen.fight.maneuvers end,
    "hafen.fight():maneuver():list(filter)" },
  { "hafen.fight.deck",    function() return hafen.fight.deck end,    "hafen.fight():deck(" },
  { "hafen.fight.summary", function() return hafen.fight.summary end, "hafen.fight():summary(" },
}

local function run()
  pass, fail, manual = 0, 0, 0   -- per RUN, not per session: the suite is run again after
                                 -- partying up or picking a fight, and a cumulative [summary]
                                 -- would not match the lines printed under it
  local party, fight = hafen.party(), hafen.fight()

  -- 1. both sections are per-addon singletons over a callable table, and an unknown verb throws
  local same = 0
  for _, s in ipairs({ "party", "fight" }) do
    if (hafen[s]() == hafen[s]()) and (type(hafen[s]) == "table") and (type(hafen[s]()) == "userdata") then
      same = same + 1
    end
  end
  check(same == 2, "each section is one callable table over one userdata object (" .. same .. "/2)")
  refuses("a section refuses an unknown verb", function() return fight:nosuchverb() end, "has no verb")

  -- 2. every retired spelling throws naming its replacement
  local n, miss = named(RETIRED)
  check(n == #RETIRED, "every retired spelling throws naming its replacement (" .. n .. "/"
        .. #RETIRED .. ")", miss)

  -- 3. the roster IS the section object, and it is an object rather than a sequence
  local noSeq = 0
  if not pcall(function() return #party end) then noSeq = noSeq + 1 end
  if not pcall(function() return party[1] end) then noSeq = noSeq + 1 end
  if not pcall(function() return hafen.party(1) end) then noSeq = noSeq + 1 end
  check(noSeq == 3, "hafen.party() IS the roster and is not a sequence: #, [1] and an argument are all"
        .. " refused (" .. noSeq .. "/3)")
  refuses("a party member is addressed by gob id, never by name",
          function() return party:get("me") end, "no name")

  -- 4. the roster answers outside a party without throwing -- the case that has no members at all
  local roster, leader = party:list(), party:leader()
  check((type(roster) == "table") and (party:count() == #roster)
        and ((leader == nil) or (type(leader) == "userdata")) and (party:get(1) == nil),
        ("the roster answers with %d member(s), leader=%s, and an unknown id is plain nil")
        :format(#roster, (leader == nil) and "none" or "yes"),
        "a read threw or disagreed with :count()")

  -- 5. the maneuver collection: minted once, keyless, and not a sequence
  local man = fight:maneuver()
  local shaped = 0
  if man == fight:maneuver() then shaped = shaped + 1 end
  if not pcall(function() return #man end) then shaped = shaped + 1 end
  if not pcall(function() return fight:maneuver(1) end) then shaped = shaped + 1 end
  if not pcall(function() return man:get("x") end) then shaped = shaped + 1 end
  check(shaped == 4, "the maneuver collection is one object, is not a sequence, and has no :get -- a"
        .. " maneuver has no key (" .. shaped .. "/4)")

  -- 6. maneuvers are interned entities and :info() keeps every field the flat reader had
  local m1 = man:list()[1]
  if m1 then
    local ok, why = shape(m1:info(), { "avail", "used" }, { "res", "name" })
    check(ok and (man:find(m1:res()) == m1) and (m1:available() == m1:info().avail)
          and (m1:used() == m1:info().used) and m1:exists(),
          ("%d maneuver(s) known; '%s' is one interned object and :info() loses nothing")
          :format(man:count(), tostring(m1:name() or m1:res())), why)
  else
    check(false, "a maneuver is one interned object and :info() loses nothing",
          "no maneuver listed -- are you in the world?")
  end

  -- 7. the deck is a plain ARRAY of cards (a layout, R3), each card pointing at its maneuver
  local deck = fight:deck()
  local c1 = deck[1]
  if c1 then
    local ok, why = shape(c1:info(), { "slot", "key" }, { "res", "name", "used" })
    check(ok and (#deck > 0) and c1:exists() and (c1:maneuver() == man:find(c1:res()))
          and (c1:slot() == c1:info().slot) and (fight:deck()[1] == c1),
          ("the deck is a %d-card array; slot %d (%s) holds '%s' and card:maneuver() is that same"
           .. " maneuver object"):format(#deck, c1:slot(), ascii(c1:key()),
                                         tostring(c1:name() or c1:res())), why)
  else
    check(false, "the deck is a plain array of cards, each pointing at its maneuver object",
          "the deck is empty -- load a combat school first")
  end

  -- 8. the summary is one interned entity, and its spend AGREES with the maneuvers it is a summary of
  local sum = fight:summary()
  if sum then
    local ok, why = shape(sum:info(), { "maxact", "used", "nact", "nsave", "usesave" })
    local spent = 0
    for _, mv in ipairs(man:list()) do spent = spent + mv:used() end
    check(ok and (sum == fight:summary()) and sum:exists() and (sum:used() == spent)
          and (sum:maxActions() == sum:info().maxact) and (sum:deckSize() == sum:info().nact)
          and (sum:saveCount() == sum:info().nsave) and (sum:activeSave() == sum:info().usesave)
          and (sum:deckSize() >= #deck),
          ("the summary is one object: %d/%d action points over %d deck slot(s), school %d of %d")
          :format(sum:used(), sum:maxActions(), sum:deckSize(), sum:activeSave(), sum:saveCount()),
          why or ("spend " .. tostring(sum:used()) .. " vs " .. spent .. " summed over the maneuvers"))
  else
    check(false, "the summary is one object whose spend agrees with the maneuvers",
          "no combat schools tab -- are you in the world?")
  end

  -- 9. THE HEADLINE, half one: a party member hands back a LIVE gob.
  local m = roster[1]
  if m then
    local g = m:gob()
    check((type(g) == "userdata") and (g:id() == m:id()) and g:exists()
          and (party:get(m:id()) == m) and (m:leader() == (leader == m)),
          ("a party member resolves its gob: id %s is '%s', and the roster addresses that same"
           .. " member object"):format(tostring(m:id()), tostring(g:name())),
          "gob:exists() " .. tostring(g:exists()) .. ", name " .. tostring(g:name()))
    -- and the member's own position is a Position, agreeing with the gob's while they are in view
    local p, gp = m:position(), g:position()
    check((type(p) == "userdata") and (type(p:durable()) == "boolean")
          and ((gp == nil) or (p:distance(gp) < 11)),
          ("the member's position is a Position (durable=%s) and lands on the gob's own tile")
          :format(tostring(p and p:durable())),
          (p == nil) and "nil -- the server has not placed them yet"
          or ("distance to the gob " .. tostring(gp and p:distance(gp))))
  else
    manualCheck("party up with another character, then run  :t039-12",
                "2 more [pass]: the first member resolves a live gob with a name, the roster addresses"
                .. " that same object, and the member's position is a Position on the gob's own tile")
  end

  -- 10. THE HEADLINE, half two: the creature you are fighting hands back a LIVE gob.
  local t = fight:target()
  if t then
    local g = t:gob()
    check((type(g) == "userdata") and (g:id() == t:id()) and g:exists() and t:exists()
          and (fight:target() == t),
          ("the combat target resolves its gob: id %s is '%s', and the target is one interned object")
          :format(tostring(t:id()), tostring(g:name())),
          "gob:exists() " .. tostring(g:exists()) .. ", name " .. tostring(g:name()))
  else
    manualCheck("attack something and leave it selected, then run  :t039-12",
                "1 more [pass]: hafen.fight():target():gob() names the creature you are fighting and"
                .. " reports :exists() true")
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-12", run)   -- the only way in: a suite does not start itself
