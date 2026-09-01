-- Simple Minimap -- the client's own minimap, in a panel of ours.
--
-- The client hangs its minimap in the bottom-left corner, inside a carved plate it blits over the map, in a
-- panel that cannot be moved and a box that cannot be sized. This addon TAKES that widget -- the same
-- CornerMap, still the client's -- into a surface of its own, and then does what a surface of your own can
-- do: stands where you drag it, sizes where you pull it, and wears the box an action bar wears.
--
-- NOTHING HERE DRAWS A MAP. A click still walks you there, the wheel still zooms, the icons, the markers
-- and the tooltips are all still the client's. That is the whole point of taking the widget rather than
-- standing in for it: its picture is a render of the map database, and no addon could reproduce it.
--
--     panel ┌─────────────────────────┐   <- the box: gfx/hud/wnd, the action bars' own
--           │ ░░░░░░░ grip ░░░░░░░░░░ │   <- the field, and the strip you drag it by
--           │ ░┌───────────────────┐░ │
--           │ ░│     CornerMap     │░ │   <- the CLIENT's widget, taken in with widget:parent(panel)
--           │ ░└───────────────────┘░ │
--           │ ░░░░░░░░░░░░░░░░░░░░ ▟░ │   <- the corner you size it by
--           └─────────────────────────┘
--
-- THE ORDER OF PAINT IS WHAT MAKES THIS A PANEL RATHER THAN A BOX BESIDE ONE. A widget draws its own
-- background, then its children, then its border -- so the field is UNDER the client's map and the brass is
-- OVER it, exactly as an action bar's field is under its buttons.
--
-- THE GRIP IS A STRIP, NOT THE PANEL. Arming the panel itself would take every press on it, and the press
-- would belong to the drag -- so a click on the map would move the window instead of walking you there. The
-- strip stands on the field above the map and covers none of it.
--
-- WHAT IS LEFT IN THE CORNER: NOTHING, AND IT IS THE TWO PANELS THAT SAY SO. The carved plate, the fold
-- arrows and the map/claim/icon buttons are not one widget and not one family -- the plate and one arrow
-- hang in the panel the map came out of, the buttons and two more arrows in the panel beside it. Hiding the
-- buttons alone leaves an arrow floating over an empty corner, because that arrow is their SIBLING and not
-- their child. So what is put away is the two panels themselves, which is legal precisely because the map
-- has already left the first one, and both are given back by hand when this stops.

local BOX   = "gfx/hud/wnd"
local FIELD = {43, 51, 44, 127}     -- the action bars' own field: same colour, same alpha

-- EIGHT, AND IT IS MEASURED: a {box = "gfx/hud/wnd"} border reserves its corner's own size, which is what
-- `new IBox.Scaled("gfx/hud/wnd", ...).ctloff()` answers -- (8, 8) design px. PAD is the field showing
-- inside it, so the brass never touches the map and the margin is somewhere to take hold of.
local EDGE  = 8
local PAD   = 3
local M     = EDGE + PAD            -- from the panel's edge to the map's
local GRIP  = 10                    -- the strip above the map that drags the whole thing
local MIN   = 64                    -- a map smaller than this is one you cannot read

local saved = hafen.store():get("state")
if saved.on == nil then saved.on = true end

local dressed = {}                  -- one record per character, each with that character's own map

-- ---------------------------------------------------------------- geometry

local function panelBox(w, h)
  return w + (M * 2), h + (M * 2) + GRIP
end

-- ---------------------------------------------------------------- the corner's own furniture

local function put(w, vis)
  if w and w:exists() then
    local ok, err = pcall(function() w:visible(vis) end)
    if not ok then hafen.log():write("Simple Minimap: " .. tostring(err)) end
  end
end

-- ---------------------------------------------------------------- on and off

local function place(r)
  if not (r.panel and r.panel:exists() and r.mmap and r.mmap:exists()) then return end
  local b = r.mmap:size()
  local pw, ph = panelBox(b.w, b.h)
  local now = r.panel:size()
  if (now.w ~= pw) or (now.h ~= ph) then
    r.panel:size(pw, ph)
    r.grip:size(b.w, GRIP)
    r.sizer:position(M + b.w, M + GRIP + b.h)      -- the corner where the two margins meet
  end
end

