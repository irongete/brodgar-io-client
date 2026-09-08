-- Builder helper -- remembers what every building site you have opened still needs, and one key floats
-- each material's have/total over the site itself.
--
-- A building site is one gob for every building in the game (gfx/terobjs/consobj); what it is becoming and
-- what it still wants are not on the object. They are in the window the server opens when you right-click
-- it: one material box (@ISBox) per material, each drawing an icon and a "have/total" figure, and the
-- window's caption is the building's name. Nothing on the wire ties that window to the site, so the addon
-- watches the two gestures that open one -- a right-click on a site, and placing a new one -- and claims the
-- window that comes for the gesture. A build window opening while a right-click stands is that click's. One
-- that was already open -- for a site just placed, or one you had open before -- is taken once the drawn
-- character has stood still for a few seconds with no window come for the click: the server answers a
-- click within the round trip after you arrive, so a window that has not come by then is not coming, and
-- the newest build window nothing has claimed is the site's. A click on the ground keeps the gesture (that
-- is you steering the walk); a click on any other object, or placing something, ends it.
--
-- A claimed window is then READ WHOLE: its boxes, in tree order, ARE the site's materials, and the record
-- is that reading. Everything that can change what the window says goes through the same read -- the claim
-- itself, a box arriving or leaving, a figure moving after the record was dropped -- so the record is
-- always the window's own answer rather than a pile of arrivals, and a record left wrong by anything at all
-- is rewritten whole the next time its window is read.
--
-- A site is filed by its place, as a Position, and Position is durable: it saves as a grid id plus an offset
-- within the grid, which is the one anchor that means the same thing to every character. The record lives
-- in an account-scope saved variable, so a site opened by one character is known to all of them and
-- survives a restart. Once every material is complete the record is dropped, since there is nothing left to
-- show -- and a figure moving in a window still open brings it straight back.
--
-- The labels are one painter per site, hung on the gob at ground level: for each material, the material's
-- own icon and its figure, stacked upward from the ground the site stands on. The painter reads the
-- record, so a change the window reports (a "chnum" update on a box, while it is open) shows on the next
-- frame with nothing re-attached. WHETHER A SITE WEARS ITS PAINTER IS ASKED OF THE GOB, never of a table
-- of our own: gob:overlay():get(KEY) is the one truth, so a painter lost by any means is simply hung again
-- the next time the site is asked. Anything about a gob that has not resolved yet -- ground not yet
-- durable, a name still streaming, a drawing the client is still resolving -- is waited out on a timer
-- while the object exists, because the client has no event for any of those moments.
--
-- Suggested key: Ctrl+B -- assign it in Options > Game > Keybindings > Builder helper.

local SITE = "gfx/terobjs/consobj"
local KEY = "materials"      -- our overlay key on a site; keys are per addon

local ICON = 16              -- the material icon's box, design pixels
local GAP = 4                -- between the icon and the figure
local ROW = 18               -- one material per row
local PAD = 3                -- the plate's margin round the column
local PLATE = {30, 30, 30, 160}   -- dark grey, translucent, behind the column

local ADOPT = 3              -- seconds the drawn character stands still with no window come for the click
                             --   before the open one is taken
local PLACED = 22            -- world units: how near a site arriving must stand to where the ghost was
                             --   dropped to be the one placed
local RETRY = 0.5            -- seconds between re-asking what has not resolved

local FONT = hafen.font():get("sans"):derive():size(11):bold(true):outline{0, 0, 0}
local PENDING = {255, 255, 255}
local DONE = {140, 230, 140}

-- The one option: a complete material is drawn green, or not at all. The value is cached in a local
-- because the painter reads it every frame.
local hideDoneOpt = hafen.client():options():addon():boolean("hide-done")
  :label("Hide completed materials")
  :tooltip("Leave a material out of the column once the site holds all of it, instead of drawing it green")
  :default(false):add()
local hideDone = hideDoneOpt:value()
hideDoneOpt:on("Changed", function(v) hideDone = v end)

-- Saved: [key] = {pos = Position, name = "Stonestead", rows = {{res = "gfx/invobjs/...", text = "2/150",
-- have = 2, total = 150}, ...}}. Everything in it is plain data or a Position, so it round-trips as-is.
local sites = hafen.store():get("sites")

-- Whether the labels are up. It is saved, so reloading the addon or restarting the client leaves them
-- exactly as you left them: a display you turned on and walked away from does not quietly go out.
local shown = hafen.store():get("shown")
local showing = shown.on == true

