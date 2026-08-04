-- hogtest -- exercises the D-018 soft per-tick CPU budget + auto-disable (Phase 1f-3).
--
-- DORMANT by default: it does nothing until ARMED. To test the soft-budget watchdog:
--   1. Arm it by setting "arm": true inside its account saved-vars file
--        savedata/account/hogtest.json    (created after the first run; shape: {"cfg":{"arm":false}})
--      -- or from the (unsandboxed) :lua console in one line:
--        :lua io.open("savedata/account/hogtest.json","w"):write('{"cfg":{"arm":true}}'):close()
--   2. Reload the addon layer:   :reload    (or relog).
-- Once armed it burns ~15 ms of Lua on EVERY OnUpdate -- over the per-tick budget (default 10 ms) but
-- under the hard per-call instruction cap -- so after a sustained run of over-budget ticks (default 30)
-- the engine AUTO-DISABLES it for the session and the AddOns panel (Options -> AddOns) shows the
-- warning on its row (also logged to the console). To stop: set "arm": false (or untick hogtest in the
-- AddOns panel), then :reload.

local hafen = hafen
local BURN = 0.015   -- seconds of Lua to burn per OnUpdate when armed

-- Busy-wait on os.clock (whitelisted; CPU seconds). Deliberately wasteful but BOUNDED per call (well
-- under the hard instruction cap), repeated every tick -- exactly what the SOFT budget is meant to catch.
local function hog()
  local t0 = os.clock()
  while os.clock() - t0 < BURN do end
end

hafen.event():on("OnLoad", function()
  local cfg = hafen.store.cfg           -- account-scope vars are loaded before OnLoad (Phase 1e)
  if cfg.arm == nil then cfg.arm = false end   -- seed the file on first run
  if cfg.arm then
    hafen.log():write("hogtest ARMED -- burning ~" .. math.floor(BURN * 1000)
              .. "ms/OnUpdate; expect an auto-disable + AddOns-panel warning shortly")
    hafen.event():on("OnUpdate", hog)
  else
    hafen.log():write("hogtest dormant -- set arm=true in savedata/account/hogtest.json then :reload to test the soft-budget watchdog")
  end
end)
