-- Farming Helper -- one key puts the growth stage over every planted crop in sight, at ground level.
--
-- The stage is not something the client works out. The server sends it WITH the object, in the state
-- bytes gob:sdt() answers, and the crop's own resource reads the FIRST of them to pick which mesh to
-- draw -- so the number here is the very byte the game draws the plant from. It is printed 1-based, the
-- way every index in this API reads, so a freshly sown field says 1 rather than 0.
--
-- Each number is a LABEL hung on the crop itself: gob:overlay():add(key):text(n). The client draws it --
-- no Lua runs per frame, the string is rasterised once for its lifetime and blitted once a frame -- and it
-- dies with the object, so a harvested crop takes its own number away. Two things make it readable where
-- a plain label would not be: it stands at height 0, on the ground the plant stands on rather than in the
-- air over its head, and its face carries a black outline baked into that one raster, which is what makes
-- white read over pale soil and dark leaves alike without a plate behind it.
--
-- Suggested key: Ctrl+F -- assign it in Options > Game > Keybindings > Farming Helper.

-- Every crop resource lives under this path. The trellis is the one thing under it that is not a plant
-- -- it is the frame grapes and hops are grown on, furniture rather than a crop -- so it is left out.
local CROP_PATH = "gfx/terobjs/plants/"
local TRELLIS = "gfx/terobjs/plants/trellis"

local KEY = "stage"    -- our own overlay key on a crop; keys are per addon

-- The client's stock face is sans at 10 design px (Text.std), so this is that face one pixel up, bold,
-- with the outline every stroked label in the game wears. One handle for every label: the rendered text
-- is cached per string and face, so ten thousand "4"s are one raster.
local FONT = hafen.font():get("sans"):derive():size(11):bold(true):outline{0, 0, 0}
local WHITE = {255, 255, 255}
-- A label stands bottom-centred on its point. Half a digit's height down puts the number's middle on the
-- ground point instead, which is where the eye looks for it.
local DROP = math.floor(hafen.ui():measure("8", {font = FONT}).h / 2)

local showing = false
local labelled = {}   -- [Gob] = true while a label of ours is on it; the label itself lives on the gob

-- gob:name() is the resource name the server sent, so a crop is recognised by where its resource lives.
local function isCrop(gob)
  local name = gob:name()
  return name ~= nil and name:sub(1, #CROP_PATH) == CROP_PATH and name ~= TRELLIS
end

-- Hang the stage on one crop, or relabel the one already there. An object whose own drawing is still
-- resolving takes no overlay in that instant, so the attach is tried and, refused, tried once more a
-- moment later -- by which time the resource that refused it has resolved.
local function label(crop, retried)
  local bytes = crop:sdt()
  local stage = bytes and bytes[1]
  if not stage then return end
  local text = tostring(stage + 1)
  local have = crop:overlay():get(KEY)
  if have then
    have:text(text)
    return
  end
  local ok = pcall(function()
    crop:overlay():add(KEY):text(text):color(WHITE):font(FONT):height(0):offset(0, DROP)
  end)
  if ok then
    labelled[crop] = true
  elseif not retried then
    hafen.timer():after(0.5, function()
      if showing and crop:exists() then label(crop, true) end
    end)
  end
end

local function showAll(session)
  for _, crop in ipairs(session:world():gob():list(isCrop)) do
    label(crop)
  end
end

-- A label hangs on the object, so one :remove takes it off every character that can see it; on a gob
-- already gone it is inert, since the label died with it.
local function hideAll()
  for crop in pairs(labelled) do
    crop:overlay():remove(KEY)
  end
  labelled = {}
end

hafen.client():options():keybindings():on("toggle", function()
  showing = not showing
  if not showing then
    hideAll()
    return
  end
  for _, session in ipairs(hafen.session():list()) do
    showAll(session)
  end
end)

-- This fires when a crop's state bytes change -- a plant advancing a stage -- AND for the first state a
-- crop is ever given, so a crop that walks into view while the numbers are on is picked up by the same
-- handler that keeps a growing one current. Nothing is polled.
hafen.event():on("GobSdtChanged", function(ev)
  local crop = ev:gob()
  if showing and isCrop(crop) then label(crop) end
end)

-- The label went with its object; only our own bookkeeping is left to drop.
hafen.event():on("GobRemoved", function(gob)
  labelled[gob] = nil
end)

-- A character arriving in the world sees the labels already hung on what it loads. Its own ground may
-- hold crops no other character has seen, and those are labelled here.
hafen.event():on("SessionEnteredWorld", function(session)
  if showing then showAll(session) end
end)
