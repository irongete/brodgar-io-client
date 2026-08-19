-- 082.1 — every outbound message, under one name. Self-checking suite.
--
-- One login is enough. Every probe is sent from the HUD itself and cancelled inside the very
-- handler that observes it, so nothing the automated half asserts reaches the wire. The one send
-- that does reach it is the re-entrancy probe, which is the point of that check: a message sent
-- from inside a handler cannot be reported, and so cannot be cancelled either.

local PROBE  = "brodgar-probe"        -- the name every probe is sent under
local INNER  = "brodgar-inner"        -- sent from INSIDE a handler: the re-entrancy guard's subject
local NEVER  = "brodgar-never-sent"   -- subscribed to, and never sent
local WINDOW = 5                      -- seconds the counting window stays open for the manual line

local pass, fail, manual = 0, 0, 0

-- Held state is a run's own: the command can be run again, and without this the tally is the sum of
-- every run since the last `:reload`, which reads as a failure the current run did not have.
local function reset()
  pass, fail, manual = 0, 0, 0
end

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- What the run recorded for one message name, or nil. The wildcard records everything it is handed,
-- so a message the client happened to send of its own accord is passed over rather than mistaken
-- for the probe.
local function recorded(list, msg)
  for _, r in ipairs(list) do
    if r.msg == msg then return r end
  end
  return nil
end

local function run()
  reset()

  local s = hafen.session():current()
  if s == nil then
    check(false, "a session is on screen to send from", "no current session")
    return summary()
  end
  local gui = s:ui():find("@GameUI")
  if gui == nil then
    check(false, "the HUD is up, so there is a server-placed widget to send from", "no @GameUI")
    return summary()
  end

  refuses("a wildcard subscription still takes a function",
          function() hafen.event():action():on("*", "no") end, "(string, function)")

  -- The wildcard alone: a name it never wrote, with the whole ev on it.
  local seen = {}
  local w1 = hafen.event():action():on("*", function(ev)
    seen[#seen + 1] = { msg = ev:msg(), sender = ev:sender():type(), n = #ev:args() }
    ev:preventDefault()            -- observed, and off the wire: UI.wdgmsg returns before rawWdgmsg
  end)
  gui:send(PROBE, 1)
  w1:off()
  local got = recorded(seen, PROBE)
  check(got ~= nil, "a wildcard is handed a message it never named (" .. PROBE .. ")",
        (#seen == 0) and "<nothing fired>" or (#seen .. " other message(s)"))
  check((got ~= nil) and (got.sender == "GameUI"), "...with the sending widget on it (GameUI)",
        got and got.sender)
  check((got ~= nil) and (got.n == 1), "...and its one argument", got and got.n)

  -- A name AND the wildcard at once. The wildcard is registered FIRST on purpose: within one addon
  -- handlers fire in registration order, so named-first can only be the dispatch's doing.
  local order, evs = {}, {}
  local w2 = hafen.event():action():on("*", function(ev)
    order[#order + 1] = "wild:" .. ev:msg()
    evs.wild = ev
    if ev:msg() == PROBE then
      gui:send(INNER, 1)           -- from inside a handler: the stream is not re-entered
    end
    ev:preventDefault()
  end)
  local n2 = hafen.event():action():on(PROBE, function(ev)
    order[#order + 1] = "named:" .. ev:msg()
    evs.named = ev
    ev:preventDefault()
  end)
  local neverRan = 0
  local n3 = hafen.event():action():on(NEVER, function() neverRan = neverRan + 1 end)
  gui:send(PROBE, 1, 2)

  check((evs.named ~= nil) and (evs.wild ~= nil), "holding a name and the wildcard, both handlers ran",
        table.concat(order, ", "))
  check((order[1] == "named:" .. PROBE) and (order[2] == "wild:" .. PROBE),
        "...the named key first, the wildcard second", table.concat(order, ", "))
  check((evs.named ~= nil) and (evs.named == evs.wild), "...over ONE ev, the very same value",
        (evs.named ~= nil) and tostring(evs.named) .. " vs " .. tostring(evs.wild) or "nothing fired")
  check(#order == 2, "a message sent from inside a handler is reported to nobody (" .. INNER .. ")",
        table.concat(order, ", "))

  -- sub:off() on the wildcard ends that subscription alone.
  w2:off()
  gui:send(PROBE, 3)
  check((#order == 3) and (order[3] == "named:" .. PROBE),
        "sub:off() on the wildcard leaves the named subscription firing", table.concat(order, ", "))
  n2:off()

  check(neverRan == 0, "a handler that named a key this run never sends is never called", neverRan)
  n3:off()

  -- The counting window, and the one thing a program cannot do: click.
  manualCheck("the counting window is OPEN for " .. WINDOW .. " seconds from now -- click the ground"
              .. " once to move",
              "your character actually walks (an observed send still reaches the server), and the"
              .. " [note] line below counts that click among the messages it saw")
  local count, names = 0, {}
  local w3 = hafen.event():action():on("*", function(ev)
    count = count + 1
    names[ev:msg()] = (names[ev:msg()] or 0) + 1     -- counted, never cancelled
  end)
  hafen.timer():after(WINDOW, function()
    w3:off()
    local parts = {}
    for m, n in pairs(names) do parts[#parts + 1] = m .. " x" .. n end
    table.sort(parts)
    hafen.log():write("[note] the window saw " .. count .. " message(s): "
                      .. ((#parts > 0) and table.concat(parts, ", ") or "none"))
    summary()
  end)
end

hafen.slash():register("t082-1", run)   -- the only way in: a suite does not start itself
