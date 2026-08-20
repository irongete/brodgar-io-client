-- 084.4 -- one door for an argument. Self-checking suite.
--
-- Every check here is an ARGUMENT REFUSAL that has to fire before anything leaves the client, which is
-- also what makes it safe to declare kin.add, world.place and item.drop and then call all three with a
-- wrong argument: if a refusal did not fire, the send would.

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

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it
-- did not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil), what, msg or "<no error>")
end

-- The same, and the message must NOT carry the other sentence: a nil is a nil, never a wrong type.
local function refusesOnly(what, fn, wantMsg, forbidMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil)
        and (msg:find(forbidMsg, 1, true) == nil), what, msg or "<no error>")
end

local function run()
  local win = hafen.ui():window():title("084.4"):visible(false)
  local e = hafen.ui():entry():parent(win):size(160, 20)
  local r = hafen.ui():radio():parent(win):position(0, 24):rows{"061.8", "Amount"}

  -- nil is refused as nil, in the house's own words -- a table field is a passed argument, so an absent
  -- one reads the same way, and neither is reported as a wrong TYPE.
  refusesOnly("hafen.timer():after(nil, fn) is the house nil refusal, not a type refusal",
              function() hafen.timer():after(nil, function() end) end,
              "seconds must not be nil", "must be a number")
  local cfg = {}
  refusesOnly("hafen.timer():after(cfg.delay, fn) with cfg empty is that same message",
              function() hafen.timer():after(cfg.delay, function() end) end,
              "seconds must not be nil", "must be a number")

  -- A numeric string is not a number, and a number is not a string. Both directions, one helper.
  refuses("a numeric string is refused where a number is wanted: hafen.timer():after(\"2\", fn)",
          function() hafen.timer():after("2", function() end) end, "seconds must be a number")
  refuses("a number is refused where a string is wanted: hafen.slash():register(42, fn)",
          function() hafen.slash():register(42, function() end) end, "name must be a string")
  refuses("hafen.json():parse's refusal names the parameter its page names",
          function() hafen.json():parse(42) end, "hafen.json():parse: str must be a string")

  -- No LuaJ "bad argument: string expected, got no value" reaches an author, from any of these doors.
  local doors = {
    {"hafen.http():get(42)",          function() hafen.http():get(42) end},
    {"hafen.http():get()",            function() hafen.http():get() end},
    {"hafen.timer():after(1)",        function() hafen.timer():after(1) end},
    {"hafen.slash():register(\"x\")", function() hafen.slash():register("x") end},
    {"widget:position(nil, 10)",      function() e:position(nil, 10) end},
    {"widget:size(\"10\", 20)",       function() e:size("10", 20) end},
  }
  local bad = {}
  for _, d in ipairs(doors) do
    local msg = said(d[2])
    if msg == nil then
      bad[#bad + 1] = d[1] .. " -> <no error>"
    elseif msg:find("bad argument", 1, true) then
      bad[#bad + 1] = d[1] .. " -> " .. msg
    end
  end
  check(#bad == 0, "no LuaJ 'bad argument' reaches an author, at the six doors that used to leak one",
        table.concat(bad, " | "))
  refuses("widget:position(nil, 10) names the verb and the parameter",
          function() e:position(nil, 10) end, "widget:position: x must not be nil")

  -- The other direction, on the two controls that used to refuse an ordinary string.
  local took = pcall(function() e:value("42") end)
  local reads = e:value()
  local refused = said(function() e:value(42) end)
  check(took and (reads == "42") and (refused ~= nil) and (refused:find("v must be a string", 1, true) ~= nil),
        "entry:value(\"42\") is taken and reads back, and entry:value(42) is refused naming a string",
        tostring(reads) .. " / " .. tostring(refused))
  local picked = pcall(function() r:value("061.8") end)
  check(picked and (r:value() == "061.8"), "a radio row labelled \"061.8\" is a row, and is choosable",
        r:value())
  win:destroy()

  -- The world half: four of these need a character, the fifth an item, so retry for a bounded window
  -- and score what the run reached.
  local sdone, idone, tries = false, false, 0
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    if (not sdone) and s and s:exists() then
      sdone = true
      refuses("s:kin():add(1234) refuses naming a string, so no hearth secret was sent",
              function() s:kin():add(1234) end, "secret must be a string")
      refuses("s:kin():add(\"\") still refuses an empty secret -- the older refusal is not swallowed",
              function() s:kin():add("") end, "must not be empty")
      refuses("s:world():position(\"42\", 0) refuses the coercion",
              function() s:world():position("42", 0) end, "x must be a number")
      refuses("s:world():place(\"42\", 0, 1, 0) refuses naming a Position, so nothing was placed",
              function() s:world():place("42", 0, 1, 0) end, "p must be a Position")
    end
    if (not idone) and s then
      local it = s:ui():inventory():items()[1]
      if it then
        idone = true
        refuses("item:drop(\"2\") refuses naming a number, so nothing was dropped",
                function() it:drop("2") end, "n must be a number")
      end
    end
    if (sdone and idone) or (tries >= 20) then
      t:cancel()
      if not sdone then
        check(false, "the world half needs a character on screen", "no session reached in 10s")
      end
      if not idone then
        check(false, "item:drop(\"2\") refuses naming a number",
              "no item reached in 10s -- open an inventory and re-run")
      end
      hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
    end
  end)
end

hafen.slash():register("t084-4", run)   -- the only way in: a suite does not start itself
