-- 143.1 — hafen.voice(): the engine in-tree, and a link built bare, opened on purpose, and heard.
-- Self-checking suite.
--
-- The dry half runs at once: the section, the address refusals, a bare link's reads, the three
-- connect-time setters and their refusals, session() in its three arities, the closed key set, connect()
-- and the two refusals that follow it, and the push-to-talk binding that is no longer there. The live half
-- opens one link to the real server and one to a host that never resolves, closes the first from inside
-- its Open, and scores everything asynchronous on ONE timer at the connect timeout plus two seconds.
--
-- This suite declares voice.connect AND both hosts, so the no-key and no-hosts refusals, the shared
-- microphone, the two move-intent taps and the teardown Step are verified by reading their sites, not here.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal is a check: the call must fail, and fail SAYING why. The Java prefix carries a space, not a
-- colon, so the strip takes either.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A group of refusals scored as one line: n of total said what they had to.
local function refusals()
  local n, total, first = 0, 0, nil
  local g = {}
  function g.ask(label, fn, ...)
    total = total + 1
    local ok, err = pcall(fn)
    local msg = ok and "<no error>" or why(err)
    local said = not ok
    for _, want in ipairs({...}) do
      if msg:find(want, 1, true) == nil then said = false end
    end
    if said then n = n + 1 else first = first or (label .. " -> " .. msg) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", first or n) end
  return g
end

-- A group of facts scored as one line: each fn returns true, or the value that was wrong.
local function scored()
  local n, total, first = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    total = total + 1
    local ok, r = pcall(fn)
    if ok and (r == true) then n = n + 1
    else first = first or (label .. " -> " .. (ok and tostring(r) or why(r))) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", first or n) end
  return g
end

local SERVER = "wss://voice.brodgar.io"
local INVALID = "wss://ws.invalid"
local TIMEOUT = 10000                                        -- the connect timeout the suite leaves in force

local finished = false
local function finish()
  if finished then return end
  finished = true
  manualCheck("enable the suite in the AddOns panel and read its consent line",
              "use your microphone to talk on the voice servers it lists: voice.brodgar.io, ws.invalid")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

