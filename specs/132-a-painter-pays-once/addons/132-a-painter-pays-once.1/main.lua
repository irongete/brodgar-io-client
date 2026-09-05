-- 132.1 -- a patch the world cannot hide. Self-checking suite.
--
-- It lays a ring, flips patch:occluded(b) on it, and asserts that everything else about the patch came
-- through the flip: a flag that re-carved rather than re-pushed loses one of them. Then it brackets three
-- bounded stretches of frames -- the ring absent, the ring shown the ordinary way, and the same ring shown
-- through the world -- and asserts the last two cost the same in draw calls and in allocation, since they
-- differ by one op and nothing else. What a patch costs the GROUND is measured off the first pair and is
-- bounded by the cuts its mask reaches: one draw call each, and that is a number of the ground rather than
-- of the ring, which is the whole of what "pays once" means.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why. LuaJ writes a bridge refusal as
-- "@chunk.lua:189 msg" -- a space and no colon -- so the strip has to allow that shape too.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
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

-- One frame's cost, straight off the profiler.
local function sample()
  local p = hafen.client():profiling()
  local g, m = p:gl(), p:memory()
  return {draws = g.drawCalls, alloc = m.allocPerFrame, heap = m.heapUsed}
end

-- fn(rows) once n frames have each been sampled.
local function window(n, fn)
  local rows, sub = {}, nil
  sub = hafen.event():on("Update", function()
    rows[#rows + 1] = sample()
    if #rows >= n then sub:off(); fn(rows) end
  end)
end

local function mean(list)
  if #list == 0 then return nil end
  local sum = 0
  for _, v in ipairs(list) do sum = sum + v end
  return sum / #list
end

local function spread(list)
  if #list == 0 then return nil end
  local lo, hi = list[1], list[1]
  for _, v in ipairs(list) do
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
-- nothing about the patch changed is the smallest difference this machine can tell apart.
local function flat(a, b)
  local ma, mb = mean(a), mean(b)
  if (ma == nil) or (mb == nil) then return nil end
  local noise = math.max(spread(a) or 0, spread(b) or 0)
  return (math.abs(ma - mb) <= noise), ma, mb, noise
end

-- ------------------------------------------------------------------ the run

local RUN = 60          -- frames in a measured window
local SETTLE = 20       -- frames after a lay or a lift, so the one-off cut falls outside a window

-- The most cuts one of these rings can reach. The mask is the ring's bounding box floored to tiles with a
-- tile of margin each way, so a ring this size spans three or four tiles; a cut is 25 of them, so the mask
-- crosses at most one cut boundary per axis. That is the ceiling a patch's OWN cost is bounded by, and it
-- is the ground's number rather than the ring's: a painter's would be one per edge.
local CUTS = 4

local function square(centre, r)
  return {centre:offset(-r, -r), centre:offset(r, -r), centre:offset(r, r), centre:offset(-r, r)}
end

local function summary(disarm)
  if disarm then hafen.client():options():client():profiling(false) end
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- One measured window per stage, in the order given: the stage's own change is made, the scene is left to
-- settle so a one-off cut falls outside, and RUN frames are then sampled. fn(byName) once the last is in.
local function stages(list, fn)
  local out, i = {}, 0
  local function step()
    i = i + 1
    if i > #list then fn(out); return end
    list[i][2]()
    after(SETTLE, function()
      window(RUN, function(rows) out[list[i][1]] = rows; step() end)
    end)
  end
  step()
end

local function measure(through, wasArmed)
  stages({
    {"absent", function() through:visible(false) end},
    {"plain",  function() through:occluded(true):visible(true) end},   -- shown, the way the world hides it
    {"world",  function() through:occluded(false) end},                -- the same ring, ONE op different
  }, function(w)
    -- What this task ships is that op, so what it has to cost is nothing: the ring, the mask, the cuts and
    -- the carve are the same on both sides of this pair, and only the depth test differs.
    local ok, mw, mp, noise = flat(series(w.world, "draws"), series(w.plain, "draws"))
    check(ok, "showing a ring through the world costs no draw call over showing it the ordinary way"
              .. " -- through " .. n1(mw) .. ", ordinary " .. n1(mp) .. ", noise " .. n1(noise),
          (ok == nil) and "the armed profiler answered no draw calls"
                       or ("a difference of " .. n1(math.abs((mw or 0) - (mp or 0)))))
    local wa, how = allocation(w.world)
    local ok2, mw2, mp2, noise2 = flat(wa, allocation(w.plain))
    check(ok2, "and no allocation per frame, by " .. how .. " -- through " .. kb(mw2)
               .. ", ordinary " .. kb(mp2) .. ", noise " .. kb(noise2),
          (ok2 == nil) and "no memory figure to compare"
                        or ("a difference of " .. kb(math.abs((mw2 or 0) - (mp2 or 0)))))
    -- And what a patch costs at all is the GROUND under it: the engine lays a second mesh over each cut the
    -- mask reaches and each of those is one draw call. It is a constant of the ground, not of the ring --
    -- a shape with eight times the points covers the same cuts and costs the same.
    local shown, gone = series(w.plain, "draws"), series(w.absent, "draws")
    local ms, mg = mean(shown), mean(gone)
    local gnoise = math.max(spread(shown) or 0, spread(gone) or 0)
    local cost = (ms and mg) and (ms - mg) or nil
    check(cost and (cost <= (CUTS + gnoise)),
          "a shown ring costs the cuts under it and no more -- " .. n1(cost) .. " draw calls against the "
          .. CUTS .. " its mask can reach, noise " .. n1(gnoise),
          (cost == nil) and "no draw-call figure to compare" or ("a cost of " .. n1(cost)))
    manualCheck("walk until a wall, a house or a hill stands between you and the two rings laid"
                .. " south of where you ran this",
                "the ORANGE ring drawn whole through it, the BLUE one cut where the wall covers it")
    summary(not wasArmed)
  end)
end

local function run()
  local s = hafen.session():current()
  local me = s and s:player() and s:player():gob()
  local here = me and me:position()
  if not here then
    say("[fail] this suite needs a character standing in the world -- got: no drawn session")
    return
  end

  -- Clear what an earlier run of this suite left standing, so a re-run starts on empty ground.
  for _, old in ipairs(hafen.virtual():patch():list()) do hafen.virtual():patch():remove(old) end

  local c = hafen.client():options():client()
  local wasArmed = c:profiling()
  if not wasArmed then c:profiling(true) end       -- gl() answers nothing while the profiler is disarmed

  -- The patch every property check runs on: anchored to the character, so it has an offset to keep.
  local p = hafen.virtual():patch():add(square(here, 4), me)
    :tint({40, 200, 120, 90}):border({255, 255, 255}, 0.5):offset(1, 2)
  -- The pair the eye reads, planted side by side on the ground south of the character.
  local blue = here:offset(-7, 14)
  local orange = here:offset(7, 14)
  hafen.virtual():patch():add(square(blue, 5), blue):tint({40, 120, 220, 120}):border({40, 120, 220}, 0.4)
  local through = hafen.virtual():patch():add(square(orange, 5), orange)
    :tint({255, 150, 40, 120}):border({255, 150, 40}, 0.4):occluded(false)

  waitFor(120, function() return p:drawn() end, function(onGround)
    eq("a patch is occluded by default", p:occluded(), true)
    check(p:occluded(false) == p, "occluded(b) hands the patch back", p:occluded(false))
    eq("the flag reads back what was written", p:occluded(), false)

    -- The four that fail if the flag re-carved the ring instead of re-pushing its material.
    local t = p:tint()
    check(t and (t.r == 40) and (t.g == 200) and (t.b == 120) and (t.a == 90),
          "the tint survives the flip",
          t and (t.r .. "," .. t.g .. "," .. t.b .. "," .. t.a))
    local bc, bw = p:border()
    check(bc and (bc.r == 255) and (bc.g == 255) and (bc.b == 255) and (bw == 0.5),
          "the border survives the flip",
          bc and (bc.r .. "," .. bc.g .. "," .. bc.b .. " at " .. tostring(bw)))
    local off = p:offset()
    check(off and (off.x == 1) and (off.y == 2) and (off.z == nil),
          "the offset survives the flip",
          off and (tostring(off.x) .. "," .. tostring(off.y) .. "," .. tostring(off.z)))
    check(onGround and p:drawn(), "it stays on the ground across the flip",
          onGround and "it left the ground" or "it never reached drawn ground inside 120 frames")
    eq("info().occluded agrees with the verb", p:info().occluded, false)

    refuses("a non-boolean is refused naming the verb", function() p:occluded(1) end,
            "patch:occluded(b): b must be true or false")

    hafen.virtual():patch():remove(p)      -- a follower re-lays as the character walks: not inside a window
    measure(through, wasArmed)
  end)
end

hafen.console():on("t132", run)   -- the only way in: a suite does not start itself
