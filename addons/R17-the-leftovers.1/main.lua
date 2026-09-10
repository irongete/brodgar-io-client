-- R17.1 -- the three refusals this block put at their doors: a request's timeout, an overlay's anchor
-- fraction, and the end every subscription on a widget gets when the widget goes. It runs only from
-- :tR17-1, sends nothing on the wire and mutates no persistent state.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level error
-- adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both: the class is
-- [^\n] rather than . so a traceback under the message cannot be eaten as a third prefix.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?[^\n]-%.lua:%d+:?[ \t]*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

-- Did fn refuse, and did it say why? Answers nil when it did, and what went wrong when it did not.
local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

local function allRefuse(name, cases, also, got)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, (bad == nil) and (also ~= false), bad or got)
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

-- ---- ht-11: the timeout range ----------------------------------------------------------------------

local RANGE = "ms must be a whole number (milliseconds) from 1 to 60000"

local function timeout()
  local req = hafen.http():request("https://example.com/r")
  allRefuse("a timeout outside 1..60000 is refused, naming the verb, the parameter and the range", {
    {"timeout(0)",   function() return req:timeout(0) end,   "request:timeout: " .. RANGE},
    {"timeout(-1)",  function() return req:timeout(-1) end,  "request:timeout: " .. RANGE},
    {"timeout(1e9)", function() return req:timeout(1e9) end, "request:timeout: " .. RANGE},
    {"timeout(1.5)", function() return req:timeout(1.5) end, "request:timeout: ms must be a whole number"},
  })
  -- ...and the clamp is gone rather than moved: a legal value is taken and reads back as itself, both edges
  -- included, where the clamp used to rewrite the 1 to the 10000 default and the 1e9 down to the ceiling.
  local d = read(function() return req:timeout() end)
  local one = read(function() return req:timeout(1):timeout() end)
  local top = read(function() return req:timeout(60000):timeout() end)
  local mid = read(function() return req:timeout(5000):timeout() end)
  ok("both edges of the range are taken and read back as themselves, the default beside them",
     (d == 10000) and (one == 1) and (top == 60000) and (mid == 5000),
     tostring(d) .. " / " .. tostring(one) .. " / " .. tostring(top) .. " / " .. tostring(mid))
end

-- ---- un-12: the anchor fractions -------------------------------------------------------------------

local function anchor(w)
  local ov = read(function() return w:overlay():add("r17"):text("x") end)
  if ov == nil then
    ok("an anchor fraction outside 0..1 is refused, naming the axis it is on", false, "no overlay to test")
    return
  end
  allRefuse("an anchor fraction outside 0..1 is refused, naming the axis it is on", {
    {"anchor(1e9, 0)", function() return ov:anchor(1e9, 0) end,  "overlay:anchor: ax must be 0..1"},
    {"anchor(0, -1)",  function() return ov:anchor(0, -1) end,   "overlay:anchor: ay must be 0..1"},
    {"anchor(0, 0/0)", function() return ov:anchor(0, 0 / 0) end, "ay must be a finite number"},
    {"anchor(0.5)",    function() return ov:anchor(0.5) end,     "takes BOTH fractions"},
  })
  -- Both edges are legal, and offset -- the verb that DOES move a label off the corner it is anchored to --
  -- still takes any finite pixel. A refusal leaves the overlay drawing what it drew before, so the pair
  -- read back is the last good one rather than whatever the refused call carried.
  local a0 = read(function() return ov:anchor(0, 0):anchor() end)
  local a1 = read(function() return ov:anchor(1, 1):anchor() end)
  local of = read(function() return ov:offset(1e6, -1e6):offset() end)
  ok("both edges of the fraction are taken, and offset still takes any finite pixel",
     (type(a0) == "table") and (a0.x == 0) and (a0.y == 0)
       and (type(a1) == "table") and (a1.x == 1) and (a1.y == 1)
       and (type(of) == "table") and (of.x == 1e6) and (of.y == -1e6)
       and (refused("offset(1/0, 0)", function() return ov:offset(1 / 0, 0) end,
                    "x must be a finite number") == nil),
     tostring((type(a1) == "table") and a1.x) .. "," .. tostring((type(a1) == "table") and a1.y)
       .. " / " .. tostring((type(of) == "table") and of.x))
end

-- ---- uw-22: the end every subscription on a widget gets ---------------------------------------------

local function ending(w, done)
  local removed, ticks = 0, 0
  local subR = read(function() return w:on("Removed", function() removed = removed + 1 end) end)
  local subU = read(function() return w:on("Update", function() ticks = ticks + 1 end) end)
  if (subR == nil) or (subU == nil) then
    ok("every subscription on a widget ends when the widget does, and the Update key stops stepping",
       false, "the widget refused one of the two keys")
    done()
    return
  end
  hafen.timer():after(0.4, function()
    local stepped = ticks
    w:destroy()
    hafen.timer():after(0.6, function()
      -- The Update sub is dead, its key's Idle took the surface off the step list (so the counter stands
      -- still), and the Removed handler ran exactly once. tostring(sub) is where a Sub says it is off.
      ok("every subscription on a widget ends when the widget does, and the Update key stops stepping",
         (stepped > 0) and (removed == 1) and ((ticks - stepped) <= 2)
           and (tostring(subU):find("off)", 1, true) ~= nil)
           and (tostring(subR):find("off)", 1, true) ~= nil),
         stepped .. " steps, " .. (ticks - stepped) .. " after the destroy, Removed " .. removed
           .. "x, " .. tostring(subU))
      -- ...and the door itself: a widget that has left the tree takes no new subscription at all, which is
      -- what makes WidgetSubs' own dead-widget arm defensive rather than a path an addon can reach.
      allRefuse("a widget that has left the tree refuses a new subscription, naming the tree", {
        {"on Removed", function() return w:on("Removed", function() end) end, "no longer in the tree"},
        {"on Update",  function() return w:on("Update", function() end) end,  "no longer in the tree"},
      }, read(function() return w:exists() end) == false, "w:exists() answered true after :destroy()")
      done()
    end)
  end)
end

-- ---- the run ---------------------------------------------------------------------------------------

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("tR17-1", function()
  pass, fail, manual = 0, 0, 0
  -- The typed command runs under the console tree's monitor, so a run that builds a widget is deferred to
  -- the step exactly as every suite that reads a widget tree is.
  hafen.timer():after(0, function()
    timeout()
    local w = hafen.ui():widget():size(8, 8):position(0, 0)
    anchor(w)
    ending(w, finish)
  end)
end)
