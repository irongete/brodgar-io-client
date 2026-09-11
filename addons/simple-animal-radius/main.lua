-- Simple Animal Radius -- a round patch laid on the ground under every animal in view.
--
-- The circle is hafen.virtual():patch(): a convex ring lying flat on the terrain, so it follows a slope
-- with no float and no gap, and one anchored to a Gob moves with that animal and ends with it. There is no
-- toggle key.
--
-- HOW a circle looks is the page Options > AddOns > Simple Animal Radius -- the GENERAL look -- and any
-- animal on the list may carry a look of its own that overrides it, field by field. WHICH animals is the
-- list below plus whatever the user adds: the "Animals..." button on that page opens a window with one line
-- per name -- a checkbox, an Edit button for that animal's own look, an X to take it off the list -- and a
-- field to add a resource name. All of it lives in the account's savedata (hafen.store(), "animals").

-- ---------------------------------------------------------------- THE BUILT-IN ANIMALS
--
-- Resource name SUFFIXES: a gob whose gob:name() ends with one of these wears a circle, so "/bear" takes
-- "gfx/kritter/bear/bear" whatever the folder. This is the aggressive set. Edit here; :reload picks it up.
-- One the user deleted from the window stays deleted (store.removed) until it is added back by name.
local ANIMALS = {
  "/mammoth",
  "/bat",
  "/caverat",
  "/boreworm",
  "/goldeneagle",
  "/eagleowl",
  "/wolf",
  "/bear",
  "/troll",
  "/lynx",
  "/walrus",
  "/boar",
  "/moose",
  "/wolverine",
  "/badger",
  "/adder",
  "/wildgoat",
  "/spermwhale",
  "/orca",
  "/caveangler",
}

-- ---------------------------------------------------------------- the palette the dropdowns offer
--
-- The client draws no colour well, so a colour is picked BY NAME here and looked up when a patch is dressed.
local COLOURS = {
  {"red",    {255,  70,  70}},
  {"orange", {255, 150,  40}},
  {"yellow", {255, 225,  60}},
  {"green",  { 70, 220, 110}},
  {"teal",   { 40, 210, 200}},
  {"blue",   { 60, 140, 255}},
  {"sky",    {120, 190, 255}},
  {"purple", {170, 110, 255}},
  {"pink",   {255, 120, 200}},
  {"white",  {255, 255, 255}},
  {"grey",   {150, 150, 150}},
  {"black",  {  0,   0,   0}},
}

local NAMES, RGB = {}, {}
for i, c in ipairs(COLOURS) do NAMES[i], RGB[c[1]] = c[1], c[2] end

local RESCAN = 2          -- seconds between full re-reads of the gobs in view
local SETTLE = 0.1        -- seconds a look change waits before the circles are re-dressed -- see soon()
local STEPS  = 24         -- points around the circle: a patch carries at most 32 edges
local BASE   = 100        -- the radius the ring is LAID at, in world units. A patch's ring cannot be changed
                          -- afterwards, so a radius is a :scale() against this rather than a re-lay.

-- The six fields a look has, with the bounds the general rows and the per-animal editor share.
local RADIUS  = {1, 330}
local PERCENT = {0, 100}
local WIDTH   = {0, 200}

-- ---------------------------------------------------------------- what the account remembers
--
-- One table, written into and never replaced:
--   off[name]     = true    the name is on the list but unticked
--   removed[name] = true    a built-in name the user took off the list
--   custom        = {...}   the names the user added, in order
--   per[name]     = {radius=, fill=, opacity=, border=, width=, through=}   that animal's own look; a
--                           field left out reads the general row
local store = hafen.store():get("animals")
store.off     = store.off     or {}
store.removed = store.removed or {}
store.custom  = store.custom  or {}
store.per     = store.per     or {}

local function save() hafen.store():flush() end

-- ---------------------------------------------------------------- the options: the general look
--
-- The options are the WHOLE of the general look: the addon keeps no colour or size of its own beside them,
-- so the page and the circles cannot disagree. Every one is remembered by the client. The page that shows
-- them is further down, once the list window it opens is written.
local opts = hafen.client():options():addon()

