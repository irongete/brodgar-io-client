-- 107.1 -- a surface an addon built, named so a theme can find it and dressed so it has a default.
--
--   :t107       run the checks
--   :t107 off   take the probe down

local ID    = "107-named-surfaces.1"       -- the folder name IS the prefix the engine writes
local STOCK = {r = 200, g = 40, b = 90, a = 200}
local RULE  = {r = 40, g = 90, b = 200, a = 200}
local probe, sheet = nil, hafen.ui():sheet()

local function run()
  local pass, fail, manual = 0, 0, 0
  local function ok(c, what, got)
    if c then pass = pass + 1; hafen.log():write("[pass] " .. what)
    else fail = fail + 1; hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got)) end
  end
  local function refused(fn, word, what)
    local good, err = pcall(fn)
    ok((not good) and string.find(tostring(err), word, 1, true) ~= nil, what,
       good and "<no error>" or err)
  end

  local s = hafen.session():current()
  if not s then hafen.log():write("[fail] run this in the world"); return end
  local hud = s:ui():match("@GameUI")

  if probe and probe:exists() then probe:destroy() end
  probe = hafen.ui():widget():parent(hud):size(120, 40):position(300, 300):name("probe")
  probe:stock{bg = {color = STOCK}, border = {color = {255, 255, 255}, width = 1}}

  -- The engine writes the addon's id in front: that is what stops two addons colliding over "probe".
  local full = ID .. "/probe"
  ok(probe:name() == full, "the engine prefixes the name with this addon's id", probe:name())
  refused(function() probe:name("again") end, "written once", "a second name is refused")

  -- ...and a selector names it back, which is the whole point of the refiner.
  ok(s:ui():match("[name=" .. full .. "]") ~= nil, "a [name=] selector finds it in the tree",
     "no match")
  ok(s:ui():match("[name=" .. ID .. "/nobody]") == nil,
     "...and a name nobody answers to matches nothing, rather than erroring", "it matched something")

  -- The stock is a real level: it resolves with no rule installed anywhere.
  local st = probe:stock()
  ok(st ~= nil and st.bg ~= nil and st.bg.color ~= nil and st.bg.color.r == STOCK.r,
     "widget:stock() reads back what the addon declared", st and hafen.json():encode(st))
  local look = probe:style()
  ok(look ~= nil and look.bg ~= nil and look.bg.color.r == STOCK.r,
     "...and the widget resolves to it with NO rule naming it", look and hafen.json():encode(look))

  -- ...and every rule beats it, per property. One step is enough -- the stock is beneath them all.
  sheet:rule("[name=" .. full .. "]"):bg{color = RULE}
  sheet:install()
  look = probe:style()
  ok(look ~= nil and look.bg ~= nil and look.bg.color.r == RULE.r,
     "a rule beats the stock outright", look and hafen.json():encode(look))
  ok(look ~= nil and look.border ~= nil and look.border.width == 1,
     "...per property: the border the stock declared still stands", look and hafen.json():encode(look))
  sheet:release()
  ok(probe:style().bg.color.r == STOCK.r, "releasing the sheet falls back to the stock",
     hafen.json():encode(probe:style()))

  -- What a stock may not say, and whose widgets it may be said on.
  refused(function() probe:stock{position = {1, 2}} end, "VERB",
          "layout in a stock is refused, naming the verb")
  refused(function() hud:name("mine") end, "your addon built",
          "naming a widget the CLIENT put up is refused, naming widget:rule()")

  hafen.log():write("[manual] a 120x40 box at (300,300) -- expect: crimson fill, white hairline, and the"
    .. " client's own text ON TOP of it, not hidden")
  manual = manual + 1
  hafen.log():write("[manual] :theme cyberpunk, then look again -- expect: unchanged, no theme names it")
  manual = manual + 1
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t107", function(a)
  if a[1] == "off" then
    if probe and probe:exists() then probe:destroy() end
    probe = nil
    sheet:release()
    hafen.log():write("107.1: probe down")
  else
    run()
  end
end)
