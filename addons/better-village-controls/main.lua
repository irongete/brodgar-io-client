-- Better village controls -- a colour row picks a group by COLOUR, and the client draws eight of them
-- (BuddyWnd.ncolors). A POLITY's groups run 0..254, and its panels are the rows that spend the whole of
-- that space: the Village and Realm tabs, and the member panel where a person is put in one. Those grow a
-- picker of their own carrying every group the server accepts.
--
-- The picker is a MIRROR, not just a command: it shows the group the row is in -- read straight off the
-- row with `row:value()`, which is the same thing the highlighted square says and the only thing a group
-- above the eighth has -- and picking a number drives the row with `row:value(n)`, which runs the very
-- method a click on a square ends in. So the message that reaches the server is the window's own
-- ("gsel" for the village's row, "perm" for the member's), and this addon never has to know it.
--
-- The picker goes UNDER the colours where there is room and BESIDE them where there is not: the panel is
-- laid out by the server, so what already sits under a row is not this addon's to move.
--
-- And every list that colours a name by group says the same thing about the people in it: the Kin tab's
-- roster and the Village and Realm tabs' "Members and known hearthlings" carry the group's NUMBER at the
-- right edge of each row, because the only thing a row itself says about the group is that colour, and
-- every group above the eighth is drawn in the same one. The number is the row's own list's: a kin's own
-- group in the roster, that polity's group for them in a member list.

local WIDTH = 64    -- design px. The colour row is 160 wide and the panel 263, so the picker fits either way
local GAP   = 2     -- design px between the colour row and the picker
local SYNC  = 0.25  -- seconds between two reads of what the rows are showing

-- The two rows that get NO picker, and why. Both were measured rather than assumed, and neither is a
-- crash: a number above the eighth reaches the server from either of them and comes to nothing there.
--   Landwindow -- the claim window's row picks which of the claim's own permission ROWS is being edited,
-- and a claim holds eight. Watched in both directions: the write leaves carrying the number and the flags
-- it was given (`-> shared 254 = 15`, the same statement that sends a click on a square), the claim
-- reopened pushes back only the rows it kept and never that one, and a character in that group is still
-- refused at the boundary. So a picker there does not offer a capability, it offers a SILENT FAILURE --
-- the ticks stand while the window is open, because this client remembers them, and are gone the next
-- time it is opened.
--   The Kin tab's row -- the kin's OWN group -- the server does keep, 0..254, across a restart. But above
-- the eighth nothing spends it: no colour of its own, and the one place a kin group is read is that same
-- claim table. It is a label, not a setting, and `kin:group(n)` is where an addon that wants one writes it.
-- That row is not in the table below and never could be: it sits inside the kin window, which is not
-- walkable from inside -- `:parent()` answers nil on anything under it, because the window holds this
-- character's hearth secret two branches along. It is turned away by the nil, one branch earlier.
local NO_PICKER = {Landwindow = true}

-- "0" .. "254", built once. Rows are strings, and `Changed` hands back the very one it was given.
local GROUPS = {}
for n = 0, 254 do GROUPS[n + 1] = tostring(n) end

local watchByAccount   = {}   -- [account] = the colour-row subscription on that character's tree
local numbersByAccount = {}   -- [account] = the member-row one
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
  -- nil is the kin window's row: unwalkable from inside, and a row with no reachable parent has nowhere to
  -- put a picker in any case. Both readings end here, so the nil is the whole check.
  if (panel == nil) or NO_PICKER[panel:type()] then return end
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
  -- ...and the rows already standing, which no "Added" is coming for: a reload with the window open, or a
  -- character who entered the world with one remembered open.
  for _, row in ipairs(session:ui():matchAll("@GroupSelector")) do addPicker(row) end
end

-- ------------------------------------------------------------------ the number on a member's row

-- The group is read LIVE inside the painter, never captured: a member's group changes under the row (the
-- server re-`add`s the member) and the row itself is rebuilt as the list scrolls, so anything remembered
-- here would be a second copy going stale against the one the client keeps.
local function numberRow(row)
  if row:group() == nil then return end   -- every other list's rows; only a polity member has one
  row:overlay():add("group"):draw(function(g, w, h)
    local group = row:group()
    if group == nil then return end
    g:color(210, 210, 210)
    g:atext(tostring(group), w - 2, h / 2, 1.0, 0.5)
  end)
end

local function watchMemberRows(session)
  local previous = numbersByAccount[session:user()]
  if previous then previous:off() end
  numbersByAccount[session:user()] = session:ui():on("@ItemWidget", "Added", numberRow)
  for _, row in ipairs(session:ui():matchAll("@ItemWidget")) do numberRow(row) end
end

hafen.event():on("SessionEnteredWorld", watchGroupRows)
hafen.event():on("SessionEnteredWorld", watchMemberRows)
hafen.event():on("SessionRemoved", function(session)
  watchByAccount[session:user()] = nil
  numbersByAccount[session:user()] = nil
end)

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
    local at, panel = row:position(), row:parent()
    local picker
    if panel == nil then picker = "none (the kin window is not walkable from inside)"
    elseif NO_PICKER[panel:type()] then picker = "none (the eight are the whole space here)"
    else picker = tostring(panel:matchAll("[name^=better-village-controls/]")[1] ~= nil) end
    say("  row " .. i .. ": panel=" .. (panel and panel:type() or "unreachable")
        .. " group=" .. tostring(row:value()) .. " at " .. at.x .. "," .. at.y
        .. " picker=" .. picker)
  end
end)
