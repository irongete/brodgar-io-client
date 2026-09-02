-- 125.3 -- the two sites the argument rule reaches last: a view standing in for one of the client's
-- windows, and a widget standing in the world. Self-checking suite: run with :t125.
--
-- The fixture is a corpse this suite builds itself -- hafen.ui():widget() then :destroy() -- and it is
-- proven dead before anything rests on it, assuming no other suite is ever run.
--
-- A negative only means something beside a positive, so a LIVE view is installed on one of the client's
-- own windows and read back BEFORE the dead one is offered: without that, ":replacement() is nil" would
-- also be true of a verb that never installed anything at all. The host window is found by selector, and
-- a run that finds none scores those checks as fails.
--
-- The view takes the host's own visibility before the window is given back, so what is on screen after
-- the run is what was on it before.

local pass, fail = 0, 0

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
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- EVERYTHING BELOW RUNS ON THE STEP, not on the console line. A command runs inside the UI of the
-- console it was typed into, holding that character's tree monitor, and building a surface in the addon
-- layer would take a second one. hafen.timer():after(0, fn) at the top of the body is the whole of it.
local function body()
  -- The fixture, proven before anything rests on it.
  local dead = hafen.ui():widget()
  dead:destroy()
  check(dead:exists() == false, "the fixture is a corpse: a surface built and destroyed", dead:exists())

  local s = hafen.session():current()
  local hud = (s ~= nil) and s:ui():match("@GameUI") or nil
  local wnds = (s ~= nil) and s:ui():matchAll("window") or {}

  -- One of the CLIENT's own windows -- unnamed and not an addon's -- and a live view standing in for it.
  local host, view, was = nil, nil, nil
  for i = 1, #wnds do
    local w = wnds[i]
    if (w:name() == nil) and (w:owned() == false) then
      local vis = w:visible()
      local v = hafen.ui():window():title("125.3"):size(120, 60)
      if pcall(function() w:replace(v) end) then
        host, view, was = w, v, vis
        break
      end
      v:destroy()
    end
  end
  local nohost = "no client window on screen took a view"
  check((host ~= nil) and (host:replacement() == view),
        "a live view installs on a client window, and :replacement() reads it back",
        (host == nil) and nohost or host:replacement())
  if host ~= nil then
    view:visible(was)                     -- ...so the window comes back exactly as the user had it
    host:replace(nil)
  end
  check((host ~= nil) and (host:replacement() == nil),
        "...and :replace(nil) gives the window back, which is what makes the negative mean something",
        (host == nil) and nohost or host:replacement())

  -- The negative: a view that died between the step that built it and this call installs nothing, and
  -- the call chains. The window is left exactly as the user has it.
  if host == nil then
    check(false, "a view that has left the tree raises nothing and chains", nohost)
    check(false, "...and nothing was installed: :replacement() reads nil", nohost)
    check(false, "a live view your addon did not build still raises, so the ownership check is reached",
          nohost)
  else
    local ok, got = pcall(function() return host:replace(dead) end)
    check(ok and (got == host), "a view that has left the tree raises nothing and chains",
          ok and got or got)
    check(host:replacement() == nil, "...and nothing was installed: :replacement() reads nil",
          host:replacement())
    refuses("a live view your addon did not build still raises, so the ownership check is reached",
            function() host:replace(host) end, "widget YOUR addon created")
  end

  -- The other half of the split: a value that is not a Widget is a spelling mistake, and still raises.
  local recv = host or hud
  if recv == nil then
    check(false, "a view that is not a Widget still raises, naming a widget your addon built",
          "no client widget on screen to call it on")
  else
    refuses("a view that is not a Widget still raises, naming a widget your addon built",
            function() recv:replace(42) end, "widget YOUR addon created")
  end

  -- The one site that goes on RAISING: it MINTS the panel it hands back, so there is no receiver to
  -- chain and no nil to hand. What the split buys there is the reason -- the tree, not "userdata".
  local me = (s ~= nil) and s:player():gob() or nil
  if me == nil then
    check(false, "a widget that has left the tree raises in the world, and names the tree",
          "not in the world: no player gob to anchor to")
    check(false, "...and a value that is not a Widget raises there as it always did",
          "not in the world: no player gob to anchor to")
  else
    refuses("a widget that has left the tree raises in the world, and names the tree",
            function() hafen.virtual():widget():add(dead, me) end, "has left the tree")
    refuses("...and a value that is not a Widget raises there as it always did",
            function() hafen.virtual():widget():add(42, me) end, "expects a Widget")
  end

  -- The boundary this feature does not cross: a stale RECEIVER keeps every rule it has.
  refuses("a stale receiver's :on(key, fn) still raises, naming the missing tree",
          function() dead:on("Removed", function() end) end, "no longer in the tree")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

local function run()
  hafen.timer():after(0, body)     -- ...and the step is where a widget of the addon layer may be written
end

hafen.console():on("t125", run)   -- the only way in: a suite does not start itself
