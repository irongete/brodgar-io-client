-- 128.1 -- a widget's subscriptions end with the widget. Self-checking suite: run with :t128.
--
-- NON-REGRESSION, and it says so out loud. No verb reports how many subscriptions an addon holds, and a
-- handler on a destroyed widget fired nothing before this task and fires nothing after -- so the leak this
-- task closes has no behavioural difference for a suite to assert. What a suite CAN assert is the surface
-- the retirement could break, and that is what every line below is:
--
--   * the announcements a death still makes -- a destroyed surface's own Removed, and the Removed of a
--     surface that dies as its DESCENDANT, which is the one the new retirement runs beside and could eat;
--   * the silence of everything else afterwards -- no Update after the destroy, and no announcement the
--     retirement invented (a teardown that announced would mint one per widget in a closing window);
--   * that a subscription held in Lua still answers sub:off() after its widget is gone, and stays idempotent;
--   * that the machinery is unharmed -- a surface built after the destroy still updates.

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

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

-- EVERYTHING BELOW RUNS ON THE STEP, not on the console line: a command runs inside the UI of the console
-- it was typed into, holding that character's tree monitor, and building a surface in the addon layer would
-- take a second one. hafen.timer():after(0, fn) at the top of the body is the whole of it.
local function body()
  local upP, upC, rmP, rmC = 0, 0, 0, 0
  local subs = {}

  -- The parent surface, and its two subscriptions. Bare (no :parent), so it stands in the addon layer.
  local parent = hafen.ui():widget():size(40, 40):position(0, 0)
  subs[#subs + 1] = parent:on("Update", function() upP = upP + 1 end)
  subs[#subs + 1] = parent:on("Removed", function() rmP = rmP + 1 end)

  -- The child goes in one step later, when the parent it hangs from has armed.
  hafen.timer():after(0, function()
    local child = hafen.ui():widget():parent(parent):size(10, 10):position(0, 0)
    subs[#subs + 1] = child:on("Update", function() upC = upC + 1 end)
    subs[#subs + 1] = child:on("Removed", function() rmC = rmC + 1 end)

    -- A bounded wait: both handlers must have fired before anything rests on their silence.
    hafen.timer():after(0.3, function()
      check(upP > 0, "the surface's Update handler fires", upP)
      check(upC > 0, "...and so does its child's", upC)

      local stillP, stillC = upP, upC
      parent:destroy()      -- the child dies as a DESCENDANT: it never runs remove() itself

      hafen.timer():after(0.3, function()
        check(rmP == 1, "the destroyed surface reported Removed, exactly once", rmP)
        check(rmC == 1, "...and so did the child that died as its descendant", rmC)
        check((upP == stillP) and (upC == stillC),
              "neither Update handler fired again after the destroy", upP - stillP + upC - stillC)
        check((parent:exists() == false) and (child:exists() == false),
              "both surfaces are gone", tostring(parent:exists()) .. "/" .. tostring(child:exists()))

        -- A subscription held in Lua outlives the widget it was made on, and still ends.
        local ok, err = pcall(function()
          for i = 1, #subs do subs[i]:off() end
        end)
        check(ok, "sub:off() on a dead widget's subscription raises nothing", err)
        local ok2, err2 = pcall(function()
          for i = 1, #subs do subs[i]:off() end
        end)
        check(ok2, "...and is still idempotent", err2)

        -- The machinery the retirement writes to is unharmed: a new surface subscribes and fires.
        local fresh = hafen.ui():widget():size(10, 10):position(0, 0)
        local upF = 0
        local fsub = fresh:on("Update", function() upF = upF + 1 end)
        hafen.timer():after(0.3, function()
          check(upF > 0, "a surface built after the destroy still updates", upF)
          fsub:off()
          fresh:destroy()
          summary()
        end)
      end)
    end)
  end)
end

local function run()
  hafen.timer():after(0, body)     -- ...and the step is where a widget of the addon layer may be written
end

hafen.console():on("t128", run)   -- the only way in: a suite does not start itself
