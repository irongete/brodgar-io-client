-- 131.3 — a colour row holds a group, and the API reads and drives it. Self-checking suite, and 131's own
-- integration check: it duplicates rather than defers, so this one command is the whole verification of
-- the feature even if nothing else is ever run again.
--
-- What it cannot check is the fault itself. 131 fixes a DRAW — an exception on the UI thread, thrown by
-- the client's own code and by the server's published code — and a suite that could observe that is a
-- suite running after the client is gone. So the crash is proved headlessly and in-game (see tasks.md),
-- and what is asserted here is the surface the three tasks ship:
--
--   * 131.2  a dropdown carrying "0" .. "254", and a real number still refused
--   * 131.1  every kin's group read as a number, with a colour exactly below the palette
--   * 131.3  a colour row's group read back, and the two refusals its drive owes
--
-- Nothing here changes a group. The two drive checks are REFUSALS, which raise before the row is touched,
-- so the suite mutates no state of the character's and needs the Kith & Kin window only to look at.

local pass, fail, manual = 0, 0, 0
local S = {}
local STEP  = 0.5    -- seconds per tick while waiting for the window
local MWAIT = 240    -- ticks (~2 min) the [manual] step is given before the run scores without it

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

-- The message a refusal carried, without the chunk prefix LuaJ puts in front of it.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(err, needle)
  return tostring(err):find(needle, 1, true) ~= nil
end

local function finish()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- 131.2 — a row of digits is a row of strings, and a real number is not a row.
local function rowChecks()
  local numbers = {}
  for n = 0, 254 do numbers[n + 1] = tostring(n) end

  local box
  local built, failure = pcall(function() box = hafen.ui():dropdown():size(64):rows(numbers) end)
  check(built, "a dropdown takes a row for every group the server accepts, \"0\" .. \"254\"", why(failure))
  if not built then return end

  local picked, pfailure = pcall(function() box:value("254") end)
  check(picked and (box:value() == "254"), "...and hands the picked row back unchanged",
        picked and box:value() or why(pfailure))

  local took, nfailure = pcall(function() box:rows({0, 1, 2}) end)
  check((not took) and says(nfailure, "tostring"), "a row that is a real number is refused, naming tostring",
        took and "<no error>" or why(nfailure))

  pcall(function() box:destroy() end)
end

-- 131.1 — the addon-facing half: the number always, the colour only where there is one.
local function kinChecks(session)
  local wrong, seen = nil, 0
  for _, kin in ipairs(session:kin():list()) do
    seen = seen + 1
    local group, colour = kin:group(), kin:color()
    if type(group) ~= "number" then
      wrong = kin:name() .. "'s group is a " .. type(group)
    elseif (group >= 0) and (group < 8) and (colour == nil) then
      wrong = kin:name() .. " is in group " .. group .. " with no colour"
    elseif (group >= 8) and (colour ~= nil) then
      wrong = kin:name() .. " is in group " .. group .. " and has a colour"
    end
  end
  check(wrong == nil, "every kin's group is a number, with a colour exactly below the palette ("
        .. seen .. " kin)", wrong)
end

-- 131.3 — the colour row itself: what it shows, and what a drive will not take.
local function colourRowChecks(row)
  local group = row:value()
  check((group == nil) or ((type(group) == "number") and (group >= 0) and (group <= 254)),
        "a colour row answers the group it is showing, or nil for none", tostring(group))

  local drove, failure = pcall(function() row:value(255) end)
  check((not drove) and says(failure, "0..254"), "...and refuses a group the server would not take",
        drove and "<no error>" or why(failure))

  drove, failure = pcall(function() row:value("3") end)
  check((not drove) and says(failure, "must be a number"), "...and refuses a string where the group goes",
        drove and "<no error>" or why(failure))
end

local function colourRow(session)
  return session:ui():matchAll("@GroupSelector")[1]
end

local function tick()
  S.waited = S.waited + 1
  local row = colourRow(S.session)
  if row then
    S.timer:cancel()
    colourRowChecks(row)
    finish()
  elseif S.waited >= MWAIT then
    S.timer:cancel()
    check(false, "a colour row came on screen to read",
          "<none in " .. math.floor(MWAIT * STEP) .. "s>")
    finish()
  end
end

local function start()
  S.session = hafen.session():current()
  if S.session == nil then
    check(false, "a character is logged in", "<no session>")
    finish()
    return
  end
  rowChecks()
  kinChecks(S.session)

  local row = colourRow(S.session)
  if row then
    colourRowChecks(row)
    finish()
    return
  end
  manualCheck("show the Village tab of the Kith & Kin window", "the three colour-row lines below")
  S.waited = 0
  S.timer = hafen.timer():every(STEP, tick)
end

local function run()
  if S.timer then pcall(function() S.timer:cancel() end) end
  pass, fail, manual = 0, 0, 0        -- a second :t131 scores its own run, not both
  S = {}
  hafen.timer():after(0, start)       -- the step, where a widget of the layer's tree may be built
end

hafen.console():on("t131", run)   -- the only way in: a suite does not start itself
