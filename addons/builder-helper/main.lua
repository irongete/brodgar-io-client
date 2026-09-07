-- Builder helper -- remembers what every building site you have opened still needs, and one key floats
-- each material's have/total over the site itself.
--
-- A building site is one gob for every building in the game (gfx/terobjs/consobj); what it is becoming and
-- what it still wants are not on the object. They are in the window the server opens when you right-click
-- it: one material box (@ISBox) per material, each drawing an icon and a "have/total" figure, and the
-- window's caption is the building's name. So the addon watches for the two gestures that open it -- a
-- right-click on a site, and placing a new one, whose window the server opens on its own -- and binds the
-- boxes of that window to the place the site stands on. A window already open when the site is named is
-- claimed as well, so the order the window and the gesture come in does not matter. Any other click on the
-- map, left or right, on the ground or on another object, forgets the gesture.
--
-- A site is filed by its place, as a Position, and Position is durable: it saves as a grid id plus an offset
-- within the grid, which is the one anchor that means the same thing to every character. The record lives
-- in an account-scope saved variable, so a site opened by one character is known to all of them and
-- survives a restart; the labels are re-hung when the site walks into view again. Once every material is
-- complete the record is dropped, since there is nothing left to show.
--
-- The labels are one painter per site, hung on the gob at ground level: for each material, the material's
-- own icon and its figure, stacked upward from the ground the site stands on. The painter reads the
-- record, so a change the window reports (a "chnum" update on a box, while it is open) shows on the next
-- frame with nothing re-attached. The option "Hide completed materials" (Options > AddOns > Builder helper)
-- leaves a complete material out of the column instead of drawing it green.
--
-- Suggested key: Ctrl+B -- assign it in Options > Game > Keybindings > Builder helper.

local SITE = "gfx/terobjs/consobj"
local KEY = "materials"      -- our overlay key on a site; keys are per addon

local ICON = 16              -- the material icon's box, design pixels
local GAP = 4                -- between the icon and the figure
local ROW = 18               -- one material per row
local PAD = 3                -- the plate's margin round the column
local PLATE = {30, 30, 30, 160}   -- dark grey, translucent, behind the column

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

local showing = false
local pending = nil          -- {key = string, gob = Gob} after a right-click on a site, until the next click
local bound = {}             -- [ISBox widget] = {key = string, row = number}: the boxes of an open window
local windows = {}           -- [key] = the window whose boxes are bound, so a reopen starts the rows over
local settling = {}          -- [key] = true while the boxes of a window are still coming up
local labelled = {}          -- [Gob] = key while a painter of ours is on it
local measured = {}          -- [text] = width in design pixels, measured once per figure

-- A place, as a string a table can be keyed by. The offset within the grid is rounded so the same site
-- read twice keys the same entry.
local function keyOf(gob)
  local p = gob:position()
  local i = p and p:info()
  if not i then return nil, nil end
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

-- ---- the labels --------------------------------------------------------------------------------------

local function unlabel(gob)
  gob:overlay():remove(KEY)
  labelled[gob] = nil
end

-- Hang the painter on one site, at height 0: the point is the ground the site stands on, and the column
-- rises from it, its bottom row on that point. An object whose own drawing is still resolving takes no
-- overlay in that instant, so a refused attach is tried once more a moment later.
local function label(gob, key, retried)
  if labelled[gob] == key then return end
  local ok = pcall(function()
    gob:overlay():add(KEY):draw(function(g, _, sx, sy)
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
    end):height(0)
  end)
  if ok then
    labelled[gob] = key
  elseif not retried then
    hafen.timer():after(0.5, function()
      if showing and gob:exists() then label(gob, key, true) end
    end)
  end
end

local function showAll(session)
  for _, gob in ipairs(session:world():gob():list(isSite)) do
    local key = keyOf(gob)
    if key and sites[key] then label(gob, key) end
  end
end

local function hideAll()
  for gob in pairs(labelled) do
    gob:overlay():remove(KEY)
  end
  labelled = {}
end

-- ---- the record --------------------------------------------------------------------------------------

