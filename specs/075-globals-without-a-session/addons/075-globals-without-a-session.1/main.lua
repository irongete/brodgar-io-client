-- 075.1 — the global namespaces stop asking for a session. Self-checking suite.
--
-- What it proves: a sound goes to the addon layer's own channel, so it is audible whichever session
-- is drawn; hafen.time() answers from any live session and every one of its verbs refuses a write
-- arity; hafen.log():write posts and hands the section back; and the console command this file
-- registers is reachable from the console it is typed into, whichever session that is.

local pass, fail, manual = 0, 0, 0

-- Every line of output goes through the verb under test. log():write hands the section back — that
-- is what makes a run of lines chain — so writing the report IS the chaining check, and the verdict
-- on it costs no extra line.
local log = hafen.log()
local chains = true

local function say(line)
  if log:write(line) ~= log then chains = false end
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

local selfcmd   -- the handle register() hands back, asserted on below

local function run()
  -- ---- hafen.sound: it plays, and this blip is the one the manual line listens for -------------
  local blip = hafen.sound():get("sfx/msg")
  blip:play()
  check(blip:playing(), "a played sound reports itself sounding", blip:playing())

  local live = hafen.sound():list()
  local listed = false
  for i = 1, #live do
    if live[i] == blip then listed = true end
  end
  check(listed, "hafen.sound():list() carries what this addon has in the air", #live)

  local quiet = hafen.sound():get("sfx/error")
  check(quiet:play():stop() == quiet, "play and stop chain, each handing the Sound back")
  check(not quiet:playing(), "a stopped sound is not sounding", quiet:playing())

  -- ---- hafen.time: any live session answers, and every verb is a read --------------------------
  local clock = hafen.time():clock()
  check(type(clock) == "number", "hafen.time():clock() answers a number", clock)
  refuses("hafen.time():clock(1) is refused, naming the verb a read",
          function() hafen.time():clock(1) end, "it is a READ of the game clock")
  refuses("the same refusal guards the astronomy readers",
          function() hafen.time():isNight(true) end, "it is a READ of the game clock")

  -- ---- hafen.log: it writes, it chains, and it refuses a nil -----------------------------------
  check(chains, "hafen.log():write hands the section back, so lines chain", chains)
  refuses("hafen.log():write(nil) is refused rather than printing the word",
          function() hafen.log():write(nil) end, "msg must not be nil")

  -- ---- hafen.slash: the command that is running is the proof it registered ---------------------
  check((type(selfcmd) == "table") and (type(selfcmd.remove) == "function"),
        "hafen.slash():register hands back a handle that can unregister", type(selfcmd))
  refuses("a reserved engine command name is refused",
          function() hafen.slash():register("reload", function() end) end,
          "reserved engine command")

  -- ---- what a program cannot observe ------------------------------------------------------------
  manualCheck("with two sessions up, tab to the OTHER one and re-run this command",
              "the blip at the top of the run is HEARD, at the same volume as from this session")
  manualCheck("type :t075-1 in the second session's own chat", "it runs, and prints this same block")

  say(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

selfcmd = hafen.slash():register("t075-1", run)   -- the only way in: a suite does not start itself
