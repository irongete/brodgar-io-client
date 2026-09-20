-- 159.3 — the cache follows the pack. Self-checking suite.
-- Run in sqlite mode (-Dhaven.store=sqlite), in the world.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function syslog(session)
  return session:chat():find(function(channel) return channel:kind() == "chat.system" end)
end

-- Say `line` (":store" or ":store sweep") at the session and read the report back from the System log,
-- polled up to `limit` seconds (a sweep runs on the console's thread before its report prints). fn gets
-- { store = "sqlite", map = "<path> — <n> entries, <x> MB", res = ..., sweep = "examined n, dropped m" } or nil.
local function readStore(session, line, limit, fn)
  local channel = syslog(session)
  local before = channel and channel:message():count() or 0
  session:console():run(line)
  local waited, poll = 0, nil
  poll = hafen.timer():every(0.1, function()
    waited = waited + 0.1
    local found = {}
    channel = syslog(session)
    if channel then
      local messages = channel:message()
      for i = before + 1, messages:count() do
        local message = messages:get(i)
        local key, rest = ((message and message:text()) or ""):match("^(%a+):%s*(.-)%s*$")
        if key == "store" or key == "map" or key == "res" or key == "sweep" or key == "data" then
          found[key] = rest
        end
      end
    end
    if found.sweep or waited >= limit then
      poll:cancel()
      fn(found.store and found or nil)
    end
  end)
end

local function entries(line)
  return tonumber((line or ""):match("(%d+) entries"))
end

local function counts(line)
  local examined, dropped = (line or ""):match("^examined (%d+), dropped (%d+)$")
  return tonumber(examined), tonumber(dropped)
end

local function finish()
  manualCheck("rebuild brodgar-res.jar after a session that fetched from the network, restart in sqlite mode and type :store",
              "dropped > 0, and the res: entry count lower than before the restart")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function fails(what)
  check(false, what, "store is not sqlite")
end

-- 3. a second :store sweep drops nothing, read back through a plain :store
local function secondSweep(session)
  readStore(session, "store sweep", 10, function()
    readStore(session, "store", 2, function(lines)
      local examined, dropped = counts(lines and lines.sweep)
      check(examined and dropped == 0, ":store after a second sweep reports dropped 0 (nothing left to drop)",
            lines and lines.sweep or "<no :store output>")
      finish()
    end)
  end)
end

-- 2. :store sweep runs the sweep now and its counts add up against the store
local function firstSweep(session)
  readStore(session, "store sweep", 10, function(lines)
    local examined, dropped = counts(lines and lines.sweep)
    local after = entries(lines and lines.res)
    check(examined and dropped and after and dropped <= examined and examined <= after + dropped,
          ":store sweep reports examined >= dropped, and examined <= the res: entries left + dropped",
          (lines and lines.sweep or "<no sweep line>") .. " | res: " .. tostring(lines and lines.res))
    secondSweep(session)
  end)
end

-- 1. :store reports the start-up sweep: a sweep: line with two numbers, polled while it is still running
local function startupSweep(session, tries)
  readStore(session, "store", 2, function(lines)
    if not (lines and lines.store == "sqlite") then
      check(false, "the store in force is sqlite (:store reached the System log)",
            lines and lines.store or "<no :store output within 2 s>")
      fails(":store reports the start-up sweep as examined <n>, dropped <m>")
      fails(":store sweep reports examined >= dropped, and examined <= the res: entries left + dropped")
      fails(":store after a second sweep reports dropped 0 (nothing left to drop)")
      finish()
    elseif lines.sweep == "running" and tries < 20 then
      hafen.timer():after(0.5, function() startupSweep(session, tries + 1) end)
    else
      check(true, "the store in force is sqlite (:store reached the System log)")
      local examined, dropped = counts(lines.sweep)
      check(examined and dropped, ":store reports the start-up sweep as examined <n>, dropped <m>", lines.sweep)
      firstSweep(session)
    end
  end)
end

local function run()
  local session = hafen.session():current()
  if not session or not session:player():gob() then
    hafen.log():write("[fail] a character must be in the world -- got: no session on screen")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    return
  end
  -- the command is the client's: no addon can take the name
  refuses("hafen.console():on('store') is refused naming the command",
          function() hafen.console():on("store", function() end) end, "store")
  startupSweep(session, 0)
end

-- the only way in: a suite does not start itself. Deferred off the typed tree's monitor.
hafen.console():on("t159", function() hafen.timer():after(0, run) end)
