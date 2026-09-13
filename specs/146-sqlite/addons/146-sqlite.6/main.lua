-- 146.6 — The Table's snapshot: nodes:info(). Self-checking suite.

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

-- {"a", "b"} -- an array of strings, readable in a verdict line.
local function show(t)
  if type(t) ~= "table" then return tostring(t) end
  local parts = {}
  for i = 1, #t do parts[i] = tostring(t[i]) end
  return "{" .. table.concat(parts, ", ") .. "}"
end

local function sameNames(got, want)
  if type(got) ~= "table" or #got ~= #want then return false end
  for i = 1, #want do
    if got[i] ~= want[i] then return false end
  end
  return true
end

local function run()
  -- the declaration as tables.md writes it
  local nodes = hafen.store():table("nodes")
    :column("grid", "text"):column("x", "integer"):column("y", "integer")
    :column("kind", "text"):column("seen", "boolean"):column("flags", "json")
    :key("grid", "x", "y")
    :index("kind")
    :create()

  local i = nodes:info()
  eq("info() is a plain table", type(i), "table")
  eq("info().name is the table's name", i.name, "nodes")
  eq("info().columns has one entry per declared column", #i.columns, 6)
  eq("columns[1] is the first declared, by name", i.columns[1] and i.columns[1].name, "grid")
  eq("columns[5].name is the fifth declared", i.columns[5] and i.columns[5].name, "seen")
  eq("columns[5].type is the word the declaration wrote", i.columns[5] and i.columns[5].type, "boolean")
  eq("columns[6].type is json, not the file's TEXT", i.columns[6] and i.columns[6].type, "json")
  check(sameNames(i.key, { "grid", "x", "y" }), "info().key is the key in :key order ({grid, x, y})",
        show(i.key))
  eq("info().indexes has one entry", #i.indexes, 1)
  check(sameNames(i.indexes[1], { "kind" }), "indexes[1] is the indexed columns ({kind})", show(i.indexes[1]))

  -- a copy: assigning into it reaches nothing the next info() answers
  i.name = "other"
  i.columns[5].type = "text"
  i.columns[7] = { name = "ghost", type = "text" }
  i.key[1] = "y"
  i.indexes[1] = nil
  local j = nodes:info()
  eq("info() is a copy: the name is unchanged after assigning into it", j.name, "nodes")
  eq("info() is a copy: a column's type is unchanged", j.columns[5].type, "boolean")
  eq("info() is a copy: an added column is not there", #j.columns, 6)
  check(sameNames(j.key, { "grid", "x", "y" }), "info() is a copy: the key is unchanged", show(j.key))
  eq("info() is a copy: the indexes are unchanged", #j.indexes, 1)
  check(i ~= j, "two info() calls answer two tables", "the same table")

  -- a second :create() with one more column (and no index) is read back in the same object's info()
  local again = hafen.store():table("nodes"):column("grid", "text"):column("x", "integer")
    :column("y", "integer"):column("kind", "text"):column("seen", "boolean"):column("flags", "json")
    :column("quality", "integer"):key("grid", "x", "y"):create()
  eq("a second :create() of the name answers the same Table", again == nodes, true)
  local k = nodes:info()
  eq("the same object's info() follows the re-declaration: one more column", #k.columns, 7)
  eq("columns[7] is the added column", k.columns[7] and k.columns[7].name, "quality")
  eq("columns[7].type is its declared word", k.columns[7] and k.columns[7].type, "integer")
  eq("the re-declaration named no index, and info() says so", #k.indexes, 0)

  refuses("info() takes no arguments", function() return nodes:info(1) end, "takes no arguments")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
