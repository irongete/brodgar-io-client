-- 120.1 -- the remembered ground has a surface: three settings and four numbers. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  local c = hafen.client():options():client()
  local wason, wasrange, wasgrey = c:recall(), c:recallRange(), c:recallGrey()
  local wasprof = c:profiling()

  -- The three settings round-trip: each reads back what it wrote, both ways and at both bounds.
  c:recall(true)
  local ontrue = c:recall()
  c:recall(false)
  check((ontrue == true) and (c:recall() == false), "recall reads back what it wrote, both ways", ontrue)

  c:recallGrey(true)
  local greytrue = c:recallGrey()
  c:recallGrey(false)
  check((greytrue == true) and (c:recallGrey() == false), "recallGrey reads back what it wrote, both ways", greytrue)

  c:recallRange(1)
  local atmin = c:recallRange()
  c:recallRange(8)
  check((atmin == 1) and (c:recallRange() == 8), "recallRange reads back what it wrote, at 1 and at 8", atmin)

  -- Three refusals. The bounds one names its bounds, and it leaves the old value standing.
  refuses("an explicit nil is refused", function() c:recallRange(nil) end, "must not be nil")
  refuses("a non-number range is refused", function() c:recallRange("4") end, "must be a number")
  refuses("a range outside the bounds is refused naming them", function() c:recallRange(9) end, "from 1 to 8")
  check(c:recallRange() == 8, "a refused write leaves the old range standing", c:recallRange())

  -- The four counters, read with profiling DISARMED: every later task of this feature proves itself
  -- through them, so a number here is the whole of what those proofs rest on.
  if wasprof then c:profiling(false) end
  check(c:profiling() == false, "profiling is disarmed for the counter reads", c:profiling())
  local r = hafen.client():profiling():render()
  check(type(r.recallGridsHeld) == "number", "render() answers recallGridsHeld", r.recallGridsHeld)
  check(type(r.recallGridsRead) == "number", "render() answers recallGridsRead", r.recallGridsRead)
  check(type(r.recallCutsDrawn) == "number", "render() answers recallCutsDrawn", r.recallCutsDrawn)
  check(type(r.recallCutsWanted) == "number", "render() answers recallCutsWanted", r.recallCutsWanted)
  if wasprof then c:profiling(true) end

  -- Put everything back, then blink the switch for ten seconds so that an open panel has something to
  -- show: the panel re-reads the field every frame, so a Lua write moves it with nothing subscribed.
  c:recall(wason):recallRange(wasrange):recallGrey(wasgrey)
  manualCheck("open Options > Game > Client and watch Remembered ground for ten seconds",
              "a checkbox, a Range slider standing at " .. wasrange .. " and a Grey wash checkbox; the"
              .. " first checkbox flips once a second and rests where it started")
  local left, blink = 10, nil
  blink = hafen.timer():every(1, function()
    left = left - 1
    if left <= 0 then
      c:recall(wason)
      blink:cancel()
    else
      c:recall(not c:recall())
    end
  end)

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t120", run)   -- the only way in: a suite does not start itself