local onOpt      = opts:boolean("enabled"):default(true):add()
local radiusOpt  = opts:number("radius"):range(RADIUS[1], RADIUS[2]):default(110):add()
local fillOpt    = opts:choice("fill"):choices(NAMES):default("red"):add()
local alphaOpt   = opts:number("opacity"):range(PERCENT[1], PERCENT[2]):default(25):add()
local edgeOpt    = opts:choice("border"):choices(NAMES):default("red"):add()
local widthOpt   = opts:number("border-width"):range(WIDTH[1], WIDTH[2]):default(30):add()
local throughOpt = opts:boolean("through"):default(false):add()

-- The field names of a look, each with the general row it falls back to.
local FIELDS = {
  {"radius",  radiusOpt},
  {"fill",    fillOpt},
  {"opacity", alphaOpt},
  {"border",  edgeOpt},
  {"width",   widthOpt},
  {"through", throughOpt},
}

-- ---------------------------------------------------------------- helpers

-- A row is answered inside the widget tree of whatever put the Options window up, and a sweep is work for a
-- step rather than for a press (api/threading.md).
local function step(fn)
  hafen.timer():after(0, fn)
end

-- A slider fires Changed once per step of a drag, so look changes are coalesced onto one short timer.
local settle
local function soon(fn)
  if settle then settle:cancel() end
  settle = hafen.timer():after(SETTLE, function()
    settle = nil
    fn()
  end)
end

local function patches()
  return hafen.virtual():patch()      -- the same object every call
end

