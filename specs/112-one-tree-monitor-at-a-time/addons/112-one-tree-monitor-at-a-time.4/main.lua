-- 112.4 — the session's own step leaves its tree's monitor, the way the layer's did.
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
local SEL = "[name=112-one-tree-monitor-at-a-time.4/probe]"
local SEL_X, SEL_Y = 41, 42      -- where the selector Removed handler writes its session-tree target...
local SUB_X, SUB_Y = 61, 62      -- ...and where widget:on("Removed") writes its own
local KILL_AT = 3                -- the frame the probe is destroyed on, from an Update handler
local WINDOW = 9.0               -- the bounded window the two off-thread drains are waited for
local NOHTTP = "no done callback in " .. WINDOW .. "s -- was http.get approved when the suite was enabled?"

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local st = {}

-- What every one of the four handlers under test does: say where it is running, then reach the OTHER
-- tree (a window is the layer's) and this session's own. Neither is legal from a handler holding a tree
-- monitor, and every one of these drains held one until this task.
local function reach(tag, target, x, y)
  st[tag .. "Step"] = hafen.client():stepping()
  local ok, err = pcall(function()
    st[tag .. "Win"] = hafen.ui():window():title("112.4 " .. tag):size(140, 34):position(40, 40)
  end)
  st[tag .. "BuildOk"], st[tag .. "BuildErr"] = ok, why(ok, err)
  if target == nil then
    return
  end
  local ok2, err2 = pcall(function() target:position(x, y) end)
  st[tag .. "WriteOk"], st[tag .. "WriteErr"] = ok2, why(ok2, err2)
end

local function finish()
  st.updSub:off()
  st.selSub:off()
  st.subSub:off()
  st.drawSub:off()
  st.msgSub:off()
  local selRead, subRead = st.target:position(), st.target2:position()

  check(st.drawStep == false, "a Draw handler does not run on the client's step", st.drawStep)

  check(st.selStep == true, "a selector Removed subscription fires on the step",
        st.selRan and st.selStep or "no Removed")
  check(st.selBuildOk == true, "...and a window is built from that handler", st.selBuildErr)
  check(st.selWriteOk and near(selRead, SEL_X, SEL_Y),
        "...and a widget of that session is written from the same handler",
        st.selWriteOk and place(selRead) or st.selWriteErr)

  check(st.subStep == true, "a widget's own Removed subscription fires on the step too",
        st.subRan and st.subStep or "no Removed")
  check(st.subBuildOk and st.subWriteOk and near(subRead, SUB_X, SUB_Y),
        "...and a window and a session-tree write both land in it",
        st.subRan and (tostring(st.subBuildErr) .. " / " .. tostring(st.subWriteErr)
                       .. " at " .. place(subRead)) or "no Removed")

  check(st.selRan and st.killFrame and ((st.selFrame - st.killFrame) <= 1),
        "the Removed edge arrives in the frame the destroy did, not later",
        st.selRan and ("destroyed on frame " .. tostring(st.killFrame) .. ", reported on "
                       .. tostring(st.selFrame)) or "no Removed")

  local nohttp = st.sendOk and NOHTTP or st.sendErr
  check(st.httpStep == true, "an HTTP done callback runs on the step",
        st.httpRan and st.httpStep or nohttp)
  check(st.httpBuildOk == true, "...and reaches the other tree from there",
        st.httpRan and st.httpBuildErr or nohttp)

  check(st.msgStep == true, "MessageAdded runs on the step", st.msgRan and st.msgStep or "no line")
  check(st.msgBuildOk == true, "...and reaches the other tree from there",
        st.msgRan and st.msgBuildErr or "no line")

  st.target:destroy()
  st.target2:destroy()
  for _, k in ipairs({"selWin", "subWin", "httpWin", "msgWin"}) do
    if st[k] then st[k]:destroy() end
  end
  report()
end

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end

  -- The frame counter the Removed edge is timed against: the bus Update is the LAYER's step, which runs
  -- earlier in the very same frame as the session step that drains the removal.
  st.frames = 0
  st.updSub = hafen.event():on("Update", function()
    st.frames = st.frames + 1
    if (st.frames >= KILL_AT) and not st.killed then
      st.killed = true
      st.killFrame = st.frames
      st.probe:destroy()          -- from an Update handler: no tree monitor held, so the destroy is free
    end
  end)

  st.selSub = s:ui():on(SEL, "Removed", function()
    if st.selRan then return end
    st.selRan = true
    st.selFrame = st.frames
    reach("sel", st.target, SEL_X, SEL_Y)
  end)

  -- The two write targets, and the probe the two Removed subscriptions are about.
  st.target = hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(2, 2)
  st.target2 = hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(3, 3)
  st.probe = hafen.ui():widget():parent(s:ui():root()):name("probe"):size(4, 4):position(1, 1)

  st.subSub = st.probe:on("Removed", function()
    if st.subRan then return end
    st.subRan = true
    reach("sub", st.target2, SUB_X, SUB_Y)
  end)

  -- The control that says the flag can read false: a draw runs under its own tree's monitor, and is the
  -- family this feature deliberately leaves there.
  st.drawSub = st.target:on("Draw", function()
    if st.drawStep == nil then st.drawStep = hafen.client():stepping() end
  end)

  -- The second per-session drain an addon meets: an HTTP completion. The address is refused on the pool
  -- thread (a loopback one always is), which is not what is under test -- the callback landing on the
  -- step, with no tree monitor held, is.
  st.msgSub = hafen.event():on("MessageAdded", function()
    if st.msgRan then return end
    st.msgRan = true
    reach("msg", nil)
  end)
  local sendOk, sendErr = pcall(function()
    -- The request is held, never chained through :on -- that verb hands back a Sub, like every other
    -- subscription in the API, and a Sub has nothing to send.
    local req = hafen.http():get("http://127.0.0.1/112-4-probe")
    st.httpSub = req:on("done", function()
        if st.httpRan then return end
        st.httpRan = true
        reach("http", nil)
      end)
    req:send()
  end)
  st.sendOk, st.sendErr = sendOk, why(sendOk, sendErr)

  -- ...and the third: a line landing in the chat, which the client's own notice is.
  hafen.log():write("112.4: a line, so MessageAdded has one to carry")

  manualCheck("watch the HUD for a few seconds after this run, and move the mouse over a window",
              "it keeps drawing and answering -- a session pump that lost its frame would not")

  hafen.timer():after(WINDOW, finish)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  st = {}
  hafen.timer():after(0, start)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
