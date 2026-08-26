-- 113.1 — the state the server sent, as the bytes it sent. Self-checking suite.
-- Type :t113 in the world, standing where at least one resource-drawn object is in view.

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

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Dense, 1-based and every element inside 0..255 -- the whole shape gob:sdt() promises.
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

-- gob:info().sdt is the same array gob:sdt() answers, element for element.
local function infoMatches(g, bytes)
  local info = g:info()
  if info.sdt == nil or (#info.sdt ~= #bytes) then return false end
  for i = 1, #bytes do
    if info.sdt[i] ~= bytes[i] then return false end
  end
  return true
end

-- Encode and parse it back through hafen.json, and compare element for element.
local function jsonRoundTrips(bytes)
  local ok, enc = pcall(function() return hafen.json():encode(bytes) end)
  if not ok then return false end
  local rt = hafen.json():parse(enc)
  if #rt ~= #bytes then return false end
  for i = 1, #bytes do
    if rt[i] ~= bytes[i] then return false end
  end
  return true
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t113 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t113 in the world")
    return report()
  end

  -- The gone case, without waiting for a despawn: an id nothing has ever loaded.
  local ghost = s:world():gob():get(1)
  check((ghost:sdt() == nil) and (not ghost:exists()),
        "an id nothing has loaded answers nil from :sdt(), and :exists() is false",
        "sdt=" .. tostring(ghost:sdt()) .. " exists=" .. tostring(ghost:exists()))

  -- The composed case: a player's own body is drawn, not resource-drawn.
  local me = s:player():gob()
  check((me == nil) or (me:sdt() == nil),
        "the player's own body answers nil -- composed, not resource-drawn",
        me and tostring(me:sdt()))

  -- Arity: the verb takes no argument, and the refusal names the rule it broke.
  refuses("gob:sdt(1) raises, naming arity", function() ghost:sdt(1) end, "arity")

  -- Everything else needs a resource-drawn gob the server actually sent -- retried over a
  -- bounded window and scored over what the run reached, per a receiver only the server gives.
  local WAIT, TRIES, tries = 0.5, 10, 0

  local function attempt()
    tries = tries + 1
    local found, shapeBad, infoBad, jsonBad = nil, {}, {}, {}
    for _, g in ipairs(s:world():gob():list()) do
      local bytes = g:sdt()
      if bytes then
        found = found or g
        if not shapeOk(bytes) then shapeBad[#shapeBad + 1] = g:id() end
        if not infoMatches(g, bytes) then infoBad[#infoBad + 1] = g:id() end
        if not jsonRoundTrips(bytes) then jsonBad[#jsonBad + 1] = g:id() end
      end
    end

    if found or (tries >= TRIES) then
      check(found ~= nil, "at least one visible gob answers a state array",
            "none of the visible gobs answered after " .. tries .. " tries")
      if found then
        check(#shapeBad == 0, "every answering array is dense, 1-based and inside 0..255",
              table.concat(shapeBad, ", "))
        check(#infoBad == 0, "gob:info().sdt matches gob:sdt() element for element",
              table.concat(infoBad, ", "))
        check(#jsonBad == 0, "gob:sdt() survives a hafen.json round trip",
              table.concat(jsonBad, ", "))
      end
      report()
    else
      hafen.timer():after(WAIT, attempt)
    end
  end
  attempt()
end

hafen.console():on("t113", run)   -- the only way in: a suite does not start itself
