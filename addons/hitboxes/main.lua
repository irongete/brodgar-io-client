-- Hitboxes -- one key cycles the footprints of everything in view through three modes: off, laid on the
-- ground, and drawn through everything standing in front of them. Assign the key in
-- Options > Keybindings > Hitboxes.
--
-- The shape is gob:hitbox() in all three, the rings the object's own resource carries, and BOTH drawing
-- modes are the same patches on the same ground: hafen.virtual():patch() lies on the terrain with no float
-- and no gap, follows a slope, and one anchored to a Gob moves and ends with that object -- so this addon
-- keeps almost no bookkeeping of its own. The MODE is one flag on those patches:
--
--   ground  the world may hide it, which is what a patch does until it is told otherwise. A box behind a
--           wall is behind the wall and a box under a house is under it, so you read the boxes as part of
--           the world.
--   over    patch:occluded(false) -- the hills, the walls and the houses in front of a ring stop cutting it,
--           so a box inside a barn or over the brow of a hill is drawn whole, which is the point of the
--           mode. It is STILL on the ground: the same ring on the same relief with one test switched off,
--           not a flat shape thrown over the screen. And the interface is still over it, because a patch is
--           drawn inside the world and your windows are drawn after the world is finished -- so the boxes
--           never cover your inventory.
--
-- Everything else about how a box LOOKS -- the fill's colour and how much of the ground reads through it,
-- the rim's colour and how thick it is -- is four rows on the page, and not this file's to decide. Both
-- modes wear the same look, so what the page shows is what you get in either one and the mode is the flag
-- alone.
--
-- A look being a look, every one of those five rows re-dresses the patches already down rather than taking
-- them up: a tint, a border and the flag cost no terrain work, where laying a box again re-cuts every tile
-- under it. Only `off` lays and drops.
--
-- With the world not hiding them, two rings that overlap stack in whatever order the client draws them in,
-- and that order is not this addon's to set. Every box wears the same colours, so the picture is the same
-- either way.
--
-- The building you are PLACING wears a box in both. That one is not a game object -- it is the client's own
-- ghost on the cursor, in no session's object cache -- so it is read from the world rather than found among
-- the gobs: s:world():placing() is the handle, and placing:hitbox() is the same rings in the same units.
--
-- gob:hitbox() answers nil for a resource that carries no shape at all -- a decoration, most flooring --
-- and those are simply not drawn: there is no hitbox to show. Nor does a shape coming back mean the object
-- blocks movement; a felled log has a click-box and no collision. This addon draws what the verb answers.

local RESCAN = 2                    -- seconds between full re-reads
local TURN   = 0.2                  -- seconds between facing corrections -- see spin()
local SETTLE = 0.1                  -- seconds a look row waits before the boxes are re-dressed -- see soon()

local MODES = {"off", "ground", "over"}

-- THE PALETTE THE TWO DROPDOWNS OFFER. The client draws a checkbox, a slider, a dropdown, a text field, a
-- button and a line of text, and none of those six is a colour well -- so a colour is picked BY NAME here
-- and looked up when a patch is dressed. `blue` and `sky` are the two this addon has always drawn and they
-- are the defaults, so a client whose owner never opens the page looks exactly as it always did.
local COLOURS = {
  {"blue",   { 60, 140, 255}},
  {"sky",    {120, 190, 255}},
  {"white",  {255, 255, 255}},
  {"grey",   {150, 150, 150}},
  {"black",  {  0,   0,   0}},
  {"red",    {255,  70,  70}},
  {"orange", {255, 150,  40}},
  {"yellow", {255, 225,  60}},
  {"green",  { 70, 220, 110}},
  {"teal",   { 40, 210, 200}},
  {"purple", {170, 110, 255}},
  {"pink",   {255, 120, 200}},
}

local NAMES, RGB = {}, {}
for i, c in ipairs(COLOURS) do NAMES[i], RGB[c[1]] = c[1], c[2] end

