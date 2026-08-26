-- 109.1 — one proved base per session, and the verb that reads it. Self-checking suite.
-- Type :t109 in the world. The addon needs `console.run` enabled and approved.
--
-- Everything below is read back out of the character's own System log, because that is where
-- `:session where` puts its answer: Sessions.say queues a line and the next frame delivers it to the
-- anchor's notice, which GameUI logs. So the run is one step and the scoring is a timer later.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local WAIT = 1.5            -- the say is queued and drained by the next tick; this is many of those
local GRID = 100            -- tiles to a grid, which is what "the base is grid-aligned" means

-- The System log of one character: the channel `:session` lines and console refusals both land in.
local function syslog(s)
  return s:chat():find(function(ch) return ch:kind() == "chat.system" end)
end

-- Every System line the run left, oldest first and deduped. A console refusal is printed AND shown as
-- a notice, and the client logs both, so one refusal leaves two identical lines -- count nothing.
local function since(sys, from)
  local out, seen = {}, {}
  for i = from, sys:message():count() do
    local m = sys:message():get(i)
    local t = m and m:text()
    if t and not seen[t] then
      seen[t] = true
      out[#out + 1] = t
    end
  end
  return out
end

-- The `:session where` line for one account, with the "session: " Sessions.say puts in front taken off.
local function lineFor(lines, user)
  local head = "session: " .. user .. ": "
  for _, l in ipairs(lines) do
    if l:sub(1, #head) == head then
      return l:sub(#head + 1)
    end
  end
  return nil
end

-- Does this text say a place? Both shapes the client prints -- a base's whole tiles "(1200, -700)" and
-- a character's fractional "(145.0, 312.5)" -- open the same way.
local function saysplace(text)
  return text:find("%(%-?%d") ~= nil
end

local st = {}

local function score()
  local lines = since(st.sys, st.before)
  local sessions = hafen.session():list()
  local cur = hafen.session():current()

  -- 1. one line per session the client holds.
  local missing, tails = {}, {}
  for _, s in ipairs(sessions) do
    local tail = lineFor(lines, s:user())
    if tail then
      tails[s:user()] = tail
    else
      missing[#missing + 1] = s:user()
    end
  end
  check((#sessions > 0) and (#missing == 0),
        ("`session where` reports one line for each of the %d sessions the client holds"):format(#sessions),
        (#sessions == 0) and "the client holds no session" or ("nothing for " .. table.concat(missing, ", ")))

  -- 2. every character that is in the world reports a base: a segment id, a tile coord, and proved.
  local inworld, unproved, bad = 0, {}, {}
  for _, s in ipairs(sessions) do
    local tail = tails[s:user()]
    if tail and s:character() then
      inworld = inworld + 1
      local seg, x, y = tail:match("^base (%x+) tile %((%-?%d+), (%-?%d+)%), proved")
      if seg then
        if (tonumber(x) % GRID ~= 0) or (tonumber(y) % GRID ~= 0) then
          bad[#bad + 1] = s:user() .. " tile (" .. x .. ", " .. y .. ")"
        end
      else
        unproved[#unproved + 1] = s:user() .. " -> " .. tail
      end
    end
  end
  check((inworld > 0) and (#unproved == 0),
        ("each of the %d characters in the world reports a proved base, with a segment and a tile coord")
          :format(inworld),
        (inworld == 0) and "no character is in the world -- run :t109 in the world"
          or table.concat(unproved, " | "))
  check(#bad == 0, "every base reported is grid-aligned -- a base, not a character's position",
        table.concat(bad, " | "))

  -- 3. the anchor's OWN base is among them. The tick's member loop skips the session holding the
  --    screen, and the anchor's base is one half of every difference between two frames -- so this is
  --    the check that the base pass runs ahead of that skip rather than inside it.
  local anchortail = cur and tails[cur:user()]
  check((anchortail ~= nil) and (anchortail:match("^base %x+ tile") ~= nil),
        "the session holding the screen reports a proved base of its own",
        (cur == nil) and "no session holds the screen" or (anchortail or "no line at all"))
  check((anchortail ~= nil) and (anchortail:find(", the anchor", 1, true) ~= nil),
        "and its own line says it is the anchor rather than measuring itself against itself",
        anchortail or "no line at all")

  -- 4. the shape rule, over every line the run reached: a place is said through a PROVED base or not
  --    at all. A session that cannot be proved is named as such and carries no coordinate.
  local leaked, refused = {}, 0
  for _, s in ipairs(sessions) do
    local tail = tails[s:user()]
    if tail then
      local why = tail:match("^no proved base %-%- (.+)$")
      if why then
        refused = refused + 1
        if saysplace(tail) then
          leaked[#leaked + 1] = s:user() .. " -> " .. tail
        end
      elseif not tail:match("^base %x+ tile %(%-?%d+, %-?%d+%), proved") then
        leaked[#leaked + 1] = s:user() .. " -> " .. tail
      end
    end
  end
  check(#leaked == 0,
        ("no line says a place through a base it does not call proved (%d of %d refused a base)")
          :format(refused, #sessions),
        table.concat(leaked, " | "))

  -- 5. the subcommand is in the usage line, which is what a maintainer who mistypes reads.
  local usage = nil
  for _, l in ipairs(lines) do
    if l:find("usage: session ", 1, true) then
      usage = l
    end
  end
  check((usage ~= nil) and (usage:find("|where|", 1, true) ~= nil),
        "the console's own usage line for `session` lists the `where` subcommand",
        usage or "no usage line was printed for `session zzz`")

  manualCheck("walk one character underground (a cave) and run :t109 again, reading its own line",
              "the line either names a different segment from the anchor's, or says it has no proved"
              .. " base and carries NO coordinate at all -- never a base in the anchor's own segment")
  report()
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t109 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t109 in the world")
    return report()
  end
  local sys = syslog(s)
  if not sys then
    check(false, "the character's System log is readable", "no System channel -- is the HUD up?")
    return report()
  end
  st = { sys = sys, before = sys:message():count() + 1 }
  -- Off the console's own tree and onto the step: the lines below reach every session the client
  -- holds, and the step is the one place that holds no tree monitor.
  hafen.timer():after(0, function()
    local ok, err = pcall(function()
      s:console():run("session where")
      s:console():run("session zzz")     -- the refusal, for the usage line
    end)
    if not ok then
      check(false, "the suite may run a console line", tostring(err))
      return report()
    end
    hafen.timer():after(WAIT, score)
  end)
end

hafen.console():on("t109", run)   -- the only way in: a suite does not start itself
