-- 168.1 — bundles: the Bundles chip, an install with what it needs, an enable with what it needs. Self-checking
-- suite, run after the steps its [manual] lines name, against the test hub (client-hub-mock.js, port 3732,
-- serving T168 Bundle 1.1.0 of T168 Plain, T168 Perm, T168 Extra and T168 News), on a client without them:
-- the client started with `ant run -Dregistry=http://localhost:3732/addons/api`.

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

-- What the client says of an addon: its status word, or "absent" where no folder of that id was found.
local function status(id)
  local addon = hafen.client():addons():get(id)
  if not addon:exists() then
    return "absent"
  end
  return addon:info().status
end

local function run()
  pass, fail, manual = 0, 0, 0
  manualCheck("AddOns > Browse with the test hub running, then the Bundles chip",
    "T168 Bundle's card alone, reading 'Bundle · 4 addons' and no version; All brings back all five")
  manualCheck("Bundles again, then a press on T168 Bundle's card away from Install",
    "its page: Includes with the cards of T168 Plain, T168 Perm, T168 Extra and T168 News, in that order")
  manualCheck("a press on T168 Plain's card there",
    "T168 Plain's page with 'Back to T168 Bundle', and About 'part of' with a T168 Bundle chip")
  manualCheck("Back to T168 Bundle, then Back to list",
    "the bundle's page at once (no 'Loading the page…'), then the list with the Bundles chip still on")
  manualCheck("Install on T168 Bundle's card",
    "a window: it installs the four at 1.0.0, T168 Perm, T168 Extra and T168 News asking for permissions")
  manualCheck("Install in that window",
    "one dialog, 'Enable T168 Bundle?', listing the three with their permissions and T168 Plain and T168 Bundle"
      .. " as asking for nothing; answered Enable")
  manualCheck("Reload UI, then :t168", "the lines below pass")
  for _, id in ipairs({ "t168-plain", "t168-perm", "t168-extra", "t168-news", "t168-bundle" }) do
    local word = status(id)
    check(word == "loaded", id .. " is installed and loaded", word)
  end
  manualCheck("on Installed, untick T168 Plain, T168 Perm and T168 Bundle, Reload UI, then tick T168 Bundle",
    "T168 Plain and T168 Perm tick with it, with no dialog (T168 Perm's consent is on record); Reload UI and :t168"
      .. " pass again")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The only way in: a suite does not start itself.
hafen.console():on("t168", function() hafen.timer():after(0, run) end)
