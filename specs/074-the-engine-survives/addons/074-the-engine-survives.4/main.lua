-- 074.4 — saved variables say which character they are for. Self-checking suite.
--
-- The two tables below are taken ONCE, in the file body, and every run reads and writes through those two
-- references. That is half the claim on its own: a character coming to the screen refills the very table
-- the addon already holds, so a reference cached at load time is still the live one after any number of
-- tabs. The other half is the mark -- one number, written into the per-character table and stamped with
-- the run that wrote it. Tab to another character and the mark you read is theirs; drop this one and bring
-- it back and the mark you read is the one that was in the tables when its session ended.

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

-- THE FILE BODY: both handles are taken here, once for the client, and never taken again.
local pc = hafen.store():get("perchar")     -- this character's, and it changes with the screen
local acct = hafen.store():get("acct")      -- the account's, and it never does

local function run()
  pass, fail, manual = 0, 0, 0

  -- What this character had saved before this run touched anything. This is the value the two manual
  -- gestures below are about, so it is read FIRST, before the checks write anything of their own.
  local was = pc.mark

  check((pc == hafen.store():get("perchar")) and (acct == hafen.store():get("acct")),
        "the tables held since load are still the live ones", tostring(pc) .. " / " .. tostring(acct))

  acct.runs = (acct.runs or 0) + 1
  local n = acct.runs
  check(acct.runs == n, ("the account scope round-trips a write (run %d)"):format(n), acct.runs)

  pc.probe = { n, "here" }
  check((type(pc.probe) == "table") and (pc.probe[1] == n) and (pc.probe[2] == "here"),
        "the per-character scope round-trips a write", pc.probe)

  check((pc.absent == nil) and (acct.absent == nil),
        "a key never written reads nil in both scopes",
        tostring(pc.absent) .. " / " .. tostring(acct.absent))

  pc.onlyChar, acct.onlyAcct = n, n
  check((acct.onlyChar == nil) and (pc.onlyAcct == nil),
        "neither scope sees the other's keys -- which is what having two of them means",
        tostring(acct.onlyChar) .. " / " .. tostring(pc.onlyAcct))

  pc.bad = function() end
  refuses("flush refuses a value a saved variable cannot hold, and names it",
          function() hafen.store():flush() end, "a saved variable may hold")
  pc.bad = nil

  -- The mark, written last so the refusal above cannot have stopped it reaching disk. The flush is the
  -- check: with the bad value gone it must go through, or the two manual gestures prove nothing.
  pc.mark = n
  local ok, err = pcall(function() hafen.store():flush() end)
  check(ok, "with the bad value gone, the same flush writes both scopes", err)

  manualCheck(("tab to another character in the world and re-run me (this character had a mark of %s, and"
               .. " now has %d)"):format(tostring(was), n),
              ("a mark of nil on a character that has never run me, or that character's OWN number --"
               .. " never %d"):format(n))
  manualCheck(("`:session drop` this character, add it back, let it reach the world and re-run me (its"
               .. " mark is %d)"):format(n),
              ("a mark of %d -- written when that session ENDED, since nothing unloaded the addon")
                :format(n))
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t074-4", run)   -- the only way in: a suite does not start itself
