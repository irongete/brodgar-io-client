-- 065.10 — checkboxes, scrollbars and sliders, and the parts they draw. Self-checking suite.

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

-- One distinct art per key: the WHOLE and its PART are painted apart, so a part that inherited the whole's
-- art would read back the whole's here and fail. The mark's two faces are PICTURES rather than fills, and
-- that is what makes the state visible on screen as well as readable here: a flat colour fills the mark's
-- whole rectangle, which is the size of the box, and would bury the very box face it is meant to sit over.
-- Its resting face is the client's own box art -- a shape a tick never is -- so a mark that resolved the
-- wrong face is a wrong SHAPE rather than a colour nobody can name.
local ART = {
  {key = "checkbox",       r = 200, g =  40, b =  40, ck = { 40,  40, 200}},
  {key = "checkbox.mark",  res = "gfx/hud/chkboxs", ckres = "gfx/hud/chkmark"},
  {key = "scrollbar",      r =  30, g =  60, b = 120},
  {key = "scrollbar.knob", r = 240, g = 140, b =  40},
  {key = "slider",         r =  60, g = 120, b =  30},
  {key = "slider.knob",    r = 200, g =  60, b = 200},
}
local LINE = { color = {255, 255, 255, 255}, width = 1 }   -- a one-pixel white outline, on the two rails

local function surface(a)
  local t = a.res and { res = a.res } or { color = {a.r, a.g, a.b, 255} }
  if a.ck then t.checked = { color = a.ck } end
  if a.ckres then t.checked = { res = a.ckres } end
  return t
end

local function dress(s)
  for _, a in ipairs(ART) do
    s:rule(a.key):bg(surface(a))
  end
  s:rule("scrollbar"):border(LINE)
  s:rule("slider"):border(LINE)
  return s
end

local function rgb(c)
  if not c then return "<nil>" end
  return c.r .. "," .. c.g .. "," .. c.b
end

