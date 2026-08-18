-- 073.4 — the world indexes belong to one world. Self-checking suite.
--
-- VrApi's two standing-entity indexes and MapApi's marker refs are one session's now: a gob id and a
-- marker ref both name objects of one login, and each is reached through the session the seam was handed
-- -- the scene an entity stands in, the gob's own Glob, the map file a notify came from. With one session
-- live those indexes hold exactly what they held before, so nothing an addon can see changes -- which is
-- the claim, and every line below is that claim read back through the very paths that were rewired.

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

local RES = "gfx/terobjs/arch/logcabin"   -- the game's own prop, stood and taken away inside one run
local NAME = "073.4 probe"                -- the pin this run adds and removes
local WINDOW, STEP = 6.0, 0.25            -- how long the marker notify has to come back, and the poll

local sub                                 -- the MarkersChanged subscription, so a failed run can end it

-- The free-entity count the profiler reports (pull-only: it answers with profiling off). It is summed
-- over every session's own index, so it is exactly what this task moved.
local function placed()
  return hafen.client():profiling():entities().placed
end

-- This addon's own ghost standing at that gob, as gob:overlay() lists it read-only -- the anchored index,
-- read through the seam that is handed the Gob rather than a bare id.
local function anchoredKey(gob)
  for _, ov in ipairs(gob:overlay():list()) do
    if ov:kind() == "ghost" then
      return ov:key()
    end
  end
  return nil
end

-- The place a Position holds, as one comparable string: a grid id and the offset inside it, which is what
-- a free entity keeps and what its :position() has to hand back unchanged.
local function place(p)
  if p == nil then
    return "nil"
  end
  local i = p:info()
  return (i == nil) and "nowhere" or ("%s %.2f,%.2f"):format(i.gridId, i.x, i.y)
end

local function body()
  local gob = hafen.player():gob()
  local p = gob and gob:position()
  if (p == nil) or not p:durable() then
    check(false, "the player stands on ground this run can place things on",
          (p == nil) and "no position" or "not durable (unrecorded ground)")
    summary()
    return
  end

  -- THE FREE INDEX, end to end. The count before and after is the whole conversion in one line: a
  -- removal that reached a different index than its create would leave the entity counted forever.
  local before = placed()
  local gh = hafen.vr():ghost():add(RES, p)
  check(gh:exists() and (place(gh:position()) == place(p)),
        "a ghost stands at a Position, exists, and reads back the place it was given",
        place(gh:position()) .. " for " .. place(p))
  local during = placed()
  check(during == (before + 1), "...and this session's free index counts it", before .. " -> " .. during)
  hafen.vr():ghost():remove(gh)
  check((not gh:exists()) and (placed() == before),
        "ending it makes :exists() false and takes it out of the index it was put in",
        tostring(gh:exists()) .. ", " .. during .. " -> " .. placed())

  -- THE ANCHORED INDEX, which gob:overlay() is the only reader of: it is keyed by GOB ID, and both seams
  -- that read it are handed the gob itself.
  local an = hafen.vr():ghost():add(RES, gob)
  local key = anchoredKey(gob)
  check((key ~= nil) and (gob:overlay():get(key) ~= nil),
        "a ghost anchored to a gob is listed on it read-only and addressable by its key", tostring(key))
  hafen.vr():ghost():remove(an)
  check((key == nil) or ((gob:overlay():get(key) == nil) and (anchoredKey(gob) == nil)),
        "...and is gone from that gob once ended", tostring(anchoredKey(gob)))

  refuses("a place that is neither a Position nor a Gob is refused",
          function() hafen.vr():ghost():add(RES, "over there") end, "Position")

  -- THE MARKER REFS AND THE NOTIFY. A ref is minted per session against the map file that session's HUD
  -- holds, and the change notify now finds its session through that very file. Both edges run here, so
  -- the pin exists for the length of one call and the database is left exactly as it was found.
  local list = hafen.map():marker():list()
  check(type(list) == "table", "hafen.map():marker():list() is a list", type(list) .. " " .. #list)

  local seen = {}
  sub = hafen.event():on("MarkersChanged", function(n) seen[#seen + 1] = n end)

  local m = hafen.map():marker():add(NAME, p)
  check((m ~= nil) and m:exists() and (m:name() == NAME)
          and (hafen.map():marker():find(NAME) ~= nil)
          and (#hafen.map():marker():list() == (#list + 1)),
        "a pin is added, reads back as itself, and is found in the collection",
        (m == nil) and "nil" or (tostring(m:name()) .. ", " .. #list .. " -> "
                                 .. #hafen.map():marker():list()))
  if m ~= nil then
    hafen.map():marker():remove(m)
  end
  check((m ~= nil) and (not m:exists()) and (hafen.map():marker():find(NAME) == nil)
          and (#hafen.map():marker():list() == #list),
        "removing it takes it back out, and the database is what it was",
        #hafen.map():marker():list() .. " of " .. #list)

  manualCheck("open the map window, and look where you are stood",
              "no \"" .. NAME .. "\" pin, and no building standing on you")

  -- THE NOTIFY closes on a timer: it is marshalled onto the tick, so it cannot have arrived yet. Two
  -- bumps were made (the add and the remove) and each must come back carrying a COUNT.
  local waited = 0
  local function score()
    if (#seen < 2) and (waited < WINDOW) then
      waited = waited + STEP
      hafen.timer():after(STEP, score)
      return
    end
    sub:off()
    sub = nil
    check((#seen >= 2) and (type(seen[1]) == "number") and (type(seen[2]) == "number"),
          "MarkersChanged fired for the add and for the remove, each carrying the count",
          #seen .. " in " .. waited .. "s: " .. tostring(seen[1]) .. ", " .. tostring(seen[2]))
    summary()
  end
  hafen.timer():after(STEP, score)
end

-- One run, and it always ends in a verdict: a raise anywhere above would otherwise leave the maintainer
-- with half a log and a pin still in the database, so the failure is scored, the subscription ended and
-- the pin taken back out here.
local function run()
  pass, fail, manual = 0, 0, 0
  local ok, err = pcall(body)
  if not ok then
    if sub ~= nil then sub:off(); sub = nil end
    local left = hafen.map():marker():find(NAME)
    if left ~= nil then hafen.map():marker():remove(left) end
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    summary()
  end
end

hafen.slash():register("t073-4", run)   -- the only way in: a suite does not start itself
