-- 133.2 -- the sweep builds nothing until it has something to read. Self-checking suite.
--
-- Nothing here can watch an allocation site. What the surface reports is the four recall keys of p:render()
-- beside the client's own estimate of its own allocation, and between them they say both halves of this
-- task. A camera standing still over ground already read is the state the sweep now passes through building
-- nothing, so a bracket over that state is what the removal is worth. And the grids held against the grids
-- read is the keep set's own arithmetic: every grid installed is one more held until the cap says otherwise,
-- which is only true while what the order holds and what the cache holds are the same set.

local SETTLE = 5             -- seconds to let a gauge come to rest after a setting moves
local ROUNDS, ROUND = 3, 4   -- rounds, and seconds each, that grids read is watched settling over
local ALLOC  = 8             -- seconds in each half of the allocation bracket
local FACTOR = 1.25          -- with the ground on, allocation may be at most this much of the rate with it off
local FLOOR  = 65536         -- bytes per frame of difference under which a ratio says nothing
local PANROUNDS, PANROUND = 6, 5   -- rounds, and seconds each, the pan is scored over

-- What the source may hold: the read square, times the squares it keeps. The read square is one grid wider
-- than the drawn range for the fill margin, and three of them are what makes a pan away and back free.
local KEEPSQUARES = 3
local function capOf(range)
  local side = ((range + 1) * 2) + 1
  return side * side * KEEPSQUARES
end

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

-- recallGridsRead is cumulative, so "it stopped" is a round with no delta at all. The record comes off the
-- disk on a shared pool and a settled client has already caught up, so this is scored over a bounded set of
-- rounds rather than demanded of one window -- and whether it ever stopped is carried to the check below,
-- because "the sweep asked for nothing" is only a claim about a sweep that had already caught up.
local function untilStopped(p, last, left, done)
  wait(ROUND, function()
    local now = gauges(p).read
    if (now == last) or (left <= 1) then
      done(now == last)
    else
      untilStopped(p, now, left - 1, done)
    end
  end)
end

-- The pan, scored while the maintainer is making it: a program cannot move a camera, but it can watch the
-- two numbers that move with one. It runs until the grids held stop climbing, or until the rounds run out.
local function panning(p, start, prev, peak, left, done)
  wait(PANROUND, function()
    local now = gauges(p)
    peak = (now.held > peak) and now.held or peak
    local climbing = (now.held > prev.held) or (now.read > prev.read)
    if (left <= 1) or ((not climbing) and (now.read > start.read)) then
      done(now, peak)
    else
      panning(p, start, now, peak, left - 1, done)
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

  local ok, err = pcall(function()
    if cam:mode() ~= "rts" then cam:mode("rts") end
    c:recall(true)
  end)
  if not ok then
    check(false, "the ground and the camera it draws under are driven through the client options",
          "refused (" .. why(err) .. ") -- grant this addon client.settings and re-run")
    summary()
    return
  end

  local cap = capOf(c:recallRange())
  hafen.log():write("[note] stand STILL in the world for about a minute -- you are asked to pan partway"
                    .. " through, and the cap at range " .. c:recallRange() .. " is " .. cap .. " grids")

  wait(SETTLE, function()
    -- Before any gauge is read: this ground draws under the rts camera and no other, so a camera that did
    -- not swap reads as every gauge flat at zero -- which is also what a base that was never proved reads
    -- as. Asking here is what keeps those two apart.
    if not check(cam:mode() == "rts", "the rts camera is installed, which is the only one this ground draws under",
                 tostring(cam:mode()) .. " -- the write did not take, so nothing below could have drawn") then
      summary()
      return
    end
    local base = gauges(p)
    if not check((base.held > 0) and (base.read > 0),
                 "the sweep proved its base and read ground back off the record"
                 .. " (" .. base.held .. " grids held, " .. base.read .. " read)",
                 "held " .. base.held .. ", read " .. base.read
                 .. " -- nothing was read, so either the base was never proved or you are standing"
                 .. " where the record has nothing") then
      summary()
      return
    end

    untilStopped(p, base.read, ROUNDS, function(stopped)
      local still = gauges(p)
      window(p, ALLOC, function(onw)
        local on = gauges(p)
        -- This task's own claim, in the state it is about: the record has caught up, so every coord the
        -- raster wants is already held and the read loop walks its whole wanted set reaching no ask at
        -- all. Nothing read and nothing dropped across the window is that sweep doing nothing, repeatedly.
        check(stopped and (on.read == still.read) and (on.held == still.held),
              "a camera that does not move asks for nothing: across " .. ALLOC
              .. " s neither the grids read nor the grids held moved (" .. on.read .. ", " .. on.held .. ")",
              "read " .. still.read .. "->" .. on.read .. ", held " .. still.held .. "->" .. on.held
              .. (stopped and " -- stand still and re-run"
                           or (" -- still climbing after " .. (ROUNDS * ROUND) .. " s, so it never settled")))

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
                "standing still, allocation with the ground on is flat against the ground off"
                .. " (" .. kb(ron) .. "/frame against " .. kb(roff) .. ", by "
                .. (useAlloc and "allocPerFrame" or "heapUsed steps") .. ")",
                kb(ron) .. " against " .. kb(roff) .. "/frame")

          c:recall(true)
          wait(SETTLE, function()
            local pan0 = gauges(p)
            hafen.log():write("[note] PAN NOW -- drag the camera across ground you have explored and back,"
                              .. " for up to " .. (PANROUNDS * PANROUND)
                              .. " s, watching the ground as it comes in")
            panning(p, pan0, pan0, pan0.held, PANROUNDS, function(fin, peak)
              local dread, dheld = fin.read - pan0.read, fin.held - pan0.held
              check(dread > 0,
                    "the pan was answered off the record (" .. dread .. " grids read)",
                    "nothing was read at all -- pan across ground you have EXPLORED, and re-run")
              -- The keep set's own arithmetic, and what fails first if what the order holds drifts from
              -- what the cache holds. Under the cap nothing is dropped, so every grid installed is one
              -- more held and the two deltas are one number; at the cap the order starts dropping and
              -- held settles there instead. Which of the two this run reached is named on the line.
              local atcap = (peak >= cap)
              local where = (peak > cap) and "it went OVER the cap"
                            or (atcap and "it settled AT the cap" or "this run stayed under the cap")
              check((peak <= cap) and (atcap or (dheld == dread)),
                    "the grids held are the grids installed, bounded by the cap of " .. cap
                    .. " (held " .. pan0.held .. "->" .. fin.held .. ", " .. dread .. " read, peak " .. peak
                    .. ", so " .. where .. ")",
                    "held +" .. dheld .. " against " .. dread .. " read, peak " .. peak .. " against cap " .. cap
                    .. " -- above the cap is an install the order never recorded, below it a drop nothing"
                    .. " asked for (a re-base mid-pan, entering a cave or a house, reads the same way)")
              manualCheck("pan across remembered ground and back",
                          "no seam and no flicker where a grid was dropped and read again")
              summary()
            end)
          end)
        end)
      end)
    end)
  end)
end

hafen.console():on("t133", run)   -- the only way in: a suite does not start itself
