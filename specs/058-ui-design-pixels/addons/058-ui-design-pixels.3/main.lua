-- 058.3 — the stylesheet says design pixels too. Self-checking suite.
--
-- 058.1 made every number a widget takes or gives a design pixel and 058.2 did the same for the surface it
-- paints. A SHEET is the third door onto those same numbers: `position`, `size`, an anchor's `offset`, `pad`,
-- a border's four slice insets, a list's row height and a grid's cell box. The claim is that they are all one
-- space with the verbs beside them -- so every check below asks a number two ways and demands one answer:
-- what the rule says against what the widget does, and what the sheet stores against what it reads back.
--
-- THE ONE CHECK THAT NEEDS A FRAME is the themed window's geometry: a window's chrome is swapped on its own
-- tick, not when :install() returns, so the suite installs the theme and scores after a beat. Everything else
-- is synchronous, because a layout rule has moved what it names by the time :install() comes back.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, sends nothing to the server. It styles and
-- lays out its OWN two windows and nothing of the client's; the theme is left in force at the end so the
-- [manual] line has something to look at, and closing the windows -- or :reload -- ends it.

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

local function xy(a, b) return tostring(a) .. "x" .. tostring(b) end       -- a size
local function pt(a, b) return tostring(a) .. ", " .. tostring(b) end      -- ...and a place

-- Every number this suite writes is a design pixel, and every number it expects is arithmetic on these.
local WIN_W, WIN_H   = 300, 220        -- the box the VERB gives the sheet's window, and its content forever after
local RULE_W, RULE_H = 260, 180        -- ...and the box a RULE gives it instead, on the same window and a twin
local AT_X, AT_Y     = 40, 200         -- where the sheet puts it
local HOME_X, HOME_Y = 60, 120         -- ...and where it was found, which dropping the sheet must give back
local TWIN_X         = 420             -- the twin, side by side with it for the [manual] line
local INSET, PAD     = 8, 8            -- the theme's slice insets and its pad: the frame is INSET + PAD per side
local EDGE           = 8               -- the anchor's offset in from the screen's bottom-right corner
local ROWH           = 18              -- a list's row height
local CELL           = 32              -- ...and the stock cell box of a bare grid, which no rule names at all
local IMG            = 24              -- the suite's own border image is 24x24 of its own -- design -- pixels
-- An odd interface scale makes a design pixel a fraction of a device one, so a sum of them can land a device
-- pixel either side of the exact answer. One design pixel of slack, and no more: the bug this suite exists to
-- catch is a whole conversion missing, which is worth a third of the number at 1.5 and never one pixel.
local SLACK          = 1

local SEL_A     = "window[title=Sheet 058-3]"
local SEL_B     = "window[title=Stock 058-3]"
local SEL_NONE  = "window[title=Nothing 058-3]"   -- matches nothing: where the refusals are written

local win, twin, list, grid, art
local sheet, rLayout, rAnchor, rTheme, rNone
local home, homeSz                     -- where and how big the window was BEFORE any rule named it

local function near(got, want)
  return (type(got) == "number") and (math.abs(got - want) <= SLACK)
end

local function teardown()
  if list then list:destroy(); list = nil end
  if grid then grid:destroy(); grid = nil end
end

local function build()
  art  = hafen.asset():get("border.png")
  win  = hafen.ui():window():title("Sheet 058-3"):size(WIN_W, WIN_H):position(HOME_X, HOME_Y)
  twin = hafen.ui():window():title("Stock 058-3"):size(RULE_W, RULE_H):position(TWIN_X, HOME_Y)
  win:on("Close",  function() if win  then win:destroy();  win  = nil end end)
  twin:on("Close", function() if twin then twin:destroy(); twin = nil end end)
  -- Building-only setters, so each is written in the statement that builds its control (spec 040 decision G).
  list = hafen.ui():list():rowHeight(ROWH):rows{"one", "two"}:position(TWIN_X, 380)
  grid = hafen.ui():grid():position(TWIN_X, 420)
  home, homeSz = win:position(), win:size()
  sheet = hafen.ui():sheet()
  -- A sheet is a document that outlives a run of this suite, so a re-run starts from an empty one: every rule
  -- below is written from scratch, and the theme the previous run left standing says nothing any more.
  sheet:drop()
  sheet:rule(SEL_A):remove()
  sheet:rule(SEL_B):remove()
  sheet:rule(SEL_NONE):remove()
end

