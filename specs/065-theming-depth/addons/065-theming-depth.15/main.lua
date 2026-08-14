-- 065.15 — glow. Self-checking suite.

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

local HALO = {40, 200, 255}       -- a cyan halo: unmistakable against the olive one every caption wears
local RADIUS = 4                  -- design pixels, which is what every distance in this vocabulary is

-- Three windows of this suite's own, and the difference between them is the whole demonstration: one named
-- by a tree key that carries a halo, one named by a tree key that carries a radius of ZERO, and one no rule
-- names at all.
local LIT, ZERO, NONE = "065.15 glow", "065.15 no halo", "065.15 unnamed"
local KLIT, KZERO = "window[title=" .. LIT .. "]", "window[title=" .. ZERO .. "]"

-- Is this the glow the suite wrote? A read hands back the shape the setter takes, so one predicate answers
-- for the rule read-back and for the resolved style alike -- which is half of what makes a read a write.
local function isHalo(g, radius)
  return g and g.color and (g.color.r == HALO[1]) and (g.color.g == HALO[2]) and (g.color.b == HALO[3])
     and (g.radius == radius)
end

local function shows(g)
  if not g then return "<nil>" end
  local c = g.color
  return (c and (c.r .. "," .. c.g .. "," .. c.b) or "<no colour>") .. " r" .. tostring(g.radius)
end

-- The values a glow cannot be, each with the words its refusal has to carry. One check for the lot: what is
-- being proved is that a half-said glow is refused rather than completed with a guess, and every refusal
-- names BOTH of the two things a glow is made of.
local NEEDS = {"color", "radius"}
local BAD = {
  {what = "{color = ..., radius = -1}", v = {color = HALO, radius = -1}, say = "cannot be negative"},
  {what = "{color = ...}",              v = {color = HALO},             say = "names no radius"},
  {what = "{radius = 4}",               v = {radius = RADIUS},          say = "names no colour"},
  {what = "{}",                         v = {},                         say = "names no colour"},
  {what = "{color = ..., radius = \"4\"}", v = {color = HALO, radius = "4"}, say = "expected a number"},
  {what = "{color = ..., colour = ...}", v = {color = HALO, colour = HALO}, say = "is not a glow property"},
}

-- The whole look as DATA, which is the door a theme.json comes through -- and the one install the manual
-- line reads: every caption in the client on the theme's halo, and the one window whose own rule says a
-- radius of zero with no halo at all, side by side under one command.
local function theme()
  return {
    ["window.title"] = {glow = {color = HALO, radius = RADIUS}},
    [KLIT]           = {glow = {color = HALO, radius = RADIUS}},
    [KZERO]          = {glow = {color = HALO, radius = 0}},
  }
end

local function run()
  local s = hafen.ui():sheet()

  -- The setter door, and the read that round-trips into it.
  s:rule("window.title"):glow{color = HALO, radius = RADIUS}
  check(isHalo(s:rule("window.title"):glow(), RADIUS),
        "a rule takes a colour and a radius and reads both back",
        shows(s:rule("window.title"):glow()))

  -- Silence is the answer that keeps the client's own halo, and it is not the same answer as a radius of 0.
  check(s:rule("heading"):glow() == nil,
        "a key with no glow rule resolves none, so the client's own blur is what stands",
        shows(s:rule("heading"):glow()))

  -- ...and zero is a VALUE: the one way to say letters with nothing behind them.
  s:rule("heading"):glow{color = HALO, radius = 0}
  check(isHalo(s:rule("heading"):glow(), 0),
        "radius 0 is a value rather than an omission, and reads back as one",
        shows(s:rule("heading"):glow()))

  -- The two decorators an embossed surface wears are independent in both directions: what fills the letters
  -- and what sits behind them are two properties, and neither implies the other.
  s:rule("button"):emboss(false)
  local ge, eg = s:rule("button"):glow(), s:rule("window.title"):emboss()
  check((ge == nil) and (eg == nil),
        "glow and emboss are independent: naming one on a key names nothing about the other",
        shows(ge) .. " / " .. tostring(eg))

  local badly = nil
  for _, b in ipairs(BAD) do
    local ok, err = pcall(function() s:rule("tooltip"):glow(b.v) end)
    err = ok and "<no error>" or tostring(err)
    local said = (not ok) and err:find(b.say, 1, true)
    for _, n in ipairs(NEEDS) do
      said = said and err:find(n, 1, true)
    end
    if not said then badly = badly or (b.what .. " -> " .. err) end
  end
  check(badly == nil, "the six values a glow cannot be are each refused, naming a colour AND a radius", badly)

  -- A key the client draws no halo at accepts it and does nothing -- the doctrine every unappliable property
  -- here follows, rather than a refusal that would make a whole-sheet theme unwritable.
  local iok, ierr = pcall(function() s:rule("inventory.slot"):glow{color = HALO, radius = RADIUS} end)
  check(iok and isHalo(s:rule("inventory.slot"):glow(), RADIUS),
        "a glow on a key that blurs nothing is accepted and inert, not refused",
        iok and shows(s:rule("inventory.slot"):glow()) or tostring(ierr))

  local lit = hafen.ui():window():title(LIT):size(230, 70):position(80, 80)
  local zero = hafen.ui():window():title(ZERO):size(230, 70):position(80, 190)
  local none = hafen.ui():window():title(NONE):size(230, 70):position(80, 300)

  s:load(theme()):install()
  check(s:info().installed and isHalo(s:rule("window.title"):glow(), RADIUS),
        "a glow loaded from DATA installs and reads back what the file said",
        tostring(s:info().installed) .. " / " .. shows(s:rule("window.title"):glow()))

  -- THE RESOLVED STYLE, which is what the draw reads: the fold carries the value to the window its key names
  -- and nothing to the window no key names, which is the whole claim about a theme silent on halos.
  check(isHalo((lit:style() or {}).glow, RADIUS),
        "the named window's RESOLVED style carries the colour and the radius",
        shows((lit:style() or {}).glow))
  check(isHalo((zero:style() or {}).glow, 0),
        "...and the window whose rule says a radius of 0 resolves exactly that, not nothing",
        shows((zero:style() or {}).glow))
  local nst = none:style()
  check((nst == nil) or (nst.glow == nil),
        "the window no rule names resolves no glow, so its caption keeps the client's own halo",
        shows(nst and nst.glow))

  s:drop()
  check((s:info().installed == false) and ((lit:style() == nil) or (lit:style().glow == nil)),
        "dropping the sheet un-installs it and leaves every window resolving nothing",
        tostring(s:info().installed))

  s:load(theme()):install()
  manualCheck("look at this suite's three windows, and at any client window with a title bar beside them",
              "every caption -- the client's own, \"065.15 glow\" and \"065.15 unnamed\" -- sitting on a CYAN"
              .. " halo (40,200,255) about 4 px wide instead of the client's olive one, the gold carved"
              .. " letters over it unchanged and still legible; and \"065.15 no halo\" with NO halo behind"
              .. " its caption at all, the letters straight on the title plate, which is what a radius of 0"
              .. " says and what silence cannot; every frame, plate and close button where it was")
  manualCheck("type :reload and look at the client's windows again",
              "every caption back on the client's own olive halo, with nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-15", run)   -- the only way in: a suite does not start itself
