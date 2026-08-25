-- 112.1 — the engine step, and widget:on("Update") with it, leave the tree monitor.
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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Is a {x=, y=} read the place we wrote? Within a pixel, because a design pixel is converted to the
-- screen and back and the user's interface scale need not be a whole number.
local function near(got, x, y)
  return (got ~= nil) and (math.abs(got.x - x) <= 1) and (math.abs(got.y - y) <= 1)
end

-- The message a pcall came back with, with the file:line prefix taken off.
local function why(ok, err)
  if ok then
    return "<no error>"
  end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A {x=, y=} read as "x,y", for a message that has to say where a write actually landed.
local function place(p)
  return (p == nil) and "<nowhere>" or (p.x .. "," .. p.y)
end

local WINDOW = 1.0    -- the timed second the two Update counts are compared over
local KILL   = 0.5    -- ...and the point inside it the second surface is destroyed at

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  local s = hafen.session():current()

  -- Two surfaces of our own, in the addon layer: one stepped for the whole window, one destroyed
  -- halfway through it.
  local win  = hafen.ui():window():title("112.1"):size(140, 40):position(40, 40)
  local dies = hafen.ui():widget():size(8, 8):position(0, 0)

  -- ...and one inside the CHARACTER's tree, which is the second monitor the pair below takes.
  local probe, probeErr
  if not s then
    probeErr = "no character on screen — run :t112 in the world"
  else
    local ok, r = pcall(function()
      return hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(1, 1)
    end)
    if ok then
      probe = r
    else
      probeErr = why(false, r)
    end
  end

  local st = {
    ticks = 0, bus = 0, dies = 0, diesAtKill = -1,
    sum = 0, nonPositive = 0, sameDt = 0, otherDt = 0, lastBusDt = nil,
    pairDone = false, pairOk = false, pairErr = "<the Update handler never ran>",
    pairRead = nil, winRead = nil,
    deepDone = false, deepOk = false, built = nil,
    deepErr = "<no item in the tree this run: open a container or pick something up, then re-run>",
  }

  local busSub = hafen.event():on("Update", function(dt)
    st.bus = st.bus + 1
    st.lastBusDt = dt
  end)

  local diesSub = dies:on("Update", function(dt)
    st.dies = st.dies + 1
  end)

  local winSub = win:on("Update", function(dt)
    st.ticks = st.ticks + 1
    st.sum = st.sum + dt
    if not ((type(dt) == "number") and (dt > 0)) then
      st.nonPositive = st.nonPositive + 1
    end
    if dt == st.lastBusDt then
      st.sameDt = st.sameDt + 1
    else
      st.otherDt = st.otherDt + 1
    end

    -- THE PAIR. A widget of this layer and a widget of the character's tree, written from ONE handler.
    -- This handler used to be a widget's own tick, which runs with its tree's monitor already held, so
    -- the second write took a second monitor under the first.
    if not st.pairDone then
      st.pairDone = true
      local ok, err = pcall(function()
        win:position(44, 45)                    -- the addon layer's tree
        if probe then
          probe:position(6, 7)                  -- ...and the character's, in the same turn
        end
      end)
      st.pairOk, st.pairErr = ok, why(ok, err)
      st.winRead = win:position()
      st.pairRead = probe and probe:position() or nil
    end

    -- THE RECORDED DEADLOCK. Reading what an item holds forces the client's own item-info build and the
    -- seam inside it, while a second surface is built — the two arrows that met. Retried every frame of
    -- the window, because only the server can put an item in the tree.
    if not st.deepDone then
      local list = s and s:ui():root():items():list() or {}
      local it = list[1]
      if it then
        st.deepDone = true
        local ok, err = pcall(function()
          it:contents()
          st.built = hafen.ui():widget():size(6, 6):position(2, 2)
        end)
        st.deepOk, st.deepErr = ok, why(ok, err)
      end
    end
  end)

  hafen.timer():after(KILL, function()
    st.diesAtKill = st.dies
    dies:destroy()
  end)

  hafen.timer():after(WINDOW, function()
    winSub:off()
    busSub:off()
    diesSub:off()

    check(st.ticks > 0, "the step fires a surface's own Update", st.ticks .. " in " .. WINDOW .. "s")

    local landed = st.pairOk and (probe ~= nil) and near(st.winRead, 44, 45) and near(st.pairRead, 6, 7)
    check(landed, "one Update writes this layer and the character's tree",
          (not st.pairOk) and st.pairErr
            or probeErr
            or ("layer " .. place(st.winRead) .. ", character " .. place(st.pairRead)))

    check(st.deepOk, "an item's contents and a second surface finish in one Update", st.deepErr)

    check((st.otherDt == 0) and (st.sameDt > 0), "the surface's dt is the step's own",
          st.sameDt .. " frames matched the bus, " .. st.otherDt .. " did not")

    check((st.nonPositive == 0) and (st.sum > (WINDOW * 0.6)) and (st.sum < (WINDOW * 1.6)),
          "dt is positive and sums to the window",
          string.format("%.3fs over %d frames, %d not positive", st.sum, st.ticks, st.nonPositive))

    check(math.abs(st.ticks - st.bus) <= 1, "the surface's Update count agrees with the bus's",
          st.ticks .. " on the surface, " .. st.bus .. " on the bus")

    check((st.diesAtKill > 0) and (st.dies == st.diesAtKill),
          "a destroyed surface stops receiving Update",
          st.diesAtKill .. " before the destroy, " .. st.dies .. " after")

    manualCheck("watch the HUD for a few seconds after this line, and move the mouse over it",
                "it keeps drawing and keeps answering the pointer")

    win:destroy()
    if probe then probe:destroy() end
    if st.built then st.built:destroy() end

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
