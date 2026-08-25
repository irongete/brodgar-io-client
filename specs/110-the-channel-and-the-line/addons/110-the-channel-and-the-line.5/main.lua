-- 110.5 -- the event catalogue, split by subject. Self-checking suite.
--
-- WHAT THIS SHIPS. Nothing in Lua. The catalogue of bus keys became a folder of four family pages under
-- one hub, and every inbound link in the tree was re-pointed at it. No key moved and no payload changed.
--
-- HOW IT IS PROVED. A split catalogue can be wrong in two ways, and they are opposite. A key can survive
-- the move onto a page the bridge does not fire -- a row that reads perfectly and fails only when someone
-- runs it -- and a key the bridge does fire can fall OFF a page entirely. The first is what this suite
-- walks: every key is TRANSCRIBED here off the new page it now sits on, page by page, and each one is
-- SUBSCRIBED to and the subscription ended, which is the only thing that asks the bridge rather than the
-- docs. A key on two pages at once is the third way -- a second copy that will disagree with itself -- so
-- the pages are also walked against each other.
--
-- The second direction is not a program's to check from Lua: nothing enumerates the bus, so a key no page
-- names is a key nothing here can think to ask about. `tools/docverbs.py` takes that half, resolving the
-- bridge's own BUS_KEYS against the pages under docs/addons/api/event/.
--
-- Three refusals the hub page promises are checked beside them, because a refusal is a check too: the near
-- miss inside the session family, which is the one that would go quiet if a page and the bridge drifted
-- apart; the wildcard, which the hub says is the streams' and not the bus's; and a name no page carries,
-- whose message has to point at the catalogue where the catalogue now is.

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

-- The call must fail, and its message must carry every one of `wants`.
local function refuses(what, fn, wants)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  local missing = nil
  if ok then
    missing = "<no error>"
  else
    for _, w in ipairs(wants) do
      if err:find(w, 1, true) == nil then
        missing = "the message does not name '" .. w .. "': " .. err
        break
      end
    end
  end
  check(missing == nil, what, missing)
end

-- ----------------------------------------------------------------------------------------------------
-- The pages, transcribed. One entry per key, in the order its page lists it.
-- ----------------------------------------------------------------------------------------------------

local PAGES = {
  {"event/bus/lifecycle.md", {
    "Load", "Update", "Disable",
    "SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionRemoved",
  }},
  {"event/bus/world.md", {
    "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved",
    "GhostClicked", "SpriteClicked", "ObjectClicked",
  }},
  {"event/bus/character.md", {
    "MeterAdded", "MeterRemoved", "MeterChanged",
    "BuffAdded", "BuffRemoved", "BuffChanged",
    "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
    "KinChanged", "QuestAdded", "QuestCompleted", "QuestFailed", "MarkerChanged",
    "FlowerMenuAdded", "FlowerMenuRemoved",
  }},
  {"event/bus/chat.md", {
    "ChannelAdded", "ChannelRemoved", "ChannelSelected", "MessageAdded",
  }},
}

-- ----------------------------------------------------------------------------------------------------

local walked, live = 0, {}

local function noop() end

-- Every key the page lists is one `:on` takes, and the Sub it hands back is registered under that very
-- name. The subscription is kept, so that ending all of them is its own check further down.
local function walkPage(name, keys)
  local bad = nil
  for _, key in ipairs(keys) do
    walked = walked + 1
    local ok, sub = pcall(function() return hafen.event():on(key, noop) end)
    if not ok then
      bad = "'" .. key .. "' is refused: " .. tostring(sub):gsub("^.-%.lua:%d+:%s*", "")
      break
    end
    if sub == nil or sub:key() ~= key then
      bad = "'" .. key .. "' subscribed as '" .. tostring(sub and sub:key()) .. "'"
      break
    end
    live[#live + 1] = sub
  end
  check(bad == nil, ("every key on %s is one the bus accepts (%d)"):format(name, #keys), bad)
end

local function body()
  local before = hafen.event():count()

  for _, page in ipairs(PAGES) do
    walkPage(page[1], page[2])
  end

  -- A key on two pages is a second copy of one row, and two copies disagree.
  local seen, twice = {}, nil
  for _, page in ipairs(PAGES) do
    for _, key in ipairs(page[2]) do
      if seen[key] then
        twice = "'" .. key .. "' is on " .. seen[key] .. " and on " .. page[1]
      end
      seen[key] = page[1]
    end
  end
  check(twice == nil, "no key is listed on two pages of the catalogue", twice)

  -- Ending them all is the other half of the subscription check: a Sub that will not end is not a Sub.
  local unended = nil
  for _, sub in ipairs(live) do
    local ok = pcall(function() sub:off() end)
    if not ok then
      unended = sub:key()
      break
    end
  end
  local after = hafen.event():count()
  check((unended == nil) and (after == before),
        ("every subscription this run made ended (%d walked)"):format(walked),
        unended or (tostring(before) .. " subscriptions before, " .. tostring(after) .. " after"))

  -- The refusals the hub page promises.
  refuses("a near miss in the session family is refused naming all four",
          function() hafen.event():on("SessionEntered", noop) end,
          {"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionRemoved"})

  refuses("the wildcard is refused on the bus and names the two streams",
          function() hafen.event():on("*", noop) end,
          {"hafen.event():action():on(\"*\", fn)", "hafen.event():message():on(\"*\", fn)"})

  refuses("a name no page carries is refused pointing at the catalogue",
          function() hafen.event():on("ChannelChanged", noop) end,
          {"unknown event 'ChannelChanged'", "docs/addons/api/event/bus/"})

  hafen.log():write(("[summary] %d pass, %d fail, %d manual -- %d keys across %d pages")
    :format(pass, fail, manual, walked, #PAGES))
end

-- The run is one pcall, so a read that throws becomes one [fail] line and a summary rather than a bare
-- stack trace with no verdict under it. Whatever it reached is unsubscribed either way.
local function run()
  local ok, err = pcall(body)
  if not ok then
    for _, s in ipairs(live) do pcall(function() s:off() end) end
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
end

hafen.console():on("t110", run)   -- the only way in: a suite does not start itself
