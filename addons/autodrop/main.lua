-- Autodrop -- the items you name are thrown on the ground the moment they reach your backpack.
--
-- IT IS ABOUT ONE CHARACTER, AND THE WINDOW STANDS IN THAT CHARACTER'S OWN TREE. The client can hold
-- several logins at once, and a surface built the ordinary way goes in the addon layer above all of
-- them -- one window, drawn over whoever is on screen. This one is built with
-- `:parent(s:ui():match("@GameUI"))` instead (docs/addons/api/ui/custom.md), so it hangs inside that
-- character's HUD: it is drawn only while that character is on screen, it ends with them, and its place
-- is filed under them. There is one instance of everything below PER LOGIN, held in `inst` by account
-- name, and nothing here ever asks who is on screen -- except the console command, which is the client's.
--
-- THE ADDON ITSELF IS STILL ONE. It is loaded once for the client, on the login screen, before any
-- character exists (docs/addons/runtime.md). What is per character is the state it keeps, and
-- `SessionEnteredWorld` -- once per session, and again for the same session when it picks another
-- character -- is where an instance is built.
--
-- THE LIST IS THAT CHARACTER'S OWN, a per-character saved variable, so two characters keep two lists and
-- neither sees the other's. A character's tables are only readable once that session has reached the
-- world -- there is no folder before there is a character -- which is exactly the moment `adopt()` runs.
--
-- WHAT IS ON THE LIST IS A RESOURCE NAME, never a display name: the resource is an item's stable
-- identity, and a name is what its tooltip happens to say once it has resolved. So one entry answers
-- for every dandelion there will ever be, and the name beside it in the window is a label only.
--
-- WHAT CARRIES A COUNT GOES BY ITS COUNT: FULL and no other number. A pile of seeds therefore fills up in
-- your bag and goes on the ground whole, and what has no count of its own -- a dandelion, a chanterelle --
-- goes the moment it arrives, as it always did. Nothing asks what an item IS: a rule resting on what a
-- name or a resource MEANS fails by DROPPING, which is the one direction that cannot be undone.
--
-- THE COUNT IS ASKED OF TWO PLACES, because the client writes it in two. One is the number on the icon,
-- `item:quantity()`; the other is the count a counted item states in its own name -- "28 seeds of Hemp",
-- the very shape `pretty` turns round below. The name answers whenever the icon does not, and asking it is
-- what keeps a part pile out of the "carries no count at all" case -- which is the case that DROPS, and
-- the whole of why a seed pile went on the ground a handful at a time.
--
-- HELD MEANS ASKED AGAIN ON A BEAT, because a pile you add to never arrives -- it grows where it stands,
-- and the server revises its tooltip rather than sending a new item. There is no event in that to wait
-- for: the revision is only BUILT when something asks the item to describe itself -- the icon's own
-- drawing, usually -- so with the inventory shut nothing asks and nothing is announced. A pile held for
-- being short therefore goes on a list, and every BEAT it is asked again: that read takes the new count
-- and forces the build in one breath, which is the timer the docs keep for a value with no change event.
--
-- WHAT GOES ON THE GROUND IS ONE ITEM, never a kind. The drop message carries a count of ITEMS OF THAT
-- KIND -- 1 for the one you clicked, -1 for every one of them, which is the client's own ctrl+alt gesture
-- -- so the count is stated here rather than left to a default that means "all of them".
--
-- ONLY THE MAIN BACKPACK IS WATCHED. `s:ui():inventory()` is that one grid, and the subscription IS the
-- registration -- a chest, a cupboard, the study window and the equipment grid are watched for nothing,
-- so an item you deliberately put in a container is safe wherever this addon stands.
--
-- THE SLOT TAKES WHAT IS ON THE CURSOR, and that is the client's own gesture rather than a shortcut. An
-- item is never "dragged" in this game: you lift it onto the cursor and click where it goes, which is
-- exactly how it lands in a belt slot or an equipment square. The client's drag gesture -- the one
-- `widget:on("Drop", fn)` answers -- carries menu-grid actions and nothing else, so a `Drop` handler
-- here would never fire for an item at all.
--
-- A HANDLER THAT BUILDS A WIDGET STILL GOES THROUGH THE STEP, and the reason has moved rather than gone
-- (docs/addons/api/threading.md). The window is in the character's tree now, so a press on it holds THAT
-- tree -- and every builder is born in the addon layer before `:parent` re-homes it, which would be a
-- second tree. So the press decides and `hafen.timer():after(0, ...)` does: the click that adds an item,
-- the X that removes one, and the console command that opens or closes the window.

