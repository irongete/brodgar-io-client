-- widgetstack -- a WoW /framestack clone + a click-to-inspect widget inspector + the SELECTOR INSPECTOR
-- (spec 20, W2; spec 030 B2). The standing in-game harness for the W-series widget introspection: it shows the
-- live stack of widgets under the cursor, outlines the hovered one, and lets you CLICK any level (or any child
-- inside an inspector) to open a window with that widget's full details -- a browsable widget inspector, built
-- entirely in pure Lua over W1/W2.
--
-- W2 adds the two reads that find *what is under the cursor* -- the only pieces missing from W1's tree:
--   hafen.ui():mouse()   -> the pointer entity: :x()/:y() the cursor in root coords (public UI.mc), polled
--                         each frame; also :over()/:shift()/:ctrl()/:alt()/:grab() (unused here)
--   hafen.ui():at(x, y)  -> the DEEPEST Widget object under that point, or nil. It mirrors the engine's own
--                         pointer dispatch, so it resolves EXACTLY the widget a real click would hit --
--                         correct under SCROLL offsets and non-rectangular hit areas (a naive rect test
--                         is wrong there). Walk :parent() up from the hit for the full stack.
--   w:rootPos()        -> { x=, y= }   the widget's top-left in root coords, for the highlight box
--
-- 030.3 -- THE SELECTOR INSPECTOR (the bottom panel). A selector system without one is unusable: nobody guesses
-- a widget's role. For the hovered widget the panel shows its role (or an honest nil), its class, the [title=]
-- key (the ENCLOSING window's caption -- 030.1's rule, not the widget's own text) and its [res=], and then
-- every selector built from those parts that ACTUALLY matches it, most specific first, each with how many
-- widgets it matches and this one's index among them. The bottom line is ready to paste into `:lua`.
--   * The list is SELF-VALIDATING: each candidate is resolved with hafen.ui():all() and kept only if the hovered
--     widget is in the result. So nothing is ever offered that does not resolve -- which is exactly the claim
--     the offered line makes.
--   * `hafen.ui():find("sel")` is offered when the widget is the FIRST match; otherwise the line is
--     `hafen.ui():all("sel")[i]`, because "first match" is what hafen.ui():find(sel) means and pretending otherwise
--     would hand you a selector that returns a different widget.
--   * "*" is deliberately omitted: it matches every widget, so it says nothing and it is the one walk that
--     interns the whole tree.
-- Click any panel row (or run `:selector`) to LOG the line -- chat-log text is selectable, which is how it
-- leaves the client. Freeze first (the "freeze" hotkey), or moving the mouse to the window re-hovers.
--
-- THE EFFICIENCY GUARD (029.1: plain `==`): Update fires EVERY frame, but the hovered widget only
-- changes when the mouse moves onto a different one. So we cache the last hovered leaf and BAIL EARLY when
-- it hasn't changed -- no tree walk, no selector resolution, no window rebuild, per frame. A per-rebuild
-- counter (and the number of selector walks it cost), shown in the window, does NOT tick while the cursor
-- sits still. Widget objects are INTERNED, so two lookups of the same live widget are the SAME value and
-- `==` IS the identity test -- :same() is gone with the collapse.
--
-- THE INSPECTOR: the stack rows are CLICKABLE -- click one and a new "Inspector" window opens with that
-- widget's type/id/pos/size/rootpos/visible/text + role/res + its selector + its parent link + its child
-- list. Inside an inspector, click a child (to descend) or the parent link (to ascend) to open a further
-- inspector window. Freeze the stack first (the "freeze" hotkey) so it holds still while you move the mouse
-- into the window to click a row. That hotkey starts UNBOUND: assign it under Options > Keybindings >
-- Widgetstack (suggested: Ctrl+Shift+F).

hafen.log():write("widgetstack loaded")

local win                 -- the floating stack window (created at EnterWorld)
local overlay             -- the HUD overlay handle drawing the highlight box
local last                -- the Widget object we last built the stack for (the guard's memory)
local rows = {}           -- the current stack, LEAF-FIRST: { {node,type,id,text,w,h}, ... }
local insp                -- the selector report for the hovered leaf (see selectorsFor)
local hoverPos            -- { x=, y= } the hovered leaf's top-left in root coords (highlight box)
local hoverSize           -- { x=, y= } its size
local rebuilds = 0        -- how many times we rebuilt the stack (proves the `==` guard: it should NOT
                          -- climb while the cursor sits still)
local walks = 0           -- how many hafen.ui():all() walks the last rebuild cost (the honest price of the panel)
local frozen = false      -- the "freeze" hotkey: hold the stack still so you can mouse into the window to read it

local LINE = 14                     -- row height, shared by every list here
local STACK_Y0 = 22                 -- first stack row y (shared by draw + click hit-test)
local STACK_MAXROWS = 11            -- stack rows that fit above the selector panel

-- ============================================================== the selector inspector (030.3), shared by
-- the hover panel and each Inspector window. Pure Lua over w:role()/:type()/:res() + hafen.ui():all().

local function trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end

local function ellipsis(s, n) return (#s <= n) and s or (s:sub(1, n - 2) .. "..") end

-- The caption [title=] resolves against: the nearest enclosing WINDOW's, counting the widget itself (030.1).
-- A bare widget the engine wraps in a titled window -- an Inventory inside a Hidewnd "Cupboard" -- has no
-- caption of its own, so this is what makes `inventory[title=Cupboard]` the selector it looks like.
local function windowTitle(w)
  local n = w
  while n do
    if n:role() == "window" then return n:text() end
    n = n:parent()
  end
  return nil
end

-- The ready-to-paste line for one candidate. hafen.ui():find(sel) is the FIRST match, so it is only honest when this
-- widget IS the first one; otherwise the index form is what actually hands back this widget.
local function pasteLine(c)
  if c.idx == 1 then return ('hafen.ui():find("%s")'):format(c.sel) end
  return ('hafen.ui():all("%s")[%d]'):format(c.sel, c.idx)
end

-- Build the selector report for `w`: its parts, every combination of them that really matches it (verified by
-- resolving it), sorted most-specific-first, and the best one. Costs one hafen.ui():all() walk per combination
-- (at most 15, usually 3 or 7) -- which is why it runs on a hover CHANGE, never per frame.
local function selectorsFor(w)
  local rep = { role = w:role(), cls = w:type(), res = w:res(), walks = 0, cands = {} }
  if rep.cls == "?" then rep.cls = nil end                  -- no named ancestor: nothing to write after "@"
  local title = windowTitle(w)
  if title then title = trim(title) end
  rep.title = title

  -- The writable parts, in the order the grammar wants them: role first, then the refiners. A value carrying
  -- "]" cannot be written inside [..], so it is simply not offered (better than offering a selector that
  -- would not parse).
  local parts = {}
  if rep.role then parts[#parts + 1] = { s = rep.role, wgt = 1 } end
  if rep.cls  then parts[#parts + 1] = { s = "@" .. rep.cls, wgt = 2 } end
  if title and (title ~= "") and not title:find("]", 1, true) then
    parts[#parts + 1] = { s = ("[title=%s]"):format(title), wgt = 4 }
  end
  if rep.res and not rep.res:find("]", 1, true) then
    parts[#parts + 1] = { s = ("[res=%s]"):format(rep.res), wgt = 8 }  -- the stable key (D-063): most specific
  end

  for mask = 1, (2 ^ #parts) - 1 do
    local s, score = "", 0
    for i = 1, #parts do
      if math.floor(mask / 2 ^ (i - 1)) % 2 == 1 then
        s = s .. parts[i].s
        score = score + parts[i].wgt
      end
    end
    rep.walks = rep.walks + 1
    local ok, hits = pcall(function() return hafen.ui():all(s) end)   -- a malformed candidate is dropped
    if ok and hits then
      local idx
      for i = 1, #hits do
        if hits[i] == w then idx = i; break end             -- interned entities: `==` IS the identity test
      end
      if idx then                                            -- keep ONLY selectors that demonstrably match
        rep.cands[#rep.cands + 1] = { sel = s, score = score, count = #hits, idx = idx }
      end
    end
  end
  table.sort(rep.cands, function(a, b)
    if a.score ~= b.score then return a.score > b.score end
    return #a.sel < #b.sel
  end)
  rep.offer = rep.cands[1]
  return rep
end

-- ============================================================================================ the inspector

local openInspector       -- forward decl (it recurses: a child/parent click opens another inspector)
local inspCascade = 0     -- cascade new inspector windows so they don't land exactly on top of each other

-- Inspector layout constants (shared by its Draw + MouseDown handlers so a click maps to the same row it drew).
local I_W, I_H       = 380, 344
local I_ROLE_Y       = 62         -- role / res
local I_SEL_Y        = 76         -- the widget's selector (resolved ONCE, when the window opens)
local I_PARENT_Y     = 92         -- the clickable "parent" link row
local I_CHILD_Y0     = 122        -- first child row
local I_MAXROWS      = math.floor((I_H - I_CHILD_Y0) / LINE)   -- children that fit before clipping

local function fmtCoord(c) return c and ("(" .. c.x .. "," .. c.y .. ")") or "-" end
local function fmtSize(c)  return c and (c.x .. "x" .. c.y) or "-" end

openInspector = function(node)
  if not node then return end
  inspCascade = (inspCascade + 1) % 10
  local st = { node = node, sel = selectorsFor(node) }   -- the selector is static enough to resolve once
  local st_sel = st.sel

  st.win = hafen.ui():window()
    :title("Inspector: " .. (node:type() or "?"))
    :size(I_W, I_H)
    :position(480 + inspCascade * 22, 70 + inspCascade * 22)
  -- widget:on(key, fn) hands back a SUB, not the widget, so none of these can sit mid-chain (041.3) -- each is
  -- wired separately, after the builder chain above has finished configuring the window.
  st.win:on("Draw", function(ev)
      local g, w, h = ev:g(), ev:w(), ev:h()
      g:color(0, 0, 0, 175); g:frect(0, 0, w, h); g:color()
      local n = st.node
      local id = n:id()
      -- header
      g:color(230, 230, 160)
      g:text(("%s%s"):format(n:type() or "?", id and (" #" .. id) or "  (client-only, no :id)"), 6, 4)
      g:color()
      g:text(("visible: %s    pos: %s    size: %s")
        :format(tostring(n:visible()), fmtCoord(n:position()), fmtSize(n:size())), 6, 20)
      g:text(("rootpos: %s"):format(fmtCoord(n:rootPos())), 6, 34)
      local txt = n:text()
      g:text(("text: %s"):format(txt and ("'" .. txt .. "'") or "(none)"), 6, 48)
      -- 030.3: what it IS in the selector vocabulary, and the selector that finds it again
      g:color(200, 200, 255)
      g:text(("role: %s    res: %s"):format(st_sel.role or "nil", st_sel.res or "-"), 6, I_ROLE_Y)
      g:color(150, 230, 150)
      g:text(st_sel.offer and ellipsis(pasteLine(st_sel.offer), 56) or "(no selector matches it)", 6, I_SEL_Y)
      g:color()
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
      g:text(("children (%d)  -- click one to descend:"):format(#kids), 6, I_PARENT_Y + LINE)
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
  end)
  st.win:on("Close", function() end)   -- bridge-owned: also destroyed on :reload/disable
  st.win:on("MouseDown", function(ev)
    local n = st.node
    local y = ev:y()
    if y >= I_SEL_Y and y < I_SEL_Y + LINE then                 -- the selector line: log it (copyable)
      if st_sel.offer then hafen.log():write(pasteLine(st_sel.offer)) end
    elseif y >= I_PARENT_Y and y < I_PARENT_Y + LINE then       -- parent link
      openInspector(n:parent())
    elseif y >= I_CHILD_Y0 then                                 -- a child row
      local idx = math.floor((y - I_CHILD_Y0) / LINE) + 1       -- 1-based
      local kids = n:children()
      if idx >= 1 and idx <= math.min(#kids, I_MAXROWS) then
        openInspector(kids[idx])
      end
    end
    ev:preventDefault()                                         -- consume (don't fall through)
  end)
end

-- ======================================================================================= the framestack HUD

-- Rebuild the stack from `last` up to the root, and re-resolve the selector panel. Only called on a HOVER
-- CHANGE (the guard gates it), so the expensive tree walks + window relayout happen once per change, not once
-- per frame. Each row keeps its NODE so a click can open that widget's inspector.
local function rebuild()
  rebuilds = rebuilds + 1
  local out = {}
  local n = last
  while n do
    local sz = n:size()
    out[#out + 1] = {
      node = n,                      -- the Widget object itself (for click-to-inspect)
      type = n:type() or "?",
      id   = n:id(),                 -- server id, or nil for a client-only widget
      text = n:text(),               -- best-effort, or nil
      w    = sz and sz.x or 0,
      h    = sz and sz.y or 0,
    }
    n = n:parent()
  end
  rows = out
  insp = last and selectorsFor(last) or nil     -- 030.3: the selector report for the hovered leaf
  walks = insp and insp.walks or 0
  -- The highlight box tracks the leaf. Suppress it when the leaf is the root widget (hovering "nothing"
  -- resolves to the full-screen root -- faithful, but a whole-screen box is just noise).
  if last and (#out > 1) then
    hoverPos, hoverSize = last:rootPos(), last:size()
  else
    hoverPos, hoverSize = nil, nil
  end
end

-- Update: the per-frame poll + the guard. This is the WoW-OnUpdate analog (the engine tick pump, 09).
hafen.event():on("Update", function(dt)
  if frozen then return end                         -- held still: keep the last stack + box
  local m = hafen.ui():mouse()
  local mx, my = m:x(), m:y()
  if not mx then return end                          -- no UI yet
  local leaf = hafen.ui():at(mx, my)                  -- deepest widget under the cursor (or nil)

  -- GUARD: same widget as last frame? -> bail (skip the rebuild entirely). This is the whole point, and it
  -- is what keeps the selector panel affordable: without it every frame would cost a fistful of tree walks.
  -- Interned entities (029.1), so `==` covers BOTH cases: same widget, and still hovering nothing (nil == nil).
  if leaf == last then return end
  last = leaf                                        -- hover CHANGED -> remember it and rebuild once
  rebuild()
end)

-- ---- the selector panel (the bottom half of the stack window) --------------------------------------------

local PANEL_Y0  = STACK_Y0 + STACK_MAXROWS * LINE + 8   -- 184
local P_CLASS   = PANEL_Y0
local P_TITLE   = PANEL_Y0 + LINE
local P_RES     = PANEL_Y0 + 2 * LINE
local P_HEAD    = PANEL_Y0 + 46
local SEL_Y0    = PANEL_Y0 + 62
local SEL_MAXROWS = 7                                    -- role+class+[title=] is 7 combinations: it fits whole
local SEL_COUNT_X = 320                                  -- the right-hand "n matches, #i" column

local function drawPanel(g, w, h)
  g:color(90, 90, 90); g:frect(6, PANEL_Y0 - 8, w - 12, 1); g:color()      -- divider
  if not insp then
    g:color(150, 150, 150); g:text("hover a widget to see what it IS and how to select it", 6, P_CLASS); g:color()
    return
  end
  g:color(230, 230, 160)
  g:text(("class: %s    role: %s"):format(insp.cls or "?", insp.role or "nil (nothing classifies it)"), 6, P_CLASS)
  g:color()
  g:text(("[title=] %s   (the ENCLOSING window's caption)")
    :format((insp.title and insp.title ~= "") and ("'" .. insp.title .. "'") or "-"), 6, P_TITLE)
  g:text(("[res=]   %s"):format(insp.res or "-  (most windows carry no resource)"), 6, P_RES)

  g:color(170, 170, 170)
  g:text(('selectors that match it, most specific first ("*" omitted):'), 6, P_HEAD)
  g:color()
  local n = #insp.cands
  for i = 1, math.min(n, SEL_MAXROWS) do
    local c = insp.cands[i]
    local y = SEL_Y0 + (i - 1) * LINE
    if i == 1 then g:color(150, 230, 150) end
    g:text(ellipsis(c.sel, 46), 10, y)              -- the count column starts at SEL_COUNT_X; do not run into it
    g:text(("%d match%s, #%d"):format(c.count, (c.count == 1) and "" or "es", c.idx), SEL_COUNT_X, y)
    if i == 1 then g:color() end
  end
  -- The offer FLOATS right under the list rather than sitting at a fixed y: most widgets have 3 candidates,
  -- and anchoring it to the bottom left the panel looking empty. Clicking anywhere below the rows still logs
  -- it, so nothing depends on where it lands.
  local used = math.min(n, SEL_MAXROWS)
  if n == 0 then
    g:color(200, 150, 150); g:text("(none -- it has no role, no named class and no key)", 10, SEL_Y0); g:color()
    used = 1
  elseif n > SEL_MAXROWS then
    g:color(120, 120, 120)
    g:text(("... (+%d less specific)"):format(n - SEL_MAXROWS), 10, SEL_Y0 + SEL_MAXROWS * LINE)
    g:color()
    used = used + 1
  end
  if insp.offer then
    g:color(150, 230, 150)
    g:text(pasteLine(insp.offer), 6, SEL_Y0 + used * LINE + 8)
    g:color()
  end
end

-- The window draws the stack text: root at the TOP, deeper widgets indented below (like /framestack).
-- Each row is CLICKABLE (see onClick) to open that widget's inspector.
local function drawStack(g, w, h)
  g:color(0, 0, 0, 160); g:frect(0, 0, w, h); g:color()            -- translucent backdrop
  local shown = math.min(#rows, STACK_MAXROWS)
  local above = #rows - shown                                      -- clipped at the ROOT end, never the leaf
  g:text(("under cursor  (rebuilds: %d, selector walks: %d%s%s)")
    :format(rebuilds, walks,
            (above > 0) and (", +" .. above .. " above") or "",
            frozen and ", FROZEN" or ""), 6, 4)
  if #rows == 0 then
    g:color(170, 170, 170); g:text("move the mouse over the UI", 6, STACK_Y0); g:color()
  else
    -- rows is leaf-first; draw shallowest-first (from index `shown` down to 1) so indent grows downward and
    -- the LEAF -- the widget the panel below is about -- is always the last line, never the clipped one.
    local y = STACK_Y0
    for i = shown, 1, -1 do
      local r = rows[i]
      local line = ("%s%s%s%s  %dx%d"):format(
        ("  "):rep(shown - i),
        r.type,
        r.id and (" #" .. r.id) or "",
        r.text and (" '" .. r.text .. "'") or "",
        r.w, r.h)
      -- tint the leaf (the hovered widget) so it stands out
      if i == 1 then g:color(120, 230, 120) end
      g:text(ellipsis(line, 62), 6, y)
      if i == 1 then g:color() end
      y = y + LINE
    end
  end
  drawPanel(g, w, h)
  g:color(150, 150, 120)
  g:text("click a row to inspect / a selector to log it (the freeze hotkey holds it)", 6, h - 16)
  g:color(120, 120, 120); g:rect(0, 0, w, h); g:color()            -- 1px border
end

-- Map a click in the stack window to a row -> open that widget's inspector, or to a panel row -> LOG that
-- selector (the chat log is selectable, which is how a selector leaves the client). Displayed stack line k
-- (k=0 at the top) is at y = STACK_Y0 + k*LINE and corresponds to rows index i = shown - k (rows is
-- leaf-first, and only its deepest STACK_MAXROWS entries are drawn).
local function stackClick(ev)
  local y = ev:y()
  local shown = math.min(#rows, STACK_MAXROWS)
  if y >= STACK_Y0 and y < STACK_Y0 + shown * LINE and #rows > 0 then
    local k = math.floor((y - STACK_Y0) / LINE)
    local r = rows[shown - k]
    if r and r.node then openInspector(r.node) end
  elseif y >= SEL_Y0 and insp then
    local k = math.floor((y - SEL_Y0) / LINE) + 1
    local c = insp.cands[k]
    if (k <= SEL_MAXROWS) and c then
      hafen.log():write(pasteLine(c))
    elseif insp.offer then
      hafen.log():write(pasteLine(insp.offer))
    end
  end
  ev:preventDefault()                                               -- consume
end

-- The HUD overlay draws the green highlight box over the hovered widget, in root coords (like WoW's outline).
local function drawOutline(g, w, h)
  if hoverPos and hoverSize then
    g:color(80, 230, 90); g:rect(hoverPos.x, hoverPos.y, hoverSize.x, hoverSize.y); g:color()
  end
end

hafen.event():on("EnterWorld", function()
  if not win then
    win = hafen.ui():window()
      :title("Widget Stack")
      :size(470, 412)
      :position(60, 60)
    -- widget:on(key, fn) hands back a SUB, not the widget (041.3), so none of these can sit mid-chain above.
    win:on("Draw", function(ev) drawStack(ev:g(), ev:w(), ev:h()) end)
    win:on("Close", function() hafen.log():write("widgetstack: window closed (X) -- :widgetstack to bring it back") end)
    win:on("MouseDown", stackClick)
    hafen.log():write("widgetstack: window up -- hover the UI; click a row to inspect; :selector logs the hovered widget's selector; :widgetstack toggles it, the freeze hotkey holds it")
  end
  if not overlay then
    overlay = hafen.ui():overlay():onDraw(drawOutline)
  end
end)

-- :widgetstack -- toggle the window (WoW /framestack on/off).
hafen.slash():register("widgetstack", function(args)
  if not win then hafen.log():write(":widgetstack -> not up yet (enter the world first)"); return end
  local show = not win:visible()
  if show then win:visible(true) else win:visible(false) end
  hafen.log():write((":widgetstack -> window %s"):format(show and "shown" or "hidden"))
end)

-- :selector -- log the hovered widget's full selector report. The window shows it too, but a logged line is
-- SELECTABLE, which is how the string actually gets out of the client and into your addon.
hafen.slash():register("selector", function(args)
  if not insp then hafen.log():write(":selector -> nothing hovered yet (move the mouse over the UI)"); return end
  hafen.log():write((":selector -> class=%s role=%s title=%s res=%s")
    :format(insp.cls or "?", insp.role or "nil",
            (insp.title and insp.title ~= "") and ("'" .. insp.title .. "'") or "-", insp.res or "-"))
  for i = 1, #insp.cands do
    local c = insp.cands[i]
    hafen.log():write(("  %s%s   (%d match%s, this one is #%d)")
      :format((i == 1) and "* " or "  ", c.sel, c.count, (c.count == 1) and "" or "es", c.idx))
  end
  if insp.offer then hafen.log():write(pasteLine(insp.offer)) end
end)

-- "freeze" -- freeze/unfreeze the stack so you can move the mouse INTO the window to read + click it without
-- the stack changing under you. Declared through hafen.client():options():keybindings():register(name, fn); it
-- starts UNBOUND (D-047) -- assign it in Options > Keybindings > Widgetstack (suggested: Ctrl+Shift+F), where
-- the choice is persisted exactly like a built-in binding.
hafen.client():options():keybindings():register("freeze", function()
  frozen = not frozen
  hafen.log():write((":widgetstack freeze %s"):format(frozen and "ON" or "OFF"))
end)
