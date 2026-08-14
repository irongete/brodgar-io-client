-- 066.2 — Options ▸ Camera picks the camera. Self-checking suite.
--
-- The camera has no Lua door yet, so what a program can see here is the neighbourhood the new
-- dropdown was added into: the cam-* bindings the panel sits beside, still registered and still
-- readable. The dropdown itself is the maintainer's four lines below.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The two ids named on the panel's own page: the keyboard half of the camera the dropdown installs.
local IDS = { "cam-left", "cam-reset" }

local function run()
  local keys = hafen.client():options():keybindings()
  local all  = keys:list()

  for _, id in ipairs(IDS) do
    check(all[id] ~= nil, id .. " is an id in list()", "absent")
    -- list() and key() are two reads of one registry, so they agree or one of them is lying.
    eq(id .. " reads the same key through key() as through list()", keys:key(id), all[id])
  end

  manualCheck("open Options ▸ Camera",
              "a Camera label with a dropdown above the two inversion checkboxes, listing exactly"
              .. " follow, worse, bad, ortho and rts in that order, and showing the one you are on")
  manualCheck("pick a different camera from that dropdown",
              "the view changes the moment you pick, with no Apply and no restart, and the box"
              .. " shows the name you picked")
  manualCheck("close the Options window and open Options ▸ Camera again",
              "the camera you picked is still the one shown -- the box is read afresh each time it"
              .. " is opened, so :cam typed in between shows up here too")
  manualCheck("restart the client and open Options ▸ Camera",
              "the camera you picked is in force and shown -- the pick wrote the defcam preference,"
              .. " which is what the client comes up on. Put your own camera back the same way")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t066-2", run)   -- the only way in: a suite does not start itself
