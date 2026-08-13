-- 065.8 — the button's face. Self-checking suite.

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

local KEY  = "button"
local FILL = { color = {70, 40, 100, 255} }                  -- the face at rest: purple
local FACE = { color = {70, 40, 100, 255},
               hover    = { color = {130, 95, 175, 255} },   -- ...lighter under the pointer
               pressed  = { color = {220, 130, 40, 255} },   -- ...orange while held
               disabled = { color = {60, 60, 60, 255} } }
local LINE = { color = {255, 220, 40, 255}, width = 2 }      -- and a two-pixel yellow frame

local function has(list, want)
  for _, v in ipairs(list or {}) do
    if v == want then return true end
  end
  return false
end

local function rgb(c)
  if not c then return "<nil>" end
  return c.r .. "," .. c.g .. "," .. c.b
end

local function run()
  local s = hafen.ui():sheet()
  local r = s:rule(KEY)

  local ok, err = pcall(function() r:bg(FACE):border(LINE) end)
  check(ok, "the button key takes a bg with state faces and a border", err)

  local g = r:bg() or {}
  check(g.color and (g.color.r == 70) and (g.color.g == 40) and (g.color.b == 100),
        "the bg reads back the face at rest (70,40,100)", rgb(g.color))
  check(g.pressed and g.pressed.color and (g.pressed.color.r == 220) and (g.pressed.color.b == 40)
        and g.hover and g.hover.color and (g.hover.color.r == 130)
        and g.disabled and g.disabled.color and (g.disabled.color.r == 60),
        "...and the three state faces beside it, each its own surface",
        rgb(g.pressed and g.pressed.color) .. " / " .. rgb(g.hover and g.hover.color))
  check(g.checked == nil, "a state the rule never named reads back nothing at all", g.checked)

  -- The line border's own claims, duplicated rather than assumed: this key's frame rests on them.
  local b = r:border() or {}
  check(b.color and (b.color.r == 255) and (b.color.g == 220) and (b.color.b == 40) and (b.width == 2),
        "the frame reads back its colour and its width (255,220,40 / 2)",
        rgb(b.color) .. " / " .. tostring(b.width))

  refuses("a state that does not exist is refused, naming the four that do",
          function() s:rule(KEY):bg{ color = {1, 2, 3}, focused = { color = {4, 5, 6} } } end,
          "\"hover\", \"pressed\", \"disabled\", \"checked\"")
  refuses("a state face with no face to vary is refused",
          function() s:rule(KEY):bg{ pressed = { color = {4, 5, 6} } } end, "no face for it to vary")
  refuses("a state inside a state is refused",
          function() s:rule(KEY):bg{ color = {1, 2, 3}, pressed = { color = {4, 5, 6},
                                                                   hover = { color = {7, 8, 9} } } } end,
          "carries no state of its own")

  local win = hafen.ui():window():title("065.8"):size(230, 60):position(120, 120)
  local one = hafen.ui():button():parent(win):position(10, 10):size(100):text("Face")
  local two = hafen.ui():button():parent(win):position(120, 10):size(100):text("Frame")
  local stock = one:size()
  check(stock and (stock.x == 100) and (stock.y > 0),
        "a button of this suite's own is 100 wide and as tall as its own art",
        stock and (stock.x .. "x" .. stock.y))

  s:rule(KEY):bg(FACE):border(LINE)
  s:install()
  local i = s:info()
  check(i.installed and has(i.rules, KEY), "the sheet is installed and names the button key",
        tostring(i.installed))
  local dressed = one:size()
  check(dressed and (dressed.x == stock.x) and (dressed.y == stock.y),
        "a dressed button is the size it was: a face paints, it never re-sizes",
        dressed and (dressed.x .. "x" .. dressed.y))

  s:drop()
  local back = two:size()
  check((s:info().installed == false) and (back.x == stock.x) and (back.y == stock.y),
        "dropping the sheet un-installs it and leaves the stock box untouched",
        tostring(s:info().installed) .. " " .. back.x .. "x" .. back.y)

  s:rule(KEY):bg(FACE):border(LINE)
  s:install()
  manualCheck("open Options beside this suite's own window, move the pointer onto one of its buttons,"
              .. " then hold it down",
              "EVERY button -- the client's own and this suite's two, at the same weight -- in a 2 px yellow"
              .. " frame (255,220,40) over a purple fill (70,40,100) with no brown left; the fill turning"
              .. " lighter purple under the pointer and ORANGE (220,130,40) while held; the caption in the"
              .. " place and the size it had, and the button's own box unmoved")
  manualCheck("type :reload, then open Options again -- this suite's own window goes with the reload, so the"
              .. " client's own buttons are what the restore is read off",
              "every button back to the stock brown: no frame, no fill, no hover, and nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-8", run)   -- the only way in: a suite does not start itself
