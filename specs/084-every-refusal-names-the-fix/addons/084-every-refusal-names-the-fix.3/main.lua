-- 084.3 -- the collection says what it has not got. Self-checking suite.
--
-- Thirteen collections have no :get, because their members have no key. Asking for one used to get
-- "<coll> has no verb 'get'" -- true, and naming nothing to write instead. Each source now declares
-- the sentence its refusal carries, and the proof is in two halves: the message names a verb, and
-- THAT VERB ANSWERS on the very collection that named it. A hint pointing at nothing would pass the
-- first half on its own.
--
-- The same sentence is what an accessor quotes, which is the marker defect: one refusal written for
-- five map collections sent every caller to a :get the marker collection has not got.
--
-- And where there IS a :get, what a key naming nothing gives back is declared per collection -- nil,
-- an object, or an error. All three are read off a live one.
--
-- Seven of the thirteen hang off a session, so the sweep RETRIES over a bounded window and scores
-- what it reached, printing the names it did not.

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

local function err(ok, e)
  if ok then return "<no error>" end
  return (tostring(e):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING what to write -- and, where the whole
-- point is that it taught a wrong fix, NOT saying the thing that was wrong.
local function refuses(what, fn, wants, forbids)
  local ok, e = pcall(fn)
  local msg = err(ok, e)
  local why = ok and "<no error>" or nil
  for _, w in ipairs(wants) do
    if (not why) and (not msg:find(w, 1, true)) then why = "no '" .. w .. "': " .. msg end
  end
  for _, f in ipairs(forbids or {}) do
    if (not why) and msg:find(f, 1, true) then why = "still names '" .. f .. "': " .. msg end
  end
  check(why == nil, what, why)
end

local function S() return hafen.session():current() end

-- ---------------------------------------------------- the thirteen collections that have no :get
--
-- name   how the bridge spells the collection, which its message must lead with
-- verbs  the verbs its hint must name -- each also has to ANSWER on the collection
-- reach  the live collection, or nil when there is no session to reach it through

local KEYLESS = {
  { name = "hafen.timer()", verbs = { "after", "every", "list" },
    reach = function() return hafen.timer() end },
  { name = "hafen.map():marker()", verbs = { "find", "nearest" },
    reach = function() return hafen.map():marker() end },
  { name = "hafen.vr():ghost()", verbs = { "add", "find" },
    reach = function() return hafen.vr():ghost() end },
  { name = "hafen.vr():sprite()", verbs = { "add", "find" },
    reach = function() return hafen.vr():sprite() end },
  { name = "hafen.vr():object()", verbs = { "add", "find" },
    reach = function() return hafen.vr():object() end },
  { name = "hafen.vr():widget()", verbs = { "add", "find" },
    reach = function() return hafen.vr():widget() end },
  { name = "session:buff()", verbs = { "find", "list" },
    reach = function() local s = S() return s and s:buff() end },
  { name = "session:meter()", verbs = { "find", "list" },
    reach = function() local s = S() return s and s:meter() end },
  { name = "session:char():credo()", verbs = { "find", "pursuing" },
    reach = function() local s = S() return s and s:char():credo() end },
  { name = "session:char():experience()", verbs = { "find" },
    reach = function() local s = S() return s and s:char():experience() end },
  { name = "session:char():skill()", verbs = { "find", "available" },
    reach = function() local s = S() return s and s:char():skill() end },
  { name = "session:study():slot()", verbs = { "find", "list" },
    reach = function() local s = S() return s and s:study():slot() end },
  { name = "session:fight():maneuver()", verbs = { "find", "list" },
    reach = function() local s = S() return s and s:fight():maneuver() end },
}

-- One collection, two questions, each scored on its own line so a failure says which half broke.
-- Returns two reasons, nil where that half held.
local function probe(t, coll)
  local head = t.name .. " has no verb 'get'"

  local ok, e = pcall(function() return coll:get("anything") end)
  local msg = err(ok, e)
  local ok2, e2 = pcall(function() local _ = coll.get end)
  local fmsg = err(ok2, e2)

  -- 1. both doors refuse, and the refusal carries a hint in the closedIndex shape naming this
  --    collection's own verbs. A field read and a colon call are one lookup.
  local said
  if ok then said = "the call read nil"
  elseif ok2 then said = "the field read nil"
  elseif not msg:find(head .. " — ", 1, true) then said = "head/separator: " .. msg
  elseif not fmsg:find(head, 1, true) then said = "field: " .. fmsg
  else
    for _, v in ipairs(t.verbs) do
      if not msg:find(t.name .. ":" .. v, 1, true) then
        said = "hint has no " .. t.name .. ":" .. v .. ": " .. msg
        break
      end
    end
  end

  -- 2. every verb the hint named is a verb this collection HAS. A sentence pointing at a verb that
  --    is not there is the same failure as no sentence, one step later.
  local answers
  for _, v in ipairs(t.verbs) do
    local vok, got = pcall(function() return coll[v] end)
    if not vok then answers = ":" .. v .. " is not on it: " .. err(vok, got) break end
    if type(got) ~= "function" then answers = ":" .. v .. " is " .. type(got) break end
  end

  return said, answers
end

local reached, broke, kin = {}, { said = {}, answers = {} }, { done = false, why = nil }

local function sweep()
  for _, t in ipairs(KEYLESS) do
    if not reached[t.name] then
      local ok, coll = pcall(t.reach)
      if ok and coll then
        reached[t.name] = true
        local said, answers = probe(t, coll)
        if said then broke.said[#broke.said + 1] = t.name .. " (" .. said .. ")" end
        if answers then broke.answers[#broke.answers + 1] = t.name .. " (" .. answers .. ")" end
      end
    end
  end
  if not kin.done then
    local s = S()
    if s then
      kin.done = true
      local byId = s:kin():get(2147483000)
      local byName = s:kin():get("nobodyhereatall")
      if byId == nil then kin.why = ":get(id) answered nil"
      elseif byId:exists() ~= false then kin.why = ":get(id):exists() is " .. tostring(byId:exists())
      elseif byName ~= nil then kin.why = ':get("<no such name>") answered ' .. tostring(byName) end
    end
  end
end

-- --------------------------------------------------------------------------- the verdict

local function verdict()
  local n, missing = 0, {}
  for _, t in ipairs(KEYLESS) do
    if reached[t.name] then n = n + 1 else missing[#missing + 1] = t.name end
  end
  local scored = " (" .. n .. "/" .. #KEYLESS .. " reached; "
    .. ((#missing == 0) and "all reached" or ("not reached: " .. table.concat(missing, ", "))) .. ")"

  check(#broke.said == 0,
        "every keyless collection refuses :get on both doors, naming its own entry verbs" .. scored,
        table.concat(broke.said, "; "))
  check(#broke.answers == 0,
        "and every verb those refusals name is a verb that collection has" .. scored,
        table.concat(broke.answers, "; "))

  -- The accessor quotes the collection instead of writing one shape for five of them.
  refuses("hafen.map():marker(1) sends you to :find and :nearest, not to a :get it has not got",
          function() return hafen.map():marker(1) end,
          { "hafen.map():marker():find(", "hafen.map():marker():nearest(" }, { ":get" })

  -- ...and the four that DO have one are sent to it by the word that collection calls its key, which
  -- is the same word its own arity refusal uses. Two messages naming one argument two ways is the
  -- defect one step smaller.
  local KEYED = { { "segment", "id" }, { "grid", "gridId" }, { "icon", "res" }, { "overlay", "tag" } }
  local said = {}
  for _, k in ipairs(KEYED) do
    local nm, word = k[1], k[2]
    local map = hafen.map()
    local aok, ae = pcall(function() return map[nm](map, 1) end)
    local gok, ge = pcall(function() return map[nm](map):get() end)
    local amsg, gmsg = err(aok, ae), err(gok, ge)
    local want = "hafen.map():" .. nm .. "():get(" .. word .. ")"
    if not amsg:find(want, 1, true) then
      said[#said + 1] = nm .. " accessor: " .. amsg
    elseif not gmsg:find("hafen.map():" .. nm .. "():get: " .. word .. " is required", 1, true) then
      said[#said + 1] = nm .. " arity: " .. gmsg
    end
  end
  check(#said == 0,
        "and the four that do have a :get send you to it by the word they call their key, the word"
        .. " their own arity refusal uses", table.concat(said, "; "))

  -- The three miss behaviours, each read off the collection that declared it.
  local absent = hafen.session():get("nobodyhereatall")
  check((absent ~= nil) and (absent:exists() == false),
        "a miss that MINTS: hafen.session():get(<no such account>) is an object, :exists() false",
        (absent == nil) and "nil" or tostring(absent:exists()))
  check(kin.why == nil,
        "session:kin() keeps the MINT it declares: :get(id) is an object with :exists() false, while"
        .. " the NAME form is a lookup and answers nil"
        .. (kin.done and "" or " (not reached: no session)"),
        kin.why)
  local cat = hafen.map():icon():get("gfx/terobjs/mm/nosuchcategory")
  check(cat == nil,
        "a miss that answers nil: hafen.map():icon():get(<no such category>)", cat)
  refuses("a miss that RAISES: hafen.asset():get(\"no.png\") names the file it wanted",
          function() return hafen.asset():get("no.png") end, { "no.png" })
  local fok, fe = pcall(function() return hafen.font():get("nosuchface") end)
  local ook, oe = pcall(function() return hafen.map():overlay():get("nosuchtag") end)
  local fmsg, omsg = err(fok, fe), err(ook, oe)
  check((not fok) and (not ook)
          and (fmsg:find("hafen.font():get", 1, true) ~= nil)
          and (omsg:find("no such overlay", 1, true) ~= nil),
        "and the other closed sets raise too: hafen.font() and hafen.map():overlay()",
        "font: " .. fmsg .. " | overlay: " .. omsg)

  -- A key that IS there still answers: the door was narrowed, not shut.
  local snd = hafen.sound():get("sfx/msg")
  check((snd ~= nil) and (snd:res() == "sfx/msg"),
        "and a key that names something still answers: hafen.sound():get(\"sfx/msg\")",
        (snd == nil) and "nil" or tostring(snd:res()))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The seven session-addressed collections are there as soon as a character is; the six others are
-- there from the start. Eight seconds, then whatever it reached.
local TICKS, EVERY = 8, 1.0

local function run()
  pass, fail, manual = 0, 0, 0
  reached, broke, kin = {}, { said = {}, answers = {} }, { done = false, why = nil }

  local left, finished = TICKS, false
  sweep()
  local timer
  timer = hafen.timer():every(EVERY, function()
    if finished then return end             -- the verdict is written once, whatever the timer does
    sweep()
    left = left - 1
    local done = kin.done
    for _, t in ipairs(KEYLESS) do
      if not reached[t.name] then done = false break end
    end
    if done or (left <= 0) then
      finished = true
      timer:cancel()
      verdict()
    end
  end)
end

hafen.slash():register("t084-3", run)       -- the only way in: a suite does not start itself
