-- Better village controls -- a colour row picks a group by COLOUR, and the client draws eight of them
-- (BuddyWnd.ncolors). The server takes 0..254. So every colour row grows a picker of its own carrying
-- every group the server accepts -- the Kin, Village and Realm tabs of the Kith & Kin window, and the
-- claim window's permission row, whose own table the fork widened to the same space (haven.res.ui.land).
--
-- The picker is a MIRROR, not just a command: it shows the group the row is in -- read straight off the
-- row with `row:value()`, which is the same thing the highlighted square says and the only thing a group
-- above the eighth has -- and picking a number drives the row with `row:value(n)`, which runs the very
-- method a click on a square ends in. So the message that reaches the server is the window's own
-- ("gsel" for the village's row, its own for the member's), and this addon never has to know it.
--
-- The picker goes UNDER the colours where there is room and BESIDE them where there is not: the panel is
-- laid out by the server, so what already sits under a row is not this addon's to move.

local WIDTH = 64    -- design px. The colour row is 160 wide and the panel 263, so the picker fits either way
local GAP   = 2     -- design px between the colour row and the picker
local SYNC  = 0.25  -- seconds between two reads of what the rows are showing

-- "0" .. "254", built once. Rows are strings, and `Changed` hands back the very one it was given.
local GROUPS = {}
for n = 0, 254 do GROUPS[n + 1] = tostring(n) end

local watchByAccount = {}   -- [account] = the widget subscription on that character's tree
local rows           = {}   -- {row =, picker =, seen = the group the row last reported}

local function say(line) hafen.log():write("better-village-controls: " .. line) end

-- Where the picker goes, in the colour row's own parent: under the row unless something the server put
-- there is already in the way, in which case beside it.
local function placeFor(colours, height)
  local at, box = colours:position(), colours:size()
  local under = at.y + box.h + GAP
  local ceiling = nil
  for _, sibling in ipairs(colours:parent():children():list()) do
    local corner, size = sibling:position(), sibling:size()
    if (corner.y >= at.y + box.h) and (corner.x < at.x + WIDTH) and ((corner.x + size.w) > at.x) then
      if (ceiling == nil) or (corner.y < ceiling) then ceiling = corner.y end
    end
  end
  if (ceiling == nil) or ((ceiling - under) >= height) then return at.x, under end
  return at.x + box.w + GAP, at.y
end

local function addPicker(colours)
  local panel = colours:parent()
  local name = "group" .. tostring(colours:position().y)
  if panel:matchAll("[name=better-village-controls/" .. name .. "]")[1] then return end

  local picker = hafen.ui():dropdown():parent(panel):name(name):size(WIDTH):rows(GROUPS)
    :tooltip("the group this row is in, and where to put it -- 0-254, the range the server takes;"
             .. " the eight colours only reach 0-7")
  picker:position(placeFor(colours, picker:size().h))
  picker:on("Changed", function(row)
    local group = tonumber(row)
    -- Protected because the drive runs the panel's OWN hook, which is published code this addon cannot
    -- read: a panel that cannot hold the group says so by throwing, and the row is left where it was.
    local driven, failure = pcall(function() colours:value(group) end)
    if not driven then say(tostring(failure)) end
  end)
  rows[#rows + 1] = {row = colours, picker = picker}
end

-- What each row is showing, into its picker. Read rather than remembered, because the group changes under
-- this addon: the village's row follows whichever group the tab is showing, and the member's row is built
-- again by the server every time another member is selected.
hafen.timer():every(SYNC, function()
  for i = #rows, 1, -1 do
    local entry = rows[i]
    if not (entry.row:exists() and entry.picker:exists()) then
      table.remove(rows, i)
    else
      local group = entry.row:value()
      if group ~= entry.seen then
        entry.seen = group
        if group then entry.picker:value(tostring(group)) end
      end
    end
  end
end)

local function watchGroupRows(session)
  local previous = watchByAccount[session:user()]
  if previous then previous:off() end
  watchByAccount[session:user()] = session:ui():on("@GroupSelector", "Added", addPicker)
end

hafen.event():on("SessionEnteredWorld", watchGroupRows)
hafen.event():on("SessionRemoved", function(session) watchByAccount[session:user()] = nil end)

-- :bvc -- every colour row this character has, what group it is in, and where its picker went.
hafen.console():on("bvc", function()
  local session = hafen.session():current()
  if not session then
    say("no character on screen")
    return
  end
  local found = session:ui():matchAll("@GroupSelector")
  say(#found .. " colour rows in this character's tree")
  for i, row in ipairs(found) do
    local at = row:position()
    say("  row " .. i .. ": panel=" .. row:parent():type() .. " group=" .. tostring(row:value())
        .. " at " .. at.x .. "," .. at.y
        .. " picker=" .. tostring(row:parent():matchAll("[name^=better-village-controls/]")[1] ~= nil))
  end
end)
