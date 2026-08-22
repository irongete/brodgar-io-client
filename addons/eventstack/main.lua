-- EventStack -- one line per thing the client did, newest FIRST, and one click for what it carried.
--
-- It OBSERVES, and that is all it does. Nowhere in this file is there a preventDefault, a rewrite, a
-- resend or a send: an inbound wildcard that cancels stops the client outright -- the widget tree stops
-- hearing from the server, and the bus goes quiet with it -- and a log is the last place to put one.
--
-- FIVE DOORS, ONE RECORD. Every source funnels into push(r), so however differently the doors are
-- addressed there is one shape to narrow, one column set and one list. Four of them are ambient -- the
-- client sends, receives, puts an event on the bus, opens and closes a widget, and a wildcard hears it.
-- The fifth is not: a control's own key (a button's Pressed, a list's Changed) fires only for somebody
-- who subscribed to that widget, so `ui` walks the tree and holds a subscription per control, which is
-- how the one door a wildcard cannot reach gets into the same list as the rest. It never cancels: the ev
-- on a borrowed key can stop the client's own action, and a log that stopped a button working would be a
-- bug with a window around it.
--
-- AND THE ROW WRITES ITS OWN SUBSCRIPTION. That is the point of watching traffic at all: you make the
-- thing happen, you find the row, and what you actually need is the line that catches it next time. So
-- the detail carries it, spelled for the door the row came through and guarded by the widget column,
-- because one message name is sent by several widgets and a handler that assumes otherwise reads an
-- argument that was never there.
--
-- FOUR AXES AND A WORD. A record is narrowed by its source, by the session it happened on, by the widget
-- it was about, by its event name, and by a substring of the whole line. Every axis FILLS ITSELF from
-- what has arrived: a message name is the server's and a widget class is the client's, so no list of
-- either can be written in advance -- each starts as (all) alone and gains a row the first time a value
-- lands on it. And dropdown:rows(t) REPLACES the set and CLEARS the pick, so every repopulation writes
-- the pick back -- without that the user's filter falls off the moment an unseen name arrives, which is
-- exactly when they are watching something.
--
-- WHAT IS CAPTURED AND WHAT IS SHOWN ARE TWO QUESTIONS. The four checkboxes open and shut the
-- subscriptions themselves, so unticking one stops the recording and loses what comes next; the source
-- dropdown narrows what is DRAWN out of what was already recorded, and loses nothing.
--
-- NEWEST FIRST, because a list keeps the scroll where the user left it. Appending downwards puts every
-- new line below the fold of a window nobody has scrolled, which is the one place a log must not put
-- them.
--
-- A ROW IS A STRING, and the log is a list rather than a table, because the click has to land somewhere:
-- hafen.ui():table() lays out real columns but HOLDS NOTHING -- no :value(), no Changed -- so no row of
-- one can be picked. A list holds its pick and reports it, so the columns are padded by hand and the
-- whole log is set in the client's mono face, where every character is one character wide and the
-- padding IS the column. CHAR_W is the one number that estimates that width; it decides how wide the
-- window stands and nothing else, and the header is padded through the very same helper as a row, so
-- the two can never disagree.
--
-- The seq number is not decoration. A list's pick comes back as the very row VALUE that was given, and
-- a row here is a string, so two identical lines would be one pick: "#" makes every line its own, and
-- recOf maps it back to the record behind it.
--
-- NOTHING IS DRAWN FROM A HANDLER. A handler appends to the ring and marks it dirty; the window's Tick
-- rewrites the list at most once a frame, and only while dirty, unpaused and visible.
--
-- THE HOT PATH STAYS SHORT. A handler builds one record and appends it, and what the row SAYS -- its
-- line and the lowercase haystack the word filter runs over -- is built there, once, rather than per
-- frame. What the record CARRIED is not: the args snapshot and the payload handle are kept exactly as
-- they arrived and read only when the row is clicked, so a message nobody ever looks at costs one table.
--
-- The ring is an array with a head index, never a table that shifts: dropping the oldest by shifting
-- copies the whole ring on every message. CAP is what it holds and the whole of what the list can ever
-- draw, because the interesting end of a log is the recent end: three hundred lines is a window you
-- scroll rather than a table that grows, and a record holds an args snapshot and a live handle, so a
-- ring that never dropped anything would pin every gob it ever saw.
--
-- IT RECORDS FROM THE MOMENT IT LOADS, not from the moment you open the window, because the traffic
-- worth reading has usually already happened: the tree building itself, the first `set`s that fill the
-- HUD, SessionEnteredWorld. A tool you have to open BEFORE the thing you are debugging can only ever
-- show you the second time it happens. So `at login` arms the doors as the addon loads and the window
-- is only a view on to what they caught -- opening and closing it records and loses nothing. Untick it
-- and the old shape is back: the doors open with the window and shut with it, and nothing is subscribed
-- to anything while it is down.
--
-- NOTHING HERE IS COPYABLE, because the client has no clipboard to offer -- so `log` writes through
-- hafen.log(), where the console shows it and the terminal keeps it whole: the picked row with its whole
-- detail, or, with nothing picked, the view itself. That is the same door widgetstack's `:selector`
-- uses, and for the same reason: the point of reading a message name off a window is to type it into a
-- file.
--
-- Its place is the ACCOUNT's saved variable, not w:remember(name): that files a placement under the
-- character on screen, and this window stands in the layer, above every one of them.

