-- timers -- a countdown list, built entirely from hafen.ui()'s own controls: one window holding the
-- timers and an Add button, a second window asking for a name, hours and minutes, and a Start, an Edit
-- and a delete button on every row.
--
--   :timers   opens the list; running it again closes it (the `toggle` hotkey does the same)
--   Add       opens the dialog; Edit reopens it on that row, prefilled
--   Start     counts the timer down -- while it runs the same button reads Stop
--   X         deletes the row
--
-- The list is kept ordered by REMAINING time, ascending, so whatever fires next is always at the top; a
-- timer that has run out sits at 00:00.00 until it is started again, which puts it there too. Editing a
-- timer stops it, because the seconds it was counting down are the ones you just replaced.
--
-- It is client-local end to end: nothing here writes to the server, so there is no permission to declare.
-- The timers live in an account-wide saved variable (hafen.store), which is filled before this file runs,
-- so there is nothing to wait for and no EnterWorld handler. What is saved for a running timer is the
-- wall-clock instant it is DUE, not the seconds left on it, so one that was running comes back still
-- running -- and one that ran out while you were logged off comes back finished, silently.
--
-- NOTHING BELOW GIVES A CONTROL A HEIGHT. widget:size(w) -- one number -- sets the width and leaves the
-- height to the control's own art (docs/addons/api/ui/pixels.md), which is the one measurement an addon
-- cannot make: a button's bottom border is drawn at the bottom of its own picture, so a box an art-height
-- short simply loses it. The COLUMNS are still measured, and for the opposite reason: they are text, and
-- font metrics are not linear in anything.

local ALARM = "sfx/hud/mmap/bell3"                    -- one of the client's own notification blips
local NAME_COLS = 24                                  -- the name column, in characters at the current scale

-- The row grid, in DESIGN pixels: the same integers on every client at every interface scale, which is
-- what makes writing them down honest. They are the client's own art heights -- a button 24, a text field
-- 20 -- and they are here to lay the rows out AROUND those controls, never to be handed to one.
local BTN, ENTRY = 24, 20
local ROW = BTN + 6                                   -- the row grid: a button, and air around it
local HEAD = BTN + 14                                 -- the header strip, above the first row

local db = hafen.store():get("timers")                -- account scope: filled before this file runs
db.list = db.list or {}                               -- { { name =, secs =, endAt =, fired = }, … }
if db.sound == nil then db.sound = true end

local win, dlg                                        -- the list and the dialog, or nil while closed
local empty                                           -- the "nothing here yet" line, shown on an empty list
local rows = {}                                       -- one row of controls per timer table
local order = {}                                      -- the timers, in the order their rows are laid out
local winH                                            -- the content height the window is currently at

local openDialog, layout                              -- mutually recursive with the row handlers below

-- ------------------------------------------------------------------ the columns, which ARE measured

local M = {}                                          -- filled once, on the first window we open

-- A label's box is exactly its rendered text, so a throwaway one is the ruler for a string's width. It is
-- built, read and destroyed inside one statement sequence, and a surface draws nothing until the tick
-- after the statement that built it -- so none of these is ever seen.
local function textSize(s)
  local l = hafen.ui():label():text(s)
  local box = l:size()
  l:destroy()
  return box
end

-- The column grid. Every number here is TEXT -- a name, a countdown, two captions -- and text is the one
-- thing a design pixel does not make constant: a glyph's advance is the font's, and a stylesheet may put
-- a different font behind any of it. So the columns are measured, and the heights above are not.
local function columns()
  if M.w then return end

  M.label = textSize("0").y                           -- one line of the stock label font
  M.time = textSize("00:00.00").x
  M.name = textSize(string.rep("n", NAME_COLS)).x
  M.start = math.max(textSize("Start").x, textSize("Stop").x) + 24
  M.edit = textSize("Edit").x + 24

  M.timeX = 10 + M.name + 10
  M.startX = M.timeX + M.time + 10
  M.editX = M.startX + M.start + 6
  M.delX = M.editX + M.edit + 6
  M.w = M.delX + BTN + 10

  hafen.log():write(("timers: columns laid out -- name %dpx, time %dpx, row %dpx, window %dpx wide")
    :format(M.name, M.time, ROW, M.w))
end

-- A line of text is shorter than a button, so it is centred against the row rather than sitting on the
-- row's own top edge. Both numbers are known: the row is BTN tall by construction, and the line is what
-- the ruler above just read.
local function textY(y)
  return y + math.floor((BTN - M.label) / 2)
end

-- ------------------------------------------------------------------ the timers themselves

local function remaining(t)
  if t.endAt then
    local left = t.endAt - os.time()
    return (left > 0) and left or 0
  end
  return t.fired and 0 or (t.secs or 0)
end

local function fmt(secs)
  local s = math.floor(secs)
  return string.format("%02d:%02d.%02d", math.floor(s / 3600), math.floor((s % 3600) / 60), s % 60)
end

