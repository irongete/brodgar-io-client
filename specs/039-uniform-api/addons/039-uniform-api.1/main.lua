-- 039.1 -- the section machinery, the collection type and the refusals.
-- Self-checking suite; see specs/testing/addon-suite.md. Run  :t039-1
--
-- It stands alone (D-085): everything it rests on is asserted here, including the premises other
-- suites also check. It declares no permissions and no network block, so the one http assertion it
-- makes is the one a read-only addon can make -- that the gate refuses, naming the manifest block.

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- The eight sections this task cuts over.
local SECTIONS = { "time", "slash", "json", "http", "hook", "timer", "log", "event" }

-- Every retired spelling, and the replacement its message must name.
local RETIRED = {
  { "hafen.time.clock",       function() return hafen.time.clock end,       "hafen.time():clock" },
  { "hafen.time.isNight",     function() return hafen.time.isNight end,     "hafen.time():isNight" },
  { "hafen.slash.register",   function() return hafen.slash.register end,   "hafen.slash():register" },
  { "hafen.json.parse",       function() return hafen.json.parse end,       "hafen.json():parse" },
  { "hafen.json.encode",      function() return hafen.json.encode end,      "hafen.json():encode" },
  { "hafen.http.get",         function() return hafen.http.get end,         "hafen.http():get" },
  { "hafen.http.post",        function() return hafen.http.post end,        "hafen.http():post" },
  { "hafen.hook.input",       function() return hafen.hook.input end,       "hafen.hook():input" },
  { "hafen.hook.action",      function() return hafen.hook.action end,      "hafen.hook():action" },
  { "hafen.hook.message",     function() return hafen.hook.message end,     "hafen.hook():message" },
  { "hafen.hook.grab",        function() return hafen.hook.grab end,        "hafen.hook():grab" },
  { "hafen.timer.after",      function() return hafen.timer.after end,      "hafen.timer():after" },
  { "hafen.timer.every",      function() return hafen.timer.every end,      "hafen.timer():every" },
  { "hafen.events",           function() return hafen.events end,           "hafen.event()" },
}

local function nothing() end   -- returns NO values: the one case narg() cannot separate from f()

local function run()
  -- 1. the section object is a per-addon singleton, handed back by identity
  local singles, tables = 0, 0
  for _, name in ipairs(SECTIONS) do
    if hafen[name]() == hafen[name]() then singles = singles + 1 end
    if type(hafen[name]) == "table" and type(hafen[name]()) == "userdata" then tables = tables + 1 end
  end
  check(singles == #SECTIONS, "every section object is the same object every call (" .. singles .. "/8)")
  check(tables == #SECTIONS, "every section is a callable table over a userdata object (" .. tables .. "/8)")
  check(select(1, pcall(hafen.time)), "pcall still works over a section table")

  -- 2. every retired spelling throws, and the message NAMES its replacement
  local named = 0
  for _, r in ipairs(RETIRED) do
    local ok, err = pcall(r[2])
    if (not ok) and (tostring(err):find(r[3], 1, true) ~= nil) then named = named + 1 end
  end
  check(named == #RETIRED,
        "every retired spelling throws naming its replacement (" .. named .. "/" .. #RETIRED .. ")")
  refuses("the busiest shortcut is cut: hafen.log(msg)",
          function() hafen.log("x") end, "hafen.log():write(msg)")
  check(hafen.nosuchsection == nil, "a name this API never had still reads plain nil")
  refuses("an unknown verb on a section object throws naming the section",
          function() return hafen.time():nosuchverb() end, "has no verb 'nosuchverb'")
  refuses("a dot call on a section object is refused",
          function() return hafen.time().clock() end, "COLON call")

  -- 3. the nil discipline, both ways, and the hole stated honestly
  refuses("an explicit nil is refused: hafen.time(nil)",
          function() return hafen.time(nil) end, "takes no arguments")
  local held = nil
  refuses("a variable holding nil is refused too: hafen.time(x)",
          function() return hafen.time(held) end, "takes no arguments")
  check(hafen.time(nothing()) == hafen.time(),
        "the documented hole: a call returning NOTHING reads as no argument at all")
  refuses("a verb refuses an explicit nil: hafen.log():write(nil)",
          function() hafen.log():write(nil) end, "must not be nil")

  -- 4. hafen.log():write reaches the console (these very lines) and chains
  check(hafen.log():write("[note] 039.1 suite running") == hafen.log(),
        "hafen.log():write printed the line above and handed the section back, so writes chain")

  -- 5. the collection type, on the addon's own timers
  local before = hafen.timer():count()
  local t = hafen.timer():every(3600, function() end)
  local listed, found = hafen.timer():list(), false
  for _, h in ipairs(listed) do if h == t then found = true end end
  check(found and (#listed == before + 1) and (hafen.timer():count() == before + 1),
        "a collection lists its members by identity, and :count() agrees with :list()")
  check(hafen.timer():find(function(h) return h == t end) == t, "a collection finds by predicate")
  refuses("a collection is not a sequence: #coll", function() return #hafen.timer() end, "count()")
  refuses("a collection is not a sequence: coll[1]", function() return hafen.timer()[1] end,
          "not an array")
  refuses("a string filter is refused where the members have no name",
          function() return hafen.timer():list("x") end, "no name")
  t:cancel()
  check(hafen.timer():count() == before, "the collection is a view: cancelling drops it from the list")

  -- 6. the sections still do their work through the new spelling
  local doc = hafen.json():parse('{"a":1,"b":[2,3]}')
  check((doc.a == 1) and (doc.b[2] == 3) and (hafen.json():encode({1, 2}) == "[1,2]"),
        "hafen.json() parses and encodes through the section object")
  local reached = 0
  for _, verb in ipairs({ "clock", "dayFraction", "isNight", "season", "moon", "yearFraction" }) do
    local ok, v = pcall(function() return hafen.time()[verb](hafen.time()) end)
    -- a reader answers nil until its data is up, which is a value, not a failure
    if ok and ((v == nil) or (type(v) == "number") or (type(v) == "boolean")) then reached = reached + 1 end
  end
  check(reached == 6, "every hafen.time() reader answers through the section object (" .. reached
        .. "/6, clock = " .. tostring(hafen.time():clock()) .. ")")
  local cmd = hafen.slash():register("t039-1-probe", function() end)
  check(cmd ~= nil, "hafen.slash():register answers a handle -- as this suite's own :t039-1 proves")
  cmd:remove()

  -- 7. http is gated by declaration, and this addon declares nothing
  refuses("hafen.http():get without a declared network block is refused at call",
          function() hafen.http():get("https://example.com/", function() end) end, "\"network\" block")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-1", run)   -- the only way in: a suite does not start itself
