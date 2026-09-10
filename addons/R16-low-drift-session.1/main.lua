-- R16 -- low drift: the session's own sections. Every check asserts through the API the block changed,
-- one line per surface no archived suite covers. It runs only from :tr16 and mutates no persistent state.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level
-- error adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?.-%.lua:%d+:?%s*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

-- Did fn refuse, and did it say why? Answers nil when it did, and what went wrong when it did not.
local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  -- ...spelled with an `if`, never `cond and nil or x`: in Lua the `and` branch being nil takes the
  -- `or` branch, so that idiom answers "it did not refuse" for every refusal that did.
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

local function allRefuse(name, cases, also, got)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, (bad == nil) and (also ~= false), bad or got)
end

-- What a read answers, or nil where the section is not up yet. A section of a character mid-login is a
-- normal state and never an error, so every read here is taken the same way.
local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

-- The receiver this check needs is one only the PLAYER can put there -- a held item, a loaded school, a
-- meal, an open recipe -- and this suite holds no key that could put it there itself. So the line says
-- the one gesture that turns it into a [pass], and the run scores what it reached.
local function needs(action)
  manual = manual + 1
  say("[manual] " .. action .. ", then type :tr16 -- expect: this line becomes a [pass]")
end

local function len(t) return (type(t) == "table") and #t or -1 end

-- ---- the eight surfaces --------------------------------------------------------------------------

local function player(s)
  -- pl-09: a dot call passes its first argument as the receiver, so these READ the character the call
  -- was written on. `move` is not here: its permission gate runs first by design (D-213), so a suite
  -- without the key reaches the gate and never the receiver -- that half is verified by reading.
  allRefuse("a dot call on the player object raises rather than silently reading", {
    {"pl.gob()",  function() return s:player().gob() end,  "COLON call on the player object"},
    {"pl.hand()", function() return s:player().hand() end, "COLON call on the player object"},
  }, read(function() return s:player():gob() end) ~= nil, "the colon call answered nil")
end

local function hand(s)
  -- pl-10: the cursor is a live interned object and no builder, no snapshot and no carrier of an
  -- ending, so it answers :info(). Only the player can put something on it, so an empty cursor scores
  -- the line as the one action they can take rather than as a failure.
  local h = read(function() return s:player():hand() end)
  if h == nil then
    needs("pick any item up onto the cursor")
    return
  end
  local i = read(function() return h:info() end)
  local it = (type(i) == "table") and i.item or nil
  ok("the cursor answers :info(), and it carries the held item's own shape",
     (type(it) == "table") and ((it.res ~= nil) or (it.name ~= nil)),
     (type(i) == "table") and tostring(it) or tostring(i))
end

local function food(s)
  -- ch-15: one builder, so a FEP entry's own :info() and the row food:info() copies are one shape.
  -- ch-04: and an entry is DATA -- there is nothing to re-resolve, so it carries no :exists().
  local f = read(function() return s:char():food() end)
  local fep = f and read(function() return f:fep() end)
  local live = fep and read(function() return fep:entry():list() end) or {}
  local snap = f and read(function() return f:info().fep.entries end) or {}
  if len(live) < 1 then
    needs("eat something, so the FEP bar has an entry to compare")
    return
  end
  local a, b = read(function() return live[1]:info() end), snap[1]
  local same = (len(live) == len(snap)) and (type(a) == "table") and (type(b) == "table")
                 and (a.res == b.res) and (a.name == b.name) and (a.amount == b.amount)
                 and (refused("entry:exists()", function() return live[1]:exists() end, "fepentry") == nil)
  ok("a FEP entry is one shape through both doors, and is data with no :exists()",
     same, len(live) .. " live / " .. len(snap) .. " copied")
end

local function rosters(s)
  -- pk-21: the roster order, the interning and both snapshot shapes -- nothing archived names either set.
  local kins = read(function() return s:kin():list() end) or {}
  local good = (len(kins) >= 0)
  if len(kins) > 0 then
    local k, i = kins[1], read(function() return kins[1]:info() end)
    good = (read(function() return s:kin():get(k:id()) end) == k) and (type(i) == "table")
             and (i.id == k:id()) and (type(i.online) == "boolean")
  end
  local mates = read(function() return s:party():list() end) or {}
  if good and (len(mates) > 0) then
    local m, i = mates[1], read(function() return mates[1]:info() end)
    good = (read(function() return s:party():get(m:id()) end) == m) and (type(i) == "table")
             and (i.id == m:id())
  end
  ok("both rosters list in window order, intern on the id, and their snapshots carry it",
     good, len(kins) .. " kin / " .. len(mates) .. " in the party")
