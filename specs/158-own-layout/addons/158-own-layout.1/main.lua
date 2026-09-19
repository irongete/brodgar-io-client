-- 158.1 — the verb is a level on a surface of yours, and the fold is its write. Self-checking suite.

local ID = "158-own-layout.1"

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

local function at(widget)
  local position = widget:position()
  return position.x .. "," .. position.y
end

local function box(widget)
  local size = widget:size()
  return size.w .. "x" .. size.h
end

-- The four reads every surface makes: the verb over the rule, the rule under the verb, the stock under both.
local function cascade(label, widget, sheet, stockAt, stockBox)
  local got = {}
  widget:position(40, 50):size(30, 30)
  got[#got + 1] = at(widget) .. " " .. box(widget)
  sheet:install()
  got[#got + 1] = at(widget) .. " " .. box(widget)
  check(got[1] == "40,50 30x30" and got[2] == "40,50 30x30",
    label .. ": the verb outranks the rule, before and after a sheet:install()", table.concat(got, " | "))
  widget:position(nil)
  widget:size(nil)
  local rule = at(widget) .. " " .. box(widget)
  sheet:release()
  local stock = at(widget) .. " " .. box(widget)
  sheet:install()
  check(rule == "200,200 80x60" and stock == (stockAt .. " " .. stockBox),
    label .. ": :position(nil)/:size(nil) land the rule, sheet:release() the stock " .. stockAt .. " " .. stockBox,
    rule .. " | " .. stock)
end

local function run()
  local sheet = hafen.ui():sheet()
  sheet:load{
    ["[name=" .. ID .. "/w]"]   = { position = {200, 200}, size = {80, 60} },
    ["[name=" .. ID .. "/win]"] = { position = {200, 200}, size = {80, 60} },
    ["[name=" .. ID .. "/b]"]   = { position = {200, 200}, size = {80, 60} },
    ["[name=" .. ID .. "/b2]"]  = { size = {80, 4} },
    ["[name=" .. ID .. "/p]"]   = { size = {80, 60} },
    ["[name=" .. ID .. "/pic]"] = { size = {80, 60} },
  }:install()

  -- a bare widget, a window (the content box), a control sized above its art
  local widget = hafen.ui():widget():name("w")
  cascade("a widget", widget, sheet, "100,100", "200x140")
  local window = hafen.ui():window():name("win")
  cascade("a window", window, sheet, "100,100", "200x140")
  local button = hafen.ui():button():name("b"):text("Go")
  local artBox = button:size()
  local art = artBox.h
  local stockBox = artBox.w .. "x" .. art
  do
    local got = {}
    button:position(40, 50):size(120, art + 40)
    got[#got + 1] = at(button) .. " " .. box(button)
    sheet:install()
    got[#got + 1] = at(button) .. " " .. box(button)
    button:position(nil)
    button:size(nil)
    got[#got + 1] = at(button) .. " " .. box(button)
    sheet:release()
    got[#got + 1] = at(button) .. " " .. box(button)
    sheet:install()
    local want = "40,50 120x" .. (art + 40)
    check(got[1] == want and got[2] == want and got[3] == ("200,200 80x" .. math.max(60, art))
        and got[4] == ("100,100 " .. stockBox),
      "a button: the verb over the rule through a sweep, the rule on nil, the stock on release",
      table.concat(got, " | "))
  end
  -- :size(w) under a rule: the width is the verb's, the height the art's; :size(nil) is the rule again
  button:size(120)
  local one = box(button)
  button:size(nil)
  check(one == ("120x" .. art) and box(button) == ("80x" .. math.max(60, art)),
    ":size(w) under a rule reads {w, art}, and :size(nil) the rule's box", one .. " | " .. box(button))

  -- the floor: a rule's size under the art lands the art, and the exact-minimum write survives a sweep
  local second = hafen.ui():button():name("b2"):text("Go")
  sheet:install()
  local floored = box(second)
  second:size(120, art)
  sheet:install()
  check(floored == ("80x" .. art) and box(second) == ("120x" .. art),
    "a rule's {80, 4} on a button reads {80, art}, and :size(120, art) reads art back after a sweep",
    floored .. " | " .. box(second))

  -- a packed surface: its box is its content's, a size rule and :size(nil) leave it
  local packed = hafen.ui():widget():name("p"):size(200, 100)
  hafen.ui():label():parent(packed):position(0, 0):text("row")
  packed:pack()
  local packedBox = box(packed)
  sheet:install()
  local afterSweep = box(packed)
  packed:size(nil)
  check(packedBox ~= "200x100" and afterSweep == packedBox and box(packed) == packedBox,
    "a packed widget keeps the packed box under a size rule, through a sweep and after :size(nil)",
    packedBox .. " | " .. afterSweep .. " | " .. box(packed))

  -- a picture: its own box, a size rule inert, :size(w, h) a pin through a sweep, :size(nil) its own again
  local picture = hafen.ui():image():name("pic"):source(hafen.asset():get("dot.png"))
  local own = box(picture)
  sheet:install()
  local ruled = box(picture)
  picture:size(50, 50)
  sheet:install()
  local pinned = box(picture)
  picture:size(nil)
  check(own ~= "50x50" and ruled == own and pinned == "50x50" and box(picture) == own,
    "a picture keeps its own box under a size rule; :size(50, 50) holds through a sweep; :size(nil) is its own",
    own .. " | " .. ruled .. " | " .. pinned .. " | " .. box(picture))

  -- dropping what was never written changes nothing; a column's child still refuses naming the column
  local fresh = hafen.ui():widget()
  local before = at(fresh)
  fresh:position(nil)
  check(before == "100,100" and at(fresh) == before, ":position(nil) on a widget never positioned changes nothing",
    before .. " | " .. at(fresh))
  local column = hafen.ui():column()
  local child = hafen.ui():label():parent(column):text("one")
  refuses("a column's child still refuses :position(nil) naming its order",
    function() child:position(nil) end, "its place is its order")

  -- the clamp: the root's child keeps 100 design pixels on screen, a child of yours goes where it is sent
  local far = hafen.ui():widget():position(-500, 10)
  local inner = hafen.ui():widget():parent(far):position(-500, 10)
  check(far:position().x == -100 and inner:position().x == -500,
    "a widget at the root clamps to x = -100; a child of a surface of yours reads -500",
    far:position().x .. " | " .. inner:position().x)

  -- a borrowed window is untouched by all this
  local session = hafen.session():current()
  local inventory = session and session:ui():match("window[title=Inventory]")
  if inventory then
    local was = at(inventory)
    inventory:position(40, 50)
    local moved = at(inventory)
    inventory:position(nil)
    check(moved == "40,50" and at(inventory) == was,
      "a borrowed window reads the verb, and :position(nil) the place it had", was .. " | " .. moved .. " | " .. at(inventory))
  else
    check(false, "a borrowed window reads the verb, and :position(nil) the place it had",
      "no character in world -- run :t158 logged in")
  end

  sheet:release()
  for _, surface in ipairs({ widget, window, button, second, packed, picture, fresh, column, far }) do
    surface:destroy()
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The only way in: a suite does not start itself. A console line runs under the character's tree monitor
-- and the suite writes layer widgets, so the run is deferred to the step, which holds none.
hafen.console():on("t158", function() hafen.timer():after(0, run) end)
