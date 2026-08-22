-- 088 -- A word means one thing I. The whole feature's suite, tasks 1 to 5, under one command.
--
-- Ten spellings meant more than one thing on the half of the API a user meets first, and two of them were
-- silently wrong: hafen.ui():list() BUILT a control and put it on screen where every other :list() in the
-- API enumerates a set, and widget:cell() was a SIZE wearing the name of a place -- so grid:cell(c.w, c.h)
-- fed from item:cell() read nil, nil and was taken, leaving the grid default-sized.
--
-- Each task is proved the way a rename is proved: the new spelling answers what the old one did, the old
-- one RAISES and names its replacement, and the words the rename frees still mean what they were freed
-- FOR. One verdict line per claim, scored, so a failure says which half of it broke.
--
-- The run buffers nothing: it opens a bounded window first, so the checks that need a character in the
-- world -- an item in a container, a recorded grid, a menu entry, traffic on the two event streams -- are
-- reached before the report is printed, and are scored over what the run got to.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- A GROUP of refusals, scored as one line. A refusal is a check like any other: the call must fail, and
-- fail SAYING what to write instead -- so the group records the first spelling that did neither, which is
-- the one thing a bare count would not tell you.
local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and (err:find(want, 1, true) ~= nil) then
      n = n + 1
    else
      why = why or (label .. " -> " .. err)
    end
  end
  function g.done(what)
    check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n)
  end
  return g
end

local function inWorld()
  local ok, p = pcall(function()
    return hafen.session():current():player():gob():position()
  end)
  return ok and (p ~= nil)
end

-- ========================================================================== 088.1 -- :list() enumerates

