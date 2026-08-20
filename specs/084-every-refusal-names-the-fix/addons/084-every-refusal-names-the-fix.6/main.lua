-- 084.6 -- two widgets, one key. Self-checking suite.
--
-- The whole of it is that the two are DIFFERENT SENTENCES. A widget on the screen and a panel standing in
-- the world used to answer to the same receiver, so the twenty-odd retired rows keyed widget:<verb> fired
-- on whichever of the two was in hand -- and widget:pos's fix, "a widget lives on the screen, so this is
-- not a Position", is the wrong one for a thing whose :position() IS a Position.
--
-- So: each of the two must name its own vocabulary and never the other's, and a row that means BOTH
-- (widget:show) must still fire on both. Standing a panel needs the world, so that half retries on a
-- bounded timer window and scores what it reached.

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

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- Every phrase a message has to carry: one string, or all of a list of them.
local function has(msg, want)
  if type(want) ~= "table" then return msg:find(want, 1, true) ~= nil end
  for _, w in ipairs(want) do
    if msg:find(w, 1, true) == nil then return false end
  end
  return true
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, want)
  local msg = said(fn)
  check((msg ~= nil) and has(msg, want), what, msg or "<no error>")
end

-- The same, and the message must NOT carry the OTHER type's words -- which is the whole of this task: two
-- receivers that used to be one, and two sentences that must not be one.
local function refusesOnly(what, fn, want, forbid)
  local msg = said(fn)
  check((msg ~= nil) and has(msg, want) and (msg:find(forbid, 1, true) == nil), what, msg or "<no error>")
end

local function run()
  -- 1. THE FLAT WIDGET, which needs no world: it must go on saying pixels, and its vocabulary must be its
  -- own. The window is hidden -- nothing here is looked at, only read back.
  local win = hafen.ui():window():title("084.6"):size(160, 96):visible(false)
  check(win:exists(), "the window is up and a known verb still answers", win)
  refusesOnly("w:pos() on a UI widget names PIXELS within the parent",
              function() return win:pos() end, { "widget:position()", "PIXELS" }, "panel:")
  refusesOnly("w:nosuchverb() names the WIDGET vocabulary, not the panel's",
              function() return win:nosuchverb() end, "widget has no verb 'nosuchverb'", ":screen()")
  refuses("w:show() -- the row that means both -- fires on the widget",
          function() return win:show() end, "widget:visible(true)")

  -- 2. THE PANEL, which needs a character in the world. Stand one on the player's own gob (the anchor that
  -- also lets :position(p) be refused below), score what the window reached, and take it back down.
  local tries, done, why = 0, false, nil
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local me = s and s:exists() and s:player():gob()
    local stood, panel = false, nil
    if (not done) and me then
      stood, panel = pcall(function() return hafen.vr():widget():add(win, me) end)
      why = (not stood) and tostring(panel) or why
    end
    if stood then
      done = true
      check(panel:widget() == win and type(panel:visible()) == "boolean",
            "the panel is standing and its own verbs answer", panel)
      refusesOnly("panel:pos() names a Position and the WORLD, not pixels",
                  function() return panel:pos() end,
                  { "panel:position()", "hands back a Position", "stands in the WORLD" }, "PIXELS")
      refusesOnly("panel:nosuchverb() names the PANEL vocabulary, not the widget's",
                  function() return panel:nosuchverb() end, "panel has no verb 'nosuchverb'", ":rootPos()")
      refuses("...and the same typo READ as a field raises too",
              function() return panel.nosuchverb end, "panel has no verb 'nosuchverb'")
      refuses("panel:show() -- the row that means both -- fires on the panel too",
              function() return panel:show() end, "panel:visible(true)")
      refusesOnly("panel:position(p) on one standing on a gob names panel:offset, and the collection is"
                  .. " still hafen.vr():widget()",
                  function() return panel:position(me:position()) end,
                  { "panel:offset(x, y, z)", "hafen.vr():widget():add(what, p)" }, "hafen.vr():panel()")
      refuses("panel:onClick(fn) names the WIDGET's own subscription",
              function() return panel:onClick(function() end) end, "panel:widget():on(\"MouseDown\", fn)")
      hafen.vr():widget():remove(panel)
    end
    if done or (tries >= 20) then
      t:cancel()
      if not done then
        check(false, "the panel half of the pair", why or "no character in the world reached in 10s")
      end
      win:destroy()
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end
  end)
end

hafen.slash():register("t084-6", run)   -- the only way in: a suite does not start itself
