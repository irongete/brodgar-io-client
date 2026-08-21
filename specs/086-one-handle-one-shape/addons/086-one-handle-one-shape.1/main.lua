-- 086.1 -- every :on hands back a Sub. Self-checking suite.
--
-- Three registries used to disagree with the API's one notification verb: a selector watch and a slash
-- command handed back a plain table ended with handle:remove(), and a hotkey handed back the keybindings
-- handle, ended by naming the hotkey a second time. A user who had learnt the bus wrote cmd:off() and got
-- "attempt to call a nil value" -- no metatable, so no message and no fix.
--
-- THE CLAIM IS ONE SHAPE, THREE TIMES, so the shape check is one scored line over the three rather than
-- three lines saying the same thing. Then the mistake that used to be silent: sub:remove() must RAISE
-- naming :off(), which only a closed vocabulary can do.

local pass, fail, manual = 0, 0, 0

-- A handler that has to exist and has to do nothing: what this suite subscribes with everywhere it is
-- proving the SHAPE of a subscription rather than what it delivers.
local function noop() end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- ---- the shape, three times ------------------------------------------------------------------------
--
-- Userdata (so nothing in Lua can write a verb off it), a tostring a person can read, :key() answering
-- what the registration was addressed by, and an :off() that is idempotent. Returns nil when the sub is
-- all four, else the complaint -- so the caller scores and the failure says which of the three broke.
local function wrong(label, key, sub)
  if type(sub) ~= "userdata" then return label .. " hands back a " .. type(sub) end
  if tostring(sub) ~= ("Sub(" .. key .. ")") then return label .. " prints " .. tostring(sub) end
  local ok, k = pcall(function() return sub:key() end)
  if (not ok) or (k ~= key) then return label .. ":key() is " .. tostring(k) end
  if not pcall(function() sub:off() end) then return label .. ":off() raised" end
  if not pcall(function() sub:off() end) then return label .. ":off() is not idempotent" end
  return nil
end

-- ---- the retirements -------------------------------------------------------------------------------
--
-- Each old spelling must raise, and name the one that replaced it. A rename is free only when the
-- refusal carries the new name: a bare "has no verb" says the call is wrong without saying what is right.
local function retirements(kb)
  local named, bad = 0, {}
  local rows = {
    {"hafen.slash():register", "hafen.slash():on", function() hafen.slash():register("t086x", noop) end},
    {"kb:register",            "kb:on(",           function() kb:register("t086x", noop) end},
    {"kb:unregister",          "sub:off()",        function() kb:unregister("t086x") end},
  }
  for _, row in ipairs(rows) do
    local msg = said(row[3])
    if msg == nil then
      bad[#bad + 1] = row[1] .. " did not raise"
    elseif not msg:find(row[2], 1, true) then
      bad[#bad + 1] = row[1] .. ": " .. msg
    else
      named = named + 1
    end
  end
  check(named == 3,
        ("every retired spelling raises naming its replacement (%d/3)"):format(named),
        (#bad > 0) and table.concat(bad, " | ") or "nothing raised")
end

-- ---- the manual half, left standing --------------------------------------------------------------
--
-- A command that runs, and the same handler on a hotkey the user may assign. Nothing in Lua can deliver a
-- console line or a keypress, so this half is the maintainer's. Re-running the suite ends the previous
-- pair first, so a second run leaves exactly one of each.
local probe, probeKey, probeOff

local function leaveProbe(kb)
  if probe then probe:off() end
  if probeKey then probeKey:off() end
  if probeOff then probeOff:off() end
  local function fired()
    hafen.log():write("086.1 probe: the command still runs")
  end
  probe = hafen.slash():on("t086", fired)
  probeKey = kb:on("t086", fired)
  probeOff = hafen.slash():on("t086off", function()
    probe:off()
    probeKey:off()
    hafen.log():write("086.1 probe: sub:off() on both -- type :t086 again")
  end)
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, arg)
  local ok, err = pcall(fn, arg)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(err))
  end
end

local function body(s)
  local kb = hafen.client():options():keybindings()

  -- The three registries, in one line. The watch is the only one that needs a character, so a run made
  -- outside the world scores two of three and says so rather than failing all of it.
  local subs, bad = {}, {}
  subs[#subs + 1] = {"hafen.slash():on", "t086shape", hafen.slash():on("t086shape", noop)}
  subs[#subs + 1] = {"kb:on", "t086key", kb:on("t086key", noop)}
  if s then
    subs[#subs + 1] = {"s:ui():on", "appear", s:ui():on("window", "appear", noop)}
  else
    bad[#bad + 1] = "s:ui():on unreached -- no character in the world"
  end
  local shaped = 0
  for _, row in ipairs(subs) do
    local why = wrong(row[1], row[2], row[3])
    if why then bad[#bad + 1] = why else shaped = shaped + 1 end
  end
  check(shaped == 3,
        ("every :on hands back a Sub -- userdata, tostring, :key(), idempotent :off() (%d/3)")
          :format(shaped),
        (#bad > 0) and table.concat(bad, " | ") or "?")

  -- The mistake that used to be silent. handle:remove() has no row of its own: the handle is a Sub, and a
  -- closed vocabulary is what turns the old spelling into a sentence naming the new one.
  local told, missed = 0, {}
  for _, row in ipairs(subs) do
    local msg = said(function() row[3]:remove() end)
    if msg == nil then
      missed[#missed + 1] = row[1] .. "'s sub took :remove()"
    elseif not msg:find(":off()", 1, true) then
      missed[#missed + 1] = row[1] .. ": " .. msg
    else
      told = told + 1
    end
  end
  check(told == 3,
        ("sub:remove() raises naming :off() (%d/3)"):format(told),
        (#missed > 0) and table.concat(missed, " | ") or "?")

  section("retirement", retirements, kb)

  -- Wrapping the registration in Subs did not drop the registry's own validation.
  local msg = said(function() hafen.slash():on("has space", noop) end)
  check((msg ~= nil) and (msg:find("non-empty word with no spaces", 1, true) ~= nil),
        "hafen.slash():on(\"has space\", fn) is refused naming a word with no spaces",
        msg or "<no error>")

  section("probe", leaveProbe, kb)
  manualCheck("type :t086 in the console",
              "the line \"086.1 probe: the command still runs\"")
  manualCheck("run :t086off, then type :t086 again",
              "the console reports that no addon currently handles :t086")
end

local function run()
  pass, fail, manual = 0, 0, 0
  -- A character is needed for the watch alone. Wait a bounded window for one and score what the run
  -- reached, rather than making the whole verdict depend on where the maintainer was standing.
  local tries, t = 0, nil
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local here = s and s:exists()
    if (tries < 24) and not here then return end
    t:cancel()
    section("main", body, here and s or nil)
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():on("t086-1", run)   -- the only way in: a suite does not start itself
