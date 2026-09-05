-- 132.3 -- a patch following an object standing still pays nothing. Self-checking suite.
--
-- The poll behind an anchored patch asks the cheap question first: the server's own point for the object,
-- which is a field, and only then the interpolated point, which walks the tile grid. So the pair this has
-- to prove fails in both directions. A gate that never opens leaves the ring behind a walking object, and
-- is read here as the distance between the ring and the object at its worst while the character crosses
-- two tiles. A gate that never closes is the cost the task removes, and is read as what N followers on an
-- object standing still add to the frame over the same N rings following nothing at all -- the same
-- ground, the same cuts, the same carve, and the poll the whole of the difference.
--
-- The character is walked by the suite rather than by the reader: `player.move` is the one order a
-- character takes, and a walk this addon issued is a walk it can watch frame by frame.

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

local function manualCheck(step, expect)
  manual = manual + 1
  say("[manual] " .. step .. " -- expect: " .. expect)
end

-- LuaJ's string.format ignores a precision, so a number is rounded by hand before it is printed.
local function n1(v)
  if not v then return "?" end
  return tostring(math.floor((v * 10) + 0.5) / 10)
end

local function n3(v)
  if not v then return "?" end
  return tostring(math.floor((v * 1000) + 0.5) / 1000)
end

local function kb(v)
  if not v then return "?" end
  return n1(v / 1024) .. "k"
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

-- fn(true) as soon as ready() answers, or fn(false) once n frames have gone by without it.
local function waitFor(n, ready, fn)
  local i, sub = 0, nil
  sub = hafen.event():on("Update", function()
    i = i + 1
    if ready() then sub:off(); fn(true)
    elseif i >= n then sub:off(); fn(false) end
  end)
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

