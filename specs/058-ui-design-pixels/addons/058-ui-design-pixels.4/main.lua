-- 058.4 — a control keeps the height its art gives it, and a container packs. Self-checking suite.
--
-- 058.1-3 made every number hafen.ui takes or gives a DESIGN pixel. This task removes the last number an
-- addon still had to find out for itself: a control's own height. It is a fact of the client's pictures --
-- a button's bottom border is drawn at the bottom of the button's art -- so widget:size(w), one number,
-- sets the width and leaves the height to that art, and a two-number write UNDER it is refused naming both
-- the number and this arity. Its other half is widget:pack(), which now sizes a bare widget to its content
-- as well as a window's chrome, so a container's box is never added up by hand either.
--
-- THE ONE HARD-CODED NUMBER IS THE POINT. A plain button is 24 design pixels tall on every client, at every
-- interface scale, because its art is loaded at the artist's own pre-scale size and multiplied once. So the
-- suite writes 24 down and asserts it -- and running this at 1.0 and again at 1.5 must print the SAME
-- summary, differing only in the scale on the [info] line. That is what makes the second run a proof.
--
-- WHAT NEEDS A FRAME CANNOT BE ASSERTED IN A STRAIGHT LINE: the packed window's own canvas is read back
-- through its Draw callback, so the suite builds, waits out a beat, and scores what came back.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, sends nothing to the server. Every
-- widget it builds is its own, and all but the one window the [manual] line asks you to look at are gone
-- before the summary prints.

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

