-- 172.2 — the deck is every hotkey place, read like the combat row. Self-checking suite.
--
-- Run :t172 on a character in the world. It reads that character's loaded layout (the Martial Arts & Combat
-- Schools tab), prints it, and checks the deck collection, each card, a manoeuvre's arity and the summary
-- through the API.

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

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(text, needle)
  return (text ~= nil) and (text:find(needle, 1, true) ~= nil)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A table's keys, sorted and joined, so a snapshot's shape compares as one string.
local function keysOf(snapshot)
  if type(snapshot) ~= "table" then return tostring(snapshot) end
  local names = {}
  for key in pairs(snapshot) do names[#names + 1] = tostring(key) end
  table.sort(names)
  return table.concat(names, ",")
end

-- The tab's hotkey labels, in place order. The shift arrow (U+21E7) is spelled as its UTF-8 bytes, so the
-- comparison does not depend on how this file is decoded.
local SHIFT = "\226\135\167"
local LABELS = { "1", "2", "3", "4", "5", SHIFT .. "1", SHIFT .. "2", SHIFT .. "3", SHIFT .. "4", SHIFT .. "5" }

local function run()
  pass, fail, manual = 0, 0, 0
  local session = hafen.session():current()
  if not session then
    hafen.log():write("[fail] no character in the world -- log in and run :t172 again")
    return
  end
  local fight = session:fight()
  local deck = fight:deck()
  local list = deck:list()
  local count = deck:count()

  -- The layout as the tab paints it: hotkey number, then the manoeuvre or (empty).
  local shown = {}
  for _, card in ipairs(list) do
    shown[#shown + 1] = card:index() .. ": "
      .. (card:empty() and "(empty)" or (card:name() or card:res() or "(resolving)"))
  end
  hafen.log():write("[info] deck: " .. ((#shown > 0) and table.concat(shown, ", ") or "(none)"))

  -- 1. One object, counted, every card at its own place.
  check((fight:deck() == deck) and (count == #list) and (count >= 1),
        "deck() is one object, and :count() == #:list() >= 1", "count " .. tostring(count) .. ", #list " .. #list)
  if count < 1 then
    -- The tab builds at login; with no place there is nothing the rest could read.
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  local misplaced, mislabelled
  for position, card in ipairs(list) do
    if (card:index() ~= position) or (card:wire() ~= position - 1) or (deck:get(position) ~= card) then
      misplaced = misplaced or position
    end
    if card:key() ~= LABELS[position] then
      mislabelled = mislabelled or (position .. " reads " .. tostring(card:key()))
    end
  end
  check(misplaced == nil,
        "each card's :index() is its position, :wire() that minus 1, and :get(n) == :list()[n]",
        "position " .. tostring(misplaced))
  check(mislabelled == nil, ":key() is the tab's label: 1..5 then shift-1..shift-5, nil past ten", mislabelled)
  check((deck:get(0) == nil) and (deck:get(count + 1) == nil), ":get(0) and :get(count + 1) are nil",
        tostring(deck:get(0)) .. " / " .. tostring(deck:get(count + 1)))
  local text = refusal(function() return deck:get("x") end)
  check(says(text, ":find(filter)"), ':get("x") raises, naming :find(filter)', text or "<no error>")

  -- 2. An empty place reads nothing; a filled one's snapshot, its manoeuvre and the search.
  local empties, filled, emptyWrong, infoWrong, searchWrong = 0, 0, nil, nil, nil
  local someManeuver
  for _, card in ipairs(list) do
    if card:empty() then
      empties = empties + 1
      if (card:res() ~= nil) or (card:name() ~= nil) or (card:maneuver() ~= nil) or (card:used() ~= nil)
         or (card:info() ~= nil) then
        emptyWrong = emptyWrong or tostring(card)
      end
    else
      filled = filled + 1
      local info = card:info()
      local stray = (type(info) ~= "table") or (info.used == nil)
      if type(info) == "table" then
        for key in pairs(info) do
          if (key ~= "key") and (key ~= "res") and (key ~= "name") and (key ~= "used") then stray = true end
        end
      end
      if stray then
        infoWrong = infoWrong or (tostring(card) .. " info {" .. keysOf(info) .. "}")
      end
      local maneuver = card:maneuver()
      someManeuver = someManeuver or maneuver
      local res = card:res()
      if (maneuver == nil) or (maneuver:res() ~= res) then
        searchWrong = searchWrong or (tostring(card) .. " :maneuver() " .. tostring(maneuver))
      elseif res ~= nil then
        local hit = deck:find(res)
        if (hit == nil) or hit:empty() or not says(hit:res(), res) then
          searchWrong = searchWrong or (tostring(card) .. " :find(res) found " .. tostring(hit))
        end
      end
    end
  end
  check(emptyWrong == nil, "an empty card answers nil from :res() :name() :maneuver() :used() :info()"
        .. ((empties == 0) and " (no place is empty)" or ""), emptyWrong)
  check(infoWrong == nil, "a filled card's :info() has used, and no key but key/res/name/used (no slot)"
        .. ((filled == 0) and " (no place is filled)" or ""), infoWrong)
  check(searchWrong == nil, "a filled card's :maneuver():res() == :res(), and deck():find(res) finds it"
        .. ((filled == 0) and " (no place is filled)" or ""), searchWrong)

  -- 3. What a card and a manoeuvre refuse.
  text = refusal(function() return list[1]:exists() end)
  check(says(text, "has no verb 'exists'") and says(text, ":empty()"),
        "card:exists() raises 'has no verb', listing :empty()", text or "<no error>")
  someManeuver = someManeuver or fight:maneuver():list()[1]
  local cardText = refusal(function() return list[1]:res(1) end)
  local maneuverText = someManeuver and refusal(function() return someManeuver:res(1) end)
  check(says(cardText, "takes no arguments") and ((someManeuver == nil) or says(maneuverText, "takes no arguments")),
        "card:res(1) and maneuver:res(1) raise 'takes no arguments'"
        .. ((someManeuver == nil) and " (no manoeuvre known)" or ""),
        (cardText or "<no error>") .. " / " .. tostring(maneuverText))

  -- 4. The summary keeps the budget only.
  local summary = fight:summary()
  if summary == nil then
    check(false, "session:fight():summary() is there", "nil")
  else
    text = refusal(function() return summary:deckSize() end)
    check(says(text, "has no verb"), "summary:deckSize() raises 'has no verb'", text or "<no error>")
    local snapshot = summary:info()
    check(keysOf(snapshot) == "maxact,used", "summary:info()'s keys are exactly maxact and used",
          "{" .. keysOf(snapshot) .. "}")
  end

  manualCheck("open Character sheet -> Martial Arts & Combat Schools and read the deck row",
              "the [info] line's manoeuvre under each hotkey, in order, and the (empty) ones empty")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t172", run)   -- the only way in: a suite does not start itself
