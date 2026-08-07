-- 042.6 -- the action bar, and the notify where the write lands. Self-checking suite; see
-- specs/addons/TESTING.md and specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. CharApi's ActionbarAdapter.poll() -- the 144-slot per-frame walk this
-- feature's headline number comes from -- is gone. ActionbarChanged now fires from the setbelt/
-- setbelt2 uimsg (three of five write paths land synchronously, so the existing tap already sees the
-- new value) PLUS a notify (AddonManager.onBeltSet) placed inside the two glob.loader.defer lambdas
-- that write belt[slot] asynchronously -- the ONLY place those two writes actually happen. cooldown
-- stays out of the change key, so a live meter ticking down while an ability cools fires nothing.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. It is READ-ONLY (no `actions` permission declared), so it cannot
-- itself set/clear/use a slot -- the maintainer's own drag/click/clear drives the [manual] lines.
-- What CAN be automated: every ActionbarChanged payload's shape, idle silence, and -- opportunistically,
-- if anything happens to be cooling while this runs -- that a cooldown keeps reading live with no
-- ActionbarChanged accompanying it.

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

-- Fired for as long as the suite is installed, so a manual drag/use/clear after :t042-6's own summary
-- still shows up.
local changed = 0
local perSlot = {}   -- slot index -> how many ActionbarChanged it has fired (feeds the cooldown check)

hafen.event():on("ActionbarChanged", function(slot)
  changed = changed + 1
  -- A Slot is a userdata handle (LuaSlot.userdataOf), not a plain table -- so the shape check is that
  -- it answers the Slot verbs, not a type() name.
  check(slot ~= nil, "ActionbarChanged carries a non-nil Slot payload", tostring(slot))
  local n = slot:index()
  check(type(n) == "number" and n >= 0 and n < 144, "the payload answers :index() in 0..143", n)
  local same = hafen.actionbar():get(n)
  check(same == slot, "the payload is the SAME Slot hafen.actionbar():get(n) answers (identity)",
        tostring(same == slot))
  perSlot[n] = (perSlot[n] or 0) + 1
  hafen.log():write(("ActionbarChanged fired (#%d) -- slot=%d empty=%s res=%s cooldown=%s")
    :format(changed, n, tostring(slot:empty()), tostring(slot:res()), tostring(slot:cooldown())))
end)

-- Opportunistic, automated proof of "no event while cooling": sample every occupied slot's cooldown
-- once a second; if a slot's cooldown value changes between two samples while its ActionbarChanged
-- count did NOT move in between, that is the claim proven live, not assumed. Silent (no line) for a
-- slot that never happens to be cooling while this runs -- it never fabricates a pass.
local lastCooldown = {}
local proved = {}
local function sampleCooldowns()
  local bar = hafen.actionbar()
  if bar == nil then return end
  for _, slot in ipairs(bar:list()) do
    if not slot:empty() then
      local n = slot:index()
      local cd = slot:cooldown()
      if cd ~= nil then
        check(type(cd) == "number" and cd >= 0 and cd <= 1,
              ("slot %d's cooldown reads live as a 0..1 number while occupied"):format(n), tostring(cd))
        local prev = lastCooldown[n]
        if (not proved[n]) and (prev ~= nil) and (prev.cd ~= cd) then
          check(perSlot[n] == prev.count,
                ("no ActionbarChanged fired while slot %d's cooldown ticked (%s -> %s)")
                  :format(n, tostring(prev.cd), tostring(cd)), tostring(perSlot[n]))
          proved[n] = true
        end
        lastCooldown[n] = {cd = cd, count = (perSlot[n] or 0)}
      end
    end
  end
end

local function run()
  pass, fail, manual = 0, 0, 0
  local baseChanged = changed

  -- 1. The read surface already up still answers its verbs -- the reads themselves did not move, only
  --    how a change is detected.
  local bar = hafen.actionbar()
  check(bar ~= nil, "hafen.actionbar() answers the collection", bar)
  local list = bar:list()
  check(type(list) == "table" and #list == 144, "actionbar():list() answers all 144 slots", #list)

  -- 2. Sample cooldowns now (in case something is already cooling) and keep sampling while the suite
  --    is installed, so a cooldown started during the [manual] step is caught too.
  sampleCooldowns()
  hafen.timer():every(1, sampleCooldowns)

  -- 3. Idle: the feature's whole claim is that nothing fires on its own once nothing is changing -- a
  --    cooling ability included (its cooldown ticking is excluded from the change key by design).
  hafen.timer():after(3, function()
    local dChanged = changed - baseChanged
    check(dChanged == 0, "idle: no ActionbarChanged fires over 3s with nothing changing",
          ("changed=%d"):format(dChanged))

    manualCheck("drag an action or item onto an empty belt slot, watching this log",
                "exactly one 'ActionbarChanged fired' line for that slot's index, content already set")
    manualCheck("use that slot so its ability starts cooling, and leave it a few seconds",
                "no further 'ActionbarChanged fired' line for that slot while it cools -- watch for a"
                .. " 'no ActionbarChanged fired while slot N's cooldown ticked' [pass] line above, which"
                .. " proves this automatically once the sampler catches the cooldown moving")
    manualCheck("clear that slot (drag it off, or clear it from the menu)",
                "exactly one more 'ActionbarChanged fired' line, empty=true, for that slot's index")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-6", run)   -- the only way in: a suite does not start itself
