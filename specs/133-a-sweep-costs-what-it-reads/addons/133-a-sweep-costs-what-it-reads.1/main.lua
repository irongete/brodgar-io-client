-- 133.1 -- the sweep proves the base from grids it names. Self-checking suite.
--
-- Nothing here can watch a witness or a monitor take. What the surface does report is the four recall keys
-- of p:render(), and a base that has not been proved draws nothing at all -- so every one of those gauges
-- moving is the proof answering from underneath. The bracket at the end is what turning that ground on
-- costs, read out of the client's own estimate of its own allocation.

local SETTLE = 5             -- seconds to let a gauge come to rest after a setting moves
local ROUNDS, ROUND = 3, 4   -- rounds, and seconds each, that grids read is watched settling over
local ALLOC  = 8             -- seconds in each half of the allocation bracket
local NARROW, WIDE = 1, 5    -- the two ranges the gauges are driven between
local FACTOR = 1.25          -- with the ground on, allocation may be at most this much of the rate with it off
local FLOOR  = 65536         -- bytes per frame of difference under which a ratio says nothing

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
  return ok
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- LuaJ's string.format ignores the precision of %f, so the number is rounded and spelled by hand.
local function num(x, dp)
  local m = 10 ^ (dp or 1)
  return tostring(math.floor((x * m) + 0.5) / m)
end

local function kb(bytes)
  return num(bytes / 1024) .. " kB"
end

-- A bridge refusal reaches Lua as "@chunk.lua:189 the message", so the chunk stamp comes off first.
local function why(err)
  local m = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")
  m = m:gsub("%s+", " ")
  if #m > 160 then m = m:sub(1, 160) .. "..." end
  return m
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- Wait a stretch of frames and go on. A suite does not sleep; it hangs its next step off the frame.
local function wait(seconds, done)
  local elapsed, sub = 0, nil
  sub = hafen.event():on("Update", function(dt)
    elapsed = elapsed + (dt or 0)
    if elapsed >= seconds then
      sub:off()
      done()
    end
  end)
end

-- The four recall keys at this instant. Sampled at a phase boundary and never per frame: a poll per frame
-- would allocate on every frame of the bracket below, which is the suite measuring itself.
local function gauges(p)
  local r = p:render()
  return {held = r.recallGridsHeld, read = r.recallGridsRead,
          drawn = r.recallCutsDrawn, wanted = r.recallCutsWanted}
end

-- One half of the allocation bracket. allocPerFrame is the client's own smoothed estimate and is only
-- advanced while the Mem: line of :stats on is drawn, so it can be absent, and worse, FROZEN -- a frozen
-- counter compares flat for the wrong reason. So both are collected: the estimate, and the same quantity
-- read out of heapUsed frame by frame with the negative steps (a collection) dropped. Which one the run
-- used is decided by whether the estimate MOVED, and is named on the verdict line.
local function window(p, seconds, done)
  local w = {frames = 0, heap = 0, prev = nil, first = nil, sum = 0, n = 0, moved = false}
  local elapsed, sub = 0, nil
  sub = hafen.event():on("Update", function(dt)
    elapsed = elapsed + (dt or 0)
    local m = p:memory()
    w.frames = w.frames + 1
    local h = m.heapUsed
    if h then
      if w.prev and (h > w.prev) then w.heap = w.heap + (h - w.prev) end
      w.prev = h
    end
    local a = m.allocPerFrame
    if a then
      if not w.first then w.first = a end
      if a ~= w.first then w.moved = true end
      w.sum = w.sum + a
      w.n = w.n + 1
    end
    if elapsed >= seconds then
      sub:off()
      done(w)
    end
  end)
end

local function rate(w, useAlloc)
  if useAlloc then return w.sum / w.n end
  return (w.frames > 0) and (w.heap / w.frames) or 0
end

