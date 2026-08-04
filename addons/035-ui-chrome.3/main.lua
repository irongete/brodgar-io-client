-- 035.3 — the window-less panels (IBox). Self-checking suite; see specs/addons/TESTING.md.
--
-- 035.1 gave the sheet the window chrome through Window.Deco, a seam only windows have. Everything else the
-- client frames -- the portrait, the party avatars, the list and info boxes in the character/skill/quest/wound/
-- fight/buddy windows, a flower-menu petal, a dropdown -- draws an IBox itself, and IBox is already an
-- interface, so `panel` joins the site keys and a sheet-fed box drops in where those panels build theirs.
--
-- What a program can prove here, and what it cannot, split honestly:
--
--   * the VOCABULARY is assertable -- "panel" is valid grammar, classifies no widget (like window.frame), and
--     an unknown property on it is still an error;
--   * the SURVEY is assertable, and is the point of this task: how many routed panels the live HUD actually
--     draws is a number, so the coverage table 035.4 writes is measured rather than guessed;
--   * the CASCADE is assertable through widget:style(): a rule reaches the panel it names and not its
--     neighbour, and dropping it returns that panel to one nil;
--   * the GEOMETRY is assertable, and its answer is the finding: a panel decided its size and placed its
--     children when it was BUILT, so a panel rule -- `pad` included -- moves nothing. Inert, never an error;
--   * whether the panel LOOKS restyled is not. A site rule reaches the draw and nothing else, so it leaves no
--     value Lua can read; that half is the two [manual] lines, parked by ':t035-3 look'.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and drops its sheet before it finishes.

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

local function xy(p) return ("%d,%d"):format(p.x, p.y) end

local PANEL = "panel.png"
local DARK  = { 26, 26, 28, 240 }

local function border()
  return { image = hafen.asset(PANEL), slice = { 8, 8, 8, 8 } }
end

local function theme(pad)
  return { ["panel"] = { bg = { color = DARK }, border = border(), pad = pad } }
end

-- The survey, run against the live HUD rather than against the source: every class below is a routed IBox
-- draw site, so this is what "which panels are reachable" actually measures. Frame's three subclasses answer
-- their OWN class name (@Class is an exact typeName, 030), so each is counted separately.
--
-- The flag is which properties that KIND honours, and it is the other half of the coverage table. A `bg`
-- replaces a surface the panel already paints, so it reaches only a panel that paints one BEFORE its content
-- (a petal, a dropdown, an ISBox). A Frame paints none: it is a border placed AROUND a region and drawn AFTER
-- what it frames -- Frame.around leaves that content a sibling of the frame entirely -- so a fill would bury
-- the rows it is drawn around, which is exactly what the character sheet showed in-game. Border on all of
-- them, bg on the ones marked below. Inert, never an error.
local KINDS = {
  { "Frame", false }, { "MemberView", false }, { "ProxyFrame", false }, { "ViewFrame", false },
  { "Petal", true  }, { "SListMenu",  true  }, { "ISBox",      true  }, { "Image",     true  },
}

