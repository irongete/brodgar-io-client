-- 066.4 -- Ctrl and a middle drag pivots the camera. Self-checking suite.
--
-- The gesture is a modifier plus a mouse drag: nothing in Lua can hold Ctrl, press the middle button
-- or see which camera is installed, so almost all of this task is the maintainer's to observe. What a
-- program CAN check is the negative -- that the pivot left no binding behind, because a modifier is
-- not a key the registry can hold -- and that it is running against the client this task changed.

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

-- The ids the Multi session section holds, and the camera key it sits under. If these are absent this
-- is not the client task 066.3 changed, and every [manual] line below is being read against the wrong
-- build -- which is the one thing that would make the maintainer's answers worthless.
local PRESENT = { "rts-next-anchor", "rts-focus", "cam-reset" }
-- The id the pivot would have had. It must NOT exist: the gesture is a modifier, and a modifier is
-- exactly what KeyMatch.Capture refuses, so a registry entry here would be one nothing could ever fill.
local ABSENT = "cam-pivot"

local function run()
  local keys = hafen.client():options():keybindings()
  local list = keys:list()

  local missing = {}
  for _, id in ipairs(PRESENT) do
    if list[id] == nil then missing[#missing + 1] = id end
  end
  check(#missing == 0, "the client answers for the Multi session ids and cam-reset",
        "absent: " .. table.concat(missing, ", "))

  check(list[ABSENT] == nil, "the pivot left no " .. ABSENT .. " binding behind", list[ABSENT])
  check(keys:key(ABSENT) == nil, "and reading it is plain nil, not a key", keys:key(ABSENT))
  refuses("a write to it is refused, since there is no such binding",
          function() keys:key(ABSENT, "F9") end, "no binding named")

  -- The two ids that DO exist still round-trip, so the section this task did not touch still works.
  local found = keys:key("rts-focus")
  keys:key("rts-focus", "F9")
  check((keys:key("rts-focus") == "F9") and (keys:list()["rts-focus"] == "F9"),
        "rts-focus still round-trips a written key (F9)",
        tostring(keys:key("rts-focus")) .. " / " .. tostring(keys:list()["rts-focus"]))
  keys:key("rts-focus", found or "None")
  check(keys:key("rts-focus") == found, "and is restored to what this run found",
        tostring(keys:key("rts-focus")) .. ", found " .. tostring(found))

  manualCheck("Options > Keybindings > Multi session",
              "two rows -- Next character and Focus selection -- and NO pivot row")
  manualCheck(":cam rts, then middle-drag, then Shift and middle-drag",
              "both pan; neither rotates or elevates")
  manualCheck("Ctrl and middle-drag, then release Ctrl and middle-drag again",
              "rotate and elevate with Ctrl held, and the pan back once it is released")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t066-4", run)   -- the only way in: a suite does not start itself
