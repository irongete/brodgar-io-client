-- 040.2 — the face setter, and IButton. Self-checking suite; see specs/addons/TESTING.md.
--
-- WHAT THIS TASK CLAIMS. `Button` and `IButton` are one control to an addon author and two classes to the
-- client, and the difference between them is only whether the face is text or pictures. So there is ONE
-- builder, hafen.ui():button(), and the SETTER chooses the class: :text("Go") completes it as a Button and
-- :image(up, down[, hover]) as an IButton. The client's `I` prefix never reaches the vocabulary.
--
-- WHY THAT IS NOT A FREE CHOICE. An IButton's faces are final and its box IS the picture, so choosing one
-- is not writing a property -- it is choosing WHICH WIDGET this is, and the widget already in the tree has
-- to be replaced. That is legal exactly while the control is still being built (nothing has drawn it yet)
-- and refused once it is on screen. Which makes the two sharpest claims here:
--   * the swap is invisible from Lua -- the very value you are chaining setters onto stays the SAME object
--     across it, and a fresh lookup through another door hands back that same value;
--   * the refusal after arming is real, and it needs a TICK to test, so this suite runs in two phases.
-- A caption is not a face: :text(s) stays live at any time, which the second phase also proves.
--
-- READ-ONLY: it declares no permissions, mutates no persistent state, and destroys everything it builds
-- except the one small window the [manual] lines need -- which closes itself when pressed, and after 90
-- seconds if it is not.

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

-- How many widgets are in the whole client tree right now. A rebuild must REPLACE a widget, not add one.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

-- A button THIS ADDON DID NOT BUILD: the close box on any window's chrome is the client's own IButton.
local function nativeButton()
  for _, w in ipairs(hafen.ui():all("button")) do
    if not w:info().owned then return w end
  end
end

-- The client's OWN art -- the [+] steppers of the attribute and fight windows. Three genuinely different
-- pictures, so the [manual] line can tell hover from released. A face named as a STRING is one of these:
-- the game's resources, taken scaled like every IButton the client itself builds.
local ADD_U, ADD_D, ADD_H = "gfx/hud/buttons/addu", "gfx/hud/buttons/addd", "gfx/hud/buttons/addh"

local base, hold, armedText, armedFace   -- built in phase 1, judged in phase 2 (one tick later)

