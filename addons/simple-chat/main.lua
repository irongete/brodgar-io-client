-- Simple Chat -- the client's chat, with the channel list as TABS across the top.
--
-- The client puts its channels down the LEFT side of the chat, one tab under the last, and the window is
-- docked into the HUD: it cannot be moved and it cannot be resized. This addon draws the same channels and
-- the same lines in a window of its own, with real tabs standing along the top, dragged by its body and
-- sized from its bottom-right corner. The client's own chat is hidden while this runs.
--
-- TWO HALVES, NOT ONE WIDGET, and that is what makes the tabs tabs. `root` is an invisible box that draws
-- nothing and exists only to hold them; the chat's own rectangle starts BELOW the strip the tabs stand in.
-- So a tab is not inside the chat's frame reaching up -- it is outside it, above it, a sibling of that
-- rectangle rather than a child of it. Anything else clips: a child is drawn inside its parent's box, so a
-- tab hung on the panel could never rise above the panel's own top edge.
--
--     root ┌──────────────────────────────┐
--          │ [System][Area][Green]        │   <- the tabs: siblings of the panel, standing above it
--          │ ╔═════════╝      ╚═══════════╗│   <- the frame, with the lit tab's mouth left undrawn
--          │ ║ panel: the lines, the line ║│   <- the field: the action bars' colour, at their alpha
--          │ ╚════════════════════════════╝│
--          └──────────────────────────────┘
--
-- AND THAT RECTANGLE IS ITSELF TWO THINGS. `panel` is the FIELD and carries no border whatever; the FRAME
-- is drawn over it through three windows which between them leave one stretch of its top edge undrawn
-- (`cutFrame`). That gap is the mouth, and it has to BE a gap rather than a patch of paint: the field is
-- translucent, and nothing translucent hides brass.
--
-- The tab ON SCREEN stands exactly where the others do and is cut exactly as they are: same top row, same
-- brass down both sides. Its FOOT is the only difference -- it is open, and it reaches on down through the
-- air the others leave under them until it is in the frame, so its fill runs into that gap and the two
-- become one shape. The others are closed boxes, touching nothing. That difference -- a missing line along
-- the bottom, and the air under the ones that keep theirs -- is the whole of what says
-- "this tab is the mouth of that panel", and it is why the art is a file rather than a
-- {color=, width=} line: a line goes all the way round by definition.
--
-- AND THE METAL IS ONE METAL. A tab's art is the panel's own frame -- `gfx/hud/wnd`, cut into files at the
-- design pixels the client draws it at -- so the brass does not resemble the window's, it IS the window's.
-- What that buys is the JOINT: at each end of the mouth the frame's top run turns UP into the selected
-- tab's side and the line never breaks. No nine-slice can cut that corner, because all four of its own have
-- the metal in one quadrant and the world round three, and this one is the other way about. So it is mitred
-- from the two runs' own cross-sections and laid over the end of the mouth (`joint-l.png`, `joint-r.png`).
--
-- ONE WINDOW PER CHARACTER, AND IT HANGS ON THAT CHARACTER'S OWN HUD. A chat belongs to a login -- two
-- characters have two System logs and two Party channels -- so the window that shows one belongs to that
-- login too: built from SessionEnteredWorld into its `@GameUI`, and dying with it. Nothing here exists
-- before a character does, which is the whole of why there is no chat window on the login screen and none
-- on the character list -- not a window kept hidden there, no window at all.
--
-- TWO OF THEM ARE NEVER ON SCREEN AT ONCE, either, and that is not this addon's doing: the client holds
-- every login as a live session and DRAWS ONE (`Sessions`, the anchor). The other character's window is
-- not hidden and not moved aside -- it is simply not in the tree the frame walks. So a window per login
-- costs the user nothing to look at, and it buys the thing that matters: each one reads its OWN chat, so
-- there is no "point the window at whoever is on screen" step to get wrong.
--
-- PICKING ANOTHER CHARACTER ON THE SAME ACCOUNT IS A REBUILD, not a restore. The session lives on -- the
-- server hands it a new world rather than ending it -- but the HUD is torn down and a new one put up, and
-- this window goes with it. SessionEnteredWorld fires a second time for that same session, and that is
-- where the new one is built.
--
-- THE PRESS MODEL. root:draggable(grip) hands the whole body to the user, and a press on a drag handle
-- starts the gesture and does NOTHING else -- so anything that wants a click of its own has to sit ABOVE
-- the grip and consume the press. Children are hit before their parent and the last one added is on top,
-- so the order things are built in IS the input order: grip first, then the panel and the frame over it,
-- then the tabs, which are therefore also drawn OVER the frame. The log subscribes to Draw and Wheel only -- a
-- surface with no MouseDown answers false, so a press on it falls through to the grip and drags.
--
-- THE LAYOUT RUNS ON UPDATE, NOT AT BUILD. A control DRAWS NOTHING UNTIL THE TICK AFTER the statement that
-- built it, so a text entry's height -- its own art's, the one measurement this window cannot pick -- is
-- not there to be read in the breath that built it. Update re-lays the window whenever its box or that
-- height moves. Everything is nevertheless BUILT at the right coordinates using ENTRY_H as a stand-in, so
-- the window is never wrong even for one frame, and the fold only corrects.
--
-- AND THE CLIENT'S CHAT IS NOT TAKEN AWAY UNTIL THAT LAYOUT HAS SUCCEEDED ONCE. An addon that hides the
-- chat and then fails to stand in for it leaves the player with no chat AND no way to read the error,
-- since the error is written to the very channel it just hid.

