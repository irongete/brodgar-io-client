-- 042.7 -- the per-widget container keys. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. UiApi.pollWidgetSubs and WidgetSubs.poll(UI) are gone. widget:on("Destroy", fn)
-- fires from the widget-removal seam (M1) the instant the watched widget itself is removed -- including
-- for a WINDOW's fade, which gets the SAME early tap Buff.reqdestroy got in 042.2 (D-180's second
-- consumer, in Window.reqdestroy): the Destroy fires when the server said "gone", not ~0.35s later when
-- the fade animation actually unlinks the widget. widget:on("ItemAdded"/"ItemRemoved", fn) fire from the
-- placement/removal seams on a container's WItem children instead of a per-tick diff.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. It is READ-ONLY (no permissions declared), so it cannot itself pick up,
-- drop or equip an item, and it cannot make a native window fade on demand -- both need the maintainer's
-- own action. What CAN be automated: Destroy on an addon-owned widget (immediate -- an owned :destroy()
-- never fades, so it needs a tick to drain, not instant), re-entrant (un)subscription from inside a firing
-- handler not breaking the watch list, idle silence on the two containers that are always open.

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

-- Item events on the two containers that are always open -- fired for as long as this suite is installed,
-- so a manual action after :t042-7's own summary still shows up. hafen.ui():inventory()/:equipment() can
-- read nil if GameUI is not up yet the moment this FILE loads (login/reload race) -- so the actual lookup
-- and subscription happen lazily, retried from run() until they land, rather than once at load time.
local invAdded, invRemoved, eqAdded, eqRemoved = 0, 0, 0, 0
local inv, eq = nil, nil

local function ensureItemWatches()
  if (not inv) and hafen.ui():inventory() then
    inv = hafen.ui():inventory()
    inv:on("ItemAdded", function(it)
      invAdded = invAdded + 1
      hafen.log():write(("inventory ItemAdded fired (#%d) -- res=%s"):format(invAdded, tostring(it:res())))
    end)
    inv:on("ItemRemoved", function(it)
      invRemoved = invRemoved + 1
      hafen.log():write(("inventory ItemRemoved fired (#%d) -- res=%s"):format(invRemoved, tostring(it:res())))
    end)
  end
  if (not eq) and hafen.ui():equipment() then
    eq = hafen.ui():equipment()
    eq:on("ItemAdded", function(it)
      eqAdded = eqAdded + 1
      hafen.log():write(("equipment ItemAdded fired (#%d) -- res=%s"):format(eqAdded, tostring(it:res())))
    end)
    eq:on("ItemRemoved", function(it)
      eqRemoved = eqRemoved + 1
      hafen.log():write(("equipment ItemRemoved fired (#%d) -- res=%s"):format(eqRemoved, tostring(it:res())))
    end)
  end
end

