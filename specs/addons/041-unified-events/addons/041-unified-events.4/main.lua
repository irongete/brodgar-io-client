-- 041.4 — The remaining widget keys. Self-checking suite; see specs/addons/TESTING.md. Run :t041-4
--
-- WHAT THIS TASK CLAIMS. The other 12 retired verbs -- a control's own Pressed/Changed/Submitted/Selected/Cell,
-- a surface's Draw/Tick/Drop/Close, and ItemAdded/ItemRemoved/Destroy on any widget -- all answer through the
-- ONE door 041.3 opened, widget:on(key, fn), over the widget's own WidgetSubs, never a stored slot. The
-- vocabulary a widget answers is WIDGET-SPECIFIC (a Button has "Pressed", a Label does not) and computed fresh
-- each call, so an unknown key throws naming exactly what THIS widget answers. Every one of the 12 old verbs
-- throws naming its replacement, the READ half included (a subscription is not a property). A programmatic
-- :value(v) still does not fire Changed (D-153, unaffected by where the firing goes). Draw/Cell/Tick/Destroy
-- need no user input to prove -- they fire every frame (or on a destroy this suite causes itself) -- so they are
-- asserted for real, through a short timer delay, rather than described.
--
-- THE ONE THING A PROGRAM CANNOT JUDGE. Pressed/Changed/Submitted/Selected need a real click/keystroke/drag
-- (D-017's sandbox has no synthetic input, same as 041.3) -- that a control's own click path fires them at all
-- was 040's proof, not this task's; what this task changes is only WHERE that fire lands (Subs, not a slot),
-- which the registration + refusal checks below verify without needing the click. The one thing left to the eye
-- is two Draw handlers actually painting two visibly different things on screen, in the single [manual] line.
--
-- READ-ONLY: declares no permissions, mutates no persistent state. Every widget this suite builds is destroyed
-- before (or a few hundred ms after) the summary prints.

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
local function isSub(v) return (type(v) == "userdata") and (type(v.off) == "function") end

local function run()
  -- 1. REGISTRATION: one representative control per new key, each answering :on(key, fn) with a Sub -- and a
  --    Label (which implements none of the five control capabilities) refused by name on every one of them,
  --    listing exactly what a Label DOES answer (the universal five, computed fresh -- widgetKeys, LuaWidget).
  local btn  = hafen.ui():button():text("go")
  local lbl  = hafen.ui():label():text("plain")
  local chk  = hafen.ui():check()
  local ent  = hafen.ui():entry()
  local menu = hafen.ui():menu():rows{"A", "B"}
  local grid = hafen.ui():grid():cell(24, 24):rows{"x", "y"}

  local LBL_KEYS = "MouseDown, MouseUp, MouseMove, Wheel, Destroy"
  local REG = {
    { btn,  "Pressed",   "a Button" },
    { chk,  "Changed",   "a CheckBox" },
    { ent,  "Submitted", "a TextEntry" },
    { menu, "Selected",  "a SListMenu" },
    { grid, "Cell",      "a GridList" },
  }
  local regOk, refOk = 0, 0
  for _, row in ipairs(REG) do
    local w, key = row[1], row[2]
    if isSub(w:on(key, nop)) then regOk = regOk + 1 end
    local ok, err = pcall(function() lbl:on(key, nop) end)
    err = ok and "<no error>" or tostring(err)
    if (not ok) and (err:find(LBL_KEYS, 1, true) ~= nil) then refOk = refOk + 1 end
  end
  check(regOk == #REG,
        ("each new control key answers :on(key, fn) with a Sub on the control that has it (%d/%d)")
        :format(regOk, #REG), regOk)
  check(refOk == #REG,
        ("...and every one of them is refused BY NAME on a Label, which has none of the five"
         .. " (listing \"%s\", %d/%d)"):format(LBL_KEYS, refOk, #REG), refOk)

  -- 2. N SUBSCRIBERS: two handlers on one control key get distinct Subs -- not one slot silently replaced.
  local subA, subB = btn:on("Pressed", nop), btn:on("Pressed", nop)
  check((subA ~= subB) and isSub(subA) and isSub(subB),
        "two handlers on one control key are two Subs, not one slot replaced", tostring(subA == subB))
  subA:off(); subB:off()

  -- 3. THE READ IS GONE TOO: a subscription is not a property, so the old ZERO-arg form throws the exact same
  --    way the write does -- there is no "reads nil" step in between (btn:onPress reads the metatable's
  --    __index once, at the dot, before arity is ever asked).
  refuses("btn:onPress() -- the READ, no function -- is gone too (a subscription is not a property)",
          function() return btn:onPress() end, "widget:on(\"Pressed\", fn)")

  -- 4. ALL 12 RETIRED VERBS throw naming widget:on(key, fn) -- one line, aggregated (039/041's own style for a
  --    roster this size). Five control notifications + the four surface keys + the three poll keys.
  local RETIRED = {
    { btn,  "onPress",       "Pressed" },
    { chk,  "onChange",      "Changed" },
    { ent,  "onSubmit",      "Submitted" },
    { menu, "onSelect",      "Selected" },
    { grid, "onCell",        "Cell" },
    { btn,  "onDraw",        "Draw" },
    { btn,  "onTick",        "Tick" },
    { btn,  "onDrop",        "Drop" },
    { btn,  "onClose",       "Close" },
    { btn,  "onItemAdded",   "ItemAdded" },
    { btn,  "onItemRemoved", "ItemRemoved" },
    { btn,  "onDestroy",     "Destroy" },
  }
  local retiredOk = 0
  for _, row in ipairs(RETIRED) do
    local w, verb, key = row[1], row[2], row[3]
    local ok, err = pcall(function() return w[verb](w, nop) end)
    err = ok and "<no error>" or tostring(err)
    if (not ok) and (err:find(("widget:on(\"%s\", fn)"):format(key), 1, true) ~= nil) then
      retiredOk = retiredOk + 1
    end
  end
  check(retiredOk == #RETIRED,
        ("all %d retired verbs throw naming widget:on(key, fn) (%d/%d)"):format(#RETIRED, retiredOk, #RETIRED),
        retiredOk)

  -- 5. D-153, RE-ASSERTED HERE: a programmatic :value(v) still does not fire Changed, whatever mechanism the
  --    fire itself now goes through (Subs, not a stored slot) -- unaffected by this task, worth re-checking on
  --    the new door.
  local changedFired = false
  local vsub = chk:on("Changed", function() changedFired = true end)
  chk:value(true)
  check(not changedFired, "a programmatic :value(v) still does NOT fire Changed (D-153)", changedFired)
  vsub:off()

  btn:destroy(); lbl:destroy(); chk:destroy(); ent:destroy(); menu:destroy(); grid:destroy()

  -- 6. DRAW/CELL/TICK/DESTROY need no user input to prove -- they fire on their own (every frame, or on a
  --    destroy this suite triggers itself) -- so a short timer delay lets them actually happen, for real.
  local dw = hafen.ui():widget():size(40, 40):position(20, 20)
  local drawA, drawB, stashedEv = false, false, nil
  dw:on("Draw", function(ev) drawA = true; stashedEv = stashedEv or ev end)
  dw:on("Draw", function(ev) drawB = true end)
  local tickFired, tickDt = false, nil
  dw:on("Tick", function(dt) tickFired = true; tickDt = dt end)

  local cgrid = hafen.ui():grid():cell(24, 24):rows{"only"}
  local cellFired, cellEv = false, nil
  cgrid:on("Cell", function(ev) cellFired = true; cellEv = cellEv or ev end)

  local dying = hafen.ui():widget():size(4, 4)
  local destroyFired = false
  dying:on("Destroy", function() destroyFired = true end)
  dying:destroy()

  -- hafen.ui():window()'s Lua handle interns on the CHROME (UiApi.attach reads Owned:rootw()), one level above
  -- the content AddonWidget that actually draws/ticks -- a DIFFERENT widget than the bare hafen.ui():widget()
  -- case above, so Draw needs proving here too (this is the exact shape the [manual] demo below also uses).
  local win = hafen.ui():window():title("t041-4"):size(20, 20):position(4, 4)
  local winDrawFired = false
  win:on("Draw", function(ev) winDrawFired = true end)

  hafen.timer():after(0.3, function()
    check(drawA and drawB, "two Draw handlers on one widget BOTH fire (N subscribers, for real)",
          tostring(drawA) .. "/" .. tostring(drawB))
    if stashedEv then
      check((stashedEv:w() == 40) and (stashedEv:h() == 40) and (stashedEv:g() ~= nil),
            "Draw's ev answers :g()/:w()/:h()", stashedEv:w() .. "x" .. stashedEv:h())
      refuses("Draw's ev throws on :preventDefault() (uncancelable, D-125's closed shape)",
              function() stashedEv:preventDefault() end, "draw event answers")
      local inertOk = pcall(function() stashedEv:g():rect(0, 0, 1, 1) end)
      check(inertOk, "a stashed Draw ev is inert after the callback (its g:xxx calls are a silent no-op)", inertOk)
    else
      check(false, "Draw fired at least once", "never fired")
    end
    check(tickFired and (type(tickDt) == "number"), "Tick fires with a delta number", tostring(tickDt))
    if cellEv then
      check((cellEv:w() == 24) and (cellEv:h() == 24) and (cellEv:item() ~= nil) and (cellEv:g() ~= nil),
            "Cell's ev answers :g()/:item()/:w()/:h()", tostring(cellEv:item()))
    else
      check(false, "Cell fired at least once", "never fired")
    end
    check(destroyFired, "Destroy fires when an owned widget is destroyed (any widget, not just a container)",
          destroyFired)
    check(winDrawFired, "Draw ALSO fires on a hafen.ui():window() (the chrome handle, not just a bare widget)",
          winDrawFired)

    dw:destroy(); cgrid:destroy(); win:destroy()

    manual = manual + 1
    hafen.log():write("[manual] a small window titled '041.4 Draw demo' with TWO Draw handlers, one drawing a"
      .. " red square and one drawing a blue square overlapping it -- expect BOTH colours visible for ~3s"
      .. " (this suite already proved both handlers fire; this is the eyeball confirmation)")
    -- a short-lived visible demo for the manual line above, destroyed on its own
    local demo = hafen.ui():window():title("041.4 Draw demo"):size(80, 80):position(300, 300)
    demo:on("Draw", function(ev) local g = ev:g(); g:color(200, 60, 60); g:frect(4, 4, 32, 32); g:color() end)
    demo:on("Draw", function(ev) local g = ev:g(); g:color(60, 120, 220); g:frect(28, 28, 32, 32); g:color() end)
    hafen.timer():after(3, function() if demo:exists() then demo:destroy() end end)

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t041-4", run)   -- the only way in: a suite does not start itself
