-- Paint -- draw on the ground with the mouse, in colour, and rub it out again.
--
-- A STROKE IS ONE PATCH OF MANY PIECES. `hafen.virtual():patch()` lies flat on the terrain -- it follows a
-- slope, a ridge and a tile boundary exactly, and whatever stands on that ground hides it -- and a piece is
-- one convex ring of it (docs/addons/api/virtual/pieces.md). A freehand stroke is not convex, so it is laid
-- as a rectangle per sampled segment plus a round join at the corners, all of them pieces of ONE patch: one
-- handle, one colour, one shape. Laying a piece per segment as its own PATCH would work and would be the
-- wrong unit -- every patch is an overlay the client walks, masks and tests every frame for as long as it
-- is down, and two overlays over one another blend where they meet, which a union does not.
--
-- THE SILHOUETTE OF THE UNION IS THE STROKE'S OWN OUTLINE, not an approximation of it -- so the pieces have
-- to be the RIGHT pieces. A rectangle extended by the half-width at each end is a SQUARE CAP, and a square
-- cap on every segment makes a curve read as a row of rectangles: on the outside of a turn each cap's corner
-- stands out past its neighbour, and between them a wedge stays open. So a segment carries no cap at all --
-- `WELD` proud and no more, which only keeps two pieces off each other's boundary -- and the corner is a
-- ROUND JOIN laid where the stroke actually turned. Along a straight run the outline is the segment's own
-- side; round a corner it is the join's arc. There is nothing else it could be.
--
-- THE SAMPLE IS DECIMATED, and that is the whole of why this does not stall. A piece laid or taken up
-- re-cuts the ground the shape covers, which is real terrain work -- so a piece per FRAME would pay it sixty
-- times a second for a stroke nobody can see the difference in. Nothing is laid until the pointer has
-- travelled `step()`, which follows the stroke's own width: a fat brush samples coarsely because it cannot
-- show anything finer, and the piece count follows the stroke's length rather than the clock.
--
-- ONE RAYCAST IN FLIGHT. `screenToWorld` reads the true terrain point off the GPU and answers a frame later,
-- and the step comes round every frame, so the next one is issued only after the last has landed
-- (docs/addons/api/world.md). At worst that is one sample per frame, which is far more than `step()` keeps.
--
-- A DRAWING IS ALWAYS SEVERAL PATCHES, and no arrangement of this addon can make it one. A patch carries
-- 128 edges across every piece it holds -- the length its half-planes are declared at in the fragment stage
-- -- which is about thirty segments, so a stroke of any size spills into a second patch and a third. The
-- budget is therefore counted in EDGES rather than in pieces: four to a segment, JOIN_N to a join, so how
-- many pieces fit depends on how much the stroke turned.
--
-- WHICH IS WHY THE PAINT IS OPAQUE. Within one patch the pieces are a union and a fragment is drawn once,
-- however many of them cover it; ACROSS two patches there is no union -- they are two overlays, and a
-- see-through fill blends with itself where they meet, so the seam shows as a brighter quadrilateral. At
-- full alpha that overlap is the same colour as either side of it and the boundary cannot be seen. This is
-- the one place the shape's own limit reaches the look, and the alpha is what pays it.
--
-- THE ERASER ASKS THE MARK WHERE IT IS, rather than remembering a coordinate. A world coordinate is the
-- answer of whichever character is looking, and stepping into a cave or a house makes the server re-base the
-- lot -- so a cached pair goes silently wrong exactly where the drawing does not. Each mark holds the
-- Position of its own midpoint instead, and `p:x()`/`p:y()` re-resolve it against whoever is on screen; a
-- character who cannot locate that ground reads `nil` there and the mark is skipped rather than rubbed out
-- from somewhere else.
--
-- NOTHING HERE REACHES THE SERVER. A patch has no server id, is never sent and grants nothing: this draws on
-- your own screen. It is also bridge-owned, so a `:reload`, disabling the addon and logging out each take
-- every stroke up by themselves -- there is nothing saved and nothing to clean.
--
-- A TOOL IS ARMED OR IT IS NOT. While one is picked, a left press on the map is the stroke's and the
-- character does not walk; with none picked the map is the client's, untouched. Clicking the picked tool
-- again puts it back, and so does closing the window -- an addon that quietly ate every map click would be
-- reported as a broken client.

