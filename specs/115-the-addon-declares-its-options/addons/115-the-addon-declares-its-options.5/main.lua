-- 115.5 — the settings window is one box. Self-checking suite.
--
-- Type :t115 in the world. Every box it reads is a declared one, so it finishes instantly wherever the
-- window was left and asks for no particular selection. The one check that scores what you have walked is
-- the panels-built one: it covers every subject visited so far, so walk the Game list and run it again to
-- have it cover all seven, Keybindings among them. Picking a row cannot be automated -- one of the client's
-- own lists hands out no row for a script to hand back -- so the walk itself is the manual line.

-- OptWnd.PAGE, in design pixels: the box every page of the settings view is drawn inside.
local PAGE = 410

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function report()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---------------------------------------------------------------- the declaration

-- Three rows, which is the point of them: a page holding this little is a fraction of the box's height,
-- so a page that came out at the box is a page that FILLED it rather than one that happened to fit.
local opts = hafen.client():options():addon()
opts:boolean("one"):label("Row one"):default(true):add()
opts:number("two"):label("Row two"):range(1, 10):default(5):add()
opts:label("three"):label("Row three"):text("a line"):add()

-- ---------------------------------------------------------------- reading the window

-- The first child of w whose class name is `ty`. The settings view is built out of the client's own
-- widgets, so its parts are named by what they are rather than by a selector of ours. :children() is a
-- COLLECTION, so :list() is what an ipairs walks.
local function childOf(w, ty)
  for _, ch in ipairs(w:children():list()) do
    if ch:type() == ty then return ch end
  end
  return nil
end

local function childrenOf(w, ty)
  local found = {}
  for _, ch in ipairs(w:children():list()) do
    if ch:type() == ty then found[#found + 1] = ch end
  end
  return found
end

-- A design-pixel comparison: the box is UI.scale'd on the way in and unscaled on the way back out, so an
-- interface scale that does not divide evenly can leave a pixel behind on either side of the trip.
local function near(a, b)
  return math.abs(a - b) <= 1
end

local function box(sz)
  return sz.w .. "x" .. sz.h
end

-- ---------------------------------------------------------------- the run

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t115 scores its own run, not both
  local s = hafen.session():current()
  local ui = s and s:ui()
  local win = ui and ui:match("@OptWnd")
  local view = win and childOf(win, "SettingsPanel")
  check(view ~= nil, "the settings view stands under the Options window", view or win)
  if not view then return report() end

  local tabs = childrenOf(view, "Tab")
  local lists, holders = {}, {}
  for i, tab in ipairs(tabs) do
    lists[i] = childOf(tab, "PanelList")
    holders[i] = childOf(tab, "Widget")   -- the holder is the one plain Widget in a tab
  end
  local whole = (#tabs == 2) and (lists[1] ~= nil) and (lists[2] ~= nil)
                and (holders[1] ~= nil) and (holders[2] ~= nil)
  check(whole, "the view holds two tabs, each a subject list and a holder", #tabs .. " tabs")
  if not whole then return report() end

  -- The box itself, on both tabs. It is a declared constant rather than a page's own size, which is the
  -- whole of why the window stops moving: what is drawn in it cannot be what decides it.
  local h1, h2 = holders[1]:size(), holders[2]:size()
  check(near(h1.w, h2.w) and near(h1.h, h2.h) and near(h1.w, PAGE) and near(h1.h, PAGE),
        "both tabs draw their page in one box, and it is " .. PAGE .. "x" .. PAGE .. " design pixels",
        box(h1) .. " and " .. box(h2))

  local l1, l2 = lists[1]:size(), lists[2]:size()
  check(near(l1.w, l2.w) and near(l1.h, l2.h) and near(l1.h, PAGE),
        "both subject lists are one box, and it is the page's own height",
        box(l1) .. " and " .. box(l2))

  -- A holder keeps every panel it has built, hidden, so one walk reads the whole history of what the two
  -- lists have been asked for: seven client panels and an addon page apiece, at the most.
  local built, over = 0, nil
  for _, holder in ipairs(holders) do
    for _, p in ipairs(holder:children():list()) do
      built = built + 1
      local sz = p:size()
      if (sz.w > PAGE + 1) or (sz.h > PAGE + 1) then
        over = over or (p:type() .. " at " .. box(sz))
      end
    end
  end
  check((built > 0) and (over == nil),
        "every panel built in a holder fits inside the box (" .. built .. " built)",
        over or "no panel has been built")

  -- The two pages that carry a port of their own are the ones whose row count nobody bounds, and the ones
  -- that used to shrink to what they held. They fill the box now: the page is the box, the port is the
  -- page's own width, and what the page draws reaches its bottom edge. An addon's page needs no walking to
  -- be here -- declaring is what puts this addon in the AddOns list, and a list picks its first row on its
  -- own -- so this check always has one filler under it, and the walk below adds the other.
  local fillers, seen, bad = {}, {}, nil
  for _, ty in ipairs({"AddonOptionsPanel", "BindingPanel"}) do
    for _, p in ipairs(ui:matchAll("@" .. ty)) do
      fillers[#fillers + 1] = p
      seen[#seen + 1] = ty
    end
  end
  for _, p in ipairs(fillers) do
    local sz, port, bottom = p:size(), childOf(p, "Scrollport"), 0
    for _, ch in ipairs(p:children():list()) do
      bottom = math.max(bottom, ch:position().y + ch:size().h)
    end
    if not (near(sz.w, PAGE) and near(sz.h, PAGE)) then
      bad = bad or (p:type() .. " is " .. box(sz))
    elseif port == nil then
      bad = bad or (p:type() .. " holds no port")
    elseif not near(port:size().w, sz.w) then
      bad = bad or (p:type() .. "'s port is " .. port:size().w .. " wide in a page " .. sz.w .. " wide")
    elseif not near(bottom, sz.h) then
      bad = bad or (p:type() .. " draws down to " .. bottom .. " of its " .. sz.h)
    end
  end
  check((#fillers > 0) and (bad == nil),
        "every page with a port of its own fills the box (" .. table.concat(seen, ", ") .. ")",
        bad or "no such page has been built")

  -- And the view around them: exactly the strip and the box. Whatever a page does inside a holder, there
  -- is nothing left in this view for it to push, which is what the window is packed around.
  -- Two pixels here, one everywhere else: this side of the comparison is three separate reads, and each
  -- one is unscaled and rounded on its own way out.
  local vz, tc, hc = view:size(), tabs[1]:position(), holders[1]:position()
  check((math.abs(vz.w - (tc.x + hc.x + h1.w)) <= 2) and (math.abs(vz.h - (tc.y + hc.y + h1.h)) <= 2),
        "the view is its tab strip plus the box, and nothing else",
        box(vz) .. " over " .. (tc.x + hc.x + h1.w) .. "x" .. (tc.y + hc.y + h1.h))

  manualCheck("open Options and walk the Game list from Interface settings down to Client",
              "the window's frame never moves -- same width, same height, same place on screen")
  report()
end

hafen.console():on("t115", run)   -- the only way in: a suite does not start itself
