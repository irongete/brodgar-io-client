-- EventStack -- one line per thing the client did, newest last.
--
-- It OBSERVES, and that is all it does. Nowhere in this file is there a preventDefault, a rewrite, a
-- resend or a send: an inbound wildcard that cancels stops the client outright -- the widget tree stops
-- hearing from the server, and the bus goes quiet with it -- and a log is the last place to put one.
--
-- Four doors, one record. Every source funnels into push(src, name, about), so however differently the
-- doors are addressed there is one shape to filter, one column set and one table.
--
-- NOTHING IS DRAWN FROM A HANDLER. A handler appends to the ring and marks it dirty; the window's Tick
-- rewrites the table at most once a frame, and only while dirty and visible. A column's of(row) runs
-- once per row per :rows(t) -- a hundred rows over four columns is four hundred calls -- so a write per
-- message would be O(n) on a stream that fires several times a frame.
--
-- The ring is an array with a head index, never a table that shifts: dropping the oldest by shifting
-- copies the whole ring on every message.
--
-- Its place is the ACCOUNT's saved variable, not w:remember(name): that files a placement under the
-- character on screen, and this window stands in the layer, above every one of them.

local CAP        = 200               -- records kept; past it the oldest go
local PAD        = 6                 -- design px between the window's content edge and the table
local COL_T      = 78                -- the clock
local COL_SRC    = 48                -- out | in | bus | widget
local COL_NAME   = 130               -- the message name, the bus key, or the widget's class
local COL_ABOUT  = 250               -- what it was about
local BAR        = 20                -- room for the table's own scrollbar
local TABLE_W    = COL_T + COL_SRC + COL_NAME + COL_ABOUT + BAR
local TABLE_H    = 300
local DEF_X, DEF_Y = 80, 80          -- where the window stands before the user has moved it
local SAVE_EVERY = 2                 -- seconds between reads of where the user put it

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

local st = hafen.store():get("settings")          -- the account's own table; filled before this file runs
st.sources = st.sources or {out = true, ["in"] = true, bus = true, widget = false}

local ring, count, head = {}, 0, 0    -- the log: the array, how much of it is filled, where the last write went
local dirty = false                   -- something arrived since the last :rows(t)
local win, tbl                        -- the window and its table, or nil while it is down
local streams = {}                    -- [src] = the wildcard subscription holding that stream
local busSubs = {}                    -- one subscription per key above
local watches = {}                    -- [session] = {appear handle, disappear handle}
local classOf = {}                    -- [widget] = its class, kept from appear for the disappear that cannot read it

-- LuaJ prints os.time() as 1.7871145E9 -- eight significant digits, the same string for a hundred
-- seconds either side. "%d" is the one spelling that gives the number back.
local function num(v)
  return string.format("%d", v)
end

-- ---------------------------------------------------------------------------------------------------
-- The ring

local function push(src, name, about)
  head = (head % CAP) + 1
  ring[head] = {src = src, name = name, about = about, t = os.time()}
  if count < CAP then count = count + 1 end
  dirty = true
end

-- Out in arrival order, oldest first: from head + 1 once the ring has wrapped, from 1 before it has.
local function records()
  local out, first = {}, (count < CAP) and 1 or ((head % CAP) + 1)
  for i = 1, count do
    out[i] = ring[((first + i - 2) % CAP) + 1]
  end
  return out
end

-- ---------------------------------------------------------------------------------------------------
-- What a bus event was about
--
-- The payloads are heterogeneous -- a Gob, a Meter, a Session, an array of captions -- so this is one
-- producer per key rather than one generic call. Sixteen of the keys carry the Session they were about
-- as their LAST argument, which is what says which character a meter or a quest belonged to.

local function who(s)
  if s == nil then return "" end
  return " @" .. (s:character() or s:user())
end