-- The list, ordered by what is left. The keys are read ONCE, before the sort rather than inside the
-- comparator: os.time() moving under table.sort is an order function that contradicts itself.
local function sorted()
  local keyed = {}
  for i, t in ipairs(db.list) do keyed[i] = { t = t, left = remaining(t) } end
  table.sort(keyed, function(a, b)
    if a.left ~= b.left then return a.left < b.left end
    return (a.t.name or "") < (b.t.name or "")
  end)
  local out = {}
  for i, e in ipairs(keyed) do out[i] = e.t end
  return out
end

local function start(t)
  t.endAt, t.fired = os.time() + (t.secs or 0), nil
  hafen.store():flush()
end

local function stop(t)
  t.endAt, t.fired = nil, nil
  hafen.store():flush()
end

local function remove(t)
  for i, x in ipairs(db.list) do
    if x == t then
      table.remove(db.list, i)
      break
    end
  end
  hafen.store():flush()
  if win then layout(sorted()) end
end

-- ------------------------------------------------------------------ one row of the list

local function buildRow(t)
  local row = {}
  row.name = hafen.ui():label():parent(win)
  row.time = hafen.ui():label():parent(win)
  -- Three buttons, three widths, and not one height between them: :size(w) says the column and the
  -- client's own art says the rest.
  row.start = hafen.ui():button():parent(win):size(M.start)
  row.edit = hafen.ui():button():parent(win):size(M.edit):text("Edit")
  row.del = hafen.ui():button():parent(win):size(BTN):text("X")
  row.del:tooltip("Delete this timer")

  row.start:on("Pressed", function()
    if t.endAt then stop(t) else start(t) end
    if win then layout(sorted()) end
  end)
  row.edit:on("Pressed", function() openDialog(t) end)
  row.del:on("Pressed", function() remove(t) end)
  return row
end

local function destroyRow(row)
  row.name:destroy()
  row.time:destroy()
  row.start:destroy()
  row.edit:destroy()
  row.del:destroy()
end

-- A label's box is exactly its text, so writing one is not free: each of the three is written only when
-- what it should say has actually changed. The time line changes once a second, the other two rarely.
local function paint(row, t)
  local name = t.name or "timer"
  if row.lastName ~= name then row.name:text(name); row.lastName = name end
  local time = fmt(remaining(t))
  if row.lastTime ~= time then row.time:text(time); row.lastTime = time end
  local caption = t.endAt and "Stop" or "Start"
  if row.lastStart ~= caption then row.start:text(caption); row.lastStart = caption end
end

-- The row's five controls, on one grid: the three buttons sit on the row's own line, the two labels are
-- centred against it.
local function place(row, y)
  row.name:position(10, textY(y))
  row.time:position(M.timeX, textY(y))
  row.start:position(M.startX, y)
  row.edit:position(M.editX, y)
  row.del:position(M.delX, y)
end

-- ------------------------------------------------------------------ the list window

