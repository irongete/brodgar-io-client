-- 041.3 — Input on any widget. Self-checking suite; see specs/addons/TESTING.md. Run :t041-3
--
-- WHAT THIS TASK CLAIMS. widget:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel", fn) answers on ANY widget --
-- one you built, and one you merely found by selector (a native widget, previously unreachable outside
-- hafen.hook():input's three fixed tokens). Both hand back a Sub over the SAME vocabulary; an unknown key
-- throws naming what the widget does answer; every retired spelling (hafen.hook():input and the four old
-- w:onClick/:onMouseUp/:onMouseMove/:onWheel verbs) throws naming its replacement. Cancelling is
-- ev:preventDefault() and nothing else -- a handler's own return value is never read, and off() then
-- re-subscribing leaves exactly one engine listener behind, never two.
--
-- THE ONE THING A PROGRAM CANNOT JUDGE. Lua has no way to synthesize a real mouse click (D-017's sandbox
-- omits luajava, and nothing in the facade injects input), so delivery itself -- a real click firing the
-- handler, preventDefault suppressing MapView's own move, a truthy return NOT suppressing it, and a
-- re-subscribe not double-firing -- rides the single [manual] round below. Everything that does not need a
-- real click (registration, Sub identity, every refusal) is asserted immediately.
--
-- READ-ONLY: declares no permissions, mutates no persistent state. The one widget it builds is destroyed
-- before the summary prints; the one MapView listener the manual round needs is timed out after 90s if the
-- maintainer never clicks.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function nop() end

local INPUT_KEYS = { "MouseDown", "MouseUp", "MouseMove", "Wheel" }

local function run()
  -- 1. A NATIVE widget, found by selector -- the reach that did not exist before this task -- answers
  --    :on(key, fn) with a Sub, on all four universal keys, exactly like an owned widget does below.
  local mapview = hafen.ui():find("@MapView")
  check(mapview ~= nil, "the map view is reachable by selector (hafen.ui():find(\"@MapView\"))", mapview)
  if mapview then
    local nativeOk = 0
    for _, key in ipairs(INPUT_KEYS) do
      local sub = mapview:on(key, nop)
      if (type(sub) == "userdata") and (type(sub.off) == "function") then
        nativeOk = nativeOk + 1
        sub:off()
      end
    end
    check(nativeOk == #INPUT_KEYS,
          ("a NATIVE widget answers :on(key, fn) with a Sub on all %d universal keys (%d/%d)")
          :format(#INPUT_KEYS, nativeOk, #INPUT_KEYS), nativeOk)
  end

  -- 2. An OWN widget answers the very same four keys through the very same door -- "one vocabulary", not a
  --    second mechanism for the widget you built (D-172).
  local own = hafen.ui():widget():size(4, 4)
  local ownOk = 0
  for _, key in ipairs(INPUT_KEYS) do
    local sub = own:on(key, nop)
    if (type(sub) == "userdata") and (type(sub.off) == "function") then
      ownOk = ownOk + 1
      sub:off()
    end
  end
  check(ownOk == #INPUT_KEYS,
        ("an OWN widget answers the same four keys, on the same verb (%d/%d)"):format(ownOk, #INPUT_KEYS),
        ownOk)

  -- 3. sub:off() is idempotent -- re-asserted here (041.1's own premise) since this is a fresh emitter.
  local idemSub = own:on("MouseDown", nop)
  idemSub:off()
  local ok2 = pcall(function() idemSub:off() end)
  check(ok2, "sub:off() on a widget Sub is idempotent -- a second call is a no-op, not an error", ok2)

  -- 4. The closed vocabulary throws naming what it does answer, listing the four keys (D-125) -- a widget-
  --    specific vocabulary joins this in 041.4; for now every widget answers the same four.
  refuses("an unknown key throws naming the four keys this task ships",
          function() own:on("Bogus", nop) end, "MouseDown, MouseUp, MouseMove, Wheel")
  refuses(":on(key) with no function is a missing-argument error, not a read",
          function() own:on("MouseDown") end, "fn is required")

  -- 5. Every retired spelling throws naming its replacement -- both hafen.hook():input doors (the colon
  --    verb and the pre-039 dotted field) and the four old widget verbs it retires alongside them.
  refuses("hafen.hook():input(...) throws naming widget:on(key, fn)",
          function() hafen.hook():input("mapview", "mousedown", nop) end, "handle:on(key, fn)")
  refuses("...and so does the pre-039 dotted spelling, hafen.hook.input",
          function() return hafen.hook.input end, "handle:on(key, fn)")
  refuses("w:onClick(fn) throws naming widget:on(\"MouseDown\", fn)",
          function() own:onClick(nop) end, "widget:on(\"MouseDown\", fn)")
  refuses("w:onMouseUp(fn) throws naming widget:on(\"MouseUp\", fn)",
          function() own:onMouseUp(nop) end, "widget:on(\"MouseUp\", fn)")
  refuses("w:onMouseMove(fn) throws naming widget:on(\"MouseMove\", fn)",
          function() own:onMouseMove(nop) end, "widget:on(\"MouseMove\", fn)")
  refuses("w:onWheel(fn) throws naming widget:on(\"Wheel\", fn)",
          function() own:onWheel(nop) end, "widget:on(\"Wheel\", fn)")
  check(type(hafen.hook().grab) == "function",
        "hafen.hook() itself still answers :grab -- this task retired :input, not the section",
        tostring(hafen.hook().grab))

  own:destroy()

  -- 6. THE FOUR THINGS ONLY A REAL CLICK CAN PROVE, all through one MapView listener that walks through
  --    four states as the maintainer clicks: PASS (no preventDefault -- a truthy return is not read either),
  --    CONSUME (preventDefault blocks MapView's own move), PASS again (the cancel did not stick), then an
  --    off()+re-subscribe that must fire exactly ONCE per click, never twice (no leaked engine listener).
  if mapview then
    local clicks = 0
    local sub
    local function install()
      sub = mapview:on("MouseDown", function(ev)
        clicks = clicks + 1
        if clicks == 1 then
          hafen.log():write(("041.3: click #1 at %d,%d btn=%d -- PASSED THROUGH (no preventDefault; a"
            .. " `return true` here is never read) -- your character should have walked there")
            :format(ev:x(), ev:y(), ev:button()))
          return true                                        -- proves the return value is ignored
        elseif clicks == 2 then
          ev:preventDefault()
          hafen.log():write(("041.3: click #2 at %d,%d btn=%d -- CONSUMED (preventDefault) -- your"
            .. " character should NOT have moved"):format(ev:x(), ev:y(), ev:button()))
        elseif clicks == 3 then
          hafen.log():write(("041.3: click #3 at %d,%d btn=%d -- PASSED THROUGH again -- click #2's"
            .. " preventDefault did not stick"):format(ev:x(), ev:y(), ev:button()))
          sub:off()                                          -- last (only) Sub on this key -> deafens the
          install()                                          -- engine listener (Subs.Idle) before re-install
        elseif clicks == 4 then
          hafen.log():write(("041.3: click #4 (after off()+re-on()) observed EXACTLY ONCE -- a second"
            .. " \"click #4\" line would mean the old engine listener leaked"))
          hafen.timer():after(0, function() if sub then sub:off() end end)
        end
      end)
    end
    install()
    hafen.timer():after(90, function() if sub then sub:off() end end)   -- safety net if never clicked
    manual = manual + 1
    hafen.log():write("[manual] click the map (character movement) FOUR times in a row -- expect the log to"
      .. " print, in order: click #1 PASSED THROUGH (character walks there), click #2 CONSUMED (character"
      .. " does NOT move), click #3 PASSED THROUGH again (character walks there), then click #4 observed"
      .. " EXACTLY ONCE with no second \"click #4\" line")
  else
    hafen.log():write("[manual] no MapView up (not in world?) -- log in and re-run :t041-3 for the click round")
    manual = manual + 1
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t041-3", run)   -- the only way in: a suite does not start itself
