-- 082.3 -- on the bus, "*" is refused and says where it means everything. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING every one of `wants`.
local function refuses(what, fn, wants)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local said = not ok
  for _, want in ipairs(wants) do
    if err:find(want, 1, true) == nil then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function nothing() end

local function run()
  -- 1. The bus refuses "*", and the refusal POINTS: both streams, spelled out as you would write them.
  refuses("the bus refuses \"*\" and names both streams",
          function() hafen.event():on("*", nothing) end,
          {"'*'", "hafen.event():action():on(\"*\", fn)", "hafen.event():message():on(\"*\", fn)"})

  -- 2. ...and the pointer is not a lie: "*" is a real subscription on each stream.
  local a = hafen.event():action():on("*", nothing)
  check(a ~= nil and pcall(function() a:off() end), "the outbound stream takes \"*\"", a)
  local m = hafen.event():message():on("*", nothing)
  check(m ~= nil and pcall(function() m:off() end), "the inbound stream takes \"*\"", m)

  -- 3. The new branch did not displace the old hint: a near miss in the session family still gets all four.
  refuses("a session near miss still gets all four spelled out",
          function() hafen.event():on("SessionEnteredWord", nothing) end,
          {"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionDestroyed"})

  -- 4. ...and a real bus key still subscribes.
  local g = hafen.event():on("GobAdded", nothing)
  check(g ~= nil and pcall(function() g:off() end), "a real bus key still subscribes", g)

  -- 5. The reservation is the streams' alone: on a widget "*" is an unknown key like any other.
  local s = hafen.session():current()
  local w = s and s:ui():find("@GameUI")
  if w == nil then
    check(false, "widget:on(\"*\") is refused as an unknown widget key", "no GameUI -- run this in the world")
  else
    refuses("widget:on(\"*\") is refused as an unknown widget key",
            function() w:on("*", nothing) end, {"has no event '*'"})
  end

  manualCheck("read the wildcard paragraph in docs/addons/api/event/bus.md, the new section in"
              .. " docs/addons/guides/debugging.md, and the threading paragraph in"
              .. " docs/addons/api/conventions.md",
              "the swallow warning and the cost callout read as things you would heed BEFORE writing a"
              .. " wildcard, not after")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t082-3", run)
