-- 146.2 — declared tables. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why (every one of `...`).
local function refuses(what, fn, ...)
  local words = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  local said = not ok
  for _, w in ipairs(words) do
    said = said and (err:find(w, 1, true) ~= nil)
  end
  check(said, what, err)
end

-- The declaration under test, as the task line spells it.
local function declare()
  return hafen.store():table("nodes")
    :column("grid", "text"):column("x", "integer"):column("y", "integer")
    :column("kind", "text"):column("seen", "boolean"):column("flags", "json")
    :key("grid", "x", "y"):index("kind"):create()
end

local function run()
  local nodes = declare()
  for _, r in ipairs(nodes:list()) do nodes:remove(r.grid, r.x, r.y) end   -- a rerun is clean

  local g = "g1"
  local row = nodes:put{ grid = g, x = 1, y = 2, kind = "fir", seen = true, flags = { a = 1 } }
  check(type(row) == "table" and row.seen == true and type(row.flags) == "table" and row.flags.a == 1,
        "put answers the row typed by the declaration (seen == true, flags.a == 1)",
        type(row) == "table" and (tostring(row.seen) .. ", " .. tostring(row.flags and row.flags.a))
          or type(row))
  local back = nodes:get(g, 1, 2)
  check(back and back.kind == "fir" and back.x == 1 and back.y == 2 and back.seen == true
          and back.flags.a == 1,
        "get(g, 1, 2) reads the row back", back and back.kind or back)
  refuses("get(g, 1) is refused naming the three key columns",
          function() nodes:get(g, 1) end, "grid", "x", "y")
  refuses("put{ kidn = \"fir\" } is refused naming kind",
          function() nodes:put{ kidn = "fir" } end, "kind")
  refuses("put{ x = \"1\" } is refused naming integer",
          function() nodes:put{ grid = g, x = "1", y = 2 } end, "integer")

  nodes:put{ grid = g, x = 3, y = 2, kind = "fir" }
  nodes:put{ grid = g, x = 2, y = 2, kind = "oak" }
  local firs = nodes:list("WHERE kind = ? ORDER BY x", "fir")
  local n = nodes:count("WHERE kind = ?", "fir")
  check(#firs == 2 and n == 2 and firs[1].x == 1 and firs[2].x == 3,
        "list and count agree over a clause with a ? (2 firs, ordered by x)",
        #firs .. " listed, " .. tostring(n) .. " counted")
  local one = nodes:find("WHERE kind = ?", "oak")
  check(one and one.x == 2 and one.seen == nil,
        "find answers one row, and NULL is an absent key", one and one.x or one)
  nodes:remove(g, 2, 2)
  check(nodes:get(g, 2, 2) == nil, "remove then get is nil", nodes:get(g, 2, 2))
  refuses("list(function() end) is refused naming SQL",
          function() nodes:list(function() end) end, "SQL")

  check(declare() == nodes, "a second :table(\"nodes\")...:create() is == the first")
  local wider = hafen.store():table("nodes")
    :column("grid", "text"):column("x", "integer"):column("y", "integer")
    :column("kind", "text"):column("seen", "boolean"):column("flags", "json"):column("note", "text")
    :key("grid", "x", "y"):create()
  local noted = wider:put{ grid = g, x = 1, y = 2, kind = "fir", note = "tall" }
  check(wider == nodes and noted.note == "tall" and nodes:get(g, 1, 2).note == "tall",
        "a declaration with one more column round-trips it through put", noted and noted.note)
  refuses("a declaration with another key is refused naming both",
          function()
            hafen.store():table("nodes"):column("grid", "text"):column("x", "integer")
              :key("grid", "x"):create()
          end, "(grid, x, y)", "(grid, x)")
  refuses(":table(\"hafen_x\") is refused naming what is allowed",
          function() hafen.store():table("hafen_x") end, "hafen_", "letters")
  refuses(":column(\"a\", \"blob\") is refused naming what is allowed",
          function() hafen.store():table("things"):column("a", "blob") end,
          "text, integer, real, boolean or json")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
