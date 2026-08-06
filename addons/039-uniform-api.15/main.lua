-- 039.15 -- the docs sweep. Self-checking suite; see specs/addons/TESTING.md. Run  :t039-15
--
-- This task is textual, so its verification is a checker and a grep -- except for the one claim a grep
-- cannot make. A code block on a page is a CLAIM, and it is the claim nobody re-reads: the only defect
-- an in-game round has ever found in a docs task was a snippet, and it was found by pasting it in. So
-- this suite is the paste: every Lua block printed in the conventions page, the getting-started
-- walkthrough and the eight guides is executed below AS WRITTEN, and a block that no longer runs
-- reddens the page it lives on by name.
--
-- Two liberties, and nothing else changes about a block:
--   * a handler passed to hafen.event():on("EnterWorld", ...) is named instead of anonymous, so the
--     suite can call it -- we are already in the world when :t039-15 is typed, which is the only reason
--     the block's payload can be reached at all;
--   * a block written around a free variable (`w`, `window`, `s`, `reset`, `step`) gets that variable
--     bound first, exactly as the page's surrounding prose says it is.
--
-- It also asserts the uniformity the sweep documents, because that is the half of "no transitional
-- marker survives" a program can see: every section is CALLED and is one object, the sections the
-- migration turned into entities all hand entities back, and a retired flat spelling throws naming its
-- replacement rather than reading as nil.
--
-- It stands alone (D-085) and declares no permissions: the one gated verb is tested by its REFUSAL. It
-- installs a stylesheet and drops it inside the same call, so the client never draws between the two;
-- every window, overlay, timer, subscription, hotkey and command it makes is torn down before it
-- prints the summary, and the two saved variables it writes are emptied again.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    local shown = "?"
    pcall(function() shown = tostring(got) end)
    hafen.log():write("[fail] " .. what .. " -- got: " .. shown)
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- ---- the harness that names a failing block -------------------------------------------------------