-- EIGHT, AND IT IS MEASURED. A {box = "gfx/hud/wnd"} border reserves its corner's own size, which is what
-- `new IBox.Scaled("gfx/hud/wnd", ...).ctloff()` answers: (8, 8) design px. The client's own windows look
-- like they use 5 because `Window.wbox` is a subclass that subtracts UI.scale(3,3) so its content may sit
-- over the frame's soft outer edge -- that subtraction is the WINDOW's, not the border's, and a rule that
-- names the box gets the plain 8. Guessing this from a window is how the tabs came out two pixels adrift.
local EDGE_T   = 8
local PAD      = EDGE_T + 2   -- the room content keeps inside the frame, so nothing sits on the art
-- ONE PIXEL, AND IT IS NOT ARBITRARY. The tab ON SCREEN is sunk this far into the frame's box, so that its
-- foot and the window below it OVERLAP rather than merely meet: two boxes that only touch can still be
-- pulled a hairline apart by the scale the client draws at, and what opens in that hairline is raw world,
-- between a tab and the window it belongs to. SEAT is what closes it. No other tab carries it, because no
-- other tab reaches the frame at all (TAB_LIFT).
--
-- IT IS ALSO WHY THE FIELD STARTS ONE ROW LOWER THAN THE FRAME. The field is translucent, so a row painted
-- twice is not the same colour as a row painted once -- the tab's foot laid over the field would draw a
-- line across the very mouth it exists to open. So the row the foot occupies is the row the field gives up:
-- the frame's box begins at STRIP and the panel's at STRIP + SEAT, the two are painted side by side and
-- never over each other, and the overlap that closes the hairline costs nothing.
local SEAT     = 1
local GAP      = 2            -- between two tabs, and between two lines
-- A TAB WEARS THE PANEL'S OWN FRAME, at its own weight, which is what these two numbers are: the corner
-- `gfx/hud/wnd` reserves and the thickness of its edge runs. A tab's art is sliced at the CORNER and a
-- joint is one RUN square, because a joint is exactly where two runs cross.
local CORNER   = 8
local RUN      = 7
local TAB_H    = 28           -- a tab that is not on screen: the run, a line of text, the run
-- EVERY TAB STARTS ON THE SAME ROW, this far down the strip -- the lit one included. It is the strip's own
-- top margin and nothing else: a tab that reached higher than its neighbours would read as a bigger tab
-- rather than as the open one, and what says "open" is the FOOT, which is the end nobody else has.
local TAB_DROP = 3
-- AND THE AIR UNDER IT. Only the tab ON SCREEN belongs to the panel: it plugs into the frame and opens a
-- mouth there. A tab that is not on screen belongs to nothing yet, so it does not touch the frame either --
-- it stands clear of it by this much, and the gap is the whole of what says "this one is not the one you
-- are reading". The selected tab reaches down through the same air and past it.
local TAB_LIFT = 1
local STRIP    = TAB_DROP + TAB_H + TAB_LIFT   -- the band above the panel that the tabs live in
-- THE SELECTED TAB ENDS AT THE FRAME'S TOP EDGE and goes no further. Running it on -- down over the
-- border to meet the log -- drags its two brass SIDES down with it, and a pair of verticals crossing the
-- frame into the content is the mistake that made this look wrong. A real tab's sides stop at the frame;
-- what reaches past it is the hole the tab opens, and a hole is not a shape a nine-slice can cut -- which
-- is why the frame is not one here (see `cutFrame`).
--
-- So the lit tab is EXACTLY its neighbours plus the air they leave under them: same top, same width, the
-- same brass down both sides, and the one difference is that it goes on to where the frame is instead of
-- stopping short of it. That difference is TAB_LIFT tall, and it is the whole of what it means to be open.
local TAB_ON_H = STRIP - TAB_DROP
-- ...and how far down the frame is CUT where that tab meets it: past every pixel the frame draws along its
-- top edge -- the run is 7 design px tall and a corner 8 -- and nowhere near what it draws down its sides.
local HOLE_H   = PAD
local TAB_MAX  = 110          -- as wide as a tab gets before the row starts sharing out
local TAB_MIN  = 44           -- ...and as narrow as one may be squeezed, the two runs included
-- The corner the user grabs, and it is EXACTLY the frame's own corner -- PAD square, flush with the panel's
-- bottom-right. A grip standing inside the field instead would have to be kept clear, and the only thing
-- that can give it that room is the line the user types in: the entry would stop a grip's width short of
-- the frame and leave a bite out of the window's one full-width control. So the grip goes on the brass,
-- where nothing else is, and the entry runs to the ink like every other row.
local SIZER    = PAD
local ENTRY_H  = 20           -- what the entry is assumed to be until its own art answers
local MIN_W    = 240          -- a window narrower than this cannot show a tab and a line
local MIN_H    = STRIP + 120
local DEF_W    = 440          -- where it stands before anyone has moved it
local DEF_H    = STRIP + 190
local DEF_X    = 20
local DEF_Y    = 20

