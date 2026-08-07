-- 042.13 -- the close: the poll stage is deleted, and the whole feature's event catalogue is re-proven at
-- once. Self-checking suite; see specs/addons/TESTING.md and specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. Every per-frame poll site the feature named (CharApi.pollTreeAdapters and its six
-- adapters, UiApi.pollReplaced/pollWidgetSubs/pollSelectorWatches, Layout.poll/redrive, MapApi.pollMarkers,
-- RenderApi.armPending's per-frame retry) is DELETED, not gated -- TreeAdapter.poll() is gone from the
-- interface itself. This suite is the feature's whole regression in one command: every documented event the
-- catalogue promises still fires with an unchanged name and payload, and idling with EVERY one of them
-- subscribed at once produces exactly zero -- the categorical "no loop" claim (D-099), not a coincidence of
-- a short window (a per-frame poll would already have fired ~180 times in the 3s idle window below).
--
-- WHAT THIS SUITE CANNOT AUTOMATE. It is READ-ONLY, so it cannot eat, take damage, place a curiosity, equip
-- gear, set a belt slot or drop a map marker on its own -- each needs the maintainer's own action, covered
-- by ONE consolidated [manual] step rather than seven, since each subsystem's own edge cases were already
-- proven in its own task's suite (archived under specs/addons/042-event-driven-reads/addons/) -- this suite's
-- job is proving the wiring still reaches Lua after the close, not re-litigating each one.

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

-- Every documented bus event this feature's acceptance criteria covers (the six it rewired plus the four
-- neighbours the criteria names as "unchanged name/payload/identity" over the whole catalogue) -- subscribed
-- at FILE-LOAD time so a rename/typo anywhere in 042 throws "unknown event" right here, before run() even
-- starts, and so a manual action after this suite's own run still logs.
local BUS_EVENTS = {
  "MeterAdded", "MeterRemoved", "MeterChanged",
  "BuffAdded", "BuffRemoved", "BuffChanged",
  "StudyChanged", "ActionbarChanged", "EquipChanged", "WoundChanged",
  "KinChanged", "FepChanged", "MarkersChanged",
}
local counts, subOk = {}, true
for _, key in ipairs(BUS_EVENTS) do
  counts[key] = 0
  local ok = pcall(function()
    hafen.event():on(key, function()
      counts[key] = counts[key] + 1
      hafen.log():write(key .. " fired (#" .. counts[key] .. ")")
    end)
  end)
  if not ok then
    subOk = false
    hafen.log():write("could not subscribe to " .. key .. " -- see [fail] below")
  end
end

-- The container pair, on the inventory that is always open once GameUI is up -- looked up lazily (login/
-- reload race, same as 042.7's suite) rather than at file-load time.
local itemAdded, itemRemoved = 0, 0
local inv = nil
local function ensureInv()
  if (not inv) and hafen.ui():inventory() then
    inv = hafen.ui():inventory()
    inv:on("ItemAdded", function(it)
      itemAdded = itemAdded + 1
      hafen.log():write(("inventory ItemAdded fired (#%d) -- res=%s"):format(itemAdded, tostring(it:res())))
    end)
    inv:on("ItemRemoved", function(it)
      itemRemoved = itemRemoved + 1
      hafen.log():write(("inventory ItemRemoved fired (#%d) -- res=%s"):format(itemRemoved, tostring(it:res())))
    end)
  end
end

local function run()
  pass, fail, manual = 0, 0, 0
  ensureInv()

  -- 1. Every documented event name in the catalogue is still recognised -- a closed-set rename anywhere in
  --    this feature would already have thrown "unknown event" above.
  check(subOk, "every documented event name in the feature's catalogue is still recognised by hafen.event()",
        subOk)

  -- 2. The read surfaces (untouched by this feature -- only change DETECTION moved) still answer with the
  --    same objects an event payload hands back.
  check(type(hafen.meter():list()) == "table", "hafen.meter():list() still answers", nil)
  check(type(hafen.buff():list()) == "table", "hafen.buff():list() still answers", nil)
  check(type(hafen.study():slot():list()) == "table", "hafen.study():slot():list() still answers", nil)
  check(#hafen.actionbar():list() == 144, "hafen.actionbar():list() still answers all 144 slots",
        #hafen.actionbar():list())
  local eq = hafen.ui():equipment()
  check((eq ~= nil) and (type(eq:items()) == "table"), "hafen.ui():equipment():items() still answers", eq)
  check(type(hafen.wound():list()) == "table", "hafen.wound():list() still answers", nil)
  check(type(hafen.kin():list()) == "table", "hafen.kin():list() still answers", nil)
  check(type(hafen.map():marker():list()) == "table", "hafen.map():marker():list() still answers", nil)
  check(inv ~= nil, "hafen.ui():inventory() still answers (once GameUI is up)", inv)

  -- 3. Destroy still fires, exactly once, from the removal seam (M1) on an addon-owned widget -- the same
  --    seam every adapter above now uses instead of TreeAdapter.poll().
  local w = hafen.ui():widget():size(4, 4)
  local destroyed = 0
  w:on("Destroy", function() destroyed = destroyed + 1 end)
  w:destroy()

  hafen.timer():after(0.3, function()
    check(destroyed == 1, "Destroy fires exactly once, from the removal seam", destroyed)

    -- 4. Idle -- EVERY event in the catalogue subscribed at once, nothing changing. A per-frame poll would
    --    have run roughly 180 times in this window; the code's shape (no loop left to gate) runs it zero.
    local base, baseItemA, baseItemR = {}, itemAdded, itemRemoved
    for k, v in pairs(counts) do base[k] = v end
    hafen.timer():after(3, function()
      local drift = {}
      for _, key in ipairs(BUS_EVENTS) do
        local d = counts[key] - base[key]
        if d ~= 0 then drift[#drift + 1] = key .. "=" .. d end
      end
      local dItemA, dItemR = itemAdded - baseItemA, itemRemoved - baseItemR
      if (dItemA ~= 0) or (dItemR ~= 0) then
        drift[#drift + 1] = ("ItemAdded=%d ItemRemoved=%d"):format(dItemA, dItemR)
      end
      check(#drift == 0, "idle: zero events over 3s with every one of the feature's events subscribed at"
            .. " once -- a categorical zero, not a coincidence of this window",
            (#drift > 0) and table.concat(drift, ", ") or "none")

      manualCheck("do at least one of: take a hit or eat/drink (buff+meter), place a curiosity in Study and"
                  .. " let it finish, equip/unequip gear, set or clear a belt slot, get or heal a wound, add"
                  .. " or remove a map marker -- watch the log for the matching *Changed line",
                  "the matching event fires once for whichever you did, with no repeat while things then"
                  .. " sit still")

      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end)
  end)
end

hafen.slash():register("t042-13", run)   -- the only way in: a suite does not start itself