local ABOUT = {
  Load    = function() return "the addon layer" end,
  Disable = function() return "the addon layer" end,

  SessionAdded        = function(s) return s:user() end,
  SessionEnteredWorld = function(s) return s:user() .. " / " .. (s:character() or "?") end,
  SessionSelected     = function(s) return s:user() end,
  SessionDestroyed    = function(s) return s:user() end,

  -- At GobRemoved the gob is already gone, and :id() is the one read that answers there.
  GobAdded          = function(g)  return g:name() or ("#" .. num(g:id())) end,
  GobRemoved        = function(g)  return "#" .. num(g:id()) end,
  GobOverlayAdded   = function(ev) return ev:key() .. " on #" .. num(ev:gob():id()) end,
  GobOverlayRemoved = function(ev) return ev:key() .. " on #" .. num(ev:gob():id()) end,

  MeterAdded   = function(m, s) return (m:res() or ("meter " .. num(m:index()))) .. who(s) end,
  MeterRemoved = function(m, s) return (m:res() or ("meter " .. num(m:index()))) .. who(s) end,
  MeterChanged = function(m, s) return (m:res() or ("meter " .. num(m:index()))) .. who(s) end,

  BuffAdded   = function(b, s) return (b:name() or b:res() or "buff") .. who(s) end,
  BuffRemoved = function(b, s) return (b:name() or b:res() or "buff") .. who(s) end,
  BuffChanged = function(b, s) return (b:name() or b:res() or "buff") .. who(s) end,

  FepChanged       = function(f, s) return (f:label() or "fep") .. who(s) end,
  StudyChanged     = function(l, s) return num(#l) .. " slot(s)" .. who(s) end,
  EquipChanged     = function(l, s) return num(#l) .. " item(s)" .. who(s) end,
  ActionbarChanged = function(k, s) return (k:name() or k:res() or ("slot " .. num(k:index()))) .. who(s) end,
  WoundChanged     = function(l, s) return num(#l) .. " wound(s)" .. who(s) end,

  KinChanged     = function(l, s) return num(#l) .. " kin" .. who(s) end,
  QuestAdded     = function(q, s) return (q:title() or "quest") .. who(s) end,
  QuestDone      = function(q, s) return (q:title() or "quest") .. " -> " .. (q:status() or "?") .. who(s) end,
  MarkersChanged = function(n)    return num(n) .. " marker(s)" end,

  FlowerMenuOpened = function(p, s) return num(#p) .. " petal(s)" .. who(s) end,
  FlowerMenuClosed = function(p, s) return (p or "(dismissed)") .. who(s) end,

  GhostClicked  = function(ev) return "button " .. num(ev:button()) end,
  SpriteClicked = function(ev) return "button " .. num(ev:button()) end,
  ObjectClicked = function(ev) return "button " .. num(ev:button()) end,
}

-- A payload that answers nil where this expected a read costs the line its description, never the line.
local function about(key, a, b)
  local f = ABOUT[key]
  if f == nil then return "" end
  local ok, s = pcall(f, a, b)
  if ok and (type(s) == "string") then return s end
  return ""
end

-- ---------------------------------------------------------------------------------------------------
-- The four doors
--
-- A stream handler runs on every message the client sends or receives, the inbound one under the very
-- lock the client takes to tick and to draw. Its body is an append and nothing else.

local function armWidget(s)
  if watches[s] then return end
  local a = s:ui():on("*", "appear", function(w)
    local class = w:type()
    classOf[w] = class
    push("widget", class, "appear")
  end)
  -- At disappear the widget is a key to match, not something to read: the class comes from appear.
  local d = s:ui():on("*", "disappear", function(w)
    push("widget", classOf[w] or "?", "disappear")
    classOf[w] = nil
  end)
  watches[s] = {a, d}
end

local function disarmWidget(s)
  local h = watches[s]
  if h == nil then return end
  h[1]:remove()
  h[2]:remove()
  watches[s] = nil
end

local function arm()
  if st.sources.out and not streams.out then
    streams.out = hafen.event():action():on("*", function(ev)
      local w = ev:sender()
      push("out", ev:msg(), (w and w:type()) or "")
    end)
  end
  if st.sources["in"] and not streams["in"] then
    streams["in"] = hafen.event():message():on("*", function(ev)
      local w = ev:target()
      push("in", ev:msg(), (w and w:type()) or "")
    end)
  end
  if st.sources.bus and (#busSubs == 0) then
    for _, key in ipairs(BUS_KEYS) do
      busSubs[#busSubs + 1] = hafen.event():on(key, function(a, b)
        push("bus", key, about(key, a, b))
      end)
    end
  end
  -- Subscribing replays every widget that character already has open, because appear covers what is
  -- already in the tree -- which is why this door is the one that starts off.
  if st.sources.widget then
    for _, s in ipairs(hafen.session():list()) do armWidget(s) end
  end
end

local function disarm()
  for src, sub in pairs(streams) do
    sub:off()
    streams[src] = nil
  end
  for _, sub in ipairs(busSubs) do sub:off() end
  busSubs = {}
  for s in pairs(watches) do disarmWidget(s) end
  classOf = {}
end

-- ---------------------------------------------------------------------------------------------------
-- The window

local close

local function build()
  if win and win:exists() then return end

  -- The two are held as locals as well, so the handlers below read the window they were built on
  -- rather than whatever the file's own variable says by the time they run.
  local w = hafen.ui():window():title("EventStack")
    :position(st.x or DEF_X, st.y or DEF_Y)
    :size(TABLE_W + PAD * 2, TABLE_H + PAD * 2)

  local t = hafen.ui():table():parent(w):position(PAD, PAD):size(TABLE_W, TABLE_H)
    :columns{
      {title = "time",   width = COL_T,     of = function(r) return num(r.t) end},
      {title = "source", width = COL_SRC,   of = function(r) return r.src end},
      {title = "name",   width = COL_NAME,  of = function(r) return r.name end},
      {title = "about",  width = COL_ABOUT, of = function(r) return r.about end},
    }

  win, tbl = w, t

  -- The coalescing this whole design exists for: one :rows(t) a frame at the very most, and none at
  -- all while nothing has arrived or nothing is on screen to read it.
  w:on("Tick", function()
    if dirty and w:visible() then
      t:rows(records())
      dirty = false
    end
  end)

  -- The chrome's close button destroys the window, so there is nothing left to hide: what is kept
  -- afterwards is "there is no window", and :eventstack builds a new one at the saved place.
  w:on("Close", function() close() end)

  dirty = true
end

close = function()
  disarm()
  if win and win:exists() then win:destroy() end
  win, tbl = nil, nil
end

-- ---------------------------------------------------------------------------------------------------
-- Dormant until it is asked for: with the window down nothing here is subscribed to anything.

hafen.slash():register("eventstack", function()
  if win and win:exists() then
    close()
  else
    build()
    arm()
  end
end)

-- A character that reaches the world while the log is up is a tree the widget door has not watched yet.
hafen.event():on("SessionEnteredWorld", function(s)
  if win and win:exists() and st.sources.widget then armWidget(s) end
end)

hafen.event():on("SessionDestroyed", disarmWidget)

-- Where the user put it. A window you built is dragged by its own title bar, which reports nothing, so
-- the place is read on a slow timer rather than written from a gesture.
hafen.timer():every(SAVE_EVERY, function()
  if win and win:exists() then
    local p = win:position()
    if p then st.x, st.y = p.x, p.y end
  end
end)