-- recallGridsRead is cumulative, so "it stopped" is a round with no delta at all, and "cumulative" is a
-- round that never goes backwards. The record comes off the disk on a shared pool and a settled client has
-- already caught up, so this is scored over a bounded set of rounds rather than demanded of one window.
local function untilStopped(p, last, left, back, done)
  wait(ROUND, function()
    local now = gauges(p).read
    back = back or (now < last)
    if (now == last) or (left <= 1) then
      done(now, now == last, back)
    else
      untilStopped(p, now, left - 1, back, done)
    end
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0
  local p = hafen.client():profiling()
  local c = hafen.client():options():client()
  local cam = hafen.client():options():camera()

  local start = gauges(p)
  check((type(start.held) == "number") and (type(start.read) == "number")
        and (type(start.drawn) == "number") and (type(start.wanted) == "number"),
        "render() reports the four recall keys the rest of this suite is read from",
        tostring(start.held) .. " / " .. tostring(start.read) .. " / "
        .. tostring(start.drawn) .. " / " .. tostring(start.wanted))
  if type(start.held) ~= "number" then
    hafen.log():write("[note] the four are absent until the world is up -- run this in the world")
    summary()
    return
  end

  refuses("a range of 0 is refused naming the bounds the page states",
          function() c:recallRange(0) end, "1 to 8")

  local range0 = c:recallRange()
  local ok, err = pcall(function()
    if cam:mode() ~= "rts" then cam:mode("rts") end
    c:recall(true)
    c:recallRange(NARROW)
  end)
  if not ok then
    check(false, "the ground and its range are driven through the client options",
          "refused (" .. why(err) .. ") -- grant this addon client.settings and re-run")
    summary()
    return
  end

  hafen.log():write("[note] reading the four gauges across the range and the ground's own switch,"
                    .. " about a minute -- stand still in the world and wait for the summary")

  wait(SETTLE, function()
    -- Before any gauge is read: this ground draws under the rts camera and no other, so a camera that did
    -- not swap reads as every gauge flat at zero -- which is also what a base that was never proved reads
    -- as. Asking here is what keeps those two apart.
    if not check(cam:mode() == "rts", "the rts camera is installed, which is the only one this ground draws under",
                 tostring(cam:mode()) .. " -- the write did not take, so nothing below could have drawn") then
      summary()
      return
    end
    local narrow = gauges(p)
    -- The task's own claim, and the one a proof that names the wrong grids fails outright: ground was read
    -- back off the record, which cannot happen until a sweep has proved the base it was read through.
    check((narrow.held > 0) and (narrow.read > 0),
          "the sweep proved its base and read ground back off the record"
          .. " (" .. narrow.held .. " grids held, " .. narrow.read .. " read)",
          "held " .. narrow.held .. ", read " .. narrow.read
          .. " -- nothing was read, so either the base was never proved or you are standing"
          .. " where the record has nothing")

    c:recallRange(WIDE)
    wait(SETTLE, function()
      local wide = gauges(p)
      -- The range is a MAXIMUM and not an amount of work: what is wanted is bounded by what fits in the
      -- view as well, so raising it may leave every gauge where it was. What it may never do is lower one.
      local bound = (wide.wanted > narrow.wanted) and "the range" or "the view"
      check((c:recallRange() == WIDE) and (wide.wanted >= narrow.wanted) and (wide.held >= narrow.held),
            "the range applies live and is a maximum: raising it never lowers a gauge"
            .. " (held " .. narrow.held .. "->" .. wide.held .. ", cuts wanted " .. narrow.wanted
            .. "->" .. wide.wanted .. ", so " .. bound .. " is what binds here)",
            "range " .. tostring(c:recallRange()) .. ", held " .. wide.held
            .. ", wanted " .. wide.wanted)

      untilStopped(p, wide.read, ROUNDS, false, function(settled, stopped, back)
        check(stopped and not back,
              "grids read is cumulative and has caught up with the camera (" .. settled .. ")",
              settled .. (back and " -- it went BACKWARDS, and it is cumulative"
                               or (" -- still climbing after " .. (ROUNDS * ROUND) .. " s of standing still")))

        window(p, ALLOC, function(onw)
          local on = gauges(p)
          c:recall(false)
          window(p, ALLOC, function(offw)
            local off = gauges(p)
            check((off.drawn == 0) and (off.wanted == 0) and (off.held >= on.held),
                  "with the ground off both cut gauges fall to zero and the grids held do not"
                  .. " (held " .. on.held .. "->" .. off.held .. ")",
                  "drawn " .. off.drawn .. ", wanted " .. off.wanted .. ", held " .. off.held)

            local useAlloc = onw.moved and offw.moved and (onw.n > 0) and (offw.n > 0)
            local ron, roff = rate(onw, useAlloc), rate(offw, useAlloc)
            check(ron <= math.max(roff * FACTOR, roff + FLOOR),
                  "allocation with the ground on is flat against the ground off"
                  .. " (" .. kb(ron) .. "/frame against " .. kb(roff) .. ", by "
                  .. (useAlloc and "allocPerFrame" or "heapUsed steps") .. ")",
                  kb(ron) .. " against " .. kb(roff) .. "/frame")

            c:recall(true)
            wait(SETTLE, function()
              local back2 = gauges(p)
              check((back2.wanted == on.wanted) and (back2.held >= on.held),
                    "and the switch brings them back to what they were, with nothing re-read"
                    .. " (cuts wanted " .. on.wanted .. "->" .. off.wanted .. "->" .. back2.wanted .. ")",
                    "wanted " .. back2.wanted .. " against " .. on.wanted .. ", held " .. back2.held
                    .. " against " .. on.held .. " -- equal unless the camera moved")
              c:recallRange(range0)
              manualCheck("walk across ground you have explored before",
                          "it is drawn, greyed, and no further out than the " .. c:recallRange()
                          .. " grids c:recallRange() reports, with none where you have not been")
              hafen.log():write("[note] range put back to " .. range0
                                .. "; the ground is left on and the camera in rts for the manual check")
              summary()
            end)
          end)
        end)
      end)
    end)
  end)
end

hafen.console():on("t133", run)   -- the only way in: a suite does not start itself
