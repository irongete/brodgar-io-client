-- 142.2 — messages on a connection: conn:send(v), conn:pending() and Message. Self-checking suite.
--
-- Dry checks first, on a bare connection and then inside Open; then a string, a table and a burst of
-- five go to the echo server, and the run is scored over the Message texts carrying this run's nonce
-- (echo.websocket.org greets first, and the greeting is not ours). The verdict prints as soon as Close
-- has run, and at the latest on one timer: two handshake timeouts, one per echo server, plus two seconds.

local ECHO = { "wss://ws.postman-echo.com/raw", "wss://echo.websocket.org" }
local TIMEOUT_MS = 5000

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- The Java prefix is "@main.lua:12 msg", a space and not a colon after the line number.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every needle must be in the message.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for _, needle in ipairs({ ... }) do
    if not err:find(needle, 1, true) then said = false end
  end
  check(said, what, err)
end

local function run()
  pass, fail, manual = 0, 0, 0
  local nonce = tostring(math.random(100000, 999999))
  local got = {}                 -- the Message texts carrying the nonce, in the order they arrived
  local sameConn = true          -- ev:connection() == conn held for every Message
  local evShape, evRefusal       -- tostring(ev) of the first Message, and what ev:body() raised
  local sendChain                -- did c:send(v) hand the connection back?
  local pendingAfterSend         -- conn:pending() right after the seven sends
  local pendingAtClose           -- conn:pending() once the seven echoes were in
  local closeCode, closeState    -- what Close said, and the state read inside it
  local errorText                -- the last Error, when no echo server answered
  local scored = false

  -- dry: a bare connection takes no message, and holds none
  local bare = hafen.websocket():connection(ECHO[1])
  refuses("a bare connection refuses send naming Open and conn:state()",
          function() bare:send("x") end, "Open", "conn:state()")
  eq("pending() reads 0 on a bare connection", bare:pending(), 0)

  local function score()
    if scored then return end
    scored = true
    eq("the string came back as sent", got[1], "hello " .. nonce)
    local okParse, t = pcall(function() return hafen.json():parse(got[2] or "") end)
    check(okParse and (type(t) == "table") and (t.tag == nonce) and (t.n == 1),
          "the table came back as JSON that parses to tag == nonce", got[2])
    local ordered = (#got == 7)
    for k = 1, 5 do
      if got[k + 2] ~= (nonce .. " " .. k) then ordered = false end
    end
    check(ordered, "the five arrived in order, after the two", table.concat(got, " | "))
    check(sameConn and (#got > 0), "ev:connection() == conn for every Message", sameConn)
    check((evShape == "Event(message)") and (evRefusal or ""):find(":text()", 1, true) ~= nil,
          "ev is a message event: tostring names it, an unknown verb is refused naming :text()",
          tostring(evShape) .. " / " .. tostring(evRefusal))
    check(sendChain == true, "send(v) hands the connection back", sendChain)
    check((pendingAtClose == 0) and (pendingAfterSend ~= nil) and (pendingAfterSend <= 7),
          "pending() reads 0 once echoed, and never more than the seven sent",
          tostring(pendingAfterSend) .. " then " .. tostring(pendingAtClose))
    eq("Close came after close() with code 1000", closeCode, 1000)
    eq("the state read closed inside Close", closeState, "closed")
    if errorText then
      check(false, "an echo server answered", "every one ended in Error, the last: " .. errorText)
    end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end

  local function attempt(i)
    local conn = hafen.websocket():connection(ECHO[i]):timeout(TIMEOUT_MS)
    conn:on("Open", function(c)
      -- dry, on an open one: the wrong kind, the cap, and a table JSON cannot write
      refuses("send(1) is refused naming a string and a table", function() c:send(1) end, "a string", "a table")
      refuses("a message over the cap is refused naming it",
              function() c:send(string.rep("x", 1048577)) end, "1048576")
      refuses("a table with a function inside is refused naming JSON",
              function() c:send({ f = function() end }) end, "JSON")
      sendChain = (c:send("hello " .. nonce) == c)
      c:send({ tag = nonce, n = 1 })
      for k = 1, 5 do c:send(nonce .. " " .. k) end
      pendingAfterSend = c:pending()
    end)
    conn:on("Message", function(ev)
      local text = ev:text()
      if not text:find(nonce, 1, true) then return end
      got[#got + 1] = text
      if ev:connection() ~= conn then sameConn = false end
      if not evShape then
        evShape = tostring(ev)
        local ok, err = pcall(function() return ev:body() end)
        evRefusal = ok and "<no error>" or why(err)
      end
      if #got == 7 then
        pendingAtClose = conn:pending()
        conn:close()
      end
    end)
    conn:on("Close", function(ev)
      closeCode = ev:code()
      closeState = ev:connection():state()
      score()
    end)
    conn:on("Error", function(ev)
      if i < #ECHO then
        hafen.log():write("[info] " .. ECHO[i] .. " ended in Error (" .. ev:error() .. "), trying " .. ECHO[i + 1])
        attempt(i + 1)
      else
        errorText = ev:error()
        score()
      end
    end)
    conn:connect()
  end

  attempt(1)
  hafen.timer():after(TIMEOUT_MS * 2 / 1000 + 2, score)
end

hafen.console():on("t142", run)   -- the only way in: a suite does not start itself