local NAME = "Paint"

-- The window, in design pixels.
local PAD    = 6          -- content edge to everything in it
local GAP    = 5          -- between two things stacked
local CELL   = 24         -- one tool cell in the strip
local CGAP   = 3          -- between two cells
local LBL_W  = 40         -- the "Width" caption
local VAL_W  = 32         -- the number the slider is showing

-- The ground, in world units (a tile is 11).
local BUDGET = 128        -- edges to a patch, across every piece of it: the shape's own limit
local JOIN_N = 8          -- sides of a round join; with the segment's four that is 12 edges to a sample
local JOIN_R = 1.09       -- the join's radius, times the half-width -- see `joint`
local JOIN_COS = 0.985    -- no join where the stroke turned less than about ten degrees: there is no wedge
local WELD   = 0.05       -- the hair two pieces overlap by, so neither sits on the other's boundary
local MIN_W  = 2          -- the slider's range, in TENTHS of a world unit
local MAX_W  = 40
local FLOOR  = 0.3        -- the finest the sample is ever decimated to
local STEP_OF_WIDTH = 0.25  -- ...and, above that floor, the share of the width one segment spans
local RUB_FLOOR = 0.15    -- the smallest the rubber goes, however far down the slider is

-- OPAQUE, and that is not a taste. A drawing is always more than one patch -- 128 edges is a patch's whole
-- budget -- and where one patch overlaps the next, a see-through fill blends with itself and the seam shows
-- as a brighter quadrilateral. At full alpha the overlap is the same colour as either side of it, so however
-- many patches a stroke really is, it reads as one shape. Translucent paint cannot: the seam IS the alpha.
local PENCILS = {
  {key = "red",    color = {222,  62,  52, 255}},
  {key = "orange", color = {235, 140,  40, 255}},
  {key = "yellow", color = {242, 212,  64, 255}},
  {key = "green",  color = { 64, 202,  96, 255}},
  {key = "blue",   color = { 62, 142, 236, 255}},
  {key = "white",  color = {244, 244, 244, 255}},
}
local ERASER = #PENCILS + 1           -- the last cell of the strip
local TOOLS  = ERASER

local STRIP_W = (CELL * TOOLS) + (CGAP * (TOOLS - 1))
local W       = STRIP_W + (PAD * 2)
local SLIDE_W = W - (PAD * 2) - LBL_W - VAL_W - (GAP * 2)   -- the height is the slider's own, read back

-- What is on the ground. `patches` is every patch this addon has laid, in the order it laid them; `marks`
-- is every quad still down, each holding the patch record it belongs to so the eraser can take it up.
local sheet = {patches = {}, marks = {}}

local tool = nil          -- 1..#PENCILS, ERASER, or nil for "the map is the client's"
local w10  = 8            -- the slider's value: tenths of a world unit
local live = nil          -- the stroke being drawn, while one is
local open = false        -- ours, not `win:visible()`: a window is visible the instant it is BUILT, so the
                          --   first :paint would read true and close a window nobody had opened
local win, strip, value, capt   -- the window and the surfaces that redraw
local refresh             -- declared here because `pick` calls it and is written above it
local mapsub = {}         -- our MouseDown subscription, per account

local function half() return (w10 / 10) / 2 end

-- THE SLIDER IS THE RUBBER'S SIZE TOO. It reads "Width" under a pencil and "Rub" under the eraser, and it
-- is the same number either way: half the slider, so a rubber matches the stroke a pencil at that setting
-- would lay. Wind it down and the eraser takes a little at a time -- which, since the finest thing it can
-- take is one whole piece, is as fine as this gets.
local function rubR() return math.max(RUB_FLOOR, half()) end

-- How far the rubber travels between two sweeps of the marks. A small rubber has to sweep finely or it
-- steps straight over pieces it was dragged across.
local function rubStep() return math.max(RUB_FLOOR, rubR() * 0.5) end

-- How far the pointer travels before another segment is laid. It follows the WIDTH -- a brush cannot show
-- detail finer than itself -- but at a FRACTION of it, because a segment as long as the stroke is wide is a
-- rectangle, and a curve built out of those is a polygon with corners you can count. At a third of the
-- width a chord departs from its own arc by well under a pixel at any zoom worth drawing at.
local function step() return math.max(FLOOR, (w10 / 10) * STEP_OF_WIDTH) end

