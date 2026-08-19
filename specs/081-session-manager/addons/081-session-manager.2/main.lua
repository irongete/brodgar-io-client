-- 081.2 — a session can be closed from an addon. Self-checking suite.
--
-- Run it with two accounts logged in (`:session add USER`). It closes the session that is NOT on
-- screen, so the screen never moves and this whole verdict block lands in one place.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The ungranted branch is the one thing this suite cannot reach from inside itself: it declared the
-- key, session.close is alone in its family, and there is no second key to leave undeclared.
local function finish(closed)
  if closed then
    manualCheck("read ':session list'", "the closed account is gone from it, the other one still there")
  end
  manualCheck("delete \"session.close\" from bin/addons/081-session-manager.2/manifest.json,"
              .. " ':reload', and run ':t081-2' again",
              "the FIRST line is a [fail] whose message names the \"session.close\" permission and the"
              .. " manifest line to paste")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The session to close: any live one that is not holding the screen.
local function victim()
  local cur = hafen.session():current()
  for _, s in ipairs(hafen.session():list()) do
    if s ~= cur then return s end
  end
  return nil
end

local WINDOW, STEP = 15, 0.5      -- the bounded window the end is scored over, and the poll interval

local function run()
  local s = victim()
  if s == nil then
    check(false, "a second session is up to close (':session add USER' before running this)",
          hafen.session():count() .. " session(s), all of them on screen")
    return finish(false)
  end
  local user = s:user()

  -- The edge, subscribed BEFORE the close: SessionDestroyed must be handed this very Session.
  local destroyed, sub = nil, nil
  sub = hafen.event():on("SessionDestroyed", function(ended)
    if ended == s then destroyed = ended end
  end)

  -- The write itself. Granted (the manifest declares the key), and it hands the Session back so
  -- writes chain -- both of which are readable right here, before anything has had time to happen.
  local ok, ret = pcall(function() return s:close() end)
  check(ok and (ret == s), "s:close() on the session NOT on screen is granted and hands the Session back",
        ok and tostring(ret) or ret)
  if not ok then                  -- nothing was closed, so there is nothing to wait for
    sub:off()
    return finish(false)
  end

  -- ...and nothing more is true yet: the verb is asynchronous, so the end is polled for a bounded
  -- window and scored over what this run reached.
  local waited, poll = 0, nil
  local function done()
    poll:cancel()
    check(destroyed == s, "SessionDestroyed was handed that same Session", tostring(destroyed))
    check(s:user() == user, "s:user() still answers after the session ended (" .. user .. ")", s:user())
    refuses("closing a session that has ended is refused, naming the account",
            function() s:close() end, user)
    sub:off()
    finish(true)
  end
  poll = hafen.timer():every(STEP, function()
    waited = waited + STEP
    if not s:exists() then
      check(true, ("the login ended -- s:exists() is false %.1fs after the call"):format(waited))
      done()
    elseif waited >= WINDOW then
      check(false, ("the login ended -- s:exists() within %ds"):format(WINDOW),
            ("still exists after %.1fs"):format(waited))
      done()
    end
  end)
end

hafen.slash():register("t081-2", run)   -- the only way in: a suite does not start itself
