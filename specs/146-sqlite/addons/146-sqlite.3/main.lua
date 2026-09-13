-- 146.3 — statements: :exec, :query, the binding and the arity, the verb decided by what the
-- statement answers, and the scan's refusals. Self-checking suite.

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

local function run()
  local store = hafen.store()
  store:table("st"):column("k", "text"):column("n", "integer"):column("b", "boolean"):key("k"):create()
  store:exec("DELETE FROM st;")               -- this suite's own rows, cleared; a trailing ; is one statement

  eq("exec(INSERT) answers 1, a ; inside a literal being no second statement",
     store:exec("INSERT INTO st (k, n, b) VALUES ('a;b', ?, ?)", 1, true), 1)
  local rows = store:query("SELECT * FROM st WHERE k = ?", "a;b")
  check((#rows == 1) and (rows[1].k == "a;b") and (rows[1].n == 1) and (rows[1].b == 1),
        "query answers rows keyed by column, the boolean raw as 1",
        #rows .. " rows, b = " .. tostring(rows[1] and rows[1].b))
  refuses("three values for two ? fail naming both counts",
          function() store:exec("INSERT INTO st (k, n) VALUES (?, ?)", "c", 2, 3) end, "2 ?s and 3 values")
  store:exec("INSERT INTO st (k, n) VALUES (?, ?)", "d", nil)
  local d = store:query("SELECT k, n FROM st WHERE k = ? AND n IS NULL", "d")
  check((#d == 1) and (d[1].k == "d") and (d[1].n == nil), "nil binds NULL and reads as an absent key",
        #d .. " rows, n = " .. tostring(d[1] and d[1].n))
  refuses("exec(SELECT 1) fails naming :query", function() store:exec("SELECT 1") end, ":query")
  local before = store:query("SELECT count(*) AS c FROM st")[1].c
  refuses("query(DELETE) fails naming :exec", function() store:query("DELETE FROM st") end, ":exec")
  eq("and the refused DELETE ran nothing: the count is unchanged",
     store:query("SELECT count(*) AS c FROM st")[1].c, before)
  refuses("two INSERTs in one string fail naming one per call",
          function() store:exec("INSERT INTO st (k) VALUES ('x'); INSERT INTO st (k) VALUES ('y')") end,
          "one statement")
  refuses("exec(CREATE TABLE) fails naming :table", function() store:exec("CREATE TABLE t (a)") end, ":table")
  refuses("exec(CREATE INDEX) fails naming :index",
          function() store:exec("CREATE INDEX st_n ON st (n)") end, ":index")
  refuses("query over hafen_documents fails naming the client's tables",
          function() store:query("SELECT * FROM hafen_documents") end, "client's")
  refuses("ATTACH is refused naming the sandbox",
          function() store:exec("ATTACH DATABASE ':memory:' AS m") end, "sandbox")
  refuses("load_extension is refused naming the sandbox",
          function() store:query("SELECT load_extension('x')") end, "sandbox")
  refuses("VACUUM INTO is refused naming :vacuum()", function() store:exec("VACUUM INTO 'x'") end, ":vacuum()")
  refuses("BEGIN is refused naming :transaction(fn", function() store:exec("BEGIN") end, ":transaction(fn")
  local r = store:query("INSERT INTO st (k, n) VALUES (?, ?) RETURNING n", "e", 5)
  check((#r == 1) and (r[1].n == 5), "query(INSERT ... RETURNING n) answers the row",
        #r .. " rows, n = " .. tostring(r[1] and r[1].n))

  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
