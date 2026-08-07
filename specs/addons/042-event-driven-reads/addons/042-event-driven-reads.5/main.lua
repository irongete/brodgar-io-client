-- 042.5 -- wounds. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. CharApi's WoundAdapter.poll() is gone: WoundChanged now fires from the
-- "wounds" uimsg the moment the server adds, heals or worsens a wound row -- never a per-frame diff
-- of the wound list. A wound's severity is DERIVED state with no queue of its own (Wound.info()), so
-- when it is not ready yet the Resolve (M2) retry-on-notify fires WoundChanged again, exactly once,
-- when the underlying resource lands.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Taking a wound, healing it, and whether its resource is already
-- cached locally (which decides whether a resolve-triggered second WoundChanged fires at all) are the
-- maintainer's own actions -- so the exact "one event per change" claim rests on a [manual] read of
-- this log while performing them.

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

-- Fired for as long as the suite is installed, so a manual action after :t042-5's own summary still shows up.
local changed = 0
local lastPayload = nil   -- the most recent WoundChanged payload, stashed for the "still answers after" check

hafen.event():on("WoundChanged", function(wounds)
  changed = changed + 1
  check(type(wounds) == "table", "WoundChanged carries a table payload", type(wounds))
  local live = hafen.wound():list()
  check(#wounds == #live, "the payload is the SAME list hafen.wound():list() answers right now (count)",
        ("payload=%d live=%d"):format(#wounds, #live))
  local sameIdentity = true
  for i = 1, #wounds do
    if wounds[i] ~= live[i] then sameIdentity = false end
  end
  check(sameIdentity, "the payload's wounds are identical (by identity) to hafen.wound():list()",
        tostring(sameIdentity))

  -- A wound that WAS in the previous payload but is not in this one healed. Its handle is data (an int
  -- id), not a widget, so once the wound record is gone its read verbs answer nil rather than a stashed
  -- value (unlike a buff/meter's widget-backed payload) -- but the CALL itself must still not error, and
  -- :exists() must read false.
  if lastPayload ~= nil then
    for _, old in ipairs(lastPayload) do
      local stillThere = false
      for _, cur in ipairs(wounds) do
        if cur == old then stillThere = true end
      end
      if not stillThere then
        local ok, exists = pcall(function() return old:exists() end)
        check(ok and (exists == false), "a healed wound's handle reports :exists() false", tostring(exists))
        local ok2 = pcall(function() return old:res() end)
        check(ok2, "a healed wound's handle answers :res() with no error (nil is fine)", tostring(ok2))
      end
    end
  end

  local rows = {}
  for i = 1, #wounds do
    rows[i] = tostring(wounds[i]:res()) .. "/" .. tostring(wounds[i]:severity())
  end
  hafen.log():write(("WoundChanged fired (#%d) -- wounds=%d [%s]")
    :format(changed, #wounds, table.concat(rows, ", ")))
  lastPayload = wounds
end)

local function run()
  pass, fail, manual = 0, 0, 0
  local baseChanged = changed

  -- 1. The read surface already up still answers its verbs -- the reads themselves did not move, only
  --    how the wound list's changes are detected.
  local wounds = hafen.wound()
  check(wounds ~= nil, "hafen.wound() answers the collection", wounds)
  if wounds ~= nil then
    local list = wounds:list()
    check(type(list) == "table", "wound():list() answers a table", type(list))
    if #list > 0 then
      local w = list[1]
      check(w:exists(), "a wound currently on the list reports :exists() true", w:exists())
      local ok = pcall(function() return w:res() end)
      check(ok, "a live wound's :res() answers with no error", ok)
    end
  end

  -- 2. Idle: the feature's whole claim is that nothing fires on its own once nothing is changing --
  --    whether or not the Health & Wounds tab happens to be open right now.
  hafen.timer():after(3, function()
    local dChanged = changed - baseChanged
    check(dChanged == 0, "idle: no WoundChanged fires over 3s with nothing changing (tab open or not)",
          ("changed=%d"):format(dChanged))

    manualCheck("take a wound (or open the Health & Wounds tab with one present), watching this log",
                "one 'WoundChanged fired (#N)' line the moment the wound appears (count goes up by one);"
                .. " if its severity was not already resolved, ONE more line a moment later once it"
                .. " resolves (nil to a value), never a third or a stream")
    manualCheck("let that wound heal a step, or fully heal",
                "one more 'WoundChanged fired' line per step it improves; if it worsens instead, one line"
                .. " showing the new severity; once fully healed it drops out of that line's wound list")
    manualCheck("equip/unequip gear rapidly a few times, or take a few hits with gear worn -- watch both"
                .. " the in-game chat AND this terminal",
                "no 'Resolve: not waitable, giving up' line anywhere, chat or terminal -- an item's"
                .. " sprite still building is routine and silent by design now")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-5", run)   -- the only way in: a suite does not start itself
