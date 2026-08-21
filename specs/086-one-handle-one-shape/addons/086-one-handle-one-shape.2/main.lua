-- 086.2 -- what you registered, you can list. Self-checking suite.
--
-- hafen.timer(), hafen.sound(), hafen.asset() and hafen.vr():ghost() all answer "what of mine is live".
-- hafen.slash() carried register and nothing else, and hafen.event() carried on/action/message -- so an
-- addon could make a subscription and had no way to read back the set of them.
--
-- THE TWO CLAIMS ARE A COLLECTION AND A HUB. hafen.slash() is a collection whose members ARE the Subs it
-- handed out, so :list/:count/:find/:get all fall out of the one machinery every other collection uses.
-- hafen.event() is deliberately NOT one: it is the door for three emitters, so it gains two verbs that
-- read over all three -- which is the half a bus-only list would silently get wrong.

local pass, fail = 0, 0

-- A handler that has to exist and has to do nothing: what this suite subscribes with everywhere it is
-- proving the SHAPE of a set of subscriptions rather than what they deliver.
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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- The suite's own way in. It is a command like any other, so it is a member of the collection under test
-- and has to be discounted from every count -- and left standing, or a second run has no door.
local ENTRY = "t086-2"

-- ---- the slash collection --------------------------------------------------------------------------
--
-- Three commands, and every collection verb read back off them. The scored lines are one per claim: what
-- the verb answers is the point, and a line per command would say the same thing three times.
local function slashSection()
  -- A run that raised midway could have left its three standing, and registering a name twice makes a
  -- SECOND subscription on that key rather than replacing the first. So the run starts from a known
  -- baseline, ended through the very collection it is about to measure.
  for _, sub in ipairs(hafen.slash():list()) do
    if sub:key() ~= ENTRY then sub:off() end
  end
  local base = hafen.slash():count()

  local a = hafen.slash():on("t086b", noop)
  local b = hafen.slash():on("t086x", noop)
  local c = hafen.slash():on("t086y", noop)

  eq("hafen.slash():count() rises by one per command registered", hafen.slash():count() - base, 3)

  -- The array promise conventions.md makes: :list() is a plain 1-based array, so ipairs walks every
  -- member and # agrees with :count(). This is the loop that runs zero times over a map.
  local list = hafen.slash():list()
  local walked = 0
  for _, sub in ipairs(list) do walked = walked + 1 end
  check((walked == hafen.slash():count()) and (#list == walked),
        ("ipairs over hafen.slash():list() walks every member :count() reported (%d/%d)")
          :format(walked, hafen.slash():count()),
        "#list=" .. tostring(#list))

  -- :get(name) addresses one, and the member IS the subscription -- so sub:key() answers "what did I
  -- register this under" and == finds the very value :on handed back: one verb for the bus, the streams
  -- and the three registries alike, rather than a second type with a second name for the same question.
  local got = hafen.slash():get("t086b")
  check((got == a) and (got:key() == "t086b"),
        "hafen.slash():get(name) is the very Sub :on handed back, and its :key() is the command name",
        tostring(got) .. " / " .. tostring(got and got:key()))

  -- The string filter. A collection whose members had no name would refuse it; a command's name is its
  -- key, so a substring of it matches -- and only t086b carries "086b".
  check(hafen.slash():find("086b") == a,
        "hafen.slash():find(\"086b\") finds it by the string filter",
        tostring(hafen.slash():find("086b")))

  -- A command you never registered is not a thing to mint: the miss is nil, not an object and not an
  -- error, and the collection is what declares that rather than the lookup being read for it.
  eq("hafen.slash():get(\"nosuch\") is a miss rather than an error", hafen.slash():get("nosuch"), nil)

  -- Mounting the section as a collection did not lose the argument checks the verb already had.
  local msg = said(function() hafen.slash():on("t086c") end)
  check((msg ~= nil) and (msg:find("fn is required", 1, true) ~= nil),
        "hafen.slash():on(name) with no handler raises naming fn",
        msg or "<no error>")

  return base, a, b, c
end

-- ---- the hub -----------------------------------------------------------------------------------
--
-- hafen.event():list/:count read over the bus, the outbound action stream and the inbound message
-- stream. Subscribing on two DIFFERENT emitters and asserting the count rises by two is the whole of
-- the three-emitter claim: a bus-only list answers one here and looks right everywhere else.
local function eventSection()
  -- Same reason as the slash baseline, and the same way of reaching it: end whatever a run that raised
  -- midway left listening, so "exactly the one GobAdded" is a claim about this run.
  for _, sub in ipairs(hafen.event():list()) do sub:off() end
  local base = hafen.event():count()
  local gob = hafen.event():on("GobAdded", noop)
  local msg = hafen.event():message():on("t086msg", noop)

  eq("hafen.event():count() covers the bus AND the streams, not the bus alone",
     hafen.event():count() - base, 2)

  local found = hafen.event():list(function(s) return s:key() == "GobAdded" end)
  check((#found == 1) and (found[1] == gob),
        "hafen.event():list(pred) finds exactly the one GobAdded subscription",
        "#found=" .. tostring(#found))

  return base, gob, msg
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, r1, r2, r3, r4 = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r1))
    return nil
  end
  return r1, r2, r3, r4
end

local function run()
  pass, fail = 0, 0

  local sbase, a, b, c = section("slash", slashSection)
  local ebase, gob, msg = section("event", eventSection)

  -- The other half of a live read: what ends comes back out of the set. One line over both, because it
  -- is one claim -- a collection that only ever grows is a log, not a view.
  local fell = {}
  if a then
    a:off(); b:off(); c:off()
    if hafen.slash():count() ~= sbase then
      fell[#fell + 1] = "slash is " .. hafen.slash():count() .. ", not " .. sbase
    end
  end
  if gob then
    gob:off(); msg:off()
    if hafen.event():count() ~= ebase then
      fell[#fell + 1] = "event is " .. hafen.event():count() .. ", not " .. ebase
    end
  end
  check((a ~= nil) and (gob ~= nil) and (#fell == 0),
        "sub:off() on each and both counts fall back to where they started",
        (#fell > 0) and table.concat(fell, " | ") or "a section did not run")

  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

hafen.slash():on(ENTRY, run)   -- the only way in: a suite does not start itself
