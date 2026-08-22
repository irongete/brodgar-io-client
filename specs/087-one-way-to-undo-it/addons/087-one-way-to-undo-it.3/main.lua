-- 087.3 -- a collection destroys its member, and the rule is written down. Self-checking suite.
--
-- hafen.session() was the one collection in the API that could destroy a member and had no :remove. Now it
-- has one, behind the same session.close key s:close() carries, and the claim is threefold: the new door
-- ENDS the login, it hands the COLLECTION back so removals chain, and it refuses exactly what s:close()
-- refuses, in the same words -- or one mistake reads as two different problems depending on which door
-- found it.
--
-- Then the rule conventions.md now states has to describe the bridge rather than an intention, so a sample
-- of the endings it names is called and compared to its own receiver.
--
-- This suite DECLARES session.close and nothing else. That is deliberate twice over: the removal needs the
-- key, and a protected verb whose key is NOT declared must still refuse -- which is also where the gate's
-- ordering shows, since it fires for a session the client does not hold and so ran before anything was
-- resolved.

local pass, fail, manual = 0, 0, 0

local GHOST = "nobodyhere087"        -- an account no client holds: both doors must refuse it identically

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

-- The message a call raised, with the chunk prefix Lua puts on it stripped; nil if it did not raise.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function refuses(what, fn, wantMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil), what, msg or "<no error>")
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, r = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- ---- the two doors -----------------------------------------------------------------------------------
--
-- Everything after the verb's own name has to be one sentence, so the refusal is read out of both and
-- compared from the word they must share onwards.

local function tail(msg)
  return msg and msg:match("the client holds no session.*")
end

local function doorsSection()
  local ghost = hafen.session():get(GHOST)
  local viaColl = said(function() hafen.session():remove(ghost) end)
  local viaMember = said(function() ghost:close() end)
  local a, b = tail(viaColl), tail(viaMember)
  -- Reaching this refusal at all is the other half of the claim: this addon declared session.close and
  -- nothing else, so both doors cleared the ONE key before either looked at the account.
  check((a ~= nil) and (a == b) and (a:find("s:exists()", 1, true) ~= nil),
        "session.close opens both doors, and both refuse an account nobody holds in the same words,"
        .. " naming s:exists()",
        tostring(viaColl) .. "  ||  " .. tostring(viaMember))
end

-- ---- what the verb takes -----------------------------------------------------------------------------
--
-- The members are objects, so keyOrMember is the object here: :get(user) mints a Session for any string,
-- and an ending whose receiver was never checked against anything is the one this must not be.

local function refusalSection()
  refuses("hafen.session():remove(\"alice\") is refused, naming a Session",
          function() hafen.session():remove("alice") end, "must be a Session object")
  refuses("...and it names hafen.session():get(user) as what hands one back",
          function() hafen.session():remove("alice") end, "hafen.session():get(user)")
end

-- ---- the gate ----------------------------------------------------------------------------------------
--
-- This addon declared session.close and nothing else, so a verb behind another key must still refuse and
-- must name ITS OWN key. The receiver is a session the client does not hold, so a gate that ran after the
-- resolution would have raised about the session instead -- which is the ordering D-213 asks for, on the
-- very machinery hafen.session():remove(s) gates through.

local function gateSection()
  local msg = said(function() hafen.session():get(GHOST):kin():add("nosuchsecret") end)
  check((msg ~= nil) and (msg:find("kin.add", 1, true) ~= nil) and (msg:find(GHOST, 1, true) == nil),
        "a key this addon did not declare still refuses, naming that key and not the receiver",
        msg or "<no error>")
end

-- ---- the rule describes the bridge ---------------------------------------------------------------------
--
-- conventions.md now names seven endings and says every one of them hands the receiver back. Three of them
-- can be exercised with nothing arranged: a sound this addon never played, a map hold it never took, and a
-- member of the one collection it owns outright.

local function ruleSection()
  local n, bad = 0, {}
  local function chains(verb, got, want)
    if got == want then n = n + 1 else bad[#bad + 1] = verb .. " -> " .. tostring(got) end
  end
  local snd = hafen.sound():get("sfx/msg")
  chains("sound:stop", snd:stop(), snd)
  local toggle = hafen.map():overlay():get("cplot")
  chains("toggle:release", toggle:release(), toggle)
  local assets = hafen.asset()
  local dot = assets:get("dot.png")
  chains("coll:remove", assets:remove(dot), assets)
  check(n == 3, "the endings the rule names each answer their receiver (" .. n .. "/3)",
        table.concat(bad, ", "))
end

-- ---- the ending itself -------------------------------------------------------------------------------
--
-- The one check that needs a second login, because ending the session on screen would end the run. The
-- drop is asynchronous -- Sessions.Member.drop returns before the member leaves the list -- so :exists()
-- and the count are read back over a bounded window rather than on the next line.

local function pickVictim()
  local list = hafen.session():list()
  if #list < 2 then return nil end        -- never the only login: one arriving holds no screen either
  local cur = hafen.session():current()
  for _, s in ipairs(list) do
    if s ~= cur then return s end
  end
  return nil
end

local function run()
  pass, fail, manual = 0, 0, 0

  manualCheck("log a second character in BEFORE running this, and watch its client window while the run"
              .. " finishes", "that window closes and the login goes, leaving the one you are looking at")
  -- The dialog is raised by TICKING the box, not by the client starting: a grant is persisted per addon
  -- and is additive, so an addon already approved comes up enabled and says nothing. Unticking and
  -- re-ticking is what asks again, with what was already granted marked as such.
  manualCheck("in Options > AddOns, untick this addon and tick it again to raise its consent dialog, then"
              .. " report the line it shows for session.close",
              "log out any of your characters")

  -- Read first, so the line below is about the refusals rather than about whatever was up when the run
  -- started: a gate or an argument check that dropped something on its way to raising is exactly the
  -- ordering defect D-213 forbids, and the membership is what reports it.
  local start = hafen.session():count()
  section("doors", doorsSection)
  section("refusal", refusalSection)
  section("gate", gateSection)
  check(hafen.session():count() == start,
        "every refusal above dropped nothing: the membership is unchanged",
        start .. " -> " .. hafen.session():count())
  section("rule", ruleSection)

  local before = hafen.session():count()
  local victim = pickVictim()
  if victim == nil then
    check(false, "hafen.session():remove(s) ends a login and hands the collection back",
          "no second login was up -- log one in and run :t087-3 again")
    check(false, "the removed login is gone: s:exists() false and hafen.session():count() down by one",
          "no second login was up")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local answered = section("ending", function() return hafen.session():remove(victim) end)
  check(answered == hafen.session(),
        "hafen.session():remove(s) ends a login and hands the COLLECTION back, so removals chain", answered)

  local tries = 0
  local function poll()
    local gone = (victim:exists() == false) and (hafen.session():count() == before - 1)
    if gone or (tries >= 40) then
      check(gone, "the removed login is gone: s:exists() false and hafen.session():count() down by one",
            tostring(victim:exists()) .. " / " .. before .. " -> " .. hafen.session():count())
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      return
    end
    tries = tries + 1
    hafen.timer():after(0.5, poll)
  end
  poll()
end

hafen.slash():on("t087-3", run)               -- the only way in: a suite does not start itself
