-- 147.1 — A document exists when get(name) first names it. Self-checking suite.
-- This manifest declares no document: that is the point.

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
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function has(list, name)
  for _, v in ipairs(list) do
    if v == name then return true end
  end
  return false
end

local function show(list)
  return "{" .. table.concat(list, ",") .. "}"
end

local function run()
  local store = hafen.store()

  -- the client door: a table, the same one twice, and it exists once named
  local seen = store:get("seen")
  check(type(seen) == "table", "hafen.store():get(\"seen\") answers a table with nothing declared", type(seen))
  check(store:get("seen") == seen, "a second :get answers the same table")
  local names = store:list()
  check(has(names, "seen"), "hafen.store():list() names seen", show(names))

  -- an earlier run's count survived the reload: the write landed and the read came back
  local earlier = seen.runs
  if earlier then
    check(type(earlier) == "number" and earlier >= 1, "an earlier run is counted (" .. tostring(earlier) .. ")", earlier)
  else
    manualCheck(":reload and run :t147 again", "[pass] an earlier run is counted")
  end
  for k in pairs(seen) do seen[k] = nil end          -- a rerun is clean, but for the counter
  seen.n = 1
  seen.runs = (earlier or 0) + 1
  store:flush()
  check(store:get("seen").n == 1, "seen.n reads back 1 after the flush", store:get("seen").n)

  -- the character door: the same name is another document, and each list is its own scope's
  store:get("client-only")                            -- a client-scope name the character's list must not show
  local s = hafen.session():current()
  local ok, err = pcall(function()
    local cseen = s:store():get("seen")
    check(type(cseen) == "table" and cseen.n == nil,
          "s:store():get(\"seen\").n is nil: one name through two doors is two documents", cseen and cseen.n)
    check(s:store():get("seen") == cseen, "a second s:store():get answers the same table")
    local cnames = s:store():list()
    check(has(cnames, "seen") and not has(cnames, "client-only"),
          "s:store():list() names the character's seen and none of the client's", show(cnames))
  end)
  if not ok then check(false, "the character door answers (is the session in the world?)", err) end
  local cl = store:list()
  check(has(cl, "client-only") and has(cl, "seen"), "hafen.store():list() names what exists in its scope, sorted",
        show(cl))
  local sorted = true
  for i = 2, #cl do if cl[i - 1] > cl[i] then sorted = false end end
  check(sorted, "hafen.store():list() is sorted", show(cl))

  -- the name: a non-empty string, refused otherwise, naming the parameter
  refuses("get(nil) is refused naming name", function() store:get(nil) end, "name")
  refuses("get(42) is refused naming name", function() store:get(42) end, "name must be a string")
  refuses("get(\"\") is refused naming name", function() store:get("") end, "name must not be empty")
  refuses("a session that is not live still refuses at the door",
          function() return hafen.session():get("nobody"):store():get("seen") end, "not a live session")

  manualCheck("add \"saved_variables\": [\"x\"] to this suite's manifest and :reload",
              "its AddOns panel row reads a manifest error naming hafen.store():get(name); then remove the line")
  manualCheck(":reload with the sibling addons rebuilt (ant bin)",
              "no manifest error row in the AddOns panel, and :gobcache stats reports your cache")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  pass, fail, manual = 0, 0, 0
end

hafen.console():on("t147", run)   -- the only way in: a suite does not start itself
