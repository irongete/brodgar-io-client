-- 124.3 -- a session's per-character saved variables read back at its OWN SessionEnteredWorld,
-- drawn or not. Self-checking suite: run with :t124.
--
-- What it proves is a claim about a moment, so everything it asserts is gathered INSIDE the handler
-- and scored later. The record is taken at file scope, which a :reload fills before the command can
-- be typed: reload() runs loadAll() before it announces, so every announcement it makes reaches the
-- subscriptions below.

local WINDOW = 30   -- seconds :t124 will wait for a login behind the screen before scoring

local pass, fail, skip = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function skipped(what, why)
  skip = skip + 1
  hafen.log():write("[skip] " .. what .. " -- not reached: " .. why)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- What the client already holds, read before the first announcement can arrive.
local atLoad = { inWorld = 0, screen = nil }
for _, s in ipairs(hafen.session():list()) do
  if s:character() ~= nil then
    atLoad.inWorld = atLoad.inWorld + 1
  end
end
local screen = hafen.session():current()
atLoad.screen = (screen ~= nil) and screen:user() or nil

local tabbed = {}   -- accounts that have taken the screen since this addon loaded
hafen.event():on("SessionSelected", function(s)
  tabbed[s:user()] = true
end)

-- Every announcement for a session that is NOT the one on screen, with the whole of the claim
-- evaluated where it is made.
local seen = {}
hafen.event():on("SessionEnteredWorld", function(s)
  local cur = hafen.session():current()
  if s == cur then
    return
  end
  local e = { user = s:user(), char = s:character(), tabbed = tabbed[s:user()] or false }
  e.ok, e.err = pcall(function()
    local t = s:store():get("probe")
    e.before = t.mark                        -- what that character's own folder held, read in already
    t.mark = e.char
    e.readback = s:store():get("probe").mark -- a fresh ask, the same live table
    e.same = (s:store():get("probe") == t)
    if (cur ~= nil) and (cur:character() ~= nil) then
      e.screen = cur:store():get("probe").mark
      e.screenChar = cur:character()
    end
    s:store():flush()                        -- so a later :reload can prove the read-back
  end)
  seen[#seen + 1] = e
  hafen.log():write("[event] SessionEnteredWorld " .. e.user .. ", playing " .. tostring(e.char)
                    .. ", which is not the session on screen")
end)

local function scoreReached()
  local bad, got

  bad = nil
  for _, e in ipairs(seen) do
    if bad == nil then
      if not e.ok then
        bad, got = e, e.err
      elseif e.char == nil then      -- the HUD is up, so the character is named: a nil would pass the
        bad, got = e, "no character"  --   three checks below without proving anything
      end
    end
  end
  check(bad == nil, "a background session's store answers inside its own SessionEnteredWorld ("
        .. #seen .. ")", bad and (bad.user .. ": " .. tostring(got)))

  bad = nil
  for _, e in ipairs(seen) do
    if (bad == nil) and e.ok then
      if not e.same then
        bad, got = e, "a second ask handed back another table"
      elseif (e.screen ~= nil) and (e.screen == e.char) then
        bad, got = e, "the mark reached " .. tostring(e.screenChar) .. " as well"
      end
    end
  end
  check(bad == nil, "the table it answers is that character's own, not the screen's",
        bad and (bad.user .. ": " .. tostring(got)))

  bad = nil
  for _, e in ipairs(seen) do
    if (bad == nil) and e.ok and (e.readback ~= e.char) then bad, got = e, e.readback end
  end
  check(bad == nil, "a write into it inside the handler reads back",
        bad and (bad.user .. ": " .. tostring(got)))

  bad = nil
  for _, e in ipairs(seen) do
    if (bad == nil) and e.ok and (e.before == nil) then bad = e end
  end
  if bad ~= nil then
    skipped("that character's own folder was read in before the handler ran",
            "nothing was saved for " .. tostring(bad.char)
            .. " yet -- it is on disk now, so :reload and :t124 again")
  else
    bad = nil
    for _, e in ipairs(seen) do
      if (bad == nil) and e.ok and (e.before ~= e.char) then bad, got = e, e.before end
    end
    check(bad == nil, "that character's own folder was read in before the handler ran",
          bad and (bad.user .. ": " .. tostring(got)))
  end

  bad = nil
  for _, e in ipairs(seen) do
    if (bad == nil) and e.tabbed then bad = e end
  end
  check(bad == nil, "no tab was needed: it answered without that session ever taking the screen",
        bad and (bad.user .. " had taken the screen first"))
end

local function report()
  if #seen > 0 then
    scoreReached()
  else
    hafen.log():write("[info] nothing announced behind the screen -- log a second character in, or"
                      .. " :reload with two in the world, then :t124")
    local why = "no login behind the screen"
    skipped("a background session's store answers inside its own SessionEnteredWorld", why)
    skipped("the table it answers is that character's own, not the screen's", why)
    skipped("a write into it inside the handler reads back", why)
    skipped("that character's own folder was read in before the handler ran", why)
    skipped("no tab was needed: it answered without that session ever taking the screen", why)
  end

  refuses("a session the client does not hold has no per-character tables to answer with",
          function() hafen.session():get("nobody-124-3"):store():get("probe") end,
          "is not a live session")

  hafen.log():write(("[summary] %d pass, %d fail, %d skip"):format(pass, fail, skip))
end

local waiting = nil

local function run()
  if waiting ~= nil then
    return
  end
  pass, fail, skip = 0, 0, 0
  hafen.log():write("[info] at load: " .. atLoad.inWorld .. " in the world, on screen: "
                    .. tostring(atLoad.screen) .. "; announcements behind the screen: " .. #seen)
  if #seen > 0 then
    report()
    return
  end
  hafen.log():write("[info] nothing behind the screen yet -- waiting up to " .. WINDOW .. "s for one")
  local left = WINDOW
  local t
  t = hafen.timer():every(1, function()
    if waiting == nil then           -- a tick already in flight when the window closed
      return
    end
    left = left - 1
    if (#seen > 0) or (left <= 0) then
      waiting = nil
      t:cancel()
      report()
    end
  end)
  waiting = t
end

hafen.console():on("t124", run)   -- the only way in: a suite does not start itself
