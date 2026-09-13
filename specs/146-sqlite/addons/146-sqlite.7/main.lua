-- 146.7 — The scan over the clause, and over the client's own cells. Self-checking suite.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function run()
  local store = hafen.store()

  -- the table the clauses run over, and one row so a clause that runs has something to keep
  local nodes = store:table("nodes")
    :column("grid", "text"):column("x", "integer"):column("y", "integer"):column("kind", "text")
    :key("grid", "x", "y"):create()
  store:exec("DELETE FROM nodes")
  nodes:put{ grid = "g1", x = 1, y = 2, kind = "fir" }

  -- the clause goes through the scan, the Table verb the receiver
  refuses("list(\"LIMIT 1; DROP TABLE nodes\") is refused naming one statement per call",
          function() return nodes:list("LIMIT 1; DROP TABLE nodes") end, "one statement per call")
  refuses("the refusal names the Table verb", function() return nodes:list("LIMIT 1; DROP TABLE nodes") end,
          "table:list(clause, ...)")
  eq("the table is still there after the refused clause", nodes:count(), 1)
  refuses("count(\"WHERE 1 IN (SELECT 1 FROM hafen_documents)\") is refused naming the client's tables",
          function() return nodes:count("WHERE 1 IN (SELECT 1 FROM hafen_documents)") end,
          "the client's own tables")
  refuses("find(\"WHERE load_extension('x')\") is refused naming the sandbox",
          function() return nodes:find("WHERE load_extension('x')") end, "sandbox")
  eq("a clause with nothing after its ; still answers", nodes:count("WHERE kind = 'fir';"), 1)

  -- a PRAGMA that writes one of the client's cells is refused; one that reads answers
  refuses("exec(\"PRAGMA user_version = 9\") is refused naming the client's cell",
          function() return store:exec("PRAGMA user_version = 9") end, "the client's own cell")
  refuses("exec(\"PRAGMA journal_mode = DELETE\") is refused naming the client's cell",
          function() return store:exec("PRAGMA journal_mode = DELETE") end, "the client's own cell")
  refuses("query(\"PRAGMA synchronous = OFF\") is refused too, the scan running before the verb",
          function() return store:query("PRAGMA synchronous = OFF") end, "the client's own cell")
  local uv = store:query("PRAGMA user_version")
  eq("query(\"PRAGMA user_version\") answers 1", uv[1] and uv[1].user_version, 1)
  local pc = store:query("PRAGMA page_count")
  eq("query(\"PRAGMA page_count\") answers a number", type(pc[1] and pc[1].page_count), "number")
  local jm = store:query("PRAGMA journal_mode")
  eq("query(\"PRAGMA journal_mode\") still reads the mode the open set", jm[1] and jm[1].journal_mode, "wal")

  -- the file reopens after a run whose PRAGMA writes were refused: a row an earlier run left proves it
  local runs = store:table("runs"):column("n", "integer"):key("n"):create()
  local earlier = runs:count()
  runs:put{ n = earlier + 1 }
  local info = store:info()
  check(type(info) == "table" and type(info.file) == "string" and info.file:find("146%-sqlite%.7%.sqlite$"),
        "hafen.store():info() answers, the file this addon's own", info and info.file)
  if earlier > 0 then
    check(true, "the file reopened after an earlier run's refused PRAGMA writes")
  else
    manual = manual + 1
    hafen.log():write("[manual] :reload and run :t146 again -- expect: [pass] the file reopened after an"
      .. " earlier run's refused PRAGMA writes")
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
