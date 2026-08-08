-- Cupboard (044): the SPATIAL-UI example -- hafen.vr():widget():add(w, gob), a window standing in the 3D
-- world on the very object it belongs to. Walk up to a cupboard, open it, and the client's own window is not
-- on the screen at all -- it is hanging over the furniture, camera-facing, with your real items going into it
-- and coming back out.
--
-- WHAT MAKES IT SMALL. Standing a widget is a RE-HOME, not a copy: the window that stands is the client's own,
-- still bound to its server id, still filling with items, still drawing every pixel it drew on the flat UI.
-- So there is no view to write and nothing to keep in sync -- "wrap, don't reimplement". Taking it back is
-- hafen.vr():widget():remove(x), and the window returns to the flat UI exactly as the user was seeing it; so
-- does :reload, and so does disabling this addon.
--
-- AND IT IS ENTIRELY EVENT-DRIVEN. Three subscriptions and nothing else -- no timer, no distance check, no
-- per-frame poll:
--   * hafen.ui():on(SEL, "appear", ...)  -- the window opened (and it fires for one that is ALREADY open)
--   * w:on("ItemAdded"/"ItemRemoved")    -- what is inside it, kept current while it stands
--   * w:on("Destroy", ...)               -- the server closed it
-- The last one is the interesting one: a standing entity ENDS WITH ITS CONTENT, so when the server destroys
-- the cupboard window the panel in the world goes with it. That is the moment this addon stands a panel of its
-- OWN in its place, drawn from the contents it snapshotted while the window was open -- and when the window
-- opens again, that panel comes down and the real one goes back up. Swapping is the normal case, not an edge.
--
-- THE ANCHOR IS A GAME OBJECT, so the panel follows the cupboard, dies with it, and -- because a camera-facing
-- quad rises along the camera's own UP axis -- is lifted clear of the ground with :offset(0, 0, z), which is
-- what keeps it out of the terrain plane when you tilt to a top-down view.
--
-- DORMANT until you type ':cupboard'. READ-ONLY: it declares no permissions. Moving an item is the gated tier,
-- and it does not have to be here -- the item you drag with your own hand goes into the standing window on its
-- own, because that window is the client's and nothing about it changed except where it is drawn.

local TITLE = "Cupboard"             -- the caption to watch; ':cupboard <title>' watches a different window
local GOB = "cupboard"               -- substring of the resource name of the thing it stands on
local LIFT = 12                      -- world units up from the gob's feet, so a top-down camera still sees it
local ROWS = 8                       -- most lines the panel lists; the rest are summed on a last line. A panel
                                     --   is sized in world units at a hundred pixels to the tile, so a
                                     --   cupboard holding forty things would otherwise stand five tiles tall.

local watch                          -- the "appear" subscription while ARMED (nil = disarmed)
local sel                            -- the selector that subscription was armed with
local stood                          -- the standing entity, whatever is up (nil = nothing standing)
local mine                           -- our own panel widget while IT is the one standing (nil otherwise)
local snap = {}                      -- what was in the window when we last looked: { {label=, num=}, ... }
local subs = {}                      -- the per-window subscriptions, dropped when that window goes
local function drop()
  for _, s in ipairs(subs) do s:off() end
  subs = {}
end

-- Take down whatever is standing. A window of the CLIENT's goes back to the flat UI under the one rule
-- (standing records where the widget was; removing puts it back there); a window of OURS goes back to its
-- default parent VISIBLE, which is where an unstood window of yours belongs -- so we destroy it, because a
-- leftover panel on the screen is not what "the real window is back" should look like.
local function takeDown()
  if stood and stood:exists() then hafen.vr():widget():remove(stood) end
  stood = nil
  if mine then
    if mine:exists() then mine:destroy() end
    mine = nil
  end
end

-- The gob to stand on: the nearest matching object, looked up ONCE, at the moment the window opens. That is
-- not a distance check -- nothing is measured again afterwards, because the panel follows the gob by itself.
local function anchor()
  local ok, g = pcall(function() return hafen.world():gob():nearest(GOB) end)
  return (ok and g and g:exists()) and g or nil
end

local function stand(w, g)
  local ok, e = pcall(function()
    return hafen.vr():widget():add(w, g):facing("camera"):offset(0, 0, LIFT)
  end)
  if not ok then
    hafen.log():write(("cupboard: could not stand it -- %s"):format((tostring(e):gsub("^.-%.lua:%d+:%s*", ""))))
    return nil
  end
  return e
end