-- Every name on the list, built-in first and the user's after, in order.
local function names()
  local all = {}
  for _, n in ipairs(ANIMALS) do
    if not store.removed[n] then all[#all + 1] = n end
  end
  for _, n in ipairs(store.custom) do all[#all + 1] = n end
  return all
end

local function listed(name)
  for _, n in ipairs(names()) do
    if n == name then return true end
  end
  return false
end

-- The list entry a resource name matches, or nil: an entry is a suffix, and an unticked one does not count.
local function wanted(res)
  for _, suffix in ipairs(names()) do
    if (not store.off[suffix]) and (res:sub(-#suffix) == suffix) then return suffix end
  end
  return nil
end

-- The look one entry wears: its own field where it has one, the general row where it has not. A colour the
-- palette no longer offers falls back to the row's default rather than raising from inside a re-dress.
local function look(key)
  local own = store.per[key] or {}
  local l = {}
  for _, f in ipairs(FIELDS) do
    local v = own[f[1]]
    if v == nil then v = f[2]:value() end
    l[f[1]] = v
  end
  if not RGB[l.fill]   then l.fill   = fillOpt:default() end
  if not RGB[l.border] then l.border = edgeOpt:default() end
  return l
end

-- The fill's opacity is the tint's fourth component; the rim stays solid, so it can be followed across pale
-- ground.
local function dress(patch, key)
  local l = look(key)
  local c = RGB[l.fill]
  local a = math.floor(((l.opacity * 255) / 100) + 0.5)
  return patch:tint({c[1], c[2], c[3], a})
              :border(RGB[l.border], l.width / 100)
              :scale(l.radius / BASE)
              :occluded(not l.through)
end

-- ---------------------------------------------------------------- laying and dropping

local laid = {}      -- [Gob] = {name = <resource when read>, key = <list entry it matched>, patch}
local ticker         -- the re-read; nil exactly while the circles are off
local warned = {}    -- [resource] = true once a refusal to lay it has been logged

-- The ring: a circle of real places around the animal, laid at BASE. A patch keeps it as offsets from its
-- anchor, so it means the same shape wherever the animal walks to.
local function ringOf(p)
  local ring = {}
  for i = 1, STEPS do
    local a = ((i - 1) / STEPS) * math.pi * 2
    local q = p:offset(BASE * math.cos(a), BASE * math.sin(a))
    if not q then return nil end      -- ground the drawn character cannot locate
    ring[i] = q
  end
  return ring
end

local function drop(mine)
  if mine.patch:exists() then patches():remove(mine.patch) end
end

local function forget(g)
  local mine = laid[g]
  if not mine then return end
  laid[g] = nil
  drop(mine)
end

-- :add refuses a client not yet in the world, and a ring on ground that cannot be measured yet. Either is a
-- frame too early rather than a mistake, so nothing is recorded and the next sweep comes back for it. The
-- reason is logged once per resource all the same, so a circle that never comes says why.
local function lay(g, name, key)
  local p = g:position()
  local ring = p and ringOf(p)
  if not ring then return end
  local ok, patch = pcall(function()
    return dress(patches():add(ring, g), key)
  end)
  if ok and patch then
    laid[g] = {name = name, key = key, patch = patch}
  elseif not ok and not warned[name] then
    warned[name] = true
    hafen.log():write("simple-animal-radius: cannot lay " .. name .. ": " .. tostring(patch))
  end
end

-- Re-read, never remembered: a gob keeps its id when its resource changes (a live animal becomes a
-- carcass), and the list moves under it, so the name and the entry it matches together say whether the
-- circle still belongs.
local function consider(g)
  local name = g:name()
  if name == nil then return end      -- not resolved yet; nothing to key a verdict on
  local key = wanted(name)
  local mine = laid[g]
  if mine then
    if key and (mine.name == name) and (mine.key == key) then return end
    forget(g)
  end
  if key then lay(g, name, key) end
end

local function sweep()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then
      for _, g in ipairs(s:world():gob():list()) do consider(g) end
    end
  end
end

-- ---------------------------------------------------------------- the cycle

local function leave()
  if ticker then ticker:cancel() end
  ticker = nil
  local was = laid
  laid = {}
  for _, mine in pairs(was) do drop(mine) end
end

local function enter()
  if not onOpt:value() then return end
  sweep()
  ticker = hafen.timer():every(RESCAN, sweep)
end

-- A look change re-dresses the circles already down rather than taking them up: a tint, a border, a scale
-- and the occlusion flag cost no terrain work, where laying a circle again re-cuts every tile under it.
-- With a key, only that entry's circles; without, every one.
local function redress(key)
  for _, mine in pairs(laid) do
    if ((key == nil) or (mine.key == key)) and mine.patch:exists() then dress(mine.patch, mine.key) end
  end
end

-- The list moved: circles on animals no longer wanted come up, and wanted ones go down.
local function relist()
  if ticker then sweep() end
end

-- ---------------------------------------------------------------- the editor: one animal's own look
--
-- The same six fields the general rows carry, as this addon's own controls in a window titled with the
-- animal's name. It opens showing what that animal wears NOW -- its own field or the general one -- and
-- every move writes that animal's own field, so an animal that was never edited wears the general look
-- and one that was keeps its own whatever the general rows do afterwards. "Use general look" drops the
-- override and closes.
local LABEL_W, CTRL_W, ROW_H = 120, 170, 26
local editor              -- {key, win} while one is open

local function closeEditor()
  if not editor then return end
  local e = editor
  editor = nil
  pcall(function() e.win:destroy() end)
end

local function openEditor(key)
  closeEditor()
  local win = hafen.ui():window():title(key):position(560, 160)
  editor = {key = key, win = win}
  local y = 0

  local function set(field, v)
    store.per[key] = store.per[key] or {}
    store.per[key][field] = v
    save()
    soon(function() redress(key) end)
  end

  local function caption(text)
    hafen.ui():label():parent(win):position(0, y + 4):text(text)
  end

  local function slider(field, text, bounds)
    caption(text)
    local s = hafen.ui():slider():parent(win):position(LABEL_W, y):size(CTRL_W, 20)
      :range(bounds[1], bounds[2]):value(look(key)[field])
    s:on("Changed", function(ev) set(field, ev:value()) end)
    y = y + ROW_H
  end

  local function colour(field, text)
    caption(text)
    local d = hafen.ui():dropdown():parent(win):position(LABEL_W, y):size(CTRL_W, 20)
      :rows(NAMES):value(look(key)[field])
    d:on("Changed", function(pick) set(field, pick) end)
    y = y + ROW_H
  end

  slider("radius",  "Radius",           RADIUS)
  colour("fill",    "Fill colour")
  slider("opacity", "Fill opacity",     PERCENT)
  colour("border",  "Border colour")
  slider("width",   "Border thickness", WIDTH)

  local through = hafen.ui():check():parent(win):position(0, y):text("Draw through the world")
    :value(look(key).through)
  through:on("Changed", function(on) set("through", on) end)
  y = y + ROW_H + 4

  local reset = hafen.ui():button():parent(win):position(0, y):size(LABEL_W + CTRL_W):text("Use general look")
  reset:on("Pressed", function()
    store.per[key] = nil
    save()
    soon(function() redress(key) end)
    step(closeEditor)
  end)

  win:pack()
  win:on("Close", function() step(closeEditor) end)
end

-- ---------------------------------------------------------------- the list window
--
-- The Options page is rebuilt on every visit, and a list is edited in place -- so the list is a window of
-- this addon's own, opened from a button on that page: a scrolling column with one line per name -- the
-- checkbox, Edit, X -- and under it a field and a button to add a name. Lines are added and taken away IN
-- PLACE: nothing is rebuilt, so the window stays where it is and shows what the list holds the moment it
-- changes.
local LIST_W, LIST_H, LINE_H, BTN_W = 300, 300, 22, 40
local win                 -- the list window, while open
local list                -- the scrolling column inside it
local lines = {}          -- in list order: {key, check, edit, del}

local function closeList()
  if not win then return end
  local w = win
  win, list, lines = nil, nil, {}
  pcall(function() w:destroy() end)
end

-- Every line at the row its index says. Called after a line is taken out, so the ones under it move up.
local function layout()
  for i, ln in ipairs(lines) do
    local y = 2 + ((i - 1) * LINE_H)
    ln.check:position(4, y)
    ln.edit:position(LIST_W - (2 * BTN_W) - 22, y)
    ln.del:position(LIST_W - BTN_W - 18, y)
  end
end

local function unlist(key)
  for i, ln in ipairs(lines) do
    if ln.key == key then
      table.remove(lines, i)
      for _, w in ipairs({ln.check, ln.edit, ln.del}) do pcall(function() w:destroy() end) end
      break
    end
  end
  layout()
end

-- Taking a name off the list takes its own look and its tick with it: a name added back later starts as
-- a new one. A built-in is remembered as removed; one of the user's own leaves the custom array.
local function remove(key)
  store.removed[key] = nil
  for i, n in ipairs(store.custom) do
    if n == key then table.remove(store.custom, i); break end
  end
  for _, n in ipairs(ANIMALS) do
    if n == key then store.removed[key] = true end
  end
  store.per[key] = nil
  store.off[key] = nil
  save()
  if editor and (editor.key == key) then closeEditor() end
  unlist(key)
  step(relist)
end

local function addLine(key)
  local y = 2 + (#lines * LINE_H)
  local ln = {key = key}
  ln.check = hafen.ui():check():parent(list):position(4, y):text(key):value(not store.off[key])
  ln.check:on("Changed", function(on)
    if on then store.off[key] = nil else store.off[key] = true end
    save()
    step(relist)
  end)
  ln.edit = hafen.ui():button():parent(list):position(LIST_W - (2 * BTN_W) - 22, y):size(BTN_W):text("Edit")
  ln.edit:on("Pressed", function() step(function() openEditor(key) end) end)
  ln.del = hafen.ui():button():parent(list):position(LIST_W - BTN_W - 18, y):size(BTN_W):text("X")
  ln.del:on("Pressed", function() step(function() remove(key) end) end)
  lines[#lines + 1] = ln
end

local function openList()
  if win then return end
  win = hafen.ui():window():title("Simple Animal Radius"):position(240, 160)
  list = hafen.ui():scroll():parent(win):position(0, 0):size(LIST_W, LIST_H)
  lines = {}
  for _, key in ipairs(names()) do addLine(key) end

  local field = hafen.ui():entry():parent(win):position(0, LIST_H + 6):size(LIST_W - 70):value("")
  local addBtn = hafen.ui():button():parent(win):position(LIST_W - 64, LIST_H + 6):size(64):text("Add")

  -- A name is taken as typed, trimmed: "gfx/kritter/fox/fox" and "/fox" both match by suffix. One already
  -- on the list is not added twice; a built-in the user had removed comes back as itself.
  local function add()
    local name = (field:value() or ""):gsub("^%s+", ""):gsub("%s+$", "")
    field:value("")
    if (name == "") or listed(name) then return end
    if store.removed[name] then
      -- A built-in coming back belongs among the built-ins, so the column is re-laid whole: the one case.
      store.removed[name] = nil
      save()
      for _, ln in ipairs(lines) do
        for _, w in ipairs({ln.check, ln.edit, ln.del}) do pcall(function() w:destroy() end) end
      end
      lines = {}
      for _, key in ipairs(names()) do addLine(key) end
    else
      -- One of the user's own goes at the end, which is where its line goes too.
      store.custom[#store.custom + 1] = name
      save()
      addLine(name)
    end
    step(relist)
  end
  field:on("Submitted", add)
  addBtn:on("Pressed", function() step(add) end)

  win:pack()
  win:remember("animals")
  win:on("Close", function() step(closeList) end)
end

-- ---------------------------------------------------------------- the page
--
-- An option draws nothing; what shows it is a control built here, on the column the client hands over each
-- time Options > AddOns > Simple Animal Radius is opened, and BOUND to it: a tick, a pick or a pull writes
-- the option, and the client keeps the value. The page is rebuilt on every visit, so nothing built here is
-- kept -- which is why the list is a window of its own, opened from the button at the foot.
--
-- A slider's caption carries its value, because a slider draws no number: the caption is written from the
-- slider's own Changed, which is the user's hand and the only thing that moves these three.
local function gauge(root, caption, opt, tooltip)
  local lbl = hafen.ui():label():parent(root):text(caption .. ": " .. opt:value())
  local sl  = hafen.ui():slider():parent(root):size(160):tooltip(tooltip):bind(opt)
  sl:on("Changed", function(ev) lbl:text(caption .. ": " .. ev:value()) end)
end

local function pick(root, caption, opt, tooltip)
  hafen.ui():label():parent(root):text(caption)
  hafen.ui():dropdown():parent(root):size(120):tooltip(tooltip):bind(opt)
end

opts:panel(function(root)
  root:gap(4)
  hafen.ui():check():parent(root):text("Draw circles")
    :tooltip("lay a circle on the ground under every animal on the list"):bind(onOpt)
  gauge(root, "Radius", radiusOpt,
        "world units from the animal's centre to the rim; a tile is 11 of them, so 110 is ten tiles")
  pick(root, "Fill colour", fillOpt, "the colour laid over the ground under the animal")
  gauge(root, "Fill opacity", alphaOpt,
        "per cent: how much of the fill is there. 0 leaves the rim standing on bare ground")
  pick(root, "Border colour", edgeOpt,
       "the rim round the circle, drawn solid whatever the opacity above says")
  gauge(root, "Border thickness", widthOpt,
        "hundredths of a world unit; a tile is 11 units, so 30 is a thin line. 0 is the thinnest line " ..
        "the screen can draw and not no line: to be rid of the rim, give it the fill's colour")
  hafen.ui():check():parent(root):text("Draw through the world")
    :tooltip("on: hills, walls and trees in front of a circle stop hiding it, so one behind a house is " ..
             "drawn whole. off: the world may hide it, as it hides the ground it lies on")
    :bind(throughOpt)
  local animals = hafen.ui():button():parent(root):size(120):text("Animals...")
    :tooltip("the animals that wear a circle: tick and untick them, give one a look of its own, take one " ..
             "off the list, or add a resource name of your own")
  animals:on("Pressed", function() step(openList) end)
end)

-- ---------------------------------------------------------------- wiring

onOpt:on("Changed", function(on)
  step(function()
    if on then
      if not ticker then enter() end
    else
      leave()
    end
  end)
end)

-- A general row re-dresses every circle: the ones with a look of their own read that look again and
-- change nothing, the rest put on the new row.
for _, f in ipairs(FIELDS) do
  f[2]:on("Changed", function() soon(function() redress(nil) end) end)
end

-- GobAdded runs before the object's first drawn frame, so an animal arriving with its resource in hand is
-- circled from that frame rather than at the next sweep.
hafen.event():on("GobAdded", function(g)
  if ticker then consider(g) end
end)

-- The patch went with it: one anchored to a Gob ends with that Gob, so only the entry goes.
hafen.event():on("GobRemoved", function(g)
  laid[g] = nil
end)

-- On the step, because the sweep wants a world to read and this runs while the client is still coming up.
step(enter)
