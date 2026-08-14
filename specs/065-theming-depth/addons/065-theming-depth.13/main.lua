-- 065.13 — the HUD's own art. Self-checking suite.

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

-- One DISTINCT colour per key: one art on all five would look identical on screen and read back identical
-- here, so the colours are the whole of what says each key resolved its own rather than a neighbour's.
local PLATE = {
  {key = "hud.belt",        r = 200, g =  40, b =  40, where = "the belt across the bottom"},
  {key = "hud.menu.left",   r =  40, g = 160, b =  60, where = "the map buttons, bottom left"},
  {key = "hud.menu.right",  r =  40, g =  60, b = 200, where = "the menu buttons, bottom right"},
  {key = "hud.search",      r = 230, g = 150, b =  30, where = "the search button's plate, bottom right"},
  {key = "minimap.frame",   r = 160, g =  40, b = 200, where = "the minimap's frame, bottom left"},
}

local function rgb(c)
  if not c then return "<nil>" end
  return c.r .. "," .. c.g .. "," .. c.b
end

local function dress(s)
  for _, p in ipairs(PLATE) do
    s:rule(p.key):picture{color = {p.r, p.g, p.b, 255}}
  end
  return s
end

local function asData()
  local t = {}
  for _, p in ipairs(PLATE) do
    t[p.key] = {picture = {color = {p.r, p.g, p.b, 255}}}
  end
  return t
end

local function has(list, want)
  for _, v in ipairs(list or {}) do
    if v == want then return true end
  end
  return false
end

local function run()
  local s = hafen.ui():sheet()

  local ok, err = pcall(function() dress(s) end)
  check(ok, "all five HUD plate keys take a picture", err)

  -- Each key answers with ITS OWN art. Five distinct colours are what makes that checkable at all: a key
  -- that fell through to a neighbour, or to nothing, reads back the wrong pair here.
  local wrong = nil
  for _, p in ipairs(PLATE) do
    local g = s:rule(p.key):picture()
    if not (g and g.color and (g.color.r == p.r) and (g.color.g == p.g) and (g.color.b == p.b)) then
      wrong = wrong or (p.key .. ": " .. rgb(g and g.color))
    end
  end
  check(wrong == nil, "each of the five reads back its own picture, so no key inherits another's", wrong)

  -- The claim that they are SITE keys rather than tree keys that match nothing. A site is where the client
  -- draws, so it lays out no widget, and the refusal has to name the site itself.
  local placed = nil
  for _, p in ipairs(PLATE) do
    local put, perr = pcall(function() s:rule(p.key):position(4, 4) end)
    perr = put and "<no error>" or tostring(perr)
    if put or not (perr:find("render site (\"" .. p.key .. "\")", 1, true)
                   and perr:find("Name the widget instead", 1, true)) then
      placed = placed or (p.key .. ": " .. perr)
    end
  end
  check(placed == nil, "all five are site keys: a position on each is refused naming the site and the fix",
        placed)

  -- ...and the other half of being a site: the role classifies no WIDGET, so a key that had quietly stayed
  -- a tree key would be matching something here instead of nothing.
  local matched = nil
  for _, p in ipairs(PLATE) do
    local n = #hafen.ui():all(p.key)
    if n ~= 0 then matched = matched or (p.key .. ": " .. n) end
  end
  check(matched == nil, "and none of the five classifies a widget: a site is a place, not a thing", matched)

  -- A plate is a whole picture, so the properties that fill and frame a box have nothing to do here. They
  -- are ACCEPTED and inert rather than refused -- the doctrine every unappliable property follows.
  local pok, perr = pcall(function() s:rule("hud.belt"):padding(6) end)
  local pd = pok and (s:rule("hud.belt"):padding() or {}) or {}
  check(pok and (pd.l == 6) and (pd.t == 6) and (pd.r == 6) and (pd.b == 6),
        "a padding on a plate key is accepted and reads back, and is inert rather than refused",
        pok and (tostring(pd.l) .. "," .. tostring(pd.t)) or tostring(perr))

  -- The picture parser these five rest on, duplicated rather than assumed: exactly one spelling, and never
  -- a list, because layers are what a bg is painted in and a plate under a plate could not be seen.
  refuses("a picture naming two spellings at once is refused",
          function() s:rule("hud.belt"):picture{color = {1, 2, 3}, res = "gfx/hud/hb-main"} end,
          "not several")
  refuses("an ARRAY of surfaces is refused: a plate is the whole picture, never a stack of them",
          function() s:rule("minimap.frame"):picture{{color = {1, 2, 3}}, {color = {4, 5, 6}}} end,
          "ONE surface")

  -- The DATA door on the same five keys, so a theme.json dresses the HUD: the whole sheet at once,
  -- replacing what the setters above wrote.
  s:load(asData())
  s:install()
  local i = s:info()
  local named = i.installed
  for _, p in ipairs(PLATE) do
    named = named and has(i.rules, p.key)
  end
  check(named, "a picture on all five loaded from DATA installs, and the sheet names every one of them",
        tostring(i.installed) .. " / " .. tostring(#i.rules))

  s:drop()
  check(s:info().installed == false, "dropping the sheet un-installs it, so every plate falls back",
        tostring(s:info().installed))

  s:load(asData())
  s:install()
  manualCheck("look along the bottom of the screen, then click a menu button and the middle of the purple"
              .. " where the minimap was",
              "five FLAT COLOURS in place of the client's own carved art: the belt RED (200,40,40), the map"
              .. " buttons' plate GREEN (40,160,60) bottom left, the menu buttons' plate BLUE (40,60,200)"
              .. " bottom right, the search button's plate ORANGE (230,150,30) beside it, and the minimap's"
              .. " frame PURPLE (160,40,200) -- with the minimap HIDDEN BEHIND that last one, this client"
              .. " drawing the frame OVER the map it frames and a flat colour having no transparent centre;"
              .. " every belt square, number and button still drawn ON TOP of its plate and still where it"
              .. " was, the menu button still toggling its window and the click on the purple still walking"
              .. " your character, a rule having changed the paint and nothing else. If your belt is the"
              .. " F-key one rather than the numbered one it has no plate to replace and stays as it is")
  manualCheck("type :reload and look again",
              "all five back to the client's own art, with nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-13", run)   -- the only way in: a suite does not start itself
