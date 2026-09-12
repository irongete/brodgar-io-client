-- 141.1 — an addon out of date is not loaded. Self-checking suite.
--
-- This manifest declares "api_version": "1.0", so this file running at all is the one state a program can
-- observe from inside: a current declaration loads. Every other state — out of date by generation, by
-- edition, by absence, and the shape error — is one no addon can cause (no verb reads another addon's
-- state, and nothing can put a folder beside itself), so the maintainer causes each by editing THIS
-- suite's own manifest, in the copy the client reads, and :reload-ing; the row, its tooltip and :addons are
-- what each line asks them to read.

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

local MF = "bin/addons/141-api-version.1/manifest.json"

local function run()
  check((ADDON ~= nil) and (ADDON.id == "141-api-version.1"),
        'a manifest declaring "api_version": "1.0" loads and runs on this client', ADDON and ADDON.id)
  manualCheck('in ' .. MF .. ' set "api_version": "9.0", :reload, open Options > AddOns',
              'the row reads `outdated (API 9.0, client 1.0)`, its checkbox still ticked')
  manualCheck('hover that row',
              'the tip opens `Out of date: written for API 9.0, this client implements 1.0`')
  manualCheck(':addons', '`141-api-version.1 [outdated]`')
  manualCheck('set "1.3", :reload', 'the row reads `outdated (API 1.3, client 1.0)`')
  manualCheck('hover that row',
              'the tip opens `Out of date: too new: needs API 1.3 or newer, this client implements 1.0`')
  manualCheck('remove the api_version line, :reload', 'the row reads `outdated (no api_version, client 1.0)`')
  manualCheck('set "api_version": 1 (a number, no quotes), :reload', 'the row reads `manifest error (hover)`')
  manualCheck('hover that row', 'the tip names the `"X.Y"` form and `"1.0"`')
  manualCheck('set "api_version": "1.0" back, :reload', 'the row reads `loaded v1.0.0`')
  manualCheck(':addons', 'no `[outdated]` anywhere in the list -- every addon of the maintainer\'s is current')
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t141", run)   -- the only way in: a suite does not start itself
