-- 042.4 -- study. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. CharApi's StudyAdapter.poll() is gone: StudyChanged now fires from the
-- widget-placement/removal seams (M3/M1) the moment a curiosity enters/leaves the study window --
-- never a per-frame diff of the slots. A slot's Curiosity numbers are DERIVED state with no queue of
-- their own (GItem.info()), so when they are not ready yet the first real Resolve (M2) retry-on-notify
-- fires StudyChanged again, exactly once, when the underlying resource lands -- not chased by a poll.
-- A curiosity with no Curiosity info at all (e.g. a Hearth-Magic bond) is not an error: its snapshot
-- carries {res, name} only and it is correctly excluded from the numeric summary.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Which curiosity gets placed/removed, and whether its resource is
-- already cached locally (which decides whether a resolve-triggered second StudyChanged fires at all),
-- is the maintainer's own action -- so the exact "one event, never a stream" claim rests on a [manual]
-- read of this log while performing the action.

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

-- Fired for as long as the suite is installed, so a manual action after :t042-4's own summary still shows up.
local changed = 0
local lastPayload = nil   -- the most recent StudyChanged payload, stashed for the "still answers after" check

hafen.event():on("StudyChanged", function(slots)
  changed = changed + 1
  check(type(slots) == "table", "StudyChanged carries a table payload", type(slots))
  local live = hafen.study():slot():list()
  check(#slots == #live, "the payload is the SAME list hafen.study():slot():list() answers right now (count)",
        ("payload=%d live=%d"):format(#slots, #live))
  local sameIdentity = true
  for i = 1, #slots do
    if slots[i] ~= live[i] then sameIdentity = false end
  end
  check(sameIdentity, "the payload's slots are identical (by identity) to hafen.study():slot():list()",
        tostring(sameIdentity))

  -- Whatever WAS in the window a moment ago but is not in this new list either just resolved (same
  -- slot, key changed) or came out -- only the latter is provably gone. A stale StudySlot still answers
  -- its verbs and now reports :exists() false (025.2's/042.3's rule, mirrored here).
  if lastPayload ~= nil then
    for _, old in ipairs(lastPayload) do
      local stillThere = false
      for _, cur in ipairs(slots) do
        if cur == old then stillThere = true end
      end
      if not stillThere and not old:exists() then
        local ok, res = pcall(function() return old:res() end)
        check(ok, "a departed slot's :res() still answers (no error) after leaving the window", res)
        check(old:exists() == false, "a departed slot reports :exists() false", old:exists())
      end
    end
  end

  -- A Curiosity-less slot (e.g. a Hearth-Magic bond) is not an error: its lp/attention/cost/time all
  -- read nil together, res/name are what identify it, and its :info() snapshot carries no numeric keys.
  for _, s in ipairs(slots) do
    if s:lp() == nil then
      local ok, info = pcall(function() return s:info() end)
      check(ok, "a Curiosity-less slot's :info() still answers (no error)", info)
      if ok and (info ~= nil) then
        check(info.lp == nil, "a Curiosity-less slot's :info() snapshot carries no lp field", info.lp)
      end
    end
  end

  local names = {}
  for i = 1, #slots do
    names[i] = tostring(slots[i]:res())
  end
  hafen.log():write(("StudyChanged fired (#%d) -- slots=%d [%s]")
    :format(changed, #slots, table.concat(names, ", ")))
  lastPayload = slots
end)

local function run()
  pass, fail, manual = 0, 0, 0
  local baseChanged = changed

  -- 1. The read surface already up still answers its verbs -- the reads themselves did not move, only
  --    how the ADD/REMOVE/RESOLVE events are detected.
  local slot = hafen.study():slot()
  check(slot ~= nil, "hafen.study():slot() answers the collection", slot)
  if slot ~= nil then
    local slots = slot:list()
    check(type(slots) == "table", "study:slot():list() answers a table", type(slots))
    if #slots > 0 then
      local s = slots[1]
      check(s:exists(), "a slot currently in the window reports :exists() true", s:exists())
    end
  end

  -- 2. Idle: the feature's whole claim is that nothing fires on its own once nothing is changing --
  --    whether or not the Study tab happens to be open right now.
  hafen.timer():after(3, function()
    local dChanged = changed - baseChanged
    check(dChanged == 0, "idle: no StudyChanged fires over 3s with nothing changing (tab open or not)",
          ("changed=%d"):format(dChanged))

    manualCheck("open Study, place a curiosity, then close and reopen the tab -- watching this log",
                "one 'StudyChanged fired (#N)' line the moment you place it (slot count goes up by one);"
                .. " if its resource was not already cached locally, ONE more line a moment later once its"
                .. " learning-point numbers resolve, never a third or a stream; then NO StudyChanged line"
                .. " at all while it just sits there studying; then exactly one more 'StudyChanged fired'"
                .. " line the instant it finishes and leaves the window (or you take it back out)")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-4", run)   -- the only way in: a suite does not start itself
