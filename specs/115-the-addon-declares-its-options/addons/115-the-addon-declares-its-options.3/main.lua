-- 115.3 — the AddOns tab draws what was declared. Self-checking suite.
--
-- Type :t115 in the world. It needs no window open and no row clicked: declaring a row is what puts
-- this addon in the AddOns list, and the list picks its first row on its own, so the page and its six
-- controls stand in the tree from the moment this file ran. The panel mirrors its options ONCE A
-- FRAME, so the half that reads the controls back runs a fifth of a second after the writes.

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

local function report()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function join(t)
  return "[" .. table.concat(t, ", ") .. "]"
end

-- ---------------------------------------------------------------- the declaration

-- What the AddOns list draws for this addon is its manifest name, and there is no verb that hands that
-- back -- so the rows are matched on the one piece of it that is this addon and nothing else. A check
-- that misses says every row it saw, which is the whole of what a wrong name looks like from here.
local TAG = "115.3"

local function isMine(s)
  return (s ~= nil) and (s:find(TAG, 1, true) ~= nil)
end

local opts = hafen.client():options():addon()

local CHOICES = {"first", "second", "third"}

local flag  = opts:boolean("flag"):label("A boolean"):tooltip("the checkbox row"):default(true):add()
local count = opts:number("count"):label("A number"):tooltip("the slider row"):range(1, 20):default(8):add()
local mode  = opts:choice("mode"):label("A choice"):choices(CHOICES):default("first"):add()
local title = opts:text("title"):label("A text row"):default("alpha"):add()
local line  = opts:label("state"):label("A label"):text("idle"):add()

-- The button row's press is the one thing here a program cannot cause, so it writes where the manual
-- check can read it: the label row, which the page re-reads as it draws.
opts:button("hit"):label("A button"):press(function() line:text("pressed") end):add()

local VALUED = {flag, count, mode, title}

-- Changed, counted per row. Subscribed once, here, so a second :t115 does not stack a second handler;
-- run() zeroes the counters instead.
local fired = {}
for _, o in ipairs(VALUED) do
  fired[o:name()] = 0
  o:on("Changed", function(v) fired[o:name()] = fired[o:name()] + 1 end)
end

-- ---------------------------------------------------------------- reading the window

-- The one child of w whose type is `ty`, or nil.
local function childOf(w, ty)
  for _, ch in ipairs(w:children():list()) do
    if ch:type() == ty then return ch end
  end
  return nil
end

