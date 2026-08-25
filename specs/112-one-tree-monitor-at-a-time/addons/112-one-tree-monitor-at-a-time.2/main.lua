-- 112.2 — a second tree monitor is a refusal, not a wait.
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

-- Is a {x=, y=} read the place we wrote? Within a pixel, because a design pixel is converted to the
-- screen and back and the user's interface scale need not be a whole number.
local function near(got, x, y)
  return (got ~= nil) and (math.abs(got.x - x) <= 1) and (math.abs(got.y - y) <= 1)
end

local function place(p)
  return (p == nil) and "<nowhere>" or (p.x .. "," .. p.y)
end

local function has(s, word)
  return (s ~= nil) and (tostring(s):find(word, 1, true) ~= nil)
end

local BUILT_X, BUILT_Y = 1, 1     -- where the character-tree probe is built...
local DRAW_X,  DRAW_Y  = 11, 12   -- ...where the refused write aims it (and must never move it)
local OWN_X,   OWN_Y   = 44, 45   -- ...where the Draw handler moves its OWN window, which must land
local STEP_X,  STEP_Y  = 21, 22   -- ...and where the SAME write, issued from the step, does move it
local WRAPUP = 0.6                -- the score is read back well after the two frames it needs

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Everything happens from here, and NOT from the console command below: a typed console line runs
-- inside the widget tree of the console it was typed into -- the client's own chat line is a
-- character's tree, an addon's chat window is the layer's -- and this suite builds in both. The
-- engine step holds no tree monitor at all, which is precisely what task 112.1 made true, so a timer
-- is where a program that means to touch two trees starts.
local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end

  -- A surface of our own, in the addon layer: its Draw handler runs under the LAYER's monitor, and a
  -- frame fires it unaided, so nothing has to be gestured to reach the seam under test.
  local win = hafen.ui():window():title("112.2"):size(140, 40):position(40, 40)
  -- ...and a widget in the CHARACTER's tree, which is the second monitor the Draw handler would take.
  local probe = hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(BUILT_X, BUILT_Y)

  local st = {
    frames = 0,
    drew = false,
    crossOk = nil, crossErr = "<the Draw handler never ran>",
    ownOk = false, ownErr = "<the Draw handler never ran>", ownRead = nil,
    freshOk = false, freshErr = "<the Draw handler never ran>", fresh = nil,
    beforeStep = nil,
    stepOk = false, stepErr = "<the Update handler never reached its frame>", stepRead = nil,
  }

  local drawSub = win:on("Draw", function(ev)
    if st.drew then
      return
    end
    st.drew = true

    -- THE NESTING. This handler holds the addon layer's monitor; the probe is in the character's tree.
    local ok, err = pcall(function() probe:position(DRAW_X, DRAW_Y) end)
    st.crossOk, st.crossErr = ok, why(ok, err)

    -- ...and the three that are NOT the nesting. First: this handler's OWN tree, re-entered.
    local ok2, err2 = pcall(function() win:position(OWN_X, OWN_Y) end)
    st.ownOk, st.ownErr = ok2, why(ok2, err2)

    -- Second: a surface built right here and not yet armed by its tree's tick. It stands on the
    -- layer's root all the same, so it is the same tree and nothing is refused.
    local ok3, err3 = pcall(function()
      st.fresh = hafen.ui():widget():size(6, 6):position(3, 4)
    end)
    st.freshOk, st.freshErr = ok3, why(ok3, err3)
  end)

  local stepSub = win:on("Update", function(dt)
    st.frames = st.frames + 1
    if (not st.drew) or (st.beforeStep ~= nil) then
      return                                   -- wait for the Draw, then act exactly once
    end
    -- Where the probe actually stands, read from the step: the refused write must have left it alone.
    st.beforeStep = probe:position()
    -- Third: THE SAME cross-tree write, issued from a handler holding no monitor at all.
    local ok, err = pcall(function() probe:position(STEP_X, STEP_Y) end)
    st.stepOk, st.stepErr = ok, why(ok, err)
    st.stepRead = probe:position()
  end)

  hafen.timer():after(WRAPUP, function()
    drawSub:off()
    stepSub:off()
    st.ownRead = win:position()          -- read back on a timer, two frames and more after the write

    check(st.drew, "the layer surface's Draw handler ran", st.frames .. " frames, no draw")

    check(st.crossOk == false, "a write across trees from Draw is refused", st.crossErr)
    check(has(st.crossErr, "addon layer") and has(st.crossErr, s:user()),
          "the refusal names BOTH trees", st.crossErr)
    check(has(st.crossErr, "Update"), "the refusal names the step that holds neither", st.crossErr)
    check(near(st.beforeStep, BUILT_X, BUILT_Y), "the refused write did not land",
          place(st.beforeStep) .. ", built at " .. BUILT_X .. "," .. BUILT_Y)

    check(st.ownOk and near(st.ownRead, OWN_X, OWN_Y), "a write into the Draw handler's own tree lands",
          st.ownOk and place(st.ownRead) or st.ownErr)
    check(st.freshOk and (st.fresh ~= nil), "a surface built and not yet armed is written", st.freshErr)
    check(st.stepOk and near(st.stepRead, STEP_X, STEP_Y),
          "the SAME cross-tree write lands when it is issued from Update",
          st.stepOk and place(st.stepRead) or st.stepErr)

    win:destroy()
    probe:destroy()
    if st.fresh then st.fresh:destroy() end
    report()
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  hafen.timer():after(0, start)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