local PAD    = 6          -- design px between the content edge and everything in it
local GAP    = 4          -- between two things stacked
local W      = 212        -- content width: the list at 200, and its margins
local SQ     = 34         -- the drop slot: the client's own inventory square
local INNER  = 32         -- a row's icon: that square less its one-pixel ring
local LIST_W = W - (PAD * 2)   -- the scrolling box across
local LIST_H = 110             -- ...and down; the rest scrolls
local BAR_W  = 20         -- the room the scrollport keeps down its right edge for its own bar
local ROW_H  = 34         -- one row: the icon square, and the X beside it
local X_W    = 24         -- the X button: a Button's own art is 24 wide, and narrower clips it
local GRACE  = 20         -- tries of patience for an arriving item's resource to resolve
local WAIT   = 0.1        -- seconds between two of them
local BEAT   = 0.5        -- seconds between two looks at a pile that is being waited on
local FULL   = 50         -- seeds: the pile that goes on the ground, and no other

local ROW_W  = LIST_W - BAR_W       -- what a row has to itself
local NAME_X = INNER + GAP          -- the name, right of the icon
local NAME_W = ROW_W - NAME_X - X_W - GAP

-- The two pieces of prose, each wrapped to the full width it is given rather than written short.
local BLURB = "The items on the list below are dropped on the ground as soon as they reach your inventory."
local HINT  = "Click here holding an item to add it to the list."

-- The client's own inventory square. haven.Inventory builds `invsq` in code rather than loading a
-- resource, so there is no `.res` to name it by and these two colours ARE the art: the ring and the fill
-- the inventory, the belt and the equipment grid are all paved with. Declared as a `stock` rather than
-- painted, so a theme can dress the slot without this addon knowing that themes exist.
local FILL = {36, 52, 38, 125}
local EDGE = {20, 28, 21, 167}

-- ONE INSTANCE PER LOGIN, by account name -- the key a Session is, and the one that survives the login it
-- names (docs/addons/api/session.md). Each holds that character's own everything:
--   s      the Session it is about, named once and never re-read from the screen
--   hud    that character's @GameUI: the tree the window stands in, and the liveness test for the lot
--   saved  its `settings` table, live -- writing into it IS saving
--   wanted [res] = true, rebuilt from saved.list
--   sub    the ItemAdded subscription on its backpack
--   watching  [item] = true for a part pile of seeds being waited on; see the beat
--   win .. the window and its parts, or nil while it is closed; `rows` in saved.list's order
local inst = {}

local function reindex(st)
  st.wanted = {}
  for _, e in ipairs(st.saved and st.saved.list or {}) do st.wanted[e.res] = true end
end

-- Writing a character's tables is addressed at that character, and a login that has already gone has
-- nothing left to write.
local function save(st)
  if st.s:exists() then pcall(function() st.s:store():flush() end) end
end

-- The tail of a resource path, capitalised: what an item is called when its tooltip has not said yet.
local function tail(res)
  local t = res:match("([^/]+)$") or res
  return t:sub(1, 1):upper() .. t:sub(2)
end

-- A COUNTED ITEM NAMES ITS OWN COUNT -- "28 seeds of Hemp" -- and a count is about the one stack that was
-- picked up, never about the kind this list holds. The wire's shape is "<n> <what> of <Name>", so it is
-- turned round into what the entry actually is: "Hemp seeds". Anything else is left exactly as the server
-- said it, "Bar of Bronze" included: a name with no count in front of it is already about the kind.
--
-- Applied on the way in AND on the way out, which costs nothing -- a tidied name no longer carries a count
-- and so no longer matches -- and means an entry saved before this existed reads right without a migration.
local function pretty(name)
  local what, kind = name:match("^%d+%s+(.-)%s+of%s+(.+)$")
  if what and kind then return kind .. " " .. what end
  return name
