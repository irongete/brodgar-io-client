-- 124.2 -- the pages state what a reload does. Self-checking suite.
--
-- THE RECORD IS TAKEN IN THIS FILE BODY, and that is the whole of what makes the reload checks below mean
-- anything. A `:reload` runs loadAll() -- which runs this file -- BEFORE it announces anything, so what is
-- written here is the world as the reload found it: complete before the first announcement arrives, and
-- long before `:t124` can be typed.
--
-- IT DUPLICATES THE RELOAD ASSERTION rather than resting on another suite: what the rewritten pages claim
-- IS that behaviour, so it is this task's own to prove, and a suite is the whole verification of its task.

local pass, fail, manual, unreached = 0, 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A check that could not be reached is not a check that passed: this run never put the client in the
-- state the assertion is about, and saying so is the only honest score for it.
local function notReached(what, why)
  unreached = unreached + 1
  hafen.log():write("[skip] " .. what .. " -- not reached: " .. why)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- ---------------------------------------------------------------- the world as the reload found it

local inWorld, offWorld, onScreen = {}, {}, nil

do
  local cur = hafen.session():current()
  onScreen = cur and cur:user() or nil
  for _, s in ipairs(hafen.session():list()) do
    if s:character() then                  -- nil until its HUD is up, which is the engine's own gate
      inWorld[#inWorld + 1] = s:user()
    else
      offWorld[#offWorld + 1] = s:user()
    end
  end
end

-- Every announcement, in the order it arrives -- and a line as each one lands, which is what the manual
-- below is read off: a reload writes this line once per login in the world, and picking another character
-- writes one more for a session that was already announced.
local announced = {}
hafen.event():on("SessionEnteredWorld", function(s)
  announced[#announced + 1] = s:user()
  hafen.log():write("[event] SessionEnteredWorld: " .. s:user()
    .. " playing " .. (s:character() or "nobody yet"))
end)

-- WHERE THE RELOAD'S OWN BURST ENDS. reload() fires everything it is going to fire before it returns, and
-- no timer of ours runs until the step after it -- so what has arrived by then is the reload's, and what
-- arrives later is a character being picked, which is the manual check rather than this claim.
local burst = nil
hafen.timer():after(0, function() burst = #announced end)

-- ---------------------------------------------------------------- the checks

local function join(list)
  return (#list > 0) and table.concat(list, ", ") or "none"
end

local function tally(list)
  local t = {}
  for _, v in ipairs(list) do t[v] = (t[v] or 0) + 1 end
  return t
end

-- What a reload announces -- the claim every rewritten passage now makes.
local function reloadCheck()
  local seen = {}                                    -- the reload's own announcements, in order
  for i = 1, (burst or #announced) do seen[i] = announced[i] end

  if #inWorld < 2 then
    local why = (#inWorld == 0)
      and "no character was in the world when this suite loaded -- a fresh client start, not a reload"
      or  "only one character was in the world when this suite loaded"
    notReached("every account in the world at the reload was announced", why)
    notReached("the first announcement is the account that was on screen", why)
    notReached("no account was announced twice by the reload", why)
    notReached("no account that was out of the world was announced", why)
    hafen.log():write("[info] log a second character in, run `:reload`, then `:t124` again")
    return
  end

  local heard, world = tally(seen), tally(inWorld)

  local missing = {}
  for _, u in ipairs(inWorld) do
    if not heard[u] then missing[#missing + 1] = u end
  end
  check(#missing == 0, "every account in the world at the reload was announced ("
    .. join(inWorld) .. ")", "never announced: " .. join(missing))

  if onScreen then
    check(seen[1] == onScreen, "the first announcement is the account that was on screen ("
      .. onScreen .. ")", tostring(seen[1]))
  else
    notReached("the first announcement is the account that was on screen",
               "no session held the screen when this suite loaded")
  end

  local twice = {}
  for u, n in pairs(heard) do
    if n > 1 then twice[#twice + 1] = u .. " x" .. n end
  end
  check(#twice == 0, "no account was announced twice by the reload (" .. #seen
    .. " announcements)", join(twice))

  local extra = {}
  for _, u in ipairs(seen) do
    if not world[u] then extra[#extra + 1] = u end
  end
  check(#extra == 0, "no account that was out of the world was announced (" .. #offWorld
    .. " connected without a character)", join(extra))
end

-- The doors the rewritten pages still name: the list is what a subscription made mid-life reads, and the
-- subscription is the one that hears the reload.
local function doorCheck()
  local list, n = hafen.session():list(), hafen.session():count()
  check(#list == n, "hafen.session():list() and :count() agree (" .. n .. ")", #list)

  local cur = hafen.session():current()
  if cur then
    local held = false
    for _, s in ipairs(list) do
      if s == cur then held = true end
    end
    check(held, "hafen.session():current() is one of the sessions :list() holds ("
      .. cur:user() .. ")", "not in the list")
  else
    notReached("hafen.session():current() is one of the sessions :list() holds",
               "the login screen holds the screen, where :current() reads nil")
  end

  local sub = hafen.event():on("SessionEnteredWorld", function() end)
  check((sub ~= nil) and (sub:key() == "SessionEnteredWorld"),
        "hafen.event():on(\"SessionEnteredWorld\", fn) hands back a Sub keyed to it",
        sub and sub:key())

  local before = hafen.event():count("SessionEnteredWorld")
  sub:off()
  local after = hafen.event():count("SessionEnteredWorld")
  check(after == (before - 1), "sub:off() ends it (" .. before .. " on that key, "
    .. (before - 1) .. " after)", after)
end

local function run()
  pass, fail, manual, unreached = 0, 0, 0, 0

  reloadCheck()
  doorCheck()

  manualCheck("with this suite loaded, pick another character on the account you are looking at",
              "one new [event] line naming that account, the second announcement for one session")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual, "
    .. unreached .. " not reached")
end

hafen.console():on("t124", run)   -- the only way in: a suite does not start itself
