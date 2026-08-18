-- 074.3 — sessions come, are picked, and go, and the addon hears all three. Self-checking suite.
--
-- The four session keys report a GESTURE, and no addon can perform one. So the first run arms the
-- recorder and the next one scores whatever arrived meanwhile: no clock, no window, no waiting. Do the
-- three gestures at whatever pace they take and run it again. The two refusals are checked on every run,
-- because a refusal needs no session at all.

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

-- What arrived, per key, in the order it arrived. It ACCUMULATES from the first run onwards and is never
-- emptied: a run that scores too early is then a run you repeat, not one that threw its evidence away.
local seen = { SessionAdded = {}, SessionEnteredWorld = {}, SessionSelected = {}, SessionDestroyed = {} }
local armed = false

-- At most the first four, then how many more: a run with a dozen tabs in it must stay readable.
local function names(key)
  local t = seen[key]
  if #t == 0 then return "nothing" end
  if #t <= 4 then return table.concat(t, ", ") end
  return table.concat({t[1], t[2], t[3], t[4]}, ", ") .. (", +%d more"):format(#t - 4)
end

-- The invariant behind SessionSelected: it reports a CHANGE, so the same account never lands twice
-- running. Answers the offending name, or nil when the whole run alternates.
local function repeated(t)
  for i = 2, #t do
    if t[i] == t[i - 1] then return t[i] end
  end
  return nil
end

local function arm()
  for key, _ in pairs(seen) do
    local k = key
    hafen.event():on(k, function(user) table.insert(seen[k], tostring(user)) end)
  end
  armed = true
end

-- Report what each key actually carried, against what the three gestures should have caused.
local function score()
  local added, entered = seen.SessionAdded, seen.SessionEnteredWorld
  local picked, gone = seen.SessionSelected, seen.SessionDestroyed
  local who = added[1]

  check(#added == 1,
        "SessionAdded fired once, naming the account (" .. names("SessionAdded") .. ")",
        #added .. ": " .. names("SessionAdded"))
  check((#entered == 1) and (who ~= nil) and (entered[1] == who),
        "SessionEnteredWorld fired for that same account, once (" .. names("SessionEnteredWorld") .. ")",
        #entered .. ": " .. names("SessionEnteredWorld"))
  check(#entered <= 1,
        ("tabbing is not entering: none of the %d screen changes produced a SessionEnteredWorld")
          :format(#picked),
        #entered .. ": " .. names("SessionEnteredWorld"))
  check(#picked >= 2,
        ("SessionSelected fired on every change of screen (%d: %s)"):format(#picked, names("SessionSelected")),
        #picked .. ": " .. names("SessionSelected"))
  check((#picked >= 2) and (repeated(picked) == nil),
        "...and never twice running for one account -- a select is a CHANGE, not a redraw",
        repeated(picked))
  check((#gone == 1) and (who ~= nil) and (gone[1] == who),
        "SessionDestroyed fired naming the account dropped (" .. names("SessionDestroyed") .. ")",
        #gone .. ": " .. names("SessionDestroyed"))
end

local function run()
  pass, fail, manual = 0, 0, 0

  -- The retirement is a check, not a note: the spelling that wrote it throws, and says what replaced it.
  refuses("the retired 'EnterWorld' throws, naming SessionEnteredWorld",
          function() hafen.event():on("EnterWorld", function() end) end, "SessionEnteredWorld")
  refuses("'SessionSelected ' with a trailing space is refused, pointing at the catalogue",
          function() hafen.event():on("SessionSelected ", function() end) end, "catalogue")
  local ok = pcall(function()
    for _, k in ipairs({"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionDestroyed"}) do
      hafen.event():on(k, function() end):off()
    end
  end)
  check(ok, "all four session keys are in the catalogue")

  if not armed then
    arm()
    hafen.log():write("[next] recording. At your own pace: `:session add <a 2nd account>`, let its HUD"
                      .. " come up,")
    hafen.log():write("[next] tab between the two as often as you like, then `:session drop` it."
                      .. " Then run :t074-3 again.")
  else
    score()
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t074-3", run)   -- the only way in: a suite does not start itself