-- OUR panel: what the window had in it when the server took the window away. It is an ordinary
-- hafen.ui():window() with an ordinary Draw handler -- the same code it would need on the flat UI, which is
-- the whole claim of the feature.
local function ownPanel()
  local shown = math.min(#snap, ROWS)
  local rest = #snap - shown
  local win = hafen.ui():window():title(TITLE .. " (closed)")
                    :size(150, 24 + 14 * math.max(shown + ((rest > 0) and 1 or 0), 1))
  win:on("Draw", function(ev)
    local g = ev:g()
    g:color(0, 0, 0, 160); g:frect(0, 0, ev:w(), ev:h()); g:color()
    if #snap == 0 then
      g:text("empty when it closed", 6, 6)
      return
    end
    for i = 1, shown do
      g:text(snap[i].label, 6, 6 + (i - 1) * 14)
      g:atext(tostring(snap[i].num), ev:w() - 6, 6 + (i - 1) * 14, 1.0, 0.0)
    end
    if rest > 0 then
      g:text(("+ %d more"):format(rest), 6, 6 + shown * 14)
    end
  end)
  return win
end

-- The CONTAINER inside the window. What stands is the window -- frame, title bar and all, which is the point
-- -- but the items belong to the grid inside it, so that is what the record is read from and subscribed to.
-- A window with no grid in it answers for itself, which is what an addon standing something else would want.
local function containerOf(w)
  local found
  w:walk(function(x)
    if (found == nil) and (x ~= w) and (x:role() == "inventory") then found = x end
    return true
  end)
  return found or w
end

-- What is in it right now, and THE GUARD IS THE WHOLE RELIABILITY OF THIS RECORD -- measured in-game, not
-- assumed. A container closes like this: the server unlinks the grid and every item widget in one batch, and
-- only then destroys the window, which announces itself at the start of its fade. By the time the addon layer
-- runs the Lua events, the grid is already out of the tree and the window -- still alive -- reads as empty.
-- So the closing container fires one ItemRemoved per item, every one of them after the fact.
--   :exists() is what tells the two apart. A grid still in the tree is answering about itself, and an item
-- you took out with your own hand updates the record; a grid that has left cannot say anything about what was
-- in it, so its reads are refused and the record keeps what it last saw. Subscribing to the WINDOW instead
-- looks equivalent and is not: the window outlives the grid, so those same after-the-fact removals would be
-- read back through a live widget and wipe the record to zero before the close was ever handled.
local function snapshot(w)
  if not (w and w:exists()) then return end
  snap = {}
  for _, it in ipairs(w:items()) do
    local label = tostring(it:name() or it:res() or "?"):gsub("^.*/", "")
    snap[#snap + 1] = { label = label:sub(1, 16), num = it:num() or 1 }
  end
end

-- The server closed the window. The panel in the world has already ended with it -- a standing entity ends
-- with its content -- so there is nothing to remove, only something to put in its place.
--
-- The record it draws from is the one the ItemAdded/ItemRemoved subscriptions built while the window was
-- open, and that is not a shortcut: by the time this handler runs the container has usually already left the
-- tree (measured, not assumed -- it answers nil to :type() here), so the last read is a no-op that the
-- :exists() guard in snapshot() turns away. Keep the record current while you can see it; do not expect to
-- be able to look once the thing is going.
-- The record it draws from is the one the ItemAdded/ItemRemoved subscriptions built while the grid was still
-- in the tree; there is nothing left to read here (see snapshot). A container closed while its items are
-- still streaming in therefore records what had arrived, which is the honest answer to what was in it.
local function closed(box, g)
  snapshot(box)                        -- a no-op once the grid has left, by the guard above; cheap insurance
  drop()
  stood = nil
  if not (g and g:exists()) then return end
  mine = ownPanel()
  stood = stand(mine, g)
  if stood then
    hafen.log():write(("cupboard: the window closed -- standing our own panel in its place (%d line(s) from"
      .. " the contents we snapshotted)"):format(#snap))
  end
end

local function appeared(w)
  local g = anchor()
  if g == nil then
    hafen.log():write(("cupboard: no '%s' object nearby to stand it on -- walk up to one and open it again")
      :format(GOB))
    return
  end
  takeDown()                                   -- our own panel comes down before the real one goes up
  drop()
  stood = stand(w, g)
  if stood == nil then return end
  local box = containerOf(w)
  snapshot(box)
  subs[#subs + 1] = box:on("ItemAdded", function() snapshot(box) end)
  subs[#subs + 1] = box:on("ItemRemoved", function() snapshot(box) end)
  subs[#subs + 1] = w:on("Destroy", function() closed(box, anchor()) end)
  hafen.log():write(("cupboard: the client's own '%s' window is now standing on the %s -- drag items into it"
    .. " where it hangs"):format(TITLE, GOB))
end

local function disarm()
  if watch then watch:remove(); watch = nil end
  drop()
  takeDown()
end

hafen.slash():register("cupboard", function(args)
  local named = (args and args[1]) and table.concat(args, " ") or nil
  if watch and (named == nil) then
    disarm()
    hafen.log():write("cupboard: DISARMED -- the window is back on the flat UI, left as you were seeing it")
    return
  end
  disarm()
  if named then TITLE = named end
  sel = ("window[title=%s]"):format(TITLE)
  watch = hafen.ui():on(sel, "appear", appeared)
  hafen.log():write(("cupboard: ARMED on %s -- open one and it stands on the nearest '%s'. Run ':cupboard'"
    .. " again to stop, or ':cupboard <window title>' to watch a different window"):format(sel, GOB))
end)

hafen.event():on("Disable", function()
  disarm()
  hafen.log():write("cupboard: Disable -- the window is back on the flat UI and every surface is freed")
end)

hafen.log():write("cupboard loaded -- type ':cupboard' in-world, then open a cupboard (dormant until then)")
