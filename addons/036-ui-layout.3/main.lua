-- 036.3 — anchors, and surviving a rescale. Self-checking suite; see specs/addons/TESTING.md.
--
-- `anchor` joins the sheet beside pos/size, and the claim worth proving is that it is not decoration: an
-- anchored widget's position is DERIVED, every time, from the geometry it names — the live root size, the
-- target widget's live box, its own live size — and never a point remembered when the rule was installed.
-- So every check here computes the answer from that geometry and compares, rather than reading the engine
-- back to itself; and the derivation is then driven by CHANGING what it reads (move the target, resize the
-- target) and asserting the anchored window moved with it, in the same call.
--
-- THE ONE THING THIS CLIENT CANNOT DO IS THE TASK'S OWN HEADLINE ASSERTION. `haven.UI.scale` is a static
-- final read once at class load — the Options slider itself says "requires restart" — so no program can
-- change the UI scale and re-read :pos(). What a rescale actually does is change the sizes an anchor reads,
-- and that IS driven here (the target's size, below). The screen's own size is the maintainer's [manual].
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and drops its sheet before it prints.

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

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end
local function pt(x, y) return ("%d,%d"):format(x, y) end
local function half(n) return math.floor(n / 2) end

-- A native window a [title=] selector names UNIQUELY, picked rather than named and SELF-VALIDATING (030.2's
-- inspector trick): a candidate counts only after hafen.ui.all() has actually resolved the selector to
-- exactly this widget, so the rules below run against a key the engine agrees with on whatever HUD this
-- happens to be. Captions carrying the grammar's own punctuation are skipped rather than escaped.
local function named()
  for _, w in ipairs(hafen.ui.all("window")) do
    local cap = w:text()
    if cap and cap ~= "" and not cap:find("[%[%]=]") then
      local sel = "window[title=" .. cap .. "]"
      local all = hafen.ui.all(sel)
      if (#all == 1) and (all[1] == w) then return w, sel end
    end
  end
end

-- Does the client's own pointer dispatch still reach `w` at this root-coord point? A window's chrome is a
-- CHILD (030: hovering a frame never hands you the window), so the hit is walked up rather than compared.
local function reaches(w, x, y)
  local hit = hafen.ui.at(x, y)
  while hit ~= nil do
    if hit == w then return true end
    hit = hit:parent()
  end
  return false
end

-- ---- the run ------------------------------------------------------------------------------------

local function run(args)
  local mode = args and args[1]
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t036-3 reports its own counts

  local w, sel = named()
  if mode == "drop" then          -- the [manual] line's cleanup: give the parked window back
    hafen.ui.skin(nil)
    hafen.log():write("[manual] the parked anchor rule is dropped -- the window is back where you had it")
    return
  end
  if w == nil then
    hafen.log():write("[fail] no HUD: run this in-world -- every check below needs one of the client's own windows")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  if mode == "hold" then          -- ...and its setup: park ONE anchored window for a human to look at
    hafen.ui.skin{ [sel] = { anchor = { to = "screen", at = "bottomright", offset = {-8, -8} } } }
    hafen.log():write("[manual] \"" .. w:text() .. "\" is anchored 8 px in from the screen's bottom-right corner."
      .. " Resize the client window, then run :t036-3 drop")
    return
  end
  hafen.ui.skin(nil)              -- start from a client this suite is holding nothing on

  -- 1. the premise, re-asserted here because everything below rests on it (D-085): a native window is
  --    reachable, is BORROWED, and a selector names it and nothing else.
  check(w ~= nil, "a native window a [title=] selector names uniquely, validated through hafen.ui.all()", sel)
  eq("...and it is a widget this addon did NOT create", w:info().owned, false)
  local stock, rsz = w:pos(), hafen.ui():size()

  -- 2. THE TASK'S SHAPE: `pos` is not a property beside `anchor`, it is the DEGENERATE anchor -- to the
  --    widget's own parent, at its top-left. One resolution path, asserted from both ends.
  hafen.ui.skin{ [sel] = { pos = {stock.x + 31, stock.y + 17} } }
  eq("a pos rule is the anchor to the parent's top-left: c IS the offset",
     xy(w:pos()), pt(stock.x + 31, stock.y + 17))
  hafen.ui.skin{ [sel] = { anchor = { to = "screen", at = "topleft", offset = {stock.x + 31, stock.y + 17} } } }
  eq("...and the same offset anchored to the SCREEN's top-left is that point in root coordinates",
     xy(w:rootpos()), pt(stock.x + 31, stock.y + 17))

  -- 3. ...and every other corner is the same arithmetic with the target's own: the widget's corner ON the
  --    target's, which is what makes offset = {-8,-8} read as "8 px in" rather than "mostly off-screen".
  hafen.ui.skin{ [sel] = { anchor = { to = "screen", at = "bottomright", offset = {-8, -8} } } }
  eq("an anchor derives its place from the LIVE root size: bottom-right, 8 px in",
     xy(w:rootpos()), pt(rsz.x - w:size().x - 8, rsz.y - w:size().y - 8))
  hafen.ui.skin{ [sel] = { anchor = { at = "center" } } }
  eq("...and centred, with every field on its default (the screen, no offset)",
     xy(w:rootpos()), pt(half(rsz.x) - half(w:size().x), half(rsz.y) - half(w:size().y)))

  -- 4. widget:style() reports the property the rule was WRITTEN with -- the read round-trips into a write.
  hafen.ui.skin{ [sel] = { anchor = { to = "screen", at = "bottomright", offset = {-8, -8} } } }
  local a = w:style().anchor
  check(a and (a.to == "screen") and (a.at == "bottomright") and (a.offset.x == -8) and (a.offset.y == -8)
        and (w:style().pos == nil),
        "widget:style() reports the anchor as it was written -- to, at and offset, and no second spelling",
        a and (tostring(a.to) .. "/" .. tostring(a.at) .. "/" .. xy(a.offset)))

  -- 5. AN ANCHOR IS A RELATIONSHIP, not a point: hung off another widget, it re-derives when that widget
  --    MOVES and when it RESIZES -- both in the same call, because the move went through this API.
  local probe = hafen.ui.window{ title = "036.3 anchor target", size = {160, 110}, pos = {260, 200} }
  hafen.ui.skin{ [sel] = { anchor = { to = probe, at = "topright", offset = {6, 0} } } }
  eq("a widget anchored to another sits on ITS corner", xy(w:rootpos()),
     pt(260 + probe:size().x - w:size().x + 6, 200))
  probe:pos(340, 260)
  eq("...and follows it the moment the target moves, in the same call and not a frame later",
     xy(w:rootpos()), pt(340 + probe:size().x - w:size().x + 6, 260))
  probe:size(240, 170)
  eq("...and re-derives from the target's SIZE too: an anchor reads geometry, it does not remember a point",
     xy(w:rootpos()), pt(340 + probe:size().x - w:size().x + 6, 260))

  -- 6. ...and a target that has left the tree is INERT, never a snap: the anchor holds it weakly, and the
  --    widget stays where it was rather than jumping back to stock the moment a window closed.
  local held = xy(w:rootpos())
  probe:destroy()
  eq("an anchor whose target is gone leaves the widget where it is", xy(w:rootpos()), held)

  -- 7. NOTHING BECOMES UNREACHABLE. An off-screen rule is handed to the client's OWN clamp (GameUI.fitwdg's
  --    100 px margin, re-derived not exposed) rather than to a second answer to "is this on screen". Asserted
  --    on a window of this addon's own, and for a reason: hit-testing answers with the TOPMOST widget under
  --    the point, so a check about reachability must not be able to redden because the HUD drew something
  --    over the corner the clamp chose. A widget added to the root last is the one thing nothing covers.
  local edge = hafen.ui.window{ title = "036.3 clamp probe", size = {150, 100}, pos = {80, 80} }
  hafen.ui.skin{ ["window[title=036.3 clamp probe]"] =
                 { anchor = { to = "screen", at = "topleft", offset = {rsz.x + 4000, rsz.y + 4000} } } }
  local p = edge:rootpos()
  check((p.x < rsz.x) and (p.y < rsz.y) and (p.x > 0) and (p.y > 0),
        "an off-screen anchor is clamped by the client's own rule, not by a second one", xy(p))
  -- ...and the point asked about is inside the window's CONTENT, not its corner: a window's chrome answers
  -- Window.checkhit through its deco, which owns the caption strip and the content area and not the
  -- transparent pixels between them -- so a corner probe would be asking about the frame's shape, not reach.
  local inner
  for _, ch in ipairs(edge:children()) do
    if ch:type() == "AddonWidget" then inner = ch end
  end
  local ip = inner and inner:rootpos()
  check((ip ~= nil) and reaches(edge, ip.x + 2, ip.y + 2),
        "...and the client's own hit dispatch still reaches the window there", xy(ip or p))
  edge:destroy()

  -- 8. ONE FOLD, TWO LEVELS (D-077/D-089), now with an anchor underneath: the hand-named verb outranks it,
  --    and the undo drops back to THE ANCHOR rather than to stock.
  hafen.ui.skin{ [sel] = { anchor = { to = "screen", at = "bottomright", offset = {-8, -8} } } }
  w:pos(stock.x + 5, stock.y + 3)
  eq("the hand-named verb outranks an anchor rule", xy(w:pos()), pt(stock.x + 5, stock.y + 3))
  w:pos(nil)
  eq("...and widget:pos(nil) drops back to THE ANCHOR, re-derived, not to the stock value",
     xy(w:rootpos()), pt(rsz.x - w:size().x - 8, rsz.y - w:size().y - 8))

  -- 9. dropping the sheet restores the exact numbers -- the whole point of a layer.
  hafen.ui.skin(nil)
  eq("dropping the sheet puts the window back exactly where the user had it", xy(w:pos()), xy(stock))

  -- 10. the refusals. Two are about WHERE layout may be said (D-088, re-asserted because an anchor is a new
  --     way to say it), and two are about saying it wrong: one question asked twice, and a typo.
  refuses("anchor on a site key is refused, because \"*\" is the default SITE and not every widget",
          function() hafen.ui.skin{ ["*"] = { anchor = { at = "center" } } } end, "lays out a WIDGET")
  refuses("widget:skin{anchor=} is refused: the hand-named level of the layout cascade is widget:pos(x, y)",
          function() w:skin{ anchor = { at = "center" } } end, "widget:pos(x, y)")
  refuses("pos and anchor in one rule is one question asked twice, and is refused",
          function() hafen.ui.skin{ [sel] = { pos = {1, 1}, anchor = { at = "center" } } } end,
          "same property said two ways")
  refuses("a misspelt corner is an error naming all nine, not a silent top-left (D-072)",
          function() hafen.ui.skin{ [sel] = { anchor = { at = "bottomrigth" } } } end, "expected one of")
  eq("...and a refused sheet leaves the client exactly as it was", xy(w:pos()), xy(stock))

  hafen.ui.skin(nil)
  manualCheck("run \":t036-3 hold\", then resize the client window (drag an edge, or maximise and restore),"
    .. " then run \":t036-3 drop\"",
    "the parked window stays 8 px in from the screen's BOTTOM-RIGHT corner the whole time and is back where"
    .. " you had it after \"drop\" -- a position derived from the live root size is the half of \"survives a"
    .. " rescale\" no program can drive here, since UI.scale is read once at startup (\"requires restart\")")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ON DEMAND ONLY (D-085). A suite does not start itself, and running THIS command alone is the whole
-- verification of task 036.3: it installs its own sheets, asserts through the API it ships, and drops
-- everything before it prints -- so a client it has run on is a stock client.
hafen.slash():register("t036-3", run)