end

-- ---------------------------------------------------------------- dropping

-- HOW MANY THIS ONE ITEM IS, asked of the two places the client writes it down. `item:quantity()` is the
-- number drawn on the icon (docs/addons/api/ui/items.md) and it is the answer whenever there is one. A
-- counted item also STATES its count in its own name -- "28 seeds of Hemp" -- and that is read when the
-- icon gives nothing, which is what a pile does whenever the code that renders its number has not resolved.
-- `nil` from both is a real answer and the only one that means "no count at all": what a dandelion says.
local function count(item)
  local n = item:quantity()
  if n then return n end
  local said = (item:name() or ""):match("^(%d+)%s+.-%s+of%s+.+$")
  return said and tonumber(said) or nil
end

-- One item, considered on the step. An item is on screen before the client knows what it is, so what has
-- not resolved yet is retried rather than read as an answer -- and `:exists()` ends the wait the moment
-- the item goes, which is what happens when the player moves it themselves.
--
-- `may` is whether this look may DROP. The items already in the bag when a character is adopted are
-- looked at with it false: emptying a backpack because somebody logged in is not what this addon means,
-- and the look is made anyway so that a part pile sitting there goes on the beat's list like any other.
local function look(st, item, tries, may)
  if not (st.saved.enabled and st.s:exists()) then return end
  if not item:exists() then
    st.watching[item] = nil                          -- gone: nothing left to wait for
    return
  end

  local function later()
    if tries > 0 then
      hafen.timer():after(WAIT, function() look(st, item, tries - 1, may) end)
    end
  end

  local res = item:res()
  if res == nil then return later() end
  if not st.wanted[res] then
    st.watching[item] = nil                          -- taken off the list while it was being waited on
    return
  end

  -- WAIT FOR THE ITEM TO BE DESCRIBED BEFORE READING ITS COUNT, and the name is what says it has been:
  -- an item arrives before the client knows what it is, and every read of its tooltip answers nil until
  -- that tooltip lands. Reading the count through that window is reading "no count" off an item that has
  -- one -- which is a part pile dropped because the answer had not arrived yet.
  if item:name() == nil then return later() end

  -- THE WHOLE RULE, AND IT IS THE COUNT AND NOTHING ELSE. What carries a count goes on the ground at
  -- FULL and is held at any other number: a pile of seeds fills up in the bag instead of being scattered
  -- a handful at a time. What carries NO count -- a dandelion, a chanterelle, a bar -- has no number to
  -- wait for and goes as it arrives, exactly as it always did.
  --   Nothing here asks what the item IS. `count` reads the name, and reads a NUMBER out of it and
  -- nothing else: a name stating no count leaves the item exactly where an icon with no number does. A
  -- rule that asked what those words MEANT is one that fails silently the day the server words something
  -- differently -- by dropping, which is the one direction that cannot be undone.
  local n = count(item)
  if n and (n ~= FULL) then
    st.watching[item] = true                         -- short: the beat asks it again as it grows
    return
  end

  if not may then return end

  st.watching[item] = nil
  -- ONE ITEM, AND SAYING SO IS THE WHOLE OF IT. `n` in the drop message is not how much of THIS item to
  -- let go -- it is how many items of its KIND, which is what the client's own click spells (WItem
  -- .mousedown: ctrl = 1, ctrl+alt = -1, the gesture that empties the bag of every pile of that thing at
  -- once). `n` defaults to -1, so the argument left untold was dropping every pile of hemp seeds in the
  -- bag the moment one of them reached 50 -- the very part piles the rule above had just held back.
  local ok, err = pcall(function() item:drop(1) end)
  if not ok then hafen.log():write(err) end
end

