-- Water Meter -- every drop of water you are carrying, drawn as one stamina bar.
--
-- The bar counts the water containers in the backpack, in the equipment window, and inside anything
-- either of them holds -- a worn belt, a pouch, a creel, a stack -- at any depth. Empty ones count too:
-- an empty waterskin is capacity you are carrying and have not filled, and a bar that ignored it would
-- read 100% for a man about to die of thirst. How that is possible when an empty container tells the
-- client nothing at all is the block further down.
--
-- Nothing here polls. A container announces what enters and leaves it (ItemAdded / ItemRemoved), and an
-- item announces the moment its own tooltip resolves or is revised (Changed) -- which is what a drink,
-- a refill and "the client has just worked out what this is" all look like from here. The tally is
-- recomputed once per frame, and only when one of those has fired.

-- ------------------------------------------------------------------ the look of gfx/hud/meter/stam

-- The client's own IMeter: a 101x24 frame with a 75x10 hole at (22, 7). It paints the trough black, the
-- fill over that, and blits the frame last, so the frame's border sits on top of the bar's ends.
local METER_RESOURCE = "gfx/hud/meter/stam"
local METER_WIDTH    = 101
local METER_HEIGHT   = 24
local TROUGH_LEFT    = 22
local TROUGH_TOP     = 7
local TROUGH_WIDTH   = 75
local TROUGH_HEIGHT  = 10

local WATER_COLOUR     = {70, 150, 235}
local DEFAULT_POSITION = {x = 10, y = 90}

-- ------------------------------------------------------------------ state

local meterWidget                     -- the bar itself, in the addon layer, above every character
local sessionStates = {}              -- Session -> that character's own tally and subscriptions
local recomputeNeeded = false         -- an event has landed; the next frame re-walks the containers

-- ------------------------------------------------------------------ reading the water

-- What a liquid container states about its inside, e.g. "4.55 l of Water". The substance itself is never
-- named to the client -- that rendered line is the whole of what arrives -- so the line is what we match
-- on, and the number in front of it is the only figure in litres anywhere in the API.
local function litresOfWater(contentsText)
  if contentsText == nil then return nil end
  for line in (contentsText .. "\n"):gmatch("([^\n]*)\n") do
    if string.find(string.lower(line), "water", 1, true) then
      local digits = string.match(line, "%d[%d,%.]*")
      if digits == nil then return 0 end
      return tonumber((string.gsub(digits, ",", ""))) or 0
    end
  end
  return nil
end

-- ------------------------------------------------------------------ what a container holds when full
--
-- A container that is completely empty publishes NOTHING to the client: no contents line, no fill meter,
-- no capacity, nothing at all to say it is even a water container. So the only way to know that an empty
-- waterskin is a 3 l waterskin is to have seen one with water in it -- and how much a waterskin takes is
-- a fact about the KIND of container rather than about that one item, so it is learned per resource name
-- and kept in the account's saved variables.
--
-- That is what stops the bar reading 100% the moment you log in. Two waterskins, one full and one empty,
-- are one resource name: the full one teaches the capacity and the empty one is charged for it in the
-- same frame. Carry nothing but empties and the store answers from the last time you carried a full one.
local learnedCapacities = hafen.store():get("capacities")

local function learnCapacity(resourceName, capacity)
  if (resourceName == nil) or (capacity == nil) or (capacity <= 0) then return end
  local rounded = math.floor((capacity * 100) + 0.5) / 100
  local knownCapacity = learnedCapacities[resourceName]
  if (knownCapacity ~= nil) and (knownCapacity >= rounded) then return end
  learnedCapacities[resourceName] = rounded
  hafen.store():flush()             -- a new kind of container is rare; losing one to a crash need not be
end

local function capacityKnownFor(resourceName)
  if resourceName == nil then return nil end
  return learnedCapacities[resourceName]
end

-- Subscribe to one item's own revisions, once. A stale item never fires and subscribing to one is inert,
-- so there is no guard to write here beyond "not twice".
local function trackItem(sessionState, item)
  if sessionState.trackedItems[item] ~= nil then return end
  sessionState.trackedItems[item] = item:on("Changed", function()
    recomputeNeeded = true
  end)
end

-- One item, and everything inside it. seenItems is the recursion's own guard; tally collects the two
-- sums the bar is drawn from.
local function visitItem(sessionState, item, seenItems, tally)
  if seenItems[item] then return end
  seenItems[item] = true
  trackItem(sessionState, item)

  local resourceName = item:res()
  local contents = item:contents()
  if contents == nil then
    -- It says nothing about itself, which is exactly what an empty container says. If we know what this
    -- kind of container takes, that is capacity standing there unfilled, and the bar should feel it.
    local knownCapacity = capacityKnownFor(resourceName)
    if knownCapacity ~= nil then
      tally.capacity = tally.capacity + knownCapacity
    end
    return
  end

  local litres = litresOfWater(contents:text())
  if litres ~= nil then
    -- The stated line carries the only figure in litres anywhere in the API. The fill meter, when it
    -- answers at all, is a fraction in its own scale and never litres, so it is used to scale that figure
    -- up to a full container and for nothing else -- and where it does not answer, the fullest one of
    -- these we have ever seen stands in for it. Either way the capacity is in litres and comparable
    -- across two kinds of container, which is what lets one bar add them up.
    local capacity = litres
    local fill = contents:fill()
    if (fill ~= nil) and (fill.max > 0) and (fill.cur > 0) then
      capacity = litres * (fill.max / fill.cur)
    end
    local knownCapacity = capacityKnownFor(resourceName)
    if (knownCapacity ~= nil) and (knownCapacity > capacity) then capacity = knownCapacity end
    learnCapacity(resourceName, capacity)

    tally.litres = tally.litres + litres
    tally.capacity = tally.capacity + capacity
  end

  -- A stack, a creel, a worn belt: what it carries is items of their own, each with its own inside.
  for _, insideItem in ipairs(contents:items():list()) do
    visitItem(sessionState, insideItem, seenItems, tally)
  end
