-- Item Indicators -- what an item says about itself, on its own icon: the quality as a label -- the mean of
-- what it holds, where it is a stack -- and the durability it has left as the bar the client draws for a
-- waterskin's water.
--
-- Both hang off one subscription and one revision listener per icon, because both are answers to the same
-- question -- the item's tooltip -- and it arrives once, a moment after the icon does.
--
-- Nothing here polls. An icon is announced as it is built, and an item announces the moment its own tooltip
-- resolves or is revised: a repair, a point of wear, a quality the client has only just worked out. The
-- label is drawn by the client and costs no Lua at all per frame, and the bar is attached only to an icon
-- that actually has a wear row, so an inventory of unwearable things costs nothing either.

-- ------------------------------------------------------------------ the quality label
--
-- The number is two real widgets rather than an overlay, and the reason is themes: pixels an addon lays down
-- in a Draw handler are final -- a rule is a value the client reads, not a painter that reaches into a
-- callback -- while a widget an addon NAMES can be found by a rule, and what it declares as its `stock` sits
-- at the BOTTOM of the cascade, under every rule. So this look is a default anybody can beat, per property,
-- and a theme dresses it by writing ["[name=item-indicators/quality]"] without either addon knowing the
-- other. A widget:rule() of ours would sit at the TOP, where no theme could reach past it.
--
-- The plate is a bare surface carrying the black; the text is one of the client's own Labels, because a
-- Label re-renders when a font override on its scope moves and is therefore the one text a rule can dress.

local PLATE_NAME  = "qualityPlate"
local LABEL_NAME  = "quality"

-- The plate never narrows past two digits, so a column of icons carries one shape rather than a ragged edge
-- of one-, two- and three-digit boxes. There is no verb that measures a string, so the floor is taken from a
-- label rendering "00" -- the widest two digits in any face -- once, off the first label built.
local MIN_DIGITS  = "00"
local minWidth                                           -- ...measured lazily: at load there may be no UI yet


local PLATE_STOCK = { bg = {color = {0, 0, 0, 255}} }
local LABEL_STOCK = { font = hafen.font():get("serif"):derive():size(11),
                      color = {255, 230, 140} }

-- ------------------------------------------------------------------ the look of ui/tt/level
--
-- Level.drawmeter is not a rectangle. Its backdrop spans the strip's width and is two pixels taller than
-- the strip; its two end caps are one-pixel lines that span only the strip's own rows. So the four corner
-- pixels are never painted, and that cut corner is what reads as a rounded end.
--
-- Reproducing it is not a matter of copying the arithmetic, because that arithmetic is written in two units
-- at once: the strip's height and the margin are UI.scale'd, while the 1s, 2s and 3s that frame them are raw
-- SCREEN pixels. A frame written as "1" through a design-pixel verb is one screen pixel on an unscaled client
-- and two on one at interface scale 2 -- and a corner cut out of a design pixel cannot be cut at all.
--
-- So this draws in SCREEN pixels, through the one verb that can. g:poly's vertices are unrounded -- they
-- reach the GPU as floats -- so a fractional design coordinate lands on the device pixel it names, where
-- g:frect and g:line round to whole design pixels first. Everything below is therefore Level's own
-- arithmetic, in Level's own unit, and the bar is that bar rather than an approximation of it.

local WEAR_KEY = "wear"

local SCALE = hafen.ui():scale()

local function dev(n)                  -- design -> device, rounded the way UI.scale rounds
  return math.floor((n * SCALE) + 0.5)
end

local BAR_MARGIN = dev(1)              -- Level.m
local BAR_HEIGHT = dev(2)              -- Level.h

local FRESH_COLOUR = { 70, 200,  80}   -- nothing worn off yet
local HALF_COLOUR  = {235, 215,  70}   -- half gone
local WORN_COLOUR  = {220,  60,  60}   -- at the end of it, and the wear row is red by now too

-- ------------------------------------------------------------------ state

local sessionSubscriptions = {}        -- Session -> the one subscription that watches its icons

-- ------------------------------------------------------------------ drawing the bar

local function mix(from, to, t)
  return {
    math.floor(from[1] + ((to[1] - from[1]) * t) + 0.5),
    math.floor(from[2] + ((to[2] - from[2]) * t) + 0.5),
    math.floor(from[3] + ((to[3] - from[3]) * t) + 0.5),
  }
end

