-- 136.1 — a patch is many pieces, drawn as their union. Self-checking suite.
--
-- The claim: hafen.virtual():patch():add(ring, anchor) lays a patch of ONE piece and patch:piece() is the
-- collection of that patch's pieces, so a shape that is not convex is one patch with several rather than
-- several patches. Every check below reads that back through the API the task shipped: the count, the order,
-- the identity of the pieces handed back, the patch still being one handle, and the four refusals.
--
-- Run it with :t136. It clears the patches this addon laid before it starts, and leaves exactly ONE behind --
-- the L the manual line reads -- so a second run is the same run.

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

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: every call must fail, and every message must carry `needle`.
local function refuses(what, needle, fns)
  for i = 1, #fns do
    local ok, err = pcall(fns[i])
    if ok then
      check(false, what, "call " .. i .. " raised nothing")
      return
    end
    local msg = why(err)
    if not msg:find(needle, 1, true) then
      check(false, what, "call " .. i .. " said " .. msg)
      return
    end
  end
  check(true, what)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- A convex quad from two corners, in world units around `p`.
local function quad(p, x0, y0, x1, y1)
  return { p:offset(x0, y0), p:offset(x1, y0), p:offset(x1, y1), p:offset(x0, y1) }
end

-- A regular n-gon of radius r centred `cx, cy` from `p` -- n points, and so n edges.
local function ngon(p, cx, cy, r, n)
  local out = {}
  for i = 1, n do
    local a = (i - 1) * 2 * math.pi / n
    out[i] = p:offset(cx + (r * math.cos(a)), cy + (r * math.sin(a)))
  end
  return out
end

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  local me = s and s:player():gob()
  if not me then
    check(false, "the player's own gob is loaded", "not in the world")
    summary()
    return
  end
  local here = me:position()
  local patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- this addon's own, and only those
  local before = patches:count()

  -- One ring is one piece, and the piece reads back the ring it was laid with.
  local p = patches:add(quad(here, -9, -3, 9, 3), here)
  check(p:piece():count() == 1, "a patch laid from one ring holds one piece", p:piece():count())
  local one = p:piece():list()[1]
  local ring = one and one:info().ring
  check((one ~= nil) and one:exists() and (type(ring) == "table") and (#ring == 4),
        "the piece exists and its snapshot carries the four points it was laid with",
        (ring and #ring) or tostring(one))

  -- A second, overlapping piece goes into the SAME patch: the count moves, the order is the order laid,
  -- and the objects handed back are the very ones :add returned.
  local two = p:piece():add(quad(here, 3, -3, 9, 15))
  local list = p:piece():list()
  check((p:piece():count() == 2) and (#list == 2) and (list[1] == one) and (list[2] == two),
        "a second piece makes the count 2, and :list() hands the two back in the order they were laid",
        p:piece():count() .. " counted, " .. #list .. " listed")
  check((patches:count() == (before + 1)) and p:exists(),
        "the two pieces are ONE patch: the patch collection grew by one, not by two",
        patches:count() - before)

  -- ...and that one patch still does everything a one-piece patch does.
  -- 0.5 and not 0.4: the width crosses the bridge as a float, so a number that is not exact in binary
  -- comes back a hair off and a == would read as a defect where there is none.
  p:tint({40, 200, 120, 120}):border({255, 255, 255}, 0.5)
  local c, w = p:border()
  check(p:drawn() and (p:tint() ~= nil) and (type(c) == "table") and (c.r == 255) and (w == 0.5),
        "it draws, takes a tint and takes a border, exactly as a one-piece patch does",
        tostring(p:drawn()) .. " drawn, border width " .. tostring(w))

  -- The ring rules hold per piece, and each names itself.
  refuses("a concave ring is refused saying concave", "concave",
          { function()
              p:piece():add({ here:offset(0, 0), here:offset(8, 0), here:offset(4, 4),
                              here:offset(8, 8), here:offset(0, 8) })
            end })
  local ring40 = ngon(here, 0, 0, 6, 40)
  refuses("a ring of 40 points is refused saying at most 32, at both doors", "at most 32",
          { function() patches:add(ring40, here) end,
            function() p:piece():add(ring40) end })

  -- The budget is the PATCH'S: four 32-edge pieces are exactly the 128 it holds, and the next ring is not.
  local b = patches:add(ngon(here, 20, 0, 5, 32), here)
  b:piece():add(ngon(here, 21, 0, 5, 32))
  b:piece():add(ngon(here, 22, 0, 5, 32))
  b:piece():add(ngon(here, 23, 0, 5, 32))
  check(b:piece():count() == 4, "four 32-edge pieces fill one patch's budget exactly", b:piece():count())
  refuses("a piece past the patch's total is refused saying 128", "128",
          { function() b:piece():add(quad(here, 20, 10, 24, 14)) end })
  patches:remove(b)

  -- A piece is a region, not a picture of something, so a string has nothing to match.
  refuses("a string filter on the pieces names the two forms that work",
          "pass a function, or nothing for all of them",
          { function() p:piece():list("x") end,
            function() p:piece():count("x") end })

  manualCheck("look at the shape this run left lying under your character",
              "ONE green L under a white line round its outside only, and no line across the join in the"
              .. " middle where the two quads meet")
  summary()
end

hafen.console():on("t136", run)   -- the only way in: a suite does not start itself
