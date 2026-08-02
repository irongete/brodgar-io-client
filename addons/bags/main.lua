-- Bags (Phase 3c): the WIDGET-REPLACEMENT example — hafen.ui.replace. This is the high-level sugar over 3a
-- (observe the server's UI) + 3b (adopt a widget as a hidden model): it finds the native inventory by descriptor,
-- adopts the real widget as a hidden MODEL, and hands us a chance to draw our own VIEW over it — "wrap, don't
-- reimplement" (D-009). The real inventory stays server-bound (so model:items() keeps reading its live items),
-- just hidden; disabling/reloading the addon — or toggling off — restores the stock window (the Phase-3 DoD).
--
-- It is DORMANT until you press its hotkey, so it never disturbs a normal login / the `hello` regression harness.
-- The hotkey starts UNBOUND: assign "toggle" under Options > Keybindings > Bags (suggested: Ctrl+Shift+I). Then:
--   * Press it            -> REPLACE: the native inventory hides, a custom "Bags" window draws your real items.
--   * Press it again      -> RESTORE: the native inventory comes back, the custom window is destroyed.
--   * Or disable/`:reload` while replaced -> the native inventory is restored on teardown (no leak).
-- Because replace SCANS for an already-open target at registration, pressing the key in-world finds your open
-- inventory immediately (the :reload case the 3a->3b observer path could not re-catch).
--
-- Item MOVING (take/transfer/drop) is an outbound gameplay action -> the gated Phase-4 actions tier (hafen.act),
-- so this view is READ-ONLY: it draws the real items and logs the one you click. `hafen` is the API facade.

hafen.log("bags loaded (v0.1.0) -- assign the 'toggle' hotkey in Options > Keybindings > Bags, then press it in-world")

local CELL = 34             -- px per inventory cell in the custom view
local replaceHandle         -- non-nil while we are replacing (nil = native inventory showing)
local model                 -- the adopted inventory model (set by the builder below; nil when not replacing)
local hover                 -- {x=,y=} grid cell under the mouse, for a highlight (or nil)

-- Stop replacing: handle:remove() restores the native inventory AND destroys our view (idempotent). Shared by the
-- hotkey toggle-off and the window's own X (onClose) so both paths converge on a clean restore.
local function stopReplace()
  if replaceHandle then
    replaceHandle:remove()
    replaceHandle, model = nil, nil
  end
end

-- The VIEW builder. hafen.ui.replace calls this with the WIDGET OBJECT for the real, now-hidden inventory (029.3:
-- the one entity every hafen.ui door hands back — the bespoke "model handle" is gone, so every widget verb answers
-- on it). We draw a custom window over m:items() (Item snapshots: {res,name,num,wear,pos,handle}) and
-- RETURN the window handle. replace owns it: it is destroyed automatically when we toggle off, when the addon is
-- reloaded/disabled, or if the server ever destroys the inventory. No manual teardown needed here.
local function buildBagsView(m)
  model = m
  -- Size the window to the items present now (a click-through demo; items streaming in later just clip harmlessly).
  local cols, rows = 4, 3
  for _, it in ipairs(m:items()) do
    local p = it.pos
    if p then cols = math.max(cols, p.x + 1); rows = math.max(rows, p.y + 1) end
  end

  return hafen.ui.window{
    title = "Bags (custom)",
    size  = { cols * CELL + 8, rows * CELL + 24 },
    pos   = { 150, 130 },
    onDraw = function(g, w, h)
      g:color(0, 0, 0, 175); g:frect(0, 0, w, h); g:color()             -- translucent backdrop
      local items = m:items()                                           -- LIVE read off the hidden inventory's items
      for _, it in ipairs(items) do
        local p = it.pos or { x = 0, y = 0 }
        local cx, cy = 4 + p.x * CELL, 4 + p.y * CELL
        g:color(46, 56, 74); g:frect(cx, cy, CELL - 2, CELL - 2); g:color()        -- cell body
        g:color(96, 116, 150); g:rect(cx, cy, CELL - 2, CELL - 2); g:color()        -- cell border
        -- No item icons yet (g:image is deferred), so show a short name + a stack count.
        local label = tostring(it.name or it.res or "?"):gsub("^.*/", "")
        g:text(label:sub(1, 5), cx + 2, cy + 2)
        if it.num and it.num > 1 then g:atext(tostring(it.num), cx + CELL - 4, cy + CELL - 13, 1.0, 0.0) end
      end
      if hover then                                                     -- hover highlight
        g:color(255, 225, 120)
        g:rect(3 + hover.x * CELL, 3 + hover.y * CELL, CELL, CELL)
        g:color()
      end
      g:color(180, 200, 160)
      g:text(("%d item(s) -- native inventory hidden (toggle key restores)"):format(#items), 4, h - 15)
      g:color()
      g:color(150, 150, 150); g:rect(0, 0, w, h); g:color()             -- outer border
    end,
    onMouseMove = function(x, y)
      hover = { x = math.floor((x - 4) / CELL), y = math.floor((y - 4) / CELL) }
    end,
    onClick = function(x, y, button)
      local cx, cy = math.floor((x - 4) / CELL), math.floor((y - 4) / CELL)
      for _, it in ipairs(m:items()) do
        local p = it.pos
        if p and p.x == cx and p.y == cy then
          hafen.log(("bags: clicked %s x%s @cell %d,%d -- moving items is the gated Phase-4 tier (read-only here)")
            :format(tostring(it.name or it.res), tostring(it.num or 1), cx, cy))
          return true
        end
      end
      hafen.log(("bags: clicked empty cell %d,%d"):format(cx, cy))
      return true                                                       -- truthy = consume
    end,
    onClose = function()
      hafen.log("bags: view closed (X) -- native inventory restored; press the toggle key to replace again")
      stopReplace()                                                   -- X also restores the native inventory
    end,
  }
end

-- The toggle hotkey. hafen.ui.replace("inv", {context="main"}, fn) targets the MAIN inventory (GameUI.maininv, the
-- unambiguous public reference) and, at registration, SCANS the live tree for it — so pressing this while your
-- inventory is open replaces it right away. handle:remove() stops replacing AND restores the native inventory
-- (destroying our view). The hotkey is declared through hafen.client:options():keybindings():register(name, fn)
-- and starts UNBOUND (D-047): a "Bags" section appears in the keybind panel (2e-3) where YOU assign the key —
-- Ctrl+Shift+I is merely the suggestion. If nothing gets replaced, the inventory just is not open yet.
local keys = hafen.client:options():keybindings()
keys:register("toggle", function()
  if replaceHandle then
    stopReplace()
    hafen.log("bags: RESTORED the native inventory")
  else
    model = nil
    replaceHandle = hafen.ui.replace("inv", { context = "main" }, buildBagsView)
    if model then
      hafen.log("bags: REPLACED the native inventory with the custom view (drag it; click an item to log it)")
    else
      hafen.log("bags: no open inventory to replace yet -- open it (Tab) then press the toggle key again")
    end
  end
end)
-- keybindings:get(name) resolves THIS addon's binding first (addon/bags/toggle), so the log always reports the
-- key the user actually assigned -- nil until they do.
hafen.log(("bags: toggle hotkey = %s (assign it under Options > Keybindings > Bags; suggested Ctrl+Shift+I)")
  :format(keys:get("toggle") or "unassigned"))

hafen.events.on("OnDisable", function()
  hafen.log("bags: OnDisable -- native inventory restored + custom view destroyed on teardown")
end)
