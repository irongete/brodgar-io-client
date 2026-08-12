-- 061.9 — widget:revert() undoes a whole edit at once. Self-checking suite.
--
-- Run :t061-9 with the Options window OPEN and "Video settings" visited at least once this session: the
-- checkbox this run drives sits on that panel, and a panel nobody has opened is not in the tree at all.
--
-- It rebuilds the page's own example on one of the client's windows -- a caption, a size, a control of its
-- own inside the frame, a level on the window's close button and a widget rule -- reads every part of it
-- back, and then asks for all of it at once. The two things revert() must NOT do are asserted just as
-- hard: a replacement standing on a second window of the client's is left alone, and a checkbox driven
-- through :value(v) still holds what it was driven to, an act having nothing to give back.
--
-- Everything it touches is put back before the run ends: the inventory's substitution is undone, the
-- checkbox is driven back to what it was found at, and the Options window is left exactly as it was.

local pass, fail, manual = 0, 0, 0

local function log(s)
  hafen.log():write(s)
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
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
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- One group of checks. An error inside one is a FAIL of that group and nothing more: the run goes on and
-- still prints its verdict, which a raise out of a slash command would have swallowed.
local function stage(fn, what)
  local ok, err = pcall(fn)
  if not ok then
    check(false, what, (tostring(err):gsub("^.-%.lua:%d+:%s*", "")))
  end
end

local function first(t)
  return (t ~= nil) and t[1] or nil
end

local function byText(list, text)
  for _, w in ipairs(list) do
    if w:text() == text then
      return w
    end
  end
  return nil
end

local function same(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local function shown(c)
  return (c == nil) and "nil" or (c.x .. "x" .. c.y)
end

-- The nearest enclosing window, exactly as the client's own replace verb finds it.
local function windowOf(w)
  while (w ~= nil) and (w:role() ~= "window") do
    w = w:parent()
  end
  return w
end

local NO_VIDEO = "Options > Video settings was never opened -- its controls are not in the tree at all"

local go, view, inv           -- the adopted control, the stand-in window, the grid it stands in for
local wasOpen                 -- ...and whether the user was seeing the window it stands in for
local gone                    -- the adopted control's own Destroy fired
local live                    -- a run is in flight (the deferred half has not printed yet)

-- The deferred half: the Destroy of a widget the revert destroyed lands on the next tick, exactly as it
-- does when the window it was built into closes.
local function finish()
  check(gone, "...and the adopted control's own Destroy fired: it is destroyed, not dropped", gone)
  if view and view:exists() then
    if inv and inv:exists() and (inv:replacement() == view) then
      view:visible(wasOpen)   -- the stock window comes back as the user was seeing it...
      inv:replace(nil)        -- ...and the substitution takes the stand-in with it
    else
      view:destroy()
    end
  end
  go, view, inv, wasOpen = nil, nil, nil, nil
  live = false
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  if live then
    log("[fail] this suite is already waiting -- let it print its summary first")
    return
  end
  pass, fail, manual = 0, 0, 0
  go, view, inv, wasOpen, gone, live = nil, nil, nil, nil, false, true

  local win = hafen.ui():find("window[title=Options]")
  if win == nil then
    check(false, "the Options window is open", "no window[title=Options] -- open Options and re-run")
    live = false
    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  -- ---- the page's own example, on one of the client's windows ---------------------------------------
  local cap, box = win:title(), win:size()
  local cbtn = first(win:all("@IButton"))      -- every window carries a close button, and it reads BORROWED
  if cbtn == nil then
    check(false, "the Options window's close button is reachable", "no @IButton under window[title=Options]")
    live = false
    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  local btnAt = cbtn:position()
  win:title("061.9")
  go = hafen.ui():button():text("061.9"):parent(win):position(0, box.y)
  go:on("Destroy", function() gone = true end)
  win:pack()
  cbtn:on("Pressed", function(ev) ev:preventDefault() end)   -- the X does nothing while this stands
  cbtn:position(btnAt.x + 6, btnAt.y + 6)
  win:rule():color(200, 210, 220)

  local packed = win:size()
  check((win:title() == "061.9") and (packed.y >= box.y + go:size().y) and (go:parent() == win),
        "a caption, a size and a control of your own land on one of the client's windows",
        tostring(win:title()) .. ", " .. shown(box) .. " -> " .. shown(packed)
        .. ", parent=" .. tostring(go:parent()))
  check((not same(cbtn:position(), btnAt)) and (win:rule():info() ~= nil),
        "...and a level on its close button, and a widget rule of your own with it",
        shown(btnAt) .. " -> " .. shown(cbtn:position()) .. ", rule=" .. tostring(win:rule():info()))

  -- ---- the two things revert() must leave exactly where they are ------------------------------------
  local DRIVEN = "a control driven with :value(v) still reads what it was driven to"
  local STANDS = "widget:revert() leaves a replacement standing: it has an undo of its own"
  local cb, ticked, drove, replaced
  stage(function()
    -- On that panel and nowhere else: the checkboxes of the AddOns panel switch addons on and off, and a
    -- run that reached for "any checkbox in Options" could drive one of those instead.
    local panel = win:find("@VideoPanel")
    cb = panel and (byText(panel:all("@CheckBox"), "Vertical sync") or first(panel:all("@CheckBox")))
    if cb == nil then
      check(false, DRIVEN, NO_VIDEO)
      return
    end
    ticked = not cb:value()
    cb:value(ticked)                           -- an act: the server saw it, and there is nothing to undo
    drove = true
  end, DRIVEN)

  stage(function()
    inv = hafen.ui():inventory()
    local invwin = windowOf(inv)
    if invwin == nil then
      check(false, STANDS, "no inventory window in the tree")
      return
    end
    wasOpen = invwin:visible()
    view = hafen.ui():window():title("061.9"):position(200, 200):size(160, 60)
    inv:replace(view)
    invwin:revert()                            -- the very window whose record carries the substitution
    replaced = true
  end, STANDS)

  -- ---- one call, and everything above comes off ------------------------------------------------------
  win:revert()
  local again = win:revert()                   -- ...and there is nothing left for a second one to do

  check(win:title() == cap, "widget:revert() gives the stock caption back", win:title())
  check(same(win:size(), box), "...and the stock outer box", shown(win:size()))
  check(not go:exists(), "...and the control it adopted into the window is gone", go:exists())
  check(same(cbtn:position(), btnAt), "...and the level on its close button, deep in the subtree, with it",
        shown(cbtn:position()))
  check(win:rule():info() == nil, "...and this addon's own widget rule", win:rule():info())
  check(again == win, "a second revert raises nothing and still chains", tostring(again))

  if replaced then
    check(inv:replacement() == view, STANDS, tostring(inv:replacement()))
  end
  if drove then
    check(cb:value() == ticked, DRIVEN, tostring(cb:value()) .. " (drove it to " .. tostring(ticked) .. ")")
    cb:value(not ticked)                       -- ...and the client is left exactly as the run found it
  end

  refuses("an argument is refused: there is one edit to undo, whatever it was made of",
          function() return win:revert(true) end, "takes no argument")
  manualCheck("once the summary has printed, click the X on the Options window",
              "it closes normally -- the Pressed handler this run put on it went with the revert")

  hafen.timer():after(0.6, finish)   -- ...once the removal the revert caused has been reported
end

hafen.slash():register("t061-9", run)   -- the only way in: a suite does not start itself