-- A refusal is a check: the call must fail, and fail SAYING every one of the things the author needs.
local function refuses(what, fn, ...)
  local want = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local said = not ok
  for _, s in ipairs(want) do
    said = said and (err:find(s, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(a, b) return tostring(a) .. "," .. tostring(b) end

-- The client's own art, in design pixels: the same integers on every client at every interface scale.
local BTN_H, ENTRY_H = 24, 20
-- The rows this suite packs: three buttons, one column wide, on a 30 px grid. Their bottom-right corner --
-- and so the content box a pack must land on -- is the design SUM, computed here and nowhere else.
local COL_W, ROW = 120, 30
local SUM_W, SUM_H = COL_W, (2 * ROW) + BTN_H
-- ...and the box the window is given BEFORE it is packed, so the shrink has a known distance to travel.
local BOX_W, BOX_H = 300, 220

local win, box                    -- the packed window (left standing) and the bare packed widget
local drawW, drawH                -- ev:w()/ev:h() inside the packed window's Draw: its canvas, after pack
local outerBefore, outerAfter     -- the window's OUTER box either side of the pack

local function teardown()
  if box then box:destroy(); box = nil end
end

local function build()
  -- 1-3. THE ARITY THIS FEATURE EXISTS FOR. One number in, two numbers back: the width is the addon's and
  --      the height is the art's. The dropdown says the same thing without naming a number at all -- the
  --      width moves twice and the height it was never given does not.
  local b = hafen.ui():button():size(80):text("Go")
  eq("a button given only a width keeps its art's height", xy(b:size().x, b:size().y), xy(80, BTN_H))
  b:destroy()

  local e = hafen.ui():entry():size(140)
  eq("a text field, the same way", xy(e:size().x, e:size().y), xy(140, ENTRY_H))
  e:destroy()

  local d = hafen.ui():dropdown():size(120)
  local dh = d:size().y
  d:size(90)
  eq("a dropdown's width moves and its art's height does not", xy(d:size().x, d:size().y), xy(90, dh))

  -- 4-7. THE REFUSALS. A box the art will not fit in is refused rather than clamped or clipped, and the
  --      message carries the two things the author cannot see: the number, and the arity that means "you
  --      do not have to know it". The dropdown is the ROADMAP's own :size(w, h) defect, closing here.
  local b2 = hafen.ui():button()
  refuses("a button under its art's height is refused, naming 24 and :size(w)",
          function() b2:size(80, 4) end, "24 design px tall", "widget:size(w)")
  b2:destroy()
  refuses("...and a dropdown the same way (the 040 defect)",
          function() d:size(80, 4) end, "design px tall", "widget:size(w)")
  d:destroy()
  local c = hafen.ui():check():text("x")
  refuses("...and a checkbox, whose box is its own tick art",
          function() c:size(60, 2) end, "widget:size(w)")
  c:destroy()

  -- A SURFACE HAS NO ART TO ASK, so the one-number arity has nothing to answer with: it refuses naming the
  -- two-number write and the pack below. The client's own root is the same answer for the same reason.
  local w2 = hafen.ui():window()
  refuses("a window has no art, so :size(w) refuses naming :size(w, h) and :pack()",
          function() w2:size(160) end, "widget:size(w, h)", "widget:pack()")
  w2:destroy()
  refuses("...and so does a widget the client built",
          function() hafen.ui():root():size(160) end, "widget:size(w, h)")

  -- 8. PACK, BARE. A widget that is not a window sizes itself to its content too -- the guard that made
  --    this a no-op is gone -- and with no chrome around it the answer is the design sum exactly.
  box = hafen.ui():widget():position(40, 320)
  hafen.ui():button():parent(box):position(0, 0):size(COL_W):text("only row")
  box:pack()
  eq("a bare widget packs to its content", xy(box:size().x, box:size().y), xy(COL_W, BTN_H))

  -- 9-10. PACK, WINDOW. Three rows of controls, none of them given a height, in a window deliberately far
  --       too big for them. The pack must travel exactly the distance between the box it was given and the
  --       sum of the rows -- read once through the window's OUTER box (where the chrome's own margins
  --       cancel out of the difference) and once through the CANVAS its Draw is handed, which after a pack
  --       is the content box itself.
  win = hafen.ui():window():title("058.4"):position(60, 120)
  win:on("Close", function() if win then win:destroy(); win = nil end end)
  win:on("Draw", function(ev)
    drawW, drawH = ev:w(), ev:h()
    local g = ev:g()
    g:color(255, 200, 0)
    g:rect(0, 0, ev:w(), ev:h())
  end)
  for i, cap in ipairs({ "one", "two", "three" }) do
    hafen.ui():button():parent(win):position(0, (i - 1) * ROW):size(COL_W):text(cap)
  end
  win:size(BOX_W, BOX_H)
  outerBefore = win:size()
  win:pack()
  outerAfter = win:size()
end

local function score()
  local dx = outerBefore.x - outerAfter.x
  local dy = outerBefore.y - outerAfter.y
  -- One design pixel of tolerance, and only here: an outer box carries the chrome's own margins, which are
  -- rounded in DEVICE pixels and are not generally a whole number of design ones. The canvas check below
  -- is the exact one.
  check((math.abs(dx - (BOX_W - SUM_W)) <= 1) and (math.abs(dy - (BOX_H - SUM_H)) <= 1),
        ("a window's pack shrinks it by exactly the rows it holds (%s)")
          :format(xy(BOX_W - SUM_W, BOX_H - SUM_H)), xy(dx, dy))
  eq("...and its canvas is then the design sum of those rows", xy(drawW, drawH), xy(SUM_W, SUM_H))

  teardown()
  hafen.log():write(("[info] interface scale in force %s -- a button is %d design px tall at every one of them")
                    :format(tostring(hafen.ui():scale()), BTN_H))
  manualCheck("look at the 058.4 window: three buttons, none of them given a height",
              "every bottom border drawn WHOLE, no two rows touching, and the yellow trace on the window's"
              .. " content edge with the third button's foot on it -- at 1.0 and at 1.5 alike."
              .. " Close it with its x when done")
  manualCheck("now run :stockfilter and :timers, at this same scale",
              "both read exactly as they do at 1.0, only larger: no clipped button, no overlapping row,"
              .. " nothing sitting off its column")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0          -- so a re-run through :t058-4 reports its own counts
  drawW, drawH, outerBefore, outerAfter = nil, nil, nil, nil
  if win then win:destroy(); win = nil end
  teardown()

  build()
  -- One beat, because a draw callback runs when the client draws and not when this function asks it to.
  hafen.timer():after(0.6, score)
end

hafen.slash():register("t058-4", run)   -- the only way in: a suite does not start itself
