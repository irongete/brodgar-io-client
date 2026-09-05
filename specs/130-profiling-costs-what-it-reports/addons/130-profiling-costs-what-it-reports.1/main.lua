-- 130.1 -- the frame ring keeps a handful of frames, not five seconds of them. Self-checking suite.
--
-- Nothing here can watch the ring or the collector. No verb reports a pause, and a stop-the-world pause is
-- invisible to the thread it stops. What memory() does report is gcCount and gcMs, cumulative and
-- meaningful only as a delta between two reads -- so this measures the way the fix was diagnosed: a
-- collection delta over a bounded stretch of frames, armed against disarmed, on the same spot. The verdict
-- is a RATIO, because the absolute millisecond count belongs to the machine it ran on.

local WINDOW = 25       -- seconds of frames in each of the two phases
local FACTOR = 3        -- the armed rate may be at most this many times the disarmed one
local FLOOR  = 0.05     -- ms of collection per frame under which a ratio says nothing

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

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- LuaJ's string.format ignores the precision of %f, so the number is rounded and spelled by hand.
local function num(x, dp)
  local m = 10 ^ (dp or 3)
  return tostring(math.floor((x * m) + 0.5) / m)
end

-- A bridge refusal reaches Lua as "@chunk.lua:189 the message", so the chunk stamp comes off first.
local function why(err)
  local m = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")
  m = m:gsub("%s+", " ")                       -- a traceback is many lines; a log line is one
  if #m > 160 then m = m:sub(1, 160) .. "..." end
  return m
end

-- One phase: memory() at each end of `seconds` worth of frames and nothing in between. A poll per frame
-- would allocate on every frame of both phases, which is the suite measuring itself.
local function phase(p, seconds, done)
  local m0 = p:memory()
  local frames, elapsed = 0, 0
  local sub
  sub = hafen.event():on("Update", function(dt)
    frames = frames + 1
    elapsed = elapsed + (dt or 0)
    if elapsed >= seconds then
      sub:off()
      local m1 = p:memory()
      done(frames, (m1.gcCount or 0) - (m0.gcCount or 0), (m1.gcMs or 0) - (m0.gcMs or 0))
    end
  end)
end

-- The verdict, once both phases have run: ms of collection per frame, armed against disarmed.
local function verdict(af, ac, ams, df, dc, dms)
  local ar = (af > 0) and (ams / af) or 0
  local dr = (df > 0) and (dms / df) or 0
  if (ac == 0) and (dc == 0) then
    check(false, "the two windows saw a collection to compare",
          "none in " .. (WINDOW * 2) .. " s -- run this in a loaded world, not on the login screen")
    return
  end
  local armed = num(ar) .. " ms/frame, " .. ac .. " collections"
  local dis = num(dr) .. " ms/frame, " .. dc .. " collections"
  if ac > 0 then armed = armed .. " of " .. num(ams / ac, 1) .. " ms mean" end
  if dc > 0 then dis = dis .. " of " .. num(dms / dc, 1) .. " ms mean" end
  check(ar <= math.max(dr * FACTOR, FLOOR),
        "collection per armed frame is within " .. FACTOR .. "x the disarmed rate"
        .. " (armed " .. armed .. "; disarmed " .. dis .. ")",
        num(ar) .. " vs " .. num(dr) .. " ms/frame")
end

local function run()
  pass, fail, manual = 0, 0, 0
  local p = hafen.client():profiling()
  local opts = hafen.client():options():client()

  local m = p:memory()
  check((type(m.gcCount) == "number") and (type(m.gcMs) == "number"),
        "memory() reports the cumulative gcCount and gcMs the comparison is made of",
        tostring(m.gcCount) .. " / " .. tostring(m.gcMs))

  local ok, err = pcall(function() opts:profiling(true) end)
  check(ok and (opts:profiling() == true), "profiling arms through the client options",
        ok and opts:profiling()
           or ("refused (" .. why(err) .. ") -- grant this addon client.settings and re-run"))
  if not (ok and opts:profiling()) then
    summary()
    return
  end

  hafen.log():write("[note] measuring " .. (WINDOW * 2) .. " s of frames, armed then disarmed"
                    .. " -- stand still in the world and wait for the summary")

  phase(p, WINDOW, function(af, ac, ams)
    -- Still armed: the reads that prove a shorter ring did not take the surface over it with it.
    local h = p:history(600)
    check(#h >= 300, "history() still holds several hundred frames (" .. #h .. ")",
          #h .. " of " .. af .. " armed frames -- under 300 only if this client runs below 12 fps")
    local f = p:frame()
    check((type(f.gpuMs) == "number") and (f.gpuMs > 0) and (type(f.gpuFrameno) == "number"),
          "frame() still resolves a gpuMs with its gpuFrameno",
          tostring(f.gpuMs) .. " / " .. tostring(f.gpuFrameno))
    check((type(f.frameno) == "number") and (type(f.gpuFrameno) == "number")
          and (f.gpuFrameno <= f.frameno),
          "the resolved GPU frame trails the frame that just finished",
          tostring(f.gpuFrameno) .. " against " .. tostring(f.frameno))

    opts:profiling(false)
    check(opts:profiling() == false, "profiling disarms through the same option", opts:profiling())
    check(next(p:frame()) == nil, "disarmed, frame() is the empty table", "a frame")

    phase(p, WINDOW, function(df, dc, dms)
      verdict(af, ac, ams, df, dc, dms)
      opts:profiling(true)
      check(opts:profiling() == true,
            "profiling is left armed for the manual check -- `:profile off` puts the switch back",
            opts:profiling())
      manualCheck("press the backtick key now, standing in the world",
                  "three windows -- UI profile, GL profile, GPU profile -- open and draw a graph"
                  .. " 16 bars wide, and the client goes on running; narrow is the fix, blank chrome"
                  .. " or a NullPointerException out of Profdisp is not")
      summary()
    end)
  end)
end

hafen.console():on("t130", run)   -- the only way in: a suite does not start itself