-- Per-frame allocation over a window. allocPerFrame is the client's own estimate and is the one to use --
-- but the client only advances it while it is drawing its own memory line, and a counter standing still
-- would read as "flat" for the wrong reason. So it is taken only when it MOVED, and otherwise the same
-- quantity is read out of the heap the window consumed, frame by frame, with the collections dropped.
local function allocation(rows)
  local live = series(rows, "alloc")
  if (#live > 0) and (spread(live) > 0) then return live, "allocPerFrame" end
  local steps = {}
  for i = 2, #rows do
    local d = rows[i].heap - rows[i - 1].heap
    if d >= 0 then steps[#steps + 1] = d end       -- a negative step is a collection, not an allocation
  end
  return steps, "heapUsed"
end

-- The verdict is a difference against the counter's OWN noise: what a window did frame to frame while
-- nothing about the patches changed is the smallest difference this machine can tell apart.
local function flat(a, b)
  local ma, mb = mean(a), mean(b)
  if (ma == nil) or (mb == nil) then return nil end
  local noise = math.max(spread(a) or 0, spread(b) or 0)
  return (math.abs(ma - mb) <= noise), ma, mb, noise
end

-- ------------------------------------------------------------------ the ground this runs on

local RUN = 90          -- frames in a measured window
local SETTLE = 20       -- frames after a lay or a lift, so the one-off cut falls outside a window
local N = 40            -- followers in the cost window, and free rings in the control
local TILE = 11         -- world units
local STEP = 2 * TILE   -- how far the character is walked

local function square(centre, r)
  return {centre:offset(-r, -r), centre:offset(r, -r), centre:offset(r, r), centre:offset(-r, r)}
end

local function clear()
  for _, old in ipairs(hafen.virtual():patch():list()) do hafen.virtual():patch():remove(old) end
end

local function summary()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ------------------------------------------------------------------ the walk, and the ring on it

-- Order the character across the ground, and answer once it is actually going. The first destination may
-- be a wall, so three are tried before the walk is given up on.
local function walk(pl, me, from, fn)
  local tries = {from:offset(0, STEP), from:offset(0, -STEP), from:offset(STEP, 0)}
  local i = 0
  local function try()
    i = i + 1
    if i > #tries then fn(false); return end
    pl:move(tries[i])
    waitFor(90, function() return me:moving() end, function(started)
      if started then fn(true) else try() end
    end)
  end
  try()
end

-- Watch the ring against the object it follows for as long as the object is going, and answer with the
-- worst gap between the two. fn(lag) once the object has been still for ten frames.
local function tail(p, me, fn)
  local lag, still, frames, sub = 0, 0, 0, nil
  sub = hafen.event():on("Update", function()
    local a, b = p:position(), me:position()
    if a and b then
      local d = a:distance(b)
      if d > lag then lag = d end
    end
    frames = frames + 1
    if me:moving() then still = 0 else still = still + 1 end
    if (still >= 10) or (frames >= 900) then sub:off(); fn(lag) end
  end)
end

-- What the ring does over a window with nothing moving: how far it drifts from where it started, and how
-- far it ends up from the object. fn(drift, gap).
local function settled(p, me, fn)
  local first, last, drift, i, sub = p:position(), nil, 0, 0, nil
  sub = hafen.event():on("Update", function()
    last = p:position()
    if first and last then
      local d = first:distance(last)
      if d > drift then drift = d end
    end
    i = i + 1
    if i >= RUN then
      sub:off()
      local b = me:position()
      fn(drift, (last and b) and last:distance(b) or nil)
    end
  end)
end

-- ------------------------------------------------------------------ what a still follower costs

-- N rings following the standing character, then the same N rings following nothing, laid on the same
-- ground in the same place. Only the poll differs. fn(followers, free).
local function cost(me, fn)
  local at = me:position()
  clear()
  for _ = 1, N do
    hafen.virtual():patch():add(square(at, 3), me):tint({40, 120, 220, 50})
  end
  after(SETTLE, function()
    window(RUN, function(followers)
      clear()
      for _ = 1, N do
        hafen.virtual():patch():add(square(at, 3), at):tint({40, 120, 220, 50})
      end
      after(SETTLE, function()
        window(RUN, function(free) fn(followers, free) end)
      end)
    end)
  end)
end

-- ------------------------------------------------------------------ the run

local function run()
  local s = hafen.session():current()
  local pl = s and s:player()
  local me = pl and pl:gob()
  local here = me and me:position()
  if not here then
    say("[fail] this suite needs a character standing in the world -- got: no drawn session")
    return
  end
  clear()          -- an earlier run of this suite leaves nothing behind for this one to read

  local p = hafen.virtual():patch():add(square(here, 4), me)
    :tint({40, 200, 120, 90}):border({255, 255, 255}, 0.4)

  waitFor(120, function() return p:drawn() and (p:position() ~= nil) end, function(onGround)
    check(onGround, "the ring reaches the ground under the object it follows",
          "it never reached drawn ground inside 120 frames")
    if not onGround then clear(); summary(); return end

    local from, start = me:position(), p:position()
    walk(pl, me, from, function(started)
      tail(p, me, function(lag)
        local now, there = p:position(), me:position()
        local went = (there and from) and from:distance(there) or 0
        local ring = (now and start) and start:distance(now) or 0
        check(started and (went >= 5) and (ring >= (went - 1)),
              "the ring travels with the object it follows -- " .. n1(ring)
              .. " world units against the object's " .. n1(went),
              (not started) and "the character never left where it stood"
                             or ("the ring went " .. n1(ring) .. " of " .. n1(went)))
        check(started and (lag <= TILE),
              "and is never left behind on the way -- " .. n1(lag)
              .. " world units at its worst, inside the " .. TILE .. " of one tile",
              (not started) and "the character never walked" or (n1(lag) .. " behind"))

        settled(p, me, function(drift, gap)
          check(drift == 0, "it stops changing the moment the object stops -- not once over "
                            .. RUN .. " frames standing still", n1(drift) .. " world units of drift")
          check(gap and (gap <= 0.5), "and comes to rest under the object rather than short of it -- "
                                      .. n3(gap) .. " world units apart",
                (gap == nil) and "no place to compare" or (n3(gap) .. " apart"))

          cost(me, function(followers, free)
            local fa, how = allocation(followers)
            local ok, mf, mn, noise = flat(fa, allocation(free))
            check(ok, "a still follower costs the frame nothing over a ring that follows nothing, by "
                      .. how .. " -- " .. N .. " followers " .. kb(mf) .. ", the same rings free "
                      .. kb(mn) .. ", noise " .. kb(noise),
                  (ok == nil) and "no memory figure to compare"
                               or ("a difference of " .. kb(math.abs((mf or 0) - (mn or 0)))))

            clear()
            local at = me:position()
            hafen.virtual():patch():add(square(at, 4), me)
              :tint({40, 200, 120, 90}):border({255, 255, 255}, 0.4)
            manualCheck("walk your character across a slope or a ridge, with the green ring under it",
                        "the ring under its feet the whole way, with no lag and no jump when you stop")
            summary()
          end)
        end)
      end)
    end)
  end)
end

hafen.console():on("t132", run)   -- the only way in: a suite does not start itself
