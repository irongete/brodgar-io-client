-- 061.8 — widget:value(v) drives a native control (protected). Self-checking suite.
--
-- Run :t061-8 with the Options window OPEN and "Video settings" visited at least once this session: a
-- panel nobody has opened is not in the tree at all, so its controls cannot be found. Every control of
-- the client's that this run drives is put back exactly as it was found, in the same tick, before the
-- client draws a single frame with the driven value on it.
--
-- The families with no client-placed target of their own are driven where they DO live: a radio button,
-- a drop arrow and a scrollbar the client mints inside a control the addon built read borrowed exactly
-- as one in the client's own window does, and neither the gate nor the seam can tell the difference.
--
-- One arm is not asserted here, for want of anything to point at: :value(v) on one of the client's own
-- progress bars refuses, naming the Supplier it re-reads every frame. The client builds no Progress of
-- its own -- the only one in a tree would be a server-placed "prog" widget -- so there is no target a
-- run can reach, and a check that always failed would say less than this line does.

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

-- A refusal is a check: the call must fail, and fail SAYING why. One line per FAMILY of them, since a
-- suite printing one line per refusal would be longer than everything it proves.
local function refuseAll(what, cases)
  local bad = nil
  for _, c in ipairs(cases) do
    local ok, err = pcall(c[1])
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if ok or (err:find(c[2], 1, true) == nil) then
      bad = bad or ("\"" .. c[2] .. "\" -> " .. err)
    end
  end
  check(bad == nil, what, bad)
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

local host, radio, drop, list          -- the controls this run builds, and the window they sit in
local live                             -- a run is in flight (the deferred half has not printed yet)

-- Every Changed handler this run holds counts into ONE place, and the whole run is scored on it once: a
-- programmatic write must wake none of them, whichever control it landed on.
local fired = 0
local function count()
  fired = fired + 1
end

local NO_VIDEO = "Options > Video settings was never opened -- its controls are not in the tree"

-- ---- the client's own checkbox and slider, on the one panel this run needs -------------------------

local function nativeCheck(panel)
  local box = byText(panel:all("@CheckBox"), "Vertical sync") or first(panel:all("@CheckBox"))
  if box == nil then
    check(false, "widget:value(v) ticks one of the client's own checkboxes", NO_VIDEO)
    return
  end
  local sub = box:on("Changed", count)
  local was = box:value()
  box:value(not was)
  local flipped = box:value()
  check((box:info().owned == false) and (type(was) == "boolean") and (flipped == (not was)),
        "widget:value(v) ticks one of the client's own checkboxes",
        "owned=" .. tostring(box:info().owned) .. ", " .. tostring(was) .. " -> " .. tostring(flipped))
  box:value(was)
  check(box:value() == was, "...and driving it back leaves it exactly as it was found", box:value())
  sub:off()
  return box
end

local function nativeSlider(panel)
  local sl = first(panel:all("@HSlider"))
  if sl == nil then
    check(false, "widget:value(v) moves one of the client's own sliders", NO_VIDEO)
    return
  end
  local sub = sl:on("Changed", count)
  local was = sl:value()
  local to = was + 1
  sl:value(to)
  if sl:value() ~= to then    -- it was sitting at its own top: step the other way instead
    to = was - 1
    sl:value(to)
  end
  check((sl:info().owned == false) and (type(was) == "number") and (sl:value() == to),
        "widget:value(v) moves one of the client's own sliders",
        "owned=" .. tostring(sl:info().owned) .. ", " .. tostring(was) .. " -> " .. tostring(sl:value()))
  sl:value(was)
  check(sl:value() == was, "...and driving it back leaves it exactly as it was found", sl:value())
  sub:off()
end

-- ---- the chat entry: the client's one always-open text field ---------------------------------------

local function nativeEntry()
  local entry = first(hafen.ui():all("chat @TextEntry"))
  if entry == nil then
    check(false, "widget:value(v) types into one of the client's own text entries", "no chat entry found")
    return
  end
  local was = entry:value()
  entry:value("061.8")
  local typed = entry:value()
  entry:value(was)
  check((typed == "061.8") and (entry:value() == was),
        "widget:value(v) types into one of the client's own text entries, and the line comes back",
        tostring(typed) .. " -> " .. tostring(entry:value()))
  return entry
end

-- ---- the borrowed controls the client mints inside controls of the addon's own ----------------------

local function nativeRadio()
  local btn = first(radio:all("@RadioButton"))
  btn:value("Beta")
  local one = btn:value()
  btn:value("Gamma")
  check((btn:info().owned == false) and (one == "Beta")
        and (first(radio:all("@RadioButton")):value() == "Gamma"),
        "widget:value(v) moves a radio group of the client's own, and any button of it reads the row",
        "owned=" .. tostring(btn:info().owned) .. ", " .. tostring(one) .. " -> "
        .. tostring(btn:value()))
  refuseAll("a row that is not one of the group's is refused, naming the rows that are", {
    {function() btn:value("Delta") end, "no row named"},
  })
