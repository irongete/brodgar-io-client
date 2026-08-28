-- 117.1 — the attrib map is written under the gob's own monitor. Self-checking suite.
-- Type :t117 in the world and then STAND STILL, camera unmoved, for about 15 seconds. The suite
-- attaches a counting overlay to every object in view, records which of them paint, then drives
-- gob:scale and gob:overlay():add/:remove over those same objects from the step and from a Draw
-- handler at once -- and asks whether anything stopped being drawn.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

local COUNT, DRIVEN = "t117c", "t117d"
local SAMPLE, DRIVE, SETTLE = 2, 8, 1.5   -- seconds: paint sample, the load, then let it land
local SLICE = 8                           -- objects each driver writes per invocation
local FINAL = {1, 1.25, 1.5, 1.75}        -- exact in a float, so a read-back compares by value

local function head(t, n)
  local out = {}
  for i = 1, math.min(#t, n) do out[i] = t[i] end
  if #t > n then out[n + 1] = "(+" .. (#t - n) .. " more)" end
  return table.concat(out, "; ")
end

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t117 in the world")
    return report()
  end
  local gobs = s:world():gob():list()
  if #gobs == 0 then
    check(false, "the character can see at least one object", "none in view")
    return report()
  end

  hafen.log():write("[t117.1] stand still, camera unmoved, for about "
                    .. (SAMPLE + DRIVE + SETTLE + SAMPLE + 1) .. "s -- " .. #gobs .. " objects in view")

  -- ---- the counting overlay: one closure per object, so the draw pass costs one table increment ----
  local paint, before = {}, {}
  local seen, attached = #gobs, {}
  for i = 1, #gobs do
    local g, id = gobs[i], gobs[i]:id()
    local ok = pcall(function()
      g:overlay():add(COUNT):draw(function() paint[id] = true end)
    end)
    if ok then attached[#attached + 1] = g end
  end
  -- Everything below drives the objects the counting overlay actually reached: each already carries the
  -- overlay attrib, so a driven :add can no longer meet the "still resolving" refusal and a raise means
  -- what the run says it means.
  gobs = attached
  check(#gobs > 0, "a counting overlay attached to the objects in view (" .. #gobs
        .. " of " .. seen .. ")", "none attached")
  if #gobs == 0 then return report() end

  -- ---- the frame counter, and the second of the two drivers ----------------------------------------
  local frames, drawCalls, stepCalls = 0, 0, 0
  local driving, drawCursor, stepCursor, phase = false, 0, 0, 0
  local win = hafen.ui():window():title("117.1"):size(140, 18):position(12, 12)

  local raises, raiseCount = {}, 0
  local function try(fn)
    local ok, err = pcall(fn)
    if not ok then
      raiseCount = raiseCount + 1
      if #raises < 3 then
        raises[#raises + 1] = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")
      end
    end
  end

  -- One driver's turn: the first object every time -- so two drivers are guaranteed to be inside one
  -- gob's setattr at once -- and then a rotating slice, so the load is spread over the whole view.
  local function driveSlice(cursor)
    phase = phase + 1
    local n, ph = #gobs, phase
    local hot = gobs[1]
    if hot:exists() then
      try(function() hot:scale(FINAL[(ph % 4) + 1]) end)
      if (ph % 2) == 0 then
        try(function() hot:overlay():add(DRIVEN) end)
      else
        try(function() hot:overlay():remove(DRIVEN) end)
      end
    end
    for _ = 1, SLICE do
      cursor = (cursor % n) + 1
      local g = gobs[cursor]
      if g:exists() then
        try(function() g:scale(FINAL[((cursor + ph) % 4) + 1]) end)
        if ((cursor + ph) % 2) == 0 then
          try(function() g:overlay():add(DRIVEN) end)
        else
          try(function() g:overlay():remove(DRIVEN) end)
        end
      end
    end
    return cursor
  end

  win:on("Draw", function()
    frames = frames + 1
    if driving then
      drawCalls = drawCalls + 1
      drawCursor = driveSlice(drawCursor)
    end
  end)

  local stepDriver = hafen.timer():every(0.02, function()
    if driving then
      stepCalls = stepCalls + 1
      stepCursor = driveSlice(stepCursor)
    end
  end)

  local function finish()
    stepDriver:cancel()
    for i = 1, #gobs do
      local g = gobs[i]
      if g:exists() then
        pcall(function() g:overlay():remove(COUNT) end)
        pcall(function() g:overlay():remove(DRIVEN) end)
        pcall(function() g:scale(1) end)
      end
    end
    pcall(function() win:destroy() end)
    report()
  end

  -- ---- SAMPLE: which objects paint at all, before any of this ---------------------------------------
  hafen.timer():after(SAMPLE, function()
    for id in pairs(paint) do before[id] = true end
    local framesAtStart = frames
    driving = true

    -- ---- DRIVE: both drivers over the same objects, every call under pcall ---------------------------
    hafen.timer():after(DRIVE, function()
      driving = false
      local framesDuring = frames - framesAtStart

      -- The last write to each object, made from the step alone now that both drivers have stopped:
      -- that is what "reads back what was last written" can mean while two writers are still running.
      local wantScale, wantDriven = {}, {}
      for i = 1, #gobs do
        local g = gobs[i]
        if g:exists() then
          local k = FINAL[(i % 4) + 1]
          wantScale[i] = k
          try(function() g:scale(k) end)
          wantDriven[i] = ((i % 2) == 0)
          if wantDriven[i] then
            try(function() g:overlay():add(DRIVEN) end)
          else
            try(function() g:overlay():remove(DRIVEN) end)
          end
        end
      end

      hafen.timer():after(SETTLE, function()
        -- ---- every write landed ---------------------------------------------------------------------
        local badScale, badKeys, readBack = {}, {}, 0
        for i = 1, #gobs do
          local g = gobs[i]
          if (wantScale[i] ~= nil) and g:exists() then
            readBack = readBack + 1
            local got = g:scale()
            if got ~= wantScale[i] then
              badScale[#badScale + 1] = g:id() .. ": " .. tostring(got) .. " not " .. wantScale[i]
            end
            local hasCount, hasDriven = false, false
            for _, ov in ipairs(g:overlay():list()) do
              if not ov:native() then
                if ov:key() == COUNT then hasCount = true end
                if ov:key() == DRIVEN then hasDriven = true end
              end
            end
            if (not hasCount) or (hasDriven ~= wantDriven[i]) then
              badKeys[#badKeys + 1] = g:id() .. ": " .. COUNT .. "=" .. tostring(hasCount)
                                      .. " " .. DRIVEN .. "=" .. tostring(hasDriven)
                                      .. " wanted " .. tostring(wantDriven[i])
            end
          end
        end

        check((readBack > 0) and (#badScale == 0),
              "gob:scale() reads back the last size written, over " .. readBack .. " objects",
              (readBack == 0) and "no object stayed in view" or head(badScale, 3))
        check((readBack > 0) and (#badKeys == 0),
              "the overlay key list reads back the last state written, over " .. readBack .. " objects",
              (readBack == 0) and "no object stayed in view" or head(badKeys, 3))
        check(raiseCount == 0, "no driven call raised (" .. (stepCalls + drawCalls)
              .. " driver turns over " .. readBack .. " objects)",
              raiseCount .. " raised: " .. head(raises, 3))
        check((stepCalls > 0) and (drawCalls > 0),
              "both drivers ran against each other (" .. stepCalls .. " on the step, "
              .. drawCalls .. " in the draw pass)",
              "step=" .. stepCalls .. " draw=" .. drawCalls)
        check(framesDuring >= (DRIVE * 5),
              "the client went on drawing through the load (" .. framesDuring .. " frames in "
              .. DRIVE .. "s)", framesDuring .. " frames -- a stall under the new monitor")

        -- ---- and the symptom the feature is named after: is it still drawn? -------------------------
        for id in pairs(paint) do paint[id] = nil end
        hafen.timer():after(SAMPLE, function()
          local cur = hafen.session():current()
          local inview = {}
          if cur then
            for _, g in ipairs(cur:world():gob():list()) do inview[g:id()] = true end
          end
          local kept, lost = 0, {}
          for id in pairs(before) do
            if inview[id] then
              kept = kept + 1
              if not paint[id] then lost[#lost + 1] = tostring(id) end
            end
          end
          check((kept > 0) and (#lost == 0),
                "every object that painted before the load still paints after it (" .. kept
                .. " still in view)",
                (kept == 0) and "no object that painted stayed in view"
                or (#lost .. " stopped painting: " .. head(lost, 5)))
          finish()
        end)
      end)
    end)
  end)
end

-- The console line runs under the character's own tree monitor, and a window of our own lives in the
-- addon layer, which is a second one. Getting onto the step first is the door threading names for that.
local function run()
  pass, fail = 0, 0                       -- a second :t117 scores its own run, not both
  hafen.timer():after(0, start)
end

hafen.console():on("t117", run)   -- the only way in: a suite does not start itself
