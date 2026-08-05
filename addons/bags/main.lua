-- Bags (032): the WIDGET-REPLACEMENT example — hafen.ui():on(selector, "appear") + widget:replace(view). Two ordinary
-- pieces of API, no third one in between: `on` WAITS for the part of the UI you name with a selector (and fires for
-- what is ALREADY open, D-068), and `replace` is a VERB ON THE WIDGET that puts your own window in place of the
-- client's. hafen.ui.replace(type, opts, fn) is GONE (032.2, hard cut): it carried a second vocabulary for "which
-- window" — the server descriptor {id,type,place,caption,parentType} — and it was the only thing that could bind a
-- view to a hidden native window, which made every by-hand replacement strictly weaker.
--
-- THE TRAP THE VERB OWNS. It hides the ENCLOSING WINDOW, not the widget you point at: we replace the inventory GRID
-- and the whole stock window goes, rather than leaving its frame around a hole. (widget:visible(false) still hides exactly
-- what you point at — that is the difference between the two.) The real inventory stays server-bound while hidden,
-- so w:items() keeps reading its live items — "wrap, don't reimplement" (D-009).
--
-- 031: THE CLIENT'S OWN TOGGLE COMES WITH THE WINDOW. Hiding a native window makes it yours, toggle included, and
-- the view you pass to :replace is what that toggle drives — so once replaced, TAB and the inventory menu button
-- open and close the "Bags (custom)" window, and the menu tick follows the custom window rather than the hidden one.
-- Before that, Tab flipped `visible` back on the very window this addon had hidden, and the stock inventory came
-- back ON TOP of the custom one.
--
-- It is DORMANT until you press its hotkey, so it never disturbs a normal login / the `hello` regression harness.
-- The hotkey ARMS and DISARMS the replacement — it is not a show/hide key, that is Tab's job now. It starts
-- UNBOUND: assign "toggle" under Options > Keybindings > Bags (suggested: Ctrl+Shift+I). Then:
--   * Press it            -> ARM: subscribe to the inventory. The subscription fires at once for an inventory that
--                            is ALREADY open, so the native window hides, a custom "Bags" window draws your real
--                            items, and the client's Tab/menu button now drive THAT window.
--   * Press it again      -> DISARM: w:replace(nil) gives the native inventory (and its toggle) back, the custom
--                            window is destroyed, and the stock window is left AS YOU WERE SEEING IT — open if the
--                            custom view was on screen, closed if you had toggled it away with Tab.
--   * Or disable/`:reload` while replaced -> the same one rule runs on teardown (no leak, no orphaned key).
--
-- Item MOVING (take/transfer/drop) is an outbound gameplay action -> the gated Phase-4 actions tier (hafen.act),
-- so this view is READ-ONLY: it draws the real items and logs the one you click. `hafen` is the API facade.

hafen.log():write("bags loaded (v0.3.0) -- assign the 'toggle' hotkey in Options > Keybindings > Bags, then press it in-world"
  .. " (once replaced, Tab and the inventory menu button drive the CUSTOM window)")

-- The selector that names the MAIN inventory and nothing else (032.1, measured in-game with `widgetstack`'s
-- self-validating inspector: 1 match, first in tree order). The role part is load-bearing — [title=Inventory] alone
-- matches every widget INSIDE the wrapper, since [title=] resolves against the nearest ENCLOSING window — and so is
-- the refiner: `inventory` alone also matches the equipory and any open container.
local SEL = "inventory[title=Inventory]"

local CELL = 34             -- px per inventory cell in the custom view
local watch                 -- the hafen.ui():on subscription while ARMED (nil = disarmed)
local grid                  -- the native inventory grid we replaced (nil = nothing replaced)
local hover                 -- {x=,y=} grid cell under the mouse, for a highlight (or nil)

-- Stop replacing: grid:replace(nil) restores the native inventory AND destroys our view (idempotent, and a silent
-- no-op on a widget that has gone stale). Shared by the hotkey's disarm and the window's own X (onClose), so both
-- paths converge on a clean restore.
local function stopReplace()
  if watch then watch:remove(); watch = nil end
  if grid then grid:replace(nil); grid = nil end
end

