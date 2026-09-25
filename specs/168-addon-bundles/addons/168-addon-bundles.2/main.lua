-- 168.2 — bundles: one dialog for their permissions, each addon the player's to turn off, and updates that add
-- addons. Self-checking suite, run after the steps its [manual] lines name, against the test hub
-- (client-hub-mock.js, port 3732, serving T168 Bundle 1.1.0, which adds T168 Extra and T168 News), on a client
-- that has T168 Bundle 1.0.0 with T168 Plain and T168 Perm: the client started with
-- `ant run -Dregistry=http://localhost:3732/addons/api`.

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
  manualCheck("on Installed, untick T168 Plain", "nothing else moves: T168 Bundle stays ticked")
  manualCheck("Check for updates, then Update on T168 Bundle",
    "a window 'Update T168 Bundle?': it installs T168 Extra and T168 News 1.0.0, both asking for permissions")
  manualCheck("Update in that window",
    "one dialog, 'Enable what T168 Bundle 1.1.0 adds?', listing both with their permissions; answered Enable")
  manualCheck("Reload UI, then :t168-2", "the lines below pass")
  local bundle = hafen.client():addons():get("t168-bundle")
  check(status("t168-bundle") == "loaded", "t168-bundle loads with T168 Plain turned off", status("t168-bundle"))
  local version = bundle:exists() and bundle:info().version
  check(version == "1.1.0", "t168-bundle is at 1.1.0, the update's", version)
  check(status("t168-plain") == "disabled", "t168-plain stays as the player left it", status("t168-plain"))
  for _, id in ipairs({ "t168-perm", "t168-extra", "t168-news" }) do
    local word = status(id)
    check(word == "loaded", id .. " is installed and loaded", word)
  end
  manualCheck("on Installed, untick T168 Bundle, then tick it again",
    "T168 Plain ticks with it, with no dialog; nothing else moves")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The only way in: a suite does not start itself.
hafen.console():on("t168-2", function() hafen.timer():after(0, run) end)
