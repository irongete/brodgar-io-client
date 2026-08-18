-- 077.1 — the character sheet belongs to a character. Self-checking suite.
--
-- What it proves: char, meter, buff, study, quest and wound are reached through a Session and nowhere
-- else; each is minted once per (addon, session) and interned on the handle; each reads the character
-- that session names, including one the client is not drawing; a Session the client does not hold
-- answers nil-shaped rather than raising; and all six loose spellings are retired, naming where they
-- went.

local pass, fail, manual = 0, 0, 0
local log = hafen.log()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log:write("[pass] " .. what)
  else
    fail = fail + 1
    log:write("[fail] " .. what .. " -- got: " .. tostring(got))
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
  log:write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The six sections this task moves, each named by the verb that reaches it on a Session.
local SIX = { "char", "meter", "buff", "study", "quest", "wound" }

-- The five that ARE a collection, and the list each hands back: study is the only one whose members
-- sit a collection deeper, so this is where that difference is spent and nowhere else. char is the
-- sixth and is not a list -- its own reads are checked below.
local LISTS = { "meter", "buff", "study", "quest", "wound" }

local function listOf(s, name)
  if name == "study" then return s:study():slot():list() end
  return s[name](s):list()
end

local function count(t) local n = 0 for _ in ipairs(t) do n = n + 1 end return n end

-- ---------------------------------------------------------------- the scored body

local function body(s)
  -- 1. Every one of the six is minted once for this (addon, session) pair and kept on the handle.
  local interned, offender = true, nil
  for _, name in ipairs(SIX) do
    if s[name](s) ~= s[name](s) then interned, offender = false, name end
  end
  check(interned, "all six sections are interned on the Session handle", offender)

  -- 2. Each collection answers a list, or is honestly empty. It is never an error.
  local listed, bad = true, nil
  for _, name in ipairs(LISTS) do
    local ok, l = pcall(listOf, s, name)
    if (not ok) or (type(l) ~= "table") then listed, bad = false, name .. ": " .. tostring(l) end
  end
  check(listed, "each collection answers a list rather than raising", bad)

  -- 3. The bars are this character's, and :find hands back the very object :list() holds. A bar whose
  --    resource is still Loading is nameless for a beat, so the search is run against a named one.
  local bars = s:meter():list()
  local named
  for _, m in ipairs(bars) do if (named == nil) and (m:res() ~= nil) then named = m end end
  check((count(bars) > 0) and (named ~= nil) and (s:meter():find(named:res()) == named),
        "the HUD bars answer and :find is the same object as :list() (" .. count(bars) .. " bars)",
        count(bars) .. " bars, named=" .. tostring(named and named:res()))

  -- 4. The sheet's own scalars, the one place absolute character numbers exist.
  local food = s:char():food()
  check((type(s:char():lp()) == "number") and (type(s:char():weight()) == "number")
        and (food ~= nil) and (type(food:total()) == "number"),
        "the sheet's scalars answer (lp/weight/food)",
        tostring(s:char():lp()) .. "/" .. tostring(s:char():weight()) .. "/" .. tostring(food))

  -- 5. An Attr is interned on its session AND its name, which is what keeps two characters' strength
  --    from being one number.
  local str = s:char():attr():get("str")
  check((str == s:char():attr():get("str")) and (str:name() == "str")
        and (type(str:base()) == "number"), "an attribute is interned per session and answers",
        tostring(str and str:base()))

  -- 6. A Session the client does not hold is still an address: every section answers nil-shaped.
  local ghost = hafen.session():get("no-such-account")
  local ok, err = pcall(function()
    return (count(ghost:meter():list()) == 0) and (count(ghost:wound():list()) == 0)
           and (ghost:char():lp() == nil) and (ghost:char():food() == nil)
           and (ghost:quest():selected() == nil) and (ghost:study():summary() == nil)
  end)
  check(ok and err, "a session the client does not hold answers nil-shaped, not an error",
        ok and "reads disagreed" or err)

  -- 7. The hard cut: reading the loose spelling AT ALL throws, naming the address.
  local retired, missed = true, nil
  for _, name in ipairs(SIX) do
    local o, e = pcall(function() return hafen[name] end)
    e = o and "<no error>" or tostring(e)
    if o or (e:find("session:" .. name .. "()", 1, true) == nil)
         or (e:find("hafen.session():current()", 1, true) == nil) then
      retired, missed = false, name .. " -> " .. e
    end
  end
  check(retired, "all six loose spellings are retired, naming the Session address", missed)

  -- 8..11. The grammar the section objects keep.
  refuses("a dot call on a section object is refused", function() return s:char().lp() end,
          "COLON call")
  refuses("an unknown verb on a section object is refused", function() return s:char():nosuchverb() end,
          "has no verb")
  refuses("an unknown attribute names the nine", function() return s:char():attr():get("nope") end,
          "no such attribute")
  refuses("a quest is addressed by its server id", function() return s:quest():get("first") end,
          "server ID")

  -- The one thing a program cannot judge: whether the numbers belong to the character named.
  local other
  for _, m in ipairs(hafen.session():list()) do if m ~= s then other = m end end
  if other == nil then
    manualCheck("bring a second character up (:session add) and run :t077-1 again",
                "this line is replaced by a reading of BOTH characters' bars")
  else
    local ob, sb = other:meter():list(), bars
    local ohp, shp = other:meter():find("hp"), s:meter():find("hp")
    manualCheck(("drawn %s (%s): %d bars hp=%s | background %s (%s): %d bars hp=%s")
                :format(tostring(s:user()), tostring(s:character()), count(sb),
                        tostring(shp and shp:value()), tostring(other:user()),
                        tostring(other:character()), count(ob), tostring(ohp and ohp:value())),
                "two readings, each that character's own -- damage one character and re-run: only its"
                .. " own hp moves, read from a character you are not looking at")
  end

  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---------------------------------------------------------------- entry

-- The sheet streams in over the seconds after the HUD is up, so a run taken the instant a character
-- enters the world would score a timing gap as a defect. Wait a bounded window for the bars and the
-- learning points, then score whatever the run reached.
local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if s == nil then
    log:write("[fail] there is no session on screen -- log a character in and run :t077-1 again")
    log:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local tries = 0
  local function go()
    tries = tries + 1
    if (tries < 12) and ((count(s:meter():list()) == 0) or (s:char():lp() == nil)) then
      hafen.timer():after(0.5, go)
      return
    end
    body(s)
  end
  go()
end

hafen.slash():register("t077-1", run)   -- the only way in: a suite does not start itself
