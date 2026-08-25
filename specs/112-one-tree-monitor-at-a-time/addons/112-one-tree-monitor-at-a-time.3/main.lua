-- 112.3 — the two seams that ran Lua on a Loader thread queue and drain on the step.
-- Self-checking suite. Run it with :t112 while a character is in the world.

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

-- The message a pcall came back with, with the file:line prefix taken off.
local function why(ok, err)
  if ok then
    return "<no error>"
  end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = why(ok, err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Is a {x=, y=} read the place we wrote? Within a pixel: a design pixel goes to the screen and back
-- through an interface scale that need not be a whole number.
local function near(got, x, y)
  return (got ~= nil) and (math.abs(got.x - x) <= 1) and (math.abs(got.y - y) <= 1)
end

local function place(p)
  return (p == nil) and "<nowhere>" or (p.x .. "," .. p.y)
end

-- The one refiner an addon owns, so this reaches THIS suite's widget and no other addon's.
local SEL = "[name=112-one-tree-monitor-at-a-time.3/probe]"
local ADD_X, ADD_Y = 31, 32      -- where the entry seam's handler writes its session-tree target...
local CHG_X, CHG_Y = 51, 52      -- ...and where the item seam's handler writes its own
local KILL   = 1.2               -- the probe is destroyed here, well after its Added has landed
local WINDOW = 13.0              -- the bounded window a real item icon is waited for
local NOITEM = "no fresh item icon arrived in " .. WINDOW .. "s -- was a container opened?"

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local st = {}

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end

  -- A timer callback IS the step, so this is the control that says the flag can read true...
  st.timerStep = hafen.client():stepping()
  st.order = {}

  -- The registration scan fires Added inline for what already matches, on whatever thread called :on.
  -- Those are not the seam under test, so they are skipped rather than scored.
  st.scanning = true

  st.addedSub = s:ui():on(SEL, "Added", function(w)
    if st.scanning or st.addedRan then return end
    st.addedRan = true
    st.order[#st.order + 1] = "Added"
    st.addedStep = hafen.client():stepping()
    st.addedExists = w:exists()
    -- THE PAIR THE DUMPS CAUGHT: a window into the LAYER, then a write into the SESSION's tree, out of
    -- one handler. Each takes one tree's monitor, and this handler arrives holding neither.
    local ok, err = pcall(function()
      st.addedWin = hafen.ui():window():title("112.3 added"):size(130, 34):position(50, 50)
    end)
    st.buildOk, st.buildErr = ok, why(ok, err)
    local ok2, err2 = pcall(function() st.target:position(ADD_X, ADD_Y) end)
    st.writeOk, st.writeErr = ok2, why(ok2, err2)
  end)

  st.removedSub = s:ui():on(SEL, "Removed", function(w)
    st.order[#st.order + 1] = "Removed"
  end)

  st.iconSub = s:ui():on("item", "Added", function(icon)
    if st.scanning or st.iconRan then return end
    st.iconRan = true
    st.iconStep = hafen.client():stepping()
    local ok, err = pcall(function()
      st.iconWin = hafen.ui():window():title("112.3 item"):size(130, 34):position(50, 100)
    end)
    st.iconBuildOk, st.iconBuildErr = ok, why(ok, err)
    local item = icon:item()
    if item == nil then
      st.changedMiss = "the icon handed over had no item"
      return
    end
    -- The entry drain runs BEFORE the item drain inside one step, so a subscription made right here
    -- still catches this item's very first Changed.
    st.changedSub = item:on("Changed", function()
      if st.changedRan then return end
      st.changedRan = true
      st.changedStep = hafen.client():stepping()
      local ok2, err2 = pcall(function()
        st.changedWin = hafen.ui():window():title("112.3 changed"):size(130, 34):position(50, 150)
      end)
      local ok3, err3 = pcall(function() st.target2:position(CHG_X, CHG_Y) end)
      st.changedBuildOk, st.changedBuildErr = ok2, why(ok2, err2)
      st.changedWriteOk, st.changedWriteErr = ok3, why(ok3, err3)
    end)
  end)

  st.scanning = false

  -- The widget the two subscriptions above are about, and one write target per seam under test.
  st.probe = hafen.ui():widget():parent(s:ui():root()):name("probe"):size(4, 4):position(1, 1)
  st.target = hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(2, 2)
  st.target2 = hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(3, 3)
  -- ...and the control that says the flag can read false: a draw runs under its own tree's monitor.
  st.drawSub = st.probe:on("Draw", function()
    if st.drawStep == nil then st.drawStep = hafen.client():stepping() end
  end)

  refuses("hafen.client():stepping() takes no argument",
          function() hafen.client():stepping(true) end, "takes no arguments")

  manualCheck("open one container and close it again, within " .. WINDOW .. "s of running :t112",
              "the four item lines score; nothing else needs you")

  hafen.timer():after(KILL, function() st.probe:destroy() end)
  hafen.timer():after(WINDOW, function()
    st.addedSub:off()
    st.removedSub:off()
    st.iconSub:off()
    st.drawSub:off()
    if st.changedSub then st.changedSub:off() end
    local addRead, chgRead = st.target:position(), st.target2:position()

    check(st.timerStep == true, "a timer callback runs on the client's step", st.timerStep)
    check(st.drawStep == false, "a Draw handler does not", st.drawStep)

    check(st.addedStep == true, "the widget-entry seam fires on the step, not on the placing thread",
          st.addedRan and st.addedStep or "no Added")
    check(st.addedExists == true, "the widget handed over is in the tree at that instant", st.addedExists)
    check(st.buildOk == true, "a window is built from that handler", st.buildErr)
    check(st.writeOk and near(addRead, ADD_X, ADD_Y),
          "...and a widget of that session is written from the same handler",
          st.writeOk and place(addRead) or st.writeErr)
    check((#st.order == 2) and (st.order[1] == "Added") and (st.order[2] == "Removed"),
          "Added, then Removed, for one widget", table.concat(st.order, ","))

    check(st.iconStep == true, "an item icon's Added fires on the step",
          st.iconRan and st.iconStep or NOITEM)
    check(st.iconBuildOk == true, "a window is built from that handler too",
          st.iconBuildErr or NOITEM)
    check(st.changedStep == true, "an item's Changed fires on the step",
          st.changedRan and st.changedStep or (st.changedMiss or NOITEM))
    check(st.changedBuildOk and st.changedWriteOk and near(chgRead, CHG_X, CHG_Y),
          "...and a window and a cross-tree write both land in it",
          st.changedRan and (st.changedBuildErr .. " / " .. st.changedWriteErr
                             .. " at " .. place(chgRead))
            or (st.changedMiss or NOITEM))

    st.target:destroy()
    st.target2:destroy()
    if st.addedWin then st.addedWin:destroy() end
    if st.iconWin then st.iconWin:destroy() end
    if st.changedWin then st.changedWin:destroy() end
    report()
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  st = {}
  hafen.timer():after(0, start)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
