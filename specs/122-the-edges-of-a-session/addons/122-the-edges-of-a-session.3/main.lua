-- 122.3 — an account name is reserved before the connection. Self-checking suite.
--
-- What the task changed is a reservation: the account name a login is being built for is taken under a
-- monitor of its own, atomically with the check that refuses a name already live, and BEFORE the two
-- blocking network round-trips that connect it. The check it replaces read the membership on the near
-- side of those round-trips, so two adds of one name could both pass it seconds apart and the client
-- could end up holding two members of one account.
--
-- Nothing in the API opens a login: there is no hafen.session():add, and the two doors are the console's
-- own `:session add` and the client's login screen. So the automated half asserts the INVARIANT the
-- reservation defends, which is the only thing an addon can see of it and the only thing that can be
-- wrong afterwards -- one member per account, from the collection's three independent readings of its
-- own membership. Two members of one name cannot hide from all three: the list would carry the name
-- twice, :count() would exceed the number of distinct names, and :get(user) has one object per name to
-- hand back and could not be both of them.
--
-- The half a program cannot cause: a refused login. `:session add` runs on a thread of its own and says
-- what happened into the notice line, so its refusal is neither returned nor readable from here. That is
-- the [manual] block, and the second pair is the regression the `finally` defends -- a login that fails
-- for its own reason must leave its account name free, or the retry the player types next is refused
-- for a session that was never built.
--
-- This suite reads only. It logs nothing in, logs nothing out and writes nothing.

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

-- The accounts the client holds, in the order it joined them, as one readable string for the verdict
-- line -- and the roster the `:session list` step below is compared against.
local function roster(list)
  local names = {}
  for i, s in ipairs(list) do names[i] = s:user() end
  return (#names == 0) and "(none)" or table.concat(names, ", ")
end

local function run()
  pass, fail, manual = 0, 0, 0

  local live = hafen.session()
  local list = live:list()

  if #list == 0 then
    check(false, "run it with at least one character logged in: the client holds a session", "(none)")
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  -- One member per account, read off the list itself: a name that appears twice is exactly the state the
  -- reservation exists to make unreachable.
  local seen, dupe, distinct = {}, nil, 0
  for _, s in ipairs(list) do
    local u = s:user()
    if seen[u] then dupe = u else seen[u] = true; distinct = distinct + 1 end
  end
  check(dupe == nil, "every account the client holds is named once (" .. roster(list) .. ")",
        dupe and ("the account '" .. tostring(dupe) .. "' is in the list twice"))

  -- The collection counting its own membership has to reach the same number the list has entries, and
  -- both have to reach the number of DISTINCT names, or one of the two is reading a duplicate.
  local n = live:count()
  check((n == #list) and (n == distinct),
        "the collection's count is the list's length and the number of accounts in it (" .. distinct .. ")",
        "count() = " .. tostring(n) .. ", #list = " .. #list .. ", accounts = " .. distinct)

  -- :get addresses by the account name, so it can only ever hand back ONE object for a name. It has to be
  -- the very entry the list carries -- and that entry has to be a live member, or the identity above is
  -- an object minted for a name nobody holds.
  local badget, badexists = nil, nil
  for _, s in ipairs(list) do
    local u = s:user()
    if live:get(u) ~= s then badget = u end
    if not s:exists() then badexists = u end
  end
  check(badget == nil, "get(user) hands back the very entry of the list that carries that name",
        badget and ("get('" .. tostring(badget) .. "') is a different object"))
  check(badexists == nil, "and every one of those entries is a session the client still holds",
        badexists and ("'" .. tostring(badexists) .. "' does not exist"))

  local cur = live:current()
  local held = cur and cur:user() or list[1]:user()

  manualCheck("run ':session add " .. held .. "', an account this client already holds",
              "one line, '" .. held .. " failed: already a live session: " .. held .. "'")
  manualCheck("then run ':session list'",
              "the same accounts this run named, in the same order, and nothing added")
  manualCheck("run ':session add <an account with no saved token>'",
              "one line, '<account> failed: no saved token for: <account> ...'")
  manualCheck("run that same line a second time",
              "the same 'no saved token' refusal, never 'already a live session'")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t122", run)   -- the only way in: a suite does not start itself
