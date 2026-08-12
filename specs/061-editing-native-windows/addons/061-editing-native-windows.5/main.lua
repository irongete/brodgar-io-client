-- 061.5 — widget:text(s) writes a native caption, widget:title(s) a native window's. Self-checking suite.
--
-- Run it with the Options window open. It takes whichever label and button that window is showing, so no
-- panel has to be visited first -- except for the ONE checkbox line: the Options panels that carry a
-- checkbox are built on first visit, and a client showing none says so rather than passing quietly.

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

local TITLE = "061.5 was here"

-- The first widget of a list that has something to SAY. Which of the client's labels or buttons this lands
-- on does not matter -- the claim is the round trip, not the caption -- so the suite names no panel and
-- takes whatever the window is showing.
local function saying(list)
  for _, w in ipairs(list) do
    local t = w:text()
    if (t ~= nil) and (t ~= "") then return w end
  end
  return nil
end

local function sameBox(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local function shown(w)
  return tostring(w:text()) .. " @ " .. w:size().x .. "x" .. w:size().y
end

-- The Options window builds each of its panels on FIRST VISIT, so a window nobody has clicked into holds
-- its main panel and nothing else -- no label, no checkbox. Both targets are looked for in the whole tree
-- before a run gives up, and giving up names the one click that supplies them.
local VISIT = " -- click 'Video settings' inside the Options window, then re-run"

local function run()
  pass, fail, manual = 0, 0, 0
  local win = hafen.ui():find("window[title=Options]")
  if not win then
    check(false, "the Options window is open", "no window[title=Options] -- open Options and re-run")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  check(win:info().owned == false, "the Options window is a NATIVE target", win:info().owned)

  -- ---- a native window's caption -------------------------------------------------------------------
  local stockCap = win:title()
  win:title(TITLE)
  eq("a native window's caption is written and read back", win:title(), TITLE)
  win:title(nil)
  eq("the stock caption comes back on :title(nil)", win:title(), stockCap)

  local labels, buttons = win:all("@Label"), win:all("@Button")

  -- ---- a native label: a second write replaces the level, so ONE nil is enough ----------------------
  local lbl = saying(labels) or saying(hafen.ui():all("@Label"))
  if not lbl then
    check(false, "a native label's text is written and read back", "no native Label in the tree" .. VISIT)
  else
    local stock, stockSz = lbl:text(), lbl:size()
    lbl:text("061.5 wrote this label")
    eq("a native label's text is written and read back", lbl:text(), "061.5 wrote this label")
    lbl:text("061.5 wrote it twice")                    -- the level is REPLACED, never stacked...
    lbl:text(nil)                                       -- ...so one nil reaches the stock value
    check((lbl:text() == stock) and sameBox(lbl:size(), stockSz),
          "one :text(nil) gives the STOCK text and its box back, not the first write", shown(lbl))
  end

  -- ---- a native checkbox's label -------------------------------------------------------------------
  local box = saying(hafen.ui():all("@CheckBox"))
  if not box then
    check(false, "a native checkbox's label is written and read back", "no native CheckBox in the tree" .. VISIT)
  else
    local stock = box:text()
    box:text("061.5 ticked this")
    eq("a native checkbox's label is written and read back", box:text(), "061.5 ticked this")
    box:text(nil)
    eq("the stock label comes back on :text(nil)", box:text(), stock)
  end

  -- ---- a native button, whose picture is cached ----------------------------------------------------
  local btn = saying(buttons)
  if not btn then
    check(false, "a native button is reachable", #buttons .. " buttons in this window")
  else
    local stock, stockSz = btn:text(), btn:size()
    btn:text("061.5")
    eq("a native button's caption is written and read back", btn:text(), "061.5")
    btn:text(nil)
    check((btn:text() == stock) and sameBox(btn:size(), stockSz),
          "the stock caption and box come back on a button whose picture is cached", shown(btn))
  end

  -- ---- the refusals, each naming what to use instead ------------------------------------------------
  -- Every target here is reachable whatever panel is open: the entry is the chat's, and the close button is
  -- on the window itself. A refusal is not something to make the maintainer open a panel for.
  local entry, cbtn = hafen.ui():all("@TextEntry")[1], win:all("@IButton")[1]
  refuses("a native text entry refuses :text(s), naming :value(v)",
          function() return entry:text("061.5") end, "widget:value(v)")
  refuses("a native window refuses :text(s), naming :title(s)",
          function() return win:text("061.5") end, "widget:title(s)")
  refuses("a widget with nothing to say refuses :text(s)",
          function() return cbtn:text("061.5") end, "nothing to say")
  refuses("a widget that is not a window refuses :title(s), naming :text(s)",
          function() return (lbl or cbtn):title("061.5") end, "widget:text(s)")

  -- ---- and one caption held long enough to be looked at ---------------------------------------------
  win:title(TITLE)
  hafen.timer():after(6, function() win:title(nil) end)
  manualCheck("watch the Options title bar for the next 6 seconds",
              "'" .. TITLE .. "', then '" .. tostring(stockCap) .. "' back, both in the client's caption font")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t061-5", run)   -- the only way in: a suite does not start itself
