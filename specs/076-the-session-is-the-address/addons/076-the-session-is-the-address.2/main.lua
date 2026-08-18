-- 076.2 — the four session events carry the session. Self-checking suite.
--
-- What it proves: the four hand a Session object and not an account string; the one a
-- SessionDestroyed carries reports :exists() == false while :user() still names its account; the
-- payload is the very interned ref hafen.session():get(user) hands back; and a misspelt session
-- key is refused naming all four.
--
-- HOW TO RUN IT. The four fire on real session moments, so the suite is a walk of THREE STEPS at
-- your own pace and on no timer: `:t076-2` scores whatever the last step produced and prints the
-- next one. Nothing arrived yet means nothing is scored and the step stands, so running it early
-- costs nothing. `:t076-2 reset` starts the walk again, and so does running it after the summary.

local pass, fail, manual = 0, 0, 0
local log = hafen.log()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log:write("[pass] " .. what)
  else
    fail = fail + 1
    log:write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local KEYS = {"SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionDestroyed"}

-- The gestures. How many times you tab and which console you type in are yours; what each step
-- asserts is the KIND of event the gesture is allowed to produce and the account it must name.
local STEPS = {
  "with ONE session up, :session add USER CHAR (an account from :session users) and wait for that"
    .. " character to be in the world, then :t076-2",
  "tab to the character you just added -- the switcher, an Alt-click or :session anchor USER --"
    .. " then :t076-2 (tabbing back and forth first is fine)",
  ":session drop the account you added, from either character, then :t076-2",
}

-- The arrival that says a step has actually happened. Until it lands the step is not scored at all:
-- SessionEnteredWorld trails its SessionAdded by whole seconds, and running :t076-2 in the gap must
-- read as "not yet" rather than as a missing event.
local AWAIT = {"SessionEnteredWorld", "SessionSelected", "SessionDestroyed"}

local subs, step, origin, added = {}, 0, nil, nil
local seen = {}                    -- arrivals since the last scoring run, already shape-checked

local function disarm()
  for i = 1, #subs do subs[i]:off() end
  subs = {}
end