-- LuaJ's string.format ignores a precision, so a tenth is written by hand.
local function tenths(v) return math.floor(v / 10) .. "." .. (v % 10) end

local function drawn()
  local s = hafen.session():current()
  return s and s:world() and s or nil
end

---------------------------------------------------------------------------- the ground

-- ONE SEGMENT, WITH NO CAP. The rectangle from `a` to `b`, `WELD` proud at each end and not a hair more.
-- The half-width extension this used to carry is a SQUARE CAP, and a square cap on every segment is what
-- makes a curve read as a row of rectangles: on the outside of a turn each cap's corner stands out past its
-- neighbour. `nil` for a segment with no length, which has no direction to be square to.
local function segment(a, ax, ay, b, bx, by)
  local dx, dy = bx - ax, by - ay
  local len = math.sqrt((dx * dx) + (dy * dy))
  if len < 1e-6 then return nil end
  local h = half()
  dx, dy = dx / len, dy / len
  local ex, ey = dx * WELD, dy * WELD              -- along it: only enough to overlap what it meets
  local ox, oy = -dy * h, dx * h                   -- across it: the width
  return {
    a:offset(-ex + ox, -ey + oy),
    b:offset( ex + ox,  ey + oy),
    b:offset( ex - ox,  ey - oy),
    a:offset(-ex - ox, -ey - oy),
  }, len, dx, dy
end

-- THE ROUND JOIN, which is what the caps were standing in for and doing badly. Two segments that meet at an
-- angle leave a wedge open on the OUTSIDE of the turn; this fills it, and its arc is what the silhouette
-- follows there. The same ring caps the two ends of a stroke.
--
-- Its radius is the half-width taken out by JOIN_R, because a polygon's FLAT sits nearer the centre than its
-- corners do: at JOIN_N sides a ring of radius h would cut inside the very segments it is joining, and the
-- join would show as a notch instead of hiding one.
local function joint(at)
  local r = half() * JOIN_R
  local ring = {}
  for k = 1, JOIN_N do
    local a = (2 * math.pi * (k - 1)) / JOIN_N
    ring[k] = at:offset(math.cos(a) * r, math.sin(a) * r)
  end
  return ring, r
end