-- The rows of a subject list, top to bottom: each row is a Label inside an item widget, and the item's
-- own y is where the list put it, whatever order the widgets were built in.
local function rowNames(list)
  local rows = {}
  for _, lbl in ipairs(list:matchAll("@Label")) do
    rows[#rows + 1] = {y = lbl:parent():position().y, text = lbl:text() or ""}
  end
  table.sort(rows, function(a, b) return a.y < b.y end)
  local names = {}
  for i = 1, #rows do names[i] = rows[i].text end
  return names
end

-- The AddOns tab's own list: the second Tab of the settings view, and the PanelList in it.
local function addonList(ui)
  local win = ui:match("@OptWnd")
  local view = win and childOf(win, "SettingsPanel")
  local tabs = {}
  for _, ch in ipairs(view and view:children():list() or {}) do
    if ch:type() == "Tab" then tabs[#tabs + 1] = ch end
  end
  return tabs[2] and childOf(tabs[2], "PanelList") or nil
end

-- This addon's own options page, identified by the heading the panel opens with -- its first child, a
-- Label at the origin carrying the addon's display name.
local function myPage(ui)
  for _, p in ipairs(ui:matchAll("@AddonOptionsPanel")) do
    for _, ch in ipairs(p:children():list()) do
      local at = ch:position()
      if (at.x == 0) and (at.y == 0) and isMine(ch:text()) then return p end
    end
  end
  return nil
end

-- The one widget of type `ty` inside the page, or nil where the page holds none.
local function ctl(page, ty)
  local found = page:matchAll("@" .. ty)
  return (#found == 1) and found[1] or nil
end

-- ---------------------------------------------------------------- the two phases

-- What this run wrote, for the frame-later half to compare the controls against.
local wrote = {}

local function phase2()
  local ui = hafen.session():current()
  ui = ui and ui:ui()
  local page = ui and myPage(ui)
  check(page ~= nil, "this addon's options page stands in the tree, listed and picked with no click", page)
  if not page then return report() end

  -- One control of each of the six kinds, which is what "one row per option, in declaration order"
  -- comes out as: the checkbox, the slider's own box, the dropdown, the field, the button, the line.
  local missing = {}
  for _, ty in ipairs({"Check", "Num", "Drop", "Entry", "Button", "Line"}) do
    if ctl(page, ty) == nil then missing[#missing + 1] = ty end
  end
  check(#missing == 0, "the page holds exactly one control of each of the six kinds", join(missing))

  -- The controls, a frame after the writes: each reads its option, so each answers what Lua wrote.
  local bad = {}
  local function reads(ty, got, want)
    if got ~= want then bad[#bad + 1] = ty .. "=" .. tostring(got) .. " want " .. tostring(want) end
  end
  local box, slider = ctl(page, "Check"), ctl(page, "HSlider")
  local drop, field = ctl(page, "Drop"), ctl(page, "Entry")
  local text, btn = ctl(page, "Line"), ctl(page, "Button")
  reads("checkbox", box and box:value(), wrote.flag)
  reads("slider", slider and slider:value(), wrote.count)
  reads("dropdown", drop and drop:value(), wrote.mode)
  reads("field", field and field:value(), wrote.title)
  reads("line", text and text:text(), line:text())
  reads("button", btn and btn:text(), "A button")
  check(#bad == 0, "every control on the page reads back what Lua wrote", join(bad))

  -- The panel re-reads and re-writes every option every frame. If its write were a second write path
  -- rather than the one Lua takes, that read-back would move the value and fire Changed again.
  local again = {}
  for _, o in ipairs(VALUED) do
    if fired[o:name()] ~= 1 then again[#again + 1] = o:name() .. "=" .. fired[o:name()] end
  end
  check(#again == 0, "a frame of the page drawing itself fires Changed no second time", join(again))

  -- The list: this addon is in it because it declared, and every other addon loaded is not because it
  -- declared nothing.
  local list = ui and addonList(ui)
  local rows = list and rowNames(list) or {}
  local mine, others = 0, {}
  for _, r in ipairs(rows) do
    if isMine(r) then mine = mine + 1 else others[#others + 1] = r end
  end
  check(mine == 1, "the AddOns tab lists this addon, once",
        list and join(rows) or "no AddOns list stands in the tree")
  check(#others == 0, "and lists no addon that declared no option", join(others))

  manualCheck("open Options and pick the AddOns tab",
              "one row, this suite, and beside it six rows -- a checkbox, a slider with its number, a"
              .. " dropdown, a text field, a button and a line of text -- each captioned on the left")
  manualCheck("click the button row on that page",
              "the line of text below it reads: pressed")
  manualCheck("drag the slider to a number you will remember and run :t115 again",
              "the first line of that run reads the number you left the slider on")
  report()
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t115 scores its own run, not both
  for _, o in ipairs(VALUED) do fired[o:name()] = 0 end

  -- On entry, before anything is written: what the store handed back is a value this declaration still
  -- accepts, and the number is the one the manual slider check reads.
  local n = count:value()
  check((type(n) == "number") and (n >= 1) and (n <= 20),
        "on entry the number row reads " .. tostring(n) .. ", inside the 1..20 it declared", n)

  -- A move on each of the four, each to a value it is not already on.
  wrote.flag = not flag:value()
  wrote.count = (count:value() % 20) + 1
  wrote.mode = CHOICES[1]
  for i, c in ipairs(CHOICES) do
    if c == mode:value() then wrote.mode = CHOICES[(i % #CHOICES) + 1] end
  end
  wrote.title = (title:value() == "alpha") and "beta" or "alpha"
  flag:value(wrote.flag)
  count:value(wrote.count)
  mode:value(wrote.mode)
  title:value(wrote.title)
  line:text("run " .. tostring(wrote.count))

  local bad = {}
  for _, o in ipairs(VALUED) do
    if o:value() ~= wrote[o:name()] then
      bad[#bad + 1] = o:name() .. "=" .. tostring(o:value()) .. " want " .. tostring(wrote[o:name()])
    end
    if fired[o:name()] ~= 1 then bad[#bad + 1] = o:name() .. " fired " .. fired[o:name()] end
  end
  check(#bad == 0, "each of the four value rows reads back the write and fired Changed once", join(bad))

  for _, o in ipairs(VALUED) do o:value(wrote[o:name()]) end
  local twice = {}
  for _, o in ipairs(VALUED) do
    if fired[o:name()] ~= 1 then twice[#twice + 1] = o:name() .. "=" .. fired[o:name()] end
  end
  check(#twice == 0, "writing the value already held fires nothing", join(twice))

  -- The page mirrors its options once a frame, so the half that reads the controls waits for one.
  hafen.timer():after(0.2, phase2)
end

hafen.console():on("t115", run)   -- the only way in: a suite does not start itself