-- WHAT IT LOOKS LIKE WHEN NOBODY SAYS OTHERWISE, and every bit of it is DECLARED rather than painted.
-- `:stock` is the bottom of the style cascade, so any rule -- a theme's -- beats it, per property, without
-- this addon knowing that themes exist; painting the same pixels in a Draw handler would be final and
-- reachable by nothing. `:name` is the other half, and the one that enables: the engine writes this addon's
-- id in front, so the selector a theme writes is [name=simple-chat/panel].
-- A TAB IS CUT FROM THE PANEL'S OWN FRAME, and the shipped files are that frame and nothing else:
-- `gfx/hud/wnd`'s four corners and four runs, downsampled from the scale the client authors them at to the
-- design pixels a file is authored in. So a tab is the same brass the panel's border is -- not a band that
-- resembles it -- and the two meet without a seam because they are one material.
--
-- THE TAB ON SCREEN IS CUT 8/8/8/1: corners and runs down three sides, and a bottom slice of ONE row that
-- carries the two side runs and nothing between them. That is the open foot, and it is why the sides reach
-- the tab's own bottom edge instead of stopping a corner short of it.
local BOX       = "gfx/hud/wnd"
local TAB_OFF   = "tab.png"
local TAB_ON    = "tab-on.png"
local SLICE_OFF = {CORNER, CORNER, CORNER, CORNER}
local SLICE_ON  = {CORNER, CORNER, CORNER, 1}
-- THE JOINT, and the client has no such piece -- it cannot. Every corner a nine-slice carries has the metal
-- in ONE quadrant with the world wrapped round three; where the panel's top run turns up into the selected
-- tab's side it is the other way about, metal three and world one. So the two are shipped: one RUN square,
-- mitred out of the two runs' own cross-sections, laid over each end of the mouth.
local JOINT_L   = "joint-l.png"
local JOINT_R   = "joint-r.png"
-- ONE FIELD, EDGE TO EDGE, AND EVERY SURFACE THAT MEETS IT WEARS IT -- and it is the ACTION BARS' OWN, the
-- same four numbers at the same alpha. A single colour under a frame is what a panel in this game is: a
-- chat drawn instead as a dark log inset into a lighter field is two colours, and the ring left between
-- them reads as a second border the window has not got. So the field is the PANEL's own bg, painted at the
-- frame's own box, and the log paints nothing at all. What looks like the background behind the lines IS
-- the panel's, and the room the lines keep off the frame is a margin INSIDE one colour rather than a second
-- colour around them.
--
-- TRANSLUCENT, WHICH IS WHAT THE FRAME COSTS. A border's centre is never painted -- that is bg's job -- so
-- nothing this window draws can HIDE the brass under a tab's mouth: at this alpha the frame's top run would
-- ghost straight through any patch laid over it. What opens the mouth, therefore, is not paint but the
-- absence of it: the frame is drawn through windows that leave that stretch of its top edge undrawn
-- (`cutFrame`), so the mouth is world and field exactly like everything else, and the selected tab's foot,
-- being the same colour at the same alpha over the same world, is literally the same pixels.
local FIELD   = {43, 51, 44, 127}       -- the panel, the lines, the tab on screen: one colour
local FILL    = {14, 19, 15, 190}       -- ...and the tabs behind it, sunk: darker, and more solid
local TEXT    = {206, 214, 200, 255}    -- a line, or the tab on screen
local DIM     = {148, 158, 144, 255}    -- a tab that is not the one on screen
local UNREAD  = {235, 196, 96, 255}     -- a tab with something in it nobody has read
local GRIP    = {26, 34, 24, 200}       -- the three strokes cut across the frame's corner

local ELLIPSIS = "\226\128\166"

-- ---------------------------------------------------------------- one window per login

-- EVERY MUTABLE THING THIS ADDON HAS IS IN HERE, and there is one of these per character in the world.
-- Nothing is a module-level `root` or `scroll` any more: two characters have two scrollbacks parked at two
-- different lines, two selected tabs and two windows in two states of layout, and a single set of those
-- variables could only ever describe one of them.
--
--   s       the login this window is showing -- the address every read below goes through
--   hud     that character's `@GameUI`: what the window hangs on, and what it is kept inside
--   root    the invisible box holding the strip and the panel together (see the drawing above)
--   frame   the three windows the frame is drawn through: {clip = , ink = }
--   tabs    one bare widget per channel, rebuilt when the channel list changes
--   scroll  how many lines up from the newest this log is parked
--   lines   the laid-out window of lines, rebuilt when `sig` moves
--   sig     channel, count, box and scroll: what `lines` was laid out for
--   shape   the root's box and the entry's height: what the layout last ran for
--   laidOut has the fold ever completed? the client's chat is not taken until it has
--   told    a refusal is reported once, not once a frame
--   hidden  the client's own chat for THIS login, if we are the one that put it away
local wins = {}                         -- keyed by the Session, which is interned and safe as a key

-- The box is the ACCOUNT'S, not the character's -- `{"scope": "account"}` in the manifest -- so every
-- character opens the chat where this user likes the chat, and dragging it on one moves it for all of them.
-- Reached with no address for exactly that reason: an account variable has no session to name.
local function saved()
  return hafen.store():get("window")
end

-- ---------------------------------------------------------------- where it may stand

-- THE WHOLE WINDOW STAYS ON SCREEN, and that is stricter than the client's own rule. The client keeps a
-- window it places GRASPABLE -- at least 100 px of it inside its parent -- and applies the same rule to
-- anything an addon drags, so this window was never able to vanish. But "100 px of it" is the whole of an
-- action bar's height and a fifth of its length, which is why a bar reads as unable to leave the screen at
-- all; on a chat three hundred tall and eight hundred wide the same allowance lets most of it walk off. So
-- this says outright what the bars only appear to say: the window is kept inside, entire.
--
-- Inside WHAT is the character's HUD, which is the screen: `@GameUI` is that login's whole view, so the
-- rule is the one it always was, and a client window made smaller pulls this one back in on the next frame.
--
-- Bigger than the screen, it is pinned to the top-left instead -- the corner every other rule here measures
-- from, and the one that keeps the tabs reachable.
local function onScreen(W, x, y)
  if not (W.hud and W.hud:exists()) then return x, y end
  local s = W.hud:size()
  if not (s and s.w and s.h and (s.w > 0) and (s.h > 0)) then return x, y end
  local b = W.root:size()
  return math.max(0, math.min(x, s.w - b.w)), math.max(0, math.min(y, s.h - b.h))