-- Build what is missing, drop what is gone, and place every row in the given order. Called when the set
-- of timers changes or when the order does -- never per tick, which only repaints the text.
layout = function(list)
  if not win then return end
  local live = {}
  for _, t in ipairs(list) do live[t] = true end
  for t, row in pairs(rows) do
    if not live[t] then
      destroyRow(row)
      rows[t] = nil
    end
  end
  for i, t in ipairs(list) do
    local row = rows[t]
    if not row then
      row = buildRow(t)
      rows[t] = row
    end
    paint(row, t)
    place(row, HEAD + ((i - 1) * ROW))
  end
  empty:visible(#list == 0)
  order = list

  local h = HEAD + (math.max(#list, 1) * ROW) + 4
  if winH ~= h then
    win:size(M.w, h)
    winH = h
  end
end

local function reordered(list)
  if #list ~= #order then return true end
  for i, t in ipairs(list) do
    if order[i] ~= t then return true end
  end
  return false
end

local function forgetList()
  if dlg then dlg:destroy(); dlg = nil end
  win, empty, winH = nil, nil, nil
  rows, order = {}, {}
end

local function closeList()
  if win then win:destroy() end
  forgetList()
end

local function buildList()
  columns()
  winH = HEAD + ROW + 4
  win = hafen.ui():window():title("Timers"):size(M.w, winH):position(60, 60)
  rows, order = {}, {}
  -- the chrome close button destroys the window for us, so this only drops what we were holding of it
  win:on("Close", forgetList)

  local add = hafen.ui():button():parent(win):position(10, 6)
    :size(textSize("Add").x + 24):text("Add")
  add:on("Pressed", function() openDialog(nil) end)

  -- a checkbox is as wide as its own caption, so it is placed from the right edge rather than at a
  -- column that only holds for one font
  local alarm = hafen.ui():check():parent(win):text("Sound Alarm"):value(db.sound)
  alarm:position(M.w - (alarm:size().x or 100) - 10, textY(6))
  alarm:on("Changed", function(on)
    db.sound = on
    hafen.store():flush()
  end)

  empty = hafen.ui():label():parent(win):position(10, HEAD + 6):text("No timers yet -- press Add.")
  layout(sorted())
end

-- ------------------------------------------------------------------ the create/edit dialog

local function trim(s)
  return (s or ""):match("^%s*(.-)%s*$")
end

-- A blank box means none of that unit; anything else has to be a whole, non-negative number.
local function unit(s)
  local text = trim(s)
  if text == "" then return 0 end
  local n = tonumber(text)
  if (not n) or (n < 0) or (n ~= math.floor(n)) then return nil end
  return n
end

local NO_NAME = "Give the timer a name."
local NOT_NUM = "Hours and minutes are whole numbers."
local NO_TIME = "A timer needs some time on it."

openDialog = function(t)
  columns()
  if dlg then dlg:destroy() end

  local hoursW = math.max(textSize("Hours").x, textSize("00").x + 20)
  local minsW = math.max(textSize("Minutes").x, textSize("00").x + 20)
  local hoursX = 10 + M.name + 10
  local minsX = hoursX + hoursW + 8
  local yLabel = 8
  local yEntry = yLabel + M.label + 4
  local yStatus = yEntry + ENTRY + 6
  local yButton = yStatus + M.label + 6
  local w = math.max(minsX + minsW + 10,
    20 + textSize(NOT_NUM).x,
    30 + textSize("Cancel").x + textSize("Save").x + 48)
  local h = yButton + BTN + 8

  dlg = hafen.ui():window()
    :title(t and "Edit Timer" or "Create New Timer")
    :size(w, h)
    :position(120, 220)
  dlg:on("Close", function() dlg = nil end)

  hafen.ui():label():parent(dlg):position(10, yLabel):text("Name")
  hafen.ui():label():parent(dlg):position(hoursX, yLabel):text("Hours")
  hafen.ui():label():parent(dlg):position(minsX, yLabel):text("Minutes")

  local name = hafen.ui():entry():parent(dlg):position(10, yEntry):size(M.name)
    :value(t and (t.name or "") or "")
  local hours = hafen.ui():entry():parent(dlg):position(hoursX, yEntry):size(hoursW)
    :value(t and tostring(math.floor((t.secs or 0) / 3600)) or "")
  local mins = hafen.ui():entry():parent(dlg):position(minsX, yEntry):size(minsW)
    :value(t and tostring(math.floor(((t.secs or 0) % 3600) / 60)) or "")

  local status = hafen.ui():label():parent(dlg):position(10, yStatus):text("")

  local function confirm()
    local nm = trim(name:value())
    local h, m = unit(hours:value()), unit(mins:value())
    if nm == "" then
      status:text(NO_NAME)
      return
    elseif (not h) or (not m) then
      status:text(NOT_NUM)
      return
    elseif ((h * 3600) + (m * 60)) <= 0 then
      status:text(NO_TIME)
      return
    end

    if t then
      -- editing replaces the countdown, so it also stops it: the seconds it was running are gone
      t.name, t.secs, t.endAt, t.fired = nm, (h * 3600) + (m * 60), nil, nil
    else
      table.insert(db.list, { name = nm, secs = (h * 3600) + (m * 60) })
    end
    hafen.store():flush()

    dlg:destroy()
    dlg = nil
    if not win then buildList() else layout(sorted()) end
  end

  name:on("Submitted", confirm)
  hours:on("Submitted", confirm)
  mins:on("Submitted", confirm)

  local okW = textSize(t and "Save" or "Add").x + 24
  local ok = hafen.ui():button():parent(dlg):position(10, yButton)
    :size(okW):text(t and "Save" or "Add")
  ok:on("Pressed", confirm)

  local cancelW = textSize("Cancel").x + 24
  local cancel = hafen.ui():button():parent(dlg):position(w - cancelW - 10, yButton)
    :size(cancelW):text("Cancel")
  cancel:on("Pressed", function()
    dlg:destroy()
    dlg = nil
  end)
end

-- ------------------------------------------------------------------ the clock

-- One timer drives the whole addon, and it runs whether the window is open or not: an alarm you closed
-- the list on is still an alarm. Half a second is under the second the display shows, so the countdown
-- never visibly skips one.
hafen.timer():every(0.5, function()
  local rang = false
  for _, t in ipairs(db.list) do
    if t.endAt and (os.time() >= t.endAt) then
      t.endAt, t.fired = nil, true
      rang = true
      hafen.log():write("timers: " .. (t.name or "timer") .. " finished")
      if db.sound then hafen.sound():get(ALARM):play(0.6) end
    end
  end
  if rang then hafen.store():flush() end

  if not win then return end
  local list = sorted()
  if reordered(list) then
    layout(list)
  else
    for _, t in ipairs(list) do paint(rows[t], t) end
  end
end)

-- ------------------------------------------------------------------ the ways in

-- A timer that was due while we were logged off has already finished: it comes back at 00:00.00, without
-- the blip, since ringing an alarm for something that happened yesterday is noise.
for _, t in ipairs(db.list) do
  if t.endAt and (os.time() >= t.endAt) then
    t.endAt, t.fired = nil, true
  end
end

hafen.slash():register("timers", function()
  if win then closeList() else buildList() end
end)

hafen.client():options():keybindings():register("toggle", function()
  if win then closeList() else buildList() end
end)

hafen.log():write("timers loaded -- :timers opens the list; Add asks for a name, hours and minutes, and"
  .. " the rows stay ordered by what is left on them")
