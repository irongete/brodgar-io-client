-- Actionbars -- as many extra hotbars as the game has slots for.
--
-- The client draws ONE bar and pages it. The server keeps 144 belt slots per character; the client's belt
-- shows twelve of them at a time and Alt+F1..F12 turns the page. This addon puts the other pages on screen
-- at the same time.
--
-- A bar is a WINDOW ONTO A PAGE and nothing else. Actionbar<N> is slots (N-1)*12+1 .. N*12 for every N but
-- one, permanently -- which is what makes the number an identity rather than a position in a list: deleting
-- Actionbar2 leaves Actionbar3 showing the same twelve slots it always showed, and "Actionbar3 slot 5" goes
-- on naming one button of the game for as long as the character exists. It also fixes the ceiling: twelve
-- pages is every slot there is, so a thirteenth bar would have nothing of its own to show.
--
-- ACTIONBAR1 IS THE EXCEPTION, because it stands in for the bar the client draws: it shows whichever page
-- the client is on, and the client's own "Go to page N" keys move it. See KEEP below.
--
-- WHICH BARS THERE ARE AND WHICH WAY ROUND THEY STAND IS A SETTING, declared as twelve options and drawn
-- as twelve rows on this addon's page of Options > AddOns: this addon has no window of its own and nothing
-- to open. Where each bar STANDS is not a setting -- it is a drag -- so that stays in the account's own
-- saved variables. The CONTENTS of the slots are neither: they are the server's, held per character, and
-- this addon neither copies them nor needs to.
--
-- THE BARS HANG ON THE CHARACTER'S HUD, not in the addon layer, and that is forced rather than chosen: the
-- action menu ends its drag with DropTarget.dropthing(ui.root, ...) on the SESSION's tree, and the addon
-- layer is a UI of its own that the walk never enters. A bar built in the layer would draw and click
-- perfectly and a dragged Paginae would fall straight through it into the map. So a bar is built per login,
-- from SessionEnteredWorld, and dies with it.

local SLOTS   = 12          -- buttons on a bar: one page of the server's belt
local MAXBARS = 12          -- 144 slots / 12
local SQ      = 34          -- the client's own inventory square, design px
local INNER   = 32          -- the icon inside it: the square less its one-pixel ring
local GAP     = 2           -- between two squares, as the F-key belt spaces them
local PITCH   = SQ + GAP
local LONG    = (SLOTS * PITCH) - GAP        -- twelve squares end to end
local DEF_X   = 200         -- where the first bar stands before anyone has moved it
local DEF_Y   = 120
local STEP    = 6           -- the air between one bar and the next one added

-- THE CLIENT'S OWN FRAME -- `gfx/hud/wnd`, the box every panel in the game wears: the inventory, the
-- portrait, the party avatars, the skill lists. It is DECLARED rather than drawn: a bar says what it looks
-- like when nothing else says otherwise, and the client paints it. That is `widget:stock`, and it is the
-- bottom of the style cascade, so any rule -- a theme's -- beats it, per property, without this addon
-- knowing that themes exist.
--
-- EDGE_T is the art's own edge run in design pixels (28 px at scale 4). It is kept because the LAYOUT is
-- still ours: a nine-slice is painted at the box we compute, and PAD_F is the room a button keeps inside
-- it -- the frame's own inset, and two more so the field below shows as a margin around the buttons rather
-- than only between them.
local EDGE_T = 7
local PAD_F  = EDGE_T + 2

-- The square inside it, in the client's own colours. haven.Inventory builds `invsq` in code rather than
-- loading a resource, so there is no `.res` name to draw it by and these four numbers ARE the art: the same
-- ring and the same fill the inventory, the belt and the equipment grid are all paved with.
local FILL  = {36, 52, 38, 125}
local EDGE  = {20, 28, 21, 167}
local LABEL = {156, 180, 158, 255}    -- the tone the belt prints "F1" in
local METER = {255, 255, 255, 64}     -- the recharge pie, exactly as the action menu draws it

-- The field a bar stands on: `ISBox.bgcol`, the dark translucent green the client fills a framed box
-- with. It is a flat colour and not `gfx/hud/wnd/lg/bg`, the window's own tiled backdrop, because `g` has no
-- tiling verb -- a 146 px texture stretched across a 430 px bar would smear, and this is the same field at
-- any length.
local BACK  = {43, 51, 44, 127}

