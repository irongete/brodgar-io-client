-- 123.2 — every symbol a comment names is one that exists. Self-checking suite.
--
-- The task rewrites nine citations across six Java files and two rows of docs/client/multi-session.md,
-- and changes not one line of behaviour. Eight of the nine are verified by reading them in the diff: a
-- comment is read, never run. The ninth is not, and that is what this suite is for.
--
-- Sessions.anchor's comment named a SessionDestroyed event key. The bus has never fired one -- its
-- closed set is SessionAdded, SessionEnteredWorld, SessionSelected and SessionRemoved -- so the
-- corrected comment names the last of those instead. What makes that correction TRUE rather than merely
-- different is reachable from Lua, at the door an addon actually uses: SessionDestroyed must be
-- REFUSED, the refusal must NAME the four keys that do exist, and each of those four must subscribe.
-- The lower-case spelling is asked for the same reason -- the client's own keys are PascalCase and the
-- set is closed, so `sessionremoved` is a typo with no future meaning to wait for, and not a second
-- way to spell one that works.
--
-- This suite subscribes and unsubscribes only. It writes nothing, and leaves no subscription behind.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The whole session family, and the closed set the corrected comment rests on.
local FOUR = {"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionRemoved"}

-- A refusal is a check: the call must fail, and fail SAYING why -- here, by naming every one of the
-- four. A near miss inside this family is the expensive kind: three of the four differ by one word, and
-- a subscription that silently never fires is the most costly way there is to learn a name.
local function refusesNamingFour(what, key)
  local ok, res = pcall(function() return hafen.event():on(key, function() end) end)
  if ok and (res ~= nil) then
    pcall(function() res:off() end)   -- accepted after all: leave nothing behind
  end
  local err = ok and "<no error>" or (tostring(res):gsub("^@?.-%.lua:%d+:?%s*", ""))
  local missing = {}
  if not ok then
    for _, k in ipairs(FOUR) do
      if err:find(k, 1, true) == nil then missing[#missing + 1] = k end
    end
  end
  local got = err
  if (not ok) and (#missing > 0) then
    got = "names neither " .. table.concat(missing, " nor ") .. " -- " .. err
  end
  check((not ok) and (#missing == 0), what, got)
end

local function run()
  pass, fail, manual = 0, 0, 0
  local bus = hafen.event()

  refusesNamingFour("SessionDestroyed is refused, and the refusal names the four keys that exist",
                    "SessionDestroyed")
  refusesNamingFour("and the lower-case sessionremoved is refused the same way -- the keys are"
                    .. " PascalCase and the set is closed", "sessionremoved")

  -- The other half of the claim: every key that refusal names is one the bus really answers.
  local bad = nil
  for _, k in ipairs(FOUR) do
    local ok, s = pcall(function() return bus:on(k, function() end) end)
    if ok and (s ~= nil) then s:off() else bad = k .. " -- " .. tostring(s) end
  end
  check(bad == nil, "and each of the four it names subscribes, so it points at keys that are there", bad)

  -- SessionRemoved, the key the corrected comment names, through the whole of its life.
  local sub = bus:on("SessionRemoved", function() end)
  check((sub ~= nil) and (sub:key() == "SessionRemoved"),
        "SessionRemoved hands back a Sub, and sub:key() answers the key it was registered under",
        sub and sub:key())

  local found = nil
  for _, s in ipairs(bus:list("SessionRemoved")) do
    if s == sub then found = s end
  end
  check((found == sub) and (bus:count("SessionRemoved") == 1),
        "and it is the very Sub list() carries, counted once",
        "found " .. tostring(found == sub) .. ", count " .. bus:count("SessionRemoved"))

  local back = sub:off()
  check(back == sub, "sub:off() ends it and hands the subscription back", tostring(back))
  check((bus:count("SessionRemoved") == 0) and (#bus:list("SessionRemoved") == 0),
        "and the subscription is gone from count() and from list()",
        bus:count("SessionRemoved") .. " counted, " .. #bus:list("SessionRemoved") .. " listed")

  local again = pcall(function() sub:off() end)
  check(again and (bus:count("SessionRemoved") == 0), "and a second off() is harmless", again)

  manualCheck("type `:session anchor main` at the console",
              "it refuses by that name and tells you to name an ACCOUNT instead, rather than answering"
              .. " \"no such session: main\"")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t123", run)   -- the only way in: a suite does not start itself