local undo = {}                       -- teardown closures, run in reverse
local function later(fn) undo[#undo + 1] = fn end

local at = "?"                        -- which block of the current page is running
local function block(where, fn) at = where; fn() end

local function page(name, fn)
  at = "the first block"
  local ok, err = pcall(fn)
  check(ok, name .. ": every Lua block on the page runs as written",
        ok and "" or (at .. " -- " .. tostring(err):gsub("^.-%.lua:%d+:%s*", "")))
end

-- ---- the pages ------------------------------------------------------------------------------------

local function conventions()
  block("the opening timer", function()
    local t = hafen.timer():every(5, function()
      hafen.log():write("still here")
    end)
    later(function() t:cancel() end)
  end)

  local w = hafen.ui():window()
  later(function() w:destroy() end)

  block("arity is the verb", function()
    w:title()                          -- reads
    w:title("Scout"):size(180, 48)     -- writes, and chains
  end)

  block("nil is an error unless it means something", function()
    local x, y = 60, 60
    w:position(x, y)          -- with an x you forgot to compute, this raises
    w:position()              -- the read is the same verb with no argument
  end)
  -- ...and the sentence beside that block is the check: the forgotten x DOES raise.
  refuses("conventions.md: an accidental nil raises rather than reading",
          function() local x = nil; w:position(x, 60) end, "nil")

  block("selectors", function()
    hafen.ui():find("window[title=Cupboard]")     -- the first match, or nil
    hafen.ui():all("inventory")              -- every match, in tree order (empty array, never nil)
  end)

  block("the filter argument", function()
    local gobs = hafen.world():gob()
    gobs:list("rabbit")                                        -- name contains "rabbit"
    gobs:list(function(g) return (g:health() or 1) < 1 end)    -- injured gobs (a Gob object)
    hafen.kin():list(function(k) return k:online() end)        -- online kin (a Kin object)
    hafen.map():marker():list(function(m) return m:type() == "player" end)   -- a Marker object
  end)

  block("colours", function()
    local positional = { 200, 210, 220 }
    local keyed      = { r = 200, g = 210, b = 220, a = 255 }
    -- the claim under the block is that BOTH go in wherever a colour does, and a read passes back
    local s = hafen.ui():sheet()
    s:rule("chat"):color(positional[1], positional[2], positional[3])
    s:rule("chat"):color(keyed)
    s:rule("chat"):remove()
  end)
end

local function gettingStarted()
  local window                                    -- the window, once we are in the world
  local trees = 0                                 -- what it displays

  block("step 2", function()
    hafen.log():write("myaddon loaded")
  end)

  block("step 4", function()
    local enter = function()
      hafen.log():write("in the world as " .. (hafen.player():name() or "?"))
    end
    local sub = hafen.event():on("EnterWorld", enter)
    later(function() sub:off() end)
    enter()
  end)

  block("step 5", function()
    local enter = function()
      hafen.log():write("in the world as " .. (hafen.player():name() or "?"))
      window = hafen.ui():window()
        :title("My Addon")
        :size(150, 24)
        :position(60, 60)
        :onDraw(function(g, w, h)
          g:color(255, 220, 120)
          g:text("trees nearby: " .. trees, 6, 4)
        end)
    end
    local sub = hafen.event():on("EnterWorld", enter)
    later(function() sub:off() end)
    enter()
    later(function() if window then window:destroy() end end)
  end)

  block("step 6", function()
    local t = hafen.timer():every(1, function()
      trees = hafen.world():gob():count("terobjs/tree")
    end)
    later(function() t:cancel() end)
  end)

  block("step 7", function()
    hafen.client():options():keybindings():register("toggle", function()
      if not window then return end
      if window:visible() then window:visible(false) else window:visible(true) end
    end)
    later(function() hafen.client():options():keybindings():unregister("toggle") end)
  end)

  block("step 8, the restore", function()
    if hafen.store():get("settings").open == false then window:visible(false) end
  end)

  block("step 8, the hotkey body", function()
    if window:visible() then window:visible(false) else window:visible(true) end
    hafen.store():get("settings").open = window:visible()
    window:visible(true)
  end)
end

local function readingTheWorld()
  block("find the objects you want", function()
    local trees   = hafen.world():gob():count("terobjs/tree")            -- how many, by name
    local nearest = hafen.world():gob():nearest("terobjs/tree")          -- the closest one, or nil
    local players = hafen.world():gob():within(50, function(g)      -- everything matching, in a radius
      return g:isPlayer()
    end)
  end)

  block("log what is around you", function()
    local cmd = hafen.slash():register("what", function()
      for _, g in ipairs(hafen.world():gob():within(15)) do
        hafen.log():write(g:name() or "?")
      end
    end)
    later(function() cmd:remove() end)
  end)

  block("read one", function()
    local tree = hafen.world():gob():nearest("terobjs/tree")
    if tree and tree:exists() then
      local p = tree:position()
      hafen.log():write(("tree at %.0f, %.0f, %.1f away"):format(p:x(), p:y(), tree:distance()))
    end
  end)

  block("do not scan every frame", function()
    local boars = {}

    local a = hafen.event():on("GobAdded", function(gob)
      if (gob:name() or ""):find("boar") then boars[gob] = true end
    end)

    local r = hafen.event():on("GobRemoved", function(gob)
      boars[gob] = nil                     -- the gob is already gone here: only :id() answers
    end)
    later(function() a:off(); r:off() end)
  end)

  block("your own character", function()
    local me = hafen.player():gob()          -- nil until you are in the world
    if me then
      local p = me:position()
      hafen.log():write(("standing at %.0f, %.0f"):format(p:x(), p:y()))
    end
  end)

  block("the ground", function()
    local p = hafen.player():gob():position()
    local t = hafen.world():tile(p)
    hafen.log():write(t and (t.name or t.id) or "not loaded yet")
  end)

  block("the map you explored", function()
    local gp = hafen.player():gob():position():info()      -- where I am, as {gridId, x, y}
    local g  = hafen.map():grid():get(gp.gridId)           -- ...the same Grid, from the recorded side
    local t  = g and g:tile({ x = 0, y = 0 })              -- nil until the grid is read off the disk
    hafen.log():write(t and t.name or "not loaded yet -- ask again next tick")
  end)
end

local function eventsAndTimers()
  block("the four moments", function()
    local a = hafen.event():on("Load", function() end)
    local b = hafen.event():on("EnterWorld", function() end)
    local c = hafen.event():on("Update", function(dt) end)
    local d = hafen.event():on("Disable", function() end)
    later(function() a:off(); b:off(); c:off(); d:off() end)
  end)

  block("keep the handle to stop early", function()
    local sub = hafen.event():on("MeterChanged", function(m) end)
    sub:off()
  end)

  block("a timer, or every frame", function()
    local every = hafen.timer():every(2, function()                  -- polling, twice as slow as it feels
      hafen.log():write("trees: " .. hafen.world():gob():count("terobjs/tree"))
    end)
    later(function() every:cancel() end)

    local handle = hafen.timer():after(5, function() end)
    handle:cancel()
  end)

  block("character data arrives a beat late", function()
    local ask
    local enter = function()
      ask = hafen.timer():after(2, function()                       -- ask again in a moment...
        local hp = hafen.meter():find("hp")
        hafen.log():write("hp: " .. tostring(hp and hp:value()))
      end)
    end
    local sub = hafen.event():on("EnterWorld", enter)
    enter()

    local m = hafen.event():on("MeterChanged", function(m)           -- ...or let the client tell you
      if m:res() == "gfx/hud/meter/hp" then hafen.log():write("hp: " .. tostring(m:value())) end
    end)
    later(function() sub:off(); m:off(); if ask then ask:cancel() end end)
  end)

  block("widgets are not on the bus", function()
    local payload = function(w)
      hafen.log():write(#w:items() .. " items")
    end
    local obs = hafen.ui():on("window[title=Cupboard]", "appear", payload)
    later(function() obs:remove() end)
    -- the block's payload is what a reader pastes, so run it against a container that IS open
    local inv = hafen.ui():inventory()
    if inv then payload(inv) end
  end)
end

local function customUi()
  local window
  local function reset() end
  local function step() end

  block("a window", function()
    local enter = function()
      window = hafen.ui():window()
        :title("Scout")
        :size(180, 48)
        :position(80, 120)
        :onDraw(function(g, w, h)
          g:color(220, 220, 220)
          g:text("players nearby: " .. hafen.world():gob():count("gfx/borka/body"), 6, 6)
        end)
        :onClose(function() hafen.log():write("closed") end)
    end
    local sub = hafen.event():on("EnterWorld", enter)
    later(function() sub:off() end)
    enter()
    later(function() if window then window:destroy() end end)
  end)

  block("draw", function()
    window:onDraw(function(g, w, h)
      g:color(0, 0, 0, 160)
      g:frect(0, 0, w, h)                     -- a dim panel behind the text
      g:color(255, 210, 120)
      g:text("hello", 6, 6)
      g:line(0, h - 1, w, h - 1)
    end)
  end)

  block("a HUD overlay", function()
    local ov = hafen.ui():overlay():onDraw(function(g, w, h)  -- over the whole HUD; w, h is the screen
      g:color(255, 255, 255)
      g:atext(os.date("%H:%M"), w - 8, 8, 1, 0)               -- anchored to the top-right corner
    end)
    later(function() ov:destroy() end)
  end)

  block("an overlay over a game object", function()
    local tagged = {}
    local function tag(gob)
      if gob:isPlayer() then gob:overlay():add("tag"):text("player"):color(0, 255, 0) end
    end
    local sub = hafen.event():on("GobAdded", tag)                    -- everyone who walks in...
    for _, g in ipairs(hafen.world():gob():list()) do tag(g) end     -- ...and everyone already here
    for _, g in ipairs(hafen.world():gob():list()) do
      if g:isPlayer() then tagged[#tagged + 1] = g end
    end
    later(function()
      sub:off()
      for _, g in ipairs(tagged) do pcall(function() g:overlay():remove("tag") end) end
    end)
  end)

  block("input", function()
    window:onClick(function(x, y, button, mods)
      if button == 3 then return end              -- leave the right button alone
      if mods.shift then reset() else step() end
      return true                                 -- truthy consumes the click
    end)
  end)
end

local function savedData()
  local window = hafen.ui():window()
  later(function() window:destroy() end)

  block("declare it, then use it", function()
    hafen.store():get("settings").window = { x = 40, y = 200 }
    hafen.store():get("seen").lastLogin  = os.time()
  end)

  block("read it at the right moment", function()
    local enter = function()
      local pos = hafen.store():get("settings").window
      if pos then window:position(pos.x, pos.y) end
    end
    local sub = hafen.event():on("EnterWorld", enter)
    later(function() sub:off() end)
    enter()
  end)

  block("store data, not objects", function()
    local enter = function()
      for _, prop in ipairs(hafen.store():get("settings").props or {}) do
        local p = hafen.world():position(prop.at)      -- :x() is nil until that grid is reachable
        if p then hafen.ghost():add(prop.res, p) end
      end
    end
    local sub = hafen.event():on("EnterWorld", enter)
    later(function() sub:off() end)
    enter()
  end)
end

local function hotkeysAndCommands()
  local window = hafen.ui():window()
  later(function() window:destroy() end)

  block("a hotkey", function()
    local keys = hafen.client():options():keybindings()

    keys:register("toggle", function()
      if window:visible() then window:visible(false) else window:visible(true) end
    end)
    later(function() keys:unregister("toggle") end)
    -- the prose beside it: one name reads the binding and writes it, so key() is the read half
    keys:key("toggle")
  end)

  block("a command", function()
    local function stop() end
    local function start(a) end
    local cmd = hafen.slash():register("scout", function(args)
      if args[1] == "off" then stop() else start(args[1]) end
    end)
    later(function() cmd:remove() end)
    cmd = nil
  end)
end

local function actionsAndPermissions()
  block("writing an addon that acts", function()
    local cmd = hafen.slash():register("gotree", function()
      if not hafen.act():enabled() then
        hafen.log():write("this addon needs the actions permission")
        return
      end
      local tree = hafen.world():gob():nearest("terobjs/tree")
      if tree then
        hafen.act():moveTo(tree:position())
      end
    end)
    later(function() cmd:remove() end)
  end)
end

local function theming()
  local s

  block("your first rule", function()
    s = hafen.ui():sheet()
    s:rule("*"):font(hafen.font():get("serif"):derive():size(11))
    s:rule("chat"):color(190, 210, 190)
    s:install()
  end)

  block("an edit to an installed sheet", function()
    local s = hafen.ui():sheet()
    s:rule("chat"):color(190, 210, 190)     -- ...or, once the sheet is installed, changes it live
    s:rule("chat"):remove()                 -- and this drops that one rule
  end)

  block("what a rule can say", function()
    s:rule("window.frame")
      :bg{ color = {26, 26, 28, 240} }
      :border{ image = hafen.asset():get("frame.png"), slice = {12, 40, 12, 12} }
      :pad(4)
    s:rule("window[title=Inventory]")
      :anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
  end)

  block("a theme is a file", function()
    local doc = hafen.json():parse(hafen.asset():get("theme.json"):text())
    -- map the two values JSON cannot carry -- a font face and an image -- to handles, then:
    hafen.ui():sheet():load(doc.rules):install()
  end)

  -- the client never draws between the install and the drop: this is all one slash dispatch
  hafen.ui():sheet():drop()
end

local function debugging()
  block("print, and read the two places it lands", function()
    hafen.log():write("state: " .. hafen.json():encode(hafen.store():get("settings")))
  end)

  block("the :lua expressions", function()
    local _ = hafen.world():gob():count("terobjs/tree")
    local w = hafen.ui():find("window[title=Inventory]")
    if w then w:info() end
    for _, m in ipairs(hafen.meter():list()) do hafen.log():write(tostring(m:res())) end
  end)
end

-- ---- the uniformity the sweep documents ------------------------------------------------------------

local SECTIONS = {
  "act", "actionbar", "asset", "buff", "char", "client", "craft", "event", "fight", "font",
  "ghost", "hook", "http", "json", "kin", "log", "map", "menugrid", "meter", "party",
  "player", "quest", "render", "slash", "sound", "speed", "store", "study", "time", "timer",
  "ui", "world", "wound",
}

local function uniformity()
  -- 1. Every surviving section is CALLED, and is the same object every call.
  local n, bad = 0, nil
  for _, name in ipairs(SECTIONS) do
    local sec = hafen[name]
    if type(sec) ~= "table" then
      bad = bad or ("hafen." .. name .. " is " .. type(sec) .. ", not a callable table")
    else
      local ok, a = pcall(sec)
      local _, b = pcall(sec)
      if not ok then
        bad = bad or ("hafen." .. name .. "() raised: " .. tostring(a))
      elseif a ~= b then
        bad = bad or ("hafen." .. name .. "() is a new object every call")
      else
        n = n + 1
      end
    end
  end
  check(bad == nil and n == #SECTIONS,
        ("all %d sections are called, and each is the SAME object every call"):format(#SECTIONS),
        bad or (n .. " of " .. #SECTIONS))

  -- 2. Gob is not the only object-oriented section: the flat halves all hand entities back now.
  local doors = {
    { "char:attr", function() return hafen.char():attr():get("str") end },
    { "study:slot", function() return hafen.study():slot():list() end },
    { "party", function() return hafen.party():list() end },
    { "fight:deck", function() return hafen.fight():deck() end },
    { "quest", function() return hafen.quest():list() end },
    { "wound", function() return hafen.wound():list() end },
    { "craft:current", function() return hafen.craft():current() end },
    { "ui:hand", function() return hafen.ui():hand() end },
  }
  local opened, why = 0, nil
  for _, d in ipairs(doors) do
    local ok, err = pcall(d[2])
    if ok then opened = opened + 1 else why = why or (d[1] .. ": " .. tostring(err)) end
  end
  check(opened == #doors,
        "every section the migration turned into entities answers through its entity door",
        why or (opened .. " of " .. #doors))

  -- 3. A retired flat spelling THROWS naming its replacement -- it never reads as plain nil.
  refuses("a retired section spelling names its replacement", function() return hafen.gob(1) end,
          "hafen.world()")
  refuses("a retired dotted sub-verb names its replacement", function() return hafen.world.gobs() end,
          "hafen.world():gob()")
  refuses("a retired config table names its replacement",
          function() return hafen.ui.skin{ chat = { color = { 1, 2, 3 } } } end, "sheet()")

  -- 4. The string filter means ONE thing across both of the gob collection's filter paths.
  --    This is what the pages' own snippets kept tripping over: a gob whose resource has not resolved
  --    yet has no name YET, which is not the same as a kind that has no name at all. The first is
  --    skipped, the second is refused. A suite can only see this when such a gob happens to be in view,
  --    so the deterministic half is a headless probe; what it CAN prove is that the two paths agree.
  local okList, listed = pcall(function() return #hafen.world():gob():list("terobjs/tree") end)
  local okCount, counted = pcall(function() return hafen.world():gob():count("terobjs/tree") end)
  check(okList and okCount and listed == counted,
        "a string filter over the live gobs answers, and :list and :count agree on it",
        (not okList) and tostring(listed) or ((not okCount) and tostring(counted)
                                              or (tostring(listed) .. " vs " .. tostring(counted))))

  local segs = hafen.map():segment():count()
  if segs > 0 then
    refuses("a collection whose KIND has no name refuses a string filter, naming what does work",
            function() return hafen.map():segment():list("anything") end, "no name to match a string")
  else
    check(false, "a collection whose KIND has no name refuses a string filter",
          "no segments loaded, so no member was reached to refuse on")
  end

  -- 5. A name this API never had still reads as plain nil, so feature tests keep working.
  local okA, a = pcall(function() return hafen.music end)
  local okB, b = pcall(function() return hafen.world.thereIsNoSuchVerb end)
  check(okA and okB and a == nil and b == nil,
        "a name this API never had still reads as plain nil, not as a refusal",
        tostring(a) .. " / " .. tostring(b))
end

-- ---- run ------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual, undo = 0, 0, 0, {}

  page("conventions.md", conventions)
  page("getting-started.md", gettingStarted)
  page("guides/reading-the-world.md", readingTheWorld)
  page("guides/events-and-timers.md", eventsAndTimers)
  page("guides/custom-ui.md", customUi)
  page("guides/saved-data.md", savedData)
  page("guides/hotkeys-and-commands.md", hotkeysAndCommands)
  page("guides/actions-and-permissions.md", actionsAndPermissions)
  page("guides/theming.md", theming)
  page("guides/debugging.md", debugging)

  -- the gated half of actions-and-permissions, which a read-only addon can only prove by refusal
  local me = hafen.player():gob()
  refuses("the guide's gated verb refuses an addon that did not declare the permission",
          function() return hafen.act():moveTo(me:position()) end, "did not declare")
  check(hafen.act():enabled() == false,
        "and hafen.act():enabled() answers that without throwing, as the guide says it does",
        hafen.act():enabled())

  uniformity()

  -- teardown, newest first: a suite leaves nothing running and nothing on screen
  for i = #undo, 1, -1 do pcall(undo[i]) end
  pcall(function() hafen.ui():sheet():drop() end)

  -- and nothing saved: the two declared tables go back to empty
  local st, sn = hafen.store():get("settings"), hafen.store():get("seen")
  st.window, st.open, st.props, sn.lastLogin = nil, nil, nil, nil

  local left = #hafen.ui():all("window[title=Scout]") + #hafen.ui():all("window[title=My Addon]")
  local installed = hafen.ui():sheet():info().installed
  check(left == 0 and installed ~= true and next(st) == nil and next(sn) == nil,
        "every window, overlay, timer, hotkey, command, rule and saved key the pages made is undone",
        left .. " window(s) left, sheet installed=" .. tostring(installed)
        .. ", saved keys=" .. tostring(next(st) or next(sn)))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-15", run)   -- the only way in: a suite does not start itself
