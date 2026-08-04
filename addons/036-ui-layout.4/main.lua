-- 036.4 — cost, docs, the theme, the boundary. Self-checking suite; see specs/addons/TESTING.md.
--
-- The task that closes feature E — and the whole skinning run — has two claims of its own, and both are
-- numbers rather than looks:
--
--   * A LAYOUT IS DATA, and unlike the chrome before it, it needs NO adapter. 035.4 proved a theme's frames can
--     come out of a file at the cost of three lines of Lua, because an image and a font's face are HANDLES. A
--     corner, an offset and a point are not handles: they are already the sheet's own shapes, so the table
--     hafen.json():parse hands back IS the sheet. This suite asserts that literally — it installs the parsed file
--     unchanged and then checks the geometry the file's own numbers predict, to the pixel, and that dropping it
--     restores what it found. The `theme` example gained its layout the same way, and its main.lua gained
--     nothing for it.
--
--   * LAYING WIDGETS OUT COSTS THE DRAW NOTHING. A position is a WRITE (D-088), enforced on events and never at
--     the draw, so a sheet that only lays widgets out opens no per-widget frame at all. Lua cannot read a pixel,
--     but it can read the rendered-text cache, whose key is (string, font, Fonts.gen()) — so ONE string drawn in
--     two windows answers the question: with a layout rule naming one of them the count stays at ONE key (a
--     drawing rule would make two), it does not move over ~60 frames while an anchor re-derives every tick, and
--     it becomes two the moment the very same rule also carries a font. That last line is the falsification
--     built in: without it, "still one key" could just mean the check cannot see anything.
--
-- The text-cache counter is pull-only, so the whole cost round above needs nothing armed. What the profiler adds
-- is the categorical half — zero draw/widget callbacks of ours while an anchored client paints — and the frame
-- median, and those are one [manual] line naming the switch when it is off.
--
-- READ-ONLY: declares no permissions, mutates no persistent state (it READS the profiling switch and never
-- writes it, and it never touches the `theme` addon's saved layout), destroys its probes and drops its sheet
-- before it finishes.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end
local function pt(x, y) return ("%d,%d"):format(x, y) end

-- ---- the file -------------------------------------------------------------------------------------

local FILE = "theme.json"
local ANCH, PLACED, PLAIN = "036.4 anchored", "036.4 placed", "036.4 plain"
local SEL_A = "window[title=" .. ANCH .. "]"
local SEL_P = "window[title=" .. PLACED .. "]"
local LINE = "036.4 layout cost probe"

local doc                                 -- the parsed theme.json — and, unchanged, the sheet
local wins = {}                           -- every window this suite stands up
local armManual = false

local function win(w)
  wins[#wins + 1] = w
  return w
end

local function killWins()
  for i = 1, #wins do
    if wins[i]:exists() then wins[i]:destroy() end
  end
  wins = {}
end

-- A probe window that DRAWS one line every frame — which is what puts a key in the rendered-text cache.
local function probe(title, y)
  return win(hafen.ui.window{
    title = title, size = { 190, 24 }, pos = { 4, y },
    onDraw = function(g) g:text(LINE, 6, 4) end,
  })
end

local function misses()
  return hafen.client:profiling():textcache().misses or 0
end

-- ---- cost, the armed half -------------------------------------------------------------------------

local ID = "036-ui-layout.4"
local SAMPLES = 15
local CORNERS = { "topleft", "topright", "bottomleft", "bottomright" }

local function median(t)
  table.sort(t)
  return t[math.ceil(#t / 2)] or 0
end

-- Milliseconds to three decimals. LuaJ 3.0.1's string.format IGNORES a float precision ("%.3f" prints the whole
-- double), so the rounding is done in integer arithmetic and printed with %d, which it does honour (035.4).
local function ms3(x)
  local t = math.floor((x * 1000) + 0.5)
  return ("%d.%03d"):format(math.floor(t / 1000), t % 1000)
end

-- Sample the widget tree's own per-frame cost (utick + draw). Spaced, so every sample is a different frame; the
-- timer itself is charged to this addon's `timers` bracket and never to `draw`, which is what keeps the zero the
-- callback check reports honest.
local function sample(n, acc, done)
  hafen.timer():after(0.06, function()
    local f = hafen.client:profiling():frame()
    if f and f.ui then acc[#acc + 1] = f.ui end
    if #acc >= n then done(acc) else sample(n, acc, done) end
  end)
end

local function ownRow()
  for _, r in ipairs(hafen.client:profiling():addons()) do
    if r.id == ID then return r end
  end
end

-- Measure with something to lay out, and stand up the scene rather than measuring whatever the maintainer has
-- open: the same four windows are on screen for BOTH halves, so the difference between the medians is the
-- layout layer and nothing else. They carry no onDraw of their own, which is what makes "0 callbacks" mean
-- "the anchors ran no Lua" rather than "this suite drew nothing" -- and it is why the round starts by
-- destroying the text probes: the check above needed windows that DRAW, and a callback counter cannot tell
-- one of those from an anchor doing something behind our back (the first in-game run counted exactly those 2).
local function costRound(after)
  local stock, rules = {}, {}
  killWins()
  hafen.ui.skin(nil)
  for i = 1, 4 do
    local title = "036.4 cost " .. i
    win(hafen.ui.window{ title = title, size = { 120, 60 }, pos = { 20 + (i * 26), 20 + (i * 26) } })
    rules["window[title=" .. title .. "]"] =
      { anchor = { to = "screen", at = CORNERS[i], offset = { (i * 12) - 40, (i * 12) - 40 } } }
  end
  sample(SAMPLES, stock, function()
    hafen.ui.skin(rules)
    local anchored = {}
    sample(SAMPLES, anchored, function()
      local row = ownRow()
      local paints = row and (row.calls.draw + row.calls.widgets)
      check(paints == 0,
            ("painting a client whose windows are anchored ran no Lua of ours: %s draw/widget callback%s over"
             .. " %d frames, while four anchors re-derived every tick"):format(tostring(paints),
                                                                               (paints == 1) and "" or "s",
                                                                               SAMPLES),
            row and paints or "no addons() row for this suite")
      local a, b = median(stock), median(anchored)
      check(b <= (a * 1.5) + 0.5,
            ("...and cost the widget tree what stock cost it: %s ms anchored vs %s ms stock, median of %d"
             .. " frames"):format(ms3(b), ms3(a), SAMPLES), ms3(b) .. " vs " .. ms3(a) .. " ms")
      hafen.ui.skin(nil)
      after()
    end)
  end)
end

-- ---- the close ------------------------------------------------------------------------------------

local function finish()
  killWins()
  hafen.ui.skin(nil)
  if armManual then
    manualCheck("tick Options ▸ Client ▸ \"Enable profiling\" and run ':t036-4' again",
                "two more [pass] lines: this addon runs 0 draw/widget callbacks while four anchored windows"
                .. " re-derive every tick, and the anchored widget tree costs what the stock one costs — both"
                .. " medians printed in the line")
  end
  manualCheck("enable the bundled `theme` addon (Options ▸ AddOns, then ':reload'), then ':theme on', open the"
              .. " inventory (Tab), the equipment window and the character sheet, and finish with ':theme off'",
              "on = the whole client themed live from theme.json, layout included: the inventory sits 8 px in"
              .. " from the screen's BOTTOM-RIGHT corner, the equipment window from its TOP-RIGHT, the character"
              .. " sheet is CENTRED, and each holds its corner while you resize the client window; off = every"
              .. " one of them back exactly where you had dragged it, frames and geometry alike")
  manualCheck("with ':theme on', run ':theme save', drag the inventory somewhere else, run ':theme save' again,"
              .. " then ':theme off' / ':theme on' — and finally ':theme forget'",
              "after the first save the window stops snapping back and is yours to drag (a pin is an absolute"
              .. " position, an anchor is re-derived); after the second save, off/on puts it back where YOU"
              .. " dragged it — and it survives a relog, on any character, because the addon keeps it in"
              .. " hafen.store. ':theme forget' drops the pins and the file's corners take over again")
  manualCheck("with the theme's layout still saved, log out to the character screen, DISABLE `theme` in the"
              .. " AddOns panel, and log back in with the same character",
              "the inventory, equipment, character sheet, kin and map windows are where YOU last dragged them by"
              .. " hand — never where the theme put them. The client's own store belongs to the user, and an"
              .. " addon's layout is a layer over it that leaves nothing behind")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---- cost, the unarmed half: does laying a widget out reach the DRAW? ------------------------------

local function costCache(after)
  killWins()
  hafen.ui.skin(nil)
  local m0 = misses()
  local a = probe(ANCH, 4)                      -- the file's anchor rule names THIS one
  probe(PLAIN, 34)                              -- ...and nothing names this one
  hafen.timer():after(0.7, function()
    -- 1. no sheet: the two windows draw the same string, so they share ONE key.
    eq("with no sheet the two windows draw one string under one key", misses() - m0, 1)
    hafen.ui.skin(doc.rules)                    -- ...a LAYOUT-ONLY sheet, straight out of the file
    local m1 = misses()
    hafen.timer():after(0.7, function()
      -- 2. THE CLAIM: the rule reached the widget (it moved) without reaching the draw. One key, still shared —
      --    a rule change bumps the generation once for everybody, which is that one; a DRAWING rule would open
      --    a frame over the window it names and make it two.
      eq("a layout-only sheet moves a window without reaching the draw: one string, still ONE key",
         misses() - m1, 1)
      check(a:style() ~= nil and a:style().anchor ~= nil,
            "...on a window that does resolve the rule — so the key is shared because nothing DREW, not because"
            .. " nothing matched", a:style())
      local m2 = misses()
      hafen.timer():after(1.1, function()
        -- 3. ...and nothing per frame. An anchor re-derives every tick over this whole second.
        eq("nothing per frame: ~60 frames with an anchor re-deriving each tick, nothing re-rasterised",
           misses() - m2, 0)
        -- 4. the falsification, built in: the same rule with a font in it DOES open a frame.
        hafen.ui.skin{ [SEL_A] = { anchor = { to = "screen", at = "bottomright", offset = { -8, -8 } },
                                   font = hafen.font("serif"):derive{ size = 18 } } }
        local m3 = misses()
        hafen.timer():after(0.7, function()
          eq("the very same rule with a font in it takes a second key — the check above can fail",
             misses() - m3, 2)
          hafen.ui.skin(nil)
          after()
        end)
      end)
    end)
  end)
end

-- ---- the run --------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t036-4 reports its own counts, not the last one's
  armManual = false
  killWins()
  hafen.ui.skin(nil)

  -- 1. the file, and what a layout looks like inside it.
  local asset = hafen.asset(FILE)
  eq("a theme is a file: it loads as a data asset", asset:type(), "data")
  doc = hafen.json():parse(asset:text())
  local an, pl = doc.rules[SEL_A].anchor, doc.rules[SEL_P]
  check((an.to == "screen") and (an.at == "bottomright") and (an.offset[1] == -8) and (an.offset[2] == -8)
        and (pl.pos[1] == 40) and (pl.pos[2] == 200) and (pl.size[1] == 180) and (pl.size[2] == 90),
        "a layout arrives as plain data — a named corner, a 1-indexed offset, and pos/size as {x, y} arrays",
        hafen.json():encode(doc.rules))

  -- 2. THE DELIVERABLE: the parsed file IS the sheet. A theme's chrome needs three lines of Lua because an
  --    image is a handle; a place is not a handle, so the layout half needs no adapter at all.
  local rsz = hafen.ui():root():size()
  local anchored = win(hafen.ui.window{ title = ANCH, size = { 150, 90 }, pos = { 12, 12 } })
  local placed = win(hafen.ui.window{ title = PLACED, size = { 100, 50 }, pos = { 12, 130 } })
  local baseA, baseP, baseS = xy(anchored:position()), xy(placed:position()), placed:size()
  check(pcall(hafen.ui.skin, doc.rules),
        "the table hafen.json():parse returned IS the sheet: a whole layout applies from a file, unmapped")

  -- 3. ...and the geometry is the file's own numbers, derived where it says derived and absolute where it
  --    says absolute.
  eq("the file's anchor holds a window 8 px in from the screen's bottom-right corner",
     xy(anchored:rootPos()), pt(rsz.x - anchored:size().x - 8, rsz.y - anchored:size().y - 8))
  eq("...and its pos and size are the point and the content box it names, to the pixel",
     xy(placed:position()) .. " " .. xy(placed:size()), "40,200 " .. pt(baseS.x + 80, baseS.y + 40))

  -- 4. and the whole thing reverts — the 035.2 method, one property along.
  hafen.ui.skin(nil)
  eq("dropping a layout that came from a file restores the exact numbers it found",
     xy(anchored:position()) .. " " .. xy(placed:position()) .. " " .. xy(placed:size()),
     baseA .. " " .. baseP .. " " .. xy(baseS))

  -- 5. PROFILES ARE AN ADDON'S BUSINESS (the feature builds none). A layout read back through :position() is a table
  --    of numbers: it survives JSON, and re-applying it puts the widget on the same pixel. That is the whole of
  --    what a profile store would have been, and hafen.store already holds tables like this one.
  hafen.ui.skin(doc.rules)
  local saved = { [SEL_P] = { x = placed:position().x, y = placed:position().y } }
  hafen.ui.skin(nil)
  local back = hafen.json():parse(hafen.json():encode(saved))
  hafen.ui.skin{ [SEL_P] = { pos = back[SEL_P] } }
  eq("a saved layout is plain data: read back, encoded, re-parsed and re-applied lands on the same pixel",
     xy(placed:position()), "40,200")
  hafen.ui.skin(nil)
  eq("...and dropping that one restores the user's numbers too", xy(placed:position()), baseP)

  -- 6. the cost round, then the armed one if the maintainer has the profiler on.
  costCache(function()
    if hafen.client:options():client():profiling() then
      costRound(finish)
    else
      armManual = true
      finish()
    end
  end)
end

-- ON DEMAND ONLY (D-085). A suite does not start itself, and running THIS command alone is the whole
-- verification of task 036.4: it reads its own theme.json, stands up its own windows, and drops every sheet
-- and destroys every window before it prints. Its round stages ~3.5 s, ~5.5 s with the cost half armed.
hafen.slash():register("t036-4", run)