-- The severity of A-050 is the SIDE EFFECT, not the message: hafen.ui():list() reached Controls.list,
-- which attaches the control to the addon layer before the next statement runs. A retirement fired from
-- inside the builder would pass a message check and still leave that box on screen -- so the claim is one
-- in two halves, and the second half is the count of this addon's own widgets across the refused call.
local function listSection()
  local probe = hafen.ui():widget():size(1, 1)
  local root = probe:parent()
  local before = #root:children()
  local g = refusals()
  g.ask("hafen.ui():list()", function() return hafen.ui():list() end, "hafen.ui():listbox()")
  local after = #root:children()
  probe:destroy()
  check((after == before) and (root ~= nil),
        "hafen.ui():list() BUILDS NOTHING: the addon layer holds the same widgets across the refused call"
        .. " (" .. before .. ")", after)
  g.done("hafen.ui():list() raises naming :listbox()")

  local box = hafen.ui():listbox():position(40, 40):size(160, 60):rowHeight(20):rows{"Alpha", "Beta"}
  local n = 0
  if box:rowHeight() == 20 then n = n + 1 end
  local rows = box:rows()
  if (type(rows) == "table") and (#rows == 2) and (rows[1] == "Alpha") then n = n + 1 end
  box:destroy()
  check(n == 2, "hafen.ui():listbox() builds one that answers :rowHeight(20) and :rows{...} ("
        .. n .. "/2)", n)
end

-- hafen.vr() holds four kinds and its own visibility switch, so it cannot itself be the collection: the
-- cross-kind set is hafen.vr():entity(), and "how many, in total" is the question it exists to answer.
local function vrSection()
  local vr = hafen.vr()
  local sum = vr:ghost():count() + vr:sprite():count() + vr:object():count() + vr:widget():count()
  local n = 0
  if vr:entity():count() == sum then n = n + 1 end
  local l = vr:entity():list()
  if (type(l) == "table") and (#l == sum) then n = n + 1 end
  if type(vr:click("MouseMove", 4, 4, 1)) == "boolean" then n = n + 1 end
  check(n == 3, "hafen.vr():entity() counts across the four kinds and :click(...) answers a boolean ("
        .. n .. "/3)", n)

  local g = refusals()
  g.ask("hafen.ui():listbox(fn)", function() return hafen.ui():listbox(function() end) end,
        "chained setters")
  g.ask("hafen.vr():list(f)", function() return hafen.vr():list() end, ":entity()")
  g.ask("hafen.vr():pointer(...)", function() return hafen.vr():pointer("MouseMove", 4, 4, 1) end,
        ":click(")
  g.done("the three retired :list()/:pointer() spellings raise naming their replacement")
end

-- Counting and removing ACROSS the kinds is the whole of the reshape, and one standing thing proves both.
local function vrWorldSection()
  local p = hafen.session():current():player():gob():position()
  local base = hafen.vr():entity():count()
  local ghost = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)
  local n = 0
  if hafen.vr():entity():count() == (base + 1) then n = n + 1 end
  hafen.vr():entity():remove(ghost)
  if (hafen.vr():entity():count() == base) and (not ghost:exists()) then n = n + 1 end
  check(n == 2, "hafen.vr():entity() counts a ghost across the kinds and :remove(x) ends it ("
        .. n .. "/2)", n)
end

-- ============================================================ 088.2 -- the selector has its own verb

-- A subtree of our own needs no session, and a BARE widget is the right scope: a window's decoration
-- draws its own close box, which is an IButton and therefore a "button" to the selector -- a fact about
-- the chrome, not about the verb, and one a count would otherwise blame on the rename.
local function selectorSection()
  local box = hafen.ui():widget():size(180, 100):position(30, 30)
  local one = hafen.ui():button():parent(box):position(8, 8):text("one")
  local two = hafen.ui():button():parent(box):position(8, 34):text("two")
  local lbl = hafen.ui():label():parent(box):position(8, 62):text("only")

  local n = 0
  if box:match("label") == lbl then n = n + 1 end            -- exactly one match: that widget
  if box:match("textentry") == nil then n = n + 1 end        -- no match: nil, never a refusal
  local all = box:matchAll("button")
  if (type(all) == "table") and (#all == 2) then n = n + 1 end
  if (all[1] == one) and (all[2] == two) then n = n + 1 end  -- ...in tree order
  if #box:matchAll("textentry") == 0 then n = n + 1 end      -- empty, never nil
  check(n == 5, "w:match(sel) is one widget or nil and w:matchAll(sel) is an array in tree order ("
        .. n .. "/5)", n)

  local g = refusals()
  -- The refusal that is the verb's whole contract: "*" matches this widget and everything under it.
  g.ask("w:match(\"*\")", function() return box:match("*") end, "matchAll")
  g.ask("w:find(sel)", function() return box:find("label") end, "widget:match(selector)")
  g.ask("w:all(sel)", function() return box:all("button") end, "widget:matchAll(selector)")
  g.ask("hafen.ui():find(sel)", function() return hafen.ui():find("window") end,
        "ui():match(selector)")
  g.ask("hafen.ui():all(sel)", function() return hafen.ui():all("window") end,
        "ui():matchAll(selector)")
  g.done("the retired selector spellings raise, an ambiguous :match(sel) included")
  box:destroy()
end

-- A collection's :find is a FILTER: nil, a substring of the member's name, or a predicate. That is the
-- half of the old verb that must NOT have moved, and hafen.font() proves it without a session.
local function filterSection()
  local mono = hafen.font():get("mono")
  local n = 0
  if hafen.font():find("mo") == mono then n = n + 1 end                    -- a SUBSTRING, not a role
  if hafen.font():find(function(f) return f == mono end) == mono then n = n + 1 end
  if hafen.font():find("nosuchface") == nil then n = n + 1 end
  check(n == 3, "a collection's :find(filter) is still a substring or a predicate (" .. n .. "/3)", n)
end

local function treeSection()
  local s = hafen.session():current()
  local n = 0
  local all = s:ui():matchAll("*")
  if (type(all) == "table") and (#all > 1) then n = n + 1 end
  if s:ui():match("@NoSuchWidgetClass") == nil then n = n + 1 end
  if s:ui():match("@GameUI") == s:ui():match("@GameUI") then n = n + 1 end  -- interned: one object
  if pcall(function() return s:kin():find("") end) then n = n + 1 end       -- the filter half, untouched
  check(n == 4, "s:ui():match / :matchAll answer over that character's own tree (" .. n .. "/4)", n)

  local g = refusals()
  g.ask("s:ui():find(sel)", function() return s:ui():find("window") end, "ui():match(selector)")
  g.ask("s:ui():all(sel)", function() return s:ui():all("window") end, "ui():matchAll(selector)")
  g.done("s:ui():find and :all raise from the session's half too")
end

-- ================================================== 088.3 -- a place, a size and a hit test

local function sizeSection()
  local grid = hafen.ui():grid():position(30, 200):size(120, 120):cellSize(48, 48)
  local c = grid:cellSize()
  local n = 0
  if (type(c) == "table") and (c.w == 48) and (c.h == 48) then n = n + 1 end
  if c.x == nil then n = n + 1 end                    -- a SIZE: it has no x, and never had one

  -- A control's height is its own ART's, so a button takes the width alone: the box below is the one
  -- the client will actually give it, which is what makes the hit test's arithmetic honest.
  local box = hafen.ui():widget():size(160, 80):position(40, 40)
  local btn = hafen.ui():button():parent(box):position(10, 10):size(60):text("hit me")
  local p = btn:rootPos()
  if box:hit{ x = p.x + 4, y = p.y + 4 } == btn then n = n + 1 end
  check(n == 3, "widget:cellSize() is a box and w:hit(coord) is a search of one subtree ("
        .. n .. "/3)", n)

  local g = refusals()
  g.ask("widget:cell(w, h)", function() return grid:cell(48, 48) end, "widget:cellSize(w, h)")
  g.ask("w:at(coord)", function() return box:at{ x = p.x, y = p.y } end, "widget:hit(coord)")
  g.ask("hafen.ui():at(x, y)", function() return hafen.ui():at(4, 4) end, "hafen.ui():hit(x, y)")
  g.ask("hafen.ui():hit()", function() return hafen.ui():hit() end, "hafen.ui():hit: x is required")
  g.done("the retired :cell/:at spellings raise, and :hit() names its two numbers")
  grid:destroy()
  box:destroy()
end

-- `at` is also a corner name inside a declarative anchor. A blind package-wide rename would have broken
-- every stylesheet that places a widget, and nothing else in the API would have said so.
local function anchorSection()
  local sheet = hafen.ui():sheet()
  local ok = pcall(function()
    sheet:rule("window[title=Nothing]"):anchor{ at = "bottomright", offset = {-4, -4} }
    sheet:install()
  end)
  check(ok, "a rule written with anchor = { at = \"bottomright\" } still installs -- a document key", ok)
  sheet:release()
end

local function placeSection()
  local s = hafen.session():current()
  local n = 0
  local items = s:ui():inventory():items()
  local it = items and items[1]
  if it then
    local c = it:cell()
    if (type(c) == "table") and (c.x ~= nil) and (c.y ~= nil) then n = n + 1 end   -- a PLACE: x and y
    if it:info().cell ~= nil then n = n + 1 end                                    -- the snapshot's own
  end
  if s:world():grid():at(s:player():gob():position()) ~= nil then n = n + 1 end
  if hafen.ui():hit(2, 2) ~= nil then n = n + 1 end
  check(n == 4, "item:cell() is still a place, grid():at(p) still addresses one, and hafen.ui():hit()"
        .. " answers (" .. n .. "/4)", it and n or "<nothing in the backpack: open it and run again>")
end

-- ==================================================== 088.4 -- :overlay() means one thing

local VERBS = { "add", "get", "remove", "list", "count", "find" }

-- How many of the six collection verbs a receiver answers. A verb a collection has not got throws from
-- LuaCollection's own __index, so pcall on the field read is the whole test.
local function vocabulary(coll)
  local n = 0
  for _, v in ipairs(VERBS) do
    local ok, f = pcall(function() return coll[v] end)
    if ok and (type(f) == "function") then n = n + 1 end
  end
  return n
end

local function hudSection()
  local ov = hafen.ui():overlay()
  local base = ov:count()
  local a = ov:add("a"):draw(function() end)
  local b = ov:add("b"):draw(function() end)
  local n = 0
  if ov:get("a") == a then n = n + 1 end                        -- interned: one painter, one object
  if ov:count() == (base + 2) then n = n + 1 end
  local l = ov:list()
  if (l[#l - 1] == a) and (l[#l] == b) then n = n + 1 end        -- :list() IS the draw order
  if a:key() == "a" then n = n + 1 end
  if ov:remove("a") == ov then n = n + 1 end                     -- a removal chains, like every other
  if (ov:count() == (base + 1)) and (not a:exists()) then n = n + 1 end
  ov:remove("b")
  check(n == 6, "the HUD painters are KEYED, :list() is the draw order and :remove(key) ends one ("
        .. n .. "/6)", n)

  local t = hafen.map():display():get("cplot")
  local m = 0
  if (t ~= nil) and (t:tag() == "cplot") then m = m + 1 end
  if hafen.map():display():count() == 4 then m = m + 1 end
  check(m == 2, "hafen.map():display() is the client's own switches, a closed set of four ("
        .. m .. "/2)", m)

  local g = refusals()
  g.ask("hafen.ui():overlay():add()", function() return hafen.ui():overlay():add() end,
        "add: key is required")
  g.ask("hafen.ui():overlay():onDraw(fn)",
        function() return hafen.ui():overlay():onDraw(function() end) end, ":add(key):draw(fn)")
  local one = ov:add("gone")
  g.ask("ov:destroy()", function() return one:destroy() end, "hafen.ui():overlay():remove(key)")
  ov:remove("gone")
  g.ask("hafen.map():overlay()", function() return hafen.map():overlay() end, "hafen.map():display()")
  g.done("the retired overlay spellings raise, and :add() with no key names it")
end

local function maskSection()
  local s = hafen.session():current()
  local g = s:world():grid():at(s:player():gob():position())
  local m = g:mask()
  local n = 0
  if vocabulary(m) >= 4 then n = n + 1 end                 -- a read-only collection: no :add/:remove
  if type(m:list()) == "table" then n = n + 1 end
  if m:get("nosuchtag") == nil then n = n + 1 end          -- the tag space is OPEN: nil, not an error
  check(n == 3, "grid:mask() is the recorded masks on that ground (" .. n .. "/3)", n)

  -- The claim the whole task is about: one word, one vocabulary, on both receivers that carry it.
  local hud, gob = vocabulary(hafen.ui():overlay()), vocabulary(s:player():gob():overlay())
  check((hud == 6) and (gob == 6), "hafen.ui():overlay() and gob:overlay() answer the SAME six verbs, so"
        .. " :overlay() means one thing (" .. (hud + gob) .. "/12)", hud .. " and " .. gob)

  local r = refusals()
  r.ask("grid:overlay()", function() return g:overlay() end, "grid:mask()")
  r.done("grid:overlay() raises naming :mask()")
end

-- ============================================================== 088.5 -- three words freed

local function chromeSection()
  local win = hafen.ui():window():title("088"):size(140, 60):position(50, 50)
  win:rule():closeButton{ at = "topleft", offset = {4, 4} }
  local st = win:style()
  local n = 0
  if (type(st) == "table") and (type(st.closeButton) == "table") then n = n + 1 end
  if st.closeButton and (st.closeButton.at == "topleft") then n = n + 1 end
  check(n == 2, "rule:closeButton{...} installs and widget:style() reads it back (" .. n .. "/2)", n)
  win:rule():release()
  win:destroy()

  local a = hafen.asset():get("note.json")
  local m = 0
  if type(a:path()) == "string" then m = m + 1 end          -- the word :path is freed FOR a file path
  hafen.asset():remove(a)
  local s = hafen.session():current()
  if s and (type(s.close) == "function") then m = m + 1 end  -- ...and :close for the one ending it means
  check(m == 2, "asset:path() is still a string and session:close is still the ending (" .. m .. "/2)",
        s and m or "<no session: the second half needs a character>")
end

-- A widget's own Draw fires every frame on a surface of ours, so the retirement and the no-widget refusal
-- are both reachable without waiting for the server. A draw event carries no widget at all, which is
-- exactly what the refusal has to say rather than inventing one.
local function drawProbe()
  local win = hafen.ui():widget():size(60, 40):position(60, 150)
  local seen, sender, target, absent = false, nil, nil, nil
  win:on("Draw", function(ev)
    if seen then return end
    seen = true
    local o1, e1 = pcall(function() return ev:sender() end)
    local o2, e2 = pcall(function() return ev:target() end)
    local o3, e3 = pcall(function() return ev:widget() end)
    sender = (not o1) and tostring(e1) or "<no error>"
    target = (not o2) and tostring(e2) or "<no error>"
    absent = (not o3) and tostring(e3) or "<no error>"
  end)
  return function()
    local n = 0
    if sender and sender:find("ev:widget()", 1, true) then n = n + 1 end
    if target and target:find("ev:widget()", 1, true) then n = n + 1 end
    if absent and absent:find("a draw event answers", 1, true) then n = n + 1 end
    check(n == 3, "ev:sender()/ev:target() raise naming ev:widget(), and ev:widget() on a kind that"
          .. " carries none names that kind (" .. n .. "/3)",
          seen and tostring(sender) or "<no frame painted>")
    win:destroy()
  end
end

-- A CATEGORY sends nothing, so its tokens are empty and its snapshot carries no `path` field at all --
-- a fact about that entry, not about the verb. So the scan takes the first entry that has one.
local function anEntry(s)
  local first
  for _, p in ipairs(s:menugrid():list()) do
    first = first or p
    local c = p:categories()
    if c and (#c > 0) then return p end
  end
  return first
end

local function menuSection()
  local s = hafen.session():current()
  local pag = anEntry(s)
  local c = pag:categories()
  local n = 0
  if type(c) == "table" then n = n + 1 end
  if pcall(function() return "menu > " .. table.concat(c, " > ") end) then n = n + 1 end
  if pag:info().path ~= nil then n = n + 1 end         -- the snapshot keeps the client's own spelling
  check(n == 3, "pag:categories() is an array of strings that concatenates, and the snapshot keeps"
        .. " `path` (" .. n .. "/3)", n)
end

local function retiredWordsSection()
  local win = hafen.ui():window():title("088b"):size(120, 50):position(50, 220)
  local g = refusals()
  g.ask("rule:close(...)", function() return win:rule():close{ at = "topleft" } end,
        "rule:closeButton(")
  g.ask("a sheet's close = {...} key",
        function() return hafen.ui():sheet():load{ ["window.frame"] = { close = { at = "topleft" } } } end,
        "closeButton")
  local s = hafen.session():current()
  if s and (s:menugrid():count() > 0) then
    g.ask("pag:path()", function() return anEntry(s):path() end, "pagina:categories()")
  end
  g.done("the retired close/path spellings raise, the sheet's DOCUMENT key included")
  win:rule():release()
  win:destroy()
end

-- The two streams carry a receiver only the server and the player can produce, so they are watched for
-- the whole bounded window and scored over what arrived.
local evSeen = { action = false, message = false }
local subs = {}

local function armStreams()
  subs[#subs + 1] = hafen.event():action():on("*", function(ev)
    if ev:widget() ~= nil then evSeen.action = true end
  end)
  subs[#subs + 1] = hafen.event():message():on("*", function(ev)
    if ev:widget() ~= nil then evSeen.message = true end
  end)
end

local function dropStreams()
  for _, sub in ipairs(subs) do sub:off() end
  subs = {}
end

local function streamSection()
  local n = (evSeen.action and 1 or 0) + (evSeen.message and 1 or 0)
  check(n == 2, "ev:widget() answers a Widget on an action and on a message (" .. n .. "/2)",
        "action " .. tostring(evSeen.action) .. ", message " .. tostring(evSeen.message)
        .. " -- move and click once in the world while the suite runs")
end

-- ================================================================================= the run

local function run()
  pass, fail, manual = 0, 0, 0
  evSeen.action, evSeen.message = false, false
  armStreams()

  -- The painter the manual line is about goes up first, so it is on screen for the whole run.
  hafen.ui():overlay():add("088-manual"):draw(function(g, w, h)
    g:color(220, 60, 60)
    g:frect(12, 12, 40, 20)
    g:color()
  end)
  local readDraw = drawProbe()

  -- The bounded window: it closes as soon as the world is up AND both streams have carried a widget,
  -- and otherwise at 20 seconds -- or at 4 seconds with nobody logged in, where no stream can ever fire.
  local tries = 0
  local function poll()
    local done = inWorld() and evSeen.action and evSeen.message
    if (tries >= 4) and (done or (tries >= 40) or ((tries >= 8) and not inWorld())) then
      section("088.1 list", listSection)
      section("088.1 vr", vrSection)
      if inWorld() then
        section("088.1 vr-world", vrWorldSection)
      else
        check(false, "hafen.vr():entity() counts a ghost across the kinds and :remove(x) ends it (0/2)",
              "<never in the world: log a character in and run :t088 again>")
      end

      section("088.2 selector", selectorSection)
      section("088.2 filter", filterSection)
      if inWorld() then section("088.2 tree", treeSection) else
        check(false, "s:ui():match / :matchAll answer over that character's own tree (0/4)", "<no tree>")
      end

      section("088.3 size", sizeSection)
      section("088.3 anchor", anchorSection)
      if inWorld() then section("088.3 place", placeSection) else
        check(false, "item:cell() is still a place, grid():at(p) still addresses one, and"
              .. " hafen.ui():hit() answers (0/4)", "<no world>")
      end

      section("088.4 hud", hudSection)
      if inWorld() then section("088.4 mask", maskSection) else
        check(false, "grid:mask() is the recorded masks, and :overlay() means one thing (0/2)", "<no world>")
      end

      section("088.5 chrome", chromeSection)
      if readDraw then section("088.5 draw", readDraw) end
      if inWorld() then section("088.5 menu", menuSection) else
        check(false, "pag:categories() is an array of strings that concatenates (0/3)", "<no menu>")
      end
      section("088.5 retired", retiredWordsSection)
      section("088.5 streams", streamSection)

      manualCheck("look at the screen now: the top-left corner, and everywhere else",
                  "a small red rectangle just inside the corner for six more seconds and then nothing --"
                  .. " and NOTHING else of this suite left: no listbox, no window, no cabin in the world")
      manualCheck("open the action menu, walk into a category, and run :t088 again",
                  "the pag:categories() line passing, and the array reading as the breadcrumb in the"
                  .. " order the menu walks it -- only you can put the menu in a nested state")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      dropStreams()
      hafen.timer():after(6, function() hafen.ui():overlay():remove("088-manual") end)
      return
    end
    tries = tries + 1
    hafen.timer():after(0.5, poll)
  end
  poll()
end

hafen.slash():on("t088", run)                  -- the only way in: a suite does not start itself
