-- 075.2 — one map database, one lock. Self-checking suite.
--
-- The pin stands for HOLD seconds before it is removed, which is the window the tab-over check needs:
-- the other character has to open its own map and find the pin this one dropped.

local pass, fail, manual = 0, 0, 0

local NAME = "075-2 probe"
local HOLD = 15                    -- seconds the pin stands, for the tab-over check

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

-- The notify is marshalled onto the tick, so score over what the run reaches: poll a bounded window.
local function waitFor(done, secs, fn)
  local left = secs
  local t
  t = hafen.timer():every(0.2, function()
    left = left - 0.2
    if done() or (left <= 0) then
      t:cancel()
      fn(done())
    end
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0            -- the second manual check re-runs this: one summary per run
  local mk = hafen.map():marker()

  local before = mk:list()
  check(type(before) == "table", "the marker collection lists", type(before))
  eq("the count agrees with the list", mk:count(), #before)

  local last                                  -- the count MarkersChanged last reported
  local sub = hafen.event():on("MarkersChanged", function(n) last = n end)

  local place = hafen.player():gob()
  place = place and place:position()
  local pin = place and mk:add(NAME, place)
  check((pin ~= nil) and (pin:type() == "player"), "a player pin is added at the player's place",
        pin and pin:type())
  check((pin ~= nil) and (mk:find(NAME) == pin), "the collection hands that same object back",
        pin and mk:find(NAME))
  check((pin ~= nil) and pin:exists(), "the pin is in the database", pin and pin:exists())
  eq("the list grew by one", mk:count(), #before + 1)
  refuses("a malformed place is refused", function() mk:add("probe", { x = 1, y = 2 }) end,
          "must be a Position")

  manualCheck("with two sessions up, tab to the other one and open its map within " .. HOLD .. "s",
              'the pin "' .. NAME .. '" is on it -- one database, both characters')
  manualCheck("`:session drop` one of the two, then re-run this suite in the one left",
              "every automated check still passes -- the database outlived the window that showed it")

  waitFor(function() return last == (#before + 1) end, 4, function(got)
    check(got, "MarkersChanged reported the add", last)
    hafen.timer():after(HOLD, function()
      if pin then mk:remove(pin) end
      check((pin ~= nil) and (not pin:exists()), "the pin is gone after :remove", pin and pin:exists())
      check(mk:find(NAME) == nil, "and the collection no longer finds it", mk:find(NAME))
      waitFor(function() return last == #before end, 4, function(back)
        check(back, "MarkersChanged reported the removal", last)
        sub:off()
        summary()
      end)
    end)
  end)
end

hafen.slash():register("t075-2", run)   -- the only way in: a suite does not start itself
