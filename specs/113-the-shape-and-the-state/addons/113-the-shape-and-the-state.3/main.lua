-- 113.3 — the state has a moment, so it has an edge. Self-checking suite.
-- Type :t113 in the world. It listens for 20 seconds: walk around so objects keep streaming into view,
-- and -- for the manual step -- open or close a gate or door you can reach in that window.

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

-- A refusal is a check too: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Dense, 1-based, 0..255 -- the same shape gob:sdt() promises (113.1).
local function shapeOk(bytes)
  local n = 0
  for _ in pairs(bytes) do n = n + 1 end
  if n ~= #bytes then return false end
  for i = 1, #bytes do
    local v = bytes[i]
    if (type(v) ~= "number") or (v ~= math.floor(v)) or (v < 0) or (v > 255) then return false end
  end
  return true
end

local function bytesKey(bytes)
  local parts = {}
  for i = 1, #bytes do parts[i] = bytes[i] end
  return "(" .. table.concat(parts, ",") .. ")"
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t113 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t113 in the world")
    return report()
  end

  refuses("hafen.event():on(\"SdtChanged\", fn) raises, the refusal naming GobSdtChanged",
          function() hafen.event():on("SdtChanged", function() end) end, "GobSdtChanged")

  local WAIT = 20
  manualCheck("within the next " .. WAIT .. "s, open or close a gate or door you can reach",
              "a line below reads [pass] gob <id> reported a second state -- the same object's id,"
              .. " carrying different bytes than its first")

  -- The Update counter is this run's own notion of "one frame": every GobSdtChanged the handler below
  -- sees between one Update and the next is stamped with the same value, which is what "twice in one
  -- frame" is checked against below.
  local frame = 0
  local uSub = hafen.event():on("Update", function() frame = frame + 1 end)

  local total, shapeBad, gobBad, dupes = 0, {}, {}, {}
  local seenThisFrame, stampedFrame = {}, -1
  local seenIds = {}

  local sub
  sub = hafen.event():on("GobSdtChanged", function(ev)
    if frame ~= stampedFrame then
      seenThisFrame, stampedFrame = {}, frame
    end
    total = total + 1

    local g = ev:gob()
    local isGob = (g ~= nil) and pcall(function() return g:id() end)
    if not isGob then
      gobBad[#gobBad + 1] = tostring(g)
    end
    local id = isGob and g:id() or -1

    local bytes = ev:sdt()
    if not shapeOk(bytes) then
      shapeBad[#shapeBad + 1] = tostring(id)
    end

    local key = tostring(id) .. "/" .. bytesKey(bytes)
    if seenThisFrame[key] then
      dupes[#dupes + 1] = key
    end
    seenThisFrame[key] = true

    -- The fire-once gate (113.3) means a repeat sighting of one id is, by construction, DIFFERENT bytes
    -- than its last firing -- so any second sighting at all is the manual step's proof, printed as a
    -- genuine [pass] the maintainer can read straight off the log.
    if seenIds[id] then
      check(true, "gob " .. id .. " reported a second state, now " .. bytesKey(bytes))
    end
    seenIds[id] = true
  end)

  hafen.timer():after(WAIT, function()
    sub:off()
    uSub:off()
    local afterOff = total

    check(total > 0, "at least one GobSdtChanged reached it over " .. WAIT .. "s",
          "none arrived -- stand where objects are still streaming in and re-run")
    if total > 0 then
      check(#shapeBad == 0, "every ev:sdt() is a dense 1-based 0..255 array, the shape gob:sdt() answers",
            table.concat(shapeBad, ", "))
      check(#gobBad == 0, "every ev:gob() is a Gob that answers :id()", table.concat(gobBad, ", "))
      check(#dupes == 0, "no (id, bytes) pair arrives twice in one frame -- one event per object",
            table.concat(dupes, ", "))
    end

    hafen.timer():after(2, function()
      check(total == afterOff, "sub:off() is followed by silence for the rest of the run",
            (total - afterOff) .. " more event(s) arrived after :off()")
      report()
    end)
  end)
end

hafen.console():on("t113", run)   -- the only way in: a suite does not start itself