-- One arrival, shape-checked the moment it lands and remembered for the step that asked for it.
-- Every claim of this task is here: the payload is an OBJECT, it names its account, it is the
-- interned ref the collection hands back for that name, and a destroyed one is readable while dead.
local function arrived(key, p)
  local why = {}
  if type(p) == "string" then
    why[#why + 1] = "a string payload (" .. p .. ")"
  end
  local ok, user = pcall(function() return p:user() end)
  if (not ok) or (type(user) ~= "string") or (user == "") then
    why[#why + 1] = ":user() -- " .. tostring(user)
    user = nil
  end
  if user and (hafen.session():get(user) ~= p) then
    why[#why + 1] = "not the ref get(" .. user .. ") hands back"
  end
  if key == "SessionDestroyed" then
    local eok, ex = pcall(function() return p:exists() end)
    if (not eok) or (ex ~= false) then
      why[#why + 1] = ":exists() is " .. tostring(ex) .. ", wanted false"
    end
  end
  seen[#seen + 1] = {key = key, user = user, why = table.concat(why, "; ")}
end

--- "SessionAdded(bob), SessionEnteredWorld(bob)" -- what a step actually produced.
local function spell(list)
  local out = {}
  for i = 1, #list do
    out[i] = list[i].key .. "(" .. tostring(list[i].user) .. ")"
  end
  return (#out == 0) and "nothing" or table.concat(out, ", ")
end

--- Score one step's arrivals. `kinds` is the set of keys the gesture may produce -- an arrival of
--- any other key is the "and nothing else" half failing. `owed` is what must be there: each entry
--- {key, user, n} demands exactly n arrivals of that key, every one of them naming that account.
local function scoreStep(what, kinds, owed)
  local why = ""
  -- A bad SHAPE is reported before anything else: a payload that cannot name its account misses its
  -- account too, and "a string payload" is the diagnosis where "the arrivals are ..." is a symptom.
  for i = 1, #seen do
    if seen[i].why ~= "" then
      why = seen[i].key .. ": " .. seen[i].why
      break
    end
  end
  if why == "" then
    for i = 1, #seen do
      if not kinds[seen[i].key] then
        why = "a " .. seen[i].key .. " this gesture does not fire"
        break
      end
    end
  end
  if why == "" then
    for i = 1, #owed do
      local key, user, n, found = owed[i][1], owed[i][2], owed[i][3], 0
      for j = 1, #seen do
        if seen[j].key == key then
          found = found + 1
          if user and (seen[j].user ~= user) then
            why = key .. " names " .. tostring(seen[j].user) .. ", wanted " .. user
          end
        end
      end
      if (why == "") and (found ~= n) then
        why = found .. " " .. key .. ", wanted " .. n
      end
      if why ~= "" then break end
    end
  end
  check(why == "", what .. " (" .. spell(seen) .. ")", why)
end

local function arm()
  disarm()
  pass, fail, manual, step, added = 0, 0, 0, 0, nil
  seen = {}

  -- What a Session is, checked before any of them arrives: the payload's type is this one.
  local cur = hafen.session():current()
  origin = cur and cur:user()
  check((cur ~= nil) and (type(cur) ~= "string") and (type(origin) == "string") and (origin ~= ""),
        "a Session is an object that names its account (" .. tostring(origin) .. ")", type(cur))

  local accepted = 0
  for i = 1, #KEYS do
    local key = KEYS[i]
    local ok, sub = pcall(function()
      return hafen.event():on(key, function(p) arrived(key, p) end)
    end)
    if ok then
      accepted = accepted + 1
      subs[#subs + 1] = sub
    end
  end
  check(accepted == #KEYS, "the bus accepts the session keys", accepted .. " of " .. #KEYS)

  refuses("a misspelt session key is refused naming the four",
          function() return hafen.event():on("SessionSelcted", function() end) end,
          "the session family is SessionAdded, SessionEnteredWorld, SessionSelected and"
          .. " SessionDestroyed")
end

local function run(args)
  if (args and (args[1] == "reset")) or (step == 0) or (step > #STEPS) then
    arm()
    step = 1
    log:write("[step 1/3] " .. STEPS[1])
    return
  end

  local ready = false              -- run early: the step stands, and its arrivals keep piling up
  for i = 1, #seen do
    if seen[i].key == AWAIT[step] then ready = true end
  end
  if not ready then
    log:write("[step " .. step .. "/3] still waiting for " .. AWAIT[step] .. " (" .. spell(seen)
              .. " so far) -- " .. STEPS[step])
    return
  end

  if step == 1 then
    -- The account the walk names from here on. A payload that could not name one is the regression
    -- this task exists to catch, so it gets a stand-in rather than throwing the walk off its rails.
    added = seen[1].user or "<unnamed>"
    scoreStep("adding a session announces it, then its entering the world, both naming it",
              {SessionAdded = true, SessionEnteredWorld = true},
              {{"SessionAdded", added, 1}, {"SessionEnteredWorld", added, 1}})
  elseif step == 2 then
    -- Tabbing fires SessionSelected and no other kind, however many times you tab; one of them has
    -- to be the character you were sent to, and the last one is whoever holds the screen now.
    local last, reached = seen[#seen], false
    for i = 1, #seen do
      if seen[i].user == added then reached = true end
    end
    scoreStep("tabbing announces a session taking the screen, and nothing else",
              {SessionSelected = true}, {})
    local cur = hafen.session():current()
    check(reached and (cur ~= nil) and (last.user == cur:user()),
          "one named the character you tabbed to, and the last names the screen ("
          .. tostring(last.user) .. ")",
          tostring(reached) .. " / " .. tostring(cur and cur:user()))
  else
    -- A drop fires that session's SessionDestroyed; if it held the screen, a SessionSelected for
    -- whoever takes over follows it. Nothing else may arrive, and only one session ended.
    scoreStep("dropping announces that session ending, and nothing but the handover with it",
              {SessionSelected = true, SessionDestroyed = true},
              {{"SessionDestroyed", added, 1}})
    -- The other door onto the same claim: the account outlives the session it named.
    local gone = hafen.session():get(added)
    check((gone:user() == added) and (gone:exists() == false),
          "the dropped account still names itself, dead (" .. tostring(added) .. ")",
          gone:exists())
  end

  seen = {}
  step = step + 1
  if step > #STEPS then
    disarm()
    log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  else
    log:write("[step " .. step .. "/3] " .. STEPS[step])
  end
end

hafen.slash():register("t076-2", run)   -- the only way in: a suite does not start itself