-- Drop a site: its record, its painter wherever it is hung, and the boxes bound to it.
local function forget(key)
  sites[key] = nil
  windows[key] = nil
  settling[key] = nil
  for gob, k in pairs(labelled) do
    if k == key then unlabel(gob) end
  end
  for w, b in pairs(bound) do
    if b.key == key then bound[w] = nil end
  end
end

-- Attach the label to the site the record was taken from, if it is in view and the labels are on.
local function labelPending(key)
  if not showing or not pending or pending.key ~= key then return end
  if pending.gob:exists() then label(pending.gob, key) end
end

local function windowOf(w)
  local p = w:parent()
  while p and p:type() ~= "Window" do p = p:parent() end
  return p
end

local function parse(text)
  if not text then return nil, nil end
  local have, total = text:match("^(%d+)/(%d+)")
  return tonumber(have), tonumber(total)
end

local seen = {}              -- [Window] = how many of its boxes have come up, which is the next box's index
local orphans = {}           -- [Window] = its boxes in order, up before any gesture named a site for it
local orphanOrder = {}       -- those windows, the most recent last

-- One box of one window goes on the site's record. The window that carries the record gets the rows
-- rebuilt from its boxes, in order; a second window of the same site, open beside it, lists the same
-- materials in the same order, so its boxes bind to the rows they stand at and keep them current too.
local function bind(w, win, key, index)
  local site = sites[key]
  local current = windows[key]
  if not site or (current ~= win and not (current and current:exists())) then
    site = {pos = pending.pos, name = win:text(), rows = {}}
    sites[key] = site
    windows[key] = win
  end
  local have, total = parse(w:text())
  local row
  if windows[key] == win then
    row = {res = w:res(), text = w:text() or "?", have = have, total = total}
    site.rows[#site.rows + 1] = row
    index = #site.rows
  else
    row = site.rows[index]
    if not row then return end
    row.res = w:res() or row.res
    row.text = w:text() or row.text
    row.have, row.total = have, total
  end
  bound[w] = {key = key, row = index}
  -- The material's resource may still be streaming, in which case it names itself a moment later.
  if not row.res then
    hafen.timer():after(0.5, function()
      if w:exists() and sites[key] and sites[key].rows[bound[w] and bound[w].row or 0] == row then
        row.res = w:res()
      end
    end)
  end
  -- The boxes of one window come up one after another within a step, so the site is judged once they
  -- all have: on the next step, a site with nothing left to bring is dropped, and every other is saved
  -- and labelled.
  if settling[key] then return end
  settling[key] = true
  hafen.timer():after(0, function()
    settling[key] = nil
    local now = sites[key]
    if not now then return end
    if complete(now) then
      forget(key)
      return
    end
    hafen.store():flush()
    labelPending(key)
  end)
end

-- A box came up: bound to the site the gesture in flight names, or kept aside until a gesture names one.
local function boxAdded(w)
  local win = windowOf(w)
  if not win then return end
  local n = (seen[win] or 0) + 1
  seen[win] = n
  if pending then
    bind(w, win, pending.key, n)
    return
  end
  if not orphans[win] then
    orphans[win] = {}
    orphanOrder[#orphanOrder + 1] = win
  end
  orphans[win][n] = w
end

-- The window a gesture names may already be open -- a site just placed opens its own, and a right-click on
-- a site whose window is up opens nothing new -- so the most recent window still up that nothing has
-- claimed is bound to the site the gesture named.
local function adopt()
  for i = #orphanOrder, 1, -1 do
    local win = orphanOrder[i]
    local boxes = orphans[win]
    orphans[win] = nil
    seen[win] = seen[win] and win:exists() and seen[win] or nil
    table.remove(orphanOrder, i)
    if win:exists() and boxes then
      for n, w in ipairs(boxes) do
        if w:exists() then bind(w, win, pending.key, n) end
      end
      return
    end
  end
end

-- The window closed: its boxes bind nothing now. A reopen builds the rows afresh.
local function boxRemoved(w)
  local b = bound[w]
  bound[w] = nil
  if b and windows[b.key] and not windows[b.key]:exists() then windows[b.key] = nil end
  for win in pairs(seen) do
    if not win:exists() then seen[win] = nil; orphans[win] = nil end
  end
end

-- Name a site: the gesture in flight is this site, for a few seconds, and a window already up is its.
local function point(gob)
  local key, pos = keyOf(gob)
  if not key or not pos:durable() then return false end
  pending = {key = key, pos = pos, gob = gob}
  local mine = pending
  hafen.timer():after(5, function()
    if pending == mine then pending = nil end
  end)
  adopt()
  return true
end

-- ---- the gesture -------------------------------------------------------------------------------------

-- A map click: a right-click on a site starts the binding, every other click on the map ends it. The
-- outbound stream carries the object the click resolved to, so nothing is searched.
hafen.event():action():on("click", function(ev)
  if ev:widget():type() ~= "MapView" then return end
  local gob = ev:gob()
  local button = ev:args()[3]
  if gob and button == 3 and isSite(gob) and point(gob) then return end
  pending = nil
end)

-- Placing a building: the server stands the site where the ghost was dropped and opens its window on its
-- own, with no click on the site to name it. The place is kept until a site stands on it.
local placed = nil           -- {pos = Position} after a "place", until the site arrives or a while passes
hafen.event():action():on("place", function(ev)
  if ev:widget():type() ~= "MapView" then return end
  local ok, pos = pcall(function() return ev:position(1) end)
  if not ok or not pos then return end
  placed = {pos = pos}
  local mine = placed
  hafen.timer():after(10, function()
    if placed == mine then placed = nil end
  end)
end)

-- The figure moved while the window is open (materials put in, or taken out). This runs where the
-- update arrives, so it only writes the record down; the painter reads it on the next frame, and a
-- completed site is dropped from the step.
hafen.event():message():on("chnum", function(ev)
  local b = bound[ev:widget()]
  if not b then return end
  local site = sites[b.key]
  local row = site and site.rows[b.row]
  if not row then return end
  local a = ev:args()
  row.have, row.total = a[1], a[2]
  if a[3] and a[3] >= 0 then
    row.text = a[1] .. "/" .. a[2] .. "/" .. a[3]
  else
    row.text = a[1] .. "/" .. a[2]
  end
  if complete(site) then
    local key = b.key
    hafen.timer():after(0, function() forget(key) end)
  end
end)

-- One pair of subscriptions per character's tree: a session listed at load is watched here, and one that
-- enters the world later is watched from that event -- never twice.
local watched = {}           -- [account name] = true
local function watch(session)
  local user = session:user()
  if watched[user] then return end
  watched[user] = true
  session:ui():on("@ISBox", "Added", boxAdded)
  session:ui():on("@ISBox", "Removed", boxRemoved)
end

for _, session in ipairs(hafen.session():list()) do
  watch(session)
end

-- ---- the world ---------------------------------------------------------------------------------------

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

-- A site arriving: the one just placed is named by where it stands, and a known one walking back into
-- view wears its label from its first frame. A site whose name has not resolved in the handler is asked
-- again a moment later.
local function arrived(gob, retried)
  local name = gob:name()
  if name == nil and not retried then
    hafen.timer():after(0.5, function()
      if gob:exists() then arrived(gob, true) end
    end)
    return
  end
  if name ~= SITE then return end
  if placed then
    local p = gob:position()
    local d = p and p:distance(placed.pos)
    if d and d < 22 then
      placed = nil
      point(gob)
    end
  end
  if not showing then return end
  local key = keyOf(gob)
  if key and sites[key] then label(gob, key) end
end

hafen.event():on("GobAdded", arrived)

-- The painter went with its object; only our own bookkeeping is left to drop.
hafen.event():on("GobRemoved", function(gob)
  labelled[gob] = nil
end)

hafen.event():on("SessionEnteredWorld", function(session)
  watch(session)
  if showing then showAll(session) end
end)

hafen.console():on("builds", function()
  local n = 0
  for key, site in pairs(sites) do
    n = n + 1
    local parts = {}
    for _, row in ipairs(site.rows) do parts[#parts + 1] = row.text end
    hafen.log():write((site.name or "?") .. " @ " .. key .. ": " .. table.concat(parts, "  "))
  end
  if n == 0 then hafen.log():write("no building site remembered") end
end)
