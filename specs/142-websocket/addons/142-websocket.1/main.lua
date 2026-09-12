-- 142.1 — hafen.websocket(): a connection built bare, opened on purpose, and heard. Self-checking suite.
--
-- The dry half runs at once: the section, the builder's refusals, the setters, the gate against an origin
-- the manifest does not list, the cap of eight, and the codes :close() takes. The live half opens one
-- connection to an echo service, closes it from inside Open, and opens one to a host that does not resolve;
-- every asynchronous check is scored on ONE timer at the connect timeout plus two seconds, so a wait is
-- bounded and a verdict always prints. When the first echo ends in Error the second is tried, and the
-- timer is re-armed once for it.
--
-- This suite declares websocket.connect AND the hosts, so the no-key and the no-hosts refusals, the
-- teardown Step and the five-second close deadline are verified by reading their sites, not here.

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

local ECHO = "wss://ws.postman-echo.com/raw"
local FALLBACK = "wss://echo.websocket.org"
local INVALID = "wss://ws.invalid"
local TIMEOUT = 10000                                        -- the connect timeout the suite leaves in force

local finished = false
local function finish()
  if finished then return end
  finished = true
  manualCheck("enable the suite in the AddOns panel and read its consent line",
              "keep a live connection to the servers it lists: ws.postman-echo.com, echo.websocket.org,"
              .. " ws.invalid")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

