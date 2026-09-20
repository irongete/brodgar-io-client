-- 159.1 — the SQLite store and the switch. Self-checking suite.
-- Run in sqlite mode (-Dhaven.store=sqlite), after a second login at a place walked away from.

local LOADED_MS = os.time() * 1000   -- a grid recorded before this is not this run's memory

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

-- Say :store at the session and read its lines back from the System log, polled up to 2 s. fn gets
-- { store = "sqlite", map = "<path> — <n> entries, <x> MB", res = ..., sweep = ... } or nil.
local function readStore(session, fn)
  local channel = syslog(session)
  local before = channel and channel:message():count() or 0
  session:console():run("store")
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
    if found.store or waited >= 2 then
      poll:cancel()
      fn(found.store and found or nil)
    end
  end)
end

local function entries(line)
  return tonumber((line or ""):match("(%d+) entries")) or 0
end

-- A grid recorded by an EARLIER process: not streamed in, modified before this file loaded, and answering
-- its tiles and heights. Searched on a ring of radius 2..12 around the character's own grid.
local function oldGrid(session)
  local gob = session:player():gob()
  local info = gob and gob:position() and gob:position():info()
  if not info then return nil, "no position yet" end
  local here = hafen.map():grid():get(info.gridId)
  local segment, centre = here and here:segment(), here and here:segmentCoord()
  if not (segment and centre) then return nil, "own grid not recorded yet" end
  for radius = 2, 12 do
    for dx = -radius, radius do
      for dy = -radius, radius do
        if math.max(math.abs(dx), math.abs(dy)) == radius then
          local grid = segment:grid():get({ x = centre.x + dx, y = centre.y + dy })
          if grid and not grid:live() then
            local modified = grid:modified()
            if modified and modified < LOADED_MS and grid:tile({ x = 0, y = 0 }) and grid:height({ x = 0, y = 0 }) then
              return grid
            end
          end
        end
      end
    end
  end
  return nil, "no recorded, not-live grid on the ring yet"
end

local function finish()
  manualCheck("launch without the property and type :store", "store: files, and the %APPDATA%\\Haven and Hearth\\data path")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- 4. a grid an earlier run recorded reads back from map.sqlite, polled up to 20 s
local function gridStep(session)
  local waited, poll = 0, nil
  poll = hafen.timer():every(1, function()
    waited = waited + 1
    local grid, why = oldGrid(session)
    if grid then
      poll:cancel()
      check(true, "a grid recorded by an earlier run reads back from map.sqlite (" .. grid:id() .. ")")
      finish()
    elseif waited >= 20 then
      poll:cancel()
      manualCheck("walk a few grids, relog in sqlite mode and run :t159 again", "[pass] a grid recorded by an earlier run (" .. tostring(why) .. ")")
      finish()
    end
  end)
end

-- 3. the map writes through the store: :store's map line reports >= 1 entry within 30 s
local function mapStep(session)
  local waited, poll = 0, nil
  poll = hafen.timer():every(3, function()
    waited = waited + 3
    readStore(session, function(lines)
      local n = lines and entries(lines.map) or 0
      if n >= 1 or waited >= 30 then
        poll:cancel()
        check(n >= 1, "the map writes through the store (map.sqlite holds entries)",
              lines and (lines.map or ("no map: line, store: " .. tostring(lines.store))) or "<no :store output>")
        gridStep(session)
      end
    end)
  end)
end

local function run()
  local session = hafen.session():current()
  if not session or not session:player():gob() then
    hafen.log():write("[fail] a character must be in the world -- got: no session on screen")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    return
  end
  -- 1. :store reaches the System log and names the store in force
  readStore(session, function(lines)
    check(lines and lines.store == "sqlite", "the store in force is sqlite (:store reached the System log)",
          lines and lines.store or "<no :store output within 2 s>")
    -- 2. the two files are under savedata/
    local map, res = lines and lines.map or "", lines and lines.res or ""
    check(map:find("savedata", 1, true) and map:find("map.sqlite", 1, true)
            and res:find("savedata", 1, true) and res:find("rescache.sqlite", 1, true),
          "map: and res: name savedata/map.sqlite and savedata/rescache.sqlite", map .. " | " .. res)
    -- the command is the client's: no addon can take the name
    refuses("hafen.console():on('store') is refused naming the command",
            function() hafen.console():on("store", function() end) end, "store")
    if not (lines and lines.store == "sqlite") then
      -- the rest proves map.sqlite, and there is none: two fails, not two claims about another store
      check(false, "the map writes through the store (map.sqlite holds entries)", "store is not sqlite")
      check(false, "a grid recorded by an earlier run reads back from map.sqlite", "store is not sqlite")
      finish()
    elseif entries(lines.map) >= 1 then
      check(true, "the map writes through the store (map.sqlite holds entries)")
      gridStep(session)
    else
      mapStep(session)
    end
  end)
end

-- the only way in: a suite does not start itself. Deferred off the typed tree's monitor.
hafen.console():on("t159", function() hafen.timer():after(0, run) end)
