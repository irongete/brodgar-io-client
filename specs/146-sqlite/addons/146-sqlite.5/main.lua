-- 146.5 — the pages: the example blocks of docs/addons/api/store/, pasted as written. Self-checking
-- suite. A page whose example does not run is the defect this suite exists to catch, so every block
-- below is its page's own fence, verbatim, wrapped in a function and nothing else; what each wrote is
-- then read back through a verb the block did not use.

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

-- A bridge refusal reads "@chunk.lua:189 msg", with a space, so the strip takes both spellings.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Run one page's blocks in order. `fn` is handed `done`, called between two blocks, so the block that
-- failed is the one named: every block before it is a [pass], and it is the one [fail].
local function page(name, blocks, fn)
  local reached = 0
  local ok, err = pcall(fn, function() reached = reached + 1 end)
  for i = 1, math.min(blocks, reached) do
    check(true, name .. ": example " .. i .. " runs as written")
  end
  if not ok then
    check(false, name .. ": example " .. (reached + 1) .. " runs as written", why(err))
  end
end

-- ==== README.md ==================================================================================

local function readme(done)
local seen = hafen.store():get("seen")                -- a document: a live table, saved for you
seen.launches = (seen.launches or 0) + 1

local trees = hafen.store():table("trees")            -- a table you declare: typed rows in and out
  :column("id", "integer"):column("kind", "text"):key("id"):create()
trees:put{ id = 1, kind = "fir" }

for _, r in ipairs(hafen.store():query("SELECT kind, count(*) AS n FROM trees GROUP BY kind")) do
  hafen.log():write(r.kind .. ": " .. r.n)            -- a statement: SQL, for what only SQL says
end
done()
end

-- ==== documents.md ===============================================================================

local function documents(done)
local settings = hafen.session():current():store():get("settings")   -- the live table, not a copy
settings.enabled = true
settings.count = (settings.count or 0) + 1

local seen = hafen.store():get("seen")                                -- your addon's own
seen.lastLogin = os.time()
done()
end

-- ==== tables.md ==================================================================================

local function tables(done)
local nodes = hafen.store():table("nodes")
  :column("grid", "text"):column("x", "integer"):column("y", "integer")
  :column("kind", "text"):column("seen", "boolean"):column("flags", "json")
  :key("grid", "x", "y")
  :index("kind")
  :create()

nodes:put{ grid = "g1", x = 1, y = 2, kind = "fir", seen = true, flags = { a = 1 } }
local row = nodes:get("g1", 1, 2)                 -- row.seen == true, row.flags.a == 1
for _, r in ipairs(nodes:list("WHERE kind = ? ORDER BY x", "fir")) do
  -- your code here
end
done()
-- the same declaration with one more column: the file gains it; an older row reads it as absent
local nodes = hafen.store():table("nodes"):column("grid", "text"):column("x", "integer")
  :column("y", "integer"):column("kind", "text"):column("seen", "boolean"):column("flags", "json")
  :column("quality", "integer"):key("grid", "x", "y"):create()
nodes:put{ grid = "g1", x = 1, y = 3, kind = "fir", quality = 31 }
done()
local near = nodes:list("WHERE grid = ? AND abs(x - ?) <= 2 ORDER BY y", "g1", 1)
local firs = nodes:count("WHERE kind = ?", "fir")
done()
return row, near, firs
end

-- ==== statements.md ==============================================================================

local function statements(done)
local store = hafen.store()
local prices = store:table("prices"):column("item", "text"):column("price", "integer")
  :key("item"):create()
prices:put{ item = "flax", price = 12 }

store:exec("UPDATE prices SET price = price + ? WHERE item = ?", 3, "flax")
for _, r in ipairs(store:query("SELECT item, price FROM prices WHERE price > ?", 10)) do
  hafen.log():write(r.item .. " costs " .. r.price)    -- flax costs 15
end
done()
local n = store:transaction(function(rows)
  for _, r in ipairs(rows) do prices:put(r) end            -- one commit for all of them, or none
  return #rows
end, { { item = "hemp", price = 9 }, { item = "wool", price = 20 } })
done()
return n
end

-- ==== the run ====================================================================================

local function run()
  local store = hafen.store()
  -- this suite's own rows, cleared, so a rerun is clean; the documents are kept, since one of them is
  -- the proof that a run before this one happened
  for _, t in ipairs({ "trees", "nodes", "prices" }) do
    store:exec("DROP TABLE IF EXISTS " .. t)
  end
  local before = store:get("seen").launches

  page("README.md", 1, readme)
  local fir = store:query("SELECT kind FROM trees WHERE id = ?", 1)[1]
  check(fir and fir.kind == "fir", "README.md: the row the example put reads back through :query", fir and fir.kind)

  page("documents.md", 1, documents)
  if before then
    check(true, "documents.md: an earlier run is counted (launches was " .. tostring(before) .. ")")
  else
    manual = manual + 1
    hafen.log():write("[manual] :reload and run :t146 again -- expect: [pass] documents.md: an earlier run"
                      .. " is counted")
  end

  local row, near, firs
  page("tables.md", 3, function(done) row, near, firs = tables(done) end)
  local raw = store:query("SELECT seen, flags, quality FROM nodes WHERE x = ? AND y = ?", 1, 2)[1]
  check(row and (row.seen == true) and row.flags and (row.flags.a == 1) and raw and (raw.seen == 1)
        and (type(raw.flags) == "string") and (raw.quality == nil) and near and (#near == 2) and (firs == 2),
        "tables.md: the row is typed through the Table (true, {a=1}) and raw through :query (1, text); the"
        .. " clause keeps 2 of 2 firs",
        row and ("seen=" .. tostring(row.seen) .. " flags.a=" .. tostring(row.flags and row.flags.a)
                 .. " raw.seen=" .. tostring(raw and raw.seen) .. " raw.flags=" .. tostring(raw and raw.flags)
                 .. " near=" .. tostring(near and #near) .. " firs=" .. tostring(firs)))

  local n
  page("statements.md", 2, function(done) n = statements(done) end)
  local flax = store:query("SELECT price FROM prices WHERE item = ?", "flax")[1]
  local all = store:query("SELECT count(*) AS n FROM prices")[1]
  check((n == 2) and flax and (flax.price == 15) and all and (all.n == 3),
        "statements.md: flax costs 15 after the UPDATE, and the transaction committed its 2 rows (3 in all)",
        "n=" .. tostring(n) .. " flax=" .. tostring(flax and flax.price) .. " all=" .. tostring(all and all.n))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
