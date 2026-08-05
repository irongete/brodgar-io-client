-- 039.7 — the stylesheet: Sheet and Rule. Self-checking suite; see specs/addons/TESTING.md.
--
-- hafen.ui.skin{…} was the last config table in this section and the densest one in the API. A stylesheet is
-- a selector -> properties MAP, which has no chained spelling of its own, so the answer is a DOCUMENT you
-- edit and then apply: hafen.ui():sheet() is this addon's one, sheet:rule(selector) is a level of the
-- cascade interned per selector, and that level's properties are setters with matching bare reads.
-- :install() puts the document in force and :drop() takes it off -- and an edit to an INSTALLED sheet lands
-- at once, because a sheet is either what the client looks like or it is not.
--
-- THE HEADLINE IS THE DATA DOOR. 036.4's result was a whole theme for zero lines of Lua: a parsed
-- theme.json handed to the sheet unmapped. A chain of setters cannot express a file, so sheet:load(t) keeps
-- that door -- this suite ships a sheet.json, hands the parsed table over unchanged, and checks the geometry
-- the file's own numbers predict, to the pixel.
--
-- The other three claims are the ones a shape change could quietly break: a layout-only sheet still costs
-- the DRAW nothing (one string in two windows, one text-cache key -- with the same rule plus a font taking
-- two, which is the falsification built into the run); widget:rule():remove() drops YOUR level and
-- re-resolves onto the rule beneath rather than to stock; and `position` and `anchor` stay ONE property,
-- refused when a loaded table says both, replaced when the later setter says the other.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, destroys every window it builds and
-- drops its sheet before it prints.

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

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end
local function pt(x, y) return ("%d,%d"):format(x, y) end

local FILE = "sheet.json"
local ANCH, PLACED, PLAIN, STYLED = "039.7 anchored", "039.7 placed", "039.7 plain", "039.7 styled"
local SEL_A = "window[title=" .. ANCH .. "]"
local SEL_P = "window[title=" .. PLACED .. "]"
local SEL_S = "window[title=" .. STYLED .. "]"
local LINE = "039.7 sheet cost probe"
local WARM, COLD = { 200, 180, 140 }, { 90, 140, 200 }

-- This addon's ONE sheet, handed back by identity: the document every rule below belongs to.
local sheet = hafen.ui():sheet()

local doc                                 -- the parsed sheet.json -- and, unchanged, the sheet
local wins = {}