-- THE BEAT, and it is the whole of how a pile that was held ever leaves the bag. One for the addon rather
-- than one per login: it walks every instance there is, and an instance whose session or character has
-- gone is answered by the first two lines of `look`. The list is copied before it is walked, because a
-- look writes to it -- the pile it drops, and the pile that has left the bag by another hand.
--   `tries` is 0: the next beat IS the retry, and nothing a tighter one reached would be reached sooner.
-- `may` is true: a held pile reaching FULL is exactly what this is waiting for, and one that was in the
-- bag before its character was adopted is on this list for that same reason and no other.
hafen.timer():every(BEAT, function()
  for _, st in pairs(inst) do
    local held = {}
    for item in pairs(st.watching) do held[#held + 1] = item end
    for _, item in ipairs(held) do look(st, item, 0, true) end
  end
end)

-- ---------------------------------------------------------------- the backpack

local function bind(st)
  local inv = st.s:ui():inventory()
  if not inv then return false end

  -- The items ALREADY in the bag fire ItemAdded while this subscribes, before `:on` returns. That is the
  -- state arriving as events, and it is not what this addon is about: emptying a backpack the moment a
  -- character logs in is not what "drop it as it arrives" means. So the burst is looked at WITHOUT the
  -- leave to drop -- which is what puts a part pile of seeds under a watch without touching anything
  -- else in there. The flag can be a plain local because that burst is synchronous.
  local seeding = true
  st.sub = inv:on("ItemAdded", function(item)
    if inst[st.s:user()] ~= st then return end
    local may = not seeding
    hafen.timer():after(0, function() look(st, item, GRACE, may) end)
  end)
  seeding = false
  return true
end

-- The HUD is up the moment a character reaches the world, but the grid inside it can be a beat behind,
-- so a miss is tried again rather than lost. It gives up the moment this instance is no longer the one.
local function bindSoon(st, tries)
  if (inst[st.s:user()] ~= st) or st.sub then return end
  if bind(st) then return end
  if tries > 0 then
    hafen.timer():after(0.5, function() bindSoon(st, tries - 1) end)
  end
end

-- ---------------------------------------------------------------- the list in the window
--
-- THE ROWS ARE BUILT RATHER THAN HANDED TO A LISTBOX, and that is forced rather than chosen. A
-- `hafen.ui():listbox()` row icon is resolved through `Resource.loadrimg`, which reads the client's LOCAL
-- resource pool, and an item's art comes from the SERVER's -- so `{icon = "gfx/invobjs/bucket"}` refuses
-- the whole `:rows(t)` write and the list stays empty however many items are on it. `g:resource` reads
-- the remote pool and draws nothing rather than throwing while one resolves, so the icon is painted here
-- and a row is three widgets: the square, the name, and the X that ends it.

-- The name, shortened until it fits the room the X leaves it.
local function fit(s, w)
  if hafen.ui():measure(s).w <= w then return s end
  for n = #s - 1, 1, -1 do
    local cut = s:sub(1, n) .. ".."
    if hafen.ui():measure(cut).w <= w then return cut end
  end
  return ""
end

local refreshList                     -- forward: a row's X rebuilds the list it is standing in

local function forget(st, e)
  for i, x in ipairs(st.saved.list) do
    if x == e then
      table.remove(st.saved.list, i)
      break
    end
  end
  reindex(st)
  refreshList(st)
  save(st)
end

refreshList = function(st)
  if not (st.scroll and st.scroll:exists()) then return end

  for _, r in ipairs(st.rows) do
    r.icon:destroy()
    r.name:destroy()
    r.x:destroy()
  end
  st.rows = {}
  if st.empty then
    st.empty:destroy()
    st.empty = nil
  end

  if #st.saved.list == 0 then
    st.empty = hafen.ui():label():parent(st.scroll):position(0, 2):text("Nothing on the list yet.")
    return
  end

  local y = 0
  for _, e in ipairs(st.saved.list) do
    local res, label = e.res, pretty(e.name)

    local icon = hafen.ui():widget():parent(st.scroll):position(0, y + 1):size(INNER, INNER)
    icon:on("Draw", function(ev) ev:g():resource(res, 0, 0, INNER, INNER) end)

    local name = hafen.ui():label():parent(st.scroll):text(fit(label, NAME_W))
    name:position(NAME_X, y + math.floor((ROW_H - name:size().h) / 2))

    local x = hafen.ui():button():parent(st.scroll):size(X_W):text("X")
      :tooltip("take " .. label .. " off the list")
    x:position(ROW_W - X_W, y + math.floor((ROW_H - x:size().h) / 2))
    -- On the step: a press holds this character's tree, and building the rows again starts in the layer.
    x:on("Pressed", function() hafen.timer():after(0, function() forget(st, e) end) end)

    st.rows[#st.rows + 1] = {icon = icon, name = name, x = x}
    y = y + ROW_H
  end
end

-- ---------------------------------------------------------------- adding one
--
-- On the step, for the rows it is about to build; the hand it reads is this character's own.
local function capture(st)
  if not st.s:exists() then return end

  local h = st.s:player():hand()
  local item = h and h:item()
  if not item then
    hafen.log():write("autodrop: pick an item up first, then click the slot")
    return
  end

  local res = item:res()
  if not res then
    hafen.log():write("autodrop: that item has not resolved yet -- click again in a moment")
    return
  end

  if st.wanted[res] then
    hafen.log():write("autodrop: " .. tail(res) .. " is on the list already")
    return
  end

  local name = pretty(item:name() or tail(res))
  st.saved.list[#st.saved.list + 1] = {res = res, name = name}
  reindex(st)
  refreshList(st)
  save(st)
  hafen.log():write("autodrop: " .. name .. " will be dropped for " .. (st.s:character() or "?"))
end

-- ---------------------------------------------------------------- the window

local function forgetWindow(st)
  st.win, st.check, st.slot, st.scroll, st.empty = nil, nil, nil, nil, nil
  st.rows = {}
end

local function build(st)
  if st.win and st.win:exists() then return end
  if not (st.hud and st.hud:exists()) then return end

  -- THE ONE LINE THIS WHOLE FILE IS SHAPED BY: the window hangs in that character's HUD rather than in
  -- the addon layer, so it is drawn only while they are on screen and dies with them. `:parent(w)` is a
  -- BUILDING verb -- it is legal until the tick that first paints the surface and refused after -- which
  -- is why a window is built per login and never re-homed.
  st.win = hafen.ui():window():parent(st.hud):title("Autodrop"):size(W, 100)
  st.win:on("Close", function() forgetWindow(st) end)

  local y = PAD

  st.check = hafen.ui():check():parent(st.win):position(PAD, y):size(W - (PAD * 2)):text("Enabled")
  st.check:on("Changed", function(on)
    st.saved.enabled = on
    save(st)
  end)
  y = y + st.check:size().h + GAP

  -- Prose is PAINTED rather than labelled, because a Label is one line however long it is and the box is
  -- exactly the text: it can only end short of the edge. `g:text`'s `width` wraps at the box you name, so
  -- this fills the content width whatever the sentence and whatever the font in force.
  local bopts = {width = LIST_W}
  local bh = hafen.ui():measure(BLURB, bopts).h
  local blurb = hafen.ui():widget():parent(st.win):position(PAD, y):size(LIST_W, bh)
  blurb:on("Draw", function(ev) ev:g():text(BLURB, 0, 0, bopts) end)
  y = y + bh + GAP

  st.slot = hafen.ui():widget():parent(st.win):position(PAD, y):size(SQ, SQ):name("slot")
    :tooltip("pick an item up, then click here to add it to the list")
  -- Nothing is painted over it: an empty square is what a target looks like, and what was put in it is
  -- the row that appeared below. The square itself is DECLARED rather than drawn, so any rule beats it.
  st.slot:stock{bg = {color = FILL}, border = {color = EDGE, width = 1}}

  -- A press that is not cancelled falls through to the frame underneath, which is what drags the window.
  st.slot:on("MouseDown", function(ev)
    ev:preventDefault()
    if ev:button() == 1 then hafen.timer():after(0, function() capture(st) end) end
  end)

  local hx = PAD + SQ + GAP
  local hopts = {width = W - hx - PAD}
  local hh = hafen.ui():measure(HINT, hopts).h
  local hint = hafen.ui():widget():parent(st.win):size(hopts.width, hh)
    :position(hx, y + math.floor((SQ - hh) / 2))
  hint:on("Draw", function(ev) ev:g():text(HINT, 0, 0, hopts) end)
  y = y + SQ + GAP

  -- The scrolling box the rows stand in: it keeps its own bar down the right edge and brings it alive on
  -- its own once the rows outgrow the box, so the window is one height whatever is on the list.
  st.scroll = hafen.ui():scroll():parent(st.win):position(PAD, y):size(LIST_W, LIST_H)
  y = y + LIST_H + PAD

  -- Remembered LAST, and the size written after it: where the user put the window is theirs to keep,
  -- while how big it is belongs to this layout and not to whatever an older version wrote down.
  --   THE NAME CARRIES THE ACCOUNT because one name is one widget for the whole addon, and two logins
  -- have two of these windows at once. What is written under it is filed by the tree the window stands
  -- in -- this character's own folder, like their list -- so two characters put the window in two places.
  st.win:remember("window-" .. st.s:user())
  st.win:size(W, y)

  st.win:title("Autodrop: " .. (st.s:character() or "?"))
  st.check:value(st.saved.enabled and true or false)   -- a write of ours never fires Changed
  refreshList(st)
end

-- ---------------------------------------------------------------- one login, adopted
--
-- Idempotent, so every door into it can simply call it. THE HUD IS THE TEST: a character switch destroys
-- the old @GameUI and everything of ours that stood in it, and `SessionEnteredWorld` fires again for the
-- same account with no `SessionRemoved` between -- so a dead HUD is exactly "this is a new character,
-- build the lot again", and a live one is the reload's second announcement, which has nothing to do.
local function adopt(s)
  local user = s:user()
  local old = inst[user]
  if old and old.hud and old.hud:exists() then return end

  if old and old.sub then pcall(function() old.sub:off() end) end
  inst[user] = nil

  local hud = s:ui():match("@GameUI")
  if not hud then return end                       -- no HUD, no tree to stand in: not in the world yet

  local ok, t = pcall(function() return s:store():get("settings") end)
  if not ok then return end                        -- its folder is not there yet either

  local st = {s = s, hud = hud, saved = t, wanted = {}, watching = {}, rows = {}}
  st.saved.list = st.saved.list or {}              -- { {res = , name = }, ... }, in the order they were added
  if st.saved.enabled == nil then st.saved.enabled = true end
  reindex(st)
  inst[user] = st

  bindSoon(st, 10)
  build(st)
end

-- The two events that matter. `SessionSelected` is not among them any more: which character is on screen
-- decides which of these windows is drawn, and the client does that by itself now that each one stands in
-- a tree of its own. `SessionRemoved` names a session that has already gone, and its payload is the key.
hafen.event():on("SessionEnteredWorld", adopt)
hafen.event():on("SessionRemoved", function(s) inst[s:user()] = nil end)

-- A `:reload` re-announces ONLY the session on screen (docs/addons/runtime.md), so the characters logged
-- in behind it would sit there without a window until they picked another character. This is the whole of
-- what makes a reload equal to a fresh login for every one of them.
for _, s in ipairs(hafen.session():list()) do
  if s:exists() and s:character() then adopt(s) end
end

-- Closing the window closes the window and nothing else: the backpack goes on being watched, and the
-- switch inside is what stops it. A console line runs in the tree of the console it was typed at, and the
-- window it opens is built in another one -- hence the step. The command is the client's, so this is the
-- one place in the file that asks who is on screen.
hafen.console():on("autodrop", function()
  hafen.timer():after(0, function()
    local s = hafen.session():current()
    local st = s and inst[s:user()]
    if not st then
      hafen.log():write("autodrop: no character on screen")
      return
    end
    if st.win and st.win:exists() then
      st.win:destroy()
      forgetWindow(st)
    else
      build(st)
    end
  end)
end)
