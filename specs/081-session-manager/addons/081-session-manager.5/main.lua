-- 081.5 — the write says whether it landed. Self-checking suite.
--
-- What is under test is a SHAPE rather than a sentence. hafen.session():current(s) reports nothing when
-- it cannot land -- it does not raise and it fires no event -- so re-reading :current() is the whole of
-- the answer, and an addon that writes without reading back stalls forever on a login that has no screen
-- of its own. The two moments the page names (a login still connecting, and the gap between the two
-- screens of a character switch) are the [manual] half: a program cannot hold a session in either of
-- them. The one no-op a suite CAN produce on demand is writing the session already on screen, and that
-- is the same silence read back the same way.
--
-- Every line is buffered and printed at the END: the in-game half of a log line goes to the character on
-- screen, and the lap below moves the screen, so printing as it runs would split the verdict block
-- across two characters' consoles.

local pass, fail, manual = 0, 0, 0
local lines = {}

local function say(s)
  lines[#lines + 1] = s
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    say("[pass] " .. what)
  else
    fail = fail + 1
    say("[fail] " .. what .. " -- got: " .. tostring(got))
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
  say("[manual] " .. step .. " -- expect: " .. expect)
end

local function name(s)
  return s and s:user() or "the login screen"
end

-- The documented cycle, spelled out here because a suite stands alone: the step is WRITTEN and then READ
-- BACK, so a login that cannot take the screen is walked past instead of pressed against again. It hands
-- back the session it landed on, or nil when nothing in the list would take the screen.
local function cycle()
  local list = hafen.session():list()
  if #list == 0 then return nil end

  local cur, at = hafen.session():current(), 0
  for i, s in ipairs(list) do
    if s == cur then at = i end
  end

  for step = 1, #list do
    local want = list[((at + step - 1) % #list) + 1]
    hafen.session():current(want)
    if hafen.session():current() == want then return want end
  end
  return nil
end

local function run()
  pass, fail, manual, lines = 0, 0, 0, {}   -- a second run reports its own verdict, not two laps of it

  local start = hafen.session():current()
  if start == nil then
    hafen.log():write("[fail] the suite needs a character on screen -- got: the login screen")
    hafen.log():write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The write hands the COLLECTION back, so writes chain. Asserted on the session already on screen, so
  -- the return value is read without the screen having moved anywhere.
  local back = hafen.session():current(start)
  check(back == hafen.session(), "the write hands the collection back", back)
  local chained = pcall(function() hafen.session():current(start):current(start) end)
  check(chained, "two writes chain off one another", "it raised")

  -- The no-op, produced on demand: naming the session already on screen changes nothing, and :current()
  -- re-read still answers that same Session. This is the read an addon uses to learn a write landed.
  check(hafen.session():current() == start,
        "writing the session already on screen leaves :current() where it was", name(hafen.session():current()))

  -- An account the client does not hold is NOT this silence: it raises, naming the account. The two ways
  -- a write can fail to move the screen are told apart by which of them says anything at all.
  refuses("a session the client does not hold raises rather than falling silent",
          function() hafen.session():current(hafen.session():get("nobody-is-here")) end,
          "nobody-is-here")

  -- The lap. A session has a screen to be given once its own widget tree exists, so the reachable ones
  -- are the ones with a :root(); the count of both is reported, so a login still connecting is visible
  -- in the verdict rather than silently shrinking the lap.
  local list = hafen.session():list()
  local reach = {}
  for _, s in ipairs(list) do
    if s:ui():root() then reach[#reach + 1] = s end
  end

  local seen, steps, stalled = {}, 0, false
  for _ = 1, #reach do
    local landed = cycle()
    if landed == nil then stalled = true; break end
    steps = steps + 1
    seen[landed] = (seen[landed] or 0) + 1
  end

  local once = (#reach > 0) and not stalled
  for _, s in ipairs(reach) do
    if seen[s] ~= 1 then once = false end
  end
  check(once, ("a checked lap visits each of the %d reachable session(s) exactly once, of %d held")
              :format(#reach, #list),
        stalled and "the cycle found nothing that would take the screen" or steps)
  check(hafen.session():current() == start, "the lap ends on the session it started on",
        name(hafen.session():current()))

  manualCheck("with two accounts up, :session add a third and press the session-manager key"
              .. " while it is still connecting",
              "the screen does not move, and the console says the session has no screen yet")
  manualCheck("wait for that third login to show a character list, then press the key again",
              "the screen lands on it -- the cycle walked past it before, it did not stall on it")

  for _, l in ipairs(lines) do hafen.log():write(l) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t081-5", run)