-- ---------------------------------------------------------------- what the user manages, and where
--
-- OPTIONS > ADDONS > ACTIONBARS, and no window of this addon's own. Which bars there are is a setting the
-- way the interface scale is a setting, so it is declared where the client keeps settings: twelve options,
-- one per bar, each saying whether that bar is on and which way it stands. The client stores the value and
-- answers the read; this addon draws the page, on the column the client hands it each time the page is
-- opened, and nothing here has to remember where the user dragged a bar.
--
-- THE OPTION IS THE VALUE. The dropdown on the page is BOUND to it, so a change arrives as one event whether
-- the user moved the dropdown or something else wrote the option, and there is no second copy to keep in
-- step.
--
-- WHAT IS NOT A SETTING stays in the account's own saved variables: where each bar STANDS. That is a drag
-- rather than a choice -- a pair of numbers with no control to draw -- and it belongs to the account,
-- because a place on the screen is the same place on every character.

local KEEP = 1              -- Actionbar1: the page you are on, and the one bar that cannot be taken away
local OFF, FLAT, UP = "off", "flat", "upright"

-- Everything the rows below drive is written further down, where the bars are.
local dropBar, syncAll, resetBars

-- THE STEP, AND NOT THE PRESS. A pick on the page is answered inside the widget tree of whatever put the
-- Options window up -- one character's -- and every writer below reaches a bar in a character's HUD, which
-- is a second tree. Two of them held at once is the one thing this client refuses. So the work is handed to
-- the next step, which holds none and reaches every login; it lands a frame later, and nothing here can
-- tell.
local function step(fn)
  hafen.timer():after(0, fn)
end

local saved = hafen.store():get("bars")     -- filled before this file runs
saved.list = saved.list or {}               -- { {n = , x = , y = }, ... }: where each bar stands

local function record(n)
  for _, r in ipairs(saved.list) do
    if r.n == n then return r end
  end
  return nil
end

-- WHAT AN OPTION READS ON A CLIENT THAT HAS NEVER BEEN TOLD OTHERWISE. Which bars existed used to be this
-- table's to say, so a bar with a place saved is a bar the user had: it seeds its own option, and this is
-- the last thing that ever reads an orientation from there.
local function seed(n)
  local r = record(n)
  if not r then return (n == KEEP) and FLAT or OFF end
  local was = r.vert and UP or FLAT
  r.vert = nil
  return was
end

local opts = hafen.client():options():addon()

-- ONE OPTION PER BAR, and the option says both things at once: a bar is off, lying flat, or standing
-- upright. Twelve options rather than a list with an Add button under it, because a bar's NUMBER is its
-- identity -- Actionbar4 is slots 37-48 for as long as the character exists, which is what lets a hotkey
-- called "Actionbar4 slot 5" name one button of the game. An option is a fact about one page of the belt,
-- and the belt has twelve.
local rows = {}

for n = 1, MAXBARS do
  rows[n] = opts:choice("bar" .. n)
    :choices((n == KEEP) and {FLAT, UP} or {OFF, FLAT, UP})
    :default(seed(n))
    :add()
end

local function mode(n)
  return rows[n]:value()
end

local function has(n)
  return mode(n) ~= OFF
end

local function vertOf(n)
  return mode(n) == UP
end

-- THE LINE UNDER THE ROWS, which says the two things no row can: how many bars are up, and what the last
-- press did. A reset that found no character in the world has nowhere else to report it, and the log is not
-- where somebody looking at this page is looking. It is a label on the page, so it is there exactly while
-- the page is open: `state` is the one on the page now, dead or nil once the page has been left, and
-- report() writes it where there is one.
local state

local function report(msg)
  local n = 0
  for i = 1, MAXBARS do
    if has(i) then n = n + 1 end
  end
  local line = n .. " of " .. MAXBARS .. " bars on"
  if n >= MAXBARS then line = line .. " -- 144 slots is every one there is" end
  if state and state:exists() then state:text(msg and (line .. " -- " .. msg) or line) end
end