-- WHAT THE BOXES ARE AND HOW THEY LOOK IS A SETTING, so all five are declared where the client keeps
-- settings: one page, Options > AddOns > Hitboxes. The rows are the WHOLE of the state -- this addon holds
-- no variable of its own beside them, and the key below moves the mode row rather than something of its
-- own -- so the page and the boxes cannot disagree, and neither can say something the other does not.
--
-- Every one of them is remembered, because the client stores what its own rows hold. A client left drawing
-- orange footprints comes back drawing orange footprints.
local opts = hafen.client():options():addon()

local modeOpt = opts:choice("mode")
  :label("Footprints")
  :tooltip("off, laid on the ground where each object stands, or drawn through everything in front of it")
  :choices(MODES)
  :default(MODES[1])
  :add()

local fillOpt = opts:choice("fill")
  :label("Fill colour")
  :tooltip("the colour laid over the ground an object stands on")
  :choices(NAMES)
  :default("blue")
  :add()

local alphaOpt = opts:number("opacity")
  :label("Fill opacity")
  :tooltip("per cent: how much of the fill is there, and so how much of the ground reads through it. " ..
           "0 leaves the rim standing on bare ground, which is a box drawn as an outline")
  :range(0, 100)
  :default(27)
  :add()

local edgeOpt = opts:choice("border")
  :label("Border colour")
  :tooltip("the rim round the ring, drawn solid whatever the opacity above says")
  :choices(NAMES)
  :default("sky")
  :add()

local widthOpt = opts:number("border-width")
  :label("Border thickness")
  :tooltip("hundredths of a world unit, and a tile is 11 of them -- so 35 is a thin line. 0 is the " ..
           "thinnest line the screen can draw and not no line: to be rid of the rim, give it the fill's colour")
  :range(0, 100)
  :default(35)
  :add()

-- THE STEP, AND NOT THE ROW. A row is answered inside the widget tree of whatever put the Options window
-- up, and a mode change is a full sweep of everything in view -- work for a step rather than for a press,
-- and work no handler should be doing while it holds a tree of its own (api/threading.md).
local function step(fn)
  hafen.timer():after(0, fn)
end

-- A slider fires Changed once per STEP OF A DRAG, so the four look rows are coalesced onto one short timer
-- rather than re-dressing every box on screen a hundred times over one pull of the mouse. It is restarted
-- rather than queued, so a whole drag pays one re-dress every tenth of a second and a single click pays
-- one -- and either way the work lands off the Options window's own tree, which is the other half of why.
local settle
local function soon(fn)
  if settle then settle:cancel() end
  settle = hafen.timer():after(SETTLE, function()
    settle = nil
    fn()
  end)
end

local laid = {}    -- [Gob] = {name = <resource when read>, own = {patch, ...},
                   --          base = <facing when read>, turn = <last written>}
local ghost        -- the same, for the ghost on the cursor
local ticker       -- the re-read; it runs in both drawing modes, and is nil in exactly one case: `off`
local turner       -- the facing correction

local function ground() return modeOpt:value() == "ground" end
local function off()    return modeOpt:value() == "off" end

local function patches()
  return hafen.virtual():patch()    -- the same object every call
end

-- ---------------------------------------------------------------- the look the page sets

-- A row holding a colour this palette no longer offers falls back to what the row itself declared rather
-- than raising -- dress() is called from redress(), where there is no pcall and a raise would spill.
local function rgb(opt)
  return RGB[opt:value()] or RGB[opt:default()]
end

-- The look every patch wears, read off the page each time one is dressed: a row IS its value, so there is
-- nowhere else for this to be kept and nothing to keep in step.
--
-- The fill takes the opacity row as its own fourth component, which on a patch is the fill's own opacity --
-- there is no picture under a patch for a blend strength to be measured against. The rim is left solid,
-- which is why the row above it says `fill`: :alpha(a) would have carried the border down with it, and a
-- border you can see through is a border you cannot follow across pale soil.
--
-- Only two things separate a `ground` box from an `over` one, and both are here: the flag, and nothing.
local function dress(patch)
  local c = rgb(fillOpt)
  local a = math.floor(((alphaOpt:value() * 255) / 100) + 0.5)
  return patch:tint({c[1], c[2], c[3], a})
              :border(rgb(edgeOpt), widthOpt:value() / 100)   -- the row is hundredths of a world unit
              :occluded(ground())
end