-- Green while it is whole, red once it is spent, and the same yellow in the middle both ways.
local function wearColour(remaining)
  if remaining > 0.5 then
    return mix(HALF_COLOUR, FRESH_COLOUR, (remaining - 0.5) * 2)
  end
  return mix(WORN_COLOUR, HALF_COLOUR, remaining * 2)
end

-- A filled rectangle stated in SCREEN pixels, as a quad wound for the triangle fan g:poly draws.
local function screenRect(graphics, x, y, w, h)
  if (w <= 0) or (h <= 0) then return end
  local left,  top    = x / SCALE, y / SCALE
  local right, bottom = (x + w) / SCALE, (y + h) / SCALE
  graphics:poly(left, top, right, top, right, bottom, left, bottom)
end

-- `lift` raises the whole bar clear of one the client has already drawn on this icon, in screen pixels.
local function drawMeter(graphics, width, height, lift, fraction, colour)
  local iconWidth  = dev(width)
  local iconBottom = dev(height) - lift
  local troughWidth = iconWidth - 3 - (BAR_MARGIN * 2)
  if troughWidth < 3 then return end

  -- The backdrop, and then the two caps beside it. The caps are the strip's own height and no more, which
  -- is what leaves the corners of the backdrop unpainted and the ends of the bar rounded.
  graphics:color(0, 0, 0, 255)
  screenRect(graphics, 1 + BAR_MARGIN, iconBottom - 3 - BAR_MARGIN - BAR_HEIGHT,
             troughWidth, BAR_HEIGHT + 2)
  screenRect(graphics, BAR_MARGIN, iconBottom - 2 - BAR_MARGIN - BAR_HEIGHT, 1, BAR_HEIGHT)
  screenRect(graphics, iconWidth - 2 - BAR_MARGIN, iconBottom - 2 - BAR_MARGIN - BAR_HEIGHT, 1, BAR_HEIGHT)

  local fillWidth = math.floor(fraction * (iconWidth - 2 - (BAR_MARGIN * 2))) - 1
  if fillWidth > 0 then
    graphics:color(colour)
    screenRect(graphics, 1 + BAR_MARGIN, iconBottom - 2 - BAR_MARGIN - BAR_HEIGHT, fillWidth, BAR_HEIGHT)
  end
  graphics:color()
end

-- ------------------------------------------------------------------ what the item says

-- What a bar takes off the icon's bottom edge, in screen pixels: its top row is this far up.
local BAR_TOP = 3 + BAR_MARGIN + BAR_HEIGHT

-- The whole bar's height plus a pixel, for getting out of the way of one the client draws.
local function stackHeight()
  return BAR_HEIGHT + 3
end

-- The plate stands on the bar's own rectangle: its left edge on the bar's leftmost column, its bottom edge
-- on the bar's bottom edge -- so the two line up whether or not the item carries a bar. Both are stated in
-- DESIGN pixels, the unit a widget's position is written in, and both come from Level's SCREEN-pixel
-- arithmetic: the bar's left cap is at m, and its bottom edge a pixel below m again. The two are not the
-- same number of screen pixels, which is why they are not the same number here either.
local PLATE_LEFT   = 1                                     -- = BAR_MARGIN screen px: Level.m
local PLATE_BOTTOM = math.ceil((BAR_MARGIN + 1) / SCALE)   -- = where Level's own bottom edge sits
local PLATE_GAP    = 1                                     -- ...and the air over a bar the icon does carry

-- Where the plate sits above the icon's bottom edge. Rounded up, so it clears a bar rather than landing a
-- fraction of a design pixel inside it.
local function labelLift(occupied)
  if occupied <= 0 then return PLATE_BOTTOM end
  return math.ceil(occupied / SCALE) + PLATE_GAP
end

-- Does the client itself already paint a bar down there? A liquid container's fill meter IS ui/tt/level, so
-- asking for its two counts is asking whether that bar is on this icon.
local function clientDrawsABar(item)
  local contents = item:contents()
  return (contents ~= nil) and (contents:fill() ~= nil)
end

-- The label says the quality of what you are looking at, and for an item that holds something that is what
-- is inside rather than what is around it. There are two kinds of inside and the client is never told which
-- it has, so both are asked for in turn.
--
-- A STACK is several real items under one icon, each with its own quality and none of them the stack's, so
-- the number is the mean of what it holds -- over the parts that can state one yet. A part whose tooltip has
-- not landed is left out of the average rather than counted as a zero, and a stack none of whose parts have
-- landed reads nil and stays bare.
local function meanQuality(item)
  local held = item:contents()
  if held == nil then return nil end
  local total, counted = 0, 0
  for _, part in ipairs(held:items():list()) do
    local quality = part:quality()
    if quality ~= nil then
      total = total + quality
      counted = counted + 1
    end
  end
  if counted == 0 then return nil end
  return total / counted