end

-- The drop arrow, and -- while the popup it opens is standing -- the one thing a native list will take.
local function nativeArrow()
  local arrow = first(drop:all("@ICheckBox"))
  local sub = arrow:on("Changed", count)
  arrow:value(true)
  local popup = first(hafen.ui():all("@SDropList"))
  local err = "<no popup>"
  if popup ~= nil then
    local ok, e = pcall(function() popup:value("One") end)
    err = ok and "<no error>" or (tostring(e):gsub("^.-%.lua:%d+:%s*", ""))
  end
  local opened = (arrow:value() == true) and (popup ~= nil)
  arrow:value(false)
  check((arrow:info().owned == false) and opened and (arrow:value() == false)
        and (err:find("ROW OF THAT LIST", 1, true) ~= nil),
        "widget:value(v) drives the client's own drop arrow, and its list takes only a row OF that list",
        "owned=" .. tostring(arrow:info().owned) .. ", opened=" .. tostring(opened)
        .. ", closed=" .. tostring(arrow:value() == false) .. ", list: " .. err)
  sub:off()
end

local function nativeBar()
  local sb = first(list:all("@Scrollbar"))
  local sub = sb:on("Changed", count)
  sb:value(5)
  local at = sb:value()
  sb:value(-1000)
  check((sb:info().owned == false) and (at == 5) and (sb:value() == 0),
        "widget:value(v) drives one of the client's own scrollbars, and a value past its range clamps",
        "owned=" .. tostring(sb:info().owned) .. ", " .. tostring(at) .. " / " .. tostring(sb:value()))
  sub:off()
end

-- ---- the deferred half: everything that needs the controls this run built to be armed and drawn -----

local function finish()
  stage(nativeRadio, "widget:value(v) moves a radio group of the client's own")
  stage(nativeArrow, "widget:value(v) drives the client's own drop arrow inside a dropdown of yours")
  stage(nativeBar, "widget:value(v) drives one of the client's own scrollbars")
  check(fired == 0, "a programmatic write is not an interaction: not one Changed fired all run", fired)
  if host and host:exists() then
    host:destroy()
  end
  host, radio, drop, list = nil, nil, nil, nil
  live = false
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  if live then
    log("[fail] this suite is already waiting -- let it print its summary first")
    return
  end
  pass, fail, manual, fired = 0, 0, 0, 0
  live = true

  local panel = hafen.ui():find("@VideoPanel")
  if panel == nil then
    check(false, "Options is open and Video settings has been visited", NO_VIDEO)
    live = false
    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local box, entry
  stage(function() box = nativeCheck(panel) end, "widget:value(v) ticks one of the client's own checkboxes")
  stage(function() nativeSlider(panel) end, "widget:value(v) moves one of the client's own sliders")
  stage(function() entry = nativeEntry() end, "widget:value(v) types into a native text entry")

  refuseAll("a value that is not the control's own shape is refused, naming what it holds", {
    {function() first(panel:all("@Label")):value(true) end, "holds nothing"},
    {function() box:value("yes") end, "BOOLEAN"},
    {function() entry:value(7) end, "STRING"},
  })

  -- The other half of the tier line: a control the addon BUILT is its own UI, and writing what it holds
  -- has never left the client, so there it is the same unprotected verb it always was.
  stage(function()
    host = hafen.ui():window():title("061.8"):position(150, 150):size(230, 210)
    local prog = hafen.ui():progress():parent(host):position(5, 175)
    prog:value(0.5)
    check(prog:value() == 0.5, "a control you built keeps its own UNPROTECTED :value(v)", prog:value())

    radio = hafen.ui():radio():rows{"Alpha", "Beta", "Gamma"}:parent(host):position(5, 5)
    drop = hafen.ui():dropdown():size(120, 20):rows{"One", "Two"}:parent(host):position(5, 65)
    local rows = {}
    for i = 1, 40 do
      rows[i] = "row " .. i
    end
    list = hafen.ui():list():size(120, 60):rows(rows):parent(host):position(5, 95)
  end, "a control you built keeps its own UNPROTECTED :value(v)")

  manualCheck("read the consent dialog you approved when you enabled this addon",
              "one line for widget.value: flip the client's own controls -- a box it ticks, a field it"
              .. " types into -- which the server sees")
  manualCheck("empty \"permissions\" in the manifest.json the CLIENT scans (beside the jar, not the"
              .. " source tree), :reload and re-run",
              "every line that drives one of the client's controls fails naming widget.value and none"
              .. " fails on its arguments -- a stage stops at its first drive, so its later lines do not"
              .. " print at all; the two lines that drive nothing of the client's (the control you built,"
              .. " and no Changed fired) still pass")

  hafen.timer():after(0.6, finish)   -- ...once the controls just built are armed and have been drawn
end

hafen.slash():register("t061-8", run)   -- the only way in: a suite does not start itself