local function dress(r)
  if r.panel or not r.mmap:exists() then return end

  local b = r.mmap:size()
  local w = math.max(MIN, saved.w or b.w)
  local h = math.max(MIN, saved.h or b.h)
  local pw, ph = panelBox(w, h)

  -- Built into the character's OWN tree: the map reads its session, so a surface in the addon layer is
  -- refused as a destination -- and rightly, the widget would go dark there.
  r.panel = hafen.ui():widget():parent(r.hud):position(saved.x or 40, saved.y or 40)
                               :size(pw, ph):name("panel")
  r.panel:stock{bg = {color = FIELD}, border = {box = BOX, mode = "tile"}}

  -- THE MAP FIRST, THE HANDLES AFTER, and the order is the point: a parent offers a press to its children
  -- last-added first, and the map answers any press that lands on it. A handle built before it would be
  -- underneath it in that walk and never hear the click that is meant for it.
  r.mmap:parent(r.panel):size(w, h):position(M, M + GRIP)

  -- Neither handle covers a pixel of the map: the strip is the field above it, the corner is where the
  -- right-hand margin meets the bottom one. So the map keeps every click that is a click on the map.
  r.grip = hafen.ui():widget():parent(r.panel):position(M, M):size(w, GRIP):name("grip")
  r.sizer = hafen.ui():widget():parent(r.panel):position(M + w, M + GRIP + h):size(M, M):name("sizer")

  -- The corner's two panels go away whole -- see the note at the top: an arrow is the buttons' sibling.
  put(r.bl, false)
  put(r.menuPanel, false)

  r.panel:draggable(r.grip)         -- the strip drags the panel, and the client's own clamp bounds it
  r.mmap:resizable(r.sizer)         -- the corner sizes the MAP; the panel follows it in place() below

  r.panel:on("Dragged", function(ev)
    saved.x, saved.y = ev:x(), ev:y()
    hafen.store():flush()
  end)
  r.mmap:on("Resized", function(ev)
    saved.w, saved.h = ev:x(), ev:y()
    hafen.store():flush()
    place(r)
  end)
  -- A resize is written every frame the pointer moves, and "Resized" is only the release, so the panel is
  -- kept round the map here. It writes nothing while nothing changes.
  r.panel:on("Update", function() place(r) end)
end

local function undress(r)
  if r.mmap and r.mmap:exists() then
    r.mmap:parent(nil)                           -- home, whole: parent, order, place and box in one
  end
  if r.panel and r.panel:exists() then
    r.panel:destroy()                            -- the map is out already; this takes the grip and the sizer
  end
  r.panel, r.grip, r.sizer = nil, nil, nil
  put(r.bl, true)                                  -- ...and the corner is the client's again, whole
  put(r.menuPanel, true)
end

-- ---------------------------------------------------------------- the characters

local function record(s)
  for _, r in ipairs(dressed) do
    if r.s == s then return r end
  end
  return nil
end

-- One panel per character, because the map is one character's widget. The subscription is what handles the
-- client REBUILDING its own map -- it destroys and re-makes the CornerMap when the map file changes -- and
-- it fires at once for the map that is already up, so it is the whole of the wiring.
local function attach(s)
  if (not s) or record(s) then return end
  local hud = s:ui():match("@GameUI")
  if not hud then return end
  local r = {s = s, hud = hud}
  dressed[#dressed + 1] = r
  s:ui():on("@CornerMap", "Added", function(mmap)
    if r.panel then undress(r) end                -- a rebuilt map: the old panel stood round a dead widget
    r.mmap = mmap
    r.bl = mmap:parent()                          -- the panel it came out of: the plate and one arrow are in it
    local menu = s:ui():match("@MapMenu")
    r.menuPanel = menu and menu:parent() or nil   -- ...and the buttons and two more arrows are in that one
    if saved.on then dress(r) end
  end)
end

local function detach(s)
  for i, r in ipairs(dressed) do
    if r.s == s then table.remove(dressed, i) return end
  end
end

-- ---------------------------------------------------------------- lifecycle

hafen.event():on("SessionEnteredWorld", function(s) attach(s) end)
hafen.event():on("SessionRemoved", function(s) detach(s) end)

-- GIVE THE CORNER BACK BY HAND. The engine restores everything this addon holds on its own, but the plate
-- and the buttons are not windows with a toggle: teardown's rule is "the widget ends up as the user was
-- seeing it", and what they were seeing is this panel. Disable fires BEFORE the teardown, which is the
-- moment to say that what they should see now is the client's own corner.
hafen.event():on("Disable", function()
  for _, r in ipairs(dressed) do
    pcall(function() undress(r) end)
  end
  dressed = {}
end)

hafen.console():on("simpleminimap", function(args)
  -- `:simpleminimap diag` -- every Hidepanel this character has, whether it is on screen, and what is in
  -- it. Two of them are this addon's business and five are nobody's; the list says which is which.
  if args and (args[1] == "diag") then
    local s = hafen.session():current()
    if not s then hafen.log():write("Simple Minimap: no character on screen") return end
    for _, p in ipairs(s:ui():matchAll("@Hidepanel")) do
      local kids = {}
      for _, c in ipairs(p:children():list()) do kids[#kids + 1] = c:type() end
      local at, b = p:position(), p:size()
      hafen.log():write(("Simple Minimap: %s panel at %d,%d %dx%d [%s]"):format(
        p:visible() and "shown " or "HIDDEN", at.x, at.y, b.w, b.h, table.concat(kids, " ")))
    end
    return
  end

  saved.on = not saved.on
  hafen.store():flush()
  for _, r in ipairs(dressed) do
    if saved.on then dress(r) else undress(r) end
  end
  hafen.log():write("Simple Minimap: " .. (saved.on and "on, the map is in its own panel"
                                                    or "off, the client's corner is back"))
end)
