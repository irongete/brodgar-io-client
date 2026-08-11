-- 047.3 — hafen.flowermenu():gob(), the object a ring was opened ON.
-- Self-checking suite; see specs/testing/addon-suite.md.
--
-- The server sends no such thing: a radial menu arrives as a list of captions and nothing else, so :gob() is a
-- CORRELATION the client makes from the press that opened the ring. That is why the interesting half of this
-- suite is the two NEGATIVE gestures -- a menu opened from an inventory item and the Kin window's own -- which
-- must answer nil while a ring is genuinely on screen. A nil asserted with nothing open would prove nothing.
--
-- An open menu grabs mouse AND keyboard, so nothing can be typed while one is up. The command therefore arms
-- one gesture at a time and the handler prints that gesture's verdict as it happens:
--
--   :t047-3        the no-menu assertions, then arms for the TREE right-click
--   :t047-3 item   arms for the INVENTORY-ITEM right-click
--   :t047-3 kin    arms for the KIN-ROW right-click
--   :t047-3 done   the cross-gesture invariants and the summary
--
-- Each arming disarms the one before it, so a gesture that went wrong is simply re-armed and repeated.

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

-- ---- the armed half: one gesture at a time, asserted with a real ring on screen ------------------

local subs, mode, seen, treeGob = {}, nil, {}, nil

local function onOpened()
  if (mode == nil) or seen[mode] then return end   -- one ring per gesture; a stray right-click stays quiet
  seen[mode] = true
  local g, cnt = hafen.flowermenu():gob(), hafen.flowermenu():count()

  if mode == "tree" then
    treeGob = g
    check((g ~= nil) and (cnt > 0) and (g:exists() == true),
          "the ring on a TREE names the gob it was opened on, and that gob is in the world",
          tostring(g) .. " / " .. tostring(cnt) .. " petals")
    local nm = (g ~= nil) and g:name() or nil
    check((nm ~= nil) and (nm:find("tree", 1, true) ~= nil),
          "...and it is the object that was clicked: its resource name says tree", nm)
    check((g ~= nil) and (hafen.flowermenu():gob() == g),
          "...and reading it again hands back the SAME Gob object: the click is claimed once, by the menu",
          tostring(g) .. " vs " .. tostring(hafen.flowermenu():gob()))
  else
    local what = (mode == "item") and "an INVENTORY ITEM" or "a KIN ROW"
    check((g == nil) and (cnt > 0),
          "a ring put up by " .. what .. " -- no click on a game object -- answers nil, with the ring up",
          tostring(g) .. " / " .. tostring(cnt) .. " petals")
  end
end

local function onClosed()
  if (mode ~= "tree") or (not seen.tree) or seen.treeClosed then return end
  seen.treeClosed = true
  check((treeGob ~= nil) and (hafen.flowermenu():gob() == treeGob) and (hafen.flowermenu():count() > 0),
        "...and while the ring fades it still names that gob: :gob() describes the menu :list() describes",
        tostring(hafen.flowermenu():gob()) .. " / " .. tostring(hafen.flowermenu():count()) .. " petals")
end

local function arm(m)
  for _, s in ipairs(subs) do s:off() end
  subs, mode = {}, m
  seen[m] = nil
  subs[1] = hafen.event():on("FlowerMenuOpened", onOpened)
  subs[2] = hafen.event():on("FlowerMenuClosed", onClosed)
  return (subs[1] ~= nil) and (subs[2] ~= nil)
end

-- ---- the four halves -----------------------------------------------------------------------------

local function open()
  pass, fail, manual = 0, 0, 0
  seen, treeGob = {}, nil

  -- The read is ungated -- this addon declares no permissions at all -- and with nothing open it ANSWERS
  -- rather than refusing, exactly as :list()/:count() do. None being up is the ordinary state.
  local ok, g = pcall(function() return hafen.flowermenu():gob() end)
  check(ok and (g == nil), "with no menu open :gob() is nil and does not throw, for an addon with no permissions",
        tostring(ok) .. " / " .. tostring(g))
  local list, cnt = hafen.flowermenu():list(), hafen.flowermenu():count()
  check((type(list) == "table") and (#list == 0) and (cnt == 0),
        "...and its two siblings agree there is nothing open: :list() is {} and :count() is 0", tostring(cnt))

  refuses("hafen.flowermenu():gob() takes no arguments", function() return hafen.flowermenu():gob("x") end,
          "takes no arguments")
  refuses("a DOT call is refused, naming the colon form", function() return hafen.flowermenu().gob() end,
          "COLON call")

  check(arm("tree"), "handlers are armed for the first gesture")

  manualCheck("right-click a TREE, let the ring appear, press Esc, then run `:t047-3 item`",
              "four [pass] lines printed by the handler while the ring is up and fading")
end

local function armItem()
  check(arm("item"), "handlers are re-armed for the inventory gesture")
  manualCheck("right-click an item in your inventory that opens a ring (a seed, a curiosity, a bucket),"
              .. " press Esc, then run `:t047-3 kin`",
              "one [pass] line: the ring is up and :gob() is nil")
end

local function armKin()
  check(arm("kin"), "handlers are re-armed for the kin gesture")
  manualCheck("open the Kin window, right-click a kin row, press Esc, then run `:t047-3 done`",
              "one [pass] line: the client-side ring is up and :gob() is nil")
end

local function done()
  for _, s in ipairs(subs) do s:off() end
  subs, mode = {}, nil
  check(seen.tree and seen.item and seen.kin, "all three rings were seen while the handlers were armed",
        "tree=" .. tostring(seen.tree) .. " item=" .. tostring(seen.item) .. " kin=" .. tostring(seen.kin))
  eq("and with every ring gone, :gob() is nil again", hafen.flowermenu():gob(), nil)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t047-3", function(args)
  local a = args and args[1]
  if a == "item" then armItem()
  elseif a == "kin" then armKin()
  elseif a == "done" then done()
  else open() end
end)
