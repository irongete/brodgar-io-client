-- 034.1 — resolve a tree key, and let Lua read it. Self-checking suite; see specs/addons/TESTING.md.
--
-- What this task shipped, and therefore what this asserts: a TREE key stops being inert (033 parsed one and
-- dropped it, D-072) — every tree rule that matches a widget is folded into ONE resolved style, most specific
-- winning per property, and w:style() reads it back, nil when nothing matches. Nothing is DRAWN differently
-- in this task (that is 034.2), so resolution being observable through w:style() IS the deliverable: every
-- criterion below is readable through it and there is ZERO [manual] here.
--
-- READ-ONLY: declares no permissions and mutates no persistent state. Its two probe windows are hidden the
-- moment they exist (034.1 draws nothing anyway) and destroyed before it finishes, and it drops its sheet —
-- a login that runs it leaves the client exactly stock.

local pass, fail = 0, 0

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
local function style(w)
  local s = w:style()
  if s == nil then return "nil" end
  local c = s.color and ("%d,%d,%d"):format(s.color.r, s.color.g, s.color.b) or "nil"
  return ("font=%s color=%s"):format(tostring(s.font ~= nil), c)
end

local WARM, COLD = { 200, 180, 140 }, { 90, 140, 200 }

local function run()
  pass, fail = 0, 0                    -- so a re-run through :t034-1 reports its own counts, not the login's
  local body = hafen.font("serif"):derive{ size = 13 }
  -- Two OWNED probe windows: the only widgets whose captions this suite can be sure of. Hidden at once.
  local a = hafen.ui.window{ title = "034 probe A", size = { 60, 20 }, pos = { 4, 4 } }
  local b = hafen.ui.window{ title = "034 probe B", size = { 60, 20 }, pos = { 4, 4 } }
  a:hide(); b:hide()
  local kid = a:children()[1] or a     -- something INSIDE probe A (its content, or its chrome's deco)

  -- 1. nil means stock: with no sheet installed nothing resolves anywhere.
  eq("a client with no sheet resolves no style", style(a), "nil")
  check(kid ~= a, "the probe window has something inside it to resolve separately", "no children")

  -- 2. a tree key resolves — on the widget it names, and on no other.
  hafen.ui.skin{ ["window[title=034 probe A]"] = { font = body, color = WARM } }
  eq("a [title=] tree key resolves on the widget it names", style(a), "font=true color=200,180,140")
  check(a:style().font == body, "the font comes back as the very handle the rule named", a:style().font)
  eq("a widget the key does not name resolves nothing", style(b), "nil")

  -- 3. resolution is PER WIDGET, and the refiner keeps 030's enclosing-window rule: `window[title=A]` needs
  --    the widget to BE that window, while `[title=A]` alone answers for everything inside it.
  eq("a child of the named window is not itself named by a role+refiner key", style(kid), "nil")
  hafen.ui.skin{ ["[title=034 probe A]"] = { color = WARM } }
  eq("a refiner-only key reaches every widget inside that window", style(kid), "font=false color=200,180,140")

  -- 4. the specificity fold: two competing keys, the more specific wins where it applies (role 1, +[title=] 4).
  hafen.ui.skin{ ["window"] = { color = WARM }, ["window[title=034 probe A]"] = { color = COLD } }
  eq("the more specific of two competing keys wins", style(a), "font=false color=90,140,200")
  eq("the broader key still answers everywhere else", style(b), "font=false color=200,180,140")

  -- 5. and it folds PER PROPERTY: the specific rule takes the colour without taking the broad rule's font.
  hafen.ui.skin{ ["window"] = { font = body }, ["window[title=034 probe A]"] = { color = COLD } }
  eq("the fold is per property, not per rule", style(a), "font=true color=90,140,200")

  -- 6. a SITE key is not a widget's style: `*` and the eleven routed surfaces resolve where they DRAW (033),
  --    and folding one into a widget would be a guess — a window contains buttons, labels and chat.
  hafen.ui.skin{ ["*"] = { color = WARM }, ["chat"] = { color = COLD } }
  eq("a site key is not a widget's style", style(a), "nil")

  -- 7. teardown is exact: dropping the sheet puts every widget back to nil.
  hafen.ui.skin{ ["window"] = { color = WARM } }
  hafen.ui.skin(nil)
  eq("dropping the sheet returns a matched widget to nil", style(a), "nil")
  eq("dropping the sheet returns every widget to nil", style(b), "nil")

  -- 8. a typo in a tree key's rule still errors, and says WHICH property (D-072: the key may mean something
  --    later, a misspelt property never will).
  refuses("an unknown property in a tree key is refused, naming it",
          function() hafen.ui.skin{ ["window[title=034 probe A]"] = { colour = COLD } } end, "colour")

  a:destroy(); b:destroy()
  eq("a destroyed widget resolves nothing", style(a), "nil")

  hafen.log(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. That is also what
-- removed the whole class of login races between suites -- each one installs a client-wide sheet and bumps
-- Fonts.gen() while it runs, so two rounds overlapping reddened lines in the OTHER suite.
hafen.slash.register("t034-1", run)                                       -- the only way in
