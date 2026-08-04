-- 036.1 — the layout verbs, and the client's memory. Self-checking suite; see specs/addons/TESTING.md.
--
-- Since 029 the Widget table has said that :position(x,y) on a NATIVE widget is an error, "moving a native widget
-- is layout, a later feature". This is that feature's first task, so the thing to prove is that the row was
-- corrected in BEHAVIOUR and not only in prose -- and that the move is a real one: c and sz, the very fields
-- the user's own drag writes, never a draw-time offset (which would draw the widget where it cannot be
-- clicked). hafen.ui():at() is what says which of the two it is, so that check is the load-bearing one here.
--
-- Everything on this page is a number, and the suite reads every one of them back through the API it ships.
-- The single exception is the risk the whole feature exists for: the client persists a few window positions
-- of its own (wndc-inv/-equ/-chr/-zerg/-map) at logout, so a naive move would have it save OUR position as
-- the user's preference and uninstalling would leave those windows displaced forever. The engine answers that
-- write with the stock coordinate instead -- but no program can log out, disable an addon and log back in, so
-- that half is the one [manual] line.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and restores every widget it touches.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end

local function centre(w)
  local p, s = w:rootPos(), w:size()
  return p and s and (p.x + math.floor(s.x / 2)), p and s and (p.y + math.floor(s.y / 2))
end

