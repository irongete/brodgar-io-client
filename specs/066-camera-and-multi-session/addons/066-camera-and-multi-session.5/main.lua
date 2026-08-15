-- 066.5 -- opts:camera():mode(). Self-checking suite.
--
-- This is the only suite of the feature with a Lua door to a camera, so it carries the whole surface:
-- the read answers what is INSTALLED, the write installs, the pair round-trips, and both refusals
-- name the offender. The names are a fixed enumeration the docs state rather than a list verb, so the
-- suite states them too -- if the client grew or lost one, the first check below is what says so.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Every name :cam takes, and therefore every name mode() may answer.
local NAMES = { follow = true, worse = true, bad = true, ortho = true, rts = true }

local function run()
  local cam = hafen.client():options():camera()

  -- The read, before anything is written: a camera is always installed in game, and its name is one
  -- of the client's own. Whatever it is, it is what this run puts back at the end.
  local found = cam:mode()
  check(type(found) == "string" and NAMES[found] == true,
        "mode() answers a name the client has", found)

  -- The write, and the read back through it. rts is this feature's own camera, so it is the one name
  -- guaranteed present, and installing it is a gesture the maintainer can see happen.
  cam:mode("rts")
  eq("mode(\"rts\") then mode() reads back", cam:mode(), "rts")

  -- A second write, to a name that is not the one in force, so the read is not answering a no-op.
  cam:mode("ortho")
  eq("mode(\"ortho\") then mode() reads back", cam:mode(), "ortho")

  -- The restore, which is also the chaining check: a write answers the subsystem handle, so it is the
  -- very table it was called on and a further verb may follow it on the same line.
  if type(found) == "string" then
    local chained = cam:mode(found)
    check(chained == cam, "a write answers the camera handle, so writes chain", tostring(chained))
    eq("and the camera this run found is back", cam:mode(), found)
  else
    check(false, "a write answers the camera handle, so writes chain",
          "skipped: mode() answered no name to restore")
  end

  -- Two refusals. The unknown name must say what it got AND what the client has -- which is why no
  -- list verb is needed, so the message naming rts is the assertion, not a nicety.
  refuses("an unknown camera is refused, naming what it got",
          function() cam:mode("nosuchcam") end, "nosuchcam")
  refuses("and naming the names that exist",
          function() cam:mode("nosuchcam") end, "rts")
  -- Arity is the verb here, so an explicit nil would silently become a read and leave a write nobody
  -- made. It raises instead.
  refuses("mode(nil) is refused rather than read as mode()",
          function() cam:mode(nil) end, "must not be nil")
  -- The refusal changed nothing: the camera is still the one the restore put back.
  eq("a refused write installed nothing", cam:mode(), found)

  -- The one thing the automated half cannot reach: the mode installs a camera WITHOUT writing the
  -- preference, and mode() reading the installed one is exactly the difference. The dropdown reads the
  -- same camname() this option does, so what it shows is what mode() would answer.
  manualCheck("note the camera in Options > Camera, then :fleet rts on and reopen that panel",
              "the dropdown reading rts -- which is what mode() answers while the mode holds the camera")
  manualCheck(":fleet rts off, then reopen Options > Camera",
              "the camera noted above back, the mode having written no preference of its own")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t066-5", run)   -- the only way in: a suite does not start itself