end

local function recompute()
  recomputeNeeded = false
  for _, sessionState in pairs(sessionStates) do
    local tally = {litres = 0, capacity = 0}
    local seenItems = {}
    for _, container in ipairs(sessionState.containers) do
      if container:exists() then
        for _, item in ipairs(container:items():list()) do
          visitItem(sessionState, item, seenItems, tally)
        end
      end
    end
    -- An item that has been drunk, eaten, dropped or moved out from under us is not this character's
    -- water any more. What it taught us about its kind stays: that is the store's, not this session's.
    for item, subscription in pairs(sessionState.trackedItems) do
      if not item:exists() then
        subscription:off()
        sessionState.trackedItems[item] = nil
      end
    end
    sessionState.litres = tally.litres
    sessionState.capacity = tally.capacity
  end
end

-- ------------------------------------------------------------------ the containers we watch

local function attachContainer(sessionState, container)
  if sessionState.attachedContainers[container] then return end
  sessionState.attachedContainers[container] = true
  sessionState.containers[#sessionState.containers + 1] = container

  local subscriptions = sessionState.subscriptions
  -- The items already in there arrive as ItemAdded while we subscribe, before :on returns, so this seeds
  -- the state as well as reporting the changes. It reaches any depth: something dropped into a belt this
  -- container holds is reported here too.
  subscriptions[#subscriptions + 1] = container:on("ItemAdded", function(item)
    trackItem(sessionState, item)
    recomputeNeeded = true
  end)
  subscriptions[#subscriptions + 1] = container:on("ItemRemoved", function()
    recomputeNeeded = true
  end)
end

local function forgetSession(session)
  local sessionState = sessionStates[session]
  if sessionState == nil then return end
  for _, subscription in ipairs(sessionState.subscriptions) do subscription:off() end
  for _, subscription in pairs(sessionState.trackedItems) do subscription:off() end
  sessionStates[session] = nil
end

-- ------------------------------------------------------------------ the bar

meterWidget = hafen.ui():widget()
  :size(METER_WIDTH, METER_HEIGHT)
  :position(DEFAULT_POSITION.x, DEFAULT_POSITION.y)
meterWidget:draggable(meterWidget)
meterWidget:remember("meter")

-- One boolean a frame while nothing moves; the walk only when an event has said something did.
meterWidget:on("Update", function()
  if recomputeNeeded then recompute() end
end)

meterWidget:on("Draw", function(event)
  local sessionState = sessionStates[hafen.session():current()]
  if sessionState == nil then return end          -- the login screen, or a character we do not watch

  local fraction = 0
  if sessionState.capacity > 0 then
    fraction = sessionState.litres / sessionState.capacity
    if fraction > 1 then fraction = 1 end
  end

  local graphics = event:g()
  graphics:color(0, 0, 0, 255)
  graphics:frect(TROUGH_LEFT, TROUGH_TOP, TROUGH_WIDTH, TROUGH_HEIGHT)
  if fraction > 0 then
    graphics:color(WATER_COLOUR)
    graphics:frect(TROUGH_LEFT, TROUGH_TOP, math.ceil(TROUGH_WIDTH * fraction), TROUGH_HEIGHT)
  end
  graphics:color()
  graphics:resource(METER_RESOURCE, 0, 0)
end)

-- ------------------------------------------------------------------ lifecycle

local function watchSession(session)
  forgetSession(session)                          -- a character switch keeps the session and drops its tree
  local sessionState = {
    subscriptions = {},
    containers = {},
    attachedContainers = {},
    trackedItems = {},
    litres = 0,
    capacity = 0,
  }
  sessionStates[session] = sessionState

  -- The backpack and the equipment grid are the client's, and both are hung a beat after the HUD is.
  -- "Added" covers one that is already up as well as one that is not, so there is nothing to wait for
  -- and nothing to poll. The role matches every open container, so the two we want are named by identity.
  local watch = session:ui():on("inventory", "Added", function(container)
    if (container == session:ui():inventory()) or (container == session:ui():equipment()) then
      attachContainer(sessionState, container)
    end
  end)
  sessionState.subscriptions[#sessionState.subscriptions + 1] = watch
end

-- A :reload re-announces only the character on screen, so the ones behind it are picked up here: a watch
-- on a session whose HUD is not up yet simply waits, and one on a session already in the world fires at
-- once for what it has open.
hafen.event():on("Load", function()
  for _, session in ipairs(hafen.session():list()) do
    watchSession(session)
  end
end)

hafen.event():on("SessionEnteredWorld", watchSession)

hafen.event():on("SessionRemoved", forgetSession)
