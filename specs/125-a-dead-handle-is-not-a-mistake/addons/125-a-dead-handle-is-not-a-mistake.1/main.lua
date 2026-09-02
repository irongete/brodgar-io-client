-- 125.1 -- widget:parent(w) stops on a Widget argument that has left the tree, at BOTH ends of the
-- verb. Self-checking suite: run with :t125.
--
-- The fixture is a corpse this suite builds itself -- hafen.ui():widget() then :destroy() -- and it is
-- proven dead before anything rests on it. What is asserted after that is never the absence of an
-- error: it is that the surface was ABANDONED (its :exists() is false and its reads answer nil) and
-- that the client's own widget did not move.

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

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

-- EVERYTHING BELOW RUNS ON THE STEP, not on the console line. A command runs inside the UI of the
-- console it was typed into, holding that character's tree monitor, and building a surface in the addon
-- layer would take a second one. hafen.timer():after(0, fn) at the top of the body is the whole of it.
local function body()
  -- The fixture, proven before anything rests on it.
  local dead = hafen.ui():widget()
  dead:destroy()
  check(dead:exists() == false, "the fixture is a corpse: a surface built and destroyed", dead:exists())

  -- The reported crash, whole: the build chain over a dead parent.
  local plate
  local built, err = pcall(function()
    plate = hafen.ui():widget():parent(dead):name("plate"):stock({}):position(4, 4)
  end)
  check(built, "the whole build chain over a dead parent raises nothing", err)
  check(tostring(plate):find("Widget", 1, true) ~= nil, "...and hands a Widget back", tostring(plate))
  check((plate ~= nil) and (plate:exists() == false),
        "...whose surface is abandoned, not left orphaned in the layer",
        (plate ~= nil) and plate:exists())
  local reads = "<no widget>"
  if plate ~= nil then
    reads = tostring(plate:name()) .. "/" .. tostring(plate:stock()) .. "/"
         .. tostring(plate:position()) .. "/" .. tostring(plate:parent())
  end
  check(reads == "nil/nil/nil/nil", "...and every read on it answers nil", reads)

  -- The other half of the split: a value that is not a Widget is a spelling mistake, and still raises.
  local own = hafen.ui():widget()
  refuses("a value that is not a Widget still raises, naming Widget",
          function() own:parent(42) end, "expects a Widget")
  check(own:exists() == true, "...and the receiver it was called on survives that refusal", own:exists())
  own:destroy()

  -- The native direction of the same verb: the destination died, the client's own widget stays put.
  local s = hafen.session():current()
  local mmap = (s ~= nil) and s:ui():match("@CornerMap") or nil
  if mmap == nil then
    check(false, "the corner minimap takes a dead destination and chains", "no @CornerMap on screen")
    check(false, "...and nothing moved: it hangs where it hung", "no @CornerMap on screen")
    check(false, "...while a destination that is not a Widget still raises", "no @CornerMap on screen")
  else
    local home = mmap:parent()
    local chained, got = pcall(function() return mmap:parent(dead) end)
    check(chained and (got == mmap), "the corner minimap takes a dead destination and chains",
          chained and tostring(got) or got)
    check((home ~= nil) and (mmap:parent() == home),
          "...and nothing moved: it hangs where it hung", tostring(mmap:parent()))
    refuses("...while a destination that is not a Widget still raises",
            function() mmap:parent(42) end, "hafen.ui():widget()")
  end

  -- The boundary this feature does not cross: a stale RECEIVER keeps every rule it has.
  refuses("a stale receiver's :on(key, fn) still raises, naming the missing tree",
          function() dead:on("Removed", function() end) end, "no longer in the tree")

  -- The build-time refusal outranks the argument rule, which needs a surface that has ARMED.
  local hud = (s ~= nil) and s:ui():match("@GameUI") or nil
  if hud == nil then
    check(false, "a surface already on screen refuses :parent(w), naming :position(x, y)",
          "no @GameUI on screen")
    check(false, "...and a dead argument does not swallow that refusal", "no @GameUI on screen")
    summary()
    return
  end
  local armed = hafen.ui():widget():parent(hud):size(20, 20):position(0, 0)
  hafen.timer():after(0, function()
    refuses("a surface already on screen refuses :parent(w), naming :position(x, y)",
            function() armed:parent(hud) end, "widget:position(x, y)")
    refuses("...and a dead argument does not swallow that refusal",
            function() armed:parent(dead) end, "widget:position(x, y)")
    armed:destroy()
    summary()
  end)
end

local function run()
  hafen.timer():after(0, body)     -- ...and the step is where a widget of the addon layer may be written
end

hafen.console():on("t125", run)   -- the only way in: a suite does not start itself