---------------------------------------------------------------------------------------------------------
-- The dry half.
---------------------------------------------------------------------------------------------------------
local function dry()
  local g = scored()
  g.want("hafen.voice() is one object", function() return hafen.voice() == hafen.voice() end)
  g.want("it counts 0", function() return hafen.voice():count() == 0 or hafen.voice():count() end)
  g.want("list() is an empty table", function()
    local l = hafen.voice():list()
    return (type(l) == "table" and #l == 0) or tostring(l)
  end)
  g.done("the section is one object and a collection: count() is 0 and list() is empty")

  local r = refusals()
  r.ask("ws://", function() return hafen.voice():connection("ws://voice.brodgar.io") end, "wss")
  r.ask("a space", function() return hafen.voice():connection("wss://a b") end, "wss://a b")
  r.done(":connection refuses ws:// naming wss, and a space naming the URL")

  g = scored()
  local c = hafen.voice():connection(SERVER)
  g.want("state() is new", function() return c:state() == "new" or c:state() end)
  g.want("url() is the address", function() return c:url() == SERVER or c:url() end)
  g.want("tostring names both", function()
    local s = tostring(c)
    return (s:find(SERVER, 1, true) ~= nil and s:find("new", 1, true) ~= nil) or s
  end)
  g.want("id() is nil", function() return c:id() == nil or tostring(c:id()) end)
  g.want("timeout() is 10000", function() return c:timeout() == TIMEOUT or c:timeout() end)
  g.want("spatial() is true", function() return c:spatial() == true or tostring(c:spatial()) end)
  g.want("bitrate() is 24000", function() return c:bitrate() == 24000 or c:bitrate() end)
  g.want("the setters chain and read back", function()
    return (c:timeout(TIMEOUT):spatial(false):bitrate(32000) == c
            and c:spatial() == false and c:bitrate() == 32000) or tostring(c:spatial()) .. "/" .. c:bitrate()
  end)
  g.done("a bare link reads new, its url, tostring names both, id() is nil, and the setters read back")
  c:spatial(true):bitrate(24000)

  r = refusals()
  r.ask("timeout(0)", function() c:timeout(0) end, "1 to 60000")
  r.ask("spatial(1)", function() c:spatial(1) end, "true or false")
  r.ask("bitrate(\"x\")", function() c:bitrate("x") end, "must be a number")
  r.ask("bitrate(1000)", function() c:bitrate(1000) end, "8000 to 64000")
  r.done("timeout(0) is refused naming 1..60000, spatial(1) a boolean, bitrate(\"x\") a number, bitrate(1000) the range")

  g = scored()
  local s = hafen.session():current()
  g.want("session() is hafen.session():current()", function()
    return c:session() == s or tostring(c:session())
  end)
  g.want("session(s) reads back", function()
    return (c:session(s) == c and c:session() == s) or tostring(c:session())
  end)
  g.want("session(nil) reads the current again", function()
    return (c:session(nil) == c and c:session() == hafen.session():current()) or tostring(c:session())
  end)
  g.want("session(42) is refused naming a Session", function()
    local ok, err = pcall(function() c:session(42) end)
    return (not ok) and (why(err):find("Session object", 1, true) ~= nil) or why(err)
  end)
  g.done("session() is the current session, session(s) reads back, session(nil) follows the screen again")

  r = refusals()
  r.ask("on(\"PeerSpeaking\")", function() return c:on("PeerSpeaking", function() end) end,
        "Open, Close, Error")
  r.done("on(\"PeerSpeaking\", fn) is refused naming Open, Close, Error")

  g = scored()
  local b = hafen.client():options():keybindings():binding():get("brodgar/ptt")
  g.want("brodgar/ptt does not exist", function() return b:exists() == false or tostring(b:exists()) end)
  g.done("the push-to-talk binding brodgar/ptt is gone: exists() is false")
  return c
end

---------------------------------------------------------------------------------------------------------
-- The live half: one link opened and closed, one that never resolves, scored on one timer.
---------------------------------------------------------------------------------------------------------
local seen = {}                                               -- what the handlers recorded

local function score()
  local g = scored()
  g.want("Open came", function() return seen.open == true or "no Open" end)
  g.want("state() read open inside it", function() return seen.openState == "open" or tostring(seen.openState) end)
  g.want("id() was a number inside it", function() return type(seen.id) == "number" or tostring(seen.id) end)
  g.want("stepping() was true inside it", function() return seen.stepping == true or tostring(seen.stepping) end)
  g.done("Open came with state open, a number in id() (" .. tostring(seen.id) .. "), on the step")

  g = scored()
  g.want("wss://ws.invalid ended in Error", function() return seen.invalidError ~= nil or "no Error" end)
  g.want("ev:error() names the host", function()
    return (seen.invalidError or ""):find("ws.invalid", 1, true) ~= nil or tostring(seen.invalidError)
  end)
  g.want("and it never opened", function() return not seen.invalidOpen or "Open fired for ws.invalid" end)
  g.want("state() read closed inside it", function() return seen.invalidState == "closed" or tostring(seen.invalidState) end)
  g.done("wss://ws.invalid, connected beside it, ended in Error naming the host")

  g = scored()
  g.want("close() read closing", function() return seen.closing == "closing" or tostring(seen.closing) end)
  g.want("Close came with a string in ev:reason()", function()
    return type(seen.reason) == "string" or tostring(seen.reason)
  end)
  g.want("ev:connection() == the link", function() return seen.same == true or tostring(seen.same) end)
  g.want("state() read closed inside it", function() return seen.closedState == "closed" or tostring(seen.closedState) end)
  g.want("id() read nil inside it", function() return seen.closedId == nil or tostring(seen.closedId) end)
  g.want("count() reads 0 now", function() return hafen.voice():count() == 0 or hafen.voice():count() end)
  g.done("after close(): closing, then Close with reason \"" .. tostring(seen.reason)
         .. "\", the link, state closed, and count() 0")
  finish()
end

local function live(c)
  c:on("Open", function(v)
    seen.open = true
    seen.openState = v:state()
    seen.id = v:id()
    seen.stepping = hafen.client():stepping()
    v:close()
    seen.closing = v:state()
  end)
  c:on("Close", function(ev)
    seen.reason = ev:reason()
    seen.same = (ev:connection() == c)
    seen.closedState = c:state()
    seen.closedId = c:id()
  end)
  c:on("Error", function(ev) seen.serverError = ev:error() end)
  local bad = hafen.voice():connection(INVALID)
  bad:on("Open", function() seen.invalidOpen = true end)
  bad:on("Error", function(ev)
    seen.invalidError = ev:error()
    seen.invalidState = bad:state()
  end)

  local g = scored()
  g.want("connect() chains", function() return c:connect() == c end)
  g.want("state() is connecting", function() return c:state() == "connecting" or c:state() end)
  g.want("a setter after connect() is refused naming :connect()", function()
    local ok, err = pcall(function() c:timeout(5000) end)
    return (not ok) and (why(err):find(":connect()", 1, true) ~= nil) or why(err)
  end)
  g.want("session(s) is still legal after connect()", function()
    return c:session(hafen.session():current()) == c or tostring(c:session())
  end)
  g.want("a second connect() is refused naming :connect()", function()
    local ok, err = pcall(function() c:connect() end)
    return (not ok) and (why(err):find(":connect()", 1, true) ~= nil) or why(err)
  end)
  g.want("a second link to the same server is refused naming this addon", function()
    local ok, err = pcall(function() hafen.voice():connection(SERVER):connect() end)
    return (not ok) and (why(err):find("143-voice.1", 1, true) ~= nil) or why(err)
  end)
  g.want("the ws.invalid link connects beside it", function() return bad:connect() == bad end)
  g.want("count() reads 2", function() return hafen.voice():count() == 2 or hafen.voice():count() end)
  g.done("connect() reads connecting; a setter and a second connect() are refused naming :connect(), a"
         .. " second link to the same server naming this addon; ws.invalid beside it reads count() 2")

  hafen.timer():after(TIMEOUT / 1000 + 2, score)
end

local function run()
  pass, fail, manual, finished, seen = 0, 0, 0, false, {}
  if hafen.voice():count() > 0 then
    for _, v in ipairs(hafen.voice():list()) do v:close() end
    hafen.log():write("[info] closed the links a previous run left; run :t143 again once they have gone")
    return
  end
  live(dry())
end

hafen.console():on("t143", run)   -- the only way in: a suite does not start itself
