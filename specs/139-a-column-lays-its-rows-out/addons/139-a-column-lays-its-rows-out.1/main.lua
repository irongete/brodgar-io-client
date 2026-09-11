-- 139.1 -- a column lays its rows out. Self-checking suite.
--
-- hafen.ui():column() and :row() place what is parented into them along one axis, in tree order, :gap(n)
-- apart and the cascade's padding in from the edge, re-laid before the call that changed a child returns;
-- the box follows the content unless :size pins an axis; a child's place is its order, so :position on one
-- refuses, and so do :pack() on the column and a window or a borrowed widget as a child.
--
-- Every number here is read back through the API, so nothing is [manual]. Geometry is compared within ONE
-- design pixel: a device height converts to design once per read, so on a scaled client a sum of two reads
-- and one read of the sum may differ by the rounding.
--
-- :t139 runs it, a tick later (the console handler runs under the typed tree's monitor, and a builder
-- must not). Nothing starts on its own; everything it builds is destroyed and the one sheet it installs is
-- released before the summary.

local pass, fail = 0, 0

local function out(line) hafen.log():write(line) end

-- LuaJ prefixes an error raised from Java as "@chunk.lua:189 msg" (a SPACE, no second colon), and one
-- raised by Lua as "chunk.lua:189: msg"; strip either so a needle is matched against the message alone.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function verdict(what, ok, got)
  if ok then pass = pass + 1 else fail = fail + 1 end
  out((ok and "[pass] " or "[fail] ") .. what .. (ok and "" or (" -- got: " .. tostring(got))))
end

local function near(a, b) return math.abs(a - b) <= 1 end

-- The call must FAIL, and its message must carry `needle` (the fix it names).
local function refuses(what, fn, needle)
  local ok, err = pcall(fn)
  if ok then return verdict(what, false, "<no error>") end
  local msg = why(err)
  verdict(what, msg:find(needle, 1, true) ~= nil, msg)
end

local function xy(w) local p = w:position() return p.x, p.y end
local function wh(w) local s = w:size() return s.w, s.h end
local function fmt(...) return table.concat({ ... }, " ") end

