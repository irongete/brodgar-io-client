-- 134.2 -- a label stands where it is told. Self-checking suite.
--
-- The claim is that WHERE UP THE GOB an overlay's point is taken is a property of the record, in world
-- units, so the projection is done per record rather than once per gob. Three readings say that between
-- them: the property reads back what it was written and refuses what is not a number, the point a draw
-- record at the ground is handed is the one the projection answers for the object itself, and a second
-- record left at the default is handed a point higher up the screen in the same frame. What no program can
-- see is whether the two labels LOOK like they stand at the feet and over the head, so both are left up to
-- be looked at.

local NATIVEROUNDS, NATIVEROUND = 6, 0.5   -- rounds, and seconds each, an overlay of the game's is waited for
local DRAWROUNDS, DRAWROUND = 6, 0.5       -- rounds, and seconds each, the probe's first frame is waited for
local GROUND, HEAD = 0, 15                 -- the two heights: the ground under the object, and the default

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

-- A bridge refusal reaches Lua as "@chunk.lua:189 the message", so the chunk stamp comes off first.
local function why(err)
  local m = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")
  m = m:gsub("%s+", " ")
  return m
end

-- One verdict line stays readable, so only what is PRINTED is cut. The match below reads the whole message:
-- these refusals name their replacement in a closing sentence, well past any sane display width.
local function short(m)
  if #m > 150 then m = m:sub(1, 150) .. "..." end
  return m
end

-- A refusal is a check: the call must fail, and fail SAYING why. The message is matched lower-cased, since
-- the bridge shouts the word it is contrasting ("READ-ONLY") and what is asserted is the words.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  local m = ok and "<no error>" or why(err)
  check((not ok) and (m:lower():find(wantMsg, 1, true) ~= nil), what, short(m))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- A projected point is a fraction and the verdict line is read by eye, so it is cut to one decimal by hand
-- (string.format's precision is not honoured here).
local function px(n)
  if n == nil then return "nil" end
  return tostring(math.floor(n * 10 + 0.5) / 10)
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

local function run()
  pass, fail, manual = 0, 0, 0

  local s = hafen.session():current()
  local me = s and s:player():gob()
  if not me then
    check(false, "the suite needs a character in the world to attach to", "no player gob")
    summary()
    return
  end

  -- The two labels the manual half is about, and the reads the automated half is: one told to stand on the
  -- ground under the object, one left at the default.
  local a = me:overlay():add("a"):text("a"):height(GROUND)
  local b = me:overlay():add("b"):text("b")

  check((a:height() == GROUND) and (b:height() == HEAD),
        "a fresh record stands at " .. HEAD .. " and :height(" .. GROUND .. ") writes the ground",
        tostring(a:height()) .. " and " .. tostring(b:height()))
  check(a:info().height == GROUND,
        "the snapshot carries the height it was written",
        tostring(a:info().height))
  refuses("a height that is not a number is refused, saying so",
          function() a:height("x") end, "must be a number")
  -- The vertical world form has a verb of its own, so the pixel one keeps its two numbers and names it.
  refuses("offset's third argument is refused, naming the height as the vertical world form",
          function() a:offset(1, 2, 3) end, "overlay:height(z)")

  -- One of the GAME's overlays is read-only, this write included -- and only the server makes one, so it is
  -- waited for over a bounded window rather than assumed to be in sight.
  local function findNative()
    for _, g in ipairs(s:world():gob():list()) do
      local ov = g:overlay():find("")
      if ov and ov:native() then return ov end
    end
    return nil
  end

  local function reachNative(left, done)
    local ov = findNative()
    if ov or (left <= 1) then
      done(ov)
    else
      wait(NATIVEROUND, function() reachNative(left - 1, done) end)
    end
  end

  -- The point itself, which is the whole claim: a record at the ground is handed the object's own projected
  -- point, and one at the default is handed a point further up the screen IN THE SAME FRAME. Both readings
  -- are taken inside the draw pass, so the projection they are compared against is that frame's own.
  local function probe(done)
    local shot = {}
    me:overlay():add("c"):height(GROUND):draw(function(g, gob, sx, sy)
      if shot.c == nil then
        shot.c = {x = sx, y = sy, p = s:world():worldToScreen(me:position())}
      end
    end)
    me:overlay():add("d"):draw(function(g, gob, sx, sy)
      if (shot.c ~= nil) and (shot.d == nil) then
        shot.d = {x = sx, y = sy}
      end
    end)

    local function held(left)
      wait(DRAWROUND, function()
        if (shot.d ~= nil) or (left <= 1) then
          me:overlay():remove("c")
          me:overlay():remove("d")
          done(shot)
        else
          held(left - 1)
        end
      end)
    end
    held(DRAWROUNDS)
  end

  reachNative(NATIVEROUNDS, function(nat)
    if nat then
      refuses("one of the game's own overlays refuses the write, naming its key",
              function() nat:height(GROUND) end, nat:key():lower())
    else
      check(false, "one of the game's own overlays refuses the write, naming its key",
            "no overlay of the game's came into sight in " .. (NATIVEROUNDS * NATIVEROUND) .. "s"
            .. " -- stand where the game decorates something (a fire, a growing crop) and run :t134 again")
    end

    probe(function(shot)
      local c, d = shot.c, shot.d
      local p = c and c.p
      local dx = (c and p) and math.abs(c.x - p.x) or nil
      local dy = (c and p) and math.abs(c.y - p.y) or nil
      check((dx ~= nil) and (dx <= 1) and (dy <= 1),
            "a record at the ground is handed the object's own projected point, within a pixel each way",
            (c == nil) and ("it never drew in " .. (DRAWROUNDS * DRAWROUND) .. "s, so nothing was proved")
            or ((p == nil) and "the projection answered nil in that frame"
                or ("(" .. px(c.x) .. ", " .. px(c.y) .. ") against ("
                    .. px(p.x) .. ", " .. px(p.y) .. ")")))
      check((d ~= nil) and (c ~= nil) and (d.y < c.y),
            "a record left at " .. HEAD .. " is handed a point higher up the screen in the same frame",
            (d == nil) and "the second record never drew beside the first"
            or ("y " .. px(d.y) .. " against " .. px(c.y)))

      manualCheck("look at your character and walk a few steps",
                  "'a' standing at the feet and 'b' over the head, both following the character"
                  .. " (the two labels go with a :reload)")
      summary()
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line runs under the CHARACTER's tree monitor,
-- and an attach mutates that object's render slots -- so the run is handed to the engine step, where no
-- monitor is held.
hafen.console():on("t134", function() hafen.timer():after(0, run) end)
