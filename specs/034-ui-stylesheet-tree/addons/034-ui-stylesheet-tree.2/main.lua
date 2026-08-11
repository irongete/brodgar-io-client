-- 034.2 — draw it: F5's frame, widened. Self-checking suite; see specs/testing/addon-suite.md.
--
-- What this task shipped, and therefore what this asserts: a tree rule stops being a Lua-only read (034.1) and
-- reaches the DRAW, through the one frame F5 already opens in the parent-first Widget.draw descent — so the rule
-- covers the widget AND its subtree, no render site is re-routed, and the risk that kills the feature is a STAMP
-- that varies per frame (it would rebuild every routed site sixty times a second).
--
-- Lua cannot read a pixel, so the draw is asserted through the ONE thing the draw leaves behind that Lua can
-- read: the rendered-text cache, whose key is (string, font, Fonts.gen()) — and Fonts.gen() is exactly the value
-- the frame stamps. Same string, two windows, one of them styled ⇒ TWO keys, and the stamp is stable when that
-- count then stops moving. hafen.client():profiling():textcache() is a pull-only counter, so this needs nothing
-- armed and nothing enabled.
--
-- READ-ONLY: declares no permissions and mutates no persistent state. Its three probe windows live for ~3 s in
-- the top-left corner and are destroyed, and it drops its sheet — a login that runs it leaves the client stock.
-- `:t034-2 demo` deliberately PARKS a sheet for the eye; `:t034-2 off` clears it, as does any :reload.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local LINE  = "034.2 stamp probe"          -- one string, drawn identically in every probe window
local STYLED, PLAIN = "034.2 styled", "034.2 plain"
local BIG = { 90, 200, 140 }

local function misses()
  return hafen.client():profiling():textcache().misses or 0
end

local function big()
  return hafen.font():get("serif"):derive():size(18)
end

-- A probe window: it exists to DRAW one line every frame, which is what puts a key in the text cache.
local function probe(title, y)
  return hafen.ui():window()
    :title(title)
    :size(170, 24)
    :position(4, y)
    :onDraw(function(g) g:text(LINE, 6, 4) end)
end

local wins = {}

local function killWins()
  for i = 1, #wins do
    if wins[i]:exists() then wins[i]:destroy() end
  end
  wins = {}
end

