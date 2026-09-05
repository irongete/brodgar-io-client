-- 132.2 -- a patch's filter and tags allocate nothing. Self-checking suite.
--
-- A patch's filter is the one thing that decides which of the ground's cuts are asked for at all, and
-- `overlayMeshes` counts exactly those: one per cut a mask was admitted to. So the filter's answer is a
-- number here rather than a picture. Each ring's raster walks the twenty-five cuts of the drawn ground and
-- must be admitted to the one or two its mask reaches -- a sense reversed reads as the twenty-odd it does
-- not, and a mask that claims the ground reads as all of them. That a ring is admitted at all is also
-- `tags()` still answering "show": nothing is drawn under a tag the map view is not counting.
--
-- Then the cost, which is measured over a window as a TOTAL rather than a per-frame estimate: the client
-- only advances its own allocation figure while it is drawing its memory line, and the heap it falls back
-- to swings by megabytes frame to frame while summing cleanly over a window. A patch laid on the drawn
-- ground is the only patch there is -- one over ground the scene has not built waits off it entirely and is
-- registered nowhere -- so what a patch costs the frame is read here as bytes per patch per frame, and what
-- is asserted of it is that it is a cost per patch and not a cost per patch per patch.

local pass, fail, manual = 0, 0, 0

local function say(line) hafen.log():write(line) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    say("[pass] " .. what)
  else
    fail = fail + 1
    say("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- LuaJ's string.format ignores a precision, so a number is rounded by hand before it is printed.
local function n1(v)
  if not v then return "?" end
  return tostring(math.floor((v * 10) + 0.5) / 10)
end

local function kb(v)
  if not v then return "?" end
  return n1(v / 1024) .. "k"
end

local function list(vals)
  local out = ""
  for i, v in ipairs(vals) do out = out .. ((i == 1) and "" or ",") .. tostring(v) end
  return out
end

-- ------------------------------------------------------------------ frames and counters

-- fn() after n drawn frames.
local function after(n, fn)
  local i, sub = 0, nil
  sub = hafen.event():on("Update", function()
    i = i + 1
    if i >= n then sub:off(); fn() end
  end)
end

-- Cut overlay meshes laid since the client started: one per cut a mask was admitted to. Pull-only, so it
-- answers with profiling disarmed and this suite arms nothing.
local function meshes()
  return hafen.client():profiling():render().overlayMeshes
end

-- fn(rows) once n frames have each been sampled.
local function window(n, fn)
  local rows, sub = {}, nil
  sub = hafen.event():on("Update", function()
    local m = hafen.client():profiling():memory()
    rows[#rows + 1] = {alloc = m.allocPerFrame, heap = m.heapUsed}
    if #rows >= n then sub:off(); fn(rows) end
  end)
end

local function mean(vals)
  if #vals == 0 then return nil end
  local sum = 0
  for _, v in ipairs(vals) do sum = sum + v end
  return sum / #vals
end

local function spread(vals)
  if #vals == 0 then return nil end
  local lo, hi = vals[1], vals[1]
  for _, v in ipairs(vals) do
    if v < lo then lo = v end
    if v > hi then hi = v end
  end
  return hi - lo
end

local function series(rows, key)
  local out = {}
  for _, r in ipairs(rows) do
    if r[key] then out[#out + 1] = r[key] end
  end
  return out
end

-- What a window ALLOCATED, as one number. allocPerFrame is the client's own estimate and is the one to use
-- -- but the client only advances it while it is drawing its own memory line, so it is taken only when it
-- MOVED, and otherwise the same quantity is read out of the heap the window consumed, frame by frame, with
-- the collections dropped. Summed rather than averaged: a single frame's heap step swings by megabytes and
-- says nothing, while the same steps over a hundred frames are the bytes that actually went.
local function total(rows)
  local live = series(rows, "alloc")
  if (#live > 0) and (spread(live) > 0) then return mean(live) * #rows, "allocPerFrame" end
  local sum = 0
  for i = 2, #rows do
    local d = rows[i].heap - rows[i - 1].heap
    if d >= 0 then sum = sum + d end             -- a negative step is a collection, not an allocation
  end
  return sum, "heapUsed"
end

-- ------------------------------------------------------------------ the ground this runs on

local RUN = 120         -- frames in a measured window
local SETTLE = 30       -- frames after a lay or a lift, so the cut it costs falls inside the reading

-- The most cuts one of these rings can reach. The mask is the ring's bounding box floored to tiles with a
-- tile of margin each way, so a ring this size spans three or four tiles; a cut is 25 of them, so the mask
-- crosses at most one cut boundary per axis.
local CUTS = 4
-- ...against the cuts of the drawn ground, which is five of them across and is what each ring's own raster
-- walks, per overlay, per frame, putting the filter to every one.
local WALK = 25

local N = 40            -- rings down for the cost reading, and that many again for the second one

local function square(c, r)
  return {c:offset(-r, -r), c:offset(r, -r), c:offset(r, r), c:offset(-r, r)}
end

local function clear()
  for _, old in ipairs(hafen.virtual():patch():list()) do hafen.virtual():patch():remove(old) end
end

local function summary()
  clear()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- N rings laid close in around the character, where the ground is built and drawn: a patch over ground the
-- scene has not made is registered nowhere and costs nothing, so a cost reading has to stand on this ground.
local function lay(here, n, seed)
  for i = 1, n do
    local c = here:offset((((i + seed) % 9) - 4) * 13, ((math.floor(i / 9) % 7) - 3) * 13)
    hafen.virtual():patch():add(square(c, 4), c):tint({40, 200, 120, 60})
  end
end

-- ------------------------------------------------------------------ what the filter admits

-- Lay each spot in turn and read off what each one cost the ground. fn(deltas, drawn) once the last is in.
local function eachSpot(spots, fn)
  local deltas, drawn, i = {}, {}, 0
  local function step()
    i = i + 1
    if i > #spots then fn(deltas, drawn); return end
    local before = meshes()
    local c = spots[i]
    local p = hafen.virtual():patch():add(square(c, 4), c):tint({40, 200, 120, 90})
    after(SETTLE, function()
      deltas[#deltas + 1] = meshes() - before
      drawn[#drawn + 1] = p:drawn()
      step()
    end)
  end
  step()
end

local function admitted(here, fn)
  -- What the ground lays while nothing of ours is on it: the drift every figure below is read against.
  local m0 = meshes()
  after(SETTLE, function()
    local quiet = meshes() - m0
    eachSpot({here, here:offset(30, 0), here:offset(0, 30)}, function(deltas, drawn)
      fn(quiet, deltas, drawn)
    end)
  end)
end

-- ------------------------------------------------------------------ what those masks cost the frame

-- Three windows: nothing down, N down, and N more. fn(none, one, two, laidCuts).
local function cost(here, fn)
  window(RUN, function(none)
    local before = meshes()
    lay(here, N, 0)
    after(SETTLE, function()
      local laidCuts = meshes() - before
      window(RUN, function(one)
        lay(here, N, 4)
        after(SETTLE, function()
          window(RUN, function(two) fn(none, one, two, laidCuts) end)
        end)
      end)
    end)
  end)
end

-- ------------------------------------------------------------------ the run

local function run()
  local s = hafen.session():current()
  local me = s and s:player() and s:player():gob()
  local here = me and me:position()
  if not here then
    say("[fail] this suite needs a character standing in the world -- got: no drawn session")
    return
  end
  clear()          -- an earlier run of this suite leaves nothing behind for this one to read

  admitted(here, function(quiet, deltas, drawn)
    local ok = true
    for _, d in ipairs(deltas) do
      if ((d - quiet) < 1) or (d > (CUTS + quiet)) then ok = false end
    end
    check(ok, "a ring is admitted to the cuts its mask reaches and to none of the rest -- " .. list(deltas)
              .. " of the " .. WALK .. " its raster walks, at most " .. CUTS .. ", drift " .. quiet,
          "cuts of " .. list(deltas))
    local all = true
    for _, d in ipairs(drawn) do if not d then all = false end end
    check(all, "and every one is on the ground, under the tag a patch answers with",
          "one never reached the ground: " .. list(drawn))

    clear()
    after(SETTLE, function()
      cost(here, function(none, one, two, laidCuts)
        check((laidCuts >= N) and (laidCuts <= (N * CUTS)),
              N .. " rings down are admitted to cuts in proportion to the rings, not to the grid -- "
              .. laidCuts .. " for " .. N .. " rings, between " .. N .. " and " .. (N * CUTS),
              laidCuts .. " cuts")
        -- What one patch costs the frame, twice over: the first N against an empty ground, the second N
        -- against the first. A cost that is per patch reads the same both times; one that is per patch per
        -- patch -- a filter walked rather than looked up, a mask asked once per mark on the ground --
        -- doubles when the count does.
        local t0, how = total(none)
        local t1 = total(one)
        local t2 = total(two)
        local per1 = (t1 - t0) / (N * RUN)
        local per2 = (t2 - t1) / (N * RUN)
        check(per2 <= math.max(per1 * 1.5, per1 + 1024),
              "a patch costs the frame the same whether it is one of " .. N .. " or one of " .. (N * 2)
              .. ", by " .. how .. " -- " .. kb(per1) .. " and " .. kb(per2) .. " per patch per frame",
              kb(per2) .. " against " .. kb(per1))
        summary()
      end)
    end)
  end)
end

hafen.console():on("t132", run)   -- the only way in: a suite does not start itself
