-- 114.1 — GobAdded before the first drawn frame. Self-checking suite.
-- Type :t114 in the world, then WALK for 20 seconds into ground you have not seen this session, so
-- objects keep streaming in. Every arriving object is drawn at a thousandth of its size while the
-- window is open, and restored when it closes -- that is the manual step.

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

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function count(t)
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  return n
end

local WAIT, SETTLE = 20, 2
local GHOST, GHOST_MAX, GHOST_STEP = "gfx/terobjs/arch/logcabin", 8, 0.5

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t114 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t114 in the world")
    return report()
  end

  manualCheck("walk into ground you have not seen this session for the next " .. WAIT .. "s",
              "no object appears at full size, not even for one frame -- each is a speck the instant it"
              .. " shows; the world draws no slower, and everything is back to size when the run ends")

  -- Everything that arrives while the window is open, announced by GobAdded and by nothing else.
  local announced, removed, dupes = {}, {}, {}
  local arrivals = 0
  local named, stated, boxed = 0, 0, 0
  local shrunk, olTries, olErr = {}, 0, nil

  local heldBefore = hafen.client():profiling():render().gobsHeld

  local addSub, remSub
  addSub = hafen.event():on("GobAdded", function(gob)
    arrivals = arrivals + 1
    local id = gob:id()
    if announced[id] and not removed[id] then
      dupes[#dupes + 1] = id                 -- announced twice with no departure between
    end
    announced[id], removed[id] = true, nil

    -- READS AT HANDLER TIME (criterion 3): the hold must cost no data. A composite still loading
    -- answers nil for all three and is not counted -- the claim is about a resource-drawn object.
    local name = gob:name()
    if name then
      named = named + 1
      if type(gob:sdt()) == "table" then stated = stated + 1 end
      local hb = gob:hitbox()
      if (type(hb) == "table") and (#hb > 0) then boxed = boxed + 1 end
    end

    -- A WRITE MADE IN THE HANDLER (criterion 4): the object holds no slots yet, so the attach cannot
    -- meet the renderability refusal at all. Tried on the first few arrivals, and taken off again.
    if (olTries < 5) and (olErr == nil) then
      olTries = olTries + 1
      local ok, err = pcall(function() gob:overlay():add("t114") end)
      if ok then
        pcall(function() gob:overlay():remove("t114") end)
      else
        olErr = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")
      end
    end

    -- ...and the manual step's own write: a speck, restored when the window closes.
    if pcall(function() gob:scale(0.001) end) then shrunk[#shrunk + 1] = gob end
  end)
  remSub = hafen.event():on("GobRemoved", function(gob) removed[gob:id()] = true end)

  -- Taken AFTER subscribing: an object that slips in between the two is in both sets, and the
  -- reconcile below only ever asks whether something NEW was announced.
  local before = {}
  for _, g in ipairs(s:world():gob():list()) do before[g:id()] = true end

  -- CRITERION 7: an object that never enters an OCache is never held. A ghost is a client-only gob the
  -- layer never queues, so nothing arms the gate on it and it must reach the scene on its own.
  --
  -- Read WHILE YOU ARE STILL STANDING NEXT TO IT. A ghost holds a durable place and the client draws the
  -- ground only about 550 world units around the character, so one asked about at the end of the walk
  -- answers drawn=false for a reason that has nothing to do with the gate: its ground is no longer drawn.
  -- The gate's answer arrives within a beat of the visual streaming in, so the poll below is the whole wait.
  local ghost, ghostErr, ghostDrawn, ghostWaited = nil, nil, false, 0
  local p = s:player():gob() and s:player():gob():position()
  if p then
    local ok, e = pcall(function() return hafen.vr():ghost():add(GHOST, p) end)
    if ok then ghost = e else ghostErr = "hafen.vr():ghost():add raised: " .. tostring(e) end
  else
    ghostErr = "the character has no place yet"
  end

  local function pollGhost(left)
    local info = ghost and ghost:info()
    if info and (info.drawn == true) then ghostDrawn = true end
    if ghostDrawn or (left <= 0) or (ghost == nil) then
      if not ghostDrawn then
        ghostErr = ghostErr or ("drawn=" .. tostring(info and info.drawn) .. " after " .. ghostWaited
                                .. "s, exists=" .. tostring(ghost and ghost:exists()))
      end
      if ghost then pcall(function() hafen.vr():ghost():remove(ghost) end) end
      ghost = nil
    else
      ghostWaited = ghostWaited + GHOST_STEP
      hafen.timer():after(GHOST_STEP, function() pollGhost(left - GHOST_STEP) end)
    end
  end
  pollGhost(GHOST_MAX)

  hafen.timer():after(WAIT, function()
    -- The final picture is taken here and reconciled SETTLE seconds later: an object is in its OCache
    -- from the instant it arrives, so one taken and judged in the same breath would be scored missing
    -- while its own GobAdded was still queued for the next step.
    local cur = hafen.session():current()
    local drawn = {}
    if cur then
      for _, g in ipairs(cur:world():gob():list()) do drawn[g:id()] = true end
    end

    for i = 1, #shrunk do pcall(function() shrunk[i]:scale(1) end) end   -- the manual step, undone

    hafen.timer():after(SETTLE, function()
      addSub:off()
      remSub:off()

      local heldAfter = hafen.client():profiling():render().gobsHeld
      local missed = {}
      for id in pairs(drawn) do
        if (not before[id]) and (not announced[id]) then missed[#missed + 1] = id end
      end

      check(arrivals > 0, "at least one GobAdded arrived over " .. WAIT .. "s",
            "none -- walk into unseen ground and re-run")
      if arrivals > 0 then
        check(#missed == 0, "every object now in the world was announced by GobAdded first ("
              .. count(drawn) .. " drawn, " .. arrivals .. " announced)",
              #missed .. " drawn but never announced: " .. table.concat(missed, ", "))
        check(#dupes == 0, "no object was announced twice without leaving in between",
              table.concat(dupes, ", "))
        check((heldAfter or 0) > (heldBefore or 0),
              "render().gobsHeld climbed -- the render add was actually parked ("
              .. tostring(heldBefore) .. " -> " .. tostring(heldAfter) .. ")",
              "it did not move: " .. tostring(heldBefore) .. " -> " .. tostring(heldAfter))
        check(named > 0, "gob:name() answers inside the handler (" .. named .. " of " .. arrivals .. ")",
              "every arrival answered nil")
        check(stated > 0, "gob:sdt() answers there too, for a resource-drawn object (" .. stated .. ")",
              "no named arrival carried state bytes")
        check(boxed > 0, "gob:hitbox() answers there too (" .. boxed .. ")",
              "no named arrival stated a shape")
        check(olErr == nil, "gob:overlay():add(key) from the handler does not meet the renderability"
              .. " refusal (" .. olTries .. " tried)", olErr)
      end

      check(ghostDrawn, "a ghost -- a client-only gob the layer never queues -- was drawn within "
            .. ghostWaited .. "s of being stood, so it was never held", ghostErr or "it never drew")

      report()
    end)
  end)
end

hafen.console():on("t114", run)   -- the only way in: a suite does not start itself
