-- 034.3 — widget:skin{}, the cut, docs, close. Self-checking suite; see specs/addons/TESTING.md.
--
-- What this task shipped, and therefore what this asserts: widget:setFont(h)/:resetFont() are a HARD CUT, and
-- widget:skin{…} takes their place as the TOP of the per-widget cascade — per-instance stops being a font-only
-- special case and becomes a level like any other (set / read / nil clears THIS addon's entry). So the checks
-- are the cascade itself: skin over the most specific tree rule, composed PER PROPERTY, read back through
-- w:style(); the same frame carrying it to the draw; D-073 (a handle's colour never styles a surface); and a
-- teardown that returns every widget to nil.
--
-- The draw check is 034.2's method: Lua cannot read a pixel, but g:text's cache is keyed on
-- (string, font, Fonts.gen()) — the very value the frame stamps — so ONE string drawn in two probe windows takes
-- TWO keys exactly when one of them is inside a frame. hafen.client:profiling():textcache() is pull-only, so
-- nothing needs arming.
--
-- READ-ONLY: declares no permissions and mutates no persistent state. Its probe windows live for ~2 s in the
-- top-left corner and are destroyed, and it drops its sheet — a login that runs it leaves the client stock.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log("[fail] " .. what .. " -- got: " .. tostring(got))
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

-- One printable line for a resolved style, so a [fail] carries the whole answer and not just "table".
local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log("[manual] " .. step .. " -- expect: " .. expect)
end

local function style(w)
  local s = w:style()
  if s == nil then return "nil" end
  local c = s.color and ("%d,%d,%d"):format(s.color.r, s.color.g, s.color.b) or "nil"
  return ("font=%s color=%s"):format(tostring(s.font ~= nil), c)
end

local LINE = "034.3 skin probe"           -- one string, drawn identically in both probe windows
local A, B = "034.3 probe A", "034.3 probe B"
local WARM, COLD = { 200, 180, 140 }, { 90, 140, 200 }

local function misses()
  return hafen.client:profiling():textcache().misses or 0
end

local function probe(title, y)
  return hafen.ui.window{
    title = title, size = { 170, 24 }, pos = { 4, y },
    onDraw = function(g) g:text(LINE, 6, 4) end,
  }
end

local wins = {}

local function killWins()
  for i = 1, #wins do
    if wins[i]:exists() then wins[i]:destroy() end
  end
  wins = {}
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a re-run through :t034-3 reports its own counts, not the login's
  hafen.ui.skin(nil)
  killWins()
  local body = hafen.font("serif"):derive{ size = 13 }
  local mono = hafen.font("mono"):derive{ size = 18 }
  local m0 = misses()
  local a, b = probe(A, 4), probe(B, 34)
  wins = { a, b }

  -- 1. the cut: the two F5 verbs read as plain nil, and skin is what answers instead.
  eq("the hard cut holds: widget:setFont", a.setFont, nil)
  eq("the hard cut holds: widget:resetFont", a.resetFont, nil)
  eq("a widget nobody skinned reads no style of its own", a:skin(), nil)

  -- 2. widget:skin{} sets, and reads back the very handle it was given (D-017: your own Lua value, not a copy).
  a:skin{ font = mono, color = WARM }
  local s = a:skin()
  check((s ~= nil) and (s.font == mono), "widget:skin() hands back the very handle it was given", s)
  eq("and the colour it was given", s and s.color and s.color.r, 200)
  eq("the skinned widget resolves that style", style(a), "font=true color=200,180,140")
  eq("and the widget beside it resolves nothing", style(b), "nil")

  -- 3. the per-instance level beats the MOST SPECIFIC tree rule -- but only for the property it names, so the
  --    rule's font survives a colour-only skin (D-076, one level up).
  hafen.ui.skin{ ["window[title=" .. A .. "]"] = { font = body, color = COLD } }
  eq("a tree rule alone answers where nothing was skinned", style(b), "nil")
  a:skin{ color = WARM }
  eq("the skin outranks the most specific tree rule, per property", style(a), "font=true color=200,180,140")
  check(a:style().font == body, "the font is still the tree rule's -- a colour-only skin takes only the colour",
        a:style().font)

  -- 4. and it clears back to the level beneath, not to stock.
  a:skin(nil)
  eq("widget:skin(nil) drops this addon's entry", a:skin(), nil)
  eq("and the widget falls back to the tree rule, not to stock", style(a), "font=true color=90,140,200")

  -- 5. D-073 carries over unchanged: a handle's own colour never styles a SURFACE, while its family does. (It
  --    still colours your own g:text -- the [manual] line below is where that is visible.)
  hafen.ui.skin(nil)
  a:skin{ font = mono:derive{ color = { 255, 0, 0 } } }
  eq("a font handle's colour does not become the surface's colour", style(a), "font=true color=nil")

  -- 6. refusals are checks: the properties are a sheet rule's, so a typo errors the same way and says which.
  refuses("an unknown property in widget:skin is refused, naming it",
          function() a:skin{ colour = WARM } end, "colour")
  refuses("a font that is not a handle is refused",
          function() a:skin{ font = "serif" } end, "font handle")
  refuses("a non-table argument is refused",
          function() a:skin("big") end, "expected a table")

  -- 7. the DRAW: with A skinned and B not, ONE string takes TWO cache keys (034.2's method, new level).
  a:skin{ font = mono }
  local m1 = misses()
  hafen.timer.after(0.7, function()
    eq("a skin reaches the draw: one string, two keys, one per subtree", misses() - m1, 2)

    -- 8. teardown is exact: dropping the skin returns the widget to nil and the two windows to one shared key.
    a:skin(nil)
    local m2 = misses()
    hafen.timer.after(0.7, function()
      eq("dropping the skin returns the widget to nil", style(a), "nil")
      eq("and both windows draw under one shared key again", misses() - m2, 1)

      manualCheck("run  :t034-3 demo  (it opens two probe windows and skins the first), look, then  :t034-3 off",
                  "the \"034.3 probe A\" window draws its CAPTION in a large mono font and its own line in RED (a"
                  .. " handle's colour still colours your own g:text, D-073), while \"034.3 probe B\" right beneath"
                  .. " it and every other window on screen stay exactly stock")

      killWins()
      hafen.ui.skin(nil)
      hafen.log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end)
  end)
end

hafen.slash.register("t034-3", function(args)
  local sub = args[1]
  if sub == "demo" then
    if #wins == 0 or not wins[1]:exists() then
      local red = hafen.font("mono"):derive{ size = 18, color = { 255, 60, 60 } }
      local a = hafen.ui.window{
        title = A, size = { 200, 28 }, pos = { 4, 4 },
        onDraw = function(g) g:text(LINE, 6, 4, { font = red }) end,
      }
      local b = probe(B, 40)
      wins = { a, b }
      a:skin{ font = hafen.font("mono"):derive{ size = 18 } }
    end
    hafen.log(":t034-3 demo -> probe A is SKINNED, probe B is not. Clear it with  :t034-3 off  (a :reload clears it too).")
  elseif sub == "off" then
    killWins()
    hafen.ui.skin(nil)
    hafen.log(":t034-3 off -> probe windows destroyed, sheet dropped")
  else
    run()
  end
end)

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it.
--
-- This one is why. It used to start at +9 and run fifteen checks synchronously, each widget:skin{}/skin(nil)
-- bumping Fonts.gen(); 034.2 started at +6 and staged 3.4s, so it was still drawing at ~+9.4 and read two
-- text-cache keys where it expects one. A 0.4s overlap makes that a RACE, which is why it passed for two whole
-- features and then reddened -- and every fix was another number in a schedule nobody asked for. Running a
-- suite by hand deletes the schedule and the race with it.