end

-- Put it back if it is outside, and say whether it had to move. Called every frame, so a drag is bounded
-- as it happens rather than snapped back on release: the gesture computes each step from the POINTER and
-- not from where the window currently is, so a step we overrule costs the next one nothing.
local function keepOnScreen(W)
  if not (W.root and W.root:exists()) then return false end
  local p = W.root:position()
  local x, y = onScreen(W, p.x, p.y)
  if (x == p.x) and (y == p.y) then return false end
  W.root:position(x, y)
  return true
end

-- THIS WINDOW'S CHAT, and it is its own login's -- never `hafen.session():current()`'s. That is the whole
-- difference the HUD bought: a window reads the character it was built for, on screen or behind it, so
-- nothing in here has to ask who is being looked at.
local function chat(W)
  return W.s:exists() and W.s:chat() or nil
end

local function selected(W)
  local c = chat(W)
  return c and c:selected() or nil
end

-- ---------------------------------------------------------------- geometry

-- The entry's height is its field ART's, which is the one measurement this window cannot make: a box a
-- pixel short of it loses the border. Until the art has answered -- which is not before the tick after the
-- entry was built -- ENTRY_H stands in, and the fold below re-lays everything the moment it does.
local function entryH(W)
  if not W.entry then return ENTRY_H end
  local b = W.entry:size()
  if b and b.h and (b.h > 0) then return b.h end
  return ENTRY_H
end

-- Inside the PANEL, which is where all of this lives: the tabs are the root's business, not the panel's.
local function logBox(W, ph, pw)
  return PAD, PAD, pw - (PAD * 2), ph - (PAD * 2) - GAP - entryH(W)
end

-- ---------------------------------------------------------------- the lines

-- A line is what was SAID plus who said it. msg:text() is the line alone -- the speaker's name is not part
-- of it, which is why the client draws it separately and why this has to put it back.
local function lineText(m)
  local who = m:speaker()
  local said = m:text() or ""
  if who then return (who:name() or "?") .. ": " .. said end
  return said
end

-- Lay out the lines that fit, newest at the bottom, walking backwards from wherever the scroll is parked.
-- Wrapping is the client's own: measure reads the same markup the draw does, so a line carrying $col
-- measures as the words it renders to and not as the characters it is spelled with.
local function relayout(W, ch, w, h, n)
  W.lines = {}
  local msgs = ch:message()
  local opts = {width = w}
  local used, i = 0, n - W.scroll
  while (i >= 1) and (used < h) do
    local m = msgs:get(i)
    if not m then break end
    local text = lineText(m)
    local box = hafen.ui():measure(text, opts)
    table.insert(W.lines, 1, {text = text, h = box.h, color = m:color()})
    used = used + box.h + GAP
    i = i - 1
  end
end

-- THE LINES, AND NOTHING UNDER THEM. The field is the panel's, edge to edge, so a rectangle painted here
-- would be a second colour inset in the first -- and that inset ring is exactly what made this window read
-- as a box inside a box. What this surface is, then, is the RECTANGLE THE LINES ARE LAID OUT IN: an inset
-- that shows as a margin rather than as a border, because there is no colour of its own to draw its edge.
--
-- The box comes from the EVENT, not from logBox(): the two agree once the layout has run, and between a
-- resize and that fold they do not. Wrapping to the box the widget actually is can never spill outside it.
local function paintLog(W, ev)
  local g = ev:g()
  local w, h = ev:w(), ev:h()

  local ch = selected(W)
  if not ch then return end
  local n = ch:message():count()

  -- Re-lay out only when something that decides the layout moved. Measuring is cached, so a re-layout is
  -- cheap after the first frame, but walking the whole visible window every frame is not free either.
  local now = tostring(ch) .. ":" .. n .. ":" .. w .. ":" .. h .. ":" .. W.scroll
  if now ~= W.sig then
    W.sig = now
    relayout(W, ch, w, h, n)
  end
  if not W.lines then return end

  local y = h
  for k = #W.lines, 1, -1 do
    local L = W.lines[k]
    y = y - L.h - GAP
    g:text(L.text, 0, y, {width = w, color = L.color or TEXT})
  end
end

-- ---------------------------------------------------------------- the tabs

-- A name too wide for its tab is cut rather than wrapped: a tab is one line by construction. Measured
-- rather than counted, because a proportional font makes "William" and "lillian" different widths.
local function fit(name, w)
  if hafen.ui():measure(name, {}).w <= w then return name end
  for cut = #name, 1, -1 do
    -- Never cut inside a character. Lua counts BYTES and a channel name may be UTF-8, so a cut landing on
    -- a continuation byte would draw the tail of a letter as a broken glyph.
    local nxt = name:byte(cut + 1)
    if not (nxt and (nxt >= 128) and (nxt < 192)) then
      local try = name:sub(1, cut) .. ELLIPSIS
      if hafen.ui():measure(try, {}).w <= w then return try end
    end
  end
  return ""