local gesture = nil          -- {gob, session, still, timer}: the site a right-click named, until its window
local placed = nil           -- {pos, session} after a "place", until the site arrives or a while passes
local claims = {}            -- [Window] = {gob = Gob, key = string, pos = Position}: the site a build
                             --   window is about; key and pos once its place has been read
local queued = {}            -- [Window] = true while a read of it is already scheduled
local boxes = {}             -- [ISBox] = {win = Window, row = number}: where that box's figure belongs
local waiting = {}           -- [Gob] = true while something about it has not resolved yet
local sweep = nil            -- the one timer that re-asks `waiting`, running only while it holds anything
local measured = {}          -- [text] = width in design pixels, measured once per figure
local flushing = false       -- a write of the record is already owed, a moment from now

-- Write the record down, once for a burst: a transfer into a site is one "chnum" per item moved.
local function flushSoon()
  if flushing then return end
  flushing = true
  hafen.timer():after(1, function()
    flushing = false
    hafen.store():flush()
  end)
end

-- A place, as a string a table can be keyed by, and the Position it was read from. The offset within the
-- grid is rounded so the same site read twice keys the same entry. nil while the ground is not durable.
local function keyOf(gob)
  local p = gob:position()
  local i = p and p:info()
  if not i then return nil end
  return i.gridId .. ":" .. math.floor(i.x + 0.5) .. ":" .. math.floor(i.y + 0.5), p
end

local function isSite(gob)
  return gob:name() == SITE
end

local function done(row)
  return row.have ~= nil and row.total ~= nil and row.have >= row.total
end

local function complete(site)
  if #site.rows == 0 then return false end
  for _, row in ipairs(site.rows) do
    if not done(row) then return false end
  end
  return true
end

local function width(text)
  local w = measured[text]
  if not w then
    w = hafen.ui():measure(text, {font = FONT}).w
    measured[text] = w
  end
  return w
end

local function parse(text)
  local have, total = text:match("^(%d+)/(%d+)")
  return tonumber(have), tonumber(total)
end

-- Every object any of your characters has loaded, each once: a Gob is one object however many see it.
local function eachGob(fn)
  local seen = {}
  for _, session in ipairs(hafen.session():list()) do
    for _, gob in ipairs(session:world():gob():list()) do
      if not seen[gob] then
        seen[gob] = true
        fn(gob)
      end
    end
  end
end

-- ---- the labels --------------------------------------------------------------------------------------

