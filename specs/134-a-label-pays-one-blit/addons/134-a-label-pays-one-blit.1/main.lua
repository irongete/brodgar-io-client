-- 134.1 -- the outline is in the raster. Self-checking suite.
--
-- The claim is that the edge is a property of the FACE, so it is in the raster and in the cache key rather
-- than in a draw call. Three readings say that between them: the box a measure answers has grown by the two
-- pixels the edge takes, two frames of an outlined label are ONE cache entry (five would be the old way of
-- drawing an edge, and two would be an outline blitted beside its glyphs), and a face carrying one is
-- refused at the door a client surface takes a face through. What no program can see is whether the edge
-- LOOKS right, so that one digit is drawn where it can be looked at.

local CACHEROUNDS, CACHEROUND = 6, 0.5   -- rounds, and seconds each, the two frames are waited for

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
-- the bridge shouts the word it is contrasting ("your OWN drawing") and what is asserted is the words.
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

local function shows(c)
  if c == nil then return "nil" end
  return "{" .. tostring(c.r) .. ", " .. tostring(c.g) .. ", " .. tostring(c.b) .. "}"
end

local function isColor(c, r, g, b)
  return (c ~= nil) and (c.r == r) and (c.g == g) and (c.b == b)
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

-- The overlay is up and painting; hold until it has drawn the twice the claim is about, or give up bounded.
local function untilDrawn(state, left, done)
  wait(CACHEROUND, function()
    if (state.frames >= 2) or (left <= 1) then
      done()
    else
      untilDrawn(state, left - 1, done)
    end
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0

  local sans = hafen.font():get("sans")
  local o = sans:derive():size(11):bold(true):outline{0, 0, 0}
  local p = sans:derive():size(11):bold(true)

  -- The property itself: it reads back what was written, and a face carrying none reads nil rather than a
  -- colour standing in for "no edge".
  check(isColor(o:outline(), 0, 0, 0) and (p:outline() == nil),
        "an outlined variant reads its colour back and a plain one reads nil",
        shows(o:outline()) .. " and " .. shows(p:outline()))

  -- nil UNDOES the layer, the meaning size and aa already carry on the same word -- and the setter hands
  -- the handle back either way, so a variant is one chain whichever of the two is written.
  local d = sans:derive():outline{9, 9, 9}
  check((d:outline(nil) == d) and (d:outline() == nil),
        "outline(nil) undoes it on a fresh draft: the write chains and the read is nil again",
        shows(d:outline()))

  refuses("a built-in refuses the setter naming :derive()",
          function() hafen.font():get("sans"):outline{0, 0, 0} end, ":derive()")

  -- The boundary A4 draws: an outline is the addon's own drawing, so the door a CLIENT surface takes a face
  -- through refuses one. The window is the suite's own and goes again either way.
  local win = hafen.ui():window():title("134.1"):size(120, 60)
  refuses("a rule on a window of its own refuses an outlined face, naming your own drawing",
          function() win:rule():font(o) end, "own drawing")
  win:destroy()

  -- The cost, which is the whole point of putting the edge in the face. The label is drawn every frame; if
  -- the raster were built per frame, or the edge blitted beside its glyphs, this would not be one entry.
  local prof = hafen.client():profiling()
  local before = prof:textcache().entries
  local state = {frames = 0}
  hafen.ui():overlay():add("t134-cache"):draw(function(g, w, h)
    state.frames = state.frames + 1
    g:text("7", 8, 8, {font = o})
  end)

  untilDrawn(state, CACHEROUNDS, function()
    hafen.ui():overlay():remove("t134-cache")
    local after = prof:textcache().entries
    check((state.frames >= 2) and ((after - before) == 1),
          "an outlined label drawn on " .. state.frames .. " frames is ONE cache entry"
          .. " (" .. before .. " -> " .. after .. ")",
          "entries " .. before .. " -> " .. after .. " over " .. state.frames .. " frames"
          .. ((state.frames < 2) and " -- it never drew twice, so nothing was proved" or ""))

    -- The measure answers the DRAWN box, so the two pixels the edge takes are in it. Design pixels: on a
    -- scaled client the two device pixels the raster grew by are worth fewer of them, so what the scale
    -- makes them is what is asked for.
    local scale = hafen.ui():scale()
    local bo, bp = hafen.ui():measure("7", {font = o}), hafen.ui():measure("7", {font = p})
    local dw, dh = bo.w - bp.w, bo.h - bp.h
    local want = (scale == 1) and "exactly 2 each way" or "1 or 2 each way at scale " .. tostring(scale)
    local okw = (scale == 1) and (dw == 2) or ((dw >= 1) and (dw <= 2))
    local okh = (scale == 1) and (dh == 2) or ((dh >= 1) and (dh <= 2))
    check(okw and okh,
          "the box an outlined face measures is the plain one grown by " .. want,
          bp.w .. "x" .. bp.h .. " -> " .. bo.w .. "x" .. bo.h
          .. " (+" .. dw .. ", +" .. dh .. ")")

    -- o has been read by a rule and by a draw call, so it is sealed like every other used variant.
    refuses("a variant a draw call has read refuses the setter naming :derive()",
            function() o:outline{1, 1, 1} end, ":derive()")

    -- What no program can look at. The plate is mid-grey so both halves of the claim are visible on it:
    -- a white glyph would vanish on white, a black edge on black.
    local big = sans:derive():size(40):bold(true):outline{0, 0, 0}
    local box = hafen.ui():measure("7", {font = big})
    hafen.ui():overlay():add("t134-look"):draw(function(g, w, h)
      g:color(110, 110, 110)
      g:frect(6, 6, box.w + 8, box.h + 8)
      g:color()
      g:text("7", 10, 10, {font = big})
    end)
    manualCheck("look at the grey plate at the top-left of the screen",
                "a white 7 with a one-pixel black edge all round it, one clean edge and no doubled outline"
                .. " (the plate goes with a :reload)")
    summary()
  end)
end

-- The only way in: a suite does not start itself. A console line runs under the CHARACTER's tree monitor,
-- and the window below is a widget of the addon layer -- a second tree -- so the run is handed to the engine
-- step, where no monitor is held.
hafen.console():on("t134", function() hafen.timer():after(0, run) end)