local CAP        = 300               -- records kept, and the most the list can draw; past it the oldest go
local PAD        = 6                 -- design px between the window's content edge and what is inside it
local GAP        = 4                 -- design px between two things on the same row
local WIDE       = 12                -- design px between two whole fields on the same row
local CHECK_W    = 74                -- one source's checkbox, the widest caption with room to spare
local BTN_W      = 60                -- one button, log and clear alike
local WIDE_CHECK = 92                -- the one checkbox whose caption is a phrase rather than a word
local ROW_H      = 20                -- what a row of controls stands at before its own art has answered
local ALL        = "(all)"           -- row 1 of each filter: the pick that narrows nothing
local FONT_SZ    = 12                -- the mono face the log and the detail are set in, in design px
local LINE_H     = 16                -- one log row: room for FONT_SZ, and no more
local CHAR_W     = 7.5               -- what one mono character measures across, in design px (an estimate)
local BAR        = 20                -- room for a list's own scrollbar
local LOG_ROWS   = 22                -- rows of log on screen before it scrolls
local DET_ROWS   = 9                 -- lines of detail on screen before it scrolls
local DUMP       = 60                -- rows the log button writes when no single one is picked
local DEF_X, DEF_Y = 80, 80          -- where the window stands before the user has moved it
local SAVE_EVERY = 2                 -- seconds between reads of where the user put it

-- The columns, in characters of the mono face. Event is 19 because that is what the longest key on
-- the bus measures (SessionEnteredWorld), and a name cut in half is a name nobody can search for.
local C_SEQ, C_TIME, C_SRC, C_SESS, C_WIDGET, C_NAME, C_ABOUT = 5, 8, 6, 10, 14, 19, 32
local CHARS   = C_SEQ + C_TIME + C_SRC + C_SESS + C_WIDGET + C_NAME + C_ABOUT + 6   -- + one space each
local LIST_W  = math.ceil(CHARS * CHAR_W) + BAR
local WIN_W   = LIST_W + PAD * 2
local LOG_H   = LOG_ROWS * LINE_H
local DET_H   = DET_ROWS * LINE_H

-- The five doors, in the order their checkboxes stand in.
local SOURCES = {"out", "in", "bus", "widget", "ui"}

-- The capability keys one of the CLIENT's own controls can answer -- a button's press, a checkbox's or a
-- list's change, an entry's Enter, a menu's pick, a grid's cell. Which of them a given widget answers is
-- asked of the client rather than guessed: widget:on(key, fn) REFUSES a key its widget has not got, so
-- the subscription that takes is the answer, and the answer is cached per class because the class is what
-- decides it -- without that every button in the tree would raise four times before finding "Pressed".
local CTL_KEYS = {"Pressed", "Changed", "Submitted", "Selected", "Cell"}

-- Every key on the bus but Update, which fires once a frame and says only that a frame happened.
local BUS_KEYS = {
  "Load", "Disable",
  "SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionDestroyed",
  "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved",
  "MeterAdded", "MeterRemoved", "MeterChanged",
  "BuffAdded", "BuffRemoved", "BuffChanged",
  "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
  "KinChanged", "QuestAdded", "QuestDone", "MarkersChanged",
  "FlowerMenuOpened", "FlowerMenuClosed",
  "GhostClicked", "SpriteClicked", "ObjectClicked",
}

-- WHICH ARGUMENT OF A BUS KEY IS THE SESSION. Sixteen of the keys are one character's and carry the
-- Session they were about as their LAST argument; four ARE a session; the rest are the world's, the
-- client's or the addon's own and carry none. That is the whole of the session column for the bus.
local SESS_AT = {
  SessionAdded = 1, SessionEnteredWorld = 1, SessionSelected = 1, SessionDestroyed = 1,
  MeterAdded = 2, MeterRemoved = 2, MeterChanged = 2,
  BuffAdded = 2, BuffRemoved = 2, BuffChanged = 2,
  FepChanged = 2, StudyChanged = 2, EquipChanged = 2, ActionbarChanged = 2, WoundChanged = 2,
  KinChanged = 2, QuestAdded = 2, QuestDone = 2,
  FlowerMenuOpened = 2, FlowerMenuClosed = 2,
}

local st = hafen.store():get("settings")          -- the account's own table; filled before this file runs
st.sources = st.sources or {out = true, ["in"] = true, bus = true, widget = false}
if st.atLogin == nil then st.atLogin = true end   -- record from the load, rather than from the window

local ring, count, head = {}, 0, 0    -- the log: the array, how much of it is filled, where the last write went
local seq = 0                         -- what numbers the next record, and what makes its line its own
local recOf = {}                      -- [line] = the record that line was built from
local dirty = false                   -- something arrived, or a filter moved, since the last :rows(t)
local paused = false                  -- the user is reading: keep recording, stop rewriting
local armed = false                   -- the doors are open, whether or not there is a window to show it
local win, log, det, tally            -- the window and its three pieces, or nil while it is down
local pauseBox                        -- ...and its pause tick, which a hotkey has to keep honest
local selLine = nil                   -- the line that names the picked row to the list, or nil

-- The four axes: what has arrived on each, in the order it first did, and what the user narrowed to.
local seenSrc,  srcOrder  = {}, {}
local seenSess, sessOrder = {}, {}
local seenWid,  widOrder  = {}, {}
local seenName, nameOrder = {}, {}
local pickSrc, pickSess, pickWid, pickName = ALL, ALL, ALL, ALL
local query = nil                     -- the word filter, lowercased; nil narrows nothing
local filtersDirty = false            -- a value arrived that no dropdown row carries yet