-- The suite is a sequence of checkpoints separated by real frames (the cache is filled by DRAWING), so it runs
-- as a chain of timers rather than straight through.
local function run()
  pass, fail, manual = 0, 0, 0        -- a re-run through :t034-2 reports its own counts, not the login's
  sheet:drop()
  killWins()
  local m0 = misses()
  local a, b = probe(STYLED, 4), probe(PLAIN, 34)
  wins = { a, b }

  hafen.timer():after(0.6, function()
    -- 1. no sheet yet: the two windows draw the SAME string, so they share ONE cache key.
    eq("with no sheet the two windows draw one string under one key", misses() - m0, 1)
    eq("and neither window resolves a style", a:style(), nil)

    local h = big()
    sheet:load{ ["window[title=" .. STYLED .. "]"] = { font = h, color = BIG } }:install()
    local m1 = misses()

    hafen.timer():after(0.6, function()
      -- 2. the rule reached the DRAW: the same string now takes two keys, one per subtree. (The unstyled
      --    window's key moved too — installing a rule bumps the generation for everyone, once.)
      eq("a tree rule reaches the draw: one string, two keys, one per subtree", misses() - m1, 2)
      check(a:style() ~= nil and a:style().font == h, "the styled window resolves the rule it was named by",
            a:style())
      eq("and the window the rule does not name resolves nothing", b:style(), nil)

      local m2 = misses()
      local c = probe(STYLED, 64)          -- created AFTER the rule was installed
      wins[#wins + 1] = c

      hafen.timer():after(0.6, function()
        -- 3. F5's captured-generation case: a widget built after the override still draws inside the frame —
        --    it lands on the styled window's key, so it rasterised NOTHING new.
        eq("a window created after the rule draws under the styled key, not a new one", misses() - m2, 0)
        check(c:style() ~= nil, "and it resolves the rule too", c:style())
        local m3 = misses()

        hafen.timer():after(1.0, function()
          -- 4. THE HEADLINE RISK: a stamp derived from the rule set is stable across frames. A stamp that
          --    varied would re-key every draw, so this count would climb by one per window per frame.
          eq("the stamp is stable across frames: ~60 frames, nothing re-rasterised", misses() - m3, 0)
          local m4 = misses()
          sheet:drop()

          hafen.timer():after(0.6, function()
            -- 5. teardown is exact: no frame opens anywhere, so all three windows share ONE key again.
            eq("dropping the sheet returns all three windows to one shared key", misses() - m4, 1)
            eq("and every widget resolves nothing again", a:style(), nil)
            eq("including the one created under the rule", c:style(), nil)

            manualCheck("run  :t034-2 demo  and look at the three probe windows (top-left), then  :t034-2 off",
                        "the two \"034.2 styled\" windows draw their line in a LARGE GREEN serif and the"
                        .. " \"034.2 plain\" one in the client's stock grey; nothing else on screen changes")
            manualCheck("open the inventory (Tab), THEN run  :t034-2 demo  with it still open; then  :t034-2 off",
                        "the inventory's caption turns large serif WHILE THE WINDOW IS ALREADY OPEN (and back"
                        .. " again), every other window keeps its own caption, and the caption's COLOUR never"
                        .. " changes at all — it is embossed, and the tile keeps only the alpha (033.3)")
            manualCheck("arm Options > Client > \"Enable profiling\", then run  :t034-2 prof",
                        "a block of [pass] lines reporting the draw phase with and without a tree sheet"
                        .. " installed, the difference well under 1 ms")

            killWins()
            hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
          end)
        end)
      end)
    end)
  end)
end

-- The measurement gate. Arming the profiler is a persistent client setting, so a suite must not flip it (see
-- TESTING.md) — the maintainer does, and from there this is automated: the descent's cost is the DRAW phase
-- with a tree sheet installed minus the same phase without one.
local function avgDraw(n)
  local h = hafen.client():profiling():history(n)
  local sum, cnt = 0, 0
  for _, f in ipairs(h) do
    if f.phases and f.phases.draw then sum = sum + f.phases.draw; cnt = cnt + 1 end
  end
  if cnt == 0 then return nil end
  return sum / cnt
end

local function prof()
  pass, fail, manual = 0, 0, 0
  if not hafen.client():profiling():frame().ms then
    hafen.log():write("[manual] :t034-2 prof needs the profiler armed -- expect: arm Options > Client >"
              .. " \"Enable profiling\" and run it again")
    hafen.log():write("[summary] 0 pass, 0 fail, 1 manual")
    return
  end
  sheet:drop()
  hafen.timer():after(1.5, function()
    local stock = avgDraw(60)
    -- Any tree rule at all makes the descent ask about EVERY widget it draws, which is the cost being measured;
    -- `window` matches the open ones, so the frame is really opened as well.
    sheet:load{ ["window"] = { color = BIG } }:install()
    hafen.timer():after(1.5, function()
      local tree = avgDraw(60)
      sheet:drop()
      if not (stock and tree) then
        check(false, "the draw phase was measured with and without a tree sheet", tostring(stock) .. "/" .. tostring(tree))
      else
        local d = tree - stock
        check(d < 1.0, ("the descent costs %.3f ms/frame (draw %.3f -> %.3f) -- under 1 ms"):format(d, stock, tree), d)
        check(tree < 8.0, ("the draw phase with a sheet installed stays under 8 ms (%.3f)"):format(tree), tree)
      end
      hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
    end)
  end)
end

hafen.slash():register("t034-2", function(args)
  local sub = args[1]
  if sub == "demo" then
    if #wins == 0 or not wins[1]:exists() then
      wins = { probe(STYLED, 4), probe(PLAIN, 34), probe(STYLED, 64) }
    end
    local h = big()
    sheet:load{
      ["window[title=" .. STYLED .. "]"] = { font = h, color = BIG },
      ["window[title=Inventory]"]        = { font = h },
    }:install()
    hafen.log():write(":t034-2 demo -> a sheet is PARKED: the two \"" .. STYLED .. "\" windows and the inventory."
              .. " Clear it with  :t034-2 off  (a :reload clears it too).")
  elseif sub == "off" then
    sheet:drop()
    killWins()
    hafen.log():write(":t034-2 off -> sheet dropped, probe windows destroyed")
  elseif sub == "prof" then
    prof()
  else
    run()
  end
end)

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. That is also what
-- removed the whole class of login races between suites -- this one's last check counts text-cache keys for ONE
-- string, and a key is (string, font, Fonts.gen()), so ANY other suite skinning something mid-round made it
-- read two keys where it expects one. Its round stages 3.4s; give it that before running the next one.