-- Every window that appears while this suite is installed gets its own Destroy watch (weak keys: a closed
-- window's record must not outlive it) -- so a window closed during the [manual] step below is caught, and
-- the timing is on the log for the maintainer's own eyeball (the exact "not late" claim, like 042.2's
-- BuffRemoved, is not something Lua can time against the fade's own start). Registering this needs no
-- GameUI to already be up -- "appear" also fires for whatever is already open once it is. The "appeared"
-- line is a diagnostic: it lets the maintainer SEE that a given window is being watched, and it is also
-- the tool for the docs/addons/api/ui/replace.md warning this test can otherwise silently run into --
-- "Neither event is about visibility... a window the client merely hides -- the inventory's Tab toggle --
-- never left, so it fires neither" -- several HUD panels (Inventory, Equipment, the character sheet) are
-- exactly that: a permanent Hidewnd the Tab/hotkey toggle only shows/hides, never destroys. Those will
-- correctly log "appeared" once and then NEVER log a Destroy, however many times they are toggled --
-- pick something the SERVER actually destroys instead (a chest/cupboard, a trade or build/craft dialog).
local wndSeen = setmetatable({}, {__mode = "k"})
hafen.ui():on("window", "appear", function(w)
  if wndSeen[w] then return end
  wndSeen[w] = true
  local title = tostring(w:text())
  hafen.log():write(("window '%s' appeared -- now watching for its Destroy (a HUD panel merely TOGGLED"
    .. " hidden, like Inventory/Equipment/the character sheet, correctly never fires one -- use a real"
    .. " chest/cupboard/dialog for the manual step)"):format(title))
  w:on("Destroy", function()
    hafen.log():write(("window '%s' Destroy fired -- watch for it landing the instant you closed it, not"
      .. " roughly a third of a second later"):format(title))
  end)
end)

local function run()
  pass, fail, manual = 0, 0, 0
  ensureItemWatches()

  -- 1. The read surface is untouched -- only how a change is detected moved.
  check(inv ~= nil, "hafen.ui():inventory() answers (once GameUI is up)", inv)
  check(eq ~= nil, "hafen.ui():equipment() answers (once GameUI is up)", eq)

  -- 2. Destroy on an addon-owned widget: EVERY handler subscribed fires -- not one slot silently replaced
  --    -- once the removal-seam queue this task drains has had a tick to run (the destroy itself only
  --    enqueues; AddonManager.tick's drain is what dispatches to Lua).
  local w1 = hafen.ui():widget():size(4, 4)
  local firedA, firedB = false, false
  w1:on("Destroy", function() firedA = true end)
  w1:on("Destroy", function() firedB = true end)
  w1:destroy()

  hafen.timer():after(0.2, function()
    check(firedA and firedB, "Destroy fires on EVERY handler subscribed to an owned widget, once each",
          tostring(firedA) .. "/" .. tostring(firedB))

    -- 3. And it fires EXACTLY once -- no repeat on a later tick.
    local w1b = hafen.ui():widget():size(4, 4)
    local count1b = 0
    w1b:on("Destroy", function() count1b = count1b + 1 end)
    w1b:destroy()

    hafen.timer():after(0.5, function()
      check(count1b == 1, "Destroy fires EXACTLY once per widget destroyed, not a repeat later", count1b)

      -- 4. Re-entrant (un)subscription from inside a firing Destroy handler does not break the watch list
      --    -- the copy-on-write rule this task's own gotcha names.
      local w2, w3 = hafen.ui():widget():size(4, 4), hafen.ui():widget():size(4, 4)
      local reentryOk, w3DestroyOk = false, false
      w2:on("Destroy", function()
        reentryOk = pcall(function() w3:on("Destroy", function() w3DestroyOk = true end) end)
      end)
      w2:destroy()

      hafen.timer():after(0.3, function()
        check(reentryOk, "subscribing to a DIFFERENT widget from inside a firing Destroy handler does not"
              .. " throw", reentryOk)
        w3:destroy()

        hafen.timer():after(0.3, function()
          check(w3DestroyOk, "...and that re-entrant subscription still fires when ITS widget is destroyed"
                .. " later", w3DestroyOk)

          -- 5. Idle: nothing fires on its own with nothing changing -- item events on the two real
          --    containers already open, and no stray Destroy anywhere above.
          local baseInvA, baseInvR = invAdded, invRemoved
          local baseEqA, baseEqR = eqAdded, eqRemoved
          hafen.timer():after(3, function()
            local dInvA, dInvR = invAdded - baseInvA, invRemoved - baseInvR
            local dEqA, dEqR = eqAdded - baseEqA, eqRemoved - baseEqR
            check((dInvA == 0) and (dInvR == 0) and (dEqA == 0) and (dEqR == 0),
                  "idle: no ItemAdded/ItemRemoved fires over 3s with nothing changing",
                  ("inv=%d/%d eq=%d/%d"):format(dInvA, dInvR, dEqA, dEqR))

            manualCheck("pick up an item into your backpack, then drop or move it out again, watching this"
                        .. " log", "one 'inventory ItemAdded fired' line the moment it enters, then one"
                        .. " 'inventory ItemRemoved fired' line the moment it leaves -- each exactly once")
            manualCheck("equip an item, then unequip it",
                        "one 'equipment ItemAdded fired' line, then one 'equipment ItemRemoved fired' line")
            manualCheck("open something the SERVER destroys when you're done with it -- a chest/cupboard,"
                        .. " a trade proposal, a build/craft confirmation dialog -- then close it (NOT a"
                        .. " HUD toggle panel like Inventory/Equipment/the character sheet: those only"
                        .. " hide, they never destroy, so they will show an 'appeared' line above but"
                        .. " correctly no Destroy, however many times you toggle them)",
                        "a 'window ... appeared' line when it opens, then one 'window ... Destroy fired'"
                        .. " line printed the instant you close it -- not delayed -- and never a second"
                        .. " one for the same window")

            hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
          end)
        end)
      end)
    end)
  end)
end

hafen.slash():register("t042-7", run)   -- the only way in: a suite does not start itself
