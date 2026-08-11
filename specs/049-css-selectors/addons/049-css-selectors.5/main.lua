-- 049.5 — the docs tier for the CSS selector grammar. Self-checking suite; see specs/testing/addon-suite.md.
--
-- A docs task's own claim is that the reference does not teach a selector that errors. So the suite's spine
-- is a SWEEP: every selector string the user-facing reference quotes is driven through the real parser, and
-- every spelling the reference marks as refused must actually refuse, naming its replacement. Around that
-- spine sit the behavioural claims those pages make -- the operators, [res=] exact vs [res*=], the space as
-- descendant, strict find, the scoped doors, and a chain used as a stylesheet key.
--
-- D-085: it stands alone. Every premise it leans on (049.1's grammar, 049.2's strict/scoped doors, 049.3's
-- chain-as-tree-key) is re-asserted HERE rather than delegated to those tasks' suites, which nobody runs.
--
-- Two shapes are borrowed from 049.1/049.3 because they are the only ones that work: a built window is NOT
-- in the tree until the next tick (UiApi's armPending), so the checks run from a timer; and the captions
-- carry SPACES on purpose, because the combinator splits on whitespace and must not cut inside a [ ] value.

local pass, fail, manual = 0, 0, 0

-- hafen.log is ASCII-only in practice (the console mangles the rest), and the reference quotes selectors
-- containing a Unicode ellipsis as a placeholder value. A verdict line must stay readable, so it is folded.
local function ascii(s)
  return (tostring(s):gsub("[\128-\255]+", "..."))
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. ascii(got))
  end
end

-- ======================================================= the reference's own selectors (sliced by the probe)

-- Every selector string the user-facing reference quotes as a selector to WRITE. Two shapes it also quotes
-- are deliberately not here: a fragment naming a refiner KEY in prose ("[title=]", "[res=]"), and a
-- metavariable standing for a name the reader supplies ("selector", "@Class", a bare ellipsis). A
-- placeholder VALUE ("window[title=<ellipsis>]") IS here -- a value is free text, so that spelling parses.
local QUOTED = {
  -- the roles the reference tabulates, and the site names it keeps valid on purpose
  "*", "window", "inventory", "button", "label", "textentry", "chat", "menu",
  "window.title", "window.frame", "panel", "heading", "tooltip", "world.nick", "world.speech",
  -- @Class steps
  "@Equipory", "@GameUI", "@MapView", "@Frame", "@Window", "@DefaultDeco",
  -- attribute steps
  "window[title=Cupboard]", "window[title=Inventory]", "window[title=Equipment]", "window[title=Chest]",
  "window[title=…]", "*[res*=gfx/hud/meter]", "[res*=gfx/hud/meter]", "[res=gfx/hud/meter]",
  -- chains
  "window[title=Cupboard] inventory", "window[title=Inventory] inventory",
  "window[title=Foo] button[text=Close]", "window[title=Cupboard] *", "window[title=Cupboard] label",
  "window[title=Inventory] label", "window[title=Inventory] inventory@Inventory",
  "window[title=…] @Frame", "window[title=…] @DefaultDeco",
}

-- Every spelling the reference states is refused, with the substring its error must hand back. The first
-- four are the disjointness rule the pages teach; the last two are the error catalogue they promise.
local REFUSED = {
  { "inventory[title=Cupboard]", "window[title=Cupboard] inventory" },
  { "[title=Cupboard]",          "window[title=Cupboard]" },
  { "*[title=Cupboard]",         "window[title=Cupboard]" },
  { "window[text=Cupboard]",     "[title=Cupboard]" },
  { "label[txt=Close]",          "$= (ends with)" },
  { "notarole",                  "is not a role" },
}

-- ============================================================================ end of the sliced section

local CAP_A, CAP_B = "049 Docs A", "049 Docs B"
local TXT_A, TXT_B = "Docs049-A", "Docs049-B"

local winA, labA, winB, labB, winZ, sheet

local function refusalHolds(sel, want)
  local ok, err = pcall(function() return hafen.ui():all(sel) end)
  if ok then return false, "<no error>" end
  err = tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  return (err:find(want, 1, true) ~= nil), err
