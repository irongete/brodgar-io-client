-- 081.4 — Session Manager. Self-checking suite.
--
-- No search verb reaches the addon layer, so Session Manager's own window cannot be found and read.
-- This suite therefore drives the very API the addon drives -- the cycle, the label rule -- and asserts
-- that the layer is out of reach, which is why it has to.
--
-- Every line is buffered and printed at the END: the in-game half of a log line goes to the character
-- on screen, and the cycle below moves the screen, so printing as it runs would split the verdict
-- block across two characters' consoles.

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
  say("[manual] " .. step .. " -- expect: " .. expect)
end

-- The addon's row label, spelled again: a suite stands alone.
local function label(s)
  return s:character() or s:user()
end

-- The addon's cycle, spelled again. A session that is still connecting has no screen to be given, so
-- the step is checked and the cycle walks past that login instead of stalling on it.
local function cycle()
  local list = hafen.session():list()
  if #list == 0 then return end

  local cur, at = hafen.session():current(), 0
  for i, s in ipairs(list) do
    if s == cur then at = i end
  end

  for step = 1, #list do
    local want = list[((at + step - 1) % #list) + 1]
    hafen.session():current(want)
    if hafen.session():current() == want then return end
  end
end

local function run()
  pass, fail, manual, lines = 0, 0, 0, {}     -- a second run reports its own verdict, not two laps of it

  -- Session Manager itself: its hotkey is the one thing about the addon a program can read back,
  -- since its window stands in the layer. list() uses the full registry ids.
  local keys = hafen.client():options():keybindings()
  check(keys:list()["addon/session-manager/next"] ~= nil,
        "session-manager registered its 'next' hotkey"
        .. " (if not: enable it in Options > AddOns, approve session.close, and :reload)",
        "no such binding")

  -- The lap. A session with no screen of its own cannot be gone to, so the lap is over the ones that
  -- have one; the count is reported either way, so a login still connecting is visible in the verdict.
  local list = hafen.session():list()
  local reach = {}
  for _, s in ipairs(list) do
    if s:ui():root() then reach[#reach + 1] = s end
  end

  local start = hafen.session():current()
  local seen, order = {}, {}
  for _ = 1, #reach do
    cycle()
    local now = hafen.session():current()
    order[#order + 1] = now
    seen[now] = (seen[now] or 0) + 1
  end

  local once = (#reach > 0)
  for _, s in ipairs(reach) do
    if seen[s] ~= 1 then once = false end
  end
  check(once, ("a lap visits each of the %d session(s) exactly once, of %d the client holds")
              :format(#reach, #list), (#reach == 0) and "no session has a screen" or #order)
  check(hafen.session():current() == start, "the lap ends on the session it started on",
        hafen.session():current() and hafen.session():current():user() or "the login screen")

  -- The label rule: the character where there is one, the account before there is.
  local nobody = hafen.session():get("nobody-is-here")
  eq("a label falls back to the account when there is no character", label(nobody), "nobody-is-here")

  local inworld
  for _, s in ipairs(list) do
    if s:character() then inworld = s end
  end
  check(inworld ~= nil and label(inworld) == inworld:character(),
        "a label is the character's name once that session is in the world",
        inworld and label(inworld) or "no session is in the world")

  -- Why this suite drives the API instead of reading the window: the layer is in no session's tree.
  local hidden, where = true, nil
  for _, s in ipairs(list) do
    if s:ui():find("window[title=Sessions]") ~= nil then
      hidden, where = false, s:user()
    end
  end
  check(hidden, "no session's tree can see the Sessions window: it stands in the layer", where)

  -- The boundary the cycle stays inside: it only ever names a session out of :list().
  refuses("the screen refuses a session the client does not hold",
          function() hafen.session():current(hafen.session():get("nobody-is-here")) end,
          "nobody-is-here")

  manualCheck("assign the key in Options > Keybindings > Session Manager, then press it",
              "the screen goes to the next character, and round past the last one")
  manualCheck("press a row's name button", "the screen goes to that character, and its row is the '*' one")
  manualCheck("watch the window while the screen switches", "it stands still: no flicker, no rebuild")
  manualCheck("press a row's X", "that character logs out and its row goes; the others stay")
  manualCheck("drag the window somewhere, quit the client, start it again",
              "the window is where you left it, whichever character you log in first")

  for _, l in ipairs(lines) do hafen.log():write(l) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t081-4", run)