-- THE PAGE. An option draws nothing; what shows it is a dropdown built here, on the column the client hands
-- over each time Options > AddOns > Actionbars is opened, and BOUND to it: a pick writes the option, and
-- the client keeps the value. The page is rebuilt on every visit, so nothing built here is kept.
--
-- A label is one line and never wraps, so the paragraph at the top is a bare surface as wide as the page,
-- drawing the text wrapped to that width -- measured first, so the surface is as tall as the lines, and two
-- pixels over so a descender on the last line is not clipped by a measure rounded to whole design pixels.
local function paragraph(root, text)
  local style = {width = root:size().w - 8}
  local box = hafen.ui():measure(text, style)
  local w = hafen.ui():widget():parent(root):size(style.width, box.h + 2)
  w:on("Draw", function(ev) ev:g():text(text, 0, 0, style) end)
end

local function tip(n)
  if n == KEEP then
    return "the page you are on, lying flat or standing upright -- it stands in for the client's own bar"
      .. " and cannot be taken away"
  end
  return "slots " .. (((n - 1) * SLOTS) + 1) .. "-" .. (n * SLOTS)
    .. " -- off, lying flat, or standing upright"
end

opts:panel(function(root)
  root:gap(4)
  paragraph(root, "A bar is one page of the belt: ActionbarN is slots (N-1)x12+1 to Nx12. Actionbar1"
    .. " follows the page you are on, in place of the client's own bar.")
  -- One row per bar: the dropdown, then its name, on one line -- the dropdowns line up because every one is
  -- the same width, and the label sits three pixels down to read level with the box beside it.
  for n = 1, MAXBARS do
    local line = hafen.ui():row():gap(6):parent(root)
    hafen.ui():dropdown():parent(line):size(90):tooltip(tip(n)):bind(rows[n])
    hafen.ui():label():parent(line):text("Actionbar" .. n):rule():margin(0, 3, 0, 0)
  end
  local reset = hafen.ui():button():parent(root):size(160):text("Reset bars position")
    :tooltip("put every bar back in the middle of the screen, stacked -- for when one has ended up past an"
      .. " edge and there is nothing left to drag")
  reset:on("Pressed", function() step(resetBars) end)
  state = hafen.ui():label():parent(root):text("")
  report()
end)

-- AN OPTION MOVED, and one path does all three things that can mean. `dropBar` takes every copy of that bar
-- off screen -- a rotation is a different box, so it is built again rather than resized -- and the sync
-- behind it puts back whatever the options now say there is.
for n = 1, MAXBARS do
  rows[n]:on("Changed", function()
    step(function()
      dropBar(n)
      syncAll()
      report()
    end)
  end)
end

-- The slot the first button of a bar is, LESS ONE: add a button's number and you have its slot. Read once
-- per pass rather than once per button, because for the main bar it is a call into the client.
local function baseOf(s, n)
  local p = n
  if n == KEEP then
    p = (s and s:exists() and s:actionbar():page()) or 1
  end
  return (p - 1) * SLOTS
end

local function slotOf(s, n, i)
  return baseOf(s, n) + i
end

-- ---------------------------------------------------------------- the shape of a bar
--
-- The row says which way a bar stands, and that is the only thing downstream ever asks: a bar lying flat
-- and a bar standing upright are the same twelve squares with `along` and `across` swapped, so the swap
-- happens here, once, and nothing past this point knows which way round it is.

local function boxOf(n)
  if vertOf(n) then
    return SQ + (PAD_F * 2), LONG + (PAD_F * 2)
  end
  return LONG + (PAD_F * 2), SQ + (PAD_F * 2)
end

