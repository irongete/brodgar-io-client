-- 042.3 -- equipment. Self-checking suite; see specs/testing/addon-suite.md and
-- specs/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. CharApi's EquipAdapter.poll() is gone: EquipChanged now fires from the
-- widget-placement/removal seams (M3/M1) the moment a GItem is equipped/unequipped under the
-- Equipory, and from the existing uimsg tap for the "num"/"chres"/"tt" messages that arrive on the
-- worn GItem itself once it is interested in that item -- never a per-frame diff of the worn set.
-- "meter" (wear/durability) is deliberately excluded from the change key, so it never fires the
-- event. GItem.info() is the first real Resolve (M2) consumer: a bare item whose resource is still
-- streaming gets its info build retried once on the notify, not chased by a hidden poll.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Which item gets equipped/unequipped is the maintainer's own
-- action -- so the exact "one event, never a stream" claim and the "no event while wearing" claim
-- rest on a [manual] read of this log while performing the action.

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

-- Fired for as long as the suite is installed, so a manual action after :t042-3's own summary still shows up.
local changed = 0
local lastPayload = nil   -- the most recent EquipChanged payload, stashed for the "still answers after" check

hafen.event():on("EquipChanged", function(items)
  changed = changed + 1
  check(type(items) == "table", "EquipChanged carries a table payload", type(items))
  local live = hafen.ui():equipment():items()
  check(#items == #live, "the payload is the SAME list hafen.ui():equipment():items() answers right now (count)",
        ("payload=%d live=%d"):format(#items, #live))
  local sameIdentity = true
  for i = 1, #items do
    if items[i] ~= live[i] then sameIdentity = false end
  end
  check(sameIdentity, "the payload's items are identical (by identity) to hafen.ui():equipment():items()",
        tostring(sameIdentity))

  -- Whatever WAS worn a moment ago but is not in this new list either just resolved (same item, key
  -- changed) or came off -- only the latter is provably gone. A stale Item still answers its verbs and
  -- now reports :exists() false (025.2's rule, mirrored here for the equip slot).
  if lastPayload ~= nil then
    for _, old in ipairs(lastPayload) do
      local stillWorn = false
      for _, cur in ipairs(items) do
        if cur == old then stillWorn = true end
      end
      if not stillWorn and not old:exists() then
        local ok, res = pcall(function() return old:res() end)
        check(ok, "a departed item's :res() still answers (no error) after coming off", res)
        check(old:exists() == false, "a departed item reports :exists() false", old:exists())
      end
    end
  end

  local names = {}
  for i = 1, #items do
    names[i] = tostring(items[i]:res())
  end
  hafen.log():write(("EquipChanged fired (#%d) -- worn=%d [%s]")
    :format(changed, #items, table.concat(names, ", ")))
  lastPayload = items
end)

local function run()
  pass, fail, manual = 0, 0, 0
  local baseChanged = changed

  -- 1. The read surface already up still answers its verbs -- the reads themselves did not move, only
  --    how the ADD/REMOVE/CONTENT events are detected.
  local eq = hafen.ui():equipment()
  check(eq ~= nil, "hafen.ui():equipment() answers the worn Equipory", eq)
  if eq ~= nil then
    local items = eq:items()
    check(type(items) == "table", "equipment:items() answers a table", type(items))
    if #items > 0 then
      local it = items[1]
      check(it:exists(), "a worn item reports :exists() true", it:exists())
    end
  end

  -- 2. Idle: the feature's whole claim is that nothing fires on its own once nothing is changing --
  --    which also covers a durability meter ticking down on gear already worn, since that never
  --    reaches the change key.
  hafen.timer():after(3, function()
    local dChanged = changed - baseChanged
    check(dChanged == 0, "idle: no EquipChanged fires over 3s with nothing changing (a worn durability"
          .. " meter ticking down included)", ("changed=%d"):format(dChanged))

    manualCheck("equip one item, wait a couple of seconds (let its durability tick if it has one),"
                .. " then unequip it -- watching this log",
                "one 'EquipChanged fired (#N)' line the moment you equip it (worn count goes up by"
                .. " one) -- plus, only if that item's resource was still streaming in at that instant"
                .. " (uncommon once it is cached locally), ONE more line a moment later once its name"
                .. " resolves, never a third; then NO EquipChanged line at all while it just sits there"
                .. " worn (durability ticking down included); then exactly one more 'EquipChanged"
                .. " fired' line the instant you unequip it (worn count goes back down)")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-3", run)   -- the only way in: a suite does not start itself