-- The hit-test target: a VISIBLE native widget the engine's own pointer dispatch already reports at BOTH the
-- points the check below uses -- its centre (which stays inside it after a 24/18 px move) and its top-left
-- corner + 2 (which does NOT, since 2 < 18) -- and that stays where this addon puts it. Picked rather than
-- named, and SELF-VALIDATING on every axis (030.2's inspector trick): a candidate counts only after
-- hafen.ui():at() has actually resolved to it at both points and after the move has actually taken, so the
-- assertions run against the same dispatch a real click uses, on whatever this HUD happens to be showing,
-- and cannot redden for a widget too big to leave its own corner or a parent that re-lays its children out.
-- Every candidate that does not qualify is put straight back.
local function hitTarget(dx, dy)
  local tried = 0
  for _, w in ipairs(hafen.ui():all("*")) do
    local p, s = w:rootPos(), w:size()
    if w:visible() and p and s and s.x > 32 and s.y > 32 and tried < 40 then
      local cx, cy = centre(w)
      local ux, uy = p.x + 2, p.y + 2
      if (hafen.ui():at(cx, cy) == w) and (hafen.ui():at(ux, uy) == w) then
        tried = tried + 1
        local was = w:position()
        w:position(was.x + dx, was.y + dy)
        if (w:position().x == was.x + dx) and (w:position().y == was.y + dy) then
          return w, was, ux, uy
        end
        w:position(nil)
      end
    end
  end
end

-- ---- the run ------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t036-1 reports its own counts

  -- 1. the premise, re-asserted here because everything below rests on it: a native window is reachable and
  --    BORROWED. hafen.ui():inventory() is the client's own grid; its enclosing window is the Hidewnd wrapper.
  local grid = hafen.ui():inventory()
  check(grid ~= nil, "the client's own inventory grid is reachable", grid)
  if grid == nil then
    hafen.log():write("[fail] no HUD: run this in-world -- every check below needs the client's own widgets")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  local wnd = grid:parent()
  eq("...and it is a widget this addon did NOT create", wnd:info().owned, false)

  -- 2. THE ROW 029 PROMISED. The verbs used to throw "layout, a later feature" on exactly this widget.
  local was = wnd:position()
  local moved = pcall(function() wnd:position(was.x + 37, was.y + 23) end)
  check(moved, "widget:position(x,y) no longer refuses on a NATIVE widget -- feature E, the row 029 promised", moved)
  eq("...and it moved c for real, read back through the same verb",
     xy(wnd:position()), ("%d,%d"):format(was.x + 37, was.y + 23))

  -- 3. the undo is the third arity, and it restores the stock numbers exactly.
  wnd:position(nil)
  eq("widget:position(nil) puts the window back exactly where the user had it", xy(wnd:position()), xy(was))

  -- 4. size: a window reads its OUTER box and is written by its CONTENT size, so the round trip is asserted
  --    on the outer box -- which must come back byte for byte. The equipment window is the target because
  --    the INVENTORY one cannot answer at all; check 5 is that finding, stated rather than stepped around.
  local equ = hafen.ui():equipment()
  check(equ ~= nil, "the client's own equipment window is reachable too", equ)
  if equ ~= nil then
    local ew = equ:parent()
    local outer = ew:size()
    local resized = pcall(function() ew:size(outer.x + 60, outer.y + 40) end)
    check(resized, "widget:size(w,h) no longer refuses on a NATIVE window", resized)
    check(xy(ew:size()) ~= xy(outer), "...and the window is genuinely a different size", xy(ew:size()))
    ew:size(nil)
    eq("widget:size(nil) restores the outer box byte for byte", xy(ew:size()), xy(outer))
  end

  -- 5. THE FINDING, and it is D-084's lesson one level up: a window that PACKS AROUND ITS CONTENT re-packs
  --    itself the moment anything resizes it. GameUI builds the inventory's Hidewnd with
  --    `cresize(ch) { pack(); }`, so the deco's own resize notifies the window, which sizes itself back to
  --    its grid before the call returns. So :size(w,h) reaches a native window but does not OVERRULE one
  --    that owns its own size -- inert, never an error, and it leaves nothing behind either.
  local invOuter = wnd:size()
  wnd:size(invOuter.x + 60, invOuter.y + 40)
  eq("a window that packs around its content re-packs itself: a resize from outside is inert, not an error",
     xy(wnd:size()), xy(invOuter))
  wnd:size(nil)
  eq("...and the undo leaves it exactly stock all the same", xy(wnd:size()), xy(invOuter))

  -- 5. THE LOAD-BEARING ONE: the move is c, not a paint-time offset, so the engine's own pointer dispatch
  --    finds the widget at its new place. A draw-time offset would leave at() answering the OLD centre.
  local t, tp, ux, uy = hitTarget(24, 18)
  check(t ~= nil, "a visible native widget the client's own hit dispatch resolves to, moved 24/18 px", t)
  if t ~= nil then
    local nx, ny = centre(t)
    eq("hafen.ui():at() finds the moved " .. t:type() .. " at its NEW place: the move is c, not a draw offset",
       hafen.ui():at(nx, ny) == t, true)
    check(hafen.ui():at(ux, uy) ~= t, "...and no longer at the corner it used to cover", hafen.ui():at(ux, uy))
    t:position(nil)
    eq("...and it goes back where it was", xy(t:position()), xy(tp))
  end

  -- 6. a write on a STALE widget is the 029.2 silent chaining no-op, layout included.
  local ghost = hafen.ui.window{ title = "036.1 probe", size = {60, 40}, pos = {-500, -500} }
  ghost:destroy()
  eq("a destroyed widget is stale", ghost:exists(), false)
  eq("...and a layout write on it CHAINS rather than throwing", ghost:position(9, 9) == ghost, true)
  eq("...and reads back nothing at all", ghost:position(), nil)

  -- 7. the refusals, and the one that changed: :pack()/:destroy() are still not the addon's to do, but their
  --    error now points at the layout verbs instead of at a feature that has arrived.
  refuses("widget:position(x) is a mistake, not an undo: the error names all three arities",
          function() wnd:position(5) end, "widget:position(nil)")
  refuses("widget:destroy() still refuses on a native widget", function() wnd:destroy() end, "NATIVE widget")
  refuses("...and the refusal now points at widget:position(x, y), not at a later feature",
          function() wnd:pack() end, "widget:position(x, y)")

  -- 8. nothing of ours is left on anything we did not move.
  wnd:position(nil)
  eq("widget:position(nil) on a window this addon is not holding is a silent no-op", xy(wnd:position()), xy(was))

  manualCheck("log out to the character screen, disable \"036.1 — the layout verbs\" in the AddOns panel, log"
    .. " back in with the same character, and look at where the inventory, equipment, character sheet, kin"
    .. " and map windows sit",
    "each one is where YOU last dragged it, not 37/23 px away -- the client persists what the user placed,"
    .. " never what an addon's layout moved (GameUI.savewndpos writes those at logout AND every 60s, so a"
    .. " layer that only restored at teardown would already have lost this)")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ON DEMAND ONLY (D-085). A suite does not start itself: the maintainer runs it when they want it, and
-- running THIS command alone is the whole verification of task 036.1. It restores every widget it touched
-- before it prints its summary, so a client it has run on is a stock client.
hafen.slash():register("t036-1", run)
