-- 061.1 — Pressed on a native button. Self-checking suite.
--
-- The target is the close button of the suite's OWN window: a real IButton the client's window chrome
-- builds, so it reads BORROWED even inside a window this addon created — a native control that needs no
-- game window to be open. Two clicks on it are the whole manual part.
--
--   :t061-1

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
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0

  local win = hafen.ui():window():title("061.1"):size(180, 60):position(240, 240)
  local btn = win:find("@IButton")
  check(btn ~= nil, "the window's close button is reachable with win:find(\"@IButton\")")
  if not btn then
    summary()
    return
  end
  eq("the close button is BORROWED inside our own window", btn:info().owned, false)

  refuses("a key a native button does not have still names the ones it does",
          function() btn:on("Changed", function() end) end,
          "Pressed, MouseDown, MouseUp, MouseMove, Wheel, Destroy")
  refuses("Pressed on a native widget that is not a button names what that one has",
          function() btn:parent():on("Pressed", function() end) end,
          "MouseDown, MouseUp, MouseMove, Wheel, Destroy")

  local ran1, ran2 = false, false
  local s1, s2
  local scored = false   -- a cancelled click looks like nothing happened, so it gets clicked again: score ONCE

  s1 = btn:on("Pressed", function(ev)
    ran1 = true
    ev:preventDefault()
  end)

  s2 = btn:on("Pressed", function(ev)
    ran2 = true
    if scored then
      return
    end
    scored = true
    refuses("ev:send(t) is refused, naming resend", function() ev:send({}) end, "resend")
    -- The client's own action runs AFTER this fire returns, so what the cancel did is read a moment later.
    hafen.timer():after(0.4, function()
      check(ran1 and ran2, "both Pressed handlers ran, the cancelling one included")
      check(win:exists(), "the cancel reached the client: the window is still open", win:exists())
      s1:off()
      s2:off()
      btn:on("Pressed", function(ev2)
        ev2:resend()
        check(not win:exists(), "ev:resend() ran the client's own action: the window closed", win:exists())
        refuses("ev:resend() on a widget that has left the tree raises, naming that",
                function() ev2:resend() end, "LEFT THE TREE")
        summary()
      end)
      manualCheck("click the X on the 061.1 window again", "it closes this time")
    end)
  end)

  manualCheck("click the X on the 061.1 window (once: nothing visible happens)", "it stays open")
end

hafen.slash():register("t061-1", run)   -- the only way in: a suite does not start itself
