-- Farming Helper -- one key puts the growth stage over every planted crop in sight, at ground level.
--
-- The stage is not something the client works out. The server sends it WITH the object, in the state
-- bytes gob:sdt() answers, and the crop's own resource reads the FIRST of them to pick which mesh to
-- draw -- so the number here is the very byte the game draws the plant from. It is printed 1-based, the
-- way every index in this API reads, so a freshly sown field says 1 rather than 0.
--
-- Two things decide how it is drawn, and both are the reason this is a painter rather than the label
-- gob:overlay():text() would have put up. A gob label hangs at a FIXED anchor 15 world units over the
-- object -- up where a name floats, not down where the plant is -- and it takes a colour and nothing
-- else. So the number is painted by hand instead: worldToScreen projects AT THE GROUND under a place,
-- and the number is drawn there white on a black outline, which is what makes it read over pale soil and
-- dark crops alike without a plate behind it.
--
-- The painter hangs on the character's own map view rather than over the whole HUD, which is what keeps
-- the numbers inside the 3D view and UNDER every window: a painter on a widget draws straight after that
-- widget, and the HUD's windows then cover it.
--
-- Suggested key: Ctrl+F -- assign it in Options > Game > Keybindings > Farming Helper.

-- Every crop resource lives under this path. The trellis is the one thing under it that is not a plant
-- -- it is the frame grapes and hops are grown on, furniture rather than a crop -- so it is left out.
local CROP_PATH = "gfx/terobjs/plants/"
local TRELLIS = "gfx/terobjs/plants/trellis"

local PAINTER = "stages"    -- our own overlay key on a map view; keys are per addon

-- The client's stock face is sans at 10 design px (Text.std), so this is that face one pixel up, bold.
local FONT = hafen.font():get("sans"):derive():size(11):bold(true)
local NUMBER = {font = FONT, color = {255, 255, 255}}
local BORDER = {font = FONT, color = {0, 0, 0}}
-- Where the black goes. These are the four the client's own stroked text uses -- left, right, up, down
-- -- so the number wears the outline every stroked label in the game wears.
local STROKE = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}

local showing = false
local crops = {}      -- [Gob] = {stage = "4", position = Position|nil}; what to draw, and where
local views = {}      -- [Session] = that character's map view, while a painter of ours is on it

-- gob:name() is the resource name the server sent, so a crop is recognised by where its resource lives.
local function isCrop(gob)
  local name = gob:name()
  return name ~= nil and name:sub(1, #CROP_PATH) == CROP_PATH and name ~= TRELLIS
end

-- What one crop draws. A crop does not move, so its place is read once and kept -- gob:position() in a
-- draw callback would build a Position per crop per frame. It reads nil until that ground has streamed
-- in, which is why the painter below finishes the job on a later frame instead of giving up here.
local function remember(crop)
  local bytes = crop:sdt()
  local stage = bytes and bytes[1]
  if stage then
    crops[crop] = {stage = tostring(stage + 1), position = crop:position()}
  end
end

-- One character's painter. It draws in the map view's OWN pixels, so the root point worldToScreen
-- answers is moved by the view's rootPos before it is used.
local function paint(session, view)
  return function(g)
    local origin = view:rootPos()
    if not origin then return end
    local world = session:world()
    g:color()      -- white, so each colour named below comes out exactly the colour it names
    for crop, record in pairs(crops) do
      record.position = record.position or crop:position()
      local point = record.position and world:worldToScreen(record.position)
      if point then
        -- Floored, so the outline lands on whole pixels and stays an even one all the way round.
        local x = math.floor(point.x - origin.x)
        local y = math.floor(point.y - origin.y)
        for _, at in ipairs(STROKE) do
          g:atext(record.stage, x + at[1], y + at[2], 0.5, 0.5, BORDER)
        end
        g:atext(record.stage, x, y, 0.5, 0.5, NUMBER)
      end
    end
  end
end

local function show(session)
  local view = session:ui():match("@MapView")
  if not view then return end            -- that character is not in the world yet
  views[session] = view
  view:overlay():add(PAINTER):draw(paint(session, view))
end

local function hide()
  for session, view in pairs(views) do
    views[session] = nil
    view:overlay():remove(PAINTER)
  end
  crops = {}
end

hafen.client():options():keybindings():on("toggle", function()
  showing = not showing
  if not showing then
    hide()
    return
  end
  for _, session in ipairs(hafen.session():list()) do
    for _, crop in ipairs(session:world():gob():list(isCrop)) do
      remember(crop)
    end
    show(session)
  end
end)

-- This fires when a crop's state bytes change -- a plant advancing a stage -- AND for the first state a
-- crop is ever given, so a crop that walks into view while the numbers are on is picked up by the same
-- handler that keeps a growing one current. Nothing is polled.
hafen.event():on("GobSdtChanged", function(ev)
  local crop = ev:gob()
  if showing and isCrop(crop) then remember(crop) end
end)

-- A crop the last of your characters can no longer see. Unlike a gob overlay, a record here does not die
-- with its object, so this is the one place it is dropped -- harvested, or simply walked away from.
hafen.event():on("GobRemoved", function(gob)
  crops[gob] = nil
end)

hafen.event():on("SessionEnteredWorld", function(session)
  if showing then show(session) end
end)

-- The map view went with the character, and took our painter with it.
hafen.event():on("SessionRemoved", function(session)
  views[session] = nil
end)
