-- 040.3 — display: label, image, separator, progress. Self-checking suite; see specs/addons/TESTING.md.
--
-- FOUR MORE REAL CLIENT WIDGETS: a live-restyling text Label, a static-picture Img, a horizontal-rule
-- HRuler and a fill-fraction Progress bar. Three of them answer nothing new but the verbs every control
-- already has; the two genuinely new names this task ships are :value() (what a control HOLDS -- the
-- progress bar is the first to answer it) and :source(h) (a picture's own content, decision E's other
-- half: hafen.ui():image():source(h), never :image():image(h)).
--
-- THE PLAN'S ASSUMPTION THAT DID NOT SURVIVE READING THE SOURCE: hafen.ui():label() was expected to
-- complete as haven.ILabel via :image(), the way :button() completes to IButton. ILabel carries no
-- picture at all -- its constructor takes a Text.Furnace, a font baked once and never live-restyled --
-- so it is not shipped. hafen.ui():label() builds a plain Label only, and :image() stays exactly what it
-- was: a button/checkbox face, refused on a label naming the builder that has one. That refusal is
-- asserted below, not just described here.

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

-- How many widgets are in the whole client tree right now. Destroying the scratch window must return this
-- to what it was before anything here was built.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

-- The client's own art (validated by 040.2's suite as loading locally, no network needed).
local ADD_U = "gfx/hud/buttons/addu"

