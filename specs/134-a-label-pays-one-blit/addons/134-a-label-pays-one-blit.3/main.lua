-- 134.3 -- a gob label wears a font. Self-checking suite.
--
-- The claim is that the label an overlay stands at a gob is blitted in a face of the ADDON's own, so the
-- edge 134.1 baked into a raster reaches the label 134.2 stood on the ground -- the one label a crop number
-- wants, at the price of one blit. Two readings say it between them: the record reads back the very handle
-- it was given, refuses anything that is not one and refuses the write on one of the game's own overlays;
-- and the label and a g:text of the same string in the same face are ONE cache entry, which they can only
-- be if the label is drawn through that face at all -- a label ignoring it would key on the stock font and
-- make two. What no program can see is whether the edge LOOKS right at the feet, so the digit is left up.

local NATIVEROUNDS, NATIVEROUND = 6, 0.5   -- rounds, and seconds each, an overlay of the game's is waited for
local CACHEROUNDS, CACHEROUND = 6, 0.5     -- rounds, and seconds each, the two frames are waited for
local GROUND = 0                           -- the height the label stands at: the ground under the object

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
-- these refusals name where a handle comes from in a closing sentence, well past any sane display width.
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

  local o = hafen.font():get("sans"):derive():size(11):bold(true):outline{0, 0, 0}
  local prof = hafen.client():profiling()
  -- Read before anything of this addon's has drawn the string: the entry the label makes is the delta.
  local before = prof:textcache().entries

  -- The label the manual half is about, and the probe beside it. Both stand at the ground, so one
  -- projection covers the two and a frame that paints the probe painted the label in the same loop --
  -- which is the only way a program can tell that a label with no Lua in it drew at all.
  local probe = {frames = 0}
  local ov = me:overlay():add("seven"):text("7"):height(GROUND):font(o)
  local mark = me:overlay():add("probe"):height(GROUND):draw(function(g, gob, sx, sy)
    probe.frames = probe.frames + 1
  end)

  check((ov:font() == o) and (mark:font() == nil),
        "a record reads back the very handle it was given, and one given none reads nil",
        tostring(ov:font()) .. " and " .. tostring(mark:font()))
  -- A face is a handle, not a name: resolving a string to the stock font would be a label silently in the
  -- wrong face, so it is refused naming where a handle comes from.
  refuses("a font that is not a handle is refused, naming where one comes from",
          function() ov:font("sans") end, "hafen.font():get")
  check(ov:info().font == nil,
        "the snapshot carries no font: a handle is a live object, not a snapshot",
        tostring(ov:info().font))

  -- One of the GAME's overlays is read-only, this write included -- and only the server makes one, so it is
  -- waited for over a bounded window rather than assumed to be in sight.
  local function findNative()
    for _, g in ipairs(s:world():gob():list()) do
      local nat = g:overlay():find("")
      if nat and nat:native() then return nat end
    end
    return nil
  end

  local function reachNative(left, done)
    local nat = findNative()
    if nat or (left <= 1) then
      done(nat)
    else
      wait(NATIVEROUND, function() reachNative(left - 1, done) end)
    end
  end

  -- The cost, which is the whole point of the face reaching the label: the same string in the same handle
  -- is ONE raster whichever of the two draws it. A label keyed on the stock font would make a second.
  local hud = {frames = 0}
  local function untilDrawn(left, done)
    wait(CACHEROUND, function()
      if ((hud.frames >= 2) and (probe.frames >= 2)) or (left <= 1) then
        done()
      else
        untilDrawn(left - 1, done)
      end
    end)
  end

  reachNative(NATIVEROUNDS, function(nat)
    if nat then
      refuses("one of the game's own overlays refuses the write, naming its key",
              function() nat:font(o) end, nat:key():lower())
    else
      check(false, "one of the game's own overlays refuses the write, naming its key",
            "no overlay of the game's came into sight in " .. (NATIVEROUNDS * NATIVEROUND) .. "s"
            .. " -- stand where the game decorates something (a fire, a growing crop) and run :t134 again")
    end

    hafen.ui():overlay():add("t134-cache"):draw(function(g, w, h)
      hud.frames = hud.frames + 1
      g:atext("7", 8, 8, 0, 0, {font = o})
    end)

    untilDrawn(CACHEROUNDS, function()
      hafen.ui():overlay():remove("t134-cache")
      me:overlay():remove("probe")
      local after = prof:textcache().entries
      local drew = (hud.frames >= 2) and (probe.frames >= 2)
      check(drew and ((after - before) == 1),
            "the label at the gob and a g:text of the same string in the same face are ONE cache entry"
            .. " (" .. before .. " -> " .. after .. ")",
            "entries " .. before .. " -> " .. after .. " over " .. probe.frames .. " painted frames at the"
            .. " gob and " .. hud.frames .. " on the HUD"
            .. (drew and "" or " -- one of them never drew twice, so nothing was proved"))

      manualCheck("look at your character's feet and walk a few steps",
                  "a white 7 with a one-pixel black edge all round it, standing on the ground point and"
                  .. " following the character (the label goes with a :reload)")
      summary()
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line runs under the CHARACTER's tree monitor,
-- and an attach mutates that object's render slots -- so the run is handed to the engine step, where no
-- monitor is held.
hafen.console():on("t134", function() hafen.timer():after(0, run) end)
