-- 061.3 — Changed and Selected on a native list, dropdown, menu and grid. Self-checking suite.
--
-- Two native targets, and one control of the addon's own:
--   * the ACTION SEARCH's result list, a real SListBox whose rows are anonymous ItemWidget subclasses —
--     so the seam has to sit where the client receives the click, not on the method a subclass replaces;
--   * the SKILLS grid on the character sheet, the client's one GridList;
--   * a list this addon builds itself, where the whole claim is that the key fires exactly ONCE.
--
-- Open before running, or the lines that need them fail saying so:
--   * the action search (Ctrl+Z), with a letter typed into it so the result list has rows;
--   * the character sheet, so its skills grid is in the tree.
--
--   :t061-3

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Phase 4: a list this addon BUILT. The seam addresses the list an addon holds, and this addon holds this
-- one — so its own dispatch is the only one that may run, and a second fire is the failure this looks for.
local function ownList()
  local win = hafen.ui():window():title("061.3"):size(200, 90):position(320, 320)
  local lst = hafen.ui():list():parent(win):position(12, 12):size(160, 60):rows{"Alpha", "Beta", "Gamma"}

  local fires, picked, scored = 0, nil, false
  lst:on("Changed", function(row)
    fires = fires + 1
    if scored then
      return
    end
    scored = true
    picked = row
    -- One click, one dispatch. A double dispatch lands in the same frame as the click that caused it, so a
    -- tenth of a second is long enough to have seen it and too short for a second click by hand.
    hafen.timer():after(0.1, function()
      check((fires == 1) and (picked == lst:value()),
            "one row click fired Changed exactly once on the list this addon built, carrying the row"
              .. " widget:value() reads back",
            fires .. " fire(s), " .. tostring(picked) .. " vs " .. tostring(lst:value()))
      win:destroy()
      summary()
    end)
  end)
  manualCheck("click a row in the list on the 061.3 window",
              "the row highlights, exactly as it would with no addon loaded")
end

-- Phase 3: the client's own list, cancelled. `before` is read INSIDE the handler, which is the instant the
-- seam runs: the client's own change() has not happened yet, so this is what the list still holds.
local function nativeList(list)
  local sub, scored = nil, false
  sub = list:on("Changed", function(ev)
    ev:preventDefault()
    if scored then
      return
    end
    scored = true
    local before, got = list:value(), ev:value()
    -- The client's own change() would have run after this fire returns, so what the cancel did is read a
    -- beat later rather than here.
    hafen.timer():after(0.4, function()
      check((got ~= nil) and (got ~= before),
            "the row the user clicked arrived as the event's value, and is not the one already picked", got)
      check(list:value() == before,
            "...and cancelling left the search's own selection exactly where it was", list:value())
      sub:off()
      local ok, err = pcall(ownList)
      if not ok then
        check(false, "a list this addon built takes a Changed subscription", err)
        summary()
      end
    end)
  end)
  manualCheck("in the action search, type a letter and then click a result that is NOT the highlighted one",
              "the yellow highlight does not move")
end

-- Phase 2: a menu's rows live in a list of their own, and that list is not the widget an addon holds. The
-- menu is built and dropped inside this function: it is never armed, so it paints nothing at all.
local function innerList()
  local m = hafen.ui():menu():rows{"Rename", "Delete"}
  local inner = m:find("@InnerList")
  check((inner ~= nil) and m:info().owned and not inner:info().owned,
        "a menu reads owned while the list of rows inside it reads borrowed",
        inner and inner:info().owned)
  if inner then
    refuses("a key on a menu's own inner list is refused, naming the menu as where it fires",
            function() inner:on("Selected", function() end) end,
            "fires on the menu itself")
  end
  m:destroy()
end

-- Phase 2b: the client's one grid, on the character sheet. Nothing here needs a click — what it pins is that
-- the roster and the seam agree about a GridList, which is the half a refusal can answer for.
local function nativeGrid()
  local grid = hafen.ui():find("@SkillGrid")
  if grid == nil then
    check(false, "the character sheet is open, so the client's skills grid is in the tree",
          "no @SkillGrid found")
    return
  end
  local ok, sub = pcall(function() return grid:on("Cell", function() end) end)
  check(ok and (sub ~= nil) and (grid:info().owned == false),
        "a native grid reads borrowed and answers Cell", ok and grid:info().owned or sub)
  if ok and sub then
    sub:off()
  end
  refuses("Changed on a native grid is refused, naming the key a grid does have",
          function() grid:on("Changed", function() end) end,
          "Cell")
end

local function run()
  pass, fail, manual = 0, 0, 0

  -- The client keeps this window in the tree and merely hides it, so being findable is not being open.
  local win = hafen.ui():find("window[title=Action search]")
  local list = win and win:find("@Results")
  local ready = (win ~= nil) and win:visible() and (list ~= nil) and (list:value() ~= nil)
  check(ready, "the action search is open and its result list holds a selection",
        (win == nil) and "no Action search window" or (win:visible() and (list and list:value()) or "hidden"))

  -- Each phase is guarded so a refusal nobody expected still ends in a [summary] line to paste back.
  local ok, err = pcall(innerList)
  if not ok then
    check(false, "a menu and the list of rows inside it answer their reads", err)
  end
  ok, err = pcall(nativeGrid)
  if not ok then
    check(false, "the client's own grid answers its reads", err)
  end

  if not ready then
    summary()
    return
  end
  check(list:info().owned == false, "a result list of the client's own reads borrowed", list:info().owned)
  refuses("Selected on a native list is refused, naming the keys a list does have",
          function() list:on("Selected", function() end) end,
          "Changed, MouseDown, MouseUp, MouseMove, Wheel, Destroy")
  refuses("Cell on a native list is refused the same way",
          function() list:on("Cell", function() end) end,
          "Changed, MouseDown, MouseUp, MouseMove, Wheel, Destroy")
  ok, err = pcall(nativeList, list)
  if not ok then
    check(false, "Changed subscribes on one of the client's own lists", err)
    summary()
  end
end

hafen.slash():register("t061-3", run)   -- the only way in: a suite does not start itself
