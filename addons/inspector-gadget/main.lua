-- Inspector Gadget -- a magnifying glass in the action menu. Turn it on and a tooltip beside the pointer
-- says everything the client knows about whatever you are hovering: an OBJECT when there is one under the
-- pointer, and otherwise the GROUND itself -- its tile, where it is, which grid it belongs to. Left-click an
-- object to run the game's own Inspect on it. Right-click the ground, or press the button again, to put the
-- glass away.
--
-- WHAT IS UNDER THE POINTER IS THE CLIENT'S OWN ANSWER, not ours. hafen.ui():mouse():pick() is the very
-- pick pass a right-click goes through, so the object described is exactly the object a click would have
-- reached, and m:ground() is that same pass's other half -- one submission, so the tile shown is never one
-- frame's while the object over it is another's. Holding the PickChanged subscription is what makes the
-- client run that pass at all, so with the glass down this addon costs a menu button and nothing else.
--
-- THE TOOLTIP IS ONE PAINTER OVER THE HUD, drawn beside the pointer, and nothing is attached to any object.
-- It reads the hovered gob LIVE on every frame it draws, so nothing in it is a snapshot: a boar that starts
-- running says so while it runs, and a question the object has no answer to is simply left out -- which is
-- what keeps the tooltip three rows on a boulder and a dozen on a player.
--
-- THE GLASS TAKES TWO GESTURES AND LEAVES THE REST ALONE. A left-click on an OBJECT is swallowed and
-- becomes the game's own Inspect -- the paginae/act/inspect action, then the click that targets it, which
-- is exactly the pair of messages a player produces by pressing Inspect and clicking. A right-click on the
-- ground puts the glass away. Everything else goes out untouched: a left-click on the ground is the
-- ordinary map click that walks you there, and a right-click on an object still opens its flower menu.

local ENTRY_ID = "lens"            -- our menu entry; its identity is addon/inspector-gadget/lens
local INSPECT_ACTION = "paginae/act/inspect"   -- the game's own Inspect, by resource name
local TIP_KEY = "tip"              -- our HUD overlay key; keys are per addon
local LENS_CURSOR = "study"        -- gfx/hud/curs/study, the game's own magnifying glass

local LINE_HEIGHT = 13             -- one label/value row, design pixels
local COLUMN_GAP = 8               -- between the label column and the value column
local PLATE_PADDING = 5            -- the plate's margin round the rows
local POINTER_GAP = 18             -- from the pointer to the corner of the plate
local PLATE_COLOUR = {18, 20, 24, 215}
local LABEL_COLOUR = {150, 172, 200}
local VALUE_COLOUR = {236, 238, 242}
local TIP_FONT = hafen.font():get("sans"):derive():size(11)

local lensIsOn = false
local hoveredGob = nil             -- what the client's last pick found, or nil
local pickSub = nil                -- live only while the glass is up; holding it is what arms the pass
local sendingInspect = false       -- OUR OWN targeting click is on the wire, and is not a gesture
local menuIcon                     -- the PNG, loaded once

-- LuaJ's string.format ignores a precision, so a rounded number is rounded by hand.
local function whole(value)
  return math.floor(value + 0.5)
end

-- A row's width, measured once per distinct string. The cache is emptied rather than grown without bound,
-- because a moving object's coordinates are a new string every time it crosses a unit.
local measuredWidth, measuredCount = {}, 0
local function widthOf(text)
  local width = measuredWidth[text]
  if not width then
    if measuredCount > 400 then measuredWidth, measuredCount = {}, 0 end
    width = hafen.ui():measure(text, {font = TIP_FONT}).w
    measuredWidth[text], measuredCount = width, measuredCount + 1
  end
  return width
end