-- The VIEW builder. It is handed the WIDGET OBJECT for the real inventory grid (029.3: the one entity every hafen.ui
-- door hands back, so every widget verb answers on it) and returns a window drawn over w:items() (Item objects:
-- :res() :name() :num() :wear() :quality() :cell() :handle()). Once :replace binds it, the engine owns its fate: it is destroyed automatically
-- when we disarm, when the addon is reloaded/disabled, or if the server ever destroys the inventory.
local function buildBagsView(w)
  -- Size the window to the items present now (a click-through demo; items streaming in later just clip harmlessly).
  local cols, rows = 4, 3
  for _, it in ipairs(w:items()) do
    local p = it:cell()
    if p then cols = math.max(cols, p.x + 1); rows = math.max(rows, p.y + 1) end
  end

  return hafen.ui():window()
    :title("Bags (custom)")
    :size(cols * CELL + 8, rows * CELL + 24)
    :position(150, 130)
    :onDraw(function(g, ww, h)
      g:color(0, 0, 0, 175); g:frect(0, 0, ww, h); g:color()             -- translucent backdrop
      local items = w:items()                                           -- LIVE read off the hidden inventory's items
      for _, it in ipairs(items) do
        local p = it:cell() or { x = 0, y = 0 }
        local cx, cy = 4 + p.x * CELL, 4 + p.y * CELL
        g:color(46, 56, 74); g:frect(cx, cy, CELL - 2, CELL - 2); g:color()        -- cell body
        g:color(96, 116, 150); g:rect(cx, cy, CELL - 2, CELL - 2); g:color()        -- cell border
        -- No item icons yet (g:image is deferred), so show a short name + a stack count.
        local label = tostring(it:name() or it:res() or "?"):gsub("^.*/", "")
        g:text(label:sub(1, 5), cx + 2, cy + 2)
        if it:num() and it:num() > 1 then g:atext(tostring(it:num()), cx + CELL - 4, cy + CELL - 13, 1.0, 0.0) end
      end
      if hover then                                                     -- hover highlight
        g:color(255, 225, 120)
        g:rect(3 + hover.x * CELL, 3 + hover.y * CELL, CELL, CELL)
        g:color()
      end
      g:color(180, 200, 160)
      g:text(("%d item(s) -- Tab closes this window; the hotkey restores the stock one"):format(#items), 4, h - 15)
      g:color()
      g:color(150, 150, 150); g:rect(0, 0, ww, h); g:color()            -- outer border
    end)
    :onMouseMove(function(x, y)
      hover = { x = math.floor((x - 4) / CELL), y = math.floor((y - 4) / CELL) }
    end)
    :onClick(function(x, y, button)
      local cx, cy = math.floor((x - 4) / CELL), math.floor((y - 4) / CELL)
      for _, it in ipairs(w:items()) do
        local p = it:cell()
        if p and p.x == cx and p.y == cy then
          hafen.log():write(("bags: clicked %s x%s @cell %d,%d -- moving items is the gated Phase-4 tier (read-only here)")
            :format(tostring(it:name() or it:res()), tostring(it:num() or 1), cx, cy))
          return true
        end
      end
      hafen.log():write(("bags: clicked empty cell %d,%d"):format(cx, cy))
      return true                                                       -- truthy = consume
    end)
    :onClose(function()
      -- The X fires while this window is still on screen, so the one restore rule ("as the user was seeing it")
      -- hands back an OPEN stock inventory -- which is what closing a window you were looking at should give you.
      hafen.log():write("bags: view closed (X) -- the stock inventory is back OPEN (you were seeing a window), and Tab"
        .. " toggles it again; press the toggle key to replace once more")
      stopReplace()                                                   -- X also restores the native inventory
    end)
end

-- The ARM/DISARM hotkey (031: it is not the show/hide key -- Tab is, once we are replacing). Arming subscribes to
-- SEL: registration SCANS the live tree, so an inventory that is already open is replaced on the spot (D-068 --
-- this is what the retired replace's own re-scan did, now the general subscription doing its job), and one that is
-- not simply gets replaced the moment it appears. The hotkey is declared through
-- hafen.client():options():keybindings():register(name, fn) and starts UNBOUND (D-047): a "Bags" section appears in
-- the keybind panel (2e-3) where YOU assign the key — Ctrl+Shift+I is merely the suggestion.
local keys = hafen.client():options():keybindings()
keys:register("toggle", function()
  if watch then
    stopReplace()
    hafen.log():write("bags: RESTORED the native inventory (and its Tab/menu toggle) -- left as you were seeing it:"
      .. " open if the custom window was on screen, closed if you had toggled it away")
  else
    local seen                                    -- did the subscription match anything at all? (see the log below)
    watch = hafen.ui():on(SEL, "appear", function(w)
      if grid then return end                     -- already standing in for one inventory; one window, one view
      seen = true
      local view = buildBagsView(w)
      -- The verb REFUSES rather than logs (it is a Lua call, not an engine path): another addon already holding
      -- this window, or a widget with no enclosing window, throws. Catch it so a refusal costs a message, not a
      -- leaked view.
      local ok, err = pcall(w.replace, w, view)
      if not ok then
        view:destroy()
        hafen.log():write(("bags: could not replace the inventory -- %s"):format((tostring(err):gsub("^.-%.lua:%d+:%s*", ""))))
        return
      end
      grid = w
      hafen.log():write("bags: REPLACED the native inventory with the custom view (drag it; click an item to log it)."
        .. " Tab and the inventory menu button now open and close THIS window, and the menu tick follows it")
    end)
    -- Only when the subscription matched NOTHING is "not open yet" the honest report: a match that was refused has
    -- already said why, and saying "no inventory yet" on top of it would be a lie about a window that is right there.
    if not seen then
      hafen.log():write("bags: ARMED -- no inventory in the tree yet; it will be replaced the moment one appears"
        .. " (press the toggle key again to disarm)")
    end
  end
end)
-- keybindings:get(name) resolves THIS addon's binding first (addon/bags/toggle), so the log always reports the
-- key the user actually assigned -- nil until they do.
hafen.log():write(("bags: toggle hotkey = %s (assign it under Options > Keybindings > Bags; suggested Ctrl+Shift+I)")
  :format(keys:key("toggle") or "unassigned"))

hafen.event():on("OnDisable", function()
  hafen.log():write("bags: OnDisable -- native inventory restored + custom view destroyed on teardown")
end)
