-- 146.4 — transactions, the bounds and vacuum: :transaction(fn, ...), the statement timeout, the row
-- cap over :query and :list, and :vacuum(). Self-checking suite.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A bridge refusal reads "@chunk.lua:189 msg", with a space, so the strip takes both spellings.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what .. " (" .. wantMsg .. ")", err)
end

-- An endless recursive CTE: count(*) over it never answers a row, so it runs into the timeout; x over
-- it with a LIMIT answers exactly that many rows.
local ENDLESS = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c)"

local function run()
  local store = hafen.store()
  local t = store:table("tx"):column("n", "integer"):column("s", "text"):key("n"):create()
  store:exec("DELETE FROM tx")                -- this suite's own rows, cleared

  local put = store:transaction(function(n)
    for i = 1, n do t:put{ n = i, s = "row" } end
    return n
  end, 10000)
  check((put == 10000) and (t:count() == 10000), "10000 puts inside one transaction: count() is 10000",
        tostring(put) .. " put, count " .. tostring(t:count()))
  local ok, err = pcall(store.transaction, store, function()
    t:put{ n = 10001, s = "x" }
    error("stop")
  end)
  check((not ok) and (why(err):find("stop", 1, true) ~= nil) and (t:count() == 10000),
        "a fn that raises rolls back: the count is unchanged and the error is out of the call",
        tostring(ok) .. ", " .. tostring(err) .. ", count " .. tostring(t:count()))
  refuses("a nested call fails naming :transaction", function()
    store:transaction(function() store:transaction(function() end) end)
  end, ":transaction")
  local a, b = store:transaction(function() return 1, "a" end)
  check((a == 1) and (b == "a"), "transaction(function() return 1, \"a\" end) answers 1, \"a\"",
        tostring(a) .. ", " .. tostring(b))
  refuses("transaction(42) fails naming fn", function() store:transaction(42) end, "fn")
  eq("vacuum() answers the store", store:vacuum(), store)
  refuses("vacuum() inside a transaction fails naming it", function()
    store:transaction(function() store:vacuum() end)
  end, ":transaction")
  local t0 = os.clock()
  refuses("a recursive CTE with no end fails naming the timeout", function()
    store:query(ENDLESS .. " SELECT count(*) AS n FROM c")
  end, "timeout")
  local took = os.clock() - t0
  check((took >= 4) and (took < 10), "...in under twice the 5 s timeout", took .. " s")
  refuses("the same CTE with LIMIT 50001 through query fails naming LIMIT", function()
    store:query(ENDLESS .. " SELECT x FROM c LIMIT 50001")
  end, "LIMIT")
  store:exec("INSERT OR REPLACE INTO tx (n, s) SELECT x, 'bulk' FROM (" .. ENDLESS .. " SELECT x FROM c LIMIT 50001)")
  refuses("list() over 50001 rows fails naming LIMIT", function() t:list() end, "LIMIT")
  eq("list(\"LIMIT 10\") answers 10", #t:list("LIMIT 10"), 10)
  store:exec("DELETE FROM tx")                -- the 50001 rows are not left in the file

  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
