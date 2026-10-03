-- 173.3 — a widget in front of or behind its siblings, within a band it keeps. Self-checking suite.

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

local function message(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Does fn raise, saying want? Answers ok and what it said, for one line over several refusals.
local function raises(fn, want)
  local ok, err = pcall(fn)
  if ok then return false, "<no error>" end
  err = message(err)
  return err:find(want, 1, true) ~= nil, err
end

local surface, red, panel
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, widget in ipairs({ panel, red, surface }) do
    if widget then pcall(function() widget:destroy() end) end
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", message(err))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("173.3"):size(560, 70):position(40, 120)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

-- Where `widget` stands among its parent's children, 1-based, and how many there are.
local function index(widget)
  local list = widget:parent():children():list()
  for at, child in ipairs(list) do
    if child == widget then return at, #list end
  end
  return nil, #list
end

local function names(widget, leaves)
  local out = {}
  for _, child in ipairs(widget:children():list()) do
    out[#out + 1] = leaves[child] or "?"
  end
  return table.concat(out, ",")
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  surface = hafen.ui():widget():size(240, 160):position(300, 220)
  surface:stock{ bg = { color = { 40, 40, 48, 220 } } }
  local a = hafen.ui():widget():parent(surface):size(60, 60):position(0, 0)
  local b = hafen.ui():widget():parent(surface):size(60, 60):position(20, 20)
  local c = hafen.ui():widget():parent(surface):size(60, 60):position(40, 40)
  a:stock{ bg = { color = { 60, 120, 200, 255 } } }
  b:stock{ bg = { color = { 60, 180, 90, 255 } } }
  c:stock{ bg = { color = { 220, 180, 60, 255 } } }
  local column = hafen.ui():column():gap(4):parent(surface):position(140, 0)
  local first = hafen.ui():button():parent(column):text("One"):size(80)
  hafen.ui():button():parent(column):text("Two"):size(80)
  local dropdown = hafen.ui():dropdown():parent(surface):size(120, 20):position(0, 100)
    :rows{ "Alpha", "Beta", "Gamma", "Delta" }:value("Alpha")
  local leaves = { [a] = "a", [b] = "b", [c] = "c", [column] = "column", [dropdown] = "dropdown" }

  -- The point all three leaves cover, in root design pixels.
  local at = a:rootPos()
  local px, py = at.x + 50, at.y + 50
  local function hit() return leaves[hafen.ui():hit(px, py)] or tostring(hafen.ui():hit(px, py)) end

  a:raise()
  local where, count = index(a)
  check(where == count and hit() == "a", "after a:raise(), :children() ends with a and hit() at the overlap is a",
    names(surface, leaves) .. ", hit " .. hit())

  a:lower()
  check(hit() == "c", "after a:lower(), hit() at the overlap is c", names(surface, leaves) .. ", hit " .. hit())

  a:z(1)
  c:raise()
  where, count = index(a)
  check(where == count and a:z() == 1, "after a:z(1) and c:raise(), a is still last and a:z() reads 1",
    names(surface, leaves) .. ", a:z() " .. tostring(a:z()))

  a:z(nil)
  check(a:z() == 0 and index(a) == 1, "a:z(nil) reads 0 and a stands first again, behind no sibling",
    names(surface, leaves) .. ", a:z() " .. tostring(a:z()))

  local before = index(b)
  b:z(2)
  local raised = index(b)
  b:revert()
  check(raised == count and b:z() == 0 and index(b) == before,
    "after b:z(2) and b:revert(), b:z() reads 0 and b is back at index " .. tostring(before),
    names(surface, leaves) .. ", b:z() " .. tostring(b:z()))

  local refused, said = raises(function() first:raise() end, "its place is its order")
  check(refused, "a column's button refuses :raise(), naming its order", said)

  local session = hafen.session():current()
  local gameui = session and session:ui():match("@GameUI")
  local chat = session and session:ui():match("hud.chat")
  if gameui and chat then
    local root, rootSaid = raises(function() session:ui():root():raise() end, "no siblings")
    local hud, hudSaid = raises(function() gameui:raise() end, "directly on the screen")
    local region, regionSaid = raises(function() chat:z(1) end, "painter's order")
    check(root and hud and region, "the root, @GameUI and hud.chat refuse the order writes, each naming why",
      (root and "" or rootSaid) .. (hud and "" or (" | " .. hudSaid)) .. (region and "" or (" | " .. regionSaid)))
  else
    check(false, "the root, @GameUI and hud.chat refuse the order writes, each naming why",
      "no character in the world -- run :t173 logged in")
  end

  local bad = {}
  for _, case in ipairs({
      { function() a:z(10) end, "from -9 to 9", ":z(10)" },
      { function() a:z(-10) end, "from -9 to 9", ":z(-10)" },
      { function() a:z(1.5) end, "whole number", ":z(1.5)" },
      { function() a:z("1") end, "must be a number", ":z(\"1\")" },
      { function() a:z(1, 2) end, "at most one argument", ":z(1, 2)" } }) do
    local ok, err = raises(case[1], case[2])
    if not ok then bad[#bad + 1] = case[3] .. ": " .. err end
  end
  check(#bad == 0 and a:z() == 0, ":z(10), :z(-10), :z(1.5), :z(\"1\") and :z(1, 2) raise, naming why",
    table.concat(bad, " | "))

  -- The popups' band: a surface of the suite's at the top band, over where the dropdown's list opens.
  local box = dropdown:rootPos()
  red = hafen.ui():widget():size(120, 48):position(box.x, box.y + 24)
  red:stock{ bg = { color = { 220, 30, 30, 255 } } }
  red:z(9)
  local function opened()
    manualCheck("with the suite's red surface at :z(9) over its dropdown, open the dropdown",
      "its list over the red square")
    finish()
  end
  prompt("Open the dropdown under the red square: its list should show OVER the red. Then press Done.", opened,
    opened)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t173", function() after(0, run) end)
