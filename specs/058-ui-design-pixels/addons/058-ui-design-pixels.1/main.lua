-- 058.1 — the unit at the widget boundary: hafen.ui measures in design pixels. Self-checking suite.
--
-- The claim is one sentence: a number this API takes is the number it gives back, whatever the user's
-- interface scale. Until now every coordinate and every size crossed as a DEVICE pixel, so :size(100, 40)
-- was 100x40 on an unscaled client and 100x40 of somebody else's pixels on a scaled one -- and the round
-- trip, :size(w, h) followed by :size(), came back a third pair. That round trip is therefore what almost
-- every line below asserts, on the three kinds of widget the verbs answer on: a surface this addon paints,
-- a control the client draws for it, and a widget the client itself built.
--
-- THE CROSS-SCALE HALF CANNOT BE ASSERTED IN ONE RUN. The client reads its scale once, at startup, so no
-- program can change it and look again -- which is why the proof is a number that must come out the SAME on
-- two runs at two scales: a bare button's height, which the button takes from its own art and never from
-- anything this addon says. 24 design pixels, at 1.0 and at 1.5 alike. The [info] line prints it beside the
-- scale in force, and the [manual] line asks for the two runs.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, destroys every widget it builds and
-- restores every client widget it touches through the nil arity.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
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

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end

-- The pair a widget stands at and the box it stands in, as one string: the two questions this feature makes
-- one question, so they are asserted as one answer.
local function box(w) return xy(w:size()) .. " at " .. xy(w:position()) end

-- The height a plain button takes from its own art, in design pixels: the button's edge image is 96 px tall
-- and declares a scale of 4, so the height is 24 wherever it is drawn. Written out because a suite that
-- asked the API for it would be asserting that a number equals itself.
local ART_H = 24

-- Is `w`, or any widget above it, the surface `want`? What hafen.ui():at() hands back is the DEEPEST widget
-- at the point, and a window's own chrome is a child of it, so the walk up is the honest comparison.
local function within(w, want)
  while w do
    if w == want then return true end
    w = w:parent()
  end
  return false
end

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t058-1 reports its own counts

  -- 1. A SURFACE this addon paints. No chrome, so the box it reads back is the box it was given.
  local surf = hafen.ui():widget():size(100, 40):position(30, 20)
  eq("a surface reads back the pair it was given", box(surf), "100,40 at 30,20")

  -- 2. ...and :info() is those same two numbers, since it is one snapshot of the verbs above and not a
  --    second answer to the same question.
  eq("widget:info() answers that same pair",
     xy(surf:info().size) .. " at " .. xy(surf:info().pos), "100,40 at 30,20")

  -- 3. AT THE ROOT, :position() and :rootPos() are one space and therefore one pair. That is the check that
  --    fails the moment a read converts and its sibling does not.
  eq("...and rootPos() is that space too, on a surface hanging off the root", xy(surf:rootPos()), "30,20")

  -- 4. A CONTROL the client draws. The same two writes, on a widget whose class and whose art are the
  --    client's own rather than this addon's.
  local btn = hafen.ui():button():text("Design px"):size(100, 40):position(30, 20)
  eq("a control reads back the pair it was given", box(btn), "100,40 at 30,20")

  -- 5. THE ART'S OWN NUMBER, and the one that carries across the two runs: a bare button is given no
  --    height at all, so what it reads back is its picture measured in design pixels.
  local bare = hafen.ui():button()
  eq("a bare button's height is its art's, the same integer at every scale", bare:size().y, ART_H)

  surf:destroy()
  btn:destroy()
  bare:destroy()

  -- 6. THE HIT TEST IS THAT SAME SPACE. The probe is computed from the two numbers this addon WROTE and
  --    the box it READ back, so a hit test in any other unit lands somewhere else -- and it lands further
  --    away the further from the origin the window sits. Aimed at the middle rather than near an edge: a
  --    window's frame corner is deliberately not part of its hit area, so a point 5 px inside its box is
  --    outside the window itself and would resolve to whatever is behind it. The three widgets above are
  --    destroyed first, so nothing of this addon's is left for it to resolve to instead.
  local wx, wy = 200, 150
  local win = hafen.ui():window():title("058.1"):size(100, 40):position(wx, wy)
  local ws = win:size()                                     -- the OUTER box, chrome included
  local hx, hy = wx + math.floor(ws.x / 2), wy + math.floor(ws.y / 2)
  check(within(hafen.ui():at(hx, hy), win),
        "hafen.ui():at(x, y) finds the window standing at those very coordinates",
        ("at %d,%d -> %s"):format(hx, hy, tostring(hafen.ui():at(hx, hy))))

  -- 7. ...and so is the pointer. `==` is identity on an interned Widget, so this says the pair the mouse
  --    reports and the pair the hit test takes are one pair, rather than two that usually agree.
  local m = hafen.ui():mouse()
  check(hafen.ui():at(m:x(), m:y()) == m:over(),
        "the pointer and the hit test are one space: at(m:x(), m:y()) == m:over()",
        tostring(m:over()) .. " vs " .. tostring(hafen.ui():at(m:x(), m:y())))

  win:destroy()

  -- 8. A WIDGET THE CLIENT BUILT, which is the case the conversion could most easily miss: the write is a
  --    layer over the client's own geometry, and it has to land in the same unit the read answers in. The
  --    grid takes the size and its enclosing window the position, because a window is written by its
  --    CONTENT size and reads back its outer box -- one number in, another out, by design.
  local grid = hafen.ui():inventory()
  if grid == nil then
    hafen.log():write("[fail] no HUD: run this in-world -- checks 8 and 9 need the client's own widgets")
  else
    local wnd = grid:parent()
    local wasSize, wasPos = grid:size(), wnd:position()
    grid:size(100, 40)
    wnd:position(30, 20)
    eq("the client's own widgets read back what they were given",
       xy(grid:size()) .. " at " .. xy(wnd:position()), "100,40 at 30,20")

    -- 9. ...and this addon's layer comes off exactly, leaving the client's own numbers as they were found.
    grid:size(nil)
    wnd:position(nil)
    eq("...and the undo leaves the client's own numbers untouched",
       xy(grid:size()) .. " at " .. xy(wnd:position()), xy(wasSize) .. " at " .. xy(wasPos))
  end

  -- 10. THE SCALE IS A READ. It is not a unit and nothing multiplies by it -- the write door is the user's
  --     own setting, and this one names it rather than quietly becoming a second way to say it.
  refuses("hafen.ui():scale(1.5) is refused, naming the setting that does write it",
          function() hafen.ui():scale(1.5) end, "interface():scale(v)")

  hafen.log():write(("[info] ui scale in force %s -- a bare button is %d design px tall")
                    :format(tostring(hafen.ui():scale()), ART_H))
  manualCheck("run this once at Interface scale 1.0 and once at 1.5, restarting the client between",
              "two identical blocks, bar the scale the [info] line prints and the client's own geometry the"
              .. " restore line echoes back -- every number this addon WROTE reads the same at both scales")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t058-1", run)   -- the only way in: a suite does not start itself