-- Put one convex ring into the stroke's shape, starting a patch where the ring would not fit in the one it
-- has. THE BUDGET IS COUNTED IN EDGES, not in rings: a segment is four and a join is JOIN_N, so a patch
-- holds a different number of each. A new patch is anchored at the ring's own place rather than at the
-- stroke's first point -- a ring is held as offsets from its anchor, and a stroke long enough to fill a
-- patch is long enough to have left that grid behind.
-- A mark records the piece as the CAPSULE it really is -- a midpoint, a half-vector along it, and a radius
-- across -- rather than as the circle that would enclose it. A bounding circle round a segment four units
-- wide and one long reaches two and a half units from its centre in every direction, so the eraser took
-- pieces it never touched. The half-vector is a displacement, and a displacement survives the server
-- re-basing the frame; only the midpoint is a place, and it is asked where it is at every sweep.
local function put(ring, edges, at, hx, hy, rad)
  local rec, piece = live.rec, nil
  if (rec == nil) or (not rec.patch:exists()) or ((rec.edges + edges) > BUDGET) then
    local ok, p = pcall(function()
      return hafen.virtual():patch():add(ring, at):tint(PENCILS[live.tool].color)
    end)
    if not ok then
      hafen.log():write(NAME .. ": " .. tostring(p))
      live.rec = nil
      return
    end
    rec = {patch = p, edges = edges, alive = 1}
    live.rec = rec
    sheet.patches[#sheet.patches + 1] = rec
    piece = p:piece():list()[1]                    -- the ring :add was given is the patch's first piece
  else
    local ok, p = pcall(function() return rec.patch:piece():add(ring) end)
    if not ok then
      hafen.log():write(NAME .. ": " .. tostring(p))
      return
    end
    rec.edges = rec.edges + edges
    rec.alive = rec.alive + 1
    piece = p                                      -- :add hands the piece back
  end
  sheet.marks[#sheet.marks + 1] = {rec = rec, piece = piece, at = at, hx = hx, hy = hy, rad = rad}
end

-- Take one mark up, and the patch with it once it holds nothing.
local function drop(i)
  local m = sheet.marks[i]
  sheet.marks[i] = sheet.marks[#sheet.marks]
  sheet.marks[#sheet.marks] = nil
  local rec = m.rec
  if rec.patch:exists() then
    pcall(function() rec.patch:piece():remove(m.piece) end)
    rec.alive = rec.alive - 1
    if rec.alive <= 0 then
      pcall(function() hafen.virtual():patch():remove(rec.patch) end)
    end
  end
end

-- How far the point (px, py) is from the capsule's own AXIS: the segment centred at (cx, cy) reaching
-- (hx, hy) each way. A join has no axis and falls out of the same expression as a plain distance.
local function axisGap(px, py, cx, cy, hx, hy)
  local len2 = (hx * hx) + (hy * hy)
  local qx, qy = cx, cy
  if len2 > 1e-12 then
    local t = (((px - cx) * hx) + ((py - cy) * hy)) / len2
    if t > 1 then t = 1 elseif t < -1 then t = -1 end
    qx, qy = cx + (hx * t), cy + (hy * t)
  end
  local dx, dy = px - qx, py - qy
  return math.sqrt((dx * dx) + (dy * dy))
end

-- Everything the rubber covers at this point. The mark is asked where it is rather than told: see the head.
--
-- IT REMOVES PIECES; IT DOES NOT BITE INTO ONE. A piece is a convex ring and the shape is their union, so
-- there is no subtracting: the finest thing this can take is one whole piece. Two things therefore decide
-- how fine it feels, and both are here -- how big the pieces are (`step`, and the width they span) and how
-- honestly the reach is measured, which is the capsule below rather than a circle round it.
local function rub(x, y)
  local reach = rubR()
  local i = 1
  while i <= #sheet.marks do
    local m = sheet.marks[i]
    local mx, my = m.at:x(), m.at:y()
    if (mx == nil) or (my == nil) then
      i = i + 1                                     -- this character cannot locate that ground
    elseif axisGap(x, y, mx, my, m.hx, m.hy) <= (reach + m.rad) then
      drop(i)
    else
      i = i + 1
    end
  end
end

local function clear()
  for _, rec in ipairs(sheet.patches) do
    if rec.patch:exists() then pcall(function() hafen.virtual():patch():remove(rec.patch) end) end
  end
  sheet.patches, sheet.marks = {}, {}
end

---------------------------------------------------------------------------- the stroke

-- Put a round join down at a sample point -- the start cap, a corner, or the end cap.
local function putJoint(at)
  local ring, r = joint(at)
  put(ring, JOIN_N, at, 0, 0, r)                   -- a join has no axis: its capsule is a plain circle
end

-- One landed raycast: lay what the pointer has travelled since the last one, or rub it out.
--
-- A SEGMENT AND, WHERE THE STROKE TURNED, A JOIN. The segment is a plain rectangle with no cap of its own,
-- so two of them meeting at an angle leave a wedge open on the outside of the turn; the join fills it, and
-- the silhouette follows its arc round the corner. A join is skipped where the turn is under JOIN_COS,
-- because there is no wedge to fill there and a ring laid anyway would only bead the stroke.
local function sample(p)
  if (live == nil) or (p == nil) then return end
  local x, y = p:x(), p:y()
  if (x == nil) or (y == nil) then return end
  if live.last == nil then
    live.last, live.lx, live.ly = p, x, y
    if live.tool == ERASER then rub(x, y) end
    return
  end
  local dx, dy = x - live.lx, y - live.ly
  local moved = math.sqrt((dx * dx) + (dy * dy))
  if live.tool == ERASER then
    if moved < rubStep() then return end
    rub(x, y)
    live.last, live.lx, live.ly = p, x, y
    return
  end
  if moved < step() then return end
  local ring, len, ux, uy = segment(live.last, live.lx, live.ly, p, x, y)
  if ring == nil then return end

  -- The corner this segment leaves behind: the first one is the stroke's own round cap, and every one after
  -- it is a turn, laid only where the stroke actually turned.
  if live.ux == nil then
    putJoint(live.last)
  elseif ((live.ux * ux) + (live.uy * uy)) < JOIN_COS then
    putJoint(live.last)
  end
  put(ring, 4, live.last:offset(dx / 2, dy / 2), dx / 2, dy / 2, half())

  live.last, live.lx, live.ly = p, x, y
  live.ux, live.uy = ux, uy
end

local function endStroke()
  if live == nil then return end
  -- The far end's own round cap. `ux` is set by the first segment, so a press that never moved lays
  -- nothing at all rather than a lone dot the user did not draw.
  if (live.tool ~= ERASER) and (live.ux ~= nil) and (live.last ~= nil) then
    pcall(function() putJoint(live.last) end)
  end
  local sub = live.sub
  live = nil
  if sub then pcall(function() sub:off() end) end
end

-- A stroke lasts exactly as long as the button is held, and NOT through a mouse grab.
--
-- A grab is the obvious mechanism and it cannot work from here. The client records which tree took a
-- button's press (`Client.btnowner`) and offers the release to that tree ALONE; our press is the MapView's,
-- which is the session's tree, while `hafen.ui():mouse():grab()` arms on the addon LAYER. So the layer is
-- never offered the up, the grab's `Up` never fires and the grab is never released -- while its `Move` goes
-- on being broadcast every frame, which is a pencil that paints for ever.
--
-- So the stroke is driven from the step instead: the pointer is read where it is, the release is the
-- MapView's own `MouseUp` -- the session's tree DOES get that one -- and the pointer leaving the map ends
-- the stroke too, which is both the safety net and the right behaviour. The subscription is held only while
-- a stroke is, so an idle Paint costs nothing per frame.
local function beginStroke()
  local s = drawn()
  if (s == nil) or (tool == nil) then return end
  endStroke()
  local m = hafen.ui():mouse()
  live = {tool = tool, pending = false, view = s:ui():match("@MapView")}

  -- One raycast in flight, and it belongs to the stroke that issued it: the step comes round every frame
  -- while an answer takes one, and a stroke that ended meanwhile must not be sampled into the next.
  local function shoot(px, py)
    local st = live
    if (st == nil) or st.pending then return end
    st.pending = true
    s:world():screenToWorld({x = px, y = py}, function(p)
      st.pending = false
      if live == st then sample(p) end
    end)
  end

  -- Is the pointer still on the map? The deepest widget under it can be a CHILD of the map view rather
  -- than the view itself, so this walks up rather than comparing: a bare `over ~= view` would end a stroke
  -- the moment it crossed anything the client parents into the world.
  local function onMap(over)
    local n, hops = over, 0
    while (n ~= nil) and (hops < 16) do
      if n == live.view then return true end
      n = n:parent()
      hops = hops + 1
    end
    return false
  end

  live.sub = hafen.event():on("Update", function()
    if live == nil then return end
    local over = m:over()
    if (over ~= nil) and not onMap(over) then          -- on a window now: the stroke is over
      endStroke()
      return
    end
    shoot(m:x(), m:y())
  end)
  shoot(m:x(), m:y())
end

---------------------------------------------------------------------------- the window

local function armCursor()
  local m = hafen.ui():mouse()
  if tool == nil then m:cursor(nil)
  elseif tool == ERASER then m:cursor("wrench")
  else m:cursor("dig") end
end

local function pick(i)
  if tool == i then tool = nil else tool = i end
  if tool == nil then endStroke() end
  armCursor()
  refresh()                                        -- the slider's caption says which of the two it is now
end

local function cellAt(x, y)
  if (y < 0) or (y >= CELL) then return nil end
  for i = 1, TOOLS do
    local cx = (i - 1) * (CELL + CGAP)
    if (x >= cx) and (x < (cx + CELL)) then return i end
  end
  return nil
end

local function paintStrip(g)
  for i = 1, TOOLS do
    local x = (i - 1) * (CELL + CGAP)
    if i == ERASER then
      g:color(206, 206, 210, 255)
      g:frect(x, 0, CELL, CELL)
      g:color(90, 90, 96, 255)                       -- the rubber's slash
      g:line(x + 5, CELL - 6, x + CELL - 6, 5, 2)
    else
      local c = PENCILS[i].color
      g:color(c[1], c[2], c[3], 255)
      g:frect(x, 0, CELL, CELL)
    end
    g:color(24, 24, 26, 255)
    g:rect(x, 0, CELL, CELL)
    if tool == i then
      g:color(255, 255, 255, 255)                    -- inside the cell: a widget clips what leaves its box
      g:rect(x + 1, 1, CELL - 2, CELL - 2)
      g:rect(x + 2, 2, CELL - 4, CELL - 4)
    end
  end
  g:color()
end

function refresh()
  if value then value:text(tenths(w10)) end
  if capt then capt:text((tool == ERASER) and "Rub" or "Width") end
end

-- A control whose own ART fixes its height is given a WIDTH ALONE and then ASKED how tall it came out.
-- `:size(w, h)` under that art raises -- a Button is 24 design px and a box a pixel short of it loses the
-- border -- and there is no number that means the same thing at every interface scale, so the layout reads
-- the heights back rather than writing them. Getting this wrong is silent from here: the raise kills the
-- rest of the chain, so the control keeps the caption it had never been given.
local function build()
  if win then return end
  win = hafen.ui():window():title(NAME)

  strip = hafen.ui():widget():parent(win):position(PAD, PAD):size(STRIP_W, CELL)
  strip:name("tools")                                -- a bare surface HAS no art: it takes both numbers
  strip:on("Draw", function(ev) paintStrip(ev:g()) end)
  strip:on("MouseDown", function(ev)
    local i = cellAt(ev:x(), ev:y())
    if i then pick(i) end
    ev:preventDefault()
  end)

  local row = PAD + CELL + GAP
  local slider = hafen.ui():slider():parent(win):size(SLIDE_W):range(MIN_W, MAX_W):value(w10)
  local sh = slider:size().h
  slider:position(PAD + LBL_W + GAP, row)
  slider:on("Changed", function(ev)
    w10 = ev:value()
    refresh()
  end)

  capt = hafen.ui():label():parent(win):text("Width")
  capt:position(PAD, row + math.floor((sh - capt:size().h) / 2))
  value = hafen.ui():label():parent(win):text(tenths(w10))
  value:position(PAD + LBL_W + GAP + SLIDE_W + GAP, row + math.floor((sh - value:size().h) / 2))

  row = row + sh + GAP
  local wipe = hafen.ui():button():parent(win):size(64):text("Clear")
  wipe:position(PAD, row)
  wipe:on("Pressed", function()
    hafen.timer():after(0, clear)                    -- the press holds a tree; the ground is not in it
  end)
  win:size(W, row + wipe:size().h + PAD)             -- the content box, once its tallest row has answered

  win:on("Close", function()
    win:visible(false)
    open = false
    tool = nil
    endStroke()
    armCursor()
  end)
end

local function show(on)
  build()
  win:visible(on)
  open = on
  if not on then
    tool = nil
    endStroke()
    armCursor()
  end
end

---------------------------------------------------------------------------- wiring

-- The two edges of the gesture, both the map's own. The press is cancelled, so the MapView never starts the
-- click that walks the character or the drag that pans the camera; the release ends the stroke and is NOT
-- cancelled, since by then there is nothing of the client's left to stop.
local function watch(s)
  local old = mapsub[s:user()]
  if old then for _, sub in ipairs(old) do pcall(function() sub:off() end) end end
  local mv = s:ui():match("@MapView")
  if mv == nil then return end

  local function ours(ev)
    if (tool == nil) or (ev:button() ~= 1) then return false end
    local cur = hafen.session():current()
    return (cur ~= nil) and (cur:user() == s:user())
  end

  mapsub[s:user()] = {
    mv:on("MouseDown", function(ev)
      if not ours(ev) then return end
      ev:preventDefault()
      beginStroke()
    end),
    mv:on("MouseUp", function(ev)
      if (live == nil) or (ev:button() ~= 1) then return end
      endStroke()
    end),
  }
end

hafen.event():on("SessionEnteredWorld", function(s) watch(s) end)
for _, s in ipairs(hafen.session():list()) do watch(s) end

hafen.console():on("paint", function(args)
  local what = args[1]
  hafen.timer():after(0, function()
    if what == "clear" then
      clear()
      hafen.log():write(NAME .. ": cleared")
    else
      show(not open)
    end
  end)
end)
