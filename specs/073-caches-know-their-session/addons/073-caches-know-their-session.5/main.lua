-- 073.5 — the rest, and the last copy of the view. Self-checking suite.
--
-- Two things moved here. An HTTP request's completion is captured on a pool thread that holds no tree and
-- can trust no anchor, and it is filed under the session of the ADDON that made the request -- so a callback
-- that ever fires is proof the enqueue found the right session's queue and that session's tick drained it.
-- And "<genus>_<char>" is now the character ONE session is playing, so every per-character path is built
-- from the addon's own session: a :flush() that raises nothing is a scope that resolved. With one session
-- live both hold exactly what they held before, which is the claim, and every line below reads it back
-- through the very paths that were rewired.
--
-- It writes into its own two saved variables, because round-tripping the store is what it is here to prove,
-- and clears them again before it ends -- so what it leaves on disk is two empty files of its own.

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local OK_URL   = "https://example.com/"      -- the one host this addon's manifest allows
local BAD_URL  = "https://not-allowed.invalid/"   -- and one it does not
local WINDOW   = 8.0                          -- how long the network has to answer before the run says so
local STEP     = 0.25

-- Everything the run has to put back, whatever happens on the way.
local pending                                 -- the live request, so a failed run can cancel it

local function cleanup()
  if pending ~= nil then
    pcall(function() pending:cancel() end)
    pending = nil
  end
  local ok = pcall(function()
    hafen.store():get("probe").value = nil
    hafen.store():get("acct").value = nil
    hafen.store():flush()
  end)
  return ok
end

local function body()
  -- THE INDEX EVERY CONVERSION IN THIS FEATURE HANGS ON: one state per session, no more and no fewer.
  local s = hafen.client():profiling():session()
  check(s.states == s.live, "every session the client holds has exactly one state",
        tostring(s.states) .. " states for " .. tostring(s.live) .. " live")

  -- THE STORE. What :get hands back is the live table itself, and the addon's per-character folder is now
  -- resolved through the session it runs for -- so the write, the read-back and the flush are one path.
  local probe = hafen.store():get("probe")
  check(probe == hafen.store():get("probe"), "hafen.store():get hands back the same live table each call",
        tostring(probe))
  probe.value = { n = 42, s = "seven", t = { true, false } }
  local back = hafen.store():get("probe").value
  check((back ~= nil) and (back.n == 42) and (back.s == "seven") and (back.t[1] == true),
        "a table written into it reads back in the same run",
        (back == nil) and "nil" or (tostring(back.n) .. ", " .. tostring(back.s)))
  check(hafen.store():get("probe").neverWritten == nil, "a key it never wrote reads nil",
        tostring(hafen.store():get("probe").neverWritten))
  hafen.store():get("acct").value = "account scope"
  check(hafen.store():get("acct").value == "account scope",
        "the account-scope table round-trips beside it", tostring(hafen.store():get("acct").value))
  refuses("a name the manifest does not declare is refused, listing the ones it does",
          function() hafen.store():get("cfg") end, 'Declared: "probe"')
  local flushed = pcall(function() hafen.store():flush() end)
  check(flushed, "hafen.store():flush() writes this character's file -- the per-character scope resolved",
        "it raised")

  -- THE ALLOWLIST, refused synchronously at call, before any I/O leaves the client.
  refuses("a host the manifest does not allow is refused, naming the allowlist",
          function() hafen.http():get(BAD_URL, function() end) end, "allowlist")

  -- THE REQUEST OBJECT: configured after the call that created it, because it goes out on the next tick.
  local cancelCalled = false
  local cancelled = hafen.http():get(OK_URL, function() cancelCalled = true end)
  check(cancelled:timeout(5000):timeout() == 5000, "a chained setter applies and reads back",
        tostring(cancelled:timeout()))
  cancelled:cancel()

  -- AND THE ROUND TRIP END TO END. A pool thread files the completion under this addon's session and that
  -- session's tick delivers it, so a callback that fires at all is the whole conversion read back.
  local res, fired = nil, false
  pending = hafen.http():get(OK_URL, function(r) res, fired = r, true end)
  pending:timeout(5000)

  local waited = 0
  local function score()
    if (not fired) and (waited < WINDOW) then
      waited = waited + STEP
      hafen.timer():after(STEP, score)
      return
    end
    pending = nil
    if fired then
      check(res ~= nil, "the callback ran on this session's tick, carrying its res table", tostring(res))
      check(res.ok and (type(res.status) == "number"),
            "...and it reports what the server answered",
            res.ok and ("status " .. tostring(res.status)) or ("transport: " .. tostring(res.error)))
    else
      check(false, "the request to " .. OK_URL .. " came back inside " .. WINDOW .. "s",
            "nothing arrived (no network? the callback never ran)")
    end
    check(not cancelCalled, "a cancelled request never calls back", tostring(cancelCalled))
    check(cleanup(), "the run leaves its own saved variables empty", "cleanup raised")
    manualCheck("with voice on and another character beside you, have them speak",
                "the speaker icon appears over THAT character -- the view Voice reads is the one on screen")
    summary()
  end
  hafen.timer():after(STEP, score)
end

-- One run, and it always ends in a verdict: a raise anywhere above would otherwise leave the maintainer
-- with half a log, a request in flight and a value in the store, so the failure is scored and put back here.
local function run()
  pass, fail, manual = 0, 0, 0
  local ok, err = pcall(body)
  if not ok then
    cleanup()
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    summary()
  end
end

hafen.slash():register("t073-5", run)   -- the only way in: a suite does not start itself
