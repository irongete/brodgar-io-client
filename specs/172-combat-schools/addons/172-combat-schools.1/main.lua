-- 172.1 — the saved combat schools. Self-checking suite.
--
-- Run :t172 on a character in the world. It reads that character's save list (the Martial Arts & Combat
-- Schools tab), prints it, and checks the collection, each school and the summary through the API. The manifest
-- declares api_version "1.4": that this suite loads at all is the proof that 1.4 loads.

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

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(text, needle)
  return (text ~= nil) and (text:find(needle, 1, true) ~= nil)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A table's keys, sorted and joined, so a snapshot's shape compares as one string.
local function keysOf(snapshot)
  if type(snapshot) ~= "table" then return tostring(snapshot) end
  local names = {}
  for key in pairs(snapshot) do names[#names + 1] = tostring(key) end
  table.sort(names)
  return table.concat(names, ",")
end

local function run()
  pass, fail, manual = 0, 0, 0
  local session = hafen.session():current()
  if not session then
    hafen.log():write("[fail] no character in the world -- log in and run :t172 again")
    return
  end
  local fight = session:fight()
  local schools = fight:school()
  local list = schools:list()
  local count = schools:count()
  local current = schools:current()

  -- The save list as the tab paints it: position, name, and the loaded one.
  local shown = {}
  for _, school in ipairs(list) do
    local name = school:name()
    shown[#shown + 1] = school:index() .. " " .. (name and ('"' .. name .. '"') or "(unused)")
      .. ((school == current) and " [current]" or "")
  end
  hafen.log():write("[info] schools: " .. ((#shown > 0) and table.concat(shown, ", ") or "(none)"))

  -- 1. One object, counted, every school at its own position.
  check((fight:school() == schools) and (count == #list) and (count >= 1),
        "school() is one object, and :count() == #:list() >= 1", "count " .. tostring(count) .. ", #list " .. #list)
  if count < 1 then
    -- The tab builds at login; with no slot there is nothing the rest could read.
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  local misplaced
  for position, school in ipairs(list) do
    if (school:index() ~= position) or (school:wire() ~= position - 1) or (schools:get(position) ~= school) then
      misplaced = misplaced or position
    end
  end
  check(misplaced == nil,
        "each school's :index() is its position, :wire() that minus 1, and :get(n) == :list()[n]",
        "position " .. tostring(misplaced))
  check((schools:get(0) == nil) and (schools:get(count + 1) == nil), ":get(0) and :get(count + 1) are nil",
        tostring(schools:get(0)) .. " / " .. tostring(schools:get(count + 1)))
  local text = refusal(function() return schools:get("x") end)
  check(says(text, ":find(filter)"), ':get("x") raises, naming :find(filter)', text or "<no error>")

  -- 2. Empty exactly when nameless; a named school's snapshot and search.
  local inconsistent, inexact, named = nil, nil, 0
  for _, school in ipairs(list) do
    local empty, name, info = school:empty(), school:name(), school:info()
    if (empty ~= (name == nil)) or (empty ~= (info == nil)) then
      inconsistent = inconsistent or tostring(school)
    end
    if name then
      named = named + 1
      local hit = schools:find(name)
      if (keysOf(info) ~= "name") or (info.name ~= name) then
        inexact = inexact or (tostring(school) .. " info {" .. keysOf(info) .. "}")
      elseif (hit == nil) or not says(hit:name(), name) then
        inexact = inexact or (tostring(school) .. " :find found " .. tostring(hit))
      end
    end
  end
  check(inconsistent == nil, ":empty() is true exactly when :name() and :info() are nil", inconsistent)
  check(inexact == nil, "a named school's :info() is exactly { name }, and :find(name) finds it"
        .. ((named == 0) and " (no school is named)" or ""), inexact)

  -- 3. The loaded one, and what a school and the collection refuse.
  local member = false
  for _, school in ipairs(list) do
    if school == current then member = true end
  end
  check(member, ":current() is a member of :list()", current)
  text = refusal(function() return schools:current(1) end)
  check(says(text, "takes no argument"), ":current(1) raises, saying it takes no argument", text or "<no error>")
  text = refusal(function() return list[1]:name(1) end)
  check(says(text, "takes no arguments"), "school:name(1) raises 'takes no arguments'", text or "<no error>")

  -- 4. The summary keeps the budget only.
  local summary = fight:summary()
  if summary == nil then
    check(false, "session:fight():summary() is there", "nil")
  else
    local activeSave = refusal(function() return summary:activeSave() end)
    local saveCount = refusal(function() return summary:saveCount() end)
    check(says(activeSave, "has no verb") and says(saveCount, "has no verb"),
          "summary:activeSave() and summary:saveCount() raise 'has no verb'",
          (activeSave or "<no error>") .. " / " .. (saveCount or "<no error>"))
    local snapshot = summary:info()
    check(keysOf(snapshot) == "maxact,nact,used", "summary:info()'s keys are exactly maxact, used and nact",
          "{" .. keysOf(snapshot) .. "}")
    text = refusal(function() return summary:used(1) end)
    check(says(text, "takes no arguments"), "summary:used(1) raises 'takes no arguments'", text or "<no error>")
  end

  manualCheck("open Character sheet -> Martial Arts & Combat Schools and read the save list",
              "the [info] line's schools in that order, (unused) where it reads Unused save,"
              .. " the check mark on the one marked [current]")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t172", run)   -- the only way in: a suite does not start itself