-- Everything the client can answer about one object, as label/value rows in a fixed order.
local function describe(gob)
  local rows = {}
  local function say(label, value)
    if value ~= nil then rows[#rows + 1] = {label = label, value = tostring(value)} end
  end

  say("resource", gob:name() or "(still loading)")
  say("id", gob:id())

  local place = gob:position()
  if place then
    say("place", whole(place:x()) .. ", " .. whole(place:y()))
    local tile = place:tileCoord()
    if tile then say("tile", tile.x .. ", " .. tile.y) end
    -- The grid id is the one anchor that means the same patch of ground to every character; the pair
    -- beside it is the offset WITHIN that grid, not the session components on the row above.
    local anchored = place:durable() and place:info() or nil
    if anchored then say("grid", anchored.gridId .. " + " .. whole(anchored.x) .. ", " .. whole(anchored.y)) end
  end

  local range = gob:distance()
  if range then say("range", whole(range)) end

  local facing = gob:facing()
  if facing then say("facing", (whole(math.deg(facing)) % 360) .. " deg") end

  local health = gob:health()
  if health then say("health", whole(health * 100) .. "%") end

  say("motion", gob:moving() and ("moving " .. whole(gob:speed() or 0)) or "still")

  if gob:player() then say("player", "yes") end
  local kin = gob:kin()
  if kin then say("kin", kin:name()) end
  say("icon", gob:icon())
  say("says", gob:speech())

  -- The state bytes as the server sent them, under the name the API reads them by. What they MEAN belongs
  -- to the resource's own published code, so they are shown as the bytes they are rather than guessed at.
  local sdt = gob:sdt()
  if sdt and #sdt > 0 then say("sdt", table.concat(sdt, " ")) end

  -- The other half of the same fact, and never beside it: a COMPOSED body (a player, an animal) is in
  -- poses where a resource-drawn one has state bytes, so exactly one of these two rows ever shows. A
  -- one-shot -- a swing, a bite -- is what it names while one plays, which is what the eye sees.
  local pose = gob:pose()
  if pose and #pose > 0 then say("pose", table.concat(pose, ", ")) end

  -- What the game itself is drawing AT the object -- a lit fire's flame, a fight's effects. Not its
  -- animation: that is the row above.
  local drawn = {}
  for _, overlay in ipairs(gob:overlay():list()) do
    if overlay:native() then drawn[#drawn + 1] = overlay:key() end
  end
  if #drawn > 0 then say("drawn", table.concat(drawn, ", ")) end

  return rows
end

-- The GROUND itself, when there is no object over it: the tile drawn there and where that tile is. The
-- place comes out of the same pick pass as the object, so it is the ground the client itself would have
-- resolved a click to -- not a raycast of our own taken at some other instant.
local function describeGround(place)
  local session = hafen.session():current()
  if not session then return {} end
  local world = session:world()
  local rows = {}
  local function say(label, value)
    if value ~= nil then rows[#rows + 1] = {label = label, value = tostring(value)} end
  end

  -- The tileset the ground is drawn from. Its `id` is a number THIS session made up when the server named
  -- the set, so the name is the half that means the same thing twice; the id is shown beside it because it
  -- is what the wire carries.
  local tile = world:tile(place)
  say("resource", tile and (tile.name or "(still loading)") or "(not streamed in)")
  if tile then say("tileset", tile.id) end

  say("place", whole(place:x()) .. ", " .. whole(place:y()))
  local coord = place:tileCoord()
  if coord then say("tile", coord.x .. ", " .. coord.y) end
  local height = world:height(place)
  if height then say("height", whole(height)) end

  -- The grid id is the one anchor that means the same patch of ground to every character; the pair beside
  -- it is the offset WITHIN that grid, not the session components on the row above.
  local anchored = place:durable() and place:info() or nil
  if anchored then say("grid", anchored.gridId .. " + " .. whole(anchored.x) .. ", " .. whole(anchored.y)) end
  local grid = world:grid():at(place)
  local segmentCoord = grid and grid:segmentCoord()
  if segmentCoord then say("segment", segmentCoord.x .. ", " .. segmentCoord.y) end

  return rows
end

-- What the tooltip is about right now: the object under the pointer when there is one, and the ground
-- itself otherwise. Both halves come out of ONE pick pass, so they can never describe two instants.
local function tooltipRows()
  local gob = hoveredGob
  if gob and gob:exists() then return describe(gob) end
  local place = hafen.ui():mouse():ground()
  return place and describeGround(place) or nil
end

-- The tooltip: down and to the right of the pointer, folded back over it at the edges of the screen so the
-- whole plate is always on it.
local function paintTip(g, screenWidth, screenHeight)
  local rows = tooltipRows()
  if not rows or #rows == 0 then return end

  local labelColumn, valueColumn = 0, 0
  for _, row in ipairs(rows) do
    labelColumn = math.max(labelColumn, widthOf(row.label))
    valueColumn = math.max(valueColumn, widthOf(row.value))
  end
  local panelWidth = labelColumn + COLUMN_GAP + valueColumn
  local panelHeight = #rows * LINE_HEIGHT

  local mouse = hafen.ui():mouse()
  local left, top = mouse:x() + POINTER_GAP, mouse:y() + POINTER_GAP
  if left + panelWidth + PLATE_PADDING > screenWidth then left = mouse:x() - POINTER_GAP - panelWidth end
  if top + panelHeight + PLATE_PADDING > screenHeight then top = mouse:y() - POINTER_GAP - panelHeight end

  g:color(PLATE_COLOUR)
  g:frect(left - PLATE_PADDING, top - PLATE_PADDING,
          panelWidth + 2 * PLATE_PADDING, panelHeight + 2 * PLATE_PADDING)
  g:color()
  for index, row in ipairs(rows) do
    local rowTop = top + (index - 1) * LINE_HEIGHT
    g:text(row.label, left, rowTop, {font = TIP_FONT, color = LABEL_COLOUR})
    g:text(row.value, left + labelColumn + COLUMN_GAP, rowTop, {font = TIP_FONT, color = VALUE_COLOUR})
  end
end

-- The glass, and everything that only runs while it is up: the pointer's picture, the client's pick pass
-- and the painter. Turning it off leaves nothing behind -- sub:off() is what stops the pass.
local function setLens(on)
  lensIsOn = on
  hafen.ui():mouse():cursor(on and LENS_CURSOR or nil)
  if pickSub then pickSub:off() end
  pickSub, hoveredGob = nil, nil
  if on then
    pickSub = hafen.ui():mouse():on("PickChanged", function(gob) hoveredGob = gob end)
    hafen.ui():overlay():add(TIP_KEY):draw(paintTip)
  else
    hafen.ui():overlay():remove(TIP_KEY)
  end
end

-- ---- the world ---------------------------------------------------------------------------------------

-- The game's own Inspect on one object, as a player produces it: the ACTION, and then the CLICK that
-- targets it. Two messages, a tick apart -- each write door lets one send out per frame, and the pair goes
-- to the server in that order whatever the gap, which is all the sequence needs.
local function inspect(gob)
  local session = hafen.session():current()
  if not session then return end
  local action = session:menugrid():get(INSPECT_ACTION)
  if not action then
    hafen.log():write("Inspector Gadget: this character has no " .. INSPECT_ACTION .. " action")
    return
  end
  local armed, err = pcall(function() action:use() end)
  if not armed then
    hafen.log():write("Inspector Gadget: " .. tostring(err))
    return
  end
  hafen.timer():after(0, function()
    -- OUR OWN CLICK COMES BACK THROUGH OUR OWN HANDLER. s:world():click sends the message the ordinary
    -- way -- Widget.wdgmsg, which IS the outbound action stream's seam -- so the handler below sees it
    -- exactly as it sees the player's, and without this flag it would swallow the very click it just
    -- asked for and start the whole gesture again, forever. The flag is exact rather than approximate:
    -- the send is synchronous, so it is raised for precisely the call it covers.
    --   The object may also have left view in the meantime, and a click about one this character cannot
    -- see is refused rather than sent. That is a refusal to report, not a defect to hide.
    sendingInspect = true
    local sent, clickErr = pcall(function() session:world():click(gob, 1, 0) end)
    sendingInspect = false
    if not sent then hafen.log():write("Inspector Gadget: " .. tostring(clickErr)) end
  end)
end

-- The two gestures the glass takes. This runs where the click is sent, so it decides what it saw and a
-- step of its own does the work.
hafen.event():action():on("click", function(clickEvent)
  if sendingInspect then return end          -- the targeting click we just sent; see inspect() above
  if not lensIsOn or clickEvent:widget():type() ~= "MapView" then return end
  local button, gob = clickEvent:args()[3], clickEvent:gob()
  if button == 1 and gob then
    -- The inspection REPLACES the click, so the character does not walk over and poke the thing.
    clickEvent:preventDefault()
    hafen.timer():after(0, function() inspect(gob) end)
  elseif button == 3 and not gob then
    clickEvent:preventDefault()
    hafen.timer():after(0, function() setLens(false) end)
  end
  -- Anything else is left alone, and a left-click on the GROUND is the one that matters: it goes out as
  -- the ordinary map click, so the glass never costs you the ability to walk.
end)

-- The button, one per character: the action menu is where the client keeps everything a character can do,
-- and an entry of ours is a Pagina like any other, so it drags onto an action bar as well.
local function addButton(session)
  local ok, err = pcall(function()
    local entry = session:menugrid():add(ENTRY_ID):name("Inspector Gadget")
      :tooltip("Hover an object to read what the client knows about it; right-click the ground to stop")
    if menuIcon then entry:icon(menuIcon) end
    entry:on("use", function() setLens(not lensIsOn) end)
  end)
  if not ok then hafen.log():write("Inspector Gadget: " .. tostring(err)) end
end

hafen.event():on("Load", function()
  menuIcon = hafen.asset():get("lens.png")
end)

-- Every moment there is a character in the world to put the button in front of: a login, that session
-- picking another character, and a :reload with one already there -- SessionEnteredWorld is announced again
-- for each login in the world, so this one handler is the whole of it. Adding the button from Load as well
-- would add it twice on a reload, and the second :add is refused: the id is unique within a character.
hafen.event():on("SessionEnteredWorld", addButton)

-- :inspector -- what the pointer is on, right now. It defers, because a console handler runs under the
-- typed tree's monitor and reading the pointer walks that tree.
hafen.console():on("inspector", function()
  hafen.timer():after(0, function()
    local mouse = hafen.ui():mouse()
    local under = mouse:over()
    local aimed = mouse:pick()
    hafen.log():write("inspector: glass " .. (lensIsOn and "on" or "off")
      .. ", pointer " .. tostring(mouse:x()) .. ", " .. tostring(mouse:y())
      .. ", over " .. (under and under:type() or "nothing"))
    local place = mouse:ground()
    hafen.log():write("inspector: pick says " .. (aimed and (aimed:name() or "?") or "nothing")
      .. ", ground " .. (place and (whole(place:x()) .. ", " .. whole(place:y())) or "nothing")
      .. ", tooltip is showing " .. (hoveredGob and (hoveredGob:name() or "?") or "the ground"))
  end)
end)