-- WHERE A BAR STANDS. The record is minted the first time a bar is turned on and KEPT when it is turned off
-- again, so a bar you took away comes back where you left it. One that has never stood anywhere goes under
-- the lowest bar there is, lined up with it, rather than on top of one already on screen.
local function spot(n)
  local r = record(n)
  if r then return r end

  local x, y = DEF_X, DEF_Y
  for m = 1, MAXBARS do
    local o = (m ~= n) and has(m) and record(m)
    if o then
      local _, h = boxOf(m)
      if (o.y + h + STEP) > y then x, y = o.x, o.y + h + STEP end
    end
  end

  r = {n = n, x = x, y = y}
  saved.list[#saved.list + 1] = r
  table.sort(saved.list, function(a, b) return a.n < b.n end)
  hafen.store():flush()
  return r
end

-- The top-left of button `i`, inside the frame.
local function originOf(vert, i)
  local d = (i - 1) * PITCH
  if vert then return PAD_F, PAD_F + d end
  return PAD_F + d, PAD_F
end

-- Which button a point is on, or nil. ONE function behind the drawing, the click, the drop and the
-- tooltip: a second copy of this arithmetic is the copy that drifts a pixel and makes a button you can see
-- and cannot press.
local function squareAt(vert, x, y)
  local along, across
  if vert then
    along, across = y - PAD_F, x - PAD_F
  else
    along, across = x - PAD_F, y - PAD_F
  end
  if (across < 0) or (across >= SQ) then return nil end
  if along < 0 then return nil end
  local i = math.floor(along / PITCH) + 1
  if (i < 1) or (i > SLOTS) then return nil end
  if (along - ((i - 1) * PITCH)) >= SQ then return nil end   -- the gutter between two squares
  return i
end

-- ---------------------------------------------------------------- the hotkeys
--
-- One per button, declared under the name the user reads: Options > Keybindings lists an addon's hotkeys in
-- a section of its own, labelled with the addon's name, each row labelled with the name it was declared
-- with. So "Actionbar1 slot 1" is literally what appears there.
--
-- They start UNBOUND -- an addon names an action and the user chooses the key -- and the key the user
-- chooses belongs to the CLIENT's registry, which outlives this addon. Dropping a bar's hotkeys and
-- declaring them again under the same names picks the same keys back up, so deleting Actionbar2 and adding
-- it again tomorrow costs the user nothing.

local keys  = hafen.client():options():keybindings()
local mouse = hafen.ui():mouse()
local keysubs = {}                          -- [n] = { <Sub> x12 }

local function keyname(n, i)
  return "Actionbar" .. n .. " slot " .. i
end

-- The modifiers physically held right now. slot:use(mods) takes the bitfield the client builds for its own
-- belt clicks, so Shift-clicking one of our buttons means what Shift-clicking the client's own does.
local function mods()
  local m = 0
  if mouse:shift() then m = m + 1 end
  if mouse:ctrl()  then m = m + 2 end
  if mouse:alt()   then m = m + 4 end
  return m
end

-- Press one button of one bar, on the character it belongs to. `use` raises on an empty slot, so an unused
-- button is a no-op rather than a line in the log; anything else the gate or the server refuses is worth
-- showing, since a button that silently does nothing is the one reported as broken.
local function useSlot(s, n, i)
  if not (s and s:exists()) then return end
  local slot = s:actionbar():get(slotOf(s, n, i))
  if slot:empty() then return end
  local ok, err = pcall(function() slot:use(mods()) end)
  if not ok then hafen.log():write(err) end
end

local function bindKeys(n)
  if keysubs[n] then return end
  local subs = {}
  for i = 1, SLOTS do
    subs[i] = keys:on(keyname(n, i), function()
      useSlot(hafen.session():current(), n, i)   -- the key is the client's: it presses whoever is on screen
    end)
  end
  keysubs[n] = subs
end

local function unbindKeys(n)
  local subs = keysubs[n]
  if not subs then return end
  for _, sub in ipairs(subs) do sub:off() end
  keysubs[n] = nil
end

-- What a button prints in its corner: the key the user gave it, or its own number while it has none. The
-- belt prints "F1" there for the same reason, and a bar nobody has bound yet still says which button is
-- which.
--
-- Read on a slow timer rather than in the draw. The registry is where a key LIVES, so reading it live would
-- be right and would also be twelve lookups per bar per frame for a string that changes when the user opens
-- Options and not otherwise. The timer is what makes a remap show up without the draw paying for it.
local labels = {}                           -- [n] = { <string> x12 }

local function relabel()
  local built = {}
  for n = 1, MAXBARS do
    if has(n) then
      local row = {}
      for i = 1, SLOTS do
        local b = keys:binding():get(keyname(n, i))
        row[i] = (b:exists() and b:key()) or tostring(i)
      end
      built[n] = row
    end
  end
  labels = built
end

local function corner(n, i)
  local row = labels[n]
  return (row and row[i]) or tostring(i)
end

-- ---------------------------------------------------------------- drawing one bar

-- ONE SQUARE, drawn by the square's own widget. The cell itself -- its fill and its ring -- is not drawn
-- here at all: it is what that widget DECLARED, so a theme can replace it and this function never learns.
-- What is left is what only this addon can draw: whichever icon the slot holds, the recharge pie over it,
-- and the key that presses it.
local function paintSlot(s, n, i, ev)
  local g = ev:g()
  local ab = s:exists() and s:actionbar()
  local slot = ab and ab:get(baseOf(s, n) + i)

  if slot and not slot:empty() then
    -- A HELD slot draws one of our own menu entries and has no client resource to name; every other
    -- slot is the server's, and its resource is its picture.
    local held = slot:hold()
    if held then
      local img = held:icon()
      if img then g:image(img, 1, 1, INNER, INNER) end
    else
      local res = slot:res()
      if res then g:resource(res, 1, 1, INNER, INNER) end
    end

    local cd = slot:cooldown()
    if cd and (cd > 0) then
      g:color(METER[1], METER[2], METER[3], METER[4])
      g:prect(1 + (INNER / 2), 1 + (INNER / 2), INNER / 2, cd)
      g:color()
    end
  end

  g:atext(corner(n, i), SQ - 3, SQ - 1, 1, 1, {color = LABEL})
end

-- ---------------------------------------------------------------- pressing one bar
--
-- THE WHOLE BAR IS THE HANDLE except the buttons that have something to fire, and the dispatch order is
-- what makes that one rule instead of two widgets fighting. `Widget.Event.dispatch` runs a widget's own
-- listeners, then its own handling, and only then descends into its children -- so this handler sees every
-- press before the drag handle under it does. What it consumes is the drag's loss; what it leaves alone
-- falls through and picks the bar up.
--
-- That is also the bug this file had: cancelling every press took every one of them away from the handle,
-- and the bar could not be dragged at all.
local function onPress(s, n, vert, ev)
  local i = squareAt(vert, ev:x(), ev:y())
  local slot = i and s:exists() and s:actionbar():get(slotOf(s, n, i))

  -- A right press is the bar's wherever it lands, so the map underneath never sees one. There is nothing
  -- for it to fall through to in any case: a drag is the left button's.
  --
  -- On a button it does what a right-click on the client's own bar does, and in the same order: a slot held
  -- for one of an addon's entries is HANDED BACK, sending nothing and landing at once, and any other slot is
  -- CLEARED, which is a round trip the server echoes. The order matters -- clearing a held slot would take
  -- away the server's own content underneath, which the hold was only drawing over.
  if ev:button() ~= 1 then
    ev:preventDefault()
    if not slot then return end
    local ok, err = pcall(function()
      if slot:hold() then slot:hold(nil) else slot:clear() end
    end)
    if not ok then hafen.log():write(err) end
    return
  end

  -- A LEFT press is the button's only where there is something in it to fire. Everything else -- the frame,
  -- the margin, the gutters, and a button standing empty -- is left alone and reaches the handle, so the
  -- bar is picked up almost anywhere on it. A bar whose belt has not streamed in yet reads as twelve empty
  -- buttons and is therefore draggable end to end, which is exactly right for a bar with nothing on it.
  if not (slot and not slot:empty()) then return end

  ev:preventDefault()
  local ok, err = pcall(function() slot:use(mods()) end)
  if not ok then hafen.log():write(err) end
end

-- A drag out of the action menu. The descriptor is neutral -- {kind, res} -- and the resource name splits
-- it: an entry an addon added (any addon's) goes into a HOLD, which the server never hears about, and one
-- of the game's own is a write to the slot, which is a round trip the server echoes back a beat later.
local function onDrop(s, n, vert, ev)
  local i = squareAt(vert, ev:x(), ev:y())
  if not i then return end
  local thing = ev:thing()
  if not (thing and (thing.kind == "pagina")) then return end
  ev:preventDefault()
  if not s:exists() then return end

  if not thing.res then
    hafen.log():write("Actionbars: that action's resource has not loaded yet -- drop it again in a moment")
    return
  end

  local slot = s:actionbar():get(slotOf(s, n, i))
  local ok, err = pcall(function()
    if thing.res:sub(1, 6) == "addon/" then
      local pag = s:menugrid():get(thing.res)
      if pag then slot:hold(pag) end        -- any addon's entry: the bar is one shared surface
    else
      slot:res(thing.res)
    end
  end)
  if not ok then hafen.log():write(err) end
end

-- The tooltip follows the pointer across the twelve buttons. MouseMove reaches every widget, the pointer
-- outside this one included, so a move off the bar clears it.
local function onMove(s, n, vert, w, ev)
  local i = squareAt(vert, ev:x(), ev:y())
  local slot = i and s:exists() and s:actionbar():get(slotOf(s, n, i))
  local name = slot and (not slot:empty()) and slot:name()
  if name then
    w:tooltip(name)
  elseif (n == KEEP) and (not i) and s:exists() then
    -- Off the buttons, on the main bar: say which page it is showing. It is the one bar that moves, and
    -- nothing else on screen says where it has moved to.
    w:tooltip("Actionbar1 -- page " .. s:actionbar():page())
  else
    w:tooltip("")
  end
end

-- ---------------------------------------------------------------- the bars, per login
--
-- One copy of every bar in every character's HUD. They are the same bar in the sense that matters -- the
-- same number, the same slots, the same place -- and different widgets, because each stands in a different
-- tree and shows a different character's belt.

local bars = {}                             -- [session] = { [n] = {w = <bar>, grip = <handle>} }
local place                                 -- forward: move every copy of one bar

local function destroy(s, n)
  local per = bars[s]
  local b = per and per[n]
  if not b then return end
  per[n] = nil
  if b.w:exists() then b.w:destroy() end     -- the handle is its child and goes with it
end

local function build(s, n)
  local per = bars[s]
  if not per then per = {}; bars[s] = per end
  if per[n] then return end

  local hud = s:exists() and s:ui():match("@GameUI")
  if not hud then return end

  local r = spot(n)
  local vert = vertOf(n)
  local bw, bh = boxOf(n)

  local w = hafen.ui():widget():parent(hud):size(bw, bh):position(r.x, r.y)

  -- The drag handle is a child covering the WHOLE bar, frame and buttons alike, and it draws nothing. It
  -- never steals a button, because the press reaches this bar's own handler first and is consumed there
  -- when it lands on one -- see onPress. What is left for the handle is exactly the frame and the gutters,
  -- which is what "drag it by its frame" means.
  local grip = hafen.ui():widget():parent(w):size(bw, bh):position(0, 0)
  w:draggable(grip)

  -- WHAT THIS BAR IS, said once, so somebody else can change it.
  --
  -- `:name` is the handle a theme reaches it by -- the engine writes this addon's id in front, so the
  -- selector a theme writes is [name=actionbars/bar]. `:stock` is what it looks like when no rule names
  -- it, and it sits at the BOTTOM of the cascade: any rule beats it, per property. That is why the default
  -- goes here rather than in a rule of our own -- a widget:rule() would sit at the TOP where no theme could
  -- reach past it, and a tree rule would tie with the theme's and leave the winner to load order.
  w:name("bar")
  w:stock{bg = {color = BACK}, border = {box = "gfx/hud/wnd", mode = "tile"}}

  -- ...and twelve squares, each a widget of its own so it can be named and dressed the same way. They
  -- subscribe to NOTHING but Draw, which is what keeps them transparent to the mouse: a surface with no
  -- input handler answers false and the press carries on to this bar, where it always landed. So the
  -- pressing, the dropping and the tooltip below are untouched -- they still read `squareAt`.
  local sq = {}
  for i = 1, SLOTS do
    local x, y = originOf(vert, i)
    -- ONE NAME PER SQUARE, not one shared by twelve. It costs a theme nothing -- [name^=…] still
    -- dresses the lot in one rule -- and it buys the thing a shared name cannot: naming ONE square.
    local cell = hafen.ui():widget():parent(w):size(SQ, SQ):position(x, y):name("slot" .. i)
    cell:stock{bg = {color = FILL}, border = {color = EDGE, width = 1}}
    cell:on("Draw", function(ev) paintSlot(s, n, i, ev) end)
    sq[i] = cell
  end

  w:on("MouseDown", function(ev) onPress(s, n, vert, ev) end)
  w:on("MouseMove", function(ev) onMove(s, n, vert, w, ev) end)
  w:on("Drop",      function(ev) onDrop(s, n, vert, ev) end)
  w:on("Dragged",   function(ev)
    local rec = record(n)
    if not rec then return end
    rec.x, rec.y = ev:x(), ev:y()            -- where it LANDED, the client's own clamp included
    place(n)                                 -- and every other login's copy follows it
  end)

  per[n] = {w = w, grip = grip, sq = sq}
end

place = function(n)
  local r = record(n)
  if not r then return end
  for _, per in pairs(bars) do
    local b = per[n]
    if b and b.w:exists() then b.w:position(r.x, r.y) end
  end
end

-- Take one bar off every character. Rotating calls it: the box changes and the handle under it with it, so
-- the widget is built again rather than resized in place.
dropBar = function(n)
  for s, per in pairs(bars) do
    if per[n] then destroy(s, n) end
  end
end

-- THE CLIENT'S OWN BAR GOES AWAY. This addon stands in for it, and Actionbar1 is the very page that bar
-- starts on, so leaving both up would draw the same twelve buttons twice.
--
-- Its KEYS are untouched, and that is deliberate. They are ordinary bindings now, listed in Options under
-- "Action bar", and a bar being off screen does not stop them: whatever you have bound there goes on
-- pressing the same twelve slots, which are Actionbar1's. A hidden bar that silently swallowed its own
-- bindings would be twelve rows in the panel that do nothing, with no way to tell from the panel that they
-- are dead.
--
-- Both spellings, because the client has two pictures of the same bar and `:belt f` picks the other one.
local hidden = {}                           -- the bars WE put away: Disable gives back these and no others

local function hideBelt(s)
  for _, sel in ipairs({"@NKeyBelt", "@FKeyBelt"}) do
    local w = s:ui():match(sel)
    if w and w:visible() then
      local ok, err = pcall(function() w:visible(false) end)
      if ok then
        hidden[#hidden + 1] = w
      else
        hafen.log():write(err)
      end
    end
  end
end

-- GIVE IT BACK BY HAND, before teardown does anything. Hiding a native widget records a restore, but the
-- rule teardown applies is "the widget ends up as the user was seeing it" -- and with no stand-in view to
-- speak for it, a bare hide is read as "not on screen" and STAYS hidden. That rule is written for
-- widget:replace(view), where the client's window has a replacement whose visibility can answer for it, and
-- for a window with a toggle the user can reopen. The client's action bar has neither: left to teardown it
-- would be gone for good, with nothing in the interface to bring it back.
--
-- Disable fires before the teardown, which is exactly the moment. Only the widgets this addon hid, so a bar
-- somebody else put away stays put away.
hafen.event():on("Disable", function()
  for _, w in ipairs(hidden) do
    if w:exists() then pcall(function() w:visible(true) end) end
  end
  hidden = {}
end)

local function sync(s)
  if not s:exists() then return end
  hideBelt(s)
  local per = bars[s]
  if not per then per = {}; bars[s] = per end

  for n in pairs(per) do
    if not has(n) then destroy(s, n) end
  end
  for n = 1, MAXBARS do
    if has(n) then
      -- A HUD that was torn down and built again -- a character leaving the world and coming back inside
      -- one login -- took our widget with it and left the handle. Drop the handle and the build is a build.
      local b = per[n]
      if b and not b.w:exists() then per[n] = nil end

      local r = spot(n)
      build(s, n)
      b = per[n]
      if b then b.w:position(r.x, r.y) end
    end
  end
end

syncAll = function()
  for s in pairs(bars) do                   -- the logins that have gone: their widgets went with the tree
    if not s:exists() then bars[s] = nil end
  end
  for _, s in ipairs(hafen.session():list()) do sync(s) end

  for n = 1, MAXBARS do
    if has(n) then bindKeys(n) end
  end
  for n in pairs(keysubs) do
    if not has(n) then unbindKeys(n) end
  end
  relabel()                                 -- after the declarations: an undeclared binding has no key yet
end

-- ---------------------------------------------------------------- putting them back on screen
--
-- A bar's place is a pair of design pixels, and the screen measured in design pixels SHRINKS when the user
-- raises the interface scale: the art gets bigger, so fewer of those pixels fit across the window. A bar
-- parked near an edge is therefore past it after a scale change or a smaller window, and a bar nobody can
-- see is a bar nobody can drag back -- the whole bar is its own handle, and none of it is on screen.
--
-- So this is the way back, and the only thing in the addon that moves a bar the user did not drag.

-- The screen the bars stand on. It is the HUD they hang off rather than the client window, because that is
-- what their x/y are measured from; any login's will do, since there is one screen however many characters
-- are logged in.
local function screenBox()
  for _, s in ipairs(hafen.session():list()) do
    if s:exists() then
      local hud = s:ui():match("@GameUI")
      local sz = hud and hud:size()
      if sz and (sz.w > 0) and (sz.h > 0) then return sz.w, sz.h end
    end
  end
  return nil
end

-- Every bar to the middle, as ONE BLOCK stacked the way a bar turned on is stacked -- not each on top of
-- the last. Bars sharing a spot would hide one another, and the eleven underneath would have to be dragged
-- off one at a time to reach the twelfth; centred as a block they are all in the middle and all visible,
-- which is what "I cannot find my bars" is asking for.
resetBars = function()
  local sw, sh = screenBox()
  if not sw then
    report("no character is in the world, so there is no screen to measure and no bar drawn to put back"
      .. " on it")
    return
  end

  local total, first = 0, true
  for n = 1, MAXBARS do
    if has(n) then
      local _, h = boxOf(n)
      total = total + h + (first and 0 or STEP)
      first = false
    end
  end

  local y = math.max(0, math.floor((sh - total) / 2))
  for n = 1, MAXBARS do
    if has(n) then
      local r = spot(n)
      local bw, bh = boxOf(n)
      r.x = math.max(0, math.floor((sw - bw) / 2))
      r.y = y
      y = y + bh + STEP
      place(n)                              -- every login's copy of that bar, at once
    end
  end
  hafen.store():flush()
  report("every bar is back in the middle of the screen")
end

-- ---------------------------------------------------------------- the way in
--
-- NEITHER OF THESE OPENS ANYTHING any more: the bars are managed on the client's own settings page, where
-- every other setting is. What is left for them is the one thing a page cannot do -- answer from where the
-- user already is, and before there is a world to be in.

local icon                                  -- the PNG, loaded once

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")
end)

local function tell()
  local said = {}
  for n = 1, MAXBARS do
    if has(n) then said[#said + 1] = "Actionbar" .. n .. " " .. mode(n) end
  end
  hafen.log():write("Actionbars: " .. table.concat(said, ", "))
  hafen.log():write("Actionbars: add, remove and rotate them in Options > AddOns > Actionbars")
end

-- A button in the action menu, one per character, which is where the client keeps everything a character
-- can do. It is a Paginae like any other, so it can itself be dragged onto a bar.
hafen.event():on("SessionEnteredWorld", function(s)
  local ok, err = pcall(function()
    -- "panel" is the entry's own id, kept as it was rather than renamed with the window it opened: a
    -- belt slot holding this entry holds it by that name, and a rename would orphan the slot.
    local pag = s:menugrid():add("panel"):name("Actionbars")
      :tooltip("what is on, and where the bars are managed")
    if icon then pag:icon(icon) end
    pag:on("use", tell)
  end)
  if not ok then hafen.log():write(err) end
  syncAll()
end)

for _, key in ipairs({"SessionAdded", "SessionRemoved", "SessionSelected"}) do
  hafen.event():on(key, syncAll)
end

-- The other door, and the only one that is there before you are in the world -- where the line lands in the
-- terminal rather than on screen, there being no character to draw it.
hafen.console():on("actionbars", tell)

-- A key the user assigns in Options is written straight into the client's registry, which tells nobody. So
-- the corner labels are re-read on a slow timer: the remap shows up within a couple of seconds, and the
-- draw never pays for it.
hafen.timer():every(2, relabel)