local function box(w)
  local c = w and w:size()
  return c and (c.x .. "x" .. c.y) or "<nil>"
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
  check(ok, "the six control keys each take a surface, and the two rails a line border as well", err)

  -- Each key answers with ITS OWN art. The part keys are the claim: one art on both would look identical on
  -- screen, so the colours are what tell them apart, here and in the manual line below.
  local wrong = nil
  for _, a in ipairs(ART) do
    local g = s:rule(a.key):bg()
    if not g then
      wrong = wrong or (a.key .. ": nothing")
    elseif a.res then
      if g.res ~= a.res then wrong = wrong or (a.key .. ": " .. tostring(g.res)) end
    elseif not (g.color and (g.color.r == a.r) and (g.color.g == a.g) and (g.color.b == a.b)) then
      wrong = wrong or (a.key .. ": " .. rgb(g.color))
    end
  end
  check(wrong == nil,
        "each of the six reads back its own art, so no part key inherits the whole's", wrong)

  -- The line border's own claims, duplicated rather than assumed: the two rails rest on them.
  local b = s:rule("scrollbar"):border() or {}
  check(b.color and (b.color.r == 255) and (b.color.g == 255) and (b.color.b == 255) and (b.width == 1)
        and (b.slice == nil) and (b.box == nil) and (b.image == nil) and (b.res == nil) and (b.mode == nil),
        "the rail's line reads back its colour and its width (255,255,255 / 1) and carries no art",
        rgb(b.color) .. " / " .. tostring(b.width))

  local cb, cm = s:rule("checkbox"):bg() or {}, s:rule("checkbox.mark"):bg() or {}
  check(cb.color and cb.checked and cb.checked.color and (cb.checked.color.b == 200)
        and (cb.checked.color.r ~= cb.color.r)
        and cm.res and cm.checked and cm.checked.res and (cm.checked.res ~= cm.res),
        "both checkbox keys carry a checked face beside the face it varies, and the two differ",
        rgb(cb.checked and cb.checked.color) .. " / " .. tostring(cm.checked and cm.checked.res))

  refuses("a state that does not exist is refused, naming the four that do",
          function() s:rule("checkbox"):bg{ color = {1, 2, 3}, ticked = { color = {4, 5, 6} } } end,
          "\"hover\", \"pressed\", \"disabled\", \"checked\"")
  refuses("a surface that names nothing is refused",
          function() s:rule("slider.knob"):bg{ at = "left" } end, "says nothing")

  -- Every one of the six is a SITE key: a site is where the client draws, so none of them lays out a widget.
  local placed = nil
  for _, a in ipairs(ART) do
    local put, perr = pcall(function() s:rule(a.key):position(4, 4) end)
    perr = put and "<no error>" or tostring(perr)
    if put or not (perr:find("render site (\"" .. a.key .. "\")", 1, true)
                   and perr:find("Name the widget instead", 1, true)) then
      placed = placed or (a.key .. ": " .. perr)
    end
  end
  check(placed == nil, "all six are site keys: a position on each is refused naming the site and the fix",
        placed)

  local win = hafen.ui():window():title("065.10"):size(260, 130):position(120, 120)
  local c   = hafen.ui():check():parent(win):position(10, 8):text("065.10"):value(false)
  local sl  = hafen.ui():slider():parent(win):position(10, 40):size(180, 20):range(0, 100):value(50)
  local sb  = hafen.ui():scrollbar():parent(win):position(230, 8):size(14, 110):range(0, 10):value(5)
  local was = {box(c), box(sl), box(sb)}
  local cz, lz, bz = c:size(), sl:size(), sb:size()
  check(cz and (cz.x > 0) and (cz.y > 0) and lz and (lz.x == 180) and bz and (bz.y == 110),
        "this suite drives one of each: a checkbox, a slider and a scrollbar",
        was[1] .. " / " .. was[2] .. " / " .. was[3])

  dress(s)
  s:install()
  local i = s:info()
  local named = true
  for _, a in ipairs(ART) do
    named = named and has(i.rules, a.key)
  end
  check(i.installed and named, "the sheet installs and names all six keys", tostring(i.installed))
  check((box(c) == was[1]) and (box(sl) == was[2]) and (box(sb) == was[3]),
        "a dressed control is the size it was: these keys paint, they never re-size",
        box(c) .. " / " .. box(sl) .. " / " .. box(sb))

  c:value(true)
  check(c:value() == true, "the checkbox toggles through the API and reads back set, so the checked face"
        .. " is the one its box and its mark resolve", c:value())

  s:drop()
  check((s:info().installed == false) and (box(c) == was[1]) and (box(sb) == was[3]),
        "dropping the sheet un-installs it and leaves the boxes untouched",
        tostring(s:info().installed) .. " " .. box(c))

  dress(s)
  s:install()
  manualCheck("open Options beside this suite's own window and tick one of its checkboxes; then look at the"
              .. " volume sliders, at the scrollbar of a long list (the Kin window, or Options' own keybind"
              .. " list), and at the HUD's own map and menu buttons in the corners",
              "every box-and-tick checkbox a RED square (200,40,40) that turns BLUE (40,40,200) when ticked,"
              .. " with the client's own TICK SHAPE drawn over that blue and never a filled box outline --"
              .. " the whole and the part are two arts, and the shape you see is the CHECKED face the mark"
              .. " resolves; every slider a solid GREEN rail (60,120,30) in a 1 px white outline with a"
              .. " MAGENTA thumb (200,60,200); every scrollbar a NAVY rail (30,60,120) outlined the same with"
              .. " an ORANGE thumb (240,140,40); each thumb still dragging where you grab it, each checkbox"
              .. " still toggling, and every caption beside them where it was. And the HUD's map and menu"
              .. " panels, and every dropdown's arrow, UNTOUCHED: a picture checkbox is its picture, and this"
              .. " key never reaches one")
  manualCheck("type :reload", "every checkbox, rail and thumb back to the client's own chains and boxes,"
              .. " with nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-10", run)   -- the only way in: a suite does not start itself
