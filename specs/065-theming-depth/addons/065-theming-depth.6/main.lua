-- 065.6 — a border may be a line, and the tooltip has a box. Self-checking suite.

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

local LINE = { color = {255, 140, 40, 255}, width = 2 }
local FILL = { color = {20, 20, 24, 235} }

local function has(list, want)
  for _, v in ipairs(list or {}) do
    if v == want then return true end
  end
  return false
end

local function run()
  local s = hafen.ui():sheet()
  local r = s:rule("tooltip")

  local ok, err = pcall(function() r:border(LINE):bg(FILL):padding(6) end)
  check(ok, "tooltip takes a line border, a bg and a padding", err)

  local b = r:border() or {}
  check(b.color and (b.color.r == 255) and (b.color.g == 140) and (b.color.b == 40)
        and (b.color.a == 255) and (b.width == 2),
        "the line reads back its colour and its width (255,140,40,255 / 2)",
        tostring(b.color and b.color.r) .. "/" .. tostring(b.width))
  check((b.slice == nil) and (b.box == nil) and (b.mode == nil) and (b.image == nil)
        and (b.res == nil),
        "a line carries no slice, no box, no art and no mode", hafen.json():encode(b))

  local g, p = r:bg() or {}, r:padding() or {}
  check(g.color and (g.color.r == 20) and (g.color.a == 235), "the bg reads back its fill (20,20,24,235)",
        g.color and g.color.a)
  check((p.l == 6) and (p.t == 6) and (p.r == 6) and (p.b == 6), "the padding reads back all four sides (6)",
        tostring(p.l) .. "," .. tostring(p.t) .. "," .. tostring(p.r) .. "," .. tostring(p.b))

  s:install()
  local i = s:info()
  check(i.installed and has(i.rules, "tooltip"), "the sheet is installed and names tooltip",
        tostring(i.installed))
  check((r:bg() ~= nil) and (r:border() ~= nil) and (r:padding() ~= nil),
        "the installed tooltip key carries all three at once")

  refuses("a slice beside a line is refused",
          function() s:rule("tooltip"):border{ color = {255, 0, 0}, width = 1, slice = {1, 1, 1, 1} } end,
          "a line has no slice")
  refuses("a width with no colour is refused for saying nothing",
          function() s:rule("tooltip"):border{ width = 2 } end, "says nothing")
  refuses("a colour with no width is refused naming the width",
          function() s:rule("tooltip"):border{ color = {255, 0, 0} } end, "needs a \"width\"")
  refuses("a negative width is refused",
          function() s:rule("tooltip"):border{ color = {255, 0, 0}, width = -1 } end,
          "at least 1 design pixel")
  refuses("a mode on a line is refused",
          function() s:rule("tooltip"):border{ color = {255, 0, 0}, width = 1, mode = "tile" } end,
          "no edge art to repeat")

  s:drop()
  check(s:info().installed == false, "dropping the sheet un-installs it", s:info().installed)
  s:rule("tooltip"):border(LINE):bg(FILL):padding(6)
  s:install()

  manualCheck("hover an inventory item",
              "the tip's box filled dark (20,20,24), outlined 2 px in orange (255,140,40),"
              .. " and its text further from the edge than stock")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-6", run)   -- the only way in: a suite does not start itself