end

-- Which of the two faces this tab wears, said as a STOCK so a theme can replace either. Re-declared when
-- the selection moves rather than chosen in a Draw handler: a bare widget enters none of the states a
-- face-per-state rule keys on, so the state has to be said from out here.
local function dressTab(t, on)
  t:stock{bg = {color = on and FIELD or FILL},
          border = {asset = on and TAB_ON or TAB_OFF,
                    slice = on and SLICE_ON or SLICE_OFF}}
end

local function tabWidth(n, width)
  local tw = math.floor(((width - (PAD * 2)) - (GAP * (n - 1))) / n)
  if tw > TAB_MAX then tw = TAB_MAX end
  if tw < TAB_MIN then tw = TAB_MIN end
  return tw
end

-- THE FRAME, AND THE HOLE THE SELECTED TAB OPENS IN IT. A tab meeting its panel is a *hole* in the frame,
-- and a hole is not a shape a nine-slice can cut: a border goes all the way round by definition. Nor can it
-- be painted out, because the field is translucent and a translucent patch does not hide brass.
--
-- So the frame is drawn THREE TIMES, at the same size and in the same place every time, each inside a
-- window that shows only part of it: the top edge left of the hole, the top edge right of it, and
-- everything below the top edge. A child is clipped to its parent's box, so a window is nothing but a box
-- with the frame parented into it at the offset that cancels its own -- which is why nothing can be
-- misregistered here. It is one drawing, seen through three holes in a mask, and the stretch no window
-- shows is the mouth.
--
-- `x1`/`x2` are the mouth's edges in the root's own coordinates; x1 = x2 = the width leaves the frame whole.
local function cutFrame(W, x1, x2)
  if not (W.root and W.root:exists()) then return end
  local b = W.root:size()
  local w = b.w
  local fh = math.max(1, b.h - STRIP)
  local function window(i, cx, cy, cw, ch)
    local f = W.frame[i]
    if not (f and f.clip:exists()) then return end
    if (cw < 1) or (ch < 1) then
      f.clip:visible(false)
      return
    end
    f.clip:visible(true)
    f.clip:position(cx, STRIP + cy):size(cw, ch)
    f.ink:position(-cx, -cy):size(w, fh)      -- the offset that cancels the window's own
  end
  window(1, 0, 0, math.min(x1, w), HOLE_H)              -- the top edge, left of the mouth
  window(2, math.min(x2, w), 0, w - math.min(x2, w), HOLE_H)   -- ...and right of it
  window(3, 0, HOLE_H, w, fh - HOLE_H)                  -- ...and everything below the top edge, whole
end

-- PLACING is not BUILDING, and the split is the point: placing runs inside the Update fold, where
-- destroying a widget mid-walk would be a change to the tree the walk is already inside. So the fold only
-- ever moves and resizes the tabs that exist; creating and destroying them happens on the events that
-- actually change the channel list, which run outside the walk.
--
-- The selected tab is placed differently, not merely painted differently: it starts at the top of the
-- strip, and the frame is cut for exactly its mouth.
local function placeTabs(W)
  if not (W.root and W.root:exists()) then return end
  local width = W.root:size().w
  local h1, h2 = width, width           -- no tab on screen, and then the frame is whole
  local lit = false
  local n = #W.tabs
  if n > 0 then
    local c = chat(W)
    local tw = tabWidth(n, width)
    for i, t in ipairs(W.tabs) do
      local on = (c and (c:selected() == t.ch)) or false
      local x = PAD + ((i - 1) * (tw + GAP))
      local room = (x + tw) <= (width - PAD)
      t.w:visible(room)
      if room then
        -- Every tab is placed at the same row and differs only in HEIGHT, which is why the lit one grows
        -- downwards alone. SEAT rides on the height for the same reason: the top does not move a pixel
        -- when it is tuned. And only the lit tab carries it -- it exists to overlap the field, and a tab
        -- standing clear of the frame has nothing under it to overlap.
        t.w:position(x, TAB_DROP):size(tw, on and (TAB_ON_H + SEAT) or TAB_H)
        -- THE MOUTH IS THE TAB'S WHOLE WIDTH, its sides included. The frame's run is not drawn under this
        -- tab at all: what carries the metal round the corner there is the tab's own side coming down and
        -- the JOINT at its foot, and a run left drawn beneath them would cross both with a line of its own.
        if on then
          h1, h2 = x, x + tw
          W.jointL:position(x, STRIP)
          W.jointR:position(x + tw - RUN, STRIP)
          lit = true
        end
      end
      t.tw, t.ty = tw, TAB_H / 2      -- one row for every name: every tab starts at TAB_DROP
    end
  end
  W.jointL:visible(lit)
  W.jointR:visible(lit)
  cutFrame(W, h1, h2)
end

local function clearTabs(W)
  for _, t in ipairs(W.tabs) do
    if t.w:exists() then t.w:destroy() end
  end
  W.tabs = {}
end

local function dressAll(W)
  local c = chat(W)
  for _, t in ipairs(W.tabs) do
    if t.w:exists() then dressTab(t.w, (c and (c:selected() == t.ch)) or false) end
  end
end

