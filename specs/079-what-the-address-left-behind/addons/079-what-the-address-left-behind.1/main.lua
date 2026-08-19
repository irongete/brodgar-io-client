-- 079.1 — the saved variables follow the address. Self-checking suite.
--
-- The claim: a character's saved variables are ONE SESSION'S, held by that session and written into that
-- character's own folder. So s:store() answers for the session it was reached through -- the one on screen
-- and every other -- two logins are two sets of tables and two folders, and neither sees the other's keys.
-- The account scope stays the addon's one file, reached without an address, and each door goes on refusing
-- the other's names. What a session cannot answer is a character it has not got: one that is not live, and
-- one that has not reached the world, are refusals rather than a table that would take writes and lose them.

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
    log("[fail] a character must be in world to run this -- got: the login screen")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  local mine = s:store():get("mine")            -- declared bare: this character's own folder
  local ours = hafen.store():get("ours")        -- declared "scope": "account": one file for the client

  -- What :get hands back is the live table, so writing into it IS saving -- read back in the same run
  -- through a freshly fetched handle rather than through the local above.
  mine.probe = "character-" .. tostring(s:character())
  check(s:store():get("mine").probe == mine.probe,
        "a per-character write reads back through a fresh s:store():get(\"mine\")",
        s:store():get("mine").probe)
  ours.probe = "account-" .. tostring(s:user())
  check(hafen.store():get("ours").probe == ours.probe,
        "an account write reads back through a fresh hafen.store():get(\"ours\")",
        hafen.store():get("ours").probe)

  -- A declared name is always a usable table, and a key nobody wrote is simply absent.
  check((mine.neverWritten == nil) and (ours.neverWritten == nil),
        "a key never written reads nil in both scopes", tostring(mine.neverWritten))

  -- Two scopes means two files, and neither can see into the other.
  ours.acctOnly, mine.charOnly = true, true
  check((ours.charOnly == nil) and (mine.acctOnly == nil) and (ours.probe ~= mine.probe),
        "the two scopes do not see each other's keys",
        tostring(ours.probe) .. " vs " .. tostring(mine.probe))

  -- THE TWO REFUSALS, one per direction: the name picks the door, so the wrong door names the right one.
  refuses("hafen.store():get(\"mine\") refuses, naming the session it is reached through",
          function() return hafen.store():get("mine") end,
          "hafen.session():current():store():get(\"mine\")", "PER CHARACTER")
  refuses("s:store():get(\"ours\") refuses, naming the account door",
          function() return s:store():get("ours") end, "hafen.store():get(\"ours\")")
  refuses("an undeclared name is refused, listing what this addon declares",
          function() return s:store():get("nosuchvariable") end,
          "declares no saved variable", "\"mine\"")

  -- A session that is not live has had its variables written and dropped, so it says so rather than
  -- handing back an empty table that would take writes and never save them.
  refuses("a session that is not live is refused, and the message says its data went to disk",
          function() return hafen.session():get("no-such-account-079-1"):store():get("mine") end,
          "not a live session")

  -- THE CRITERION. The session is named by ACCOUNT rather than reached through :current(), and the write
  -- lands: before this task that raised for every session but the one being drawn.
  local named = hafen.session():get(s:user())
  local okNamed, errNamed = pcall(function() named:store():get("mine").byAccount = s:user() end)
  check(okNamed and (s:store():get("mine").byAccount == s:user()),
        "a per-character write on a session named by ACCOUNT succeeds",
        okNamed and tostring(s:store():get("mine").byAccount) or errNamed)

  -- ...and EVERY session in world holds its own tables: each reads back what was written into it, and no
  -- two of them are the same table. With one character up this is 1 of 1; with two it is the whole claim.
  local inworld, own, tables, distinct, why = 0, 0, {}, true, "none"
  for _, m in ipairs(hafen.session():list()) do
    if m:character() ~= nil then
      inworld = inworld + 1
      local ok, err = pcall(function()
        local t = m:store():get("mine")
        for _, seen in ipairs(tables) do
          if seen == t then distinct = false end
        end
        tables[#tables + 1] = t
        t.whose = m:user()
        if m:store():get("mine").whose == m:user() then own = own + 1 end
      end)
      if not ok then why = tostring(err) end
    end
  end
  check((inworld > 0) and (own == inworld) and distinct,
        "every session in world reads back its OWN value (" .. own .. " of " .. inworld
        .. ", tables distinct: " .. tostring(distinct) .. ")", why)

  -- EACH FLUSH IS ITS OWN SCOPE. A function is a value no saved variable may hold, and it is sitting in
  -- the per-character table -- so the account flush must not see it, and this character's must.
  mine.bad = function() end
  check(pcall(function() return hafen.store():flush() end),
        "hafen.store():flush() writes the account scope alone, and does not read this character's")
  refuses("s:store():flush() refuses this character's uncarriable value, naming where it sits",
          function() return s:store():flush() end, "\"mine\".bad", "function")
  mine.bad = nil

  -- ...and what this run wrote goes to disk now, one file per character, so the folders below exist.
  local landed = pcall(function() hafen.store():flush() end)
  for _, m in ipairs(hafen.session():list()) do
    if m:character() ~= nil then
      landed = pcall(function() m:store():flush() end) and landed
    end
  end
  check(landed, "every session's flush lands, and the account's, once the bad value is gone")

  manualCheck("with two characters in world, run this, then tab to the other character and run it again",
              "the OWN value line reads 2 of 2 both times, and savedata/ holds a <genus>_<char> folder for"
              .. " EACH character, each with its own 079-what-the-address-left-behind.1.json naming that"
              .. " character's own account -- two folders, not one")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t079-1", run)   -- the only way in: a suite does not start itself