local streams = {}                    -- [src] = the wildcard subscription holding that stream
local busSubs = {}                    -- one subscription per key above
local watches = {}                    -- [session] = {appear handle, disappear handle}
local uiWatch = {}                    -- [session] = the same pair, for the ui door
local uiSubs = {}                     -- [session] = {[widget] = the capability Sub held on it}
local keyOfClass = {}                 -- [class] = the key that class answers, or false for none
local classOf = {}                    -- [widget] = its class, kept from appear for the disappear that cannot read it
local rootSess = {}                   -- [root widget] = the session whose tree it tops
local sessOf = {}                     -- [widget] = the account it belongs to, resolved once and kept
local sessOfN = 0                     -- how much of that cache is filled

-- LuaJ prints os.time() as 1.7871145E9 -- eight significant digits, the same string for a hundred
-- seconds either side. "%d" is the one spelling that gives the number back.
local function num(v)
  return string.format("%d", v)
end

-- The clock a record is stamped with. os.date is in the sandbox, but it is asked once here rather than
-- trusted per record: a client whose os.date will not take a format leaves the log timed, not broken.
local clock
if pcall(os.date, "%H:%M:%S", os.time()) then
  clock = function() return os.date("%H:%M:%S") end
else
  clock = function() return num(os.time() % 86400) end
end

-- ---------------------------------------------------------------------------------------------------
-- Text, laid out in characters because the face is mono

local BLANK = string.rep(" ", 64)

-- One column: padded to exactly n characters, and cut with a "~" where the value is longer than the
-- column it has to live in. Everything on a line goes through this, the header included.
local function cell(s, n)
  if (s == nil) or (s == "") then return string.sub(BLANK, 1, n) end
  local len = string.len(s)
  if len > n then return string.sub(s, 1, n - 1) .. "~" end
  return s .. string.sub(BLANK, 1, n - len)
end

local function lineOf(a, b, c, d, e, f, g)
  return cell(a, C_SEQ) .. " " .. cell(b, C_TIME) .. " " .. cell(c, C_SRC) .. " " .. cell(d, C_SESS)
      .. " " .. cell(e, C_WIDGET) .. " " .. cell(f, C_NAME) .. " " .. cell(g, C_ABOUT)
end

local HEADER = lineOf("#", "time", "source", "session", "widget", "event", "about")

-- A number as a person reads it: whole where it is whole, since every id, index and count on the wire
-- arrives as a float and 1.234E3 is nobody's widget id.
local function fmtNum(v)
  if v == math.floor(v) then return string.format("%d", v) end
  return string.format("%.3f", v)
end

