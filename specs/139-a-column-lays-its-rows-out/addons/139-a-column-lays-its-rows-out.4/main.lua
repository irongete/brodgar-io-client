-- 139.4 — a panel is columns inside columns. Self-checking suite.
--
-- No new verb: the panel below is the composition the options page is built from, and every check reads it
-- back through the verbs the feature already shipped. It is built a tick after :t139, because the console
-- handler runs under the typed tree's monitor and a window is built into the layer.

local pass, fail, manual = 0, 0, 0
local built                               -- the last panel, torn down when :t139 runs again

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The panel: a window packed around one column. Inside it a row (an icon and a checkbox on one line), a
-- switch, the group it governs (a column indented by a left padding, greyed out until the switch is ticked),
-- and a scroll holding a column of thirty rows.
local function build()
  local win = hafen.ui():window():title("Harvest"):position(80, 120)
  local panel = hafen.ui():column():gap(4):parent(win):position(0, 0)

  local row = hafen.ui():row():gap(4):parent(panel)
  local icon = hafen.ui():image():source("gfx/hud/chr/farming"):size(24, 24):parent(row)
  local ripe = hafen.ui():check():parent(row):text("Only ripe")

  local sw = hafen.ui():check():parent(panel):text("Advanced")
  local group = hafen.ui():column():gap(4):parent(panel)
  group:stock{ padding = {16, 0, 0, 0} }
  local unripe = hafen.ui():check():parent(group):text("Also unripe")
  hafen.ui():entry():parent(group):size(120)
  unripe:on("Changed", function() hafen.log():write("TICKED") end)
  group:enabled(false)
  sw:on("Changed", function(on) group:enabled(on) end)

  local sp = hafen.ui():scroll():parent(panel):size(160, 120)
  local list = hafen.ui():column():parent(sp):position(0, 0)
  for i = 1, 30 do hafen.ui():label():parent(list):text("row " .. i) end

  win:pack()
  return { win = win, panel = panel, row = row, icon = icon, ripe = ripe, group = group,
           unripe = unripe, sp = sp }
end

local function run()
  pass, fail, manual = 0, 0, 0
  if built then built.win:destroy() end
  local p = build()
  built = p

  -- a column in a scroll: the bar is one of the scroll's children, and thirty rows moved it
  local bar
  for _, ch in ipairs(p.sp:children():list()) do
    if ch:range() then bar = ch end
  end
  local range = bar and bar:range()
  check(range and (range.max > 0), "the scroll's bar has :range().max > 0 once thirty rows are in",
        range and range.max or "no child of the scroll answers :range()")

  -- a window packed around a column follows it
  local h0 = p.win:size().h
  local extra = hafen.ui():label():parent(p.panel):text("one more row"):size(120, 20)
  eq("the window grows by exactly a row's height and the gap when a label is added after :pack()",
     p.win:size().h - h0, extra:size().h + p.panel:gap())

  -- a row: the icon and the checkbox on one line, the icon at the box :size gave it
  eq("the image and the checkbox of one row share a y", p.icon:position().y, p.ripe:position().y)
  local isz = p.icon:size()
  check((isz.w == 24) and (isz.h == 24), "the picture's box is the :size(24, 24) written on it",
        isz.w .. "x" .. isz.h)
  eq("the row is as tall as the taller of the icon and the checkbox", p.row:size().h,
     math.max(isz.h, p.ripe:size().h))

  -- a column nested with a left padding is an indent
  eq("the nested column's first child sits 16 further right than its parent's",
     p.unripe:position().x + p.group:position().x - p.row:position().x, 16)

  -- a disabled group: the rows keep their own flag, and the group reads false
  local own = true
  for _, ch in ipairs(p.group:children():list()) do
    own = own and ch:enabled()
  end
  eq("group:enabled(false) leaves every child's own :enabled() true", own, true)
  eq(":info() on the group reads enabled false", p.group:info().enabled, false)

  manualCheck("look at the first row", "a small farming icon, 24 px, drawn whole beside Only ripe")
  manualCheck("tick the box inside the greyed group", "nothing: no tick, and no TICKED line")
  manualCheck("flip the Advanced switch above it and tick the box again",
              "it ticks, and a TICKED line appears")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t139", function() hafen.timer():after(0, run) end)   -- a suite does not start itself
