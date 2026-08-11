-- 041.7 -- the close: a completeness sweep over the whole 041-unified-events roster. Self-checking suite;
-- see specs/testing/addon-suite.md. Run :t041-7
--
-- WHAT THIS TASK CLAIMS. Every earlier 041.* suite proves ONE emitter (or two). This one proves what none
-- of them can, because it is a property of the WHOLE roster: every emitter x key in the feature -- the 26
-- bus keys, the 5 universal widget keys, the 2 container keys, the 4 own-widget keys, the 5 control keys
-- over their 12 (builder, key) rows across all 16 builders, the 2 grab keys and the 7 mouse verbs -- exists
-- where EXAMPLES.md says and is refused BY NAME where it does not. Then the Retired completeness sweep in
-- one pass (the 4 On-prefixed lifecycle bus names, the 16 widget verbs, hafen.hook and its four verbs, and
-- the :mouse() table read), the cardinality invariants once more on a live tree, and a teardown leaving the
-- widget tree exactly where it started. Last, the two composite bus payloads this task itself objectifies --
-- GobOverlayAdded/GobOverlayRemoved's {gob, key, native} and the three *Clicked's {ghost/sprite/object,
-- button, x, y} -- both now LuaEvents, no member left readable with a dot.
--
-- WHAT IT DELIBERATELY DOES NOT RE-PROVE. Per TESTING.md's duplication rule, a claim belongs to the suite
-- that first makes it: the delivery semantics (registration order, the OR cancel rule, ev:preventDefault(),
-- ev:resend()/:rewrite(t), teardown deafening a native listener) are each 041.1-041.5's own premise,
-- asserted there. This sweep is existence and refusal, over the WHOLE roster at once -- a key that quietly
-- stopped answering somewhere, or started answering where it should not, is a completeness bug nothing else
-- in this feature would catch, since every earlier suite only ever looked at its own emitter.
--
-- THE ONE THING A PROGRAM CANNOT JUDGE. A real click on a world entity needs a human (D-017's sandbox has
-- no synthetic input, same as every earlier click round in this feature) -- so the three *Clicked composite
-- payloads' shape is asserted INSIDE the handler of one real click on a ghost this suite places, exactly
-- the pattern 041.2/041.3 already used for real input. The overlay half of the same claim fires on its own
-- (an addon's own gob:overlay():add/:remove is ungated and ordinary), so it is asserted immediately.
--
-- READ-ONLY: declares no permissions, mutates no persistent state. Everything it builds -- the 16 controls,
-- the own widget, the grab -- lives in one scratch window and is torn down before the summary prints; the
-- one ghost it places for the manual click is removed by teardown too, on a 90s safety net if never clicked.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function nop() end
local function isSub(v) return (type(v) == "userdata") and (type(v.off) == "function") end

-- How many widgets are in the whole client tree right now (040.13's own gauge, re-used for this feature's
-- own teardown claim).
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

local function findByType(w, t)
  if w:type() == t then return w end
  for _, c in ipairs(w:children()) do
    local found = findByType(c, t)
    if found then return found end
  end
  return nil
end

-- ================================================================ 1. THE BUS: all 26 keys, closed set
local LIFECYCLE = { "Load", "EnterWorld", "Update", "Disable" }
local UNCHANGED = {
  "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved",
  "MeterAdded", "MeterRemoved", "MeterChanged",
  "BuffAdded", "BuffRemoved", "BuffChanged",
  "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
  "KinChanged", "QuestAdded", "QuestDone", "MarkersChanged",
  "GhostClicked", "SpriteClicked", "ObjectClicked",
}

local function busSweep()
  local ok = 0
  for _, k in ipairs(LIFECYCLE) do
    local good, sub = pcall(function() return hafen.event():on(k, nop) end)
    if good and isSub(sub) then ok = ok + 1; sub:off() end
  end
  for _, k in ipairs(UNCHANGED) do
    local good, sub = pcall(function() return hafen.event():on(k, nop) end)
    if good and isSub(sub) then ok = ok + 1; sub:off() end
  end
  local total = #LIFECYCLE + #UNCHANGED
  check(ok == total, ("all %d bus keys answer :on(key, fn) with a Sub (%d lifecycle + %d unchanged, %d/%d)")
        :format(total, #LIFECYCLE, #UNCHANGED, ok, total), ok)
  refuses("an unknown bus key throws instead of being accepted and never firing",
          function() hafen.event():on("GobAdded ", nop) end, "unknown event 'GobAdded '")
end

-- ================================================================ 2. WIDGETS: universal / container / surface
local NATIVE_KEYS = { "MouseDown", "MouseUp", "MouseMove", "Wheel", "Destroy", "ItemAdded", "ItemRemoved" }
local SURFACE_ONLY = { "Draw", "Tick", "Drop", "Close" }

local function widgetSweep(own)
  local mapview = hafen.ui():find("@MapView")
  if mapview then
    local ok = 0
    for _, k in ipairs(NATIVE_KEYS) do
      local sub = mapview:on(k, nop)
      if isSub(sub) then ok = ok + 1; sub:off() end
    end
    check(ok == #NATIVE_KEYS,
          ("a NATIVE widget (found by selector) answers the %d universal+container keys (%d/%d)")
          :format(#NATIVE_KEYS, ok, #NATIVE_KEYS), ok)
    refuses("...and is refused BY NAME on a surface key it does not have (Draw)",
            function() mapview:on("Draw", nop) end, "MapView has no event 'Draw'")
  else
    check(false, "the map view is reachable by selector to prove the native half of the widget sweep",
          "not in world?")
  end

  local ok = 0
  local ALL = {}
  for _, k in ipairs(NATIVE_KEYS) do ALL[#ALL + 1] = k end
  for _, k in ipairs(SURFACE_ONLY) do ALL[#ALL + 1] = k end
  for _, k in ipairs(ALL) do
    local sub = own:on(k, nop)
    if isSub(sub) then ok = ok + 1; sub:off() end
  end
  check(ok == #ALL, ("an OWN widget answers all %d widget keys (%d universal+container + %d surface, %d/%d)")
        :format(#ALL, #NATIVE_KEYS, #SURFACE_ONLY, ok, #ALL), ok)
end

-- ================================================================ 3. CONTROLS: the 12 (builder, key) rows
local function buildControls(ui, hold)
  local y = 8
  local function place(w) w:parent(hold):position(4, y); y = y + 16; return w end
  local btn  = place(ui:button())
  local chk  = place(ui:check())
  local rad  = place(ui:radio():rows{"A", "B"})
  local sld  = place(ui:slider():range(0, 10))
  local sbar = place(ui:scrollbar():range(0, 10))
  local scr  = place(ui:scroll():size(60, 40))
  place(ui:widget():size(60, 200):parent(scr))         -- tall content, so the scroll bar comes alive
  local ent  = place(ui:entry())
  local lst  = place(ui:list():rows{"A", "B"})
  local ddn  = place(ui:dropdown():rows{"A", "B"})
  local menu = place(ui:menu():rows{"A", "B"})
  local grid = place(ui:grid():cell(24, 24):rows{"x"})
  local lbl  = place(ui:label():text("plain"))
  return {
    { name = "button",     w = btn,  key = "Pressed" },
    { name = "check",      w = chk,  key = "Changed" },
    { name = "radio",      w = rad,  key = "Changed" },
    { name = "slider",     w = sld,  key = "Changed" },
    { name = "scrollbar",  w = sbar, key = "Changed" },
    { name = "entry",      w = ent,  key = "Changed" },
    { name = "entry",      w = ent,  key = "Submitted" },
    { name = "list",       w = lst,  key = "Changed" },
    { name = "dropdown",   w = ddn,  key = "Changed" },
    { name = "menu",       w = menu, key = "Selected" },
    { name = "grid",       w = grid, key = "Cell" },
  }, lbl, scr, { btn = btn, chk = chk, ent = ent, menu = menu, grid = grid }
end

local function controlSweep(ui, hold)
  local rows, lbl, scr, handles = buildControls(ui, hold)
  local ok, bad = 0, nil
  for _, r in ipairs(rows) do
    if r.w == nil then
      bad = bad or (r.name .. ":" .. r.key .. " -- widget not found")
    else
      local sub = r.w:on(r.key, nop)
      if isSub(sub) then ok = ok + 1; sub:off() else bad = bad or (r.name .. ":" .. r.key) end
    end
  end
  check((ok == #rows) and (bad == nil),
        ("the control-key matrix: %d of the 12 (builder, key) rows answer :on(key, fn) with a Sub (the 11"
          .. " immediate ones; the scroll's own bar is checked separately below, once the tree has"
          .. " settled) (%d/%d)"):format(#rows, ok, #rows), bad)

  local CONTROL_KEYS = { "Pressed", "Changed", "Submitted", "Selected", "Cell" }
  local refOk = 0
  for _, k in ipairs(CONTROL_KEYS) do
    local okc, err = pcall(function() lbl:on(k, nop) end)
    err = okc and "<no error>" or tostring(err)
    if (not okc) and (err:find("Label has no event", 1, true) ~= nil) then refOk = refOk + 1 end
  end
  check(refOk == #CONTROL_KEYS,
        ("a Label -- none of the 5 control capabilities -- is refused BY NAME on every one of them (%d/%d)")
        :format(refOk, #CONTROL_KEYS), refOk)
  return scr, handles
end

-- The 12th (builder, key) row: hafen.ui():scroll()'s OWN widget has no verb of its own (040.13's own
-- roster already establishes that) -- it is the bar INSIDE it that answers "Changed", found the ordinary
-- way (docs/addons/api/ui/controls.md: "hafen.ui():all(\"@Scrollbar\") or sp:children()"). Checked apart
-- from the 11 above and a moment later, since a composite's child is a tree shape rather than a
-- same-statement return value.
local function scrollBarCheck(scr)
  local bar = scr and findByType(scr, "Scrollbar")
  if bar == nil then
    check(false, "the scroll's own bar (the 12th control-key row) is found inside it, as a Scrollbar child",
          "no Scrollbar child found")
    return
  end
  local sub = bar:on("Changed", nop)
  check(isSub(sub), "...and answers :on(\"Changed\", fn) with a Sub, same as a bare hafen.ui():scrollbar()",
        sub)
  if isSub(sub) then sub:off() end
end

-- ================================================================ 4. THE GRAB: 2 keys, closed set
local function grabSweep()
  local g = hafen.ui():mouse():grab()
  local moveSub, upSub = g:on("Move", nop), g:on("Up", nop)
  check(isSub(moveSub) and isSub(upSub) and (moveSub ~= upSub),
        "the grab answers :on(\"Move\", fn) and :on(\"Up\", fn), each with its own Sub")
  refuses("...and refuses a third key by name", function() g:on("Down", nop) end, "Move, Up")
  moveSub:off(); upSub:off()
  g:release()
end

-- ================================================================ 5. THE MOUSE: 7 verbs, table read gone
local function mouseSweep()
  local m = hafen.ui():mouse()
  local typesOk = (type(m.x) == "function") and (type(m.y) == "function")
  check(typesOk, "the old {x=,y=} table read is gone: .x/.y find the VERB now, not a number or nil",
        ("x=%s y=%s"):format(type(m.x), type(m.y)))
  local allOk = (type(m:x()) == "number") and (type(m:y()) == "number")
    and (type(m:shift()) == "boolean") and (type(m:ctrl()) == "boolean") and (type(m:alt()) == "boolean")
    and (type(m.grab) == "function")
  check(allOk, "the mouse answers all 7 verbs: :x() :y() :over() :shift() :ctrl() :alt() :grab()", allOk)
  local overW, atW = m:over(), hafen.ui():at(m:x(), m:y())
  check(overW == atW, ":over() still agrees with hafen.ui():at(m:x(), m:y())")
end

-- ================================================================ 6. THE RETIRED COMPLETENESS SWEEP
-- Every spelling this whole feature retired, in one pass: the 4 lifecycle bus names, the 16 widget verbs,
-- hafen.hook (bare, called, and all four of its old verb-attempts, which now all die at the SAME section-
-- level refusal since 041.5 deleted the section whole), and the :mouse() table read (asserted separately in
-- mouseSweep above, since it is a SHAPE change, not a throw -- there is no message to match here).
--
-- Every widget probe below rides an ALREADY-BUILT handle from the control matrix (handles.*) or the shared
-- `own` widget -- never a fresh hafen.ui():…() -- because a bare builder attaches under root immediately
-- (UiApi.attach), and one built here and never parented into `hold` would survive `hold:destroy()` and
-- read as a leak in the teardown check below.
local function buildRetired(handles, own)
  return {
    { "OnLoad",       function() hafen.event():on("OnLoad", nop) end,        "is now 'Load'" },
    { "OnEnterWorld", function() hafen.event():on("OnEnterWorld", nop) end,  "is now 'EnterWorld'" },
    { "OnUpdate",     function() hafen.event():on("OnUpdate", nop) end,      "is now 'Update'" },
    { "OnDisable",    function() hafen.event():on("OnDisable", nop) end,     "is now 'Disable'" },

    { "widget:onPress",       function() handles.btn:onPress(nop) end,       "widget:on(\"Pressed\", fn)" },
    { "widget:onChange",      function() handles.chk:onChange(nop) end,      "widget:on(\"Changed\", fn)" },
    { "widget:onSubmit",      function() handles.ent:onSubmit(nop) end,      "widget:on(\"Submitted\", fn)" },
    { "widget:onSelect",      function() handles.menu:onSelect(nop) end,     "widget:on(\"Selected\", fn)" },
    { "widget:onCell",        function() handles.grid:onCell(nop) end,       "widget:on(\"Cell\", fn)" },
    { "widget:onDraw",        function() own:onDraw(nop) end,                "widget:on(\"Draw\", fn)" },
    { "widget:onTick",        function() own:onTick(nop) end,                "widget:on(\"Tick\", fn)" },
    { "widget:onClick",       function() own:onClick(nop) end,               "widget:on(\"MouseDown\", fn)" },
    { "widget:onMouseUp",     function() own:onMouseUp(nop) end,             "widget:on(\"MouseUp\", fn)" },
    { "widget:onMouseMove",   function() own:onMouseMove(nop) end,           "widget:on(\"MouseMove\", fn)" },
    { "widget:onWheel",       function() own:onWheel(nop) end,               "widget:on(\"Wheel\", fn)" },
    { "widget:onDrop",        function() own:onDrop(nop) end,                "widget:on(\"Drop\", fn)" },
    { "widget:onClose",       function() own:onClose(nop) end,               "widget:on(\"Close\", fn)" },
    { "widget:onItemAdded",   function() own:onItemAdded(nop) end,           "widget:on(\"ItemAdded\", fn)" },
    { "widget:onItemRemoved", function() own:onItemRemoved(nop) end,         "widget:on(\"ItemRemoved\", fn)" },
    { "widget:onDestroy",     function() own:onDestroy(nop) end,             "widget:on(\"Destroy\", fn)" },

    { "hafen.hook",                 function() return hafen.hook end,                         "hafen.hook is gone" },
    { "hafen.hook()",                function() return hafen.hook() end,                       "hafen.hook is gone" },
    { "hafen.hook():input(...)",     function() hafen.hook():input("mapview", "mousedown", nop) end, "hafen.hook is gone" },
    { "hafen.hook():action(...)",    function() hafen.hook():action("click", nop) end,          "hafen.hook is gone" },
    { "hafen.hook():message(...)",   function() hafen.hook():message("set", nop) end,           "hafen.hook is gone" },
    { "hafen.hook():grab{...}",      function() hafen.hook():grab{ move = nop, up = nop } end,  "hafen.hook is gone" },
  }
end

local function retiredSweep(handles, own)
  local retired = buildRetired(handles, own)
  local ok, bad = 0, nil
  for _, r in ipairs(retired) do
    local good, err = pcall(r[2])
    if (not good) and (tostring(err):find(r[3], 1, true) ~= nil) then
      ok = ok + 1
    else
      bad = bad or (r[1] .. " -- " .. (good and "<no error>" or tostring(err)))
    end
  end
  check(ok == #retired,
        ("every retired spelling in the feature throws naming its replacement -- 4 lifecycle bus names + 16"
          .. " widget verbs + hafen.hook and its 4 verb-attempts (%d/%d)"):format(ok, #retired), bad)
end

-- ================================================================ 7. THE TWO COMPOSITE PAYLOADS THIS TASK SHIPS
-- GobOverlayAdded/GobOverlayRemoved: fires on the player's own gob, through the player's OWN attach --
-- ungated, ordinary, and entirely self-triggered (no human needed, unlike a world click).
local overlayAdded, overlayRemoved = nil, nil
local OVERLAY_KEY = "t041-7-mark"

local function armOverlay()
  local pg = hafen.player():gob()
  if pg == nil then return nil end
  local addSub = hafen.event():on("GobOverlayAdded", function(ev)
    if (ev:gob():id() == pg:id()) and (ev:key() == OVERLAY_KEY) then overlayAdded = ev end
  end)
  local remSub = hafen.event():on("GobOverlayRemoved", function(ev)
    if (ev:gob():id() == pg:id()) and (ev:key() == OVERLAY_KEY) then overlayRemoved = ev end
  end)
  pg:overlay():add(OVERLAY_KEY)
  return addSub, remSub, pg
end

local function checkOverlayAdded()
  if overlayAdded == nil then
    check(false, "GobOverlayAdded fires on an addon's own gob:overlay():add()", "never fired")
    return
  end
  local ev = overlayAdded
  check((ev:gob() ~= nil) and (ev:gob():id() == hafen.player():gob():id()) and (ev:key() == OVERLAY_KEY)
        and (ev:native() == false),
        "GobOverlayAdded's ev answers :gob() :key() :native() -- gob() is the owner's own handle, native()"
          .. " is false for an addon's own attach",
        ("gob=%s key=%s native=%s"):format(tostring(ev:gob()), tostring(ev:key()), tostring(ev:native())))
  check(type(ev.gob) == "function", "...and no member reads with a dot: ev.gob finds the VERB, not the Gob",
        type(ev.gob))
end

local function checkOverlayRemoved()
  check(overlayRemoved ~= nil, "GobOverlayRemoved fires on the matching gob:overlay():remove()",
        "never fired")
  if overlayRemoved ~= nil then
    check(type(overlayRemoved.key) == "function", "...and it answers the same shape, dot-free",
          type(overlayRemoved.key))
  end
end

-- GhostClicked/SpriteClicked/ObjectClicked: needs a real click (D-017's sandbox has no synthetic input),
-- so the shape assertion rides inside the one real click the [manual] line below asks for -- the same
-- pattern 041.2/041.3 already used for a gesture nothing here can fake.
local clickGhost, clickSub

local function armClickableGhost()
  local pg = hafen.player():gob()
  local p = pg and pg:position()
  if p == nil then return end
  clickGhost = hafen.ghost():add("gfx/terobjs/arch/logcabin", p:offset(22, 0)):alpha(0.6):clickable(true)
  clickSub = hafen.event():on("GhostClicked", function(ev)
    check(ev:ghost() == clickGhost, "GhostClicked's ev:ghost() is THIS suite's ghost handle",
          tostring(ev:ghost()))
    check((ev:sprite() == nil) and (ev:object() == nil),
          "...and the two nouns that do NOT apply read nil rather than throwing (Shape.INPUT's own rule)",
          ("sprite=%s object=%s"):format(tostring(ev:sprite()), tostring(ev:object())))
    check((type(ev:button()) == "number") and (type(ev:x()) == "number") and (type(ev:y()) == "number"),
          "...and :button() :x() :y() all answer numbers",
          ("button=%s x=%s y=%s"):format(tostring(ev:button()), tostring(ev:x()), tostring(ev:y())))
    check(type(ev.ghost) == "function", "...and no member reads with a dot: ev.ghost finds the VERB",
          type(ev.ghost))
    if clickSub then clickSub:off() end
    if clickGhost then hafen.ghost():remove(clickGhost); clickGhost = nil end
  end)
  hafen.timer():after(90, function()          -- safety net if never clicked
    if clickSub then clickSub:off(); clickSub = nil end
    if clickGhost then hafen.ghost():remove(clickGhost); clickGhost = nil end
  end)
end

-- ================================================================ 8. CARDINALITY, ONCE MORE ON A LIVE TREE
local seenA, seenB = false, false
local cardSubA, cardSubB

local function armCardinality()
  cardSubA = hafen.event():on("Update", function() seenA = true end)
  cardSubB = hafen.event():on("Update", function() seenB = true end)
end

-- Split in two, each judged after its own frame has had time to pass -- see the run()'s own timer chain,
-- which is what actually spaces them (there is no "wait one frame" primitive here; a short timer stands in,
-- the same way 041.1's own delivery round did).
local function cardinalityPart1()
  check(seenA and seenB, "cardinality, once more: two handlers on one bus key both fire",
        ("a=%s b=%s"):format(tostring(seenA), tostring(seenB)))
  cardSubA:off()
  seenA, seenB = false, false
end

local function cardinalityPart2()
  check((not seenA) and seenB, "off() on the first leaves the second still firing",
        ("a=%s b=%s"):format(tostring(seenA), tostring(seenB)))
  cardSubB:off()
end

-- ================================================================ the run
local function run()
  pass, fail, manual = 0, 0, 0
  overlayAdded, overlayRemoved = nil, nil
  seenA, seenB = false, false

  local ui = hafen.ui()
  local base = treeCount()
  local hold = ui:window():title("041.7 -- scratch"):size(260, 340):position(460, 40)
  local own = ui:widget():size(20, 20):parent(hold):position(4, 300)

  busSweep()
  widgetSweep(own)
  local scr, handles = controlSweep(ui, hold)
  grabSweep()
  mouseSweep()
  retiredSweep(handles, own)

  local addSub, remSub, pg = armOverlay()
  armCardinality()
  armClickableGhost()

  if pg == nil then
    check(false, "hafen.player():gob() is up, to prove the overlay half of the composite-payload claim",
          "not in world?")
  end

  hafen.timer():after(0.3, function()
    if pg ~= nil then
      checkOverlayAdded()
      pg:overlay():remove(OVERLAY_KEY)
    end
    hafen.timer():after(0.3, function()
      if pg ~= nil then
        checkOverlayRemoved()
        if addSub then addSub:off() end
        if remSub then remSub:off() end
      end

      cardinalityPart1()

      hafen.timer():after(0.2, function()
        cardinalityPart2()
        scrollBarCheck(scr)

        hold:destroy()
        local after = treeCount()
        check(after == base,
              "destroying the scratch window gives back a tree with no control left in it -- the whole"
                .. " roster, gone in one destroy", ("base=%d after=%d"):format(base, after))

        manualCheck("click the translucent log cabin ghost this suite placed a couple of tiles east of you",
                    "in the log a GhostClicked round with 4 [pass] lines: ev:ghost() is the handle, "
                      .. "ev:sprite()/ev:object() read nil, :button()/:x()/:y() answer numbers, and ev.ghost "
                      .. "finds the verb, not a value")

        hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      end)
    end)
  end)
end

hafen.slash():register("t041-7", run)   -- the only way in: a suite does not start itself
