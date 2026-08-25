-- 111.1 -- s:console():run(line). Self-checking suite.
--
-- WHAT THIS SHIPS. One verb: a console line run through ONE character's own console, exactly as though
-- the user had typed it there. The section is per-session and interned; the key is `console.run`, which
-- this addon declares -- so what is proved here is the EFFECT. The gate itself cannot be proved by an
-- addon that was granted it, and is verified by reading that requirePermission is the verb's first
-- statement.
--
-- HOW IT IS PROVED. Four claims, and each has a way of being wrong that only its own check can see.
--
--   * THE TWO HALVES MEET. `:on` names a word and `run` says one, and until now nothing said one. So the
--     suite registers `t111probe` and drives it: what is asserted is that the handler THIS addon
--     registered fired, not that some command by that name exists somewhere.
--   * THE CLIENT'S OWN SPLITTER RAN IT. `t111probe one "two three"` has to land as two arguments with the
--     quotes stripped and the pair kept whole. A verb that split the line itself would either reimplement
--     the client's splitter or lose the literal text, and this is what tells the two apart.
--   * THE CONSOLE WAS THE SESSION'S. `gl` is registered on that UI's OWN console -- it is not in the
--     client-wide table -- so `usage: gl SETTING VALUE` is a message a line resolved against a shared
--     registry could not produce at all. `:lo` is the same tier and is deliberately not driven.
--   * A FAILING COMMAND IS NOT THE CALLER'S ERROR. `t111zzz` must not raise: its message goes where the
--     console puts one, that character's System log, in the console's own words with no addon tag.
--
-- The addressing is proven when a second login exists, and only then: one line, two chats, and the wrong
-- answer is visible either way round. With one login there is no second chat to distinguish, so the suite
-- prints the honest [manual] rather than quietly not running the check.

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

-- A refusal is a check: the call must fail, and its message must carry every one of `wants`.
local function refuses(what, fn, wants)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local missing = ok and "<no error>" or nil
  if not ok then
    for _, w in ipairs(wants) do
      if err:find(w, 1, true) == nil then
        missing = "the message does not name '" .. w .. "': " .. err
        break
      end
    end
  end
  check(missing == nil, what, missing)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- ----------------------------------------------------------------------------------------------------
-- The command this suite drives. Registered client-wide, because registration is client-wide -- which is
-- exactly why running one is addressed and registering one is not.
-- ----------------------------------------------------------------------------------------------------

local fired, seen = 0, nil

hafen.console():on("t111probe", function(args)
  fired = fired + 1
  seen = args
end)

-- ----------------------------------------------------------------------------------------------------

-- That character's System log -- where the console puts the message of a command that failed.
local function syslog(s)
  return s:chat():find(function(ch) return ch:kind() == "chat.system" end)
end

-- The newest line in a channel, without the line terminator the console's own writer leaves on it.
local function newest(ch)
  local n = ch:message():count()
  local m = (n > 0) and ch:message():get(n) or nil
  return m and (((m:text() or ""):gsub("%s+$", ""))) or "<no line>"
end

local function body()
  local cur = hafen.session():current()
  if (cur == nil) or (syslog(cur) == nil) then
    check(false, "the character on screen has a System log to read back",
          "run :t111 in the world, on a character whose HUD is up")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  local cons, mine = cur:console(), syslog(cur)

  -- The claim: the two halves of the section meet. It is the handler THIS addon registered that runs.
  fired, seen = 0, nil
  cons:run("t111probe")
  check(fired == 1, "run fires the command this addon registered: the section's two halves meet", fired)

  -- The client's own splitter is what ran the line: quotes stripped, the pair kept whole, 1-based.
  cons:run('t111probe one "two three"')
  check((fired == 2) and (seen ~= nil) and (seen[1] == "one") and (seen[2] == "two three")
        and (seen[3] == nil),
        "the client's own splitter ran the line: one, and two three kept whole",
        seen and (tostring(seen[1]) .. " | " .. tostring(seen[2]) .. " | " .. tostring(seen[3])))

  -- It hands the SECTION back, and the section is interned, so writes chain onto the same object.
  check(cons:run("t111probe") == cur:console(),
        "run hands the section back, and the section is interned", "a different value")

  -- The console was the SESSION'S. `gl` is registered on that UI's own console and is nowhere in the
  -- client-wide table, so this message is one a shared registry could not have produced.
  --
  -- TWO lines, not one, and that is the client's own doing: the console writes a failure to both of its
  -- exits, and once the HUD is up both END in the System log -- `cons.out` is re-pointed at it, and the
  -- on-screen notice is logged there as well. The count is asserted rather than ignored because a delta
  -- of one would mean only one exit fired, which is the regression worth catching.
  local before = mine:message():count()
  cons:run("gl")
  check((mine:message():count() == before + 2) and (newest(mine) == "usage: gl SETTING VALUE"),
        "the line reached that character's own per-instance tier: gl",
        newest(mine) .. " / " .. tostring(mine:message():count() - before) .. " System lines")

  -- The five refusals.
  refuses("no line is refused naming the argument",
          function() cons:run() end, {"line"})
  refuses("a number is refused naming the type",
          function() cons:run(111) end, {"must be a string", "number"})
  refuses("an empty line is refused saying there is no command in it",
          function() cons:run("   ") end, {"there is no command in that line"})
  refuses("a leading colon is refused naming the spelling without it",
          function() cons:run(":reload") end, {'s:console():run("reload")'})
  refuses("hafen.console():run is refused naming where a line is said",
          function() hafen.console():run("t111probe") end, {"s:console():run"})

  -- A command that FAILS is the console's refusal, not the caller's error: it does not raise, and its
  -- message is in the console's own words with no addon tag in front of it.
  before = mine:message():count()
  local ok = pcall(function() cons:run("t111zzz") end)
  check(ok and (mine:message():count() == before + 2) and (newest(mine) == "t111zzz: no such command"),
        "a failing command answers on the console's channel and does not raise",
        (ok and (newest(mine) .. " / " .. tostring(mine:message():count() - before) .. " System lines"))
        or "it raised")

  -- THE ADDRESSING, and it is provable only with a second login: one line, two chats.
  local other = nil
  for _, s in ipairs(hafen.session():list()) do
    if (s ~= cur) and s:exists() and (syslog(s) ~= nil) then other = s end
  end
  if other == nil then
    manualCheck("log a second character in and re-run :t111",
                "one more [pass] -- the addressing check needs two chats to tell apart")
  else
    local theirs = syslog(other)
    local m0, t0 = mine:message():count(), theirs:message():count()
    other:console():run("gl")
    check((theirs:message():count() == t0 + 2) and (newest(theirs) == "usage: gl SETTING VALUE")
          and (mine:message():count() == m0),
          "the line ran at the character it was addressed at, not the drawn one",
          newest(theirs) .. " / the drawn chat moved by "
          .. tostring(mine:message():count() - m0))
  end

  manualCheck("look at the on-screen notices this run raised",
              "error notices, the newest reading: t111zzz: no such command")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- One pcall around the run, so a read that throws becomes one [fail] and a summary rather than a bare
-- stack trace with no verdict under it.
local function run()
  pass, fail, manual = 0, 0, 0     -- :t111 is run more than once in a load; a tally that carried over
  local ok, err = pcall(body)      --   would score the second run as the sum of both

  if not ok then
    check(false, "the run reached its end", (tostring(err):gsub("^.-%.lua:%d+:%s*", "")))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
end

hafen.console():on("t111", run)   -- the only way in: a suite does not start itself