end

local function checks()
  local A = ("window[title=%s]"):format(CAP_A)

  -- 1. THE SWEEP. Every selector the reference quotes must parse; :all() is the door that raises for one
  -- reason only (a parse error), where find() would also raise on an ambiguous -- and perfectly legal -- one.
  local bad, first = 0, nil
  for _, sel in ipairs(QUOTED) do
    local ok, err = pcall(function() return hafen.ui():all(sel) end)
    if not ok then
      bad = bad + 1
      first = first or (sel .. " -> " .. tostring(err))
    end
  end
  check(bad == 0, ("every selector the reference quotes parses (%d of %d)"):format(#QUOTED - bad, #QUOTED),
        first)

  -- 2. ...and the site names among them stay valid grammar that classifies nothing, which is what lets the
  -- sheet and the lookups share one vocabulary instead of keeping two.
  local sites = { "window.title", "window.frame", "panel", "heading", "tooltip", "world.nick", "world.speech" }
  local nonEmpty = 0
  for _, s in ipairs(sites) do
    if #hafen.ui():all(s) > 0 then nonEmpty = nonEmpty + 1 end
  end
  check(nonEmpty == 0, ("every render-site name is a valid selector matching no widget (%d of %d)"):format(
        #sites - nonEmpty, #sites), nonEmpty .. " matched something")

  -- 3. The four operators the grammar table promises, with = exact rather than a prefix.
  local function hits(s) return #hafen.ui():all(A .. " " .. s) end
  check((hits("label[text=" .. TXT_A .. "]") == 1) and (hits("label[text*=cs049]") == 1)
        and (hits("label[text^=Docs]") == 1) and (hits("label[text$=049-A]") == 1)
        and (hits("label[text=Docs]") == 0),
        "the four operators work and = is EXACT: a prefix under = matches nothing")

  -- 4. [res=] is exact and [res*=] is the substring form -- the one migration nothing can catch at runtime,
  -- and the pair the reference spells out on the meters.
  local exact, sub = #hafen.ui():all("*[res=gfx/hud/meter]"), #hafen.ui():all("*[res*=gfx/hud/meter]")
  check((exact == 0) and (sub > 0),
        "[res=gfx/hud/meter] is exact and matches nothing; [res*=] catches every meter",
        ("exact %d (want 0), substring %d (want > 0)"):format(exact, sub))

  -- 5. The space is DESCENDANT at any depth, and a widget is never its own descendant.
  local inside, sawWin, deep = hafen.ui():all(A .. " *"), false, 0
  for i = 1, #inside do if inside[i] == winA then sawWin = true end end
  for _, w in ipairs(hafen.ui():all("window *")) do
    local p, hops = w:parent(), 1
    while p and (p:role() ~= "window") do p = p:parent(); hops = hops + 1 end
    if p and (hops > 1) then deep = deep + 1 end
  end
  check((#inside > 0) and not sawWin and (deep > 0),
        "the space reaches descendants at any depth, and never the anchor itself",
        ("%d inside, anchor among them=%s, deeper than one hop=%d"):format(#inside, tostring(sawWin), deep))

  -- 6. The reference's headline example shape: one string, one exact nested widget.
  check(hafen.ui():find(A .. " label[text=" .. TXT_A .. "]") == labA,
        "a chain plus an attribute names ONE exact nested widget")

  -- 7. find answers nil for none and REFUSES two or more, saying how many and naming the collection form.
  local none = hafen.ui():find("window[title=049 No Such Window]")
  local ok, err = pcall(function() return hafen.ui():find("*") end)
  err = ok and "<no error>" or tostring(err)
  check((none == nil) and (not ok) and (err:find("matches", 1, true) ~= nil)
        and (err:find('all("*")[i]', 1, true) ~= nil),
        "find is nil for no match and RAISES for two or more, naming :all(sel)[i]",
        ("no match -> %s | ambiguous -> %s"):format(tostring(none), err))

  -- 8. The scoped doors search one subtree, the widget itself included -- and the scope narrows the
  -- CANDIDATES, never the selector, so an ancestor step still names a widget above the scope.
  local aboveScope = labA:all(A .. " label[text=" .. TXT_A .. "]")
  check((winA:find(A) == winA) and (winA:find("label[text=" .. TXT_A .. "]") == labA)
        and (#winB:all("label[text=" .. TXT_A .. "]") == 0)
        and (#aboveScope == 1) and (aboveScope[1] == labA),
        "w:find/w:all search one subtree, itself included, while an ancestor step still reaches above it")

  -- 9. ...and on a departed subject both REFUSE, where every flat read answers nil or empty.
  winZ:destroy()
  local gone1 = not pcall(function() return winZ:find("label") end)
  local gone2 = not pcall(function() return winZ:all("label") end)
  check(gone1 and gone2, "both scoped doors refuse a widget that has left the tree",
        ("find raised=%s, all raised=%s"):format(tostring(gone1), tostring(gone2)))

  -- 10. A chain is a legal sheet key, and it dresses the descendant it names -- not a same-shaped widget
  -- under another caption, and not the window the chain merely walks THROUGH.
  sheet = hafen.ui():sheet()
  sheet:load{ [A .. " label"] = { color = { 200, 180, 140 } } }:install()
  local sA, sB, sW = labA:style(), nil, winA:style()
  for _ = 1, 25 do sB = labB:style() end   -- settled past the re-fold countdown: a negative, not a countdown
  check(sA and sA.color and (sA.color.r == 200) and (sB == nil) and (sW == nil),
        "a chain used as a sheet key styles the descendant alone",
        ("target %s | sibling %s | window %s"):format(
          tostring(sA and sA.color and sA.color.r), tostring(sB), tostring(sW)))

  -- 11-12. Every spelling the reference marks refused is refused, naming its replacement.
  local dis, disErr, cat, catErr = 0, nil, 0, nil
  for i, r in ipairs(REFUSED) do
    local held, err = refusalHolds(r[1], r[2])
    if i <= 4 then
      if held then dis = dis + 1 else disErr = disErr or (r[1] .. " -> " .. err) end
    else
      if held then cat = cat + 1 else catErr = catErr or (r[1] .. " -> " .. err) end
    end
  end
  check(dis == 4, ("every retired spelling refuses, naming its replacement (%d of 4)"):format(dis), disErr)
  check(cat == 2, ("the error catalogue holds: a bad refiner key lists the operators, a bad role lists"
        .. " the roles (%d of 2)"):format(cat), catErr)

  -- 13. Nothing survives the run: the suite is read-only about the client's own state. The dropped sheet is
  -- read back while the label is still THERE -- a departed widget is a different question, asked above.
  sheet:drop()
  local dropped = labA:style()
  winA:destroy()
  winB:destroy()
  check((dropped == nil) and (#hafen.ui():all(A) == 0)
        and (#hafen.ui():all(("window[title=%s]"):format(CAP_B)) == 0) and not winZ:exists(),
        "the suite leaves no window and no installed sheet behind", tostring(dropped))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function cleanup()
  if sheet then pcall(function() sheet:drop() end) end
  for _, w in ipairs({ winA, winB, winZ }) do
    if w and w:exists() then pcall(function() w:destroy() end) end
  end
end

local function run()
  winA = hafen.ui():window():title(CAP_A):size(220, 80):position(120, 120)
  labA = hafen.ui():label():parent(winA):position(8, 8):text(TXT_A)
  winB = hafen.ui():window():title(CAP_B):size(220, 80):position(360, 120)
  labB = hafen.ui():label():parent(winB):position(8, 8):text(TXT_B)
  winZ = hafen.ui():window():title("049 Docs Z"):size(160, 60):position(600, 120)
  -- The builder arms the add for the NEXT tick, so nothing above is findable or styleable yet.
  hafen.timer():after(0.3, function()
    local ok, err = pcall(checks)
    if not ok then
      hafen.log():write("[fail] the suite itself raised -- got: " .. ascii(err))
      cleanup()
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    end
  end)
end

hafen.slash():register("t049-5", run)   -- the only way in: a suite does not start itself
