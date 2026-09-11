-- 139.2 -- margin is the room around a row. Self-checking suite.
--
-- A column places each child inside its own margin: the child sits its left and top inset further in, the
-- walk advances by its box plus both insets, then by the gap -- added, never collapsed. The margin is the
-- child's own cascade: widget:rule(), a stock, or a tree rule from Lua or from a loaded document. A site key
-- refuses it naming a render site, and on a widget no column lays out it moves nothing.
--
-- :t139 runs it. Nothing here starts on its own; the column, the lone label and the sheet it installs are all
-- torn down before the summary. The column is built a tick after the command, because the console handler
-- runs under the typed tree's monitor and a surface of ours takes the layer's. Its children are bare widgets
-- sized in design pixels rather than labels, so that every read is exact at every interface scale.

local pass, fail = 0, 0

local function out(line) hafen.log():write(line) end

-- LuaJ prefixes an error raised from Java as "@chunk.lua:189 msg" (a SPACE, no second colon), and one
-- raised by Lua as "chunk.lua:189: msg"; strip either so a needle is matched against the message alone.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function check(what, ok, got)
  if ok then pass = pass + 1 else fail = fail + 1 end
  out((ok and "[pass] " or "[fail] ") .. what .. (ok and "" or (" -- got: " .. tostring(got))))
end

-- The call must FAIL, and fail SAYING why: its message carries `needle`.
local function refuses(what, fn, needle)
  local ok, err = pcall(fn)
  if ok then return check(what, false, "<no error>") end
  local msg = why(err)
  check(what, msg:find(needle, 1, true) ~= nil, msg)
end

local function bottom(w) return w:position().y + w:size().h end
local function at(w) return w:position().x .. "," .. w:position().y end
local function insets(t) return t and (t.l .. "," .. t.t .. "," .. t.r .. "," .. t.b) or tostring(t) end

local function run()
  pass, fail = 0, 0
  -- Bare widgets sized in design pixels, and insets that are multiples of 4: every number converts exactly
  -- at every interface scale, so a sum of reads IS the column's own device sum. A label's height is the
  -- client's art (20 device px is 13.33 design px at 1.5) and a sum over such reads can be off by one.
  local col = hafen.ui():column():gap(8):position(40, 60)
  local one = hafen.ui():widget():parent(col):size(40, 20)
  local two = hafen.ui():widget():parent(col):name("two"):size(40, 24)
  local three = hafen.ui():widget():parent(col):name("three"):size(48, 16)

  -- widget:rule(): the hand-named level takes it, and the column has moved the rest before the call returns.
  two:rule():margin(16, 4, 8, 12)
  check("the second's x is its left margin 16, and its right inset widens the column to 16 + 40 + 8",
        two:position().x == 16 and col:size().w == 64, at(two) .. " col w " .. col:size().w)
  check("the second's y is the first's bottom + gap 8 + top margin 4",
        two:position().y == bottom(one) + 12, at(two) .. " vs bottom " .. bottom(one))
  check("the third's y is the second's bottom + bottom margin 12 + gap 8",
        three:position().y == bottom(two) + 20, at(three) .. " vs bottom " .. bottom(two))
  check("rule:margin() reads back {l = 16, t = 4, r = 8, b = 12}", insets(two:rule():margin()) == "16,4,8,12",
        insets(two:rule():margin()))
  check("widget:style().margin reads the same", insets(two:style().margin) == "16,4,8,12",
        insets(two:style().margin))
  two:rule():padding(1, 2, 3, 4)
  check("the twin verb takes four numbers too: rule:padding(1, 2, 3, 4) reads back {1, 2, 3, 4}",
        insets(two:rule():padding()) == "1,2,3,4", insets(two:rule():padding()))

  -- a stock: the bottom of the cascade, and a margin all the same.
  local four = hafen.ui():widget():parent(col):size(40, 20)
  four:stock{ margin = 8 }
  check("a :stock{ margin = 8 } child sits 8 in, and 8 below the gap",
        four:position().x == 8 and four:position().y == bottom(three) + 16, at(four) .. " vs bottom " .. bottom(three))

  -- a tree rule, written in Lua and then loaded from a document: both land, and releasing gives it back.
  local s = hafen.ui():sheet()
  local key = "[name=" .. three:name() .. "]"
  s:rule(key):margin(12):sheet():install()
  check("a tree rule's margin(12) from Lua lands: the third at 12 in, and 12 + 8 + 12 below the second",
        three:position().x == 12 and three:position().y == bottom(two) + 32, at(three) .. " vs bottom " .. bottom(two))
  s:release()
  s:load(hafen.json():parse('{"' .. key .. '": {"margin": [12, 12, 12, 12]}}')):install()
  check("the same rule loaded from JSON lands the same",
        three:position().x == 12 and three:position().y == bottom(two) + 32, at(three) .. " vs bottom " .. bottom(two))
  s:release()
  check("released, the third is back at the left edge, 12 + 8 below the second",
        three:position().x == 0 and three:position().y == bottom(two) + 20, at(three) .. " vs bottom " .. bottom(two))

  -- a site key is no widget: refused, naming the kind of key it is.
  refuses("[\"*\"]:margin(4) is refused naming a render site", function() s:rule("*"):margin(4) end, "render site")
  refuses("...and so is { [\"*\"] = { margin = 4 } } through sheet:load",
          function() s:load({ ["*"] = { margin = 4 } }) end, "render site")

  -- outside a column the property is inert: readable, and it moves nothing.
  local lone = hafen.ui():label():text("lone"):position(300, 300)
  lone:rule():margin(20)
  check("a label outside any column keeps its :position() under a margin, and still reads it back",
        lone:position().x == 300 and lone:position().y == 300 and insets(lone:style().margin) == "20,20,20,20",
        at(lone) .. " margin " .. insets(lone:style().margin))

  col:destroy()
  lone:destroy()
  out(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

-- The only way in: a suite does not start itself. Deferred a tick, off the console handler's monitor.
hafen.console():on("t139", function() hafen.timer():after(0, run) end)
