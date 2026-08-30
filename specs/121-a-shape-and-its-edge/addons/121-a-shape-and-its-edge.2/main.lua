-- 121.2 — the base under a character wears its border again. Self-checking suite.
--
-- The suite before it wrote a fill and a border ONE AT A TIME. What the base under a character needs is
-- the pair COMPOSED on one patch -- a see-through ground under a solid line -- so that is what this one
-- asserts: written in a single chain, read off a single info() with each colour keeping its own alpha,
-- left alone by :alpha(), and costing the terrain nothing.
--
-- Three stages, because "no terrain work" is the one claim a synchronous run cannot make: the overlay
-- counters climb on a cut build, and a cut is built while a frame is drawn. So the patch is laid, frames
-- are let pass, the baseline is read, the chain is written, frames are let pass again, and only then are
-- the counters compared. Sampled inside one tick they could not have moved, and the assertion would prove
-- nothing.

local pass, fail, manual = 0, 0, 0

local FILL_A  = 70                       -- the ground you can see through
local EDGE_A  = 255                      -- the line standing solid round it
local WIDTH   = 1.5                      -- world units, and deliberately not a whole number

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

local function pair(i)
  local b = i.border
  return "tint " .. shows(i.tint) .. ", border " .. shows(b and b.color)
         .. " w " .. tostring(b and b.width) .. ", alpha " .. tostring(i.alpha)
end

local function square(at, half)
  return { at:offset(-half, -half), at:offset(half, -half), at:offset(half, half), at:offset(-half, half) }
end

local patch, baseMeshes, baseOutlines

-- ---------------------------------------------------------------- stage 1: lay the ground to measure from
local function lay()
  local patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- so a second :t121 measures a clean run
  local here = hafen.session():current():player():gob():position()
  local at = here:offset(14, 0)
  patch = patches:add(square(at, 6), at)
end

-- ------------------------------------------------- stage 2: the chain, between the two counter readings
local function writes()
  baseMeshes, baseOutlines = counters()

  -- ONE chain: the border is written on whatever the tint handed back, so each link has to be the patch.
  local afterTint = patch:tint({40, 200, 120, FILL_A})
  local afterEdge = afterTint:border({255, 255, 255, EDGE_A}, WIDTH)
  check((afterTint == patch) and (afterEdge == patch),
        "each setter of the chain handed the patch back",
        tostring(afterTint == patch) .. " then " .. tostring(afterEdge == patch))

  -- ONE info(): the fill and the edge come off the same snapshot, each colour keeping its own alpha.
  local i = patch:info()
  check(sameColor(i.tint, 40, 200, 120, FILL_A)
        and (i.border ~= nil) and sameColor(i.border.color, 255, 255, 255, EDGE_A)
        and (i.border.width == WIDTH),
        "one info() carries the fill and the border together, each colour at its own alpha and the width as written",
        pair(i))

  -- :alpha multiplies what is DRAWN. It writes neither of them, so both readings stand exactly as they were.
  local afterAlpha = patch:alpha(0.5)
  local j = patch:info()
  check((afterAlpha == patch) and (j.alpha == 0.5)
        and sameColor(j.tint, 40, 200, 120, FILL_A)
        and (j.border ~= nil) and sameColor(j.border.color, 255, 255, 255, EDGE_A)
        and (j.border.width == WIDTH),
        "alpha(0.5) multiplies what is drawn and writes neither the fill nor the border",
        pair(j))

  -- And the two readers agree about the pair the snapshot just showed.
  local c, w = patch:border()
  check(sameColor(c, 255, 255, 255, EDGE_A) and (w == WIDTH)
        and (j.border ~= nil) and sameColor(j.border.color, c.r, c.g, c.b, c.a) and (j.border.width == w),
        "border() hands back exactly the pair info().border carries", shows(c) .. " w " .. tostring(w))
end

-- ------------------------------------------------- stage 3: the counters again, once frames have been drawn
local function settle()
  local m, o = counters()
  check((m == baseMeshes) and (o == baseOutlines),
        "composing a fill and a border on one patch cost no terrain work",
        "meshes " .. tostring(baseMeshes) .. "->" .. tostring(m)
        .. ", outlines " .. tostring(baseOutlines) .. "->" .. tostring(o))

  hafen.virtual():patch():remove(patch)   -- taken up before the look, so the only shapes left are the bases
  patch = nil

  manualCheck("with session-manager enabled and two characters logged in, look at the ground under each",
              "a green disc with a solid GREEN line round it under the one on screen, and a fainter "
              .. "white disc with a solid WHITE line round it under the other")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function run()
  pass, fail, manual = 0, 0, 0
  lay()
  hafen.timer():after(1, function()      -- long enough for the :add cut to have been built and counted
    writes()
    hafen.timer():after(0.5, settle)     -- and long enough that a cut the chain had queued would have landed
  end)
end

hafen.console():on("t121", run)   -- the only way in: a suite does not start itself
