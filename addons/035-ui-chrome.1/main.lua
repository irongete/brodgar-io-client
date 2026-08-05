-- 035.1 — window.frame, and a Deco fed by the sheet. Self-checking suite; see specs/addons/TESTING.md.
--
-- What this task shipped, and therefore what this asserts: "window.frame" joins the site keys; `bg` and
-- `border` join `font`/`color` as style properties (plain data, folded per property like every other level,
-- D-076); and a window whose chrome a rule names carries a sheet-fed Deco instead of the stock one, swapped
-- through the engine's own public chdeco.
--
-- SUPERSEDED, deliberately: this suite used to assert that a frame rule moves and resizes NOTHING — a promise
-- whose own text named 035.2 as the task that would end it. It has: a border's insets are the window's frame
-- margins now, so a framed window IS a different size, and `addons/035-ui-chrome.2/` asserts those numbers.
-- What stayed here is the half 035.1 still owns and which the change makes stronger: dropping the sheet puts
-- the exact previous size and position back.
--
-- Two things a program cannot judge -- whether the frame LOOKS restyled, and whether a restyled window still
-- drags/resizes/closes/focuses -- are [manual], and ':t035-1 look' parks the sheet on so they can be done at
-- leisure rather than during the 3-second login run.
--
-- READ-ONLY: declares no permissions and mutates no persistent state. Its probe window is destroyed before it
-- finishes and its sheet is dropped, so a login that runs it leaves the client stock.

local pass, fail, manual = 0, 0, 0

-- This addon's one stylesheet: a selector names a rule on it, and :install() applies what it says.
local sheet = hafen.ui():sheet()

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

-- The deco is a CHILD of its window (030's finding, and the whole reason window.frame is a SITE key rather
-- than the `window` role): so "what chrome is this window wearing" is read by type off its own children.
local function decoOf(w)
  for _, kid in ipairs(w:children()) do
    local t = kid:type()
    if (t == "SkinDeco") or (t == "DefaultDeco") then return t end
  end
  return "none"
end

local function xy(p) return ("%d,%d"):format(p.x, p.y) end

local PANEL = "panel.png"
local DARK  = { 26, 26, 28, 240 }

local function theme()
  local p = hafen.asset(PANEL)
  return { ["window.frame"] = { bg = { color = DARK },
                                border = { image = p, slice = { 8, 8, 8, 8 } } } }
end

-- ---- the automated run -------------------------------------------------------------------------

local function stage3(w, sz, pos)
  eq("dropping the sheet takes every dressed deco off", #hafen.ui():all("@SkinDeco"), 0)
  eq("the window is back on the stock chrome", decoOf(w), "DefaultDeco")
  eq("the restore leaves the window the size it was", xy(w:size()), sz)
  eq("the restore leaves the window where it was", xy(w:position()), pos)
  w:destroy()
  manualCheck("run ':t035-1 look', then open a window (Tab for the inventory)",
    "every window's frame is dark with a gold border, and its caption still reads in the stock title font")
  manualCheck("with ':t035-1 look' still on: drag a window by its caption, resize the map window from its"
    .. " corner, close one with its X, and click between two windows",
    "all four behave exactly as stock -- then ':t035-1 look' again puts the stock chrome back")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function stage2(w, sz, pos)
  check(#hafen.ui():all("@SkinDeco") > 0, "a window.frame rule puts a sheet-fed deco on the client's windows",
        #hafen.ui():all("@SkinDeco"))
  eq("the probe window is wearing it too", decoOf(w), "SkinDeco")
  sheet:drop()
  hafen.timer():after(0.4, function() stage3(w, sz, pos) end)   -- the swap back happens in Window.tick
end

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t035-1 reports its own counts, not the login's
  sheet:drop()

  -- 1. the refusals. A property this client does not ship is still an error (D-072); so is a chrome value
  --    that cannot mean anything, each naming the part that is wrong.
  refuses("an unknown style property is still refused, naming it",
          function() sheet:load{ ["window.frame"] = { backgrund = {} } }:install() end, "backgrund")
  refuses("a bg that says nothing is refused",
          function() sheet:load{ ["window.frame"] = { bg = {} } }:install() end, "says nothing")
  refuses("a bg that is both a colour and an image is refused",
          function() sheet:load{ ["window.frame"] = { bg = { color = DARK,
                                                                image = hafen.asset(PANEL) } } }:install() end, "not both")
  refuses("an unknown key inside bg is refused, naming it",
          function() sheet:load{ ["window.frame"] = { bg = { colour = DARK } } }:install() end, "colour")
  refuses("a border with no slice is refused",
          function() sheet:load{ ["window.frame"] = { border = { image = hafen.asset(PANEL) } } }:install() end,
          "needs a slice")
  refuses("a slice that leaves no middle is refused, naming the image's size",
          function() sheet:load{ ["window.frame"] = { border = { image = hafen.asset(PANEL),
                                                                    slice = { 40, 40, 40, 40 } } } }:install() end, "24x24")

  -- 2. widget:style() reports the new properties, beside font and color and under the same one nil (D-075).
  local w = hafen.ui():window()
    :title("035 probe")
    :size(90, 40)
    :position(8, 8)
  eq("a widget nothing styles resolves nothing", w:style(), nil)
  local panel = hafen.asset(PANEL)
  w:rule():bg{ color = DARK }:border{ image = panel, slice = { 8, 8, 8, 8 } }
  local st = w:style()
  check(st ~= nil, "a widget with a rule of its own resolves a style", st)
  eq("widget:style() reports the bg colour", st.bg and st.bg.color and st.bg.color.r, 26)
  eq("widget:style() reports the bg alpha, which is what makes a panel translucent",
     st.bg and st.bg.color and st.bg.color.a, 240)
  eq("widget:style() reports the border's slice, keyed so it writes straight back",
     st.border and st.border.slice and st.border.slice.l, 8)
  check(st.border and (st.border.image == panel),
        "the border comes back as the very asset handle the rule named", st.border and st.border.image)
  w:rule():remove()
  eq("dropping that level returns the widget to one nil", w:style(), nil)

  -- 3. the swap itself, and that dropping the sheet undoes it exactly. It happens in Window.tick (never
  --    inside a draw), so each half is read a frame later.
  eq("a client with no frame rule wears no sheet-fed deco anywhere", #hafen.ui():all("@SkinDeco"), 0)
  eq("the probe window starts on the stock chrome", decoOf(w), "DefaultDeco")
  local sz, pos = xy(w:size()), xy(w:position())
  sheet:load(theme()):install()
  hafen.timer():after(0.4, function() stage2(w, sz, pos) end)
end

-- ---- the parked state, for the two [manual] lines ------------------------------------------------

local looking = false

local function look()
  looking = not looking
  if looking then
    sheet:load(theme()):install()
    hafen.log():write(":t035-1 look -> the frame theme is ON. Drag / resize / close / focus a window, then"
      .. " ':t035-1 look' again (or :reload, or disabling this addon) for stock chrome.")
  else
    sheet:drop()
    hafen.log():write(":t035-1 look -> stock chrome restored.")
  end
end

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. That is also what
-- removed the whole class of login races between suites -- each one installs a client-wide sheet and bumps
-- Fonts.gen() while it runs, so two rounds overlapping reddened lines in the OTHER suite.
hafen.slash():register("t035-1", function(args)
  if (args and args[1]) == "look" then look() else run() end
end)
