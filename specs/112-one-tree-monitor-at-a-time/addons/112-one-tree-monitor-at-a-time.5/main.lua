-- 112.5 — the placement seam hands its adapters to the entry seam's drain.
-- Self-checking suite. Run it with :t112 while a character is in the world, then do the [manual] step.
--
-- This addon deliberately registers NO selector subscription. Until this task the widget-entry seam's
-- queue was filled only when somebody was watching the tree with s:ui():on, and the equipment, buff and
-- meter events came off the placement seam instead — on the Loader thread that applied the server's
-- message, under that tree's monitor. A pass here is therefore both halves of the task: the handler is
-- free to reach any tree, and it was fed at all.

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

local TO_X, TO_Y = 57, 58        -- where the EquipChanged handler writes its session-tree target
local WINDOW = 25.0              -- the bounded window the manual gesture is waited for

local st = {}

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- How many items the character is wearing right now, or nil if the equipment cannot be read yet. Only a
-- seed: a fire carrying MORE than the last count is an item that ENTERED the equipory, which is the half
-- of the adapter this task moved.
local function worn(s)
  local ok, n = pcall(function() return #s:ui():equipment():items():list() end)
  return ok and n or nil
end

local function finish()
  if st.done then
    return
  end
  st.done = true
  st.equipSub:off()
  st.drawSub:off()
  local read = st.target:position()
  local winUp = (st.win ~= nil) and st.win:exists()
  local nofire = st.fires .. " EquipChanged in " .. WINDOW .. "s, none of them an addition"

  -- The control that says the flag can read false: a draw runs under its own tree's monitor, and is the
  -- family this feature deliberately leaves there.
  check(st.drawStep == false, "a Draw handler does not run on the client's step", st.drawStep)

  check(st.added == true, "an item entering the equipment fires EquipChanged", nofire)
  check(st.step == true, "...and the handler runs on the client's step, holding no tree monitor",
        st.added and st.step or nofire)
  check(st.buildOk and winUp, "...and a window is built from inside it, and is standing afterwards",
        st.added and (tostring(st.buildErr) .. ", standing=" .. tostring(winUp)) or nofire)
  check(st.writeOk and near(read, TO_X, TO_Y),
        "...and a widget of that character's tree is written from the same handler",
        st.added and (st.writeOk and place(read) or st.writeErr) or nofire)

  st.target:destroy()
  if st.win then
    st.win:destroy()
  end
  report()
end

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end

  st.fires = 0
  st.prev = worn(s)
  st.target = hafen.ui():widget():parent(s:ui():root()):size(4, 4):position(2, 2)
  st.drawSub = st.target:on("Draw", function()
    if st.drawStep == nil then
      st.drawStep = hafen.client():stepping()
    end
  end)

  st.equipSub = hafen.event():on("EquipChanged", function(items)
    st.fires = st.fires + 1
    local n, prev = #items, st.prev
    st.prev = n
    if st.added or (prev == nil) or (n <= prev) then
      return                       -- the take-off, or a re-read: only an ARRIVAL is the moved half
    end
    st.added = true
    st.step = hafen.client():stepping()
    -- The other tree (a window with no parent is the layer's), then this one — the pair that nested two
    -- monitors while this event still left from inside AddWidget.run.
    local ok, err = pcall(function()
      st.win = hafen.ui():window():title("112.5"):size(150, 34):position(40, 40)
    end)
    st.buildOk, st.buildErr = ok, why(ok, err)
    local ok2, err2 = pcall(function() st.target:position(TO_X, TO_Y) end)
    st.writeOk, st.writeErr = ok2, why(ok2, err2)
  end)

  manualCheck("now take one worn item off and put it back on",
              "the four checks below score -- only the server can put an item into the equipment")

  hafen.timer():after(WINDOW, finish)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  st = {}
  hafen.timer():after(0, start)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
