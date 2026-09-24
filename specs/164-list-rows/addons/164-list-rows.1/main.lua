-- 164.1 — the rows of the client's lists, and its search lists. Self-checking suite.
-- Run it with the Kith & Kin window open on its Village tab, so the member list is filled.

local pass, fail, manual = 0, 0, 0
local poll                                   -- the previous run's timer, taken down by the next run
local subscriptions = {}                     -- every Search handler this run holds, ended before it reports

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function refusal(fn, ...)
  local ok, err = pcall(fn, ...)
  if ok then return "<no error>" end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(message, text)
  return message:find(text, 1, true) ~= nil
end

-- The client's own test on all four search lists: the row's text contains what is typed, in any case.
local function contains(text, part)
  return (text ~= nil) and (text:lower():find(part:lower(), 1, true) ~= nil)
end

local function endSubscriptions()
  for _, subscription in ipairs(subscriptions) do subscription:off() end
  subscriptions = {}
end

local function answers(widget, key)
  for _, name in ipairs(widget:events()) do
    if name == key then return true end
  end
  return false
end

-- The member lists are found through a row: its class is the server's to name, and a kin row's parent is nil
-- (the Kin window stops that walk), so a row with a group and a parent is a village's or realm's member row.
local function memberLists(session)
  local lists, seen = {}, {}
  for _, row_widget in ipairs(session:ui():matchAll("@ItemWidget")) do
    local list = row_widget:group() ~= nil and row_widget:parent() or nil
    if list and not seen[list] and answers(list, "Search") then
      seen[list] = true
      lists[#lists + 1] = list
    end
  end
  return lists
end

-- A search on one list, with a handler counting the rows and comparing each first verdict to the client's test.
local function searchesEveryRow(list)
  local rows = list:rows() or {}
  local sample
  for _, row in ipairs(rows) do
    sample = sample or (row:text() or ""):match("^(%a%a)")
  end
  if sample == nil then return true end    -- no name to type: nothing to hold the verdicts to
  local fired, wrong = 0, nil
  subscriptions[#subscriptions + 1] = list:on("Search", function(event)
    fired = fired + 1
    local own = contains(event:row():text(), sample)
    if event:text() ~= sample or event:match() ~= own then wrong = wrong or event:row() end
  end)
  list:search(sample)
  endSubscriptions()
  list:search("")
  return (fired == #rows) and (wrong == nil), ("%d of %d rows asked, first wrong %s"):format(fired, #rows, tostring(wrong))
end

-- Every row widget of a list draws one of the list's rows, in the group the row reads.
local function joined(list)
  local held = {}
  for _, row in ipairs(list:rows() or {}) do held[row] = true end
  local row_widgets = list:matchAll("@ItemWidget")
  for _, row_widget in ipairs(row_widgets) do
    local row = row_widget:row()
    if row == nil or not held[row] or row_widget:group() ~= row:group() then return false, tostring(row) end
  end
  return #row_widgets > 0, #row_widgets .. " row widgets"
end

local function run()
  if poll then poll:cancel() end
  endSubscriptions()
  pass, fail, manual = 0, 0, 0
  local session = hafen.session():current()
  local kin_list = session and session:ui():match("@BuddyList")
  if kin_list == nil then
    check(false, "the kin list is in the tree", "no @BuddyList -- log a character in")
    return summary()
  end

  -- 1: the kin list's rows are the roster, in its order
  local rows, roster = kin_list:rows(), session:kin():list()
  local same = #rows == #roster
  for index, kin in ipairs(roster) do
    local row = rows[index]
    same = same and row ~= nil and row:text() == kin:name() and row:group() == kin:group()
  end
  check(same, "kin_list:rows() is the kin roster, row by row: text and group", #rows .. " rows, " .. #roster .. " kin")

  -- 2, 3: one row, one value; the snapshot; a surplus argument
  local again = kin_list:rows()
  local keyed = {}
  if rows[1] then keyed[rows[1]] = true end
  check(rows[1] == nil or (rows[1] == again[1] and keyed[again[1]] == true),
        "two reads hand back == rows, and one keys a table the other reads", tostring(rows[1]))
  local info = rows[1] and rows[1]:info()
  local surplus = rows[1] and refusal(function() return rows[1]:text(1) end) or "takes no arguments"
  check(rows[1] == nil or (info.text == rows[1]:text() and info.group == rows[1]:group() and says(surplus, "takes no arguments")),
        "row:info() carries text and group, and row:text(1) is refused naming the arity", surplus)

  -- 4: rows() elsewhere, rows(t) on a client list
  local hud = session:ui():match("@GameUI")
  local written = refusal(function() kin_list:rows({}) end)
  check(hud:rows() == nil and says(written, "widget:rows()"),
        "GameUI:rows() reads nil, and kin_list:rows({}) is refused naming widget:rows()", written)

  -- 5: reading and typing the search
  local before = kin_list:search()
  kin_list:search("zz")
  local during = kin_list:search()
  kin_list:search("")
  local after = kin_list:search()
  local nil_text = refusal(function() kin_list:search(nil) end)
  local not_search = refusal(function() hud:search("zz") end)
  check(before == nil and during == "zz" and after == nil and says(nil_text, '""') and says(not_search, "does not search"),
        "search() reads nil, then what search(text) typed, then nil after \"\"; nil and a non-list are refused",
        tostring(before) .. "/" .. tostring(during) .. "/" .. tostring(after) .. " | " .. nil_text)

  -- 6: every open search list asks every row, starting from the client's own test
  local lists = { kin_list }
  local members = memberLists(session)
  for _, list in ipairs(members) do lists[#lists + 1] = list end
  for _, list in ipairs(session:ui():matchAll("@MarkerList")) do lists[#lists + 1] = list end
  for _, list in ipairs(session:ui():matchAll("@IconList")) do lists[#lists + 1] = list end
  local all_asked, detail = true, ""
  for _, list in ipairs(lists) do
    local ok, got = searchesEveryRow(list)
    if not ok then all_asked, detail = false, list:type() .. ": " .. got end
  end
  check(all_asked, "Search asks every row of " .. #lists .. " open search list(s), with the text and the client's own verdict", detail)

  -- 8: row widgets join the rows, on the kin list and every member list
  local join_ok, join_detail = joined(kin_list)
  for _, list in ipairs(members) do
    local ok, got = joined(list)
    join_ok, join_detail = join_ok and ok, join_detail .. "; " .. got
  end
  check(join_ok and #members > 0, "every row widget's :row() is one of its list's rows, in the same group",
        (#members == 0) and "no village or realm member list is filled -- open the Village tab" or join_detail)

  -- 7: one handler drops every row, another keeps one. The list draws that row alone once it has rebuilt its
  -- row widgets (on its own tick), and an addon's search picked nothing: the selection is what it was.
  local target = rows[#rows]
  if target == nil then
    check(false, "the verdicts keep one row, and the search picks nothing", "your kin list is empty")
    return summary()
  end
  local picked_before = kin_list:value()
  subscriptions[#subscriptions + 1] = kin_list:on("Search", function(event) event:match(false) end)
  subscriptions[#subscriptions + 1] = kin_list:on("Search", function(event)
    if event:row() == target then event:match(true) end
  end)
  kin_list:search("zzq")
  endSubscriptions()
  local started = os.time()
  poll = hafen.timer():every(0.25, function()
    local drawn = kin_list:matchAll("@ItemWidget")
    local alone = (#drawn == 1) and (drawn[1]:row() == target)
    if not alone and os.time() - started < 3 then return end
    poll:cancel()
    poll = nil
    check(alone and kin_list:value() == picked_before and kin_list:search() == "zzq",
          "the verdicts keep " .. tostring(target:text()) .. " alone, and the search picked nothing",
          #drawn .. " row widget(s), picked " .. tostring(kin_list:value()) .. ", search " .. tostring(kin_list:search()))
    manualCheck("switch the Kith & Kin window to its Kin tab",
                "only " .. tostring(target:text()) .. " listed, with 'zzq (1/" .. #rows .. ")' in the list's corner;"
                .. " click an empty part of the list, then anywhere outside it, and everyone is listed again")
    summary()
  end)
end

hafen.console():on("t164-1", function() hafen.timer():after(0, run) end)