local function buildTabs(W)
  clearTabs(W)
  if not (W.root and W.root:exists()) then return end
  local c = chat(W)
  if not c then return end
  local list = c:list()
  if #list == 0 then return end

  for i, ch in ipairs(list) do
    -- Parented to the ROOT, not to the panel: that is what puts it outside the chat's frame. And built
    -- after the panel, so it is drawn over the panel's top border rather than under it.
    local t = hafen.ui():widget():parent(W.root):position(PAD, TAB_DROP):size(TAB_MIN, TAB_H)
    t:name("tab" .. i)
    local rec = {w = t, ch = ch, tw = TAB_MIN, ty = TAB_H / 2}
    dressTab(t, c:selected() == ch)
    -- The stock draws the plate and the frame; this draws the one thing a stock cannot say, which is the
    -- name, and the one colour that is a fact about the channel rather than about the tab. The text is
    -- centred in the tab's FACE -- the part standing above the panel -- not in its whole box, which for
    -- the selected one runs on down behind the frame.
    t:on("Draw", function(ev)
      local live = chat(W)
      local on = (live and (live:selected() == ch)) or false
      local col = TEXT
      if (ch:urgency() or 0) > 0 then col = UNREAD elseif not on then col = DIM end
      local room = rec.tw - (RUN * 2) - 2            -- the two runs, and a pixel of air off them
      -- rec.ty is the line the name sits on, in this tab's OWN coordinates -- and it is computed so that
      -- the selected tab's name and the others' land on the same row of the screen, though one widget
      -- starts at the top of the strip and the rest a few pixels down it.
      ev:g():atext(fit(ch:name() or "?", room), rec.tw / 2, rec.ty, 0.5, 0.5, {color = col})
    end)
    -- A tab takes its own press, which is what keeps it from dragging the window under it.
    t:on("MouseDown", function(ev)
      if ev:button() == 1 then
        local live = chat(W)
        if live and ch:exists() then pcall(function() live:selected(ch) end) end
        W.scroll = 0
      end
      ev:preventDefault()
    end)
    W.tabs[#W.tabs + 1] = rec
  end
  placeTabs(W)
end

-- ---------------------------------------------------------------- the client's own chat

-- TAKE IT ONCE, and not until this window has laid itself out: an addon that hides the chat and then fails
-- to stand in for it leaves the player with no chat AND no way to read the error, since the error is
-- written to the very channel it just hid. It is THIS login's chat, reached through THIS login's own UI --
-- so a character in the background never loses the chat belonging to the one on screen.
local function takeChat(W)
  if W.hidden or not W.s:exists() then return end
  local c = W.s:ui():match("@ChatUI")
  if not (c and c:visible()) then return end
  local ok, err = pcall(function() c:visible(false) end)
  if ok then W.hidden = c else hafen.log():write(err) end
end

-- GIVE IT BACK BY HAND. Hiding a native widget records a restore, but teardown's rule is "the widget ends
-- up as the user was seeing it" -- and the chat is not one of the windows the client can reopen, so a bare
-- hide left to teardown would leave the player with no chat and nothing in the interface to bring it back.
local function giveChatBack(W)
  if W.hidden and W.hidden:exists() then pcall(function() W.hidden:visible(true) end) end
  W.hidden = nil
end

-- ---------------------------------------------------------------- layout

local function relayoutFrame(W)
  local b = W.root:size()
  local ph = math.max(1, b.h - STRIP - SEAT)   -- the FIELD's height: the frame's box, less the row the
  local eh = entryH(W)                         -- tabs' feet occupy (see SEAT)
  W.grip:size(b.w, b.h)
  W.panel:position(0, STRIP + SEAT):size(b.w, ph)
  local lx, ly, lw, lh = logBox(W, ph, b.w)
  W.log:position(lx, ly):size(math.max(1, lw), math.max(1, lh))
  -- The entry runs the whole width the frame leaves, exactly as the lines above it do: the grip it used to
  -- stand clear of is on the brass now, outside every row.
  W.entry:position(PAD, ph - PAD - eh):size(math.max(1, b.w - (PAD * 2)))
  W.sizer:position(b.w - SIZER, b.h - SIZER)   -- the root's corner, which is the frame's
  W.sig = nil
  placeTabs(W)
end

-- The fold: three numbers, every frame, and a re-layout only when one of them moved. Guarded, because a
-- window that cannot lay itself out must not also take the chat away -- and because a refusal in here is
-- otherwise written to a channel this addon has hidden, which is the one place nobody can read it.
local function tick(W)
  if not (W.root and W.root:exists()) then return end
  keepOnScreen(W)
  local b = W.root:size()
  local now = b.w .. "x" .. b.h .. "@" .. entryH(W)
  if now ~= W.shape then
    local ok, err = pcall(relayoutFrame, W)
    if ok then
      W.shape = now
      -- Only now, and never before: the window is up, so the client's may go.
      if not W.laidOut then
        W.laidOut = true
        takeChat(W)
      end
    elseif not W.told then
      W.told = true
      hafen.log():write("simple-chat could not lay its window out: " .. tostring(err))
    end
  end
end

-- What is written down is where the window is ALLOWED to be, never where the pointer let go: the clamp runs
-- first, so a place that could not be kept is never the place the next character restores.
local function remember(W)
  keepOnScreen(W)
  local box, p, b = saved(), W.root:position(), W.root:size()
  box.x, box.y, box.w, box.h = p.x, p.y, b.w, b.h
end

-- ---------------------------------------------------------------- building

-- ONE CHARACTER'S WINDOW, hung on that character's own HUD. Called from SessionEnteredWorld and from the
-- Load sweep, and from nowhere else: there is no window without a character to own it, which is the whole
-- of why nothing of this addon is on screen before one is.
local function build(s, took)
  local hud = s:exists() and s:ui():match("@GameUI")
  if not hud then return nil end

  local box = saved()
  local w = math.max(MIN_W, box.w or DEF_W)
  local h = math.max(MIN_H, box.h or DEF_H)
  local fh = h - STRIP          -- the FRAME's box: it begins where the tabs stop
  local ph = fh - SEAT          -- ...and the FIELD's, one row lower, so no tab foot is painted over it

  local W = {s = s, hud = hud, frame = {}, tabs = {}, scroll = 0,
             lines = nil, sig = nil, shape = nil, laidOut = false, told = false, hidden = nil}
  -- THE CHAT IS HANDED OVER, NOT GIVEN BACK AND TAKEN AGAIN. A window replacing another of ours inherits
  -- whatever the first one had already put away, so the client's chat never reappears for the frame
  -- between the two. A dead handle -- the ordinary case, since a character switch takes the whole HUD
  -- with it -- inherits nothing, and `takeChat` puts the new one away on the tick the layout lands.
  if took and took:exists() then W.hidden = took end

  -- The root draws NOTHING: no stock, no name a theme would want. It is the strip and the panel held
  -- together so the two can be dragged and sized as one thing.
  W.root = hafen.ui():widget():parent(hud):size(w, h):position(box.x or DEF_X, box.y or DEF_Y)
  wins[s] = W                            -- registered here, not at the end: from this line on there is
                                         -- something for `drop` to tidy if anything below throws

  -- FIRST, so everything built after it sits above it and can take its own press.
  W.grip = hafen.ui():widget():parent(W.root):position(0, 0):size(w, h)
  W.root:draggable(W.grip)

  -- ...then the FIELD, and it is the panel's whole business: a background and no border. The frame cannot
  -- be the panel's own, because this window needs one with a bite out of it, so the two are separated here
  -- and the panel is the thing everything else stands on.
  W.panel = hafen.ui():widget():parent(W.root):position(0, STRIP + SEAT):size(w, ph):name("panel")
  W.panel:stock{bg = {color = FIELD}}

  W.log = hafen.ui():widget():parent(W.panel):position(PAD, PAD)
  W.log:size(math.max(1, w - (PAD * 2)), math.max(1, ph - (PAD * 2) - GAP - ENTRY_H))
  W.log:name("log")
  W.log:on("Draw", function(ev) paintLog(W, ev) end)
  W.log:on("Wheel", function(ev)
    local ch = selected(W)
    local n = ch and ch:message():count() or 0
    W.scroll = math.max(0, math.min(math.max(0, n - 1), W.scroll - ev:amount()))
    ev:preventDefault()
  end)

  W.entry = hafen.ui():entry():parent(W.panel):position(PAD, ph - PAD - ENTRY_H)
  W.entry:size(math.max(1, w - (PAD * 2)))
  W.entry:on("Submitted", function(text)
    W.entry:value("")
    if (text == nil) or (text == "") then return end
    local ch = selected(W)
    if not ch then return end
    local ok, err = pcall(function() ch:send(text) end)
    if not ok then hafen.log():write(err) end
    W.scroll = 0
  end)

  -- ...and the FRAME over it, in the three windows `cutFrame` moves. They are built after the panel, so
  -- they are drawn over it and over everything standing on it, and before the tabs, which are built last
  -- and are therefore drawn over them -- which is the whole of what makes a tab sit ON the frame. Each
  -- window is a bare box holding one full-size nine-slice, and every one of the three holds the SAME
  -- drawing: they differ only in which part of it their box lets through.
  for i = 1, 3 do
    local clip = hafen.ui():widget():parent(W.root):position(0, STRIP):size(w, fh)
    local ink = hafen.ui():widget():parent(clip):position(0, 0):size(w, fh):name("frame")
    ink:stock{border = {box = BOX}}
    W.frame[i] = {clip = clip, ink = ink}
  end
  cutFrame(W, w, w)

  -- ...and the two JOINTS, which is where the frame turns up into the selected tab. They stand at the ends
  -- of the mouth, over frame that is not drawn there, and they are built after it so nothing paints over
  -- them. One RUN square each: exactly the crossing of the panel's top run and that tab's side.
  W.jointL = hafen.ui():widget():parent(W.root):position(PAD, STRIP):size(RUN, RUN):name("jointLeft")
  W.jointL:stock{bg = {asset = JOINT_L}}
  W.jointL:visible(false)
  W.jointR = hafen.ui():widget():parent(W.root):position(PAD, STRIP):size(RUN, RUN):name("jointRight")
  W.jointR:stock{bg = {asset = JOINT_R}}
  W.jointR:visible(false)

  -- THE GRIP IS CUT INTO THE FRAME, not laid on the field. It wears no plate and no border of its own --
  -- a filled square here would be a bite taken out of the brass corner, which is the one place in the
  -- window where the client's own art is the whole of what is drawn. Three strokes across it, and the rest
  -- of the corner is the frame, exactly as the client leaves the corner of every window it lets you size.
  -- It hangs on the ROOT and is built after the frame, because a frame is drawn over its own contents: a
  -- grip parented into the panel would be painted out by the very brass it is scored into.
  W.sizer = hafen.ui():widget():parent(W.root):position(w - SIZER, h - SIZER):size(SIZER, SIZER)
  W.sizer:name("sizer")
  W.sizer:on("Draw", function(ev)
    local g = ev:g()
    g:color(GRIP[1], GRIP[2], GRIP[3], GRIP[4])
    for k = 1, 3 do                      -- three strokes across the corner: the client's own sizer reads so
      local d = 1 + ((k - 1) * 3)
      g:line(d, SIZER - 2, SIZER - 2, d, 1)
    end
    g:color()
  end)
  W.root:resizable(W.sizer)

  W.root:on("Update", function() tick(W) end)
  W.root:on("Dragged", function() remember(W) end)
  W.root:on("Resized", function()
    local b = W.root:size()
    local bw, bh = math.max(MIN_W, b.w), math.max(MIN_H, b.h)
    -- ...and no bigger than the room left of the screen, or the corner being dragged would push the far
    -- side off it -- which is the same rule as the drag's, said about the other gesture.
    local p, s = W.root:position(), (W.hud and W.hud:exists()) and W.hud:size() or nil
    if s and (s.w > 0) and (s.h > 0) then
      bw = math.min(bw, math.max(MIN_W, s.w - p.x))
      bh = math.min(bh, math.max(MIN_H, s.h - p.y))
    end
    if (bw ~= b.w) or (bh ~= b.h) then W.root:size(bw, bh) end
    remember(W)                          -- the layout follows on the next tick, through the fold above
  end)

  buildTabs(W)
  return W
end

-- A window being replaced or thrown away. Its widgets go if they are still there at all -- on a character
-- switch they went down with the HUD a moment before this runs -- and the client's chat it had put away is
-- HANDED TO THE CALLER rather than restored here. Only the caller knows whether anything is about to stand
-- in for it: a rebuild inherits it, and a teardown is the one moment the player must have it back.
local function drop(s)
  local W = wins[s]
  if not W then return nil end
  wins[s] = nil
  if W.root and W.root:exists() then W.root:destroy() end
  return W.hidden
end

-- Build one, and be sure the player is left with A chat whatever happens. If the build throws, whatever it
-- managed to put up is torn down and the client's own chat is given back -- because at that point nothing
-- of ours is standing in for it, and the refusal itself is written to a channel it would otherwise have hidden.
local function raise(s, took)
  local ok, err = pcall(build, s, took)
  if ok then return end
  hafen.log():write("simple-chat could not build its window: " .. tostring(err))
  local left = drop(s)
  local give = left or took
  if give and give:exists() then pcall(function() give:visible(true) end) end
end

-- ---------------------------------------------------------------- lifecycle

-- THE SESSION EVENTS REPORT CHANGES, NOT STATE, so an addon loaded while three characters are up hears
-- about none of the three, and a `:reload` in the middle of a game re-announces only the character ON
-- SCREEN. This sweep is what covers them: every login already in the world gets its window here, and the
-- one about to be re-announced is rebuilt when it is -- `drop` first, so the second announcement replaces
-- a window rather than standing another one on top of it.
hafen.event():on("Load", function()
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then raise(s) end
  end
end)

-- Every entry into the world is a NEW HUD -- a fresh login, or the same account picking another character,
-- which keeps the session and replaces its world -- and this window belongs to the HUD. So every entry
-- builds one, and nothing before one builds anything at all.
hafen.event():on("SessionEnteredWorld", function(s)
  raise(s, drop(s))
end)

-- The login is gone and its tree with it, so there is nothing to destroy and nothing to give back: what is
-- left is the record, and dropping it is what keeps this table from growing one entry per login for the
-- life of the client.
hafen.event():on("SessionRemoved", function(s)
  wins[s] = nil
end)

-- A channel appearing or going away is one character's, and so is the window it belongs in: these events
-- hand us the session LAST, and that is the address. A login we have no window for -- one still on its
-- character screen -- is simply not one of ours yet.
for _, key in ipairs({"ChannelAdded", "ChannelRemoved"}) do
  hafen.event():on(key, function(ch, s)
    local W = wins[s]
    if not W then return end
    W.scroll = 0
    W.sig = nil
    buildTabs(W)
  end)
end

-- The tabs do not need rebuilding when the selection moves -- only re-dressing and re-placing, since the
-- one on screen stands taller than the rest and drops to the top of the strip.
hafen.event():on("ChannelSelected", function(ch, s)
  local W = wins[s]
  if not W then return end
  W.scroll = 0
  W.sig = nil
  dressAll(W)
  pcall(placeTabs, W)
end)

-- A line landing in the channel a window is showing is already caught by the signature its log recomputes
-- each frame. What this catches is the scrollback the user has scrolled UP into: a new line below them
-- must not shift what they are reading, so the park moves with it.
hafen.event():on("MessageAdded", function(msg, s)
  local W = wins[s]
  if W and (W.scroll > 0) and (msg:channel() == selected(W)) then W.scroll = W.scroll + 1 end
end)

-- Disable fires BEFORE the teardown, which is exactly the moment every chat we put away has to come back:
-- our own windows are about to go, and a client chat left hidden behind them is one the player has nothing
-- in the interface to reopen.
hafen.event():on("Disable", function()
  for _, W in pairs(wins) do giveChatBack(W) end
  wins = {}
end)

-- The word is typed at the client, but a window is a character's -- so this is about the one on screen, and
-- on the login screen or the character list there is no window for it to be about. It says so rather than
-- doing nothing, since a console verb that answers silence is one the user cannot tell from a broken one.
hafen.console():on("simplechat", function()
  local s = hafen.session():current()
  local W = s and wins[s]
  if not (W and W.root and W.root:exists()) then
    hafen.log():write("simple-chat: no character on screen")
    return
  end
  W.root:visible(not W.root:visible())
end)
