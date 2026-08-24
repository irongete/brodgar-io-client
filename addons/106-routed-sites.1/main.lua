-- 106.1 -- the action menu's cell, a chat channel's field and a meter bar, as style sites.
--
-- Run it in the world, with the action menu and the chat on screen: two of the three declare their own
-- look from their own draw, so they answer only once they have drawn.
--
--   :t106       run the checks
--   :t106 off   give the sheet back

local sheet = hafen.ui():sheet()

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
  local function json(v) return v and hafen.json():encode(v) or "nil" end

  -- The three are KEYS: a bare role the client knows, so each mints a rule rather than raising.
  local named = true
  for _, k in ipairs({"menu.slot", "menu.frame", "chat.frame", "chat.log", "meter"}) do
    local good, r = pcall(function() return sheet:rule(k) end)
    named = named and good and r:selector() == k
  end
  ok(named, 'menu.slot, menu.frame, chat.frame, chat.log and meter are all keys', "one raised")

  -- ...and SITE keys rather than tree keys. Both halves, because either alone passes on a tree key too:
  -- a site names a place the client draws, so it classifies no widget AND sheet:stock() answers for it.
  local s = hafen.session():current()
  ok(s ~= nil and s:ui():match("meter") == nil, '"meter" names a site, so it matches no widget',
     s == nil and "no session -- run this in the world" or s:ui():match("meter"))
  refused(function() return sheet:stock("@Img") end, "render site",
          "sheet:stock() on a tree key is refused, naming what a site is")

  -- What each DECLARES is what it draws.
  local cell = sheet:stock("menu.slot")
  ok(cell ~= nil and cell.bg ~= nil and cell.border ~= nil and cell.border.width == 1,
     "menu.slot declares the fill and the one-pixel outline it is drawn with",
     cell and json(cell) or "nil -- has the action menu drawn?")
  local inv = sheet:stock("inventory.slot")
  ok(inv ~= nil and cell ~= nil and json(inv) == json(cell),
     "...the very raster an inventory square is, under a key of its own",
     inv == nil and "nil" or "differs from menu.slot")

  local deco = sheet:stock("chat.frame")
  ok(deco ~= nil and deco.bg ~= nil and deco.bg.res ~= nil and deco.border == nil,
     "chat.frame declares the field it tiles, and no frame -- that shape is not sayable whole",
     deco and json(deco) or "nil -- is the chat on screen?")
  local wash = sheet:stock("chat.log")
  ok(wash ~= nil and wash.bg ~= nil and wash.bg.color ~= nil and wash.bg.color.a == 128
     and wash.border == nil,
     "chat.log declares the flat wash a channel lays behind its lines",
     wash and json(wash) or "nil")

  -- A meter declares NOTHING on purpose: two shapes wear the key and one entry would describe neither.
  ok(sheet:stock("meter") == nil, "meter declares no stock look -- routed, and not sayable WHOLE",
     json(sheet:stock("meter")))

  -- A site is where the client draws, and text has no position: the three layout properties still refuse.
  refused(function() sheet:rule("menu.slot"):position(4, 4) end, "WIDGET",
          "position on menu.slot is refused, naming the fix")
  refused(function() sheet:rule("meter"):size(4, 4) end, "WIDGET", "size on meter is refused too")

  -- And each takes the chrome its page says it does, read straight back and then applied.
  sheet:rule("menu.slot"):bg{color = {20, 30, 50, 190}}
  sheet:rule("chat.log"):bg{color = {80, 0, 40, 210}}
  sheet:rule("menu.frame"):picture{color = {0, 0, 0, 0}}
  sheet:rule("meter"):bg{color = {0, 0, 0}}:border{color = {255, 45, 149}, width = 1}
  sheet:install()
  local m = sheet:rule("meter"):info()
  ok(m ~= nil and m.bg ~= nil and m.border ~= nil and m.border.width == 1,
     "a meter rule carries bg and border, and reads them back", json(m))

  hafen.log():write("[manual] look at the action menu, the chat and a meter -- expect: menu cells navy, its"
    .. " frame GONE, chat log wine, meter troughs black inside a magenta hairline")
  manual = manual + 1
  hafen.log():write("[manual] then :t106 off -- expect: all three back to the client's own, to the pixel")
  manual = manual + 1
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t106", function(a)
  if a[1] == "off" then
    sheet:release()
    hafen.log():write("106.1: released -- the client's own again")
  else
    run()
  end
end)
