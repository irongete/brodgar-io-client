-- 044.8 -- docs, the example addon, and the close. Self-checking suite; see specs/testing/addon-suite.md and
-- specs/044-spatial-ui/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. The feature is written down, and what is written down is true. A documentation task
-- has exactly one kind of defect a program can catch -- a page that describes a surface the client does not
-- have, or describes it wrongly -- so this suite reads every claim the new pages make back out of the live
-- API: the fourth collection and the three verbs only a standing widget has, the three facing modes on a
-- widget AND on a sprite, the screen/pointer pair the input section calls exact inverses, the tooltip and
-- focus reads the Widget page gained, the four keys of the standing-surface counter, and the refusals each
-- page names. A claim nothing here can reach is a claim the page should not be making.
--
-- AND THE EXAMPLE ADDON'S COMPOSITION. The `cupboard` example is three ordinary things in a row: a window
-- stands on the object it belongs to; the server destroys it, and the panel ends WITH ITS CONTENT; a panel of
-- the addon's own stands in its place; the window comes back and they swap again. A suite cannot make a server
-- close a window, so it drives that same sequence with widgets it built itself -- the very same bodies -- and
-- the real thing, on a real cupboard, is the [manual] line.
--
-- AND THE ONE ENDING WITH NOTHING TO PUT BACK. A panel's content goes back where it stood from on every
-- ending but one: a widget that is DYING has nowhere to go. That is not the same question as "is it still in
-- the surface", because a window announces its removal at the START of its fade -- which is the path the
-- server's own destroy takes -- so at that moment it is a perfectly ordinary child. Putting it back then left
-- a dead, empty window on the flat UI that no teardown owned and a ':reload' could not clear. It bites
-- through every door, not just the drain: re-click an open cupboard and the server closes and reopens its
-- window, so the new one's `appear` arrives while the old one is still mid-fade and the addon's own :remove
-- runs the ordinary put-back -- ten clicks, ten dead windows. The flag is therefore set at the removal TAP,
-- where "it is on its way out" becomes true for :remove, :reload, disable and the drain at once. A program
-- cannot make a server close a window, so the drivable half is asserted below and the fade is a [manual].
--
-- AND THE WALK OUT OF A PANEL. Standing re-homes a widget into a surface hanging off the root, so a client
-- widget asking the tree for the HUD it belongs to used to reach the root without ever passing it -- which
-- made an inventory's shift-wheel transfer throw, and quietly cost an item its contents window and the
-- equipory its slot hints. The walk now crosses to where the standing widget stood from. A program cannot
-- hold SHIFT down, so the transfer itself is a [manual] line; what IS asserted here is the client's own
-- container standing with every thread to the server intact and the wheel reaching it in the world.
--
-- HOW IT IS DRIVEN. hafen.vr():pointer(key, x, y [, a]) enters the client's own input path at a screen point
-- and widget:screen(x, y) is its exact inverse, so no click hardware is needed. Every panel stands on the
-- PLAYER'S OWN gob, lifted clear of the ground with :offset(0, 0, z) -- which is both the arrangement the new
-- page recommends for a camera-facing quad and the one place on screen that is always in view, so a check
-- never depends on where the camera happens to be pointing.
--
-- It re-asserts its own premises -- standing takes a widget off the flat UI's hit test, a poke lands on the
-- panel it is aimed at, the collection answers -- because a suite is read alone and must convince alone.
--
-- IT LEAVES ITS PANELS STANDING for the [manual] lines and takes them down on ':t044-8 off'. ':t044-8' at any
-- time starts over from an empty scene.
--
-- READ-ONLY: no permissions, no persistent state; every widget it stands is one it built itself.

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
local function refuses(fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local good = not ok
  for _, want in ipairs({ ... }) do
    if err:find(want, 1, true) == nil then good = false end
  end
  return good, err
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local icon                                -- this suite's own image (hafen.asset, loaded once at Load)
hafen.event():on("Load", function()
  local ok, h = pcall(function() return hafen.asset():get("icon.png") end)
  icon = ok and h or nil
end)

local S                                   -- everything a run is holding

local function counters() return hafen.client():profiling():surfaces() end

-- Put the pointer on whatever stands at the SCREEN point panel pixel (wx, wy) is drawn at.
local function poke(e, wx, wy, key, arg)
  local sx, sy = e:screen(wx, wy)
  if sx == nil then return nil end
  if arg == nil then return hafen.vr():pointer(key, sx, sy) end
  return hafen.vr():pointer(key, sx, sy, arg)
end

-- A press and its release, never held apart: a Button takes a UI grab on its press and does not test
-- coordinates on the way in, so a press left outstanding becomes a client-wide interceptor.
local function click(e, wx, wy)
  local took = poke(e, wx, wy, "MouseDown", 1)
  poke(e, wx, wy, "MouseUp", 1)
  return took
end

local function within(hit, w)
  for _ = 1, 16 do
    if hit == nil then return false end
    if hit == w then return true end
    hit = hit:parent()
  end
  return false
end

-- How many of nine points spread over a widget's SCREEN rectangle still reach it down the flat UI's own hit
-- test. Taken before it stands, and again after: standing must take it to zero.
local function rect(w)
  local p, s = w:rootPos(), w:size()
  if (p == nil) or (s == nil) then return {} end
  local pts = {}
  for _, fx in ipairs({ 0.2, 0.5, 0.8 }) do
    for _, fy in ipairs({ 0.2, 0.5, 0.8 }) do
      pts[#pts + 1] = { math.floor(p.x + (s.x * fx)), math.floor(p.y + (s.y * fy)) }
    end
  end
  return pts
end

local function reachable(pts, w)
  local n = 0
  for _, q in ipairs(pts) do
    if within(hafen.ui():at(q[1], q[2]), w) then n = n + 1 end
  end
  return n
end

-- Where a child sits inside a WINDOW, in the window's own OUTER pixels -- which is what a surface pixel is.
-- A window's chrome inset belongs to the client and no verb converts it, so it is MEASURED down the flat UI's
-- own hit test while the window is still on the flat UI.
local function childRect(win, kid)
  local wp, ws = win:rootPos(), win:size()
  if (wp == nil) or (ws == nil) or (kid == nil) then return nil end
  local my
  for _, f in ipairs({ 0.5, 0.3, 0.7, 0.2, 0.8 }) do
    local cx, y0, y1 = wp.x + math.floor(ws.x * f), nil, nil
    for y = 0, ws.y - 1 do
      if within(hafen.ui():at(cx, wp.y + y), kid) then y0 = y0 or y; y1 = y end
    end
    if y0 ~= nil then my = wp.y + math.floor((y0 + y1) / 2); break end
  end
  if my == nil then return nil end
  local x0, x1
  for x = 0, ws.x - 1 do
    if within(hafen.ui():at(wp.x + x, my), kid) then x0 = x0 or x; x1 = x end
  end
  if x0 == nil then return nil end
  local mx, y0, y1 = wp.x + math.floor((x0 + x1) / 2), nil, nil
  for y = 0, ws.y - 1 do
    if within(hafen.ui():at(mx, wp.y + y), kid) then y0 = y0 or y; y1 = y end
  end
  if y0 == nil then return nil end
  return { x = x0 + math.floor(((x1 - x0) + 1) / 2), y = y0 + math.floor(((y1 - y0) + 1) / 2) }
end

local function listed(coll, h)
  for _, e in ipairs(coll:list()) do
    if e == h then return true end
  end
  return false
end

local function clear()
  local c = hafen.vr():widget()
  for _, e in ipairs(c:list()) do c:remove(e) end
  for _, e in ipairs(hafen.vr():sprite():list()) do hafen.vr():sprite():remove(e) end
  if S then
    for _, w in ipairs(S.built) do
      if w and w:exists() then w:destroy() end
    end
  end
  S = nil
end

local phase2, phase3, phase4, offRound

local function run(args)
  if args and (args[1] == "off") then return offRound() end
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it stands panels in the 3D scene and puts the pointer on them)",
          tostring(okp and me))
    return summary()
  end
  clear()
  S = { built = {}, downs = {}, pressed = 0 }

  -- THE PANEL every read is asked of: a real window with a button carrying a tooltip and a text entry, so
  -- the click, the tooltip and the focus checks are all about the same panel standing in the same place.
  local panel = hafen.ui():window():title("044.8 panel"):size(220, 130):position(300, 160)
  local btn = hafen.ui():button():parent(panel):position(10, 60):size(120, 26):text("Sort")
                    :tooltip("044.8 world tip")
  local entry = hafen.ui():entry():parent(panel):position(10, 10):size(170, 20):value("click me")
  btn:on("Pressed", function() S.pressed = S.pressed + 1 end)
  panel:on("MouseDown", function(ev) S.downs[#S.downs + 1] = { x = ev:x(), y = ev:y() } end)
  S.panel, S.btn, S.entry = panel, btn, entry
  S.built[#S.built + 1] = panel

  -- ...and a second, EMPTY one, whose only job is to sit still: the uploads-against-frames reading only
  -- means anything on a panel with nothing in it that can change.
  local idle = hafen.ui():window():title("044.8 idle"):size(120, 50):position(300, 330)
  S.idle = idle
  S.built[#S.built + 1] = idle

  -- Measured on the flat UI, while these windows are still there to measure on.
  S.brect, S.erect = childRect(panel, btn), childRect(panel, entry)
  S.pRect = rect(panel)
  S.seen0 = reachable(S.pRect, panel)

  -- Standing on the PLAYER'S OWN gob, lifted clear of the ground: the gob anchor and the :offset(0, 0, z)
  -- lift the new page recommends for a camera-facing quad, and the one anchor that is always on screen.
  S.ent = hafen.vr():widget():add(panel, me):facing("camera"):offset(0, 0, 22)
  S.idleEnt = hafen.vr():widget():add(idle, me):facing("camera"):offset(0, -20, 8)
  hafen.timer():after(1.5, function() phase2() end)
end

phase2 = function()
  if not (S and S.ent and S.ent:exists() and S.idleEnt:exists()) then
    check(false, "the standing panels survived the frames they needed to be drawn once", "gone")
    if S then clear() end
    return summary()
  end
  local c, panel = hafen.vr():widget(), S.panel

  -- ---- 1. the collection, the entity's own reads, and the premise everything below rests on -------------
  local host = panel:parent()
  check((c == hafen.vr():widget()) and listed(c, S.ent) and (c:count() == 2)
        and (c:find("044.8 panel") == S.ent) and (S.ent:widget() == panel) and (S.ent:facing() == "camera")
        and panel:exists() and (host ~= nil) and (S.seen0 > 0) and (reachable(S.pRect, panel) == 0),
        "the fourth collection stands a widget on a game object and answers for it: the section hands back"
        .. " the same collection every call, :list() holds the entity, :count() counts it, a string filter"
        .. " finds it by the window's CAPTION, :widget() is the very widget that was passed in and :facing()"
        .. " reads back the mode it was given -- while the widget itself is still in the tree and still"
        .. " :exists(), and is gone from the flat UI's hit test, which is what 'it is drawn in the world"
        .. " instead' has to mean",
        ("count=%d find=%s :widget()==panel=%s facing=%s host=%s flat-reachable %d->%d of 9"):format(
          c:count(), tostring(c:find("044.8 panel") == S.ent), tostring(S.ent:widget() == panel),
          tostring(S.ent:facing()), host and host:type() or "nil", S.seen0, reachable(S.pRect, panel)))

  -- ---- 2. the counter page's four keys ------------------------------------------------------------------
  local ct = counters()
  check((type(ct.live) == "number") and (type(ct.culled) == "number") and (type(ct.uploads) == "number")
        and (type(ct.frames) == "number") and (ct.live >= c:count()) and (ct.culled >= 0),
        "the standing-surface counter answers the four keys its page names -- live, culled, uploads and"
        .. " frames -- and live counts every surface in the client rather than only this addon's, so the two"
        .. " panels this suite is holding are inside it",
        ("live=%s culled=%s uploads=%s frames=%s, this addon's own=%d"):format(tostring(ct.live),
          tostring(ct.culled), tostring(ct.uploads), tostring(ct.frames), c:count()))

  if (S.brect == nil) or (S.erect == nil) then
    check(false, "the button and the text entry were measurable inside the window on the flat UI, which"
          .. " every point below is aimed by", ("button=%s entry=%s"):format(tostring(S.brect ~= nil),
          tostring(S.erect ~= nil)))
    return summary()
  end

  -- ---- 3. the screen/pointer pair, asserted NUMERICALLY -------------------------------------------------
  -- A SURFACE pixel -- the standing window's own outer coordinates, chrome included -- in the lower half,
  -- clear of the entry, the button and the title bar, because a press on the last of those would start the
  -- client's own drag and this check is about coordinates.
  local px, py = 180, 118
  S.downs = {}
  local took = poke(S.ent, px, py, "MouseDown", 3)
  poke(S.ent, px, py, "MouseUp", 3)
  local d = S.downs[#S.downs]
  check((took == true) and (d ~= nil) and (math.abs(d.x - px) <= 2) and (math.abs(d.y - py) <= 2),
        ("the two directions are exact inverses: widget:screen(%d, %d) says where that panel pixel is drawn,"
         .. " a pointer put on that screen point is taken by the panel, and it arrives as the widget's OWN"
         .. " MouseDown at the pixel it was aimed at -- so 'where is my button on screen' and 'what did the"
         .. " player click' are read off one map and cannot disagree"):format(px, py),
        ("took=%s, MouseDown at %s,%s (aimed at %d,%d)"):format(tostring(took),
          d and tostring(d.x) or "nil", d and tostring(d.y) or "nil", px, py))

  -- ---- 4. ...and a control on the panel runs its own callback -------------------------------------------
  local was = S.pressed
  click(S.ent, S.brect.x, S.brect.y)
  check(S.pressed == (was + 1),
        "a button standing in the world runs the very callback it ran on screen: the click reaches the"
        .. " control as a control, not as 'the panel was clicked', which is the whole of what the"
        .. " transparency rule promises about the code you already wrote",
        ("Pressed fired %d time(s)"):format(S.pressed - was))

  -- ---- 5. a pointer on nothing, and a key that is not one of the four ------------------------------------
  local miss = hafen.vr():pointer("MouseMove", 0, 0)
  local okKey, errKey = refuses(function() hafen.vr():pointer("Klick", 10, 10) end,
                                "MouseDown", "MouseUp", "MouseMove", "Wheel")
  check((miss == false) and okKey,
        "a pointer put where no panel is standing hands back false -- which is the moment the client's own"
        .. " world click goes on exactly as it always did -- and a key that is not one of the four a widget"
        .. " answers to is refused naming all four",
        ("pointer on nothing=%s; bad key -> %s"):format(tostring(miss), errKey))

  -- ---- 6. the refusal the page is built around ----------------------------------------------------------
  local okClick, errClick = refuses(function() S.ent:onClick(function() end) end, "MouseDown", "clickable")
  check(okClick,
        "widget:onClick(fn) is refused on a standing widget, naming the widget's own MouseDown instead: the"
        .. " other three kinds are pictures, so 'it was clicked' is all they have to say, and two ways to"
        .. " hear about one click would be exactly the dual API this section refuses",
        errClick)

  -- ---- 7. the tooltip reads --------------------------------------------------------------------------
  local tsx, tsy = S.ent:screen(S.brect.x, S.brect.y)
  local tw = (tsx ~= nil) and hafen.ui():tipAt(tsx, tsy) or nil
  S.btn:tooltip("044.8 rewritten")
  local rewritten = S.btn:tooltip()
  S.btn:tooltip("044.8 world tip")
  check((tw ~= nil) and (tw == S.btn) and (tw:tooltip() == "044.8 world tip")
        and (rewritten == "044.8 rewritten"),
        "the tooltip reads answer on a panel in the world: asked at the screen point that button is drawn"
        .. " at, the client says which widget would speak for the point and that widget's own line comes"
        .. " back -- and writing the line on a control you built round-trips through the same read",
        ("tipAt -> %s, tooltip=%s, rewrite read back as %s"):format(tw and tw:type() or "nil",
          tw and tostring(tw:tooltip()) or "nil", tostring(rewritten)))

  -- ---- 8. focus follows a click into the world -----------------------------------------------------------
  click(S.ent, S.erect.x, S.erect.y)
  check((S.entry:focused() == true) and (S.panel:focused() == true) and (S.idle:focused() == false),
        "clicking a text entry standing in the world gives it the keyboard, and being focused is a property"
        .. " of the PATH rather than of one widget: the entry answers true and so does the window around it,"
        .. " because the keystroke passes through on the way -- while a second panel in the world answers"
        .. " false, which is what says focus moved rather than spread",
        ("entry=%s window=%s other panel=%s"):format(tostring(S.entry:focused()),
          tostring(S.panel:focused()), tostring(S.idle:focused())))

  -- ---- 9. three modes, on a widget and on a sprite -- LAST, because writing it rebuilds the visual --------
  S.ent:facing("fixed"); local m1 = S.ent:facing()
  S.ent:facing("screen"); local m2 = S.ent:facing()
  S.ent:facing("camera"); local m3 = S.ent:facing()
  local okMode, errMode = refuses(function() S.ent:facing("flat") end, "fixed", "camera", "screen")
  local sp = icon and hafen.vr():sprite():add(icon, hafen.player():gob()) or nil
  if sp then sp:facing("camera"):offset(0, 20, 8) end
  check((m1 == "fixed") and (m2 == "screen") and (m3 == "camera") and okMode
        and (sp ~= nil) and (sp:facing() == "camera"),
        "all three facing modes read back on a standing widget and a fourth is refused naming them, and the"
        .. " camera-facing mode reaches a SPRITE as well -- the two kinds share the entity core, so the mode"
        .. " is a property of a flat thing in the world rather than a feature of panels. Writing it rebuilds"
        .. " the visual and leaves the surface alone, which is why the panel is still the same panel here",
        ("widget %s/%s/%s, refusal -> %s, sprite=%s"):format(tostring(m1), tostring(m2), tostring(m3),
          errMode, sp and tostring(sp:facing()) or "no image handle"))

  S.base = counters()
  hafen.timer():after(1.2, function() phase3() end)
end

phase3 = function()
  if not (S and S.idleEnt and S.idleEnt:exists()) then
    check(false, "the idle panel was still standing when its measurement window closed", "gone")
    if S then clear() end
    return summary()
  end

  -- ---- 10. uploads and frames, read the way the page says to read them -----------------------------------
  local ct = counters()
  local du, df = ct.uploads - S.base.uploads, ct.frames - S.base.frames
  check((df >= 10) and (du < df),
        "uploads and frames are cumulative and mean something as a DELTA between two reads: over the window"
        .. " just closed the frames climbed while the uploads did not keep up with them, which is the whole"
        .. " cost claim -- a panel is repainted when its content changed and not because another frame went"
        .. " by",
        ("uploads +%d over frames +%d"):format(du, df))

  -- ---- 11. the composition the example addon is -----------------------------------------------------------
  -- A window stands; its content is destroyed (which is what the server closing a container window does) and
  -- the entity ends WITH it; a panel of your own stands in its place; taking that one back puts the widget
  -- where it stood from. Three ordinary calls, and they are the whole of the example.
  local c, me = hafen.vr():widget(), hafen.player():gob()
  local first = hafen.ui():window():title("044.8 native stand-in"):size(120, 50)
  local e1 = c:add(first, me)
  local up = c:count()
  first:destroy()
  local spare = hafen.ui():widget():size(90, 40)
  S.built[#S.built + 1] = spare
  local sParent, sPos = spare:parent(), spare:position()
  hafen.timer():after(0.5, function()
    local goneEnt, goneCount = e1:exists(), c:count()
    -- ...and nothing of it was put back. A panel's content is put back where it stood from on every ending
    -- but this one: a widget that is DYING has nowhere to go, and dropping it on the flat UI would leave an
    -- empty window nobody owns. (The client's own windows announce their removal before they unlink, so
    -- that is where this really bites -- see the [manual] line about closing the cupboard.)
    local orphan = hafen.ui():find("window[title=044.8 native stand-in]")
    local e2 = c:add(spare, me)
    e2:facing("camera"):offset(0, 0, 30)
    local stoodAgain = e2:exists() and (c:count() == goneCount + 1)
    c:remove(e2)
    local back = (spare:parent() == sParent) and (spare:position().x == sPos.x)
                 and (spare:position().y == sPos.y)
    check((up == 3) and (goneEnt == false) and (goneCount == 2) and (orphan == nil) and stoodAgain and back
          and (not e2:exists()),
          "the swap the example addon is made of, in three ordinary calls: a window stands, destroying its"
          .. " content ends the entity with it (which is what the server closing a container window does, so"
          .. " there is nothing to clean up in that handler) and leaves NOTHING of it on the flat UI,"
          .. " standing a different widget in its place is an ordinary :add, and taking that one back puts"
          .. " the widget down where it stood from -- the whole of 'standing records where it was, removing"
          .. " puts it back', and the one ending where there is nothing to put back",
          ("count 2 -> %d -> %d, first entity exists=%s, left on the flat UI=%s, second stood=%s, put"
           .. " back=%s"):format(up, goneCount, tostring(goneEnt), tostring(orphan ~= nil),
            tostring(stoodAgain), tostring(back)))

    -- ---- 12. one of the CLIENT's own containers, standing ------------------------------------------------
    -- Stood here rather than with the rest, so the counts above are about panels this suite built. It goes
    -- back in the same check: the player's own backpack is not something to leave hanging in a field.
    local inv = hafen.ui():inventory()
    if inv == nil then
      check(false, "the client's own main inventory was findable (it is what the wheel checks stand on)", "nil")
      return summary()
    end
    S.inv, S.invParent, S.invPos, S.invId = inv, inv:parent(), inv:position(), inv:id()
    S.invItems = #inv:items()
    S.invEnt = hafen.vr():widget():add(inv, me):facing("camera"):offset(0, 14, 16)
    hafen.timer():after(1.2, function() phase4() end)
  end)
end

phase4 = function()
  if not (S and S.invEnt) then return summary() end
  local inv = S.inv
  local took = (S.invEnt:exists() and poke(S.invEnt, 8, 8, "Wheel", -1)) or false
  local id, items = inv:id(), #inv:items()
  hafen.vr():widget():remove(S.invEnt)
  local back = (inv:parent() == S.invParent) and (inv:position().x == S.invPos.x)
               and (inv:position().y == S.invPos.y)
  check((took == true) and (id ~= nil) and (id == S.invId) and (items == S.invItems) and back
        and (not S.invEnt:exists()),
        "the client's own container stands and keeps every thread that ties it to the server: it holds the"
        .. " same widget id and reads the same live items while it hangs in the world, a wheel turn over it"
        .. " is taken by the panel and delivered to the inventory rather than reaching the world beneath,"
        .. " and taking it back puts it down where it stood from. (What the wheel does WITH a modifier held"
        .. " is the [manual] line below -- a program cannot hold a key down.)",
        ("wheel taken=%s, id %s -> %s, items %d -> %d, put back=%s"):format(tostring(took),
          tostring(S.invId), tostring(id), S.invItems, items, tostring(back)))

  manualCheck("look at your own character: TWO windows hang over your head -- '044.8 panel' with a text"
              .. " field and a 'Sort' button, and the smaller '044.8 idle' beside it, plus a small image"
              .. " floating on the other side. Click the button, hover it for its tooltip, click the text"
              .. " field and type, and turn the camera all the way around. Then run ':t044-8 off'",
              "they read as ordinary windows drawn out in the world: square-on from every angle, the"
              .. " button lights up under the pointer and clicks like a button, the tooltip appears over"
              .. " the panel and not on the flat UI, and the letters you type land in the field")
  manualCheck("now the example addon end to end -- type ':cupboard', walk to a cupboard and open it; drag an"
              .. " item in and back out, then LEAVE AT LEAST ONE ITEM IN IT and close the window; open it"
              .. " again; then ':reload'",
              "the client's own cupboard window is not on the screen at all -- it hangs over the cupboard,"
              .. " camera-facing, and the item goes in and out of it there. Closing it leaves a small panel"
              .. " of the addon's own in the same place, LISTING THE ITEM YOU LEFT (a container that was"
              .. " empty when it closed says so instead), AND NOTHING ELSE -- no empty window is left on the"
              .. " flat UI, either then or after the ':reload'. Opening it again puts the real window back")
  manualCheck("...and the fast swap: with the cupboard open and standing, CLICK THE CUPBOARD AGAIN half a"
              .. " dozen times without moving -- the server closes and reopens its window each time, which"
              .. " ends one standing panel and starts another inside the same breath",
              "one panel, still standing on the cupboard, all the way through -- and the flat UI stays"
              .. " clean: no empty copy of the cupboard window is left behind by any of the clicks, and none"
              .. " is there after a ':reload' either")
  manualCheck("with that cupboard window still standing in the world, hold SHIFT and turn the wheel over it,"
              .. " up and then down -- the client's own bulk transfer, which asks the widget tree for the"
              .. " HUD it belongs to and is the one walk standing used to break",
              "an item moves out of the cupboard and then back into it, exactly as it does when the window"
              .. " is on the screen, and NO 'standing widget input error' line appears in the console")
  summary()
end

-- ':t044-8 off' takes down what the run left standing and asserts that nothing of it is left.
offRound = function()
  pass, fail, manual = 0, 0, 0
  if S == nil then
    check(false, "':t044-8' has been run first -- this round takes down what THAT one left standing",
          "nothing of this suite is standing")
    return summary()
  end
  local c, panel = hafen.vr():widget(), S.panel
  for _, e in ipairs(c:list()) do c:remove(e) end
  for _, e in ipairs(hafen.vr():sprite():list()) do hafen.vr():sprite():remove(e) end
  local ct = counters()
  check((ct.live == 0) and (ct.culled == 0) and (c:count() == 0) and panel:exists()
        and (panel:parent() ~= nil),
        "nothing of this suite is left standing: the collection is empty and the live-surface count is back"
        .. " at 0, so every texture the run allocated has been freed -- while the window that was being"
        .. " drawn on one of them is still alive on the flat UI, which is the difference between ending an"
        .. " entity and destroying a widget",
        ("live=%d culled=%d collection=%d widget alive=%s"):format(ct.live, ct.culled, c:count(),
          tostring(panel:exists())))
  clear()
  summary()
end

hafen.slash():register("t044-8", run)   -- the only way in: a suite does not start itself