local function census()
  local n, out = 0, {}
  for _, k in ipairs(KINDS) do
    local c = #hafen.ui():all("@" .. k[1])
    n = n + c
    out[#out + 1] = k[1] .. "=" .. c .. (k[2] and "(bg+border)" or "(border)")
  end
  return n, table.concat(out, " ")
end

-- ---- the run ------------------------------------------------------------------------------------

local panel, base
local steps = {}

local function finish()
  hafen.ui.skin(nil)
  manualCheck("run ':t035-3 look', then right-click the ground for a flower menu and click one of its petals",
    "the petals are dark with a gold border instead of the stock wooden one, their text still reads, and"
    .. " clicking one still chooses that option -- ':t035-3 look' again puts the stock panels back")
  manualCheck("with ':t035-3 look' still on, open two windows that frame things (the character sheet, Ctrl+T,"
    .. " and the map) and read what is inside their boxes",
    "every box wears the theme's gold BORDER and NOTHING is filled in behind its contents -- the attribute"
    .. " rows, the map and the marker list are all exactly as readable and exactly where they were, because a"
    .. " Frame takes border and not bg. The WINDOWS around them are still stock: a panel rule is not a"
    .. " window.frame rule")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every step waits first: a sheet reaches the screen on the next draw, so a step running inline with the
-- write it checks would read the previous frame (035.2's lesson, which cost it a whole login round).
local function step(i)
  hafen.timer():after(0.35, function()
    local f = steps[i]
    if not f then return finish() end
    f()
    step(i + 1)
  end)
end

-- A panel rule is live: nothing moved, nothing was resized, and no window's chrome was swapped for it.
steps[1] = function()
  eq("a panel rule moves and resizes nothing -- a panel owns no layout to re-run, so pad is inert here",
     xy(panel:size()) .. " @ " .. xy(panel:position()), base)
  eq("...and it is a PANEL rule: it does not dress a single window's chrome",
     #hafen.ui():all("@SkinDeco"), 0)
  hafen.ui.skin(nil)
end

steps[2] = function()
  eq("dropping the sheet leaves every panel exactly as it found it",
     xy(panel:size()) .. " @ " .. xy(panel:position()), base)
  eq("...and leaves no sheet-fed chrome behind anywhere", #hafen.ui():all("@SkinDeco"), 0)
end

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t035-3 reports its own counts, not the login's
  hafen.ui.skin(nil)

  -- 1. the vocabulary. "panel" is a SITE key, the sibling of window.frame: valid grammar everywhere a
  --    selector is, and -- like every site role -- classifying no widget rather than guessing at one.
  eq("\"panel\" is a valid role that classifies no widget: an honest nothing", #hafen.ui():all("panel"), 0)
  refuses("a misspelt role lists panel among the ones that exist",
          function() hafen.ui():find("pannel") end, "panel")
  refuses("an unknown property on a panel rule is still an error (D-072)",
          function() hafen.ui.skin{ ["panel"] = { bordre = {} } } end, "bordre")
  check(pcall(hafen.ui.skin, theme(6)), "the sheet accepts a panel rule carrying bg, border and pad")
  hafen.ui.skin(nil)

  -- 2. the survey -- measured on the live HUD, which is the whole point of this task. The counts are what
  --    035.4's coverage table is written from, so they travel in the line itself.
  local n, tally = census()
  check(n > 0, "the live HUD draws " .. n .. " routed IBox panels right now -- " .. tally, tally)

  -- 3. the per-widget cascade reaches a panel, and only the one it names.
  local frames = hafen.ui():all("@Frame")
  panel = frames[1]
  check(panel ~= nil, "the client's own panels are reachable as widgets", panel)
  if panel == nil then
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  eq("a panel nothing styles resolves nothing", panel:style(), nil)
  panel:skin{ bg = { color = DARK }, border = border() }
  local st = panel:style()
  eq("widget:style() reports the bg a rule put on a panel", st and st.bg and st.bg.color and st.bg.color.r, 26)
  eq("...and the border's slice, keyed so it writes straight back",
     st and st.border and st.border.slice and st.border.slice.l, 8)
  if frames[2] ~= nil then
    eq("the panel beside it is untouched: a rule reaches what it names", frames[2]:style(), nil)
  end
  panel:skin(nil)
  eq("dropping the skin returns the panel to one nil", panel:style(), nil)

  -- 4. geometry, and the site half going live. Both are read a frame later, in the steps above.
  base = xy(panel:size()) .. " @ " .. xy(panel:position())
  eq("no window is wearing sheet-fed chrome before this suite starts", #hafen.ui():all("@SkinDeco"), 0)
  hafen.ui.skin(theme(6))
  step(1)
end

-- ---- the parked state, for the two [manual] lines ------------------------------------------------

local looking = false

local function look()
  looking = not looking
  if looking then
    hafen.ui.skin(theme())
    hafen.log():write(":t035-3 look -> the panel theme is ON. Look at the portrait frame, right-click for a flower"
      .. " menu, open the character sheet -- then ':t035-3 look' again (or :reload, or disabling this addon).")
  else
    hafen.ui.skin(nil)
    hafen.log():write(":t035-3 look -> stock panels restored.")
  end
end

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. This one installs a
-- client-wide sheet and bumps Fonts.gen() while it runs, so when suites started themselves it could only be
-- kept out of the others' way with a number in a schedule -- and every such number was one more thing to get
-- wrong. Its round stages ~0.7s.
hafen.slash():register("t035-3", function(args)
  if (args and args[1]) == "look" then look() else run() end
end)
