-- 040.1 — the ownership contract, and hafen.ui():button(). Self-checking suite; see specs/addons/TESTING.md.
--
-- THE MECHANISM THIS TASK MOVED is provenance. "Is this widget mine?" has always been derived from the
-- tree and never stored (029.2), but the test was written against a CLASS -- the addon's own painted
-- surface -- and a haven.Button an addon builds is not one. Under the old test every control this feature
-- ships would have read as BORROWED, and :destroy(), the builder setters and the whole owned half would
-- have refused on the addon's own button. So the test is now a CONTRACT, and the proof of that is not a
-- Java type: it is that the owned verbs answer on a button this addon built and still refuse on a NATIVE
-- button of the same class, in the same run, one after the other.
--
-- AND THE ADAPTER MUST NEVER SURFACE. A control is a real client widget wearing a thin bridge subclass, so
-- :type() has to keep reading "Button" -- otherwise every selector, role and stylesheet key that names the
-- engine's class would quietly stop matching a control an addon built. That is asserted twice here: by the
-- name itself, and by an @Class selector finding it.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the two [manual] lines need -- which closes itself when pressed, and after
-- 90 seconds if it is not.

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

local function nop() end

-- How many widgets are in the whole client tree right now. The orphan test is this number before and
-- after: a control the addon destroyed must leave the tree exactly as it found it, chrome included.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

-- A button THIS ADDON DID NOT BUILD. There is always at least one once we have a window of our own: the
-- close box on its chrome is the client's own IButton, built by the deco and owned by nobody. A plain
-- haven.Button is preferred where the tree has one, since "the same class" is the sharper claim.
local function nativeButton()
  local any
  for _, w in ipairs(hafen.ui():all("button")) do
    if not w:info().owned then
      if w:type() == "Button" then return w end
      any = any or w
    end
  end
  return any
end