-- PHASE 2 — one tick after the statements below, so those controls are ARMED: they have been drawn, and
-- the face is no longer a choice. Everything here needs that tick; nothing else in the run does.
local function phase2()
  refuses("once the control is on screen the face setter refuses, naming that a face is chosen at build time",
          function() armedText:image(ADD_U, ADD_D) end, "already on screen")
  refuses("...and it refuses on an image button too: re-facing one is building a different widget",
          function() armedFace:image(ADD_U, ADD_D, ADD_H) end, "already on screen")
  check((armedText:text("Later") == armedText) and (armedText:text() == "Later"),
        "a CAPTION is not a face: widget:text(s) still writes a live button's caption after it is on screen",
        armedText:text())
  hold:destroy()
  eq("everything built here dies with the window it was put in, rebuilt controls included", treeCount(), base)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  -- The window the two [manual] lines need is built FIRST, so the tree count below measures exactly what
  -- the assertions build and nothing else. Its own lines are printed at the end, where they read.
  local up, down = hafen.asset():get("up.png"), hafen.asset():get("down.png")
  check((up ~= nil) and (down ~= nil),
        "the suite ships its own two PNG faces, and hafen.asset loaded them (a suite stands alone)",
        tostring(up))
  local demo = hafen.ui():window():title("040.2 — faces"):size(150, 70):position(60, 60)
  hafen.ui():button():parent(demo):position(20, 22):image(ADD_U, ADD_D, ADD_H)
    :onPress(function()
        hafen.log():write("040.2: pressed (resource faces)")
        demo:destroy()
      end)
  hafen.ui():button():parent(demo):position(90, 22):image(up, down)
    :onPress(function() hafen.log():write("040.2: pressed (asset faces)") end)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  base = treeCount()
  hold = hafen.ui():window():title("040.2 — scratch"):size(240, 120):position(420, 300)

  -- 1. THE PREMISE THIS TASK STANDS ON (D-085: a suite convinces alone). One builder, built bare, and what
  --    it gives you is a real client Button this addon owns -- with no face, because it has a caption.
  local b = hafen.ui():button():parent(hold):position(10, 10):text("Plain"):onPress(nop)
  check((b:type() == "Button") and (b:info().owned == true) and (b:image() == nil),
        "the premise: hafen.ui():button() builds a real client Button this addon owns, and it has no face",
        ("%s owned=%s image=%s"):format(b:type(), tostring(b:info().owned), tostring(b:image())))

  -- 2. THE SWAP. Three of the client's own resource names, while the control is still being built: the
  --    widget under the handle is replaced by an IButton, and nothing about the handle changes.
  local beforeSwap = treeCount()
  local chained = b:image(ADD_U, ADD_D, ADD_H)
  check(chained == b, "widget:image(up, down, hover) chains, like every other setter", tostring(chained))
  eq("...and the setter chose the class: the control is now the client's IButton", b:type(), "IButton")
  eq("...while the role a selector matches is unchanged -- both are buttons", b:role(), "button")
  eq("the rebuild REPLACES the widget rather than adding one", treeCount(), beforeSwap)

  -- 3. ...AND THE SWAP IS INVISIBLE FROM LUA. The handle you are chaining onto is the same object, still
  --    live, still owned, still where you put it -- and a lookup through a DIFFERENT door interns to that
  --    very value, which is the half a re-pointed field alone would not give.
  local found = false
  for _, w in ipairs(hafen.ui():all("@IButton")) do found = found or (w == b) end
  check(b:exists() and (b:info().owned == true) and found,
        "the Lua handle survives the rebuild: still live, still owned, and a fresh lookup is == to it",
        ("exists=%s owned=%s found=%s"):format(tostring(b:exists()), tostring(b:info().owned),
                                               tostring(found)))
  local p = b:position()
  check((b:parent() == hold) and (p.x == 10) and (p.y == 10),
        "...and so does its place in the tree: the same parent, the same coordinate",
        ("parent=%s %d,%d"):format(tostring(b:parent() == hold), p.x, p.y))
  eq("...and the handler installed before the face outlives the rebuild", b:onPress(), nop)
  local f = b:image()
  check((f.up == ADD_U) and (f.down == ADD_D) and (f.hover == ADD_H),
        "widget:image() reads the three faces back exactly as they were named",
        ("%s / %s / %s"):format(tostring(f.up), tostring(f.down), tostring(f.hover)))

  -- 4. THE OTHER SOURCE, AND THE TWO-FACE FORM. A face is a client resource name OR a hafen.asset handle --
  --    your own file, drawn at its own pixels -- and with two of them the hovered face defaults to the
  --    released one, exactly as the engine's own two-argument constructor does.
  local ib = hafen.ui():button():parent(hold):position(10, 40):image(up, down)
  local g, sz = ib:image(), ib:size()
  check((ib:type() == "IButton") and (g.up == up) and (g.down == down) and (g.hover == up),
        "two hafen.asset faces build an image button, and `hover` defaults to the released face",
        ("%s up=%s hover==up:%s"):format(ib:type(), tostring(g.up == up), tostring(g.hover == up)))
  check((sz.x == 24) and (sz.y == 24), "...and the control's box IS the picture (this asset is 24x24)",
        ("%dx%d"):format(sz.x, sz.y))
  eq("an image button has no caption: widget:text() reads nil rather than throwing", ib:text(), nil)
  refuses("...and widget:text(s) refuses on it, naming what it shows instead",
          function() ib:text("nope") end, "PICTURE")

  -- 5. THE REFUSALS, all on ONE control -- which is also how the last of them is proved: a face that does
  --    not resolve must leave the control exactly as it was, so every one of these was a non-event.
  local bad = hafen.ui():button():parent(hold):position(120, 10):text("Kept")
  refuses("two faces are the minimum: the released one is not optional",
          function() bad:image(ADD_U) end, "down is required")
  refuses("an explicit nil face is refused (R5), not read as an arity",
          function() bad:image(nil, ADD_D) end, "must not be nil")
  refuses("a face that is neither a handle nor a name says what the two are",
          function() bad:image(1, 2) end, "hafen.asset image handle")
  refuses("a resource the client does not have is refused where it was named",
          function() bad:image("gfx/hud/buttons/nosuchface", ADD_D) end, "no resource named")
  refuses("...and a string that is really a FILE in your own folder is sent to hafen.asset by name",
          function() bad:image("up.png", "down.png") end, "looks like a file")
  check((bad:type() == "Button") and (bad:text() == "Kept") and (bad:image() == nil),
        "every one of those was a non-event: a face that fails to resolve leaves the control as it was",
        ("%s %s"):format(bad:type(), tostring(bad:text())))

  -- 6. AND IT IS STILL A CONTROL VERB ON A CONTROL YOU OWN. A painted surface has no face, and a button the
  --    CLIENT built is not ours to re-face -- the 040.1 provenance test, re-asserted on this verb.
  local plain = hafen.ui():widget():parent(hold):position(120, 40)
  refuses("a surface you paint yourself has no face, and the refusal names the builder that does",
          function() plain:image(ADD_U, ADD_D) end, "no face")
  local nat = nativeButton()
  check(nat ~= nil, "the client's own tree has a button that is not ours (a window's close box, at least)",
        tostring(nat))
  if nat ~= nil then
    refuses("...and the face setter refuses on it, naming it a native widget",
            function() nat:image(ADD_U, ADD_D) end, "NATIVE widget")
  end

  -- 7. What phase 2 judges: one of each, left on screen to be armed by the next tick.
  armedText = hafen.ui():button():parent(hold):position(120, 70):text("Armed")
  armedFace = hafen.ui():button():parent(hold):position(200, 70):image(up, down)

  -- 8. The two things a program cannot judge: that the three faces are THREE, and that a control the addon
  --    built behaves like the client's own.
  manual = manual + 1
  hafen.log():write("[manual] hover the LEFT button in the window \"040.2 — faces\" at 60,60, then press it"
                    .. " -- expect: three different pictures (the client's own [+] stepper: released, a"
                    .. " lighter one under the cursor, a pressed one while held), the log printing"
                    .. " \"040.2: pressed (resource faces)\", and the window closing itself")
  manual = manual + 1
  hafen.log():write("[manual] press the RIGHT button in that window, the blue [+] circle"
                    .. " -- expect: a face from this addon's own PNG files, darker while held, the log"
                    .. " printing \"040.2: pressed (asset faces)\", and NO change under the cursor (it was"
                    .. " given two faces, so hover is the released one)")

  hafen.timer():after(0.5, phase2)   -- one tick to arm what is on screen; phase 2 closes the run
end

hafen.slash():register("t040-2", run)   -- the only way in: a suite does not start itself
