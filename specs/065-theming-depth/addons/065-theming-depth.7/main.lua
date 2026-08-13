-- 065.7 — the inventory square. Self-checking suite.

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

local KEY  = "inventory.slot"
local FILL = { color = {40, 20, 60, 255} }
local LINE = { color = {255, 140, 40, 255}, width = 1 }

local function has(list, want)
  for _, v in ipairs(list or {}) do
    if v == want then return true end
  end
  return false
end

local function run()
  local s = hafen.ui():sheet()
  local r = s:rule(KEY)

  local ok, err = pcall(function() r:bg(FILL):border(LINE) end)
  check(ok, "inventory.slot takes a bg and a line border", err)

  local g = r:bg() or {}
  check(g.color and (g.color.r == 40) and (g.color.g == 20) and (g.color.b == 60) and (g.color.a == 255),
        "the bg reads back its fill (40,20,60,255)",
        g.color and (tostring(g.color.r) .. "," .. tostring(g.color.g) .. "," .. tostring(g.color.b)))

  -- The line border's own claims, duplicated rather than assumed: this key rests on them.
  local b = r:border() or {}
  check(b.color and (b.color.r == 255) and (b.color.g == 140) and (b.color.b == 40) and (b.width == 1),
        "the line reads back its colour and its width (255,140,40 / 1)",
        tostring(b.color and b.color.r) .. "/" .. tostring(b.width))
  check((b.slice == nil) and (b.box == nil) and (b.mode == nil) and (b.image == nil) and (b.res == nil),
        "a line carries no slice, no box, no art and no mode", hafen.json():encode(b))
  refuses("a slice beside a line is refused",
          function() s:rule(KEY):border{ color = {255, 0, 0}, width = 1, slice = {1, 1, 1, 1} } end,
          "a line has no slice")
  refuses("a colour with no width is refused naming the width",
          function() s:rule(KEY):border{ color = {255, 0, 0} } end, "needs a \"width\"")

  s:install()
  local i = s:info()
  check(i.installed and has(i.rules, KEY), "the sheet is installed and names inventory.slot",
        tostring(i.installed))
  check((r:bg() ~= nil) and (r:border() ~= nil), "the installed key carries both properties at once")

  -- A site is not a widget: the square is where the client draws, so it has no place of its own to move.
  refuses("a position on inventory.slot is refused, naming the site",
          function() s:rule(KEY):position(10, 10) end, "render site (\"inventory.slot\")")
  refuses("...and the refusal names the fix: select the widget",
          function() s:rule(KEY):position(10, 10) end, "Name the widget instead")

  s:drop()
  check(s:info().installed == false, "dropping the sheet un-installs it", s:info().installed)
  s:rule(KEY):bg(FILL):border(LINE)
  s:install()

  manualCheck("open the inventory and the equipment window",
              "every square filled dark purple (40,20,60) and outlined 1 px in orange (255,140,40) --"
              .. " in BOTH windows -- with the item icons in the places they were, drawn OVER their"
              .. " squares rather than under them, and the grid the same size it was")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-7", run)   -- the only way in: a suite does not start itself