-- The painter for one record. It reads the record every frame, so what the window last said is what is
-- drawn, and a record dropped draws nothing.
local function paint(key)
  return function(g, _, sx, sy)
    local site = sites[key]
    if not site then return end
    local rows = site.rows
    if hideDone then
      rows = {}
      for _, row in ipairs(site.rows) do
        if not done(row) then rows[#rows + 1] = row end
      end
    end
    if #rows == 0 then return end
    local top = sy - #rows * ROW
    local widest = 0
    for _, row in ipairs(rows) do
      local w = ICON + GAP + width(row.text)
      if w > widest then widest = w end
    end
    g:color(PLATE)
    g:frect(sx - widest / 2 - PAD, top - PAD, widest + 2 * PAD, #rows * ROW + 2 * PAD)
    g:color()
    for i, row in ipairs(rows) do
      local y = top + (i - 1) * ROW
      local x = sx - (ICON + GAP + width(row.text)) / 2
      if row.res then g:resource(row.res, x, y, ICON, ICON) end
      g:text(row.text, x + ICON + GAP, y + 1, {font = FONT, color = done(row) and DONE or PENDING})
    end
  end
end

-- Hang the painter on one site, at height 0: the point is the ground the site stands on, and the column
-- rises from it. Answers whether it is hung. The gob itself says whether it already wears one, so a painter
-- lost by any means is hung again here. An object whose own drawing is still resolving takes no overlay
-- in that instant, and one that has gone takes none at all: both answer false, for the caller to wait or
-- to stop. Any other refusal is a defect and is raised.
local function label(gob, key)
  if gob:overlay():get(KEY) then return true end
  local ok, err = pcall(function()
    gob:overlay():add(KEY):draw(paint(key)):height(0)
  end)
  if ok then return true end
  if not gob:exists() or tostring(err):find("still resolving", 1, true) then return false end
  error(err, 0)
end

local point                  -- point(gob, session, atOnce), below: settle() names a site placed

-- Ask one gob everything the addon wants of it, and answer whether it has all been answered: false while
-- something has not resolved yet, so the caller keeps asking. Its place comes first, because the place is
-- what says whether the gob is anybody's business at all; its name is only read for a gob standing where
-- a ghost was just dropped or on a remembered site, so an object that never resolves a name and is
-- neither is done at once.
local function settle(gob)
  local key, pos = keyOf(gob)
  if not key then return false end                  -- its ground is not durable yet
  if placed then
    local d = pos:distance(placed.pos)
    if d and d < PLACED then
      local name = gob:name()
      if not name then return false end
      if name == SITE then
        local session = placed.session
        placed = nil
        point(gob, session, true)
      end
    end
  end
  if not sites[key] then return true end
  local name = gob:name()
  if not name then return false end
  if name ~= SITE or not showing then return true end
  return label(gob, key)
end

-- Ask, and keep asking while the answer is "not yet" and the object exists. One timer serves every such
-- gob, and runs only while there is one. A refusal that is not a "not yet" stops the asking of that gob
-- and is raised, once, rather than raised again every beat.
local function ask(gob)
  if not gob:exists() then return end
  if settle(gob) then
    waiting[gob] = nil
    return
  end
  waiting[gob] = true
  if sweep then return end
  sweep = hafen.timer():every(RETRY, function()
    for g in pairs(waiting) do
      if not g:exists() then
        waiting[g] = nil
      else
        local ok, settled = pcall(settle, g)
        if not ok then
          waiting[g] = nil
          error(settled, 0)
        end
        if settled then waiting[g] = nil end
      end
    end
    if next(waiting) == nil then
      sweep:cancel()
      sweep = nil
    end
  end)
end

local function showAll()
  eachGob(ask)
end

local function hideAll()
  waiting = {}
  if sweep then
    sweep:cancel()
    sweep = nil
  end
  eachGob(function(gob) gob:overlay():remove(KEY) end)
end

-- ---- the record --------------------------------------------------------------------------------------

-- Drop a site: its record and its painter wherever it is hung. What is claimed stays claimed: a completed
-- site whose window is still open is one material out of complete again the moment the server says so, and
-- that update reaches the claim.
local function forget(key)
  sites[key] = nil
  eachGob(function(gob)
    if gob:overlay():get(KEY) and keyOf(gob) == key then gob:overlay():remove(KEY) end
  end)
  hafen.store():flush()
end

-- The window a widget stands in, by ROLE and not by class name: the client wraps windows in subclasses,
-- and "window" is the one word true of all of them. A widget the client has already removed carries no
-- parent, so this answers nil for one.
local function windowOf(w)
  local p = w:parent()
  while p and p:role() ~= "window" do p = p:parent() end
  return p
end

-- The build windows in one character's tree: every window with a material box in it, each once.
local function buildWindows(session)
  local out, seen = {}, {}
  for _, b in ipairs(session:ui():matchAll("@ISBox")) do
    local win = windowOf(b)
    if win and not seen[win] then
      seen[win] = true
      out[#out + 1] = win
    end
  end
  return out
end

local read                   -- read(win), below: schedule() and it call each other

-- One read of a window per step, however many boxes report in it.
local function schedule(win, delay)
  if queued[win] then return end
  queued[win] = true
  hafen.timer():after(delay or 0, function() read(win) end)
end

-- Let a window go: it is nobody's site now, and its boxes say nothing about any record. Both halves, or
-- neither -- a box left bound to a record its window no longer stands for writes another site's figures
-- into it every time one moves.
local function release(win)
  claims[win] = nil
  for w, b in pairs(boxes) do
    if b.win == win then boxes[w] = nil end
  end
end

-- READ ONE CLAIMED WINDOW WHOLE. Its boxes, in tree order, are the materials of the site it was claimed
-- for, in the order the window lists them, so the record it writes is complete and in order whatever the
-- events did. The site's place is read here, off its gob, the first time: the ground under a site clicked
-- across the yard may not be durable yet, and a read is what waits for it. A window the client has taken
-- away is released instead.
read = function(win)
  queued[win] = nil
  local claim = claims[win]
  if not claim then return end
  if not win:exists() then
    release(win)
    return
  end
  if not claim.key then
    if not claim.gob:exists() then
      release(win)
      return
    end
    local key, pos = keyOf(claim.gob)
    if not key then
      schedule(win, RETRY)
      return
    end
    claim.key, claim.pos = key, pos
  end
  local list = win:matchAll("@ISBox")
  if #list == 0 then return end
  local rows, streaming = {}, false
  for i, w in ipairs(list) do
    local text = w:text()
    local have, total = parse(text)
    local res = w:res()       -- the material's picture may still be streaming; it names itself later
    if not res then streaming = true end
    rows[i] = {res = res, text = text, have = have, total = total}
    boxes[w] = {win = win, row = i}
  end
  local key = claim.key
  sites[key] = {pos = claim.pos, name = win:text(), rows = rows}
  if complete(sites[key]) then
    forget(key)
    return
  end
  hafen.store():flush()
  if showing then ask(claim.gob) end
  if streaming then schedule(win, RETRY) end
end

-- ---- the gesture -------------------------------------------------------------------------------------

local function endGesture()
  if gesture and gesture.timer then gesture.timer:cancel() end
  gesture = nil
end

-- This window is that site's, from now until it closes -- and the gesture that named the site is done.
local function stake(win, g)
  claims[win] = {gob = g.gob}
  win:on("Removed", function() release(win) end)
  schedule(win)
  if gesture == g then endGesture() end
end

-- The build window already open is the site's: the newest one nothing has claimed, in the gesture's own
-- character's tree -- the server's widget ids count up, so the highest id is the one opened last.
local function adopt(g)
  local best, id
  for _, win in ipairs(buildWindows(g.session)) do
    local wid = win:id()
    if not claims[win] and wid and (not id or wid > id) then best, id = win, wid end
  end
  if best then stake(best, g) end
end

-- Name a site: the gesture in flight is this site until a window is found for it. A site just placed
-- (atOnce) takes the window already open at once, because the server opens it before the object arrives;
-- a click waits, and takes the open one only once the character has stood still ADOPT seconds without
-- one coming, which is a click on a site whose window already stands.
point = function(gob, session, atOnce)
  endGesture()
  local g = {gob = gob, session = session, still = 0}
  gesture = g
  if atOnce then adopt(g) end
  if gesture ~= g then return end
  g.timer = hafen.timer():every(RETRY, function()
    if not g.gob:exists() or not g.session:exists() then
      endGesture()
      return
    end
    local me = g.session:player():gob()
    if me and me:moving() then
      g.still = 0
    else
      g.still = g.still + RETRY
    end
    if g.still >= ADOPT then
      adopt(g)
      endGesture()
    end
  end)
end

-- A box came up: in a claimed window, which is read again, or in a window opened while a click on a site
-- stands, which is that site's. One nothing named stays as it is, for a later gesture to take.
local function boxAdded(session, w)
  local win = windowOf(w)
  if not win then return end
  if claims[win] then
    schedule(win)
    return
  end
  if gesture and gesture.session == session then stake(win, gesture) end
end

-- A box left a window while it stayed open: what its window lists has changed, so the window is read again.
local function boxRemoved(w)
  local b = boxes[w]
  boxes[w] = nil
  if b and claims[b.win] then schedule(b.win) end
end

-- A map click. It runs where the click is sent, so it only records what it saw and the step does the rest.
-- A right-click on a site names it -- and asks the site, so a remembered one you clicked wears its label
-- whether or not a window comes. A click on the ground is you steering the walk, and changes nothing.
-- A click on any other object ends the gesture -- and a right-click on the object standing on a
-- remembered site's place is the building that took the site's place, so that record is dropped.
hafen.event():action():on("click", function(ev)
  if ev:widget():type() ~= "MapView" then return end
  local gob = ev:gob()
  if not gob then return end
  local button, session = ev:args()[3], ev:widget():session()
  hafen.timer():after(0, function()
    if button == 3 and isSite(gob) then
      point(gob, session, false)
      if showing then ask(gob) end
      return
    end
    endGesture()
    if button == 3 then
      local key = keyOf(gob)
      if key and sites[key] then forget(key) end
    end
  end)
end)

-- Placing a building: the server stands the site where the ghost was dropped and opens its window on its
-- own, with no click on the site to name it. The place is kept until a site stands on it, and the gesture
-- of any earlier click is over.
hafen.event():action():on("place", function(ev)
  if ev:widget():type() ~= "MapView" then return end
  local pos, session = ev:position(1), ev:widget():session()
  hafen.timer():after(0, function()
    endGesture()
    placed = {pos = pos, session = session}
    local mine = placed
    hafen.timer():after(10, function()
      if placed == mine then placed = nil end
    end)
  end)
end)

-- The figure moved while the window is open (materials put in, or taken out). This runs as the update
-- arrives, BEFORE the box applies it, so the new figure is read off the message and written into the row;
-- the painter shows it on the next frame, and the step drops a completed site. A site already dropped for
-- being complete is read back off its window instead -- one material out again is a site with something
-- to show.
hafen.event():message():on("chnum", function(ev)
  local b = boxes[ev:widget()]
  if not b then return end
  local claim = claims[b.win]
  if not claim then return end
  local site = sites[claim.key]
  if not site then
    schedule(b.win)
    return
  end
  local row = site.rows[b.row]
  if not row then
    schedule(b.win)
    return
  end
  local a = ev:args()
  row.have, row.total = a[1], a[2]
  if a[3] and a[3] >= 0 then
    row.text = a[1] .. "/" .. a[2] .. "/" .. a[3]
  else
    row.text = a[1] .. "/" .. a[2]
  end
  if complete(site) then
    local key = claim.key
    hafen.timer():after(0, function() forget(key) end)
  else
    flushSoon()
  end
end)

-- One pair of subscriptions per character's TREE: a session listed at load is watched here, and one
-- entering the world is watched from that event. It re-subscribes rather than skipping an account it has
-- already seen -- one account plays one character at a time and picking another hands it a new world, so
-- a subscription made against the tree that has gone hears nothing of the tree that replaced it. Ending
-- the old pair first is what keeps a re-entry from stacking a second one.
local watched = {}           -- [account name] = the two subscriptions standing on that character's tree
local function watch(session)
  local user = session:user()
  local had = watched[user]
  if had then
    for _, sub in ipairs(had) do sub:off() end
  end
  watched[user] = {session:ui():on("@ISBox", "Added", function(w) boxAdded(session, w) end),
                   session:ui():on("@ISBox", "Removed", boxRemoved)}
end

for _, session in ipairs(hafen.session():list()) do
  watch(session)
end
if showing then showAll() end

-- ---- the world ---------------------------------------------------------------------------------------

-- The key runs in the key press; it flips the switch and the step does the rest.
hafen.client():options():keybindings():on("toggle", function()
  showing = not showing
  shown.on = showing
  hafen.log():write("builder helper: labels " .. (showing and "on" or "off"))
  hafen.timer():after(0, function()
    hafen.store():flush()
    if showing then showAll() else hideAll() end
  end)
end)

-- A site arriving: the one just placed is named by where it stands, and a known one walking back into
-- view wears its label from its first frame -- or from the first frame after its ground and its name
-- have resolved.
hafen.event():on("GobAdded", ask)

hafen.event():on("SessionEnteredWorld", function(session)
  watch(session)
  if showing then showAll() end
end)

-- :builds            -- what is remembered, one line per site, each material named as the window drew it
-- :builds forget     -- drop every record, and everything claimed with them
hafen.console():on("builds", function(args)
  local sub = args[1]
  hafen.timer():after(0, function()
    if sub == "forget" then
      local keys = {}
      for key in pairs(sites) do keys[#keys + 1] = key end
      for _, key in ipairs(keys) do forget(key) end
      claims, queued, boxes = {}, {}, {}
      hafen.log():write("forgot " .. #keys .. " building site(s)")
      return
    end
    local live = {}                      -- which sites have a window standing for them right now
    for win, claim in pairs(claims) do
      if win:exists() and claim.key then live[claim.key] = true end
    end
    local n = 0
    for key, site in pairs(sites) do
      n = n + 1
      local parts = {}
      for _, row in ipairs(site.rows) do
        local mat = row.res and row.res:match("([^/]+)$") or "?"
        parts[#parts + 1] = mat .. " " .. row.text
      end
      hafen.log():write((site.name or "?") .. " @ " .. key .. (live[key] and " [window]" or "")
        .. ": " .. table.concat(parts, "  "))
    end
    if n == 0 then hafen.log():write("no building site remembered") end
    -- A build window nothing stands for is the one thing that looks like a fault and is not visible from
    -- the record: its figures move and no site hears them. Right-click that site to take it.
    local free = 0
    for _, session in ipairs(hafen.session():list()) do
      for _, win in ipairs(buildWindows(session)) do
        if not claims[win] then
          free = free + 1
          hafen.log():write("open and unclaimed: " .. (win:text() or "?"))
        end
      end
    end
    if free == 0 and n > 0 then hafen.log():write("every open build window is claimed") end
  end)
end)
