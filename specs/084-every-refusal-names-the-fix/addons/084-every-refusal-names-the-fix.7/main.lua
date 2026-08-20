-- 084.7 -- the doors the nine files did not reach. Self-checking suite.
--
-- The negative is the whole point: every door below used to answer a LuaJ "bad argument: string expected,
-- got no value", which names neither the verb that was called nor the argument that was wrong. Each must
-- now raise saying "<verb>: <param> ...", and the range refusals sitting underneath must still be the ones
-- that fire when the TYPE is right.

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

-- The same, and the message must NOT carry the other sentence: a value of the right type that breaks the
-- rule underneath must go on naming the RULE, never the type check now standing in front of it.
local function refusesOnly(what, fn, wantMsg, forbidMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil)
        and (msg:find(forbidMsg, 1, true) == nil), what, msg or "<no error>")
end

local function run()
  local p = hafen.client():profiling()
  local opts = hafen.client():options()

  -- The fourteen doors, at once. Each must raise, no message may carry "bad argument", and each must name
  -- its own verb followed by its own parameter -- which is exactly the half a LuaJ argerror drops.
  local doors = {
    {"p:scope()",                          "client:profiling():scope",   "name",    function() p:scope() end},
    {"p:scope(1)",                         "client:profiling():scope",   "name",    function() p:scope(1) end},
    {"p:measure()",                        "client:profiling():measure", "name",    function() p:measure() end},
    {"opts:audio():masterVolume(\"loud\")", "audio:masterVolume",        "v",       function() opts:audio():masterVolume("loud") end},
    {"opts:audio():latency(\"20\")",       "audio:latency",              "ms",      function() opts:audio():latency("20") end},
    {"opts:video():fpsLimit(\"60\")",      "video:fpsLimit",             "v",       function() opts:video():fpsLimit("60") end},
    {"opts:video():bgFpsLimit(\"30\")",    "video:bgFpsLimit",           "v",       function() opts:video():bgFpsLimit("30") end},
    {"opts:video():renderScale(\"1\")",    "video:renderScale",          "v",       function() opts:video():renderScale("1") end},
    {"opts:video():lightLimit(\"8\")",     "video:lightLimit",           "n",       function() opts:video():lightLimit("8") end},
    {"opts:video():lightingMode(1)",       "video:lightingMode",         "mode",    function() opts:video():lightingMode(1) end},
    {"opts:interface():scale(\"2\")",      "interface:scale",            "v",       function() opts:interface():scale("2") end},
    {"opts:interface():posGran(\"5\")",    "interface:posGran",          "v",       function() opts:interface():posGran("5") end},
    {"opts:interface():angGran(\"15\")",   "interface:angGran",          "degrees", function() opts:interface():angGran("15") end},
    {"opts:camera():mode(1)",              "camera:mode",                "name",    function() opts:camera():mode(1) end},
  }
  local bad = {}
  for _, d in ipairs(doors) do
    local msg = said(d[4])
    if msg == nil then
      bad[#bad + 1] = d[1] .. " -> <no error>"
    elseif msg:find("bad argument", 1, true) then
      bad[#bad + 1] = d[1] .. " -> " .. msg
    elseif msg:find(d[2] .. ": " .. d[3], 1, true) == nil then
      bad[#bad + 1] = d[1] .. " -> does not say \"" .. d[2] .. ": " .. d[3] .. "\": " .. msg
    end
  end
  check(#bad == 0, ("all %d doors refuse naming their own verb and parameter, none says \"bad argument\"")
        :format(#doors), table.concat(bad, " | "))

  -- The third door in the same file, and the one a string helper cannot close.
  refuses("p:measure(\"x\", \"notafunction\") names fn and what it has to be",
          function() p:measure("x", "notafunction") end,
          "client:profiling():measure: fn must be a function")
  -- ...and a good call still answers, so the doors were closed and not merely nailed shut.
  local sc = p:scope("brodgar-084-7")
  check((sc ~= nil) and (sc:name() == "brodgar-084-7"), "p:scope(name) still answers a scope of that name",
        sc and sc:name())

  -- The coercion, on the numeric controls: one family, one type language.
  local sl = hafen.ui():slider():visible(false):range(0, 100)
  refuses("slider:value(\"50\") refuses naming a number", function() sl:value("50") end,
          "widget:value: v must be a number")
  refuses("slider:range(\"0\", 100) refuses naming a number", function() sl:range("0", 100) end,
          "widget:range: min must be a number")
  local took = pcall(function() sl:value(50) end)
  check(took and (sl:value() == 50), "slider:value(50) within :range(0, 100) is still taken and reads back",
        tostring(took) .. " / " .. tostring(sl:value()))
  sl:destroy()

  -- The refusals underneath: a value of the RIGHT type that breaks a rule must still name the rule.
  refusesOnly("opts:audio():masterVolume(2) goes on naming the 0.0..1.0 range, not the type",
              function() opts:audio():masterVolume(2) end, "between 0.0 and 1.0", "must be a number")
  refusesOnly("opts:camera():mode(\"nosuchcam\") goes on naming the cameras the client has, not the type",
              function() opts:camera():mode("nosuchcam") end, "no such camera", "must be a string")

  -- A Position needs a character in the world, so retry for a bounded window and score what the run reached.
  local tries = 0
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local g = s and s:exists() and s:player() and s:player():gob()
    local pos = g and g:position()
    if pos or (tries >= 20) then
      t:cancel()
      if pos then
        refuses("p:offset(\"1\", 2) refuses naming a number", function() pos:offset("1", 2) end,
                "position:offset: dx must be a number")
      else
        check(false, "p:offset(\"1\", 2) refuses naming a number",
              "no Position reached in 10s -- needs a character in the world")
      end
      hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
    end
  end)
end

hafen.slash():register("t084-7", run)   -- the only way in: a suite does not start itself
