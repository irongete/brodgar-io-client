-- 079.2 — an ordinary quit stops losing what an addon wrote. Self-checking suite.
--
-- The claim: quitting the client persists what an addon wrote. The engine flushes every live session's
-- per-character saved variables and every addon's account file BEFORE the process ends, and it does so
-- without running anything an addon wrote; Disable fires on the way out as well, bounded, so an addon that
-- computes its state at teardown gets that write saved too.
--
-- The proof needs two runs of the client, because that is what the defect is about. This run WRITES a token
-- into both scopes and NEVER FLUSHES IT -- no store():flush(), no waiting for the thirty-second auto-save.
-- The next run, after an ordinary quit, reads it back. Before this task it was gone unless thirty seconds
-- happened to pass.

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

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- ---------------------------------------------------------------------------------------------------

-- Disable fires on the way out too, and it is the addon's own last chance to write. The handler stamps the
-- token THIS run wrote, so the next run can tell that the Disable which followed the write is the one that
-- ran -- and it is registered here, at file scope, rather than by :t079-2: a handler armed by the command
-- would say nothing about a client the maintainer had not typed the command in. It starts nothing, waits
-- for nothing and costs nothing until the client is leaving.
hafen.event():on("Disable", function()
  local s = hafen.session():current()
  if s == nil then
    return                                    -- no character on screen: nothing whose folder to write into
  end
  local q = s:store():get("quit")
  q.disabledFor = q.token
end)

-- Has :t079-2 already run in THIS client? If it has, what the tables hold was put there by that run and
-- never went near a file, so the survival lines would be scoring memory. One run per client life is what
-- the proof is made of.
local ranThisLife = false

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if s == nil then
    log("[fail] a character must be in world to run this -- got: the login screen")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  local q = s:store():get("quit")              -- declared bare: this character's own folder
  local a = hafen.store():get("acct")          -- declared "scope": "account": one file for the client

  -- What the LAST run left behind, read before this one overwrites any of it.
  local prevTok, prevAcct, prevDisabled, runs = q.token, a.token, q.disabledFor, q.runs or 0

  -- The store itself, duplicated here so this suite stands alone: a write into the live table is readable
  -- through a freshly fetched handle, in both scopes, in the same run.
  q.probe = "character-" .. tostring(s:character())
  check(s:store():get("quit").probe == q.probe,
        "a per-character write reads back through a fresh s:store():get(\"quit\")",
        s:store():get("quit").probe)
  a.probe = "account-" .. tostring(s:user())
  check(hafen.store():get("acct").probe == a.probe,
        "an account write reads back through a fresh hafen.store():get(\"acct\")",
        hafen.store():get("acct").probe)

  -- THE CRITERION, and it can only be scored on the first run of a client that has something stored.
  if ranThisLife then
    manualCheck("this client has already run :t079-2, so what these tables hold was put there by that run"
                .. " and never went near a file",
                "quit as below and run it once per client, which is the only run that reads from disk")
  elseif runs == 0 then
    manualCheck("nothing is stored on this character yet, so this run has no survival to score",
                "the three lines about the previous run appear the next time this is run, after the quit")
  else
    check((type(prevTok) == "string") and (prevTok ~= ""),
          "the per-character table came back from disk (run " .. runs .. ", token "
          .. tostring(prevTok) .. ")", prevTok)
    check(prevAcct == prevTok,
          "the account file came back too, holding the token that run wrote", prevAcct)
    -- Disable fired on the way out AND what it wrote was flushed: the stamp names the token that was
    -- live when it ran, which is the one the run before this one wrote.
    check(prevDisabled == prevTok,
          "Disable fired on the way out, and what it wrote was saved",
          tostring(prevDisabled) .. " (wanted " .. tostring(prevTok) .. ")")
  end

  -- What THIS run leaves for the next one. Written into the live tables and left there: nothing below
  -- flushes, and the run ends here.
  runs = runs + 1
  -- string.format("%d", ...) and not tostring(): os.time() hands back a Lua DOUBLE, and tostring prints
  -- one in scientific notation at eight significant digits, so the token would be the same string for a
  -- hundred seconds either side of this one.
  local tok = string.format("%d-%d", os.time(), runs)
  q.runs, q.token, q.disabledFor = runs, tok, nil
  a.token = tok

  manualCheck("note the token this run wrote -- \"" .. tok .. "\" -- into \"quit\".token and"
              .. " \"acct\".token, then QUIT ORDINARILY (close the client window, or type :q) and never by"
              .. " killing the process; start it again, log this same character in and run :t079-2",
              "the three lines about the previous run read [pass] and each names \"" .. tok .. "\";"
              .. " nothing here flushed it, so quit at once rather than waiting out the auto-save")

  ranThisLife = true
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t079-2", run)   -- the only way in: a suite does not start itself