-- One protocol argument, or one field of a snapshot, as a line of text. Coordinates are the one table
-- shape the wire has, and they are named rather than counted.
local function fmt(v)
  local t = type(v)
  if v == nil then return "nil" end
  if t == "number" then return fmtNum(v) end
  if t == "boolean" then return tostring(v) end
  if t == "string" then
    if string.len(v) > 56 then v = string.sub(v, 1, 55) .. "~" end
    return '"' .. v .. '"'
  end
  -- A HANDLE IS USERDATA, not a table: every reference object in this API is LuaValue.userdataOf, so a
  -- type(v) == "table" test misses every gob, widget and meter there is -- and each of them carries a
  -- __tostring that names it (Widget(Inventory#42)), which is exactly the line to print.
  if t == "userdata" then
    local ok, str = pcall(tostring, v)
    return (ok and str) or "userdata"
  end
  if t == "table" then
    if (type(v.x) == "number") and (type(v.y) == "number") then
      return "{x = " .. fmtNum(v.x) .. ", y = " .. fmtNum(v.y) .. "}"
    end
    local n = #v
    if n > 0 then return "[" .. num(n) .. " item(s)]" end
    return "{}"
  end
  return t
end

-- What the about column says for a message: the first arguments, in the units the wire carries, cut at
-- the width of the column rather than built whole and thrown away.
local function argSummary(a)
  if (a == nil) or (#a == 0) then return "" end
  local s = ""
  for i = 1, #a do
    s = s .. ((i > 1) and ", " or "") .. fmt(a[i])
    if string.len(s) >= C_ABOUT then break end
  end
  return s
end

-- ---------------------------------------------------------------------------------------------------
-- Which session a widget stands in
--
-- A message names the widget it left or is about to reach, and a widget is in exactly one character's
-- tree -- so the session is the top of that tree, and the walk to it is the one read that answers.
-- It is walked ONCE per widget and kept: a stream fires on the same handful of widgets over and over,
-- and a per-message walk is a per-message loop.

local function mapRoots()
  rootSess, sessOf, sessOfN = {}, {}, 0
  for _, s in ipairs(hafen.session():list()) do
    local ok, r = pcall(function() return s:ui():root() end)
    if ok and r then rootSess[r] = s:user() end
  end
end

local function walkUp(w)
  local node, depth = w, 0
  while (node ~= nil) and (depth < 64) do
    local name = rootSess[node]
    if name then return name end
    node = node:parent()
    depth = depth + 1
  end
  return ""
end

local function sessionOf(w)
  if w == nil then return "" end
  local c = sessOf[w]
  if c ~= nil then return c end
  local ok, name = pcall(walkUp, w)
  if not ok then name = "" end
  if sessOfN > 4000 then sessOf, sessOfN = {}, 0 end     -- a client left running for a day, bounded
  sessOf[w] = name
  sessOfN = sessOfN + 1
  return name
end

-- ---------------------------------------------------------------------------------------------------
-- The ring

-- One entry per value, the first time it arrives and never a second: that is the whole of a filter axis.
local function note(seen, order, v)
  if (v == nil) or (v == "") or seen[v] then return end
  seen[v] = true
  order[#order + 1] = v
  filtersDirty = true
end

-- Every door lands here. The record arrives with what it IS -- src, name, who, wclass, about -- and what
-- it CARRIED -- args, w, p1, p2 -- and leaves with its number, its clock, its line and its haystack.
local function push(r)
  seq = seq + 1
  r.n = seq
  r.clock = clock()
  r.line = lineOf(num(seq % 100000), r.clock, r.src, r.who, r.wclass, r.name, r.about)
  -- The haystack is the FIELDS, not the line: a line is padded and CUT to its columns, and a word filter
  -- over it would refuse to find the second half of a resource name the about column had to trim -- which
  -- is exactly the word somebody searches for. It costs one concatenation more per record, and it means a
  -- search finds what the row is about rather than what happened to fit.
  r.hay = string.lower(r.clock .. " " .. r.src .. " " .. r.who .. " " .. r.wclass .. " " .. r.name
                       .. " " .. r.about)
  head = (head % CAP) + 1
  local old = ring[head]
  if old then recOf[old.line] = nil end                  -- the oldest goes, and its row with it
  ring[head] = r
  recOf[r.line] = r
  if count < CAP then count = count + 1 end
  note(seenSrc, srcOrder, r.src)
  note(seenSess, sessOrder, r.who)
  note(seenWid, widOrder, r.wclass)
  note(seenName, nameOrder, r.name)
  dirty = true
end

-- ALL on an axis keeps every record of it, so four ALLs and no word is the whole log.
local function keep(r)
  if (pickSrc ~= ALL) and (r.src ~= pickSrc) then return false end
  if (pickSess ~= ALL) and (r.who ~= pickSess) then return false end
  if (pickWid ~= ALL) and (r.wclass ~= pickWid) then return false end
  if (pickName ~= ALL) and (r.name ~= pickName) then return false end
  if (query ~= nil) and (string.find(r.hay, query, 1, true) == nil) then return false end
  return true
end

-- The list's rows: the ring walked BACKWARDS from the newest, narrowed by all five. There is no second
-- cut here -- the ring itself is the cut, so what matched is what is drawn.
local function records()
  local out, n = {}, 0
  local first = (count < CAP) and 1 or ((head % CAP) + 1)
  local shown = false
  for i = count, 1, -1 do
    local r = ring[((first + i - 2) % CAP) + 1]
    if keep(r) then
      n = n + 1
      out[n] = r.line
      if r.line == selLine then shown = true end
    end
  end
  return out, shown
end

-- ---------------------------------------------------------------------------------------------------
-- What a bus event was about
--
-- The payloads are heterogeneous -- a Gob, a Meter, a Session, an array of captions -- so this is one
-- producer per key rather than one generic call. None of them names the character any more: the session
-- is a column and an axis of its own now, read off SESS_AT.

local ABOUT = {
  Load    = function() return "the addon layer" end,
  Disable = function() return "the addon layer" end,

  SessionAdded        = function(s) return s:user() end,
  SessionEnteredWorld = function(s) return s:character() or "?" end,
  SessionSelected     = function(s) return s:user() end,
  SessionDestroyed    = function(s) return s:user() end,

  -- At GobRemoved the gob is already gone, and :id() is the one read that answers there.
  GobAdded          = function(g)  return g:name() or ("#" .. num(g:id())) end,
  GobRemoved        = function(g)  return "#" .. num(g:id()) end,
  GobOverlayAdded   = function(ev) return ev:key() .. " on #" .. num(ev:gob():id()) end,
  GobOverlayRemoved = function(ev) return ev:key() .. " on #" .. num(ev:gob():id()) end,

  MeterAdded   = function(m) return m:res() or ("meter " .. num(m:index())) end,
  MeterRemoved = function(m) return m:res() or ("meter " .. num(m:index())) end,
  MeterChanged = function(m) return m:res() or ("meter " .. num(m:index())) end,

  BuffAdded   = function(b) return b:name() or b:res() or "buff" end,
  BuffRemoved = function(b) return b:name() or b:res() or "buff" end,
  BuffChanged = function(b) return b:name() or b:res() or "buff" end,

  FepChanged       = function(f) return f:label() or "fep" end,
  StudyChanged     = function(l) return num(#l) .. " slot(s)" end,
  EquipChanged     = function(l) return num(#l) .. " item(s)" end,
  ActionbarChanged = function(k) return k:name() or k:res() or ("slot " .. num(k:index())) end,
  WoundChanged     = function(l) return num(#l) .. " wound(s)" end,

  KinChanged     = function(l) return num(#l) .. " kin" end,
  QuestAdded     = function(q) return q:title() or "quest" end,
  QuestDone      = function(q) return (q:title() or "quest") .. " -> " .. (q:status() or "?") end,
  MarkersChanged = function(n) return num(n) .. " marker(s)" end,

  FlowerMenuOpened = function(p) return num(#p) .. " petal(s)" end,
  FlowerMenuClosed = function(p) return p or "(dismissed)" end,

  GhostClicked  = function(ev) return "button " .. num(ev:button()) end,
  SpriteClicked = function(ev) return "button " .. num(ev:button()) end,
  ObjectClicked = function(ev) return "button " .. num(ev:button()) end,
}

-- A payload that answers nil where this expected a read costs the line its description, never the line.
local function about(key, a)
  local f = ABOUT[key]
  if f == nil then return "" end
  local ok, s = pcall(f, a)
  if ok and (type(s) == "string") then return s end
  return ""
end

-- The account a bus key was about, where it was about one at all.
local function busSession(key, a, b)
  local at = SESS_AT[key]
  if at == nil then return "" end
  local s = (at == 1) and a or b
  if s == nil then return "" end
  local ok, u = pcall(function() return s:user() end)
  return (ok and u) or ""
end

-- ---------------------------------------------------------------------------------------------------
-- What one record carried
--
-- Read at the CLICK, never at the push. An args table is already a snapshot and reads the same whenever
-- it is asked; a handle is live, so a gob that has gone or a widget the client has destroyed answers
-- nothing -- and saying so is the honest line, where a snapshot taken per message on the chance somebody
-- might click it is a cost paid on every message that nobody does.

local SNAP_ITEMS = 12                 -- elements of a payload that is a LIST, before it says how many more

-- :info() is the one snapshot every reference object in this API answers, and it is asked of userdata and
-- of a table alike -- a handle is userdata, and reading its fields any other way is a guess at a name.
local function snap(v, out)
  if v == nil then return end
  local t = type(v)
  if (t ~= "table") and (t ~= "userdata") then
    out[#out + 1] = "  " .. fmt(v)
    return
  end
  local ok, info = pcall(function() return v:info() end)
  if ok and (type(info) == "table") then
    local keys = {}
    for k in pairs(info) do keys[#keys + 1] = k end
    table.sort(keys, function(x, y) return tostring(x) < tostring(y) end)
    if #keys == 0 then out[#out + 1] = "  (the snapshot is empty)" end
    for _, k in ipairs(keys) do
      out[#out + 1] = "  " .. cell(tostring(k), 12) .. " " .. fmt(info[k])
    end
    return
  end
  -- No snapshot: an array of handles (a study slot list, a kin roster) says what is in it one line at a
  -- time, since each element names itself; anything else has simply gone, and says so.
  if t == "table" then
    local n = #v
    if n > 0 then
      out[#out + 1] = "  [" .. num(n) .. " item(s)]"
      for i = 1, math.min(n, SNAP_ITEMS) do
        out[#out + 1] = "    [" .. num(i) .. "] " .. fmt(v[i])
      end
      if n > SNAP_ITEMS then out[#out + 1] = "    ... and " .. num(n - SNAP_ITEMS) .. " more" end
      return
    end
  end
  out[#out + 1] = "  " .. fmt(v) .. " -- nothing left to read, it is gone"
end

-- THE LINE YOU WOULD WRITE TO CATCH THIS AGAIN. It is the whole point of watching traffic: you make the
-- thing happen, you find the row, and what you actually need is the subscription -- so it is written out
-- here rather than left to be assembled out of five columns and a page of documentation. The door is the
-- source, the key is the name, and the widget column is the guard nearly every stream handler needs,
-- because one message name is sent by several widgets and a handler that assumes otherwise reads an
-- argument that was never there.
local function subscribeLines(r)
  local q = '"' .. r.name .. '"'
  if r.src == "bus" then
    local at, sig = SESS_AT[r.name], "function(payload)"
    if at == 1 then sig = "function(s)"
    elseif at == 2 then sig = "function(payload, s)"
    elseif (r.name == "Load") or (r.name == "Disable") then sig = "function()" end
    return {"hafen.event():on(" .. q .. ", " .. sig .. " end)"}
  end
  if (r.src == "out") or (r.src == "in") then
    local door = (r.src == "out") and "action" or "message"
    local noun = (r.src == "out") and "sender" or "target"
    local out = {"hafen.event():" .. door .. "():on(" .. q .. ", function(ev)"}
    if r.wclass ~= "" then
      out[#out + 1] = '  if ev:' .. noun .. '():type() ~= "' .. r.wclass .. '" then return end'
    end
    out[#out + 1] = "end)"
    return out
  end
  if r.src == "widget" then
    return {'hafen.session():current():ui():on("@' .. r.wclass .. '", "' .. r.name .. '", function(w)',
            "end)"}
  end
  if r.src == "ui" then
    return {'hafen.session():current():ui():find("@' .. r.wclass .. '"):on(' .. q .. ', function(ev)',
            "end)",
            '-- "@' .. r.wclass .. '" may match several: widgetstack names the one you mean'}
  end
  return {}
end

local function detailOf(r)
  local out = {}
  local function add(s) out[#out + 1] = s end
  local function field(k, v) add(cell(k, 10) .. " " .. v) end

  field("when", r.clock .. "   #" .. num(r.n))
  field("source", r.src)
  field("event", r.name)
  if r.who ~= "" then
    local s = hafen.session():get(r.who)
    local ok, c = pcall(function() return s:character() end)
    field("session", r.who .. ((ok and c) and (" / " .. c) or ""))
  end
  if r.wclass ~= "" then field("widget", r.wclass) end
  if r.about ~= "" then field("about", r.about) end

  local sl = subscribeLines(r)
  if #sl > 0 then
    add("")
    add("subscribe:")
    for _, ln in ipairs(sl) do add("  " .. ln) end
  end

  if r.args ~= nil then
    add("")
    add("args (" .. num(#r.args) .. "):")
    if #r.args == 0 then add("  (none)") end
    for i = 1, #r.args do add("  [" .. num(i) .. "] " .. fmt(r.args[i])) end
  end
  if r.w ~= nil then
    add("")
    add("the widget, as it stands now:")
    snap(r.w, out)
  end
  if r.p1 ~= nil then
    add("")
    add("the payload, as it stands now:")
    snap(r.p1, out)
  end
  return out
end

-- ---------------------------------------------------------------------------------------------------
-- The four doors
--
-- A stream handler runs on every message the client sends or receives, the inbound one under the very
-- lock the client takes to tick and to draw. Its body is one record and an append.

local function armWidget(s)
  if watches[s] then return end
  local user = s:user()
  local a = s:ui():on("*", "appear", function(w)
    local class = w:type()
    classOf[w] = class
    local id = w:id()          -- the server's own name for it, where it has one: what a uimsg is addressed to
    push{src = "widget", name = "appear", who = user, wclass = class,
         about = id and ("#" .. num(id)) or "", w = w}
  end)
  -- At disappear the widget is a key to match, not something to read: the class comes from appear.
  local d = s:ui():on("*", "disappear", function(w)
    push{src = "widget", name = "disappear", who = user, wclass = classOf[w] or "?", about = "", w = w}
    classOf[w] = nil
  end)
  watches[s] = {a, d}
end

local function disarmWidget(s)
  local h = watches[s]
  if h == nil then return end
  h[1]:off()
  h[2]:off()
  watches[s] = nil
end

-- One of the client's own controls, watched for the one thing it does. It never cancels: the ev on a
-- borrowed key can stop the client's own action, and a log that stopped a button working would be a bug
-- with a window around it. The key is found by trying, cached per class, and a widget whose class answers
-- nothing is skipped without a second thought.
local function watchCtl(s, w, who)
  local held = uiSubs[s]
  if (held == nil) or held[w] then return end
  local class = w:type()
  local known = keyOfClass[class]
  if known == false then return end
  local handler = function(ev)
    local v
    local got, r = pcall(function() return ev:value() end)
    if got then v = r end
    push{src = "ui", name = keyOfClass[class] or "?", who = who, wclass = class,
         about = (v ~= nil) and fmt(v) or "", w = w}
  end
  local tries = known and {known} or CTL_KEYS
  for _, key in ipairs(tries) do
    local ok, sub = pcall(function() return w:on(key, handler) end)
    if ok and sub then
      keyOfClass[class] = key
      held[w] = sub
      return
    end
  end
  keyOfClass[class] = false
end

local function armUi(s)
  if uiWatch[s] then return end
  local who = s:user()
  uiSubs[s] = {}
  local root = s:ui():root()
  if root then root:walk(function(n) watchCtl(s, n, who) end) end
  local a = s:ui():on("*", "appear", function(w) watchCtl(s, w, who) end)
  -- A destroyed widget's subscription has nothing left to fire, but it is still an entry in a table this
  -- addon holds -- so it goes when the widget does, rather than piling up a login at a time.
  local d = s:ui():on("*", "disappear", function(w)
    local held = uiSubs[s]
    local sub = held and held[w]
    if sub then
      sub:off()
      held[w] = nil
    end
  end)
  uiWatch[s] = {a, d}
end

local function disarmUi(s)
  local h = uiWatch[s]
  if h == nil then return end
  h[1]:off()
  h[2]:off()
  uiWatch[s] = nil
  for _, sub in pairs(uiSubs[s] or {}) do sub:off() end
  uiSubs[s] = nil
end

-- One door at a time, because one checkbox at a time is what the user has: a source already open is left
-- alone, and one already shut costs nothing to shut again.
local function armSrc(k)
  if k == "out" then
    if streams.out then return end
    streams.out = hafen.event():action():on("*", function(ev)
      local w = ev:sender()
      local a = ev:args()
      push{src = "out", name = ev:msg(), who = sessionOf(w), wclass = (w and w:type()) or "",
           about = argSummary(a), args = a, w = w}
    end)
  elseif k == "in" then
    if streams["in"] then return end
    streams["in"] = hafen.event():message():on("*", function(ev)
      local w = ev:target()
      local a = ev:args()
      push{src = "in", name = ev:msg(), who = sessionOf(w), wclass = (w and w:type()) or "",
           about = argSummary(a), args = a, w = w}
    end)
  elseif k == "bus" then
    if #busSubs > 0 then return end
    for _, key in ipairs(BUS_KEYS) do
      busSubs[#busSubs + 1] = hafen.event():on(key, function(a, b)
        push{src = "bus", name = key, who = busSession(key, a, b), wclass = "",
             about = about(key, a), p1 = a}
      end)
    end
  elseif k == "widget" then
    -- Subscribing replays every widget that character already has open, because appear covers what is
    -- already in the tree -- which is why this door is one of the two that start off.
    for _, s in ipairs(hafen.session():list()) do armWidget(s) end
  elseif k == "ui" then
    -- The other: this one walks the whole tree to find what answers a key, and then holds a subscription
    -- per control. It is the door that tells you which key a window of the client's own answers, which is
    -- the one question the other four cannot reach.
    for _, s in ipairs(hafen.session():list()) do armUi(s) end
  end
end

local function disarmSrc(k)
  if (k == "out") or (k == "in") then
    local sub = streams[k]
    if sub then
      sub:off()
      streams[k] = nil
    end
  elseif k == "bus" then
    for _, sub in ipairs(busSubs) do sub:off() end
    busSubs = {}
  elseif k == "widget" then
    for s in pairs(watches) do disarmWidget(s) end
    classOf = {}
  elseif k == "ui" then
    for s in pairs(uiWatch) do disarmUi(s) end
  end
end

-- Armed is the whole state of "is anything being recorded", and it is not the window's: with `at login`
-- the doors open as the file loads and stay open through every open and close of the view.
local function arm()
  mapRoots()
  for _, k in ipairs(SOURCES) do
    if st.sources[k] then armSrc(k) end
  end
  armed = true
end

local function disarm()
  for _, k in ipairs(SOURCES) do disarmSrc(k) end
  armed = false
end

-- ---------------------------------------------------------------------------------------------------
-- The window

local close

-- A control's height is its own ART's, which is the one measurement that cannot be computed -- so it is
-- read back off the control rather than written down here.
local function tall(w)
  local sz = w:size()
  return (sz and sz.h and (sz.h > 0)) and sz.h or ROW_H
end

-- An axis, written on to its dropdown. :rows(t) clears the pick, so the pick goes straight back on: the
-- set only ever grows, so whatever was picked is still one of the rows.
local function refill(dd, order, pick)
  local rows = {ALL}
  for i, v in ipairs(order) do rows[i + 1] = v end
  dd:rows(rows)
  dd:value(pick)
end

local function build()
  if win and win:exists() then return end

  -- Everything is held as a local as well, so the handlers below read the window they were built on
  -- rather than whatever the file's own variable says by the time they run.
  local w = hafen.ui():window():title("EventStack")
    :position(st.x or DEF_X, st.y or DEF_Y)
    :size(WIN_W, LOG_H)

  local mono = hafen.font():get("mono"):derive():size(FONT_SZ)

  -- One checkbox per door. It writes the account's setting and opens or shuts that one subscription,
  -- so a source is off the moment it is unticked rather than at the next login.
  local x, checkH = PAD, ROW_H
  for _, k in ipairs(SOURCES) do
    local c = hafen.ui():check():parent(w):position(x, PAD):size(CHECK_W):text(k)
      :value(st.sources[k] and true or false)
    c:on("Changed", function(on)
      st.sources[k] = on
      if on then armSrc(k) else disarmSrc(k) end
    end)
    checkH = math.max(checkH, tall(c))
    x = x + CHECK_W + GAP
  end

  -- Reading is what pause is for: the doors stay open and the ring keeps filling, and only the rewrite
  -- stops -- so a row picked apart at leisure does not move under the pointer.
  local ps = hafen.ui():check():parent(w):position(x + WIDE, PAD):size(CHECK_W):text("pause")
    :value(paused)
  ps:on("Changed", function(on)
    paused = on
    if not on then dirty = true end
  end)
  pauseBox = ps

  -- The one switch that is not about this window at all: it says when the DOORS open, and it is read at
  -- the next load. Ticking it here opens them now, since a switch that meant nothing until tomorrow
  -- would be a switch nobody could test.
  local al = hafen.ui():check():parent(w):position(x + WIDE + CHECK_W + GAP, PAD):size(WIDE_CHECK)
    :text("at login"):value(st.atLogin and true or false)
    :tooltip("record from the moment the addon loads, rather than from the moment this window opens")
  al:on("Changed", function(on)
    st.atLogin = on
    if on and not armed then arm() end
  end)

  local lg = hafen.ui():button():parent(w):position(WIN_W - PAD - BTN_W * 2 - GAP, PAD):size(BTN_W)
    :text("log"):tooltip("write the picked row, whole, to the console and the terminal")
  local clr = hafen.ui():button():parent(w):position(WIN_W - PAD - BTN_W, PAD):size(BTN_W):text("clear")

  -- A label's box is exactly its own text, so each field is laid out from what its own pieces answer
  -- rather than from a column of numbers that the theme's font would falsify.
  local function field(name, wdt, fx, fy)
    local l = hafen.ui():label():parent(w):text(name)
    local d = hafen.ui():dropdown():parent(w):size(wdt)
    local dh, lw = tall(d), l:size().w
    l:position(fx, fy + math.max(0, math.floor((dh - tall(l)) / 2)))
    d:position(fx + lw + GAP, fy)
    return d, fx + lw + GAP + wdt + WIDE, dh
  end

  local y1 = PAD + checkH + GAP
  local ds, dn, dw, dv, nx, fh
  ds, nx, fh = field("source", 110, PAD, y1)
  dv, nx     = field("session", 150, nx, y1)
  dw, nx     = field("widget", 190, nx, y1)

  local y2 = y1 + fh + GAP
  dn, nx = field("event", 190, PAD, y2)

  -- The word filter runs over the whole line -- the time, the class, the name and the about alike --
  -- because what the user has in mind is usually a fragment of something they SAW, not of an axis.
  local le = hafen.ui():label():parent(w):text("word")
  local ent = hafen.ui():entry():parent(w):size(220)
  local eh = tall(ent)
  le:position(nx, y2 + math.max(0, math.floor((eh - tall(le)) / 2)))
  ent:position(nx + le:size().w + GAP, y2)

  -- What the filters answered, of what the ring is holding.
  local cnt = hafen.ui():label():parent(w):text("0 of 0")
    :tooltip("rows the filters matched, of the records kept")
  cnt:position(nx + le:size().w + GAP + 220 + WIDE, y2 + math.max(0, math.floor((eh - tall(cnt)) / 2)))

  refill(ds, srcOrder, pickSrc)
  refill(dv, sessOrder, pickSess)
  refill(dw, widOrder, pickWid)
  refill(dn, nameOrder, pickName)
  ds:on("Changed", function(v) pickSrc  = v or ALL; dirty = true end)
  dv:on("Changed", function(v) pickSess = v or ALL; dirty = true end)
  dw:on("Changed", function(v) pickWid  = v or ALL; dirty = true end)
  dn:on("Changed", function(v) pickName = v or ALL; dirty = true end)
  ent:on("Changed", function(s)
    query = ((s == nil) or (s == "")) and nil or string.lower(s)
    dirty = true
  end)

  local y3 = y2 + math.max(fh, eh) + GAP
  local hd = hafen.ui():label():parent(w):position(PAD, y3):text(HEADER)
  hd:rule():font(mono)

  local logY = y3 + LINE_H + 2
  local l = hafen.ui():listbox():parent(w):position(PAD, logY):size(LIST_W, LOG_H):rowHeight(LINE_H)
  l:rule():font(mono)

  local sepY = logY + LOG_H + GAP
  local sp = hafen.ui():separator():parent(w):position(PAD, sepY):size(LIST_W)

  local detY = sepY + tall(sp) + GAP
  local d = hafen.ui():listbox():parent(w):position(PAD, detY):size(LIST_W, DET_H):rowHeight(LINE_H)
  d:rule():font(mono)

  w:size(WIN_W, detY + DET_H + PAD)

  win, log, det, tally = w, l, d, cnt

  local function show(r)
    selLine = r and r.line or nil
    if r == nil then
      d:rows{}
      return
    end
    local ok, lines = pcall(detailOf, r)
    d:rows((ok and lines) or {"(that record cannot be read any more)"})
  end

  -- The pick comes back as the row VALUE, which here is the line itself -- and recOf is what turns it
  -- back into the record behind it. A click on empty space below the rows is a deselect, and arrives
  -- as nil.
  l:on("Changed", function(line)
    show((line ~= nil) and recOf[line] or nil)
  end)

  -- The console is the only way a string in this window reaches a file, so this writes the row's whole
  -- detail rather than a summary of it: the terminal keeps every line whole, where the in-game half
  -- clips at 500 characters, and one line per field is what makes both halves readable.
  lg:on("Pressed", function()
    local r = selLine and recOf[selLine] or nil
    if r == nil then
      -- Nothing picked is not a mistake to refuse: it is the other question -- "give me what I am looking
      -- at" -- so the filtered view goes down the same door, newest first and capped, since a console is
      -- read rather than scrolled.
      local rows = records()
      hafen.log():write("eventstack: " .. num(#rows) .. " row(s) match"
                        .. ((#rows > DUMP) and (", the newest " .. num(DUMP) .. " of them") or ""))
      hafen.log():write(HEADER)
      for i = 1, math.min(#rows, DUMP) do hafen.log():write(rows[i]) end
      return
    end
    hafen.log():write("--- " .. r.line)
    local ok, lines = pcall(detailOf, r)
    if not ok then
      hafen.log():write("  (that record cannot be read any more)")
      return
    end
    for _, ln in ipairs(lines) do
      if ln ~= "" then hafen.log():write("  " .. ln) end
    end
  end)

  clr:on("Pressed", function()
    ring, count, head, recOf = {}, 0, 0, {}
    seenSrc, srcOrder = {}, {}
    seenSess, sessOrder = {}, {}
    seenWid, widOrder = {}, {}
    seenName, nameOrder = {}, {}
    pickSrc, pickSess, pickWid, pickName = ALL, ALL, ALL, ALL
    filtersDirty, dirty = true, true
    show(nil)
  end)

  -- The coalescing this whole design exists for: one :rows(t) a frame at the very most, and none at all
  -- while nothing has arrived, nothing is on screen to read it, or the user has said pause. The filters
  -- ride the same beat, because a repopulation is a :rows(t) of its own -- and the pick is written back
  -- after the rows, since :rows(t) is what cleared it.
  w:on("Tick", function()
    if dirty and (not paused) and w:visible() then
      if filtersDirty then
        refill(ds, srcOrder, pickSrc)
        refill(dv, sessOrder, pickSess)
        refill(dw, widOrder, pickWid)
        refill(dn, nameOrder, pickName)
        filtersDirty = false
      end
      local rows, shown = records()
      l:rows(rows)
      if shown then l:value(selLine) end
      cnt:text(num(#rows) .. " of " .. num(count))
      dirty = false
    end
  end)

  -- The chrome's close button destroys the window, so there is nothing left to hide: what is kept
  -- afterwards is "there is no window", and :eventstack builds a new one at the saved place.
  w:on("Close", function() close() end)

  dirty = true
end

close = function()
  if not st.atLogin then disarm() end     -- with it ticked the doors stay open behind the closed window
  if win and win:exists() then win:destroy() end
  win, log, det, tally, pauseBox = nil, nil, nil, nil, nil
  selLine = nil
end

-- ---------------------------------------------------------------------------------------------------
-- Dormant until it is asked for: with the window down nothing here is subscribed to anything.

local function toggle()
  if win and win:exists() then
    close()
  else
    build()
    if not armed then arm() end
  end
end

-- Pausing from a key has to move the tick as well: the checkbox is what the user reads to know whether
-- the view is live, and a programmatic :value(v) never re-enters its own Changed, so there is no loop.
local function togglePause()
  paused = not paused
  if not paused then dirty = true end
  if pauseBox and win and win:exists() then pauseBox:value(paused) end
  hafen.log():write("eventstack: " .. (paused and "paused -- the doors are still open" or "live"))
end

hafen.slash():on("eventstack", toggle)

-- Both start UNBOUND: the addon names an action and the user assigns the key, under
-- Options > Keybindings > EventStack. Pause is worth one, because the row you want to read is usually
-- going past while you are reaching for the mouse.
local keys = hafen.client():options():keybindings()
keys:on("toggle", toggle)
keys:on("pause", togglePause)

-- A character that reaches the world while the doors are open is a tree the widget door has not watched
-- yet, and a root the session column has not learnt. It follows the DOORS and not the window, which is
-- the whole of what `at login` buys: the login that happens before anybody opens the view is the one
-- worth having caught.
hafen.event():on("SessionEnteredWorld", function(s)
  if not armed then return end
  mapRoots()
  if st.sources.widget then armWidget(s) end
  if st.sources.ui then armUi(s) end
end)

hafen.event():on("SessionDestroyed", function(s)
  disarmWidget(s)
  disarmUi(s)
  if armed then mapRoots() end
end)

-- And the doors themselves, opened here rather than by the window. At this point no session exists yet,
-- so the widget door has nothing to replay and costs nothing to open -- which is the one moment opening
-- it is free.
if st.atLogin then arm() end

-- Where the user put it. A window you built is dragged by its own title bar, which reports nothing, so
-- the place is read on a slow timer rather than written from a gesture.
hafen.timer():every(SAVE_EVERY, function()
  if win and win:exists() then
    local p = win:position()
    if p then st.x, st.y = p.x, p.y end
  end
end)