-- The themed window's outer box, read after the chrome has had its tick. The whole point of the number: a
-- window's frame is its content plus the room its own art asks for, and every term of that is design pixels.
local function scoreThemed()
  local sz = win and win:size()
  check(sz ~= nil and near(sz.x, WIN_W + (2 * (INSET + PAD)))
                 and near(sz.y, WIN_H + (2 * (INSET + PAD))),
        "a themed window measures its content plus its own slice and pad, in design px ("
        .. xy(WIN_W + (2 * (INSET + PAD)), WIN_H + (2 * (INSET + PAD))) .. ")",
        sz and xy(sz.x, sz.y))

  hafen.log():write(("[info] ui scale in force %s -- the border image is %dx%d of its own pixels, sliced %d")
                    :format(tostring(hafen.ui():scale()), IMG, IMG, INSET))
  manualCheck("look at the two windows: \"Sheet 058-3\" wears the theme, \"Stock 058-3\" beside it is stock",
              "the themed frame reads at the WEIGHT of the stock chrome, not thinner -- at 1.5 its border is"
              .. " half again as thick as at 1.0. Close both windows with their x when done")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0          -- so a re-run through :t058-3 reports its own counts
  if win  then win:destroy();  win  = nil end
  if twin then twin:destroy(); twin = nil end
  teardown()
  build()

  -- ---- the layout half: what a rule says, and what the widget then does ----------------------------------
  rLayout = sheet:rule(SEL_A):position(AT_X, AT_Y):size(RULE_W, RULE_H)
  rAnchor = sheet:rule(SEL_B):anchor{ to = "screen", at = "bottomright", offset = {-EDGE, -EDGE} }
  sheet:install()

  local at = win:position()
  eq("a rule's position puts the window at the design pair it names",
     pt(at.x, at.y), pt(AT_X, AT_Y))

  -- The sheet STORES what the rule said, so both halves read back as written on every client -- which a rule
  -- converted at parse time could not do: at 1.5 it would answer 60, 300 and 390x270.
  local st = win:style()
  check(st ~= nil and st.position ~= nil and st.size ~= nil
        and st.position.x == AT_X and st.position.y == AT_Y
        and st.size.x == RULE_W and st.size.y == RULE_H,
        "...and the rule reads its own position and size back in design px ("
        .. pt(AT_X, AT_Y) .. " / " .. xy(RULE_W, RULE_H) .. ")",
        st and st.size and (pt(st.position.x, st.position.y) .. " / " .. xy(st.size.x, st.size.y)))

  -- ONE SPACE, TWO DOORS: the same pair, given to one window by a rule and to its twin by the verb, has to
  -- produce the same box. This is the check a rule that skipped the conversion fails outright.
  local ruled, verbed = win:size(), twin:size()
  eq("a rule's size gives a window the very box the verb gives its twin",
     xy(ruled.x, ruled.y), xy(verbed.x, verbed.y))

  -- The anchor: a corner plus an offset, and the offset is 8 of the same pixels everything else here counts.
  local rp, tsz, root = twin:rootPos(), twin:size(), hafen.ui():root():size()
  check(rp ~= nil and near(root.x - (rp.x + tsz.x), EDGE) and near(root.y - (rp.y + tsz.y), EDGE),
        "an anchored window sits " .. EDGE .. " design px in from the screen's bottom-right corner",
        rp and pt(root.x - (rp.x + tsz.x), root.y - (rp.y + tsz.y)))

  -- ...and dropping the sheet gives back exactly what it found, both halves: the stock value the layer recorded
  -- is the client's own, kept as the client had it, so this is an equality and not an approximation.
  sheet:drop()
  local back, backSz = win:position(), win:size()
  check(back.x == home.x and back.y == home.y and backSz.x == homeSz.x and backSz.y == homeSz.y,
        "dropping the sheet puts the window back where and as big as it was found ("
        .. pt(home.x, home.y) .. " / " .. xy(homeSz.x, homeSz.y) .. ")",
        pt(back.x, back.y) .. " / " .. xy(backSz.x, backSz.y))
  rLayout:remove()
  rAnchor:remove()

  -- ---- the chrome half, and the two controls that carry a number of their own ----------------------------
  rNone = sheet:rule(SEL_NONE)
  eq("a rule's pad reads back the number of design px it was given", rNone:pad(PAD):pad(), PAD)
  eq("a list's row height reads back the height it was given", list:rowHeight(), ROWH)
  eq("a bare grid's stock cell box is the same design box at every scale",
     xy(grid:cell().w, grid:cell().h), xy(CELL, CELL))

  -- Refusals: the conversion must swallow neither check. A pad still cannot be negative, and a slice that
  -- leaves no middle is still measured against the image's OWN pixels, which is what the insets are cut in.
  refuses("a negative pad is refused, saying a pad cannot take space away",
          function() rNone:pad(-1) end, "negative")
  refuses("a slice with no middle left is refused, naming the image's own size",
          function() rNone:border{ image = art, slice = {IMG - 4, IMG - 4, IMG - 4, IMG - 4} } end,
          xy(IMG, IMG))
  rNone:remove()
  teardown()

  -- The theme itself, on the sheet's own window: its border owns the frame insets and its pad IS the margin.
  rTheme = sheet:rule(SEL_A):border{ image = art, slice = {INSET, INSET, INSET, INSET} }:pad(PAD)
  sheet:install()
  -- One beat: a window's chrome is swapped on its own tick, not when :install() returns.
  hafen.timer():after(0.6, scoreThemed)
end

hafen.slash():register("t058-3", run)   -- the only way in: a suite does not start itself