end

-- A LIQUID CONTAINER carries no items at all -- it states what it holds -- so :items() is empty, the mean
-- has nothing to average, and contents:quality() is the water's own. That is the number the label wants: the
-- jug's own quality is the jug's, and reading it here would put the vessel's number on the drink.
local function qualityOf(item)
  local held = item:contents()
  if held ~= nil then
    local inside = meanQuality(item) or held:quality()
    if inside ~= nil then return inside end
  end
  return item:quality()
end

-- cur is what has been worn away, so what is left is the other side of it. An item that prints no wear row
-- answers nil, and so does one whose tooltip has not arrived yet -- which is most icons, for a moment.
local function remainingOf(item)
  local durability = item:durability()
  if (durability == nil) or (durability.max == nil) or (durability.max <= 0) then return nil end
  local remaining = 1 - (durability.cur / durability.max)
  if remaining < 0 then return 0 end
  if remaining > 1 then return 1 end
  return remaining
end

-- ------------------------------------------------------------------ one icon

local function decorateIcon(icon)
  local item = icon:item()
  if item == nil then return end

  -- Built into the icon, so both die with it and there is nothing to release: an icon is destroyed and
  -- rebuilt every time its item moves, and the one that replaces it arrives here on its own. Adoption is a
  -- build-time verb, so the parent is named in the chain that builds each.
  --
  -- THE ICON MAY DIE UNDER THIS FUNCTION. A handler is called with the tree's monitor released, so the
  -- widget it was handed is alive when it is handed over and not necessarily a statement later -- and moving
  -- items about destroys and rebuilds icons continuously. A write on a stale widget chains as a no-op, but
  -- the two verbs that cannot say nothing raise: adopting into a widget that has left the tree, and
  -- subscribing on one. So each is asked whether it still has a parent before the next one is spoken, and an
  -- icon that lost the race is simply left alone -- it is on its way out, and its replacement arrives here.
  local plate = hafen.ui():widget():parent(icon):name(PLATE_NAME):stock(PLATE_STOCK)
  if not plate:exists() then return end
  -- :position(0, 0) is not decoration: a widget is BORN at the client's default place (100, 100) and
  -- :parent(w) keeps the place it was given, so a label never positioned sits far outside a plate this size
  -- and is clipped away -- present, sized, and invisible.
  local label = hafen.ui():label():parent(plate):position(0, 0):name(LABEL_NAME):stock(LABEL_STOCK)
  if not label:exists() then return end

  local fitted                                           -- the icon height `fit` last laid the plate out against

  -- A Label's box is exactly the text it renders, so writing a new number resizes it -- and the plate is
  -- that box, or the two-digit floor, whichever is wider. The number is centred in what is left over, so a
  -- single digit sits in the middle of the box rather than against its left edge.
  local function fit(occupied)
    if minWidth == nil then                              -- once, on the first label there ever is
      local written = label:text()
      label:text(MIN_DIGITS)
      local floor = label:size()
      label:text(written or "")
      if floor == nil then return end                    -- ...unless that label is already gone; the next one
      minWidth = floor.w                                 --    measures it instead
    end

    -- A READ ON A STALE WIDGET ANSWERS NIL, and any of these three can go stale between two lines here for
    -- the reason decorateIcon states. There is nothing to lay out then: the icon is on its way out and its
    -- replacement is already on its way in.
    local box, within = label:size(), icon:size()
    if (box == nil) or (within == nil) then return end
    fitted = within.h
    local width = math.max(box.w, minWidth)
    plate:size(width, box.h)
    plate:position(PLATE_LEFT, within.h - labelLift(occupied) - box.h)
    label:position(math.floor((width - box.w) / 2), 0)
  end

  -- AN ICON IS BORN ONE CELL AND GROWS LATER. The client builds an item's widget at the inventory's square
  -- size and gives it the item's real footprint on the first tick after the item's SPRITE resolves -- so the
  -- icon for a board or a bar is 1x3 a moment after it was 1x1. Nothing announces that: a position is a
  -- coordinate rather than a relationship, and the client's own resize is not the `Resized` a widget
  -- subscribes to. So a plate laid out against the smaller box sits a whole cell too high for the rest of
  -- that icon's life -- which is exactly what makes moving the item appear to fix it: the icon is destroyed,
  -- rebuilt, and the race is rolled again.
  --
  -- The resize is applied by the icon's OWN tick, and by the next one it has certainly run: whatever brought
  -- us here -- a tooltip landing, or a revision of one -- waited on the item's resource, which is the very
  -- thing the sprite waits on. So one deferred look is the whole of the answer, and on an icon that was its
  -- full size already it costs a comparison and nothing else.
  local function layout(occupied)
    fit(occupied)
    local against = fitted
    hafen.timer():after(0, function()
      local within = icon:size()
      if (within ~= nil) and (within.h ~= against) then fit(occupied) end
    end)
  end

  -- Read on the tooltip's arrival rather than in the painter: these numbers change once in a while and the
  -- painter runs sixty times a second.
  local function refresh()
    if not icon:exists() then return end                   -- the item outlived this icon by a frame

    -- No number, no plate. A label with nothing in it is still a box once the plate has a floor under its
    -- width, so an item that cannot state a quality would wear an empty black square instead of nothing at
    -- all: one whose tooltip has not landed, and a STACK ON THE CURSOR, which states neither a quality of
    -- its own nor what it holds -- the server sends the dragged item as its own widget and attaches no
    -- contents to it, so there is nothing to average until it lands somewhere.
    local quality = qualityOf(item)
    label:text((quality ~= nil) and tostring(math.floor(quality + 0.5)) or "")
    plate:visible(quality ~= nil)

    -- A bar down there is the label's business as well: it is drawn in the same corner, so the label goes
    -- above whichever bars the icon carries -- ours, the client's own fill meter, or both stacked.
    local remaining = remainingOf(item)
    local lift = (remaining ~= nil) and clientDrawsABar(item) and stackHeight() or 0
    local occupied = 0
    if remaining ~= nil then
      occupied = lift + BAR_TOP
    elseif clientDrawsABar(item) then
      occupied = BAR_TOP
    end
    layout(occupied)

    if remaining == nil then
      icon:overlay():remove(WEAR_KEY)                      -- inert on an icon carrying none
      return
    end

    local colour = wearColour(remaining)
    icon:overlay():add(WEAR_KEY):draw(function(graphics, width, height)
      drawMeter(graphics, width, height, lift, remaining, colour)
    end)
  end

  refresh()                                                -- an icon the tooltip beat here
  if not icon:exists() then return end                     -- ...or an icon that did not outlive its own build

  -- The item outlives the icon: moving it to another slot destroys this widget and builds another, which
  -- arrives here on its own. This one's listener would otherwise go on writing to overlays nobody draws.
  --
  -- THE ICON'S SUBSCRIPTION IS TAKEN FIRST and reads the item's out of an upvalue, because the icon is the
  -- one that can still refuse: it is the one that may have left the tree since the line above. Taking the
  -- item's first would leave, on that refusal, a listener with nothing holding the end of it.
  local revisions
  icon:on("Removed", function()
    if revisions ~= nil then
      revisions:off()
    end
  end)

  -- ONE listener covers the stack's parts as well, and the client is what makes that true: a part's tooltip
  -- bumps the part's own revision, the stack sees it on its next tick, drops the info it had built, and the
  -- rebuild that follows IS this Changed. A part leaving arrives the same way, by the contents' child count.
  -- So the mean is a frame behind the part and no more, and a stack of eight costs what a stack of one does
  -- -- where a listener per part would cost a refresh per part, each one re-reading every other.
  revisions = item:on("Changed", refresh)                  -- ...and every icon, a moment later
end

-- ------------------------------------------------------------------ lifecycle

local function forgetSession(session)
  local subscription = sessionSubscriptions[session]
  if subscription == nil then return end
  subscription:off()
  sessionSubscriptions[session] = nil
end

-- "item" is every icon an item is drawn as: a container slot, an equipment slot, and the cursor while the
-- item is carried. Subscribing reports the ones already on screen as well as the ones built later, so there
-- is nothing to walk and nothing to wait for.
local function watchSession(session)
  forgetSession(session)                                   -- a character switch keeps the session, not its tree
  sessionSubscriptions[session] = session:ui():on("item", "Added", decorateIcon)
end

hafen.event():on("SessionEnteredWorld", watchSession)

hafen.event():on("SessionRemoved", forgetSession)