---------------------------------------------------------------------------------------------------------
-- The dry half.
---------------------------------------------------------------------------------------------------------
local function dry()
  local g = scored()
  g.want("hafen.websocket() is one object", function() return hafen.websocket() == hafen.websocket() end)
  g.want("it counts 0", function() return hafen.websocket():count() == 0 end)
  g.want("# is refused", function()
    local ok, err = pcall(function() return #hafen.websocket() end)
    return (not ok) and (why(err):find("count()", 1, true) ~= nil) or why(err)
  end)
  g.done("the section is one object and a collection: count() is 0 and # is refused")

  local r = refusals()
  r.ask("ws://", function() return hafen.websocket():connection("ws://ws.postman-echo.com/raw") end, "wss")
  r.ask("https://", function() return hafen.websocket():connection("https://ws.postman-echo.com/") end, "https")
  r.ask("a space", function() return hafen.websocket():connection("wss://a b") end, "wss://a b")
  r.done(":connection refuses ws:// naming wss, https:// naming the scheme, and a space naming the URL")

  g = scored()
  local c = hafen.websocket():connection(ECHO)
  g.want("state() is new", function() return c:state() == "new" or c:state() end)
  g.want("url() is the address", function() return c:url() == ECHO or c:url() end)
  g.want("tostring names both", function()
    local s = tostring(c)
    return (s:find(ECHO, 1, true) ~= nil and s:find("new", 1, true) ~= nil) or s
  end)
  g.done("a bare connection reads new, its url, and tostring names both")

  g = scored()
  g.want("header(\"X-A\", \"1\") chains", function() return c:header("X-A", "1") == c end)
  g.want("and reads back under x-a", function() return c:header("x-a") == "1" or tostring(c:header("x-a")) end)
  g.want("timeout() is 10000", function() return c:timeout() == TIMEOUT or c:timeout() end)
  g.want("timeout(0) is refused naming 1..60000", function()
    local ok, err = pcall(function() c:timeout(0) end)
    return (not ok) and (why(err):find("1 to 60000", 1, true) ~= nil) or why(err)
  end)
  g.want("timeout(nil) is refused", function()
    local ok, err = pcall(function() c:timeout(nil) end)
    return (not ok) and (why(err):find("nil", 1, true) ~= nil) or why(err)
  end)
  g.done("a header reads back case-insensitively, the timeout is 10000, and 0 and nil are refused")

  r = refusals()
  r.ask("on(\"message\")", function() return c:on("message", function() end) end,
        "Open, Message, Close, Error")
  r.done("on(\"message\", fn) is refused naming Open, Message, Close, Error")

  r = refusals()
  r.ask("an unapproved origin", function() return hafen.websocket():connection("wss://api.example.org"):connect() end,
        "api.example.org", "approved:")
  r.done("connect() to wss://api.example.org is refused naming that origin and the approved list")

  r = refusals()
  r.ask("close(1002)", function() return c:close(1002) end, "1000 or 3000..4999")
  r.done("close(1002) is refused naming 1000 or 3000..4999")
  c:close()                                                  -- new: closed silently, holding no slot

  -- The cap: eight connect, the ninth is refused naming the read that saw it coming, all eight close. They
  -- read closing until the step delivers each Close; the live half waits for that below.
  local eight = {}
  local ok, err = pcall(function()
    for i = 1, 8 do
      eight[i] = hafen.websocket():connection(ECHO)
      eight[i]:connect()
    end
  end)
  g = scored()
  g.want("eight connect()ed", function() return ok or why(err) end)
  g.want("count() is 8", function() return hafen.websocket():count() == 8 or hafen.websocket():count() end)
  g.want("the ninth is refused naming :count()", function()
    local okn, errn = pcall(function() hafen.websocket():connection(ECHO):connect() end)
    return (not okn) and (why(errn):find("hafen.websocket():count()", 1, true) ~= nil) or why(errn)
  end)
  g.want("a setter after connect() is refused naming :connect()", function()
    local oks, errs = pcall(function() eight[1]:timeout(5000) end)
    return (not oks) and (why(errs):find(":connect()", 1, true) ~= nil) or why(errs)
  end)
  g.want("a second connect() is refused naming :connect()", function()
    local okc, errc = pcall(function() eight[1]:connect() end)
    return (not okc) and (why(errc):find(":connect()", 1, true) ~= nil) or why(errc)
  end)
  g.want("all eight close", function()
    for i = 1, 8 do
      if eight[i] and eight[i]:close() ~= eight[i] then return "close() did not chain" end
    end
    return eight[8] ~= nil and (eight[8]:state() == "closing" or eight[8]:state())
  end)
  g.done("eight connections count 8, the ninth and every write after connect() are refused, all eight close")
end

---------------------------------------------------------------------------------------------------------
-- The live half: one connection opened and closed, one that never resolves, scored on one timer.
---------------------------------------------------------------------------------------------------------
local seen = {}                                               -- what the handlers recorded
local deadline = nil
local fellBack = false

local function score()
  local g = scored()
  g.want("Open came", function() return seen.open == true or "no Open" end)
  g.want("state() read open inside it", function() return seen.openState == "open" or tostring(seen.openState) end)
  g.want("stepping() was true inside it", function() return seen.stepping == true or tostring(seen.stepping) end)
  g.want("the session's ui was readable inside it", function() return seen.ui == true or tostring(seen.ui) end)
  g.done("Open came with state open, on the step, with the session's ui readable"
         .. (fellBack and " (through the fallback echo)" or ""))

  g = scored()
  g.want("close(4000, \"bye\") read closing", function() return seen.closing == "closing" or tostring(seen.closing) end)
  g.want("Close came with a number in ev:code()", function()
    return type(seen.code) == "number" or tostring(seen.code)
  end)
  g.want("ev:connection() == conn", function() return seen.same == true or tostring(seen.same) end)
  g.want("state() read closed inside it", function() return seen.closedState == "closed" or tostring(seen.closedState) end)
  g.want("count() read 0 inside it", function() return seen.count == 0 or tostring(seen.count) end)
  g.done("after close(4000, \"bye\"): closing, then Close with code " .. tostring(seen.code)
         .. ", the connection, state closed and count 0")

  g = scored()
  g.want("wss://ws.invalid ended in Error", function() return seen.invalidError ~= nil or "no Error" end)
  g.want("ev:error() names the host", function()
    return (seen.invalidError or ""):find("ws.invalid", 1, true) ~= nil or tostring(seen.invalidError)
  end)
  g.want("and it never opened", function() return not seen.invalidOpen or "Open fired for ws.invalid" end)
  g.done("wss://ws.invalid ended in Error naming the host")
  finish()
end

local function arm()
  if deadline then deadline:cancel() end
  deadline = hafen.timer():after(TIMEOUT / 1000 + 2, score)
end

local function openEcho(url)
  local conn = hafen.websocket():connection(url)
  conn:on("Open", function(c)
    seen.open = true
    seen.openState = c:state()
    seen.stepping = hafen.client():stepping()
    local s = hafen.session():current()
    seen.ui = (s ~= nil) and (s:ui() ~= nil) or "no session on screen"
    c:close(4000, "bye")
    seen.closing = c:state()
  end)
  conn:on("Close", function(ev)
    seen.code = ev:code()
    seen.same = (ev:connection() == conn)
    seen.closedState = conn:state()
    seen.count = hafen.websocket():count()
  end)
  conn:on("Error", function(ev)
    if (not seen.open) and (not fellBack) and (url == ECHO) then
      fellBack = true                                      -- the first echo is down: the second, once
      hafen.log():write("[info] " .. ECHO .. " ended in Error (" .. ev:error() .. "); trying " .. FALLBACK)
      openEcho(FALLBACK)
      arm()
    else
      seen.echoError = ev:error()
    end
  end)
  conn:connect()
end

local function live()
  openEcho(ECHO)
  local bad = hafen.websocket():connection(INVALID)
  bad:on("Open", function() seen.invalidOpen = true end)
  bad:on("Error", function(ev) seen.invalidError = ev:error() end)
  bad:connect()
  arm()
end

local function run()
  pass, fail, manual, finished, seen, fellBack = 0, 0, 0, false, {}, false
  dry()
  -- The eight closed connections leave the collection once the step has delivered their Close; the live
  -- half needs the slots, so it starts when they have gone, and within a bounded wait.
  local waited, poll = 0, nil
  poll = hafen.timer():every(0.1, function()
    waited = waited + 1
    if hafen.websocket():count() == 0 or waited >= 30 then
      poll:cancel()
      check(hafen.websocket():count() == 0, "the eight closed connections left the collection within three seconds",
            hafen.websocket():count())
      live()
    end
  end)
end

hafen.console():on("t142", run)   -- the only way in: a suite does not start itself
