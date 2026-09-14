-- 147.2 — the pages. Self-checking suite: documents.md's and the store hub's example blocks,
-- pasted as written, and one verdict per line each prints.

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

local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function clearDoc(t)
  for k in pairs(t) do t[k] = nil end
end

-- What the two blocks write, cleared first so a rerun scores the same.
local function clean()
  clearDoc(hafen.session():current():store():get("settings"))
  clearDoc(hafen.store():get("seen"))
  pcall(function() hafen.store():exec("DELETE FROM trees") end)   -- absent on the first run
end

-- api/store/documents.md, the opening block, as written
local function documentsPage()
  local settings = hafen.session():current():store():get("settings")   -- the live table, not a copy
  settings.enabled = true
  settings.count = (settings.count or 0) + 1

  local seen = hafen.store():get("seen")                                -- your addon's own
  seen.lastLogin = os.time()
  return settings, seen
end

-- api/store/README.md, the opening block, as written
local function hubPage()
  local seen = hafen.store():get("seen")                -- a document: a live table, saved for you
  seen.launches = (seen.launches or 0) + 1

  local trees = hafen.store():table("trees")            -- a table you declare: typed rows in and out
    :column("id", "integer"):column("kind", "text"):key("id"):create()
  trees:put{ id = 1, kind = "fir" }

  for _, r in ipairs(hafen.store():query("SELECT kind, count(*) AS n FROM trees GROUP BY kind")) do
    hafen.log():write(r.kind .. ": " .. r.n)            -- a statement: SQL, for what only SQL says
  end
  return seen, trees
end

local function run()
  pass, fail, manual = 0, 0, 0
  clean()

  local ok, settings, seen = pcall(documentsPage)
  check(ok, "documents.md: the block runs as written", ok or why(settings))
  if ok then
    eq("documents.md: get(\"settings\") is the live table, the same object again",
       hafen.session():current():store():get("settings") == settings, true)
    eq("documents.md: settings.enabled = true lands", settings.enabled, true)
    eq("documents.md: settings.count counts from an empty document", settings.count, 1)
    eq("documents.md: get(\"seen\") through hafen.store() is another document", seen == settings, false)
    eq("documents.md: seen.lastLogin = os.time() is a number", type(seen.lastLogin), "number")
  end

  local ok2, seen2, trees = pcall(hubPage)
  check(ok2, "the hub: the block runs as written", ok2 or why(seen2))
  if ok2 then
    eq("the hub: seen.launches counts from the cleared document", seen2.launches, 1)
    eq("the hub: the same seen as documents.md's", seen2 == seen, true)
    eq("the hub: trees:put{ id = 1, kind = \"fir\" } is the row", trees:get(1) and trees:get(1).kind, "fir")
    local rows = hafen.store():query("SELECT kind, count(*) AS n FROM trees GROUP BY kind")
    eq("the hub: the statement answers one group", #rows, 1)
    eq("the hub: the group reads fir: 1", rows[1] and (rows[1].kind .. ": " .. rows[1].n), "fir: 1")
  end

  eq("both pages: :list() on the addon's own door names seen",
     table.concat(hafen.store():list(), ","):find("seen", 1, true) ~= nil, true)

  clean()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t147", run)   -- the only way in: a suite does not start itself
