-- widgetstack -- a WoW /framestack clone + a click-to-inspect widget inspector (spec 20, W2). The standing
-- in-game harness for the W-series widget introspection: it shows the live stack of widgets under the cursor,
-- outlines the hovered one, and lets you CLICK any level (or any child inside an inspector) to open a window
-- with that widget's full details -- a browsable widget inspector, built entirely in pure Lua over W1/W2.
--
-- W2 adds the two reads that find *what is under the cursor* -- the only pieces missing from W1's tree:
--   hafen.ui.mouse()   -> { x=, y= }   the cursor in root coords (public UI.mc), polled each frame
--   hafen.ui.at(x, y)  -> the DEEPEST WidgetNode under that point, or nil. It mirrors the engine's own
--                         pointer dispatch, so it resolves EXACTLY the widget a real click would hit --
--                         correct under SCROLL offsets and non-rectangular hit areas (a naive rect test
--                         is wrong there). Walk :parent() up from the hit for the full stack.
--   node:rootpos()     -> { x=, y= }   the node's top-left in root coords, for the highlight box
--
-- The EFFICIENCY GUARD (the point of W1's :same): OnUpdate fires EVERY frame, but the hovered widget only
-- changes when the mouse moves onto a different one. So we cache the last hovered leaf and BAIL EARLY when
-- it hasn't changed -- no tree walk, no window rebuild, per frame. A per-rebuild counter, shown in the
-- window, does NOT tick while the cursor sits still.
--
-- THE INSPECTOR: the stack rows are CLICKABLE -- click one and a new "Inspector" window opens with that
-- widget's type/id/pos/size/rootpos/visible/text + its parent link + its child list. Inside an inspector,
-- click a child (to descend) or the parent link (to ascend) to open a further inspector window. Freeze the
-- stack first (the "freeze" hotkey) so it holds still while you move the mouse into the window to click a row.
-- That hotkey starts UNBOUND: assign it under Options > Keybindings > Widgetstack (suggested: Ctrl+Shift+F).

hafen.log("widgetstack loaded (v0.2.0)")

local win                 -- the floating stack window (created at OnEnterWorld)
local overlay             -- the HUD overlay handle drawing the highlight box
local last                -- the WidgetNode we last built the stack for (the guard's memory)
local rows = {}           -- the current stack, LEAF-FIRST: { {node,type,id,text,w,h}, ... }
local hoverPos            -- { x=, y= } the hovered leaf's top-left in root coords (highlight box)
local hoverSize           -- { x=, y= } its size
local rebuilds = 0        -- how many times we rebuilt the stack (proves the :same guard: it should NOT
                          -- climb while the cursor sits still)
local frozen = false      -- the "freeze" hotkey: hold the stack still so you can mouse into the window to read it

local STACK_Y0, LINE = 22, 14   -- first stack row y + row height (shared by draw + click hit-test)

-- ============================================================================================ the inspector

local openInspector       -- forward decl (it recurses: a child/parent click opens another inspector)
local inspCascade = 0     -- cascade new inspector windows so they don't land exactly on top of each other

-- Inspector layout constants (shared by its onDraw + onClick so a click maps to the same row it drew).
local I_W, I_H       = 320, 320
local I_PARENT_Y     = 62         -- the clickable "parent" link row
local I_CHILD_Y0     = 92         -- first child row
local I_MAXROWS      = math.floor((I_H - I_CHILD_Y0) / LINE)   -- children that fit before clipping

local function fmtCoord(c) return c and ("(" .. c.x .. "," .. c.y .. ")") or "-" end
local function fmtSize(c)  return c and (c.x .. "x" .. c.y) or "-" end

openInspector = function(node)
  if not node then return end
  inspCascade = (inspCascade + 1) % 10
  local st = { node = node }              -- the target; everything else is read live off it each frame

  st.win = hafen.ui.window{
    title = "Inspector: " .. (node:type() or "?"),
    size  = { I_W, I_H },
    pos   = { 420 + inspCascade * 22, 70 + inspCascade * 22 },
    onDraw = function(g, w, h)
      g:color(0, 0, 0, 175); g:frect(0, 0, w, h); g:color()
      local n = st.node
      local id = n:id()
      -- header
      g:color(230, 230, 160)
      g:text(("%s%s"):format(n:type() or "?", id and (" #" .. id) or "  (client-only, no :id)"), 6, 4)
      g:color()
      g:text(("visible: %s    pos: %s    size: %s")
        :format(tostring(n:visible()), fmtCoord(n:pos()), fmtSize(n:size())), 6, 20)
      g:text(("rootpos: %s"):format(fmtCoord(n:rootpos())), 6, 34)
      local txt = n:text()
      g:text(("text: %s"):format(txt and ("'" .. txt .. "'") or "(none)"), 6, 48)
      -- parent link (clickable)
      local p = n:parent()
      if p then
        g:color(150, 190, 255)
        g:text(("^ parent: %s%s  (click)"):format(p:type() or "?", p:id() and (" #" .. p:id()) or ""), 6, I_PARENT_Y)
      else
        g:color(120, 120, 120)
        g:text("^ parent: (this is the root)", 6, I_PARENT_Y)
      end
      g:color()
      -- children (each clickable to descend)
      local kids = n:children()
      g:text(("children (%d)  -- click one to descend:"):format(#kids), 6, 76)
      for i = 1, math.min(#kids, I_MAXROWS) do
        local c = kids[i]
        g:color(180, 220, 180)
        g:text(("[%d] %s%s%s  %s"):format(
          i - 1, c:type() or "?",
          c:id() and (" #" .. c:id()) or "",
          c:text() and (" '" .. c:text() .. "'") or "",
          fmtSize(c:size())), 10, I_CHILD_Y0 + (i - 1) * LINE)
        g:color()
      end
      if #kids > I_MAXROWS then
        g:color(120, 120, 120)
        g:text(("... (+%d more)"):format(#kids - I_MAXROWS), 10, I_CHILD_Y0 + I_MAXROWS * LINE)
        g:color()
      end
      g:color(120, 120, 120); g:rect(0, 0, w, h); g:color()
    end,
    onClick = function(x, y, button)
      local n = st.node
      if y >= I_PARENT_Y and y < I_PARENT_Y + LINE then           -- parent link
        openInspector(n:parent())
      elseif y >= I_CHILD_Y0 then                                 -- a child row
        local idx = math.floor((y - I_CHILD_Y0) / LINE) + 1       -- 1-based
        local kids = n:children()
        if idx >= 1 and idx <= math.min(#kids, I_MAXROWS) then
          openInspector(kids[idx])
        end
      end
      return true                                                  -- consume (don't fall through)
    end,
    onClose = function() end,   -- bridge-owned: also destroyed on :reload/disable
  }
end

-- ======================================================================================= the framestack HUD

-- Rebuild the stack from `last` up to the root. Only called on a HOVER CHANGE (the guard gates it), so the
-- expensive tree walk + window relayout happen once per change, not once per frame. Each row keeps its NODE
-- so a click can open that widget's inspector.
local function rebuild()
  rebuilds = rebuilds + 1
  local out = {}
  local n = last
  while n do
    local sz = n:size()
    out[#out + 1] = {
      node = n,                      -- the WidgetNode itself (for click-to-inspect)
      type = n:type() or "?",
      id   = n:id(),                 -- server id, or nil for a client-only widget
      text = n:text(),               -- best-effort, or nil
      w    = sz and sz.x or 0,
      h    = sz and sz.y or 0,
    }
    n = n:parent()
  end
  rows = out
  -- The highlight box tracks the leaf. Suppress it when the leaf is the root widget (hovering "nothing"
  -- resolves to the full-screen root -- faithful, but a whole-screen box is just noise).
  if last and (#out > 1) then
    hoverPos, hoverSize = last:rootpos(), last:size()
  else
    hoverPos, hoverSize = nil, nil
  end
end

-- OnUpdate: the per-frame poll + the guard. This is the WoW-OnUpdate analog (the engine tick pump, 09).
hafen.events.on("OnUpdate", function(dt)
  if frozen then return end                         -- held still: keep the last stack + box
  local m = hafen.ui.mouse()
  if not m then return end                          -- no UI yet
  local leaf = hafen.ui.at(m.x, m.y)                -- deepest widget under the cursor (or nil)

  -- GUARD: same widget as last frame? -> bail (skip the rebuild entirely). This is the whole point.
  if leaf and last and leaf:same(last) then return end
  if not leaf and not last then return end          -- still hovering nothing
  last = leaf                                        -- hover CHANGED -> remember it and rebuild once
  rebuild()
end)

-- The window draws the stack text: root at the TOP, deeper widgets indented below (like /framestack).
-- Each row is CLICKABLE (see onClick) to open that widget's inspector.
local function drawStack(g, w, h)
  g:color(0, 0, 0, 160); g:frect(0, 0, w, h); g:color()            -- translucent backdrop
  g:text(("under cursor  (rebuilds: %d%s)"):format(rebuilds, frozen and ", FROZEN" or ""), 6, 4)
  if #rows == 0 then
    g:color(170, 170, 170); g:text("move the mouse over the UI", 6, STACK_Y0); g:color()
  else
    -- rows is leaf-first; iterate root-first (from the end) so indent = depth grows downward.
    local y = STACK_Y0
    for i = #rows, 1, -1 do
      local r = rows[i]
      local depth = #rows - i
      local line = ("%s%s%s%s  %dx%d"):format(
        ("  "):rep(depth),
        r.type,
        r.id and (" #" .. r.id) or "",
        r.text and (" '" .. r.text .. "'") or "",
        r.w, r.h)
      -- tint the leaf (the hovered widget) so it stands out
      if i == 1 then g:color(120, 230, 120) end
      g:text(line, 6, y)
      if i == 1 then g:color() end
      y = y + LINE
      if y > h - 6 then break end                                  -- clip overflow (deep trees)
    end
  end
  g:color(150, 150, 120)
  g:text("click a row to inspect (the freeze hotkey holds it)", 6, h - 16)
  g:color(120, 120, 120); g:rect(0, 0, w, h); g:color()            -- 1px border
end

-- Map a click in the stack window to a row -> open that widget's inspector. Displayed line k (k=0 at the
-- top = root) is at y = STACK_Y0 + k*LINE, and corresponds to rows index i = #rows - k (rows is leaf-first).
local function stackClick(x, y, button)
  if y >= STACK_Y0 and #rows > 0 then
    local k = math.floor((y - STACK_Y0) / LINE)
    local i = #rows - k
    local r = rows[i]
    if r and r.node then openInspector(r.node) end
  end
  return true                                                       -- consume
end

-- The HUD overlay draws the green highlight box over the hovered widget, in root coords (like WoW's outline).
local function drawOutline(g, w, h)
  if hoverPos and hoverSize then
    g:color(80, 230, 90); g:rect(hoverPos.x, hoverPos.y, hoverSize.x, hoverSize.y); g:color()
  end
end

hafen.events.on("OnEnterWorld", function()
  if not win then
    win = hafen.ui.window{
      title   = "Widget Stack",
      size    = { 340, 280 },
      pos     = { 60, 60 },
      onDraw  = drawStack,
      onClick = stackClick,
      onClose = function() hafen.log("widgetstack: window closed (X) -- :widgetstack to bring it back") end,
    }
    hafen.log("widgetstack: window up -- hover the UI; click a row to inspect; :widgetstack toggles it, the freeze hotkey holds it")
  end
  if not overlay then
    overlay = hafen.ui.overlay(drawOutline)
  end
end)

-- :widgetstack -- toggle the window (WoW /framestack on/off).
hafen.slash.register("widgetstack", function(args)
  if not win then hafen.log(":widgetstack -> not up yet (enter the world first)"); return end
  local show = not win:visible()
  if show then win:show() else win:hide() end
  hafen.log((":widgetstack -> window %s"):format(show and "shown" or "hidden"))
end)

-- "freeze" -- freeze/unfreeze the stack so you can move the mouse INTO the window to read + click it without
-- the stack changing under you. Declared through hafen.client:options():keybindings():register(name, fn); it
-- starts UNBOUND (D-047) -- assign it in Options > Keybindings > Widgetstack (suggested: Ctrl+Shift+F), where
-- the choice is persisted exactly like a built-in binding.
hafen.client:options():keybindings():register("freeze", function()
  frozen = not frozen
  hafen.log((":widgetstack freeze %s"):format(frozen and "ON" or "OFF"))
end)