-- ---------------------------------------------------------------- reading a footprint

-- A ring the patch collection refuses is a shape this addon cannot lay, not an error worth spilling: an
-- obst layer is whatever the resource's author drew, so a concave one or one past the 32-edge limit is a
-- real shape to meet. Each ring goes on its own, so one bad ring in a set does not cost the others.
local function put(anchor, ring, own)
  local ok, patch = pcall(function()
    return dress(patches():add(ring, anchor))
  end)
  if ok and patch then own[#own + 1] = patch end
end

-- Read one object's footprint and lay it. Nothing is recorded for an object with no shape, so the sweep
-- comes back for it -- which is what picks up a resource that had not resolved yet.
--
-- The patches ARE the drawing and they hold the shape, so the rings are not kept: a patch anchored to the
-- Gob follows it over the ground, and `base`/`turn` are all that is left to correct.
local function read(g, name)
  local box = g:hitbox()
  if not box then return end
  local own = {}
  for _, ring in ipairs(box) do put(g, ring, own) end
  if #own == 0 then return end
  laid[g] = {name = name, own = own, base = g:facing() or 0, turn = 0}
end

local function drop(mine)
  for _, patch in ipairs(mine.own) do
    if patch:exists() then patches():remove(patch) end
  end
end

local function forget(g)
  local mine = laid[g]
  if not mine then return end
  laid[g] = nil
  drop(mine)
end

-- Re-read, never remembered: a felled tree keeps its id and becomes a log, so the box read for the tree is
-- the wrong box a moment later. The resource name is what says so, and it is a cheap read.
local function consider(g)
  local name = g:name()
  if name == nil then return end    -- not resolved yet; nothing to key a verdict on
  local mine = laid[g]
  if mine then
    if mine.name == name then return end
    forget(g)
  end
  read(g, name)
end

local function sweep()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then
      for _, g in ipairs(s:world():gob():list()) do consider(g) end
    end
  end
end

-- ---------------------------------------------------------------- keeping a laid box straight

-- gob:hitbox() hands back rings ALREADY turned by the object's facing, and a patch keeps them as offsets
-- from its anchor that the object's own turning does not turn. So a boar that comes about would wear its
-- box sideways -- unless the patch is turned by as much as the object has since its rings were read, which
-- is what this is. It is its own timer, and a fast one, because a running creature turns between sweeps;
-- it is affordable there because turning a patch re-carves the ground it already covers and rebuilds no
-- mesh, where taking a box up and laying it again re-cuts every tile under it.
local function spin()
  for g, mine in pairs(laid) do
    local now = g:facing()
    if now then
      local turn = now - mine.base              -- absolute: the patch was laid at a rotation of 0
      if turn ~= mine.turn then
        mine.turn = turn
        for _, patch in ipairs(mine.own) do
          if patch:exists() then patch:rotate(turn) end
        end
      end
    end
  end
end

-- ---------------------------------------------------------------- the ghost on the cursor

local function unlayGhost()
  if not ghost then return end
  local mine = ghost
  ghost = nil
  drop(mine)
end

-- The ghost is none of the above: it is not among the gobs, so no sweep finds it, and it has no Gob for a
-- patch to follow. So its box is laid at a PLACE and moved from here, every frame, because that is how
-- often a cursor moves -- and affordable because it is one object.
--
-- Only the DRAWN character's ghost is read. A Plob is drawn in its own session's view, so another login's
-- is not on your screen to want a box round; tab away mid-placement and the read goes nil, which takes the
-- box up by the same line a click does.
local function ghostFollow()
  local s = hafen.session():current()
  local pl = s and s:world():placing()
  if not pl then return unlayGhost() end
  local name = pl:name()
  if name == nil then return end                -- the resource the server named has not resolved yet
  if ghost and (ghost.name ~= name) then
    unlayGhost()                                -- something else is on the cursor now: read it again
  end
  local at = pl:position()
  if not at then return end
  if not ghost then
    local box = pl:hitbox()
    if not box then return end                  -- this one leaves no footprint: nothing to draw
    local own = {}
    for _, ring in ipairs(box) do put(at, ring, own) end
    if #own > 0 then
      ghost = {own = own, name = name, base = pl:facing() or 0, turn = 0, x = at:x(), y = at:y()}
    end
    return
  end
  local x, y = at:x(), at:y()
  if x and y and ((x ~= ghost.x) or (y ~= ghost.y)) then
    ghost.x, ghost.y = x, y
    -- A place a patch may stand at has to be one that can be KEPT, and the collection refuses one that
    -- cannot be. Under the cursor that is ground you are looking at, so it is durable -- but this runs every
    -- frame, which is the worst place in the file for a raise, so a refusal takes the box up instead and the
    -- next frame lays it again through the guarded path above.
    local ok = pcall(function()
      for _, patch in ipairs(ghost.own) do
        if patch:exists() then patch:position(at) end
      end
    end)
    if not ok then return unlayGhost() end
  end
  local now = pl:facing()
  if now then
    local turn = now - ghost.base
    if turn ~= ghost.turn then
      ghost.turn = turn
      for _, patch in ipairs(ghost.own) do
        if patch:exists() then patch:rotate(turn) end
      end
    end
  end
end

-- ---------------------------------------------------------------- the cycle

local function leave()
  if ticker then ticker:cancel() end
  if turner then turner:cancel() end
  ticker, turner = nil, nil
  unlayGhost()
  local was = laid
  laid = {}
  for _, mine in pairs(was) do drop(mine) end
end

local function enter()
  if off() then return end
  sweep()
  ghostFollow()
  ticker = hafen.timer():every(RESCAN, sweep)
  turner = hafen.timer():every(TURN, spin)
end

-- Every row but the mode's own means exactly this, and so does ground <-> over: the boxes stay where they
-- are and put on what the page now says. The ring, the anchor and the ground under every one of them are
-- the same before and after, so there is no half-converted set to be caught in -- there is nothing to
-- convert.
local function redress()
  for _, mine in pairs(laid) do
    for _, patch in ipairs(mine.own) do
      if patch:exists() then dress(patch) end
    end
  end
  if ghost then
    for _, patch in ipairs(ghost.own) do
      if patch:exists() then dress(patch) end
    end
  end
end

-- ONE PATH INTO A MODE, whether it was picked on the page or cycled with the key: the row is the setting,
-- so this is where the modes are left and entered, and the key below only moves the row. `ticker` is what
-- says whether boxes are down, which is the one question that decides between the three answers.
modeOpt:on("Changed", function(m)
  step(function()
    if off() then
      leave()
    elseif ticker then
      redress()
    else
      enter()
    end
    hafen.log():write("hitboxes: " .. m)
  end)
end)

-- The four look rows all say one thing to the boxes: wear it. None of them lays or drops anything, because
-- none of them changes WHAT is drawn -- only how, and a box that is not drawn has nothing to hear.
for _, o in ipairs({fillOpt, alphaOpt, edgeOpt, widthOpt}) do
  o:on("Changed", function() soon(redress) end)
end

-- The hotkey starts unbound: an addon names an action and the user assigns the key, in
-- Options > Game > Keybindings > Hitboxes.
hafen.client():options():keybindings():on("cycle", function()
  local at = 1
  for i, m in ipairs(MODES) do
    if m == modeOpt:value() then at = i end
  end
  modeOpt:value(MODES[(at % #MODES) + 1])
end)

-- GobAdded runs before the object's first drawn frame, so one that arrives with its resource already in
-- hand is boxed from that frame rather than at the next sweep.
hafen.event():on("GobAdded", function(g)
  if not off() then consider(g) end
end)

-- The patches went with it: one anchored to a Gob ends with that Gob, so there is nothing to take up and
-- only the entry goes.
hafen.event():on("GobRemoved", function(g)
  laid[g] = nil
end)

-- The per-frame beat, and the cursor is the whole of it: everything else follows the object it is anchored
-- to without being asked. Nothing at all runs while the boxes are off.
hafen.event():on("Update", function()
  if off() then return end
  ghostFollow()
end)

-- The mode is remembered now, so a client that starts in one enters it rather than waiting for a keypress.
-- On the step, because the sweep wants a world to read and this runs while the client is still coming up.
if not off() then step(enter) end
