-- 087.2 -- every ending returns the receiver. Self-checking suite.
--
-- conventions.md teaches that a write hands the object back so writes chain, and the teardown family is
-- where a user chains most. Seven endings answered nil. The claim is `x:end() == x`, so the whole of this
-- suite is `==` against the very object the verb was called on -- there is nothing else to look at.
--
-- A verb that returns the receiver and does NOTHING would pass that check alone, so each ending is asked
-- again afterwards whether it ended: a subscription is gone from hafen.event():count(), a timer is not
-- :alive(), a widget does not :exist(). And the two the pages promise are idempotent are called twice.
--
-- ov:destroy() is the refusal: it must still answer nil, so the sweep stopped exactly where it was told.

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

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, r = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- ---- the claim ---------------------------------------------------------------------------------------
--
-- Seven rows, each filled in by the section that owns that ending: the verb answered the object it was
-- called on, or it did not and this says what came back instead. The flowermenu row needs an open menu,
-- which only the player can put up, so it is reached inside a bounded window and scored over what it got.

local chain = {}
local ORDER = {"sub:off", "timer:cancel", "widget:destroy", "rule:release",
               "grab:release", "request:cancel", "flowermenu:cancel"}

local function chained(verb, got, want)
  chain[verb] = {got == want, tostring(got)}
end

local function chainVerdict()
  local n, bad = 0, {}
  for _, verb in ipairs(ORDER) do
    local r = chain[verb]
    if r and r[1] then
      n = n + 1
    else
      bad[#bad + 1] = verb .. " -> " .. (r and r[2] or "<not reached>")
    end
  end
  check(n == 7, "each of the seven endings answers the receiver it was called on (" .. n .. "/7 reached)",
        table.concat(bad, ", "))
end

-- ---- the three that also have to have ENDED ----------------------------------------------------------
--
-- The subscription, the timer and the widget are the three whose ending is readable back through the API,
-- so they carry the other half of the claim: the receiver came back AND the thing is over.

local ended = {n = 0, bad = {}}

local function endedIs(what, ok, got)
  if ok then ended.n = ended.n + 1 else ended.bad[#ended.bad + 1] = what .. " -> " .. tostring(got) end
end

-- ---- the subscription --------------------------------------------------------------------------------

local function subSection()
  local before = hafen.event():count()
  local sub = hafen.event():on("GobAdded", function() end)
  local during = hafen.event():count()
  chained("sub:off", sub:off(), sub)
  local after = hafen.event():count()
  endedIs("hafen.event():count() around sub:off()", (during == before + 1) and (after == before),
          before .. " -> " .. during .. " -> " .. after)
  return sub
end

-- ---- the timer ---------------------------------------------------------------------------------------

local function timerSection()
  local t = hafen.timer():after(3600, function() end)
  chained("timer:cancel", t:cancel(), t)
  endedIs("t:alive() after t:cancel()", t:alive() == false, t:alive())
  return t
end

-- ---- the widget, and the layer it carries ------------------------------------------------------------
--
-- Two endings on one probe window: the rule is the layer this addon took over that widget, and destroying
-- the widget is the end of the widget itself. Both are read back before the window goes.

local function widgetSection()
  local w = hafen.ui():window():title("087.2 probe"):size(60, 30)
  local rule = w:rule()
  rule:color({200, 210, 220})
  chained("rule:release", rule:release(), rule)
  chained("widget:destroy", w:destroy(), w)
  endedIs("w:exists() after w:destroy()", w:exists() == false, w:exists())
end

-- ---- the pointer -------------------------------------------------------------------------------------
--
-- Taken and given back inside one call, so the pointer is this addon's for no frame the user can see.

local function grabSection()
  local g = hafen.ui():mouse():grab()
  if g == nil then
    chain["grab:release"] = {false, "<no UI to capture on>"}
    return
  end
  chained("grab:release", g:release(), g)
end

-- ---- the request -------------------------------------------------------------------------------------
--
-- A request goes out on the NEXT tick, so one cancelled in the same statement that made it never leaves:
-- example.invalid is a reserved name that resolves nowhere, and nothing is sent to it either way.

local function httpSection()
  local req = hafen.http():get("https://example.invalid/087-2", function() end)
  chained("request:cancel", req:cancel(), req)
end

-- ---- the open menu -----------------------------------------------------------------------------------
--
-- The one ending nothing in the API can cause: a radial menu is the server's, put up by a right-click the
-- player makes. So this polls for one over a bounded window and scores what it reached.

local function menu()
  local ok, fm = pcall(function()
    local s = hafen.session():current()
    return s and s:flowermenu()
  end)
  if (not ok) or (fm == nil) then return nil end
  return (fm:count() > 0) and fm or nil
end

local function menuSection(fm)
  if fm == nil then return end
  chained("flowermenu:cancel", fm:cancel(), fm)
end

-- ---- idempotence -------------------------------------------------------------------------------------
--
-- The pages promise a second call is harmless. Returning the receiver must not turn it into a raise: the
-- second call still finds nothing, still answers the receiver, and still says nothing.

local function idempotence(sub, t)
  local n, bad = 0, {}
  for _, c in ipairs({{"sub:off", sub}, {"timer:cancel", t}}) do
    local verb, obj = c[1], c[2]
    if obj == nil then
      bad[#bad + 1] = verb .. " -> <the first call never ran>"
    else
      local ok, r = pcall(function()
        if verb == "sub:off" then return obj:off() end
        return obj:cancel()
      end)
      if ok and (r == obj) then n = n + 1 else bad[#bad + 1] = verb .. " -> " .. tostring(r) end
    end
  end
  check(n == 2, "a second sub:off() and a second timer:cancel() each answer the receiver again and"
        .. " raise nothing (" .. n .. "/2)", table.concat(bad, ", "))
end

-- ---- where the sweep stopped -------------------------------------------------------------------------
--
-- ov:destroy() is deliberately left answering nil: the HUD painter's ending is replaced wholesale one
-- feature on, and fixing a return on a verb that is about to go is work done twice.

local function overlaySection()
  local ov = hafen.ui():overlay()
  local r = ov:destroy()
  check(r == nil, "ov:destroy() still answers nil -- the sweep stopped where it was told", r)
end

local function run()
  pass, fail, manual = 0, 0, 0
  chain = {}
  ended = {n = 0, bad = {}}

  manualCheck("right-click a tree, a stone or yourself to open a radial menu within the next 20 seconds,"
              .. " then read the lines below",
              "the menu is dismissed with nothing picked, and the seven-ending line scores 7/7")

  local sub = section("subscription", subSection)
  local t = section("timer", timerSection)
  section("widget", widgetSection)
  section("grab", grabSection)
  section("request", httpSection)

  local tries = 0
  local function poll()
    local fm = menu()
    if (fm ~= nil) or (tries >= 40) then
      section("menu", function() menuSection(fm) end)
      chainVerdict()
      check(ended.n == 3, "each of the three whose end is readable back has ENDED, not only chained ("
            .. ended.n .. "/3)", table.concat(ended.bad, ", "))
      section("idempotence", function() idempotence(sub, t) end)
      section("overlay", overlaySection)
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      return
    end
    tries = tries + 1
    hafen.timer():after(0.5, poll)
  end
  poll()
end

hafen.slash():on("t087-2", run)               -- the only way in: a suite does not start itself
