-- 078.3 — saved variables know whose they are. Self-checking suite.
--
-- The claim: a scope is an address. A character's saved variables are that character's own folder and are
-- reached through its session; an account's are this addon's one file and are reached without naming
-- anyone. Scope is declared in the manifest, so the NAME picks the door, each door refuses the other's
-- names, and a session that is not the one on screen refuses rather than handing back the wrong
-- character's data. Each flush writes its own scope and nothing else.

local pass, fail, manual = 0, 0, 0

local function log(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every `want` in the message.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- ---------------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if s == nil then
    log("[fail] a session must be on screen -- got: the login screen")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  local mine = s:store():get("mine")            -- declared bare: this character's own folder
  local ours = hafen.store():get("ours")        -- declared "scope": "account": one file for the client
  local wasOwner, wasStamp = mine.owner, ours.stamp   -- what a PREVIOUS run left, read before this one writes

  -- The handle is one per (addon, session), like every other section on a Session.
  check(s:store() == s:store(), "s:store() is minted once and handed back by identity", tostring(s:store()))

  -- What :get hands back is the live table, so writing into it IS saving -- both halves, both read back
  -- in the same run through a freshly fetched handle rather than through the local above.
  ours.probe = "account-" .. tostring(s:user())
  check(hafen.store():get("ours").probe == ours.probe,
        "the account table is live: a write reads back through hafen.store():get(\"ours\")",
        hafen.store():get("ours").probe)
  mine.probe = "character-" .. tostring(s:character())
  check(s:store():get("mine").probe == mine.probe,
        "this character's table is live: a write reads back through s:store():get(\"mine\")",
        s:store():get("mine").probe)

  -- A declared name is always a usable table, and a key nobody wrote is simply absent.
  check((mine.neverWritten == nil) and (ours.neverWritten == nil),
        "a key never written reads nil in both scopes", tostring(mine.neverWritten))

  -- Two scopes means two files, and neither can see into the other -- which is the whole of what having
  -- two of them is for.
  ours.acctOnly, mine.charOnly = true, true
  check((ours.charOnly == nil) and (mine.acctOnly == nil) and (ours.probe ~= mine.probe),
        "the two scopes do not see each other's keys",
        tostring(ours.probe) .. " vs " .. tostring(mine.probe))

  -- THE TWO REFUSALS, one per direction. The name picks the door, so the wrong door names the right one.
  refuses("hafen.store():get(\"mine\") refuses, naming the session it is reached through",
          function() return hafen.store():get("mine") end,
          "hafen.session():current():store():get(\"mine\")", "PER CHARACTER")
  refuses("s:store():get(\"ours\") refuses, naming the account door",
          function() return s:store():get("ours") end, "hafen.store():get(\"ours\")")
  -- ...and the pre-colon field read of a per-character name lands on the same answer.
  refuses("hafen.store.mine refuses, naming the session door too",
          function() return hafen.store.mine end,
          "hafen.session():current():store():get(\"mine\")")

  -- THE ADDRESS EXISTS FOR EVERY SESSION, and a session that is not the one on screen says so instead of
  -- answering: the client holds one set of per-character tables and they hold the character being drawn.
  refuses("a session that is not on screen refuses rather than answering another character's data",
          function() return hafen.session():get("no-such-account-078-3"):store():get("mine") end,
          "session:store():get(\"mine\")", "not in memory")

  -- EACH FLUSH IS ITS OWN SCOPE. A function is a value no saved variable may hold, and it is sitting in
  -- the per-character table -- so the account flush must not see it, and this character's must.
  mine.bad = function() end
  check(pcall(function() return hafen.store():flush() end),
        "hafen.store():flush() writes the account scope alone, and does not read this character's")
  refuses("s:store():flush() refuses this character's uncarriable value, naming where it sits",
          function() return s:store():flush() end, "\"mine\".bad", "function")
  mine.bad = nil

  -- ...and what this run leaves is written now, so the manual round trip below has something to read.
  mine.owner = tostring(s:character())
  ours.stamp = ours.stamp or tostring(s:character())      -- stamped once, by whoever ran first
  check(pcall(function() s:store():flush() end) and pcall(function() hafen.store():flush() end),
        "both flushes land once the value a saved variable cannot hold is gone")

  manualCheck("with two sessions up, run on one character and then tab and run on the other -- this run"
              .. " is " .. tostring(s:character()) .. ", and it read owner=" .. tostring(wasOwner)
              .. " stamp=" .. tostring(wasStamp),
              "owner is nil the first time you run on a character and that character's name every time"
              .. " after; stamp is the FIRST character's whichever one is up -- one folder each, one file"
              .. " for the client")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t078-3", run)   -- the only way in: a suite does not start itself
