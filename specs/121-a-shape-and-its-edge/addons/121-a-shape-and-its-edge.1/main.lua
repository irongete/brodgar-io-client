-- 121.1 — a patch takes a border. Self-checking suite.
--
-- Three stages, because the one claim a synchronous run cannot make is that a write cost no TERRAIN work:
-- the overlay counters climb on a cut build, and a cut is built while a frame is drawn. So the two patches
-- are laid, frames are let pass, the baseline is read, every border write is made, frames are let pass
-- again, and only then are the counters compared. Sampled inside one tick they could not move at all, and
-- the assertion would prove nothing.

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

-- A refusal is a check: the call must fail, and fail SAYING why. LuaJ prefixes a bridge error with
-- "@chunk.lua:189 " (a SPACE, not a colon), so the strip has to take both shapes.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function counters()
  local r = hafen.client():profiling():render()
  return r.overlayMeshes, r.overlayOutlines
end

local function sameColor(c, r, g, b, a)
  return (c ~= nil) and (c.r == r) and (c.g == g) and (c.b == b) and (c.a == a)
end

local function shows(c)
  if c == nil then return "nil" end
  return "{" .. tostring(c.r) .. "," .. tostring(c.g) .. "," .. tostring(c.b) .. "," .. tostring(c.a) .. "}"
end

local function square(at, half)
  return { at:offset(-half, -half), at:offset(half, -half), at:offset(half, half), at:offset(-half, half) }
end

local a, b, baseMeshes, baseOutlines

-- ---------------------------------------------------------------- stage 1: lay the ground to measure from
local function lay()
  local patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- so a second :t121 measures a clean run
  local here = hafen.session():current():player():gob():position()
  local left, right = here:offset(-14, 0), here:offset(14, 0)
  a = patches:add(square(left, 6), left)
  b = patches:add(square(right, 6), right)
end

-- ---------------------------------------------------- stage 2: every border write, between two counter reads
local function writes()
  baseMeshes, baseOutlines = counters()

  -- A fresh patch wears nothing, and says so in both places.
  local c0, w0 = a:border()
  check((c0 == nil) and (w0 == nil) and (a:info().border == nil),
        "a fresh patch reads no border, and its info() carries no border key", shows(c0))

  -- The write hands the patch back, and the read answers the colour keyed and the width it was given.
  local back = a:border({255, 140, 40, 200}, 2)
  local c1, w1 = a:border()
  check((back == a) and sameColor(c1, 255, 140, 40, 200) and (w1 == 2),
        "border(c, w) hands the patch back and reads back the colour keyed and the width given",
        shows(c1) .. " w " .. tostring(w1))

  local i1 = a:info().border
  check((i1 ~= nil) and sameColor(i1.color, 255, 140, 40, 200) and (i1.width == 2),
        "info().border is the same pair, as {color, width}",
        (i1 == nil) and "nil" or (shows(i1.color) .. " w " .. tostring(i1.width)))

  -- The read feeds the write: two:border(one:border()) is one expression.
  b:border(a:border())
  local c2, w2 = b:border()
  check(sameColor(c2, 255, 140, 40, 200) and (w2 == 2),
        "b:border(a:border()) reads back exactly what a holds", shows(c2) .. " w " .. tostring(w2))

  -- No width at all is the thinnest line the screen draws, which is 0.
  b:border({10, 20, 30})
  local _, w3 = b:border()
  check(w3 == 0, "a border written with no width reads back 0, the hairline", w3)

  -- Outside the range is brought into it, the way :scale and :alpha are -- never refused.
  b:border({10, 20, 30}, -5)
  local _, wlo = b:border()
  b:border({10, 20, 30}, 100000)
  local _, whi = b:border()
  check((wlo == 0) and (whi == 100),
        "a width outside the range is brought to its own end (0 and 100)",
        tostring(wlo) .. " and " .. tostring(whi))

  -- nil is the documented "no border", and it puts both readings back where they started.
  b:border(nil)
  local cn, wn = b:border()
  check((cn == nil) and (wn == nil) and (b:info().border == nil),
        "border(nil) clears it: the read is nil again and info() drops the key", shows(cn))

  refuses("a stylesheet's own border value is refused naming that out here a border is two arguments",
          function() a:border{box = "gfx/hud/wnd"} end, "TWO arguments")
  refuses("a colour that is not a table is refused naming both colour spellings",
          function() a:border("green") end, "{200, 210, 220}", "{r = 200, g = 210, b = 220")

  -- The feature's other half: the tint's own a is the FILL's opacity, and :alpha is still the whole patch's.
  a:tint({40, 200, 120, 70}):border({255, 255, 255}, 0)
  local i2 = a:info()
  check(sameColor(i2.tint, 40, 200, 120, 70) and (i2.border ~= nil)
        and sameColor(i2.border.color, 255, 255, 255, 255) and (i2.alpha == 1),
        "a translucent fill under an opaque line: each colour keeps its own alpha, :alpha() untouched",
        shows(i2.tint) .. " / " .. shows(i2.border and i2.border.color) .. " alpha " .. tostring(i2.alpha))

  b:tint({60, 120, 255, 70}):border({255, 255, 255}, 2)
end

-- ------------------------------------------------- stage 3: the counters again, once frames have been drawn
local function settle()
  local m, o = counters()
  check((m == baseMeshes) and (o == baseOutlines),
        "laying, colouring, widening and clearing a border cost no terrain work",
        "meshes " .. tostring(baseMeshes) .. "->" .. tostring(m)
        .. ", outlines " .. tostring(baseOutlines) .. "->" .. tostring(o))

  manualCheck("look at the GREEN patch", "a solid white line all the way round green ground you can see through")
  manualCheck("look at the BLUE patch, whose border is two world units",
              "a white band about a fifth of a tile across")
  manualCheck("zoom all the way out, watching the GREEN patch's hairline border",
              "still drawn, still about one pixel wide")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function run()
  pass, fail, manual = 0, 0, 0
  lay()
  hafen.timer():after(1, function()      -- long enough for the two :add cuts to have been built and counted
    writes()
    hafen.timer():after(0.5, settle)     -- and long enough that a cut the writes had queued would have landed
  end)
end

hafen.console():on("t121", run)   -- the only way in: a suite does not start itself
