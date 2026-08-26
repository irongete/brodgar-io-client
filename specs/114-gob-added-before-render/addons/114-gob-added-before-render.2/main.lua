-- 114.2 — the GobAdded guarantee, stated where an addon reads it. Self-checking suite.
--
-- Type :t114 in the world, then WALK for 20 seconds into ground you have not seen this session, so
-- objects keep streaming in and objects behind you keep leaving view. The run needs both edges: the
-- arrivals drive the promise, and one departure drives the refusal that is still reachable.
--
-- It duplicates 114.1's reads-at-handler-time assertions on purpose: this suite assumes no other one
-- was ever run.

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

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the
-- strip has to allow both shapes or the message is scored with its own location glued to the front.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local WAIT, LABELS = 20, 12
local KEY, GONEKEY = "t114-label", "t114-gone"

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t114 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t114 in the world")
    return report()
  end

  manualCheck("read docs/addons/api/event/bus/world.md, the section \"Before the first drawn frame\"",
              "GobAdded runs before the object it announces is drawn; the reads that answer in the"
              .. " handler and the composite that answers nil; the three limits under it")
  manualCheck("read docs/addons/api/threading.md, the step row and the ordering paragraph under the table",
              "the step is ordered ahead of the drawing for GobAdded, and nothing else on it promises"
              .. " that")
  manualCheck("read docs/addons/api/overlay.md, the attach-timing paragraph",
              "an attach from GobAdded is never too early and that is the rule, not a way round"
              .. " anything; what still raises about WHEN is an object whose own drawing is resolving")

  manualCheck("walk into ground you have not seen this session for the next " .. WAIT .. "s",
              "objects keep arriving ahead of you and objects behind you keep leaving view -- this run"
              .. " needs both edges, and it labels the first " .. LABELS .. " arrivals and takes the"
              .. " labels off again when it ends")

  -- THE PROMISE, driven rather than restated: everything below is read INSIDE the handler.
  local arrivals = 0
  local named, stated, boxed = 0, 0, 0
  local attached, readBack, olErr = 0, 0, nil
  local labelled = {}

  -- ...and the refusal that is still reachable, off the other edge.
  local goneTried, goneErr = false, nil

  local addSub, remSub
  addSub = hafen.event():on("GobAdded", function(gob)
    arrivals = arrivals + 1

    -- Reads at handler time: the hold must cost no data. A composite still being assembled answers nil
    -- for all three and is not counted -- the claim is about a resource-drawn object.
    local name = gob:name()
    if name then
      named = named + 1
      if type(gob:sdt()) == "table" then stated = stated + 1 end
      local hb = gob:hitbox()
      if (type(hb) == "table") and (#hb > 0) then boxed = boxed + 1 end
    end

    -- A write made in the handler, and read back THERE: the object holds no slots yet, so the attach
    -- has nothing to fail against, and gob:overlay():get(key) answers before the first drawn frame.
    if (#labelled < LABELS) and (olErr == nil) then
      local ok, err = pcall(function()
        gob:overlay():add(KEY):text("t114"):color{200, 210, 220}
      end)
      if not ok then
        olErr = why(err)
      else
        attached = attached + 1
        labelled[#labelled + 1] = gob
        local ov = gob:overlay():get(KEY)
        if ov and (ov:key() == KEY) then readBack = readBack + 1 end
      end
    end
  end)

  remSub = hafen.event():on("GobRemoved", function(gob)
    -- On GobRemoved the object is already gone, so this is the one place an attach meets the refusal
    -- that 114.1 did NOT take away -- and it must name the gob, not anything about renderability.
    if goneTried then return end
    goneTried = true
    local ok, err = pcall(function() gob:overlay():add(GONEKEY) end)
    goneErr = ok and "<no error>" or why(err)
  end)

  hafen.timer():after(WAIT, function()
    addSub:off()
    remSub:off()
    for i = 1, #labelled do pcall(function() labelled[i]:overlay():remove(KEY) end) end

    check(arrivals > 0, "at least one GobAdded arrived over " .. WAIT .. "s",
          "none -- walk into unseen ground and re-run")
    if arrivals > 0 then
      check(named > 0, "gob:name() answers inside the handler (" .. named .. " of " .. arrivals .. ")",
            "every arrival answered nil")
      check(stated > 0, "gob:sdt() answers there too, for a resource-drawn object (" .. stated .. ")",
            "no named arrival carried state bytes")
      check(boxed > 0, "gob:hitbox() answers there too (" .. boxed .. ")",
            "no named arrival stated a shape")
      check((olErr == nil) and (attached > 0) and (readBack == attached),
            "an overlay attached in the handler reads back through gob:overlay():get(key) in that same"
            .. " handler (" .. readBack .. " of " .. attached .. ")",
            olErr or (attached == 0 and "nothing was attached"
                      or (attached - readBack) .. " attached but did not read back"))
    end

    check(goneTried and (goneErr ~= "<no error>") and (goneErr:find("that gob is gone", 1, true) ~= nil)
          and (goneErr:find("renderable", 1, true) == nil),
          "gob:overlay():add(key) on a gob that has left refuses NAMING THE GOB, and says nothing about"
          .. " renderability",
          goneTried and goneErr or "no object left view -- walk further and re-run")

    report()
  end)
end

hafen.console():on("t114", run)   -- the only way in: a suite does not start itself