local function win(w)
  wins[#wins + 1] = w
  return w
end

local function killWins()
  for i = 1, #wins do
    if wins[i]:exists() then wins[i]:destroy() end
  end
  wins = {}
end

-- A probe window that DRAWS one line every frame, which is what puts a key in the rendered-text cache.
local function probe(title, y)
  return win(hafen.ui():window()
    :title(title)
    :size(190, 24)
    :position(4, y)
    :onDraw(function(g) g:text(LINE, 6, 4) end))
end

-- The rendered-text cache is pull-only, so the cost round needs nothing armed; a counter that cannot be read
-- at all reddens its own lines rather than killing the round before it prints a summary.
local function misses()
  local ok, n = pcall(function() return hafen.client:profiling():textcache().misses end)
  return (ok and n) or 0
end

-- Every property of a rule, with the write that must return the rule and the read that must give it back.
-- One table so eight are two verdict lines rather than sixteen: a failure names the property it was.
local function props(r)
  local h = hafen.font("mono")
  local img = hafen.asset("panel.png")
  return {
    { "font",   function() return r:font(h) end, function() return r:font() == h end },
    { "color",  function() return r:color(200, 210, 220) end,
                function() local c = r:color() return (c ~= nil) and (c.r == 200) and (c.b == 220) end },
    { "bg",     function() return r:bg{ color = { 26, 26, 28, 240 } } end,
                function() local b = r:bg() return (b ~= nil) and (b.color ~= nil) and (b.color.a == 240) end },
    { "border", function() return r:border{ image = img, slice = { 8, 8, 8, 8 } } end,
                function() local b = r:border() return (b ~= nil) and (b.image == img) and (b.slice.l == 8) end },
    { "pad",    function() return r:pad(6) end, function() return r:pad() == 6 end },
    { "size",   function() return r:size(180, 90) end,
                function() local s = r:size() return (s ~= nil) and (s.x == 180) and (s.y == 90) end },
    { "position", function() return r:position(40, 200) end,
                function() local p = r:position() return (p ~= nil) and (p.x == 40) and (p.y == 200) end },
    -- LAST, and deliberately: `anchor` is the same property as `position`, so it replaces it -- which is
    -- what the check after the loop reads, and why the loop reads each property as it writes it.
    { "anchor", function() return r:anchor{ to = "screen", at = "center" } end,
                function() local a = r:anchor() return (a ~= nil) and (a.at == "center") end },
  }
end

-- ---- the cost round: does a layout-only sheet reach the DRAW? ---------------------------------------

local function costRound(after)
  killWins()
  sheet:drop()
  local m0 = misses()
  probe(ANCH, 4)                                -- the file's anchor rule names THIS one
  probe(PLAIN, 34)                              -- ...and nothing names this one
  hafen.timer():after(0.7, function()
    -- 1. no sheet: the two windows draw the same string, so they share ONE key.
    eq("with no sheet the two windows draw one string under one key", misses() - m0, 1)
    sheet:load(doc.rules):install()             -- ...a LAYOUT-ONLY sheet, straight out of the file
    local m1 = misses()
    hafen.timer():after(0.7, function()
      -- 2. THE CLAIM: the rule reached the widget (it moved) without reaching the draw. One key, still
      --    shared -- a rule change bumps the generation once for everybody, which is that one; a DRAWING
      --    rule would open a frame over the window it names and make it two.
      eq("a layout-only sheet moves a window without reaching the draw: one string, still ONE key",
         misses() - m1, 1)
      -- 3. the falsification, built in -- and it is an edit to an INSTALLED sheet, so it also proves that
      --    a setter on one applies at once, with no second :install().
      sheet:rule(SEL_A):font(hafen.font("serif"):derive{ size = 18 })
      local m2 = misses()
      hafen.timer():after(0.7, function()
        eq("the very same rule with a font in it takes a second key, so the check above can fail",
           misses() - m2, 2)
        sheet:drop()
        killWins()
        after()
      end)
    end)
  end)
end

-- ---- the run ----------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0     -- so a re-run through :t039-7 reports its own counts
  killWins()
  sheet:drop()

  -- 1. THE SECTION VERB AND THE TWO CUTS. A sheet is per addon and handed back by identity, and both old
  --    spellings throw naming what replaced them rather than reading as plain nil.
  eq("hafen.ui():sheet() is this addon's one sheet, the same object on every call",
     hafen.ui():sheet() == sheet, true)
  refuses("hafen.ui.skin{...} throws naming the sheet that replaced it",
          function() return hafen.ui.skin end, "hafen.ui():sheet()")
  refuses("widget:skin{...} throws naming widget:rule()",
          function() return hafen.ui():root().skin end, "widget:rule()")

  -- 2. A RULE IS A NAME FOR A LEVEL: interned per selector, climbing back to its sheet so a whole sheet is
  --    one expression, and saying nothing until something is set on it.
  local r = sheet:rule("@NoSuchWidgetClass")     -- a tree key that matches nothing: every property is legal
  eq("sheet:rule(selector) is interned per selector", sheet:rule("@NoSuchWidgetClass") == r, true)
  eq("...and rule:sheet() climbs back to the sheet it belongs to", r:sheet() == sheet, true)
  eq("rule:selector() is the key it was named by", r:selector(), "@NoSuchWidgetClass")
  eq("a rule nobody has set a property on says nothing", r:info(), nil)

  -- 3. EVERY PROPERTY IS A SETTER THAT CHAINS, AND A BARE READ THAT GIVES IT BACK. Eight of each, two lines:
  --    the `got` names the property that broke, which is the whole reason the table exists.
  local badSelf, badRead = nil, nil
  for _, p in ipairs(props(r)) do          -- write and read IN ONE PASS: `anchor` replaces `position`, so a
    if p[2]() ~= r then badSelf = (badSelf or p[1]) end   -- second sweep would read the slot it took over
    if not p[3]() then badRead = (badRead or p[1]) end
  end
  check(badSelf == nil, "all eight rule setters return the rule itself, so a level is one expression", badSelf)
  check(badRead == nil, "...and every one reads back bare: arity is the verb on a rule too", badRead)
  eq("position and anchor are ONE property: the later setter replaces the earlier", r:position(), nil)
  check(r:info() ~= nil and r:info().selector == "@NoSuchWidgetClass",
        "rule:info() is the whole level as a table, selector included", r:info())
  r:remove()
  eq("rule:remove() ends a level, and the name goes on answering", r:info(), nil)

  -- 4. THE HEADLINE: A SHEET IS DATA. A chain of setters cannot express a file, so the document keeps one
  --    door for a whole one -- and what goes through it is the table hafen.json():parse hands back, unmapped.
  local asset = hafen.asset(FILE)
  eq("a sheet is a file: it loads as a data asset", asset:type(), "data")
  doc = hafen.json():parse(asset:text())
  local an, pl = doc.rules[SEL_A].anchor, doc.rules[SEL_P]
  check((an.to == "screen") and (an.at == "bottomright") and (an.offset[1] == -8)
        and (pl.position[1] == 40) and (pl.size[1] == 180),
        "a layout arrives as plain data -- a named corner, a 1-indexed offset, position and size as arrays",
        hafen.json():encode(doc.rules))
  local rsz = hafen.ui():root():size()
  local anchored = win(hafen.ui():window():title(ANCH):size(150, 90):position(12, 12))
  local placed = win(hafen.ui():window():title(PLACED):size(100, 50):position(12, 130))
  local baseA, baseP, baseS = xy(anchored:position()), xy(placed:position()), placed:size()
  check(pcall(function() sheet:load(doc.rules):install() end),
        "the table hafen.json():parse returned IS the sheet: a whole file installs, unmapped")
  eq("the file's anchor holds a window 8 px in from the screen's bottom-right corner",
     xy(anchored:rootPos()), pt(rsz.x - anchored:size().x - 8, rsz.y - anchored:size().y - 8))
  eq("...and its position and size are the point and the content box it names, to the pixel",
     xy(placed:position()) .. " " .. xy(placed:size()), "40,200 " .. pt(baseS.x + 80, baseS.y + 40))
  sheet:drop()
  eq("dropping a sheet that came from a file restores the exact numbers it found",
     xy(anchored:position()) .. " " .. xy(placed:position()), baseA .. " " .. baseP)

  -- 5. widget:rule() IS THE SAME OBJECT ONE LEVEL UP, and its removal drops YOUR level rather than emptying
  --    the cascade (D-089): the rule underneath takes the widget back, and stock returns only when nothing
  --    names that half any more.
  local styled = win(hafen.ui():window():title(STYLED):size(120, 40):position(240, 12))
  sheet:load{ [SEL_S] = { color = COLD } }:install()
  eq("...and a load REPLACES the document whole: what the file said is no longer said",
     sheet:rule(SEL_A):info(), nil)
  eq("a tree rule colours the window it names", styled:style().color.r, 90)
  styled:rule():color(WARM)                     -- a colour VALUE passes straight into the setter
  eq("widget:rule() outranks the rule that matched it", styled:style().color.r, 200)
  styled:rule():remove()
  eq("widget:rule():remove() drops YOUR level and falls back to the rule beneath, not to stock",
     styled:style().color.r, 90)
  sheet:drop()
  eq("...and only dropping the sheet as well returns the widget to stock", styled:style(), nil)

  -- 6. THE REFUSALS. Two spellings of one property cannot be said at once where there is no "later" to pick
  --    from; the renamed key says so itself; and layout can only be said where something can be laid out.
  refuses("a loaded rule saying both position and anchor is refused, not resolved by table order",
          function() sheet:load{ [SEL_P] = { position = { 1, 1 }, anchor = { at = "center" } } } end,
          "said two ways")
  refuses("the sheet key `pos` is now `position`, and says so",
          function() sheet:load{ [SEL_P] = { pos = { 1, 1 } } } end, "is now \"position\"")
  refuses("layout on a SITE key is refused: a site is where the client draws, not something with a position",
          function() sheet:rule("*"):position(1, 1) end, "lays out a WIDGET")
  refuses("layout on a widget's own rule names the verb that IS that level",
          function() styled:rule():position(1, 1) end, "widget:position(x, y)")
  refuses("an unknown property throws naming the ones that exist: a misspelt property has no later meaning",
          function() sheet:rule("chat"):colour(1, 2, 3) end, "has no verb")
  refuses("a nil that would silently become a read is refused",
          function() sheet:rule("chat"):pad(nil) end, "must not be nil")
  eq("...and a refused sheet leaves the client exactly as it was", styled:style(), nil)

  -- 7. the cost round, which needs frames to happen in.
  costRound(function()
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

-- ON DEMAND ONLY (D-085). A suite does not start itself, and running THIS command alone is the whole
-- verification of task 039.7: it builds its own windows, loads its own file, and drops every sheet and
-- destroys every window before it prints. Its round stages ~2.1 s.
hafen.slash():register("t039-7", run)