local function run()
  -- The window the [manual] line needs is built FIRST, so `base` below measures exactly what the
  -- assertions build and nothing else.
  local demo = hafen.ui():window():title("040.3 — display"):size(180, 130):position(60, 60)
  hafen.ui():label():parent(demo):position(10, 10):text("Stamina")
  hafen.ui():image():parent(demo):position(10, 30):source(ADD_U)
  hafen.ui():separator():parent(demo):position(10, 60):size(160, 1)
  hafen.ui():progress():parent(demo):position(10, 75):size(160, 20):value(0.75)
  hafen.timer():after(90, function() if demo:exists() then demo:destroy() end end)

  local base = treeCount()
  local scratch = hafen.ui():window():title("040.3 — scratch"):size(240, 160):position(420, 300)

  -- 1. THE PREMISE (D-085: a suite convinces alone). Four more verbs on the one section object, built
  --    bare (R4) like every other builder here.
  local names = {"label", "image", "separator", "progress"}
  local allFns = true
  for _, n in ipairs(names) do
    if type(hafen.ui()[n]) ~= "function" then allFns = false end
  end
  check(allFns, "hafen.ui() carries all four builders as verbs on the one section object", tostring(allFns))
  local badArg
  for _, n in ipairs(names) do
    local ok = pcall(function() return hafen.ui()[n](hafen.ui(), "x") end)
    if ok then badArg = n end
  end
  check(badArg == nil, "all four builders take no arguments (R4)", tostring(badArg))

  -- 2. LABEL: a real client Label, and :text(s) is R2 completing a read every text-bearing widget already
  --    had. Its self-resize is ASSERTED, not assumed -- Label.settext calls resize() itself.
  local lbl = hafen.ui():label():parent(scratch):position(10, 10):text("Hi")
  local s1 = lbl:size()
  lbl:text("A much longer caption than that one")
  local s2 = lbl:size()
  check((lbl:type() == "Label") and (lbl:role() == "label") and (s2.x > s1.x),
        "hafen.ui():label() builds a real Label, and widget:text(s) resizes it -- the caption is not merely stored",
        ("type=%s role=%s %d -> %d px wide"):format(lbl:type(), tostring(lbl:role()), s1.x, s2.x))
  eq("a label holds nothing: widget:value() reads nil rather than throwing", lbl:value(), nil)
  refuses("...and a WRITE refuses, naming the builder that does hold something",
          function() lbl:value(1) end, "hafen.ui():progress()")
  refuses("a label has no FACE either -- ILabel is not shipped, so :image() stays the button/checkbox setter",
          function() lbl:image(ADD_U, ADD_U) end, "no face")

  -- 3. IMAGE: a real client Img, its content :source(h) -- NOT :image(h), so the builder never reads as
  --    image():image(h) (decision E). Unlike a button's face this is live at any time, not building-only.
  local img = hafen.ui():image():parent(scratch):position(10, 60):source(ADD_U)
  local isz = img:size()
  check((img:type() == "Img") and (img:source() == ADD_U) and (isz.x > 1) and (isz.y > 1),
        "hafen.ui():image():source(h) builds a real Img and installs the picture (the box is no longer a placeholder)",
        ("type=%s source=%s %dx%d"):format(img:type(), tostring(img:source()), isz.x, isz.y))
  eq("a picture holds nothing: widget:value() reads nil rather than throwing", img:value(), nil)
  refuses("widget:source(nil) is refused (R5), not read as an arity",
          function() img:source(nil) end, "must not be nil")
  refuses("widget:source(h) refuses on a control with no picture, naming the builder that has one",
          function() lbl:source(ADD_U) end, "hafen.ui():image()")

  -- 4. SEPARATOR: a real client HRuler, with no verb of its own -- the plain word over :ruler() (decision F).
  local sep = hafen.ui():separator():parent(scratch):position(10, 90):size(180, 1)
  check(sep:type() == "HRuler", "hafen.ui():separator() builds a real HRuler", sep:type())
  eq("a separator holds nothing: widget:value() reads nil rather than throwing", sep:value(), nil)

  -- 5. PROGRESS: a real client Progress bar, the FIRST control that answers :value() rather than merely
  --    reading nil on it. Round-tripped with fractions exact in float (0.25/0.75), a write outside 0..1
  --    refused (not clamped), and an explicit nil refused like every other verb (R5).
  local p = hafen.ui():progress():parent(scratch):position(10, 110):size(180, 20)
  check(p:type() == "Progress", "hafen.ui():progress() builds a real Progress bar", p:type())
  check((p:value(0.25) == p) and (p:value() == 0.25) and (p:value(0.75) == p) and (p:value() == 0.75),
        "widget:value(v) round-trips both ways on a progress bar, and chains",
        ("%s"):format(tostring(p:value())))
  refuses("a write above the range is refused, not clamped", function() p:value(1.5) end, "0..1")
  refuses("a write below the range is refused, not clamped", function() p:value(-0.25) end, "0..1")
  refuses("a non-number write is refused naming the type it wants",
          function() p:value("lots") end, "NUMBER")
  refuses("widget:value(nil) is refused (R5), not read as an arity",
          function() p:value(nil) end, "must not be nil")

  -- 6. ALL FOUR ARE FOUND BY CLASS, and widget:parent() is the scratch window they were built in -- "built
  --    and placed", proven rather than assumed.
  local foundL, foundI, foundS, foundP = false, false, false, false
  for _, w in ipairs(hafen.ui():all("@Label")) do foundL = foundL or (w == lbl) end
  for _, w in ipairs(hafen.ui():all("@Img")) do foundI = foundI or (w == img) end
  for _, w in ipairs(hafen.ui():all("@HRuler")) do foundS = foundS or (w == sep) end
  for _, w in ipairs(hafen.ui():all("@Progress")) do foundP = foundP or (w == p) end
  check(foundL and foundI and foundS and foundP
        and (lbl:parent() == scratch) and (img:parent() == scratch)
        and (sep:parent() == scratch) and (p:parent() == scratch),
        "a class selector finds each of the four, all parented to the window they were built in",
        ("label=%s image=%s separator=%s progress=%s"):format(
          tostring(foundL), tostring(foundI), tostring(foundS), tostring(foundP)))

  -- 7. THE TWO NEW VERBS ARE STILL CONTROL VERBS ON A CONTROL YOU OWN -- the 040.1 provenance test,
  --    re-asserted on both of them, since neither existed before this task.
  local root = hafen.ui():root()
  refuses("widget:value(v) refuses on a NATIVE widget, naming it native",
          function() root:value(1) end, "NATIVE widget")
  refuses("widget:source(h) refuses on a NATIVE widget, naming it native",
          function() root:source(ADD_U) end, "NATIVE widget")

  -- 8. TEARDOWN GIVES THE TREE BACK. Destroying the window all four were parented to ends them with it.
  local grown = treeCount()
  scratch:destroy()
  local after = treeCount()
  check((grown > base) and (after == base),
        "destroying the window ends every control parented to it, and the tree returns to the size it started",
        ("%d before, %d built, %d after"):format(base, grown, after))

  -- 9. The one thing a program cannot judge: that all four look like the client's own.
  manual = manual + 1
  hafen.log():write("[manual] look at the window \"040.3 — display\" at 60,60"
                    .. " -- expect: a text label reading \"Stamina\", a small picture below it (the client's"
                    .. " own + stepper art, gfx/hud/buttons/addu), a thin horizontal rule under that, and a"
                    .. " progress bar filled 3/4 of the way (it closes itself in 90s)")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-3", run)   -- the only way in: a suite does not start itself