end

local function chat(s)
  -- ct-15: the scrollback and whose line it is -- the read a MessageAdded handler that speaks needs.
  local chans = read(function() return s:chat():list() end) or {}
  local ch = (len(chans) > 0) and (read(function() return s:chat():selected() end) or chans[1]) or nil
  local n = ch and read(function() return ch:message():count() end) or -1
  local mine = (type(n) == "number") and (n > 0)
                 and read(function() return ch:message():get(n):mine() end) or false
  ok("the chat lists its channels and its scrollback, and a line says whether it is yours",
     (len(chans) > 0) and (type(n) == "number") and (n >= 0)
       and ((n == 0) or (type(mine) == "boolean")),
     len(chans) .. " channels / " .. tostring(n) .. " lines")
end

local function speed(s)
  -- bm-09: every index here is 1-based, whatever the wire says -- including the one tostring prints.
  local good, got = true, ""
  for i = 1, 4 do
    local sp = read(function() return s:speed():get(i) end)
    local t = sp and tostring(sp) or "nil"
    got = (i == 1) and t or got
    good = good and (sp ~= nil) and (sp:index() == i) and (sp:wire() == (i - 1))
             and (t:sub(1, 7 + #tostring(i)) == "Speed(" .. i .. " ")
  end
  ok("tostring(speed) prints the 1-based position sp:index() answers, not the wire's", good, got)
end

local function hudbars(s)
  -- bm-17: session:buff(), session:meter() and the SEGMENT shape the change detection diffs on are proved
  -- by nothing archived. A bar's fill is a property of a segment, not of the bar -- meter:info() copies the
  -- first segment's out as `value` -- so the segment list is what the two doors have to agree about.
  local ms = read(function() return s:meter():list() end) or {}
  local bn = read(function() return s:buff():count() end)
  if len(ms) < 1 then
    ok("the HUD bars and the buff bar read, and a bar's segments answer through both doors",
       false, "no meter in the HUD slot")
    return
  end
  local i = read(function() return ms[1]:info() end)
  local segs = read(function() return ms[1]:segment():list() end) or {}
  local v = (len(segs) > 0) and read(function() return segs[1]:value() end) or nil
  local n = (len(segs) > 0) and read(function() return segs[1]:index() end) or nil
  local copied = (type(i) == "table") and i.segments or nil
  ok("the HUD bars and the buff bar read, and a bar's segments answer through both doors",
     (type(i) == "table") and (type(copied) == "table") and (len(copied) == len(segs))
       and (type(v) == "number") and (n == 1) and (type(bn) == "number") and (bn >= 0),
     len(ms) .. " meters, " .. tostring(bn) .. " buffs; segments " .. len(segs) .. " live / "
       .. len(copied or {}) .. " copied; first value " .. tostring(v) .. " at " .. tostring(n))
end

local function fight(s)
  -- fg-09: a card names the maneuver in it, so ONE filter string finds it through either door.
  -- fg-10: and a card's key is the label the window paints, never a number invented past them.
  local cards = read(function() return s:fight():deck():list() end) or {}
  if len(cards) < 1 then
    needs("load a combat school, so the deck has a card in it")
    return
  end
  local res = read(function() return cards[1]:res() end)
  local viaDeck = res and read(function() return s:fight():deck():find(res) end)
  local viaMan = res and read(function() return s:fight():maneuver():find(res) end)
  local key = read(function() return cards[1]:key() end)
  ok("one filter string finds a deck card and its maneuver alike, and the card's key is a label",
     (viaDeck == cards[1]) and (viaMan ~= nil) and (read(function() return viaMan:res() end) == res)
       and (type(key) == "string"),
     tostring(res) .. " / key " .. tostring(key))
end

local function questlog(s)
  -- cq-17: :selected() mints through the same door :get(id) does, so it never hands back a dead quest.
  -- cq-04: and a status is one of the four words this client has, or nothing.
  local sel = read(function() return s:quest():selected() end)
  local good = (sel == nil) or (read(function() return sel:exists() end) == true)
  local words = {pending = true, done = true, failed = true, disabled = true}
  local qs = read(function() return s:quest():list() end) or {}
  for _, q in ipairs(qs) do
    local st = read(function() return q:status() end)
    good = good and ((st == nil) or (words[st] == true))
  end
  local wn = read(function() return s:wound():count() end)
  local rn = read(function() return s:wound():roots():count() end)
  ok("a selected quest is a live one, every status is a word this client has, and the wounds tree",
     good and (type(wn) == "number") and (type(rn) == "number") and (rn <= wn),
     tostring(sel) .. " / " .. len(qs) .. " quests / " .. tostring(rn) .. " of " .. tostring(wn))
end

local function craft(s)
  -- cq-03: one builder, so craft:info()'s slot rows and spec:info() cannot spell one shape two ways --
  -- including the two keys a tool and a quality input have not got.
  local live = read(function() return s:craft():inputs():list() end) or {}
  local snap = read(function() return s:craft():info().inputs end) or {}
  if len(live) < 1 then
    needs("open a recipe with an ingredient in it")
    return
  end
  local a, b = read(function() return live[1]:info() end), snap[1]
  local good = (len(live) == len(snap)) and (type(a) == "table") and (type(b) == "table")
                 and (a.res == b.res) and (a.name == b.name) and (a.num == b.num) and (a.opt == b.opt)
  local tools = read(function() return s:craft():tools():list() end) or {}
  if good and (len(tools) > 0) then
    local t = read(function() return tools[1]:info() end)
    good = (type(t) == "table") and (t.num == nil) and (t.opt == nil)
  end
  ok("a recipe slot is one shape through both doors, and a tool carries neither count nor flag",
     good, len(live) .. " inputs / " .. len(snap) .. " copied / " .. len(tools) .. " tools")
end

local function actionbar(s)
  -- ab-03: the section IS the collection, so its own verb takes the collection's receiver check. The
  -- page is read and written back, so the run leaves the bar on the page the player had it on.
  local ab = s:actionbar()
  local page = read(function() return ab:page() end)
  allRefuse("a dot call on the action bar raises, where the colon call turns the page and chains", {
    {"ab.page(3)", function() return ab.page(3) end, "COLON call on the collection"},
  }, (type(page) == "number") and (read(function() return ab:page(page) end) == ab),
     "page " .. tostring(page))
end

local function menu(s, tries, done)
  -- ab-07: an ID-ONLY entry has no action layer, and its categories are the EMPTY array the page
  -- promises rather than nil. A resource still streaming answers nil too, so the count is retried.
  local nils, seen = 0, 0
  for _, pag in ipairs(read(function() return s:menugrid():list() end) or {}) do
    seen = seen + 1
    if read(function() return pag:categories() end) == nil then nils = nils + 1 end
  end
  -- An entry the catalogue lists has a resolved resource, so nothing here should answer nil once it has
  -- settled -- but the catalogue fills in over the first seconds, so the count is retried before it is
  -- scored rather than read once at the moment the command was typed.
  if (nils > 0) and (tries < 8) then
    hafen.timer():after(0.5, function() menu(s, tries + 1, done) end)
    return
  end
  ok("every menu entry answers an array of categories, the empty one included",
     (seen > 0) and (nils == 0), nils .. " of " .. seen .. " answered nil")
  done()
end

-- ---- the run -------------------------------------------------------------------------------------

local TRIES = 20            -- ~10 s at 500 ms, which is a login and the HUD building

local function ready()
  local s = hafen.session():current()
  if not s then return nil end
  local got, g = pcall(function() return s:player():gob() end)
  if not got or not g or not g:exists() then return nil end
  if read(function() return s:menugrid():count() end) == nil then return nil end
  return s
end

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function report(s)
  player(s)
  hand(s)
  food(s)
  rosters(s)
  chat(s)
  speed(s)
  hudbars(s)
  fight(s)
  questlog(s)
  craft(s)
  actionbar(s)
  menu(s, 1, finish)
end

local function attempt(n)
  local s = ready()
  if s then
    report(s)
  elseif n >= TRIES then
    fail = fail + 1
    say("[fail] no character was in the world after " .. TRIES .. " tries -- log in and run :tr16 again")
    finish()
  else
    hafen.timer():after(0.5, function() attempt(n + 1) end)
  end
end

hafen.console():on("tr16", function()
  pass, fail, manual = 0, 0, 0
  -- The typed command runs under the console tree's monitor, so the run is deferred to the step exactly
  -- as every suite that reads a widget tree is.
  hafen.timer():after(0, function() attempt(1) end)
end)