local function run()
  local base = treeCount()

  -- 1. THE PREMISE THIS TASK STANDS ON (D-085: a suite convinces alone). hafen.ui() is the section object,
  --    handed back by identity, and a builder is a verb ON it rather than a key beside it.
  check((hafen.ui() == hafen.ui()) and (type(hafen.ui().button) == "function"),
        "hafen.ui() is the one section object, and :button() is a verb on it", tostring(hafen.ui()))

  -- 2. BUILT BARE, WITH THE CLIENT'S OWN DEFAULTS (R4). The constructor takes nothing: what a button looks
  --    like is chosen by the setters that follow, and an argument names them.
  local scratch = hafen.ui():window():title("040.1 — scratch"):size(240, 120):position(400, 300)
  local b = hafen.ui():button()
  local bp, bs = b:position(), b:size()
  check((b:text() == "") and (bp.x == 100) and (bp.y == 100) and (bs.x > 0) and (bs.y > 0)
        and (b:parent() == hafen.ui():root()),
        "a bare button exists at once: no caption, the client's default place and its own height, under the root",
        ("text=%q %d,%d %dx%d"):format(b:text(), bp.x, bp.y, bs.x, bs.y))
  refuses("...and the builder itself takes no arguments: the caption is a setter",
          function() return hafen.ui():button("Go") end, "takes no arguments")

  -- 3. IT IS A REAL CLIENT BUTTON, AND THE BRIDGE'S ADAPTER NEVER SURFACES. :type() is the engine's class,
  --    :role() the selector vocabulary, and both a role selector and an @Class one find the very widget --
  --    which is the whole claim "a control is a Widget" reduced to something that can fail.
  eq("a control reports the ENGINE's class, never the bridge's adapter", b:type(), "Button")
  eq("...and the role classifier already knows it", b:role(), "button")
  local byRole, byClass = false, false
  for _, w in ipairs(hafen.ui():all("button")) do byRole = byRole or (w == b) end
  for _, w in ipairs(hafen.ui():all("@Button")) do byClass = byClass or (w == b) end
  check(byRole and byClass, "a selector finds a control the addon built, by role and by class",
        ("role=%s class=%s"):format(tostring(byRole), tostring(byClass)))

  -- 4. THE OWNED VERBS ANSWER ON IT, and each is an EXISTING verb with no new spelling. :parent(w) is legal
  --    while the control is still being built, exactly as it is for a surface (D-121).
  check((b:parent(scratch) == b) and (b:parent() == scratch),
        "widget:parent(w) puts a control in a window the addon built, and chains", tostring(b:parent()))
  local p = b:position(10, 10):position()
  local s = b:size(120, 20):size()
  check((p.x == 10) and (p.y == 10) and (s.x == 120) and (s.y == 20),
        "widget:position(x, y) and widget:size(w, h) answer on a control and read back",
        ("%d,%d %dx%d"):format(p.x, p.y, s.x, s.y))
  check((b:visible(false) == b) and (b:visible() == false) and (b:visible(true) == b) and (b:visible() == true),
        "widget:visible(b) answers on a control, both ways", tostring(b:visible()))
  eq("widget:info().owned is true for a control this addon built", b:info().owned, true)

  -- 5. THE TWO VERBS A BUTTON ADDS. :text(s) is the caption -- the read half already answered on any
  --    text-bearing widget, so this is R2 completing a verb rather than a new name -- and :onPress(fn) is
  --    the activation, which holds nothing. Both chain; both read back; a nil write is refused (R5).
  check((b:text("Harvest") == b) and (b:text() == "Harvest") and (b:text("Harvest!") == b)
        and (b:text() == "Harvest!"),
        "widget:text(s) writes a control's caption, chains, and round-trips both ways", b:text())
  refuses("widget:text(nil) is refused: a nil that silently became a READ is the bug the rule exists for",
          function() b:text(nil) end, "must not be nil")
  check((b:onPress(nop) == b) and (b:onPress() == nop), "widget:onPress(fn) chains and reads back",
        tostring(b:onPress()))
  local plain = hafen.ui():widget()
  eq("...and reads nil on a widget with nothing to press", plain:onPress(), nil)
  plain:destroy()

  -- 6. A CONTROL IS NOT A SURFACE. The eight draw/input callbacks are things a widget the addon PAINTS has;
  --    the client draws and drives a control, so the write refuses naming what does take it.
  refuses("a surface-only callback refuses on a control, naming the builders that have one",
          function() b:onDraw(nop) end, "SURFACE")

  -- 7. ...AND OWNERSHIP HOLDS IN THE OTHER DIRECTION, which is the half the contract could have broken:
  --    the very same verbs, on a button the CLIENT built, still refuse naming what to do instead.
  local nat = nativeButton()
  check(nat ~= nil, "the client's own tree has a button that is not ours (its close box, at least)",
        tostring(nat))
  if nat ~= nil then
    eq("a native button reads as borrowed", nat:info().owned, false)
    refuses("...so widget:destroy() refuses on it, naming it a native widget",
            function() nat:destroy() end, "NATIVE widget")
    refuses("...and so does widget:text(s), the caption of a control we do not own",
            function() nat:text("mine now") end, "NATIVE widget")
    refuses("...and widget:onPress(fn), which would put our handler on the client's own button",
            function() nat:onPress(nop) end, "NATIVE widget")
  end

  -- 8. TEARDOWN GIVES THE TREE BACK. Destroying a control ends it, and destroying the window around one
  --    takes it with it -- so the count returns to what it was before anything here was built.
  local grown = treeCount()
  b:destroy()
  eq("widget:destroy() ends a control", b:exists(), false)
  local held = hafen.ui():button():parent(scratch):position(10, 40)
  scratch:destroy()
  local after = treeCount()
  check((grown > base) and (not held:exists()) and (after == base),
        "a control dies with the window it was put in, and the tree ends the size it started",
        ("%d before, %d built, %d after"):format(base, grown, after))

  -- 9. The two things a program cannot judge: that it FIRES, and that it LOOKS like the client's own.
  local demo = hafen.ui():window():title("040.1 — controls"):size(140, 60):position(60, 60)
  hafen.ui():button()
    :parent(demo):position(20, 20)
    :text("Press me")
    :onPress(function()
        hafen.log():write("040.1: pressed")
        demo:destroy()                      -- safe: Button releases its grab BEFORE it calls the activation
      end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)
  manual = manual + 1
  hafen.log():write("[manual] click the button in the window \"040.1 — controls\" at 60,60"
                    .. " -- expect: the log prints \"040.1: pressed\" and the window closes itself")
  manual = manual + 1
  hafen.log():write("[manual] before pressing it, look at that button"
                    .. " -- expect: the client's own button -- same frame, same caption font, and it"
                    .. " depresses under the cursor (it is unpressed and gone in 90s if you leave it)")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-1", run)   -- the only way in: a suite does not start itself