local function run()
  pass, fail = 0, 0
  local col = hafen.ui():column()
  local row = hafen.ui():row()
  local one = hafen.ui():label():parent(col):text("one")
  local two = hafen.ui():label():parent(col):text("two"):name("two")
  local three = hafen.ui():label():parent(col):text("three")

  -- what it is
  verdict("a column's :role() is \"column\" and its :type() \"AddonWidget\"; a row's :role() is \"row\"",
    col:role() == "column" and col:type() == "AddonWidget" and row:role() == "row",
    fmt(tostring(col:role()), tostring(col:type()), tostring(row:role())))

  -- the layout: tree order along y, no gap from birth, the box the content's
  local x1, y1 = xy(one); local w1, h1 = wh(one)
  local x2, y2 = xy(two); local _, h2 = wh(two)
  local _, y3 = xy(three); local _, h3 = wh(three)
  local cw, ch = wh(col)
  verdict("the second's y is the first's y + h, the third's the second's, and the column is their height",
    x1 == 0 and y1 == 0 and x2 == 0 and near(y2, y1 + h1) and near(y3, y2 + h2) and near(ch, y3 + h3)
      and cw >= w1 and col:gap() == 0,
    fmt(x1, y1, h1, "|", x2, y2, h2, "|", y3, h3, "| col", cw, ch, "gap", tostring(col:gap())))

  -- :gap(n)
  col:gap(6)
  local _, g2 = xy(two); local _, g3 = xy(three)
  verdict(":gap(6) reads back 6 and puts 6 between the rows, and the column grows by twice that",
    col:gap() == 6 and near(g2, y1 + h1 + 6) and near(g3, g2 + h2 + 6) and near(select(2, wh(col)), ch + 12),
    fmt(tostring(col:gap()), g2, g3, select(2, wh(col))))

  -- a hidden child takes no room
  two:visible(false)
  local _, hid3 = xy(three)
  two:visible(true)
  local _, back3 = xy(three)
  verdict("hiding the second moves the third up into its place, and showing it moves the third back",
    near(hid3, y1 + h1 + 6) and back3 == g3, fmt(hid3, back3, "want", y1 + h1 + 6, g3))

  -- a child that resizes re-lays before the next line
  local r1 = hafen.ui():label():parent(row):text("a")
  local r2 = hafen.ui():label():parent(row):text("b")
  local rx2 = select(1, xy(r2))
  one:text("a much longer first line than before")
  r1:text("a much longer first cell than before")
  local nw = select(1, wh(one)); local ncw = select(1, wh(col))
  local rw1 = select(1, wh(r1)); local nrx2 = select(1, xy(r2))
  verdict("a longer :text on the first re-lays before the next line: the column is as wide as it, and the"
    .. " row's second moves right by the growth",
    ncw == nw and nw > w1 and near(nrx2, rw1) and nrx2 > rx2, fmt(w1, nw, ncw, "| row", rx2, nrx2, rw1))

  -- a destroyed child leaves; the rest close up
  one:destroy()
  local _, d2 = xy(two); local _, d3 = xy(three)
  verdict("destroying the first puts the second at the top and the third one gap under it",
    d2 == 0 and near(d3, h2 + 6), fmt(d2, d3, "want 0", h2 + 6))

  -- a row places x the same way
  local rx1, ry1 = xy(r1); local ry2 = select(2, xy(r2))
  local rowW, rowH = wh(row)
  verdict("a row places x the same way: the second's x is the first's x + w, both at y 0, the row their width",
    rx1 == 0 and ry1 == 0 and ry2 == 0 and near(nrx2, rx1 + rw1) and near(rowW, nrx2 + select(1, wh(r2)))
      and rowH >= select(2, wh(r1)),
    fmt(rx1, ry1, rw1, "|", nrx2, ry2, "| row", rowW, rowH))

  -- the cascade's padding is the inner room
  col:stock{ padding = 8 }
  local px, py = xy(two); local _, p3 = xy(three)
  local pw, ph = wh(col)
  verdict(":stock{padding = 8} puts the first child at 8, 8 and adds 16 to both sides of the box",
    px == 8 and py == 8 and near(p3, 8 + h2 + 6) and near(ph, p3 + h3 + 8)
      and near(pw, math.max(select(1, wh(two)), select(1, wh(three))) + 16),
    fmt(px, py, p3, "| col", pw, ph))
  col:stock{}

  -- :size pins an axis; :size(nil) lets it follow again
  local fw = select(1, wh(col))
  col:size(120)
  local sw, sh = wh(col)
  local four = hafen.ui():label():parent(col):text("four")
  local sw2, sh2 = wh(col)
  col:size(nil)
  four:destroy()
  local uw = select(1, wh(col))
  verdict(":size(120) pins the width, the height still follows a fourth child, and :size(nil) lets the width go",
    sw == 120 and near(sh, select(2, wh(two)) + 6 + select(2, wh(three))) and sw2 == 120 and sh2 > sh
      and uw == fw, fmt(fw, "|", sw, sh, "|", sw2, sh2, "|", uw))

  -- a rule's position on a child is inert
  local sheet = hafen.ui():sheet()
  sheet:rule("[name=" .. two:name() .. "]"):position(50, 50)
  sheet:install()
  local sx, sy = xy(two)
  sheet:release()
  verdict("a sheet rule's position on a child leaves it where the column put it",
    sx == 0 and sy == 0, fmt(sx, sy))

  -- the refusals, each naming the fix
  local ok1, e1 = pcall(function() two:position(10, 10) end)
  local ok2, e2 = pcall(function() two:position(nil) end)
  local order = "its place is its order"
  verdict(":position(x, y) and :position(nil) on a child are both refused naming its order and :parent(other)",
    (not ok1) and (not ok2) and why(e1):find(order, 1, true) ~= nil and why(e2):find(order, 1, true) ~= nil
      and why(e1):find("widget:parent(other)", 1, true) ~= nil,
    fmt(tostring(ok1), why(e1 or "<no error>"), "||", tostring(ok2), why(e2 or "<no error>")))
  refuses(":pack() on a column is refused naming construction and :size",
    function() col:pack() end, "packed by construction")
  local win = hafen.ui():window()
  refuses("a window is refused as a child naming why", function() win:parent(col) end,
    "a window cannot stand in a column")
  local s = hafen.session():current()
  local hud = s and s:ui():match("@GameUI")
  if hud then
    refuses("a borrowed widget is refused as a child naming why", function() hud:parent(col) end,
      "lays out only what your addon built")
  else
    verdict("a borrowed widget is refused as a child naming why", false,
      "no character in world -- run :t139 logged in")
  end

  win:destroy(); col:destroy(); row:destroy()
  out(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

hafen.console():on("t139", function() hafen.timer():after(0, run) end)
