-- 097 -- three edges, three words. The whole feature under one command.
--
-- WHAT WAS WRONG. The event vocabulary used FIVE word-pairs for THREE edges. Something appearing was
-- ...Added, or ...Opened, or `appear`. Something going was ...Removed, or ...Destroyed, or ...Closed, or
-- ...Done, or `Destroy`, or `disappear`. Six spellings for one edge. Every one of those costs a lookup and
-- then throws, which is cheap.
--
-- ONE OF THEM DID NOT THROW. QuestDone fired for a quest that FAILED as well as one completed, and only
-- q:status() told the two apart -- so a handler that read the name and congratulated the player ran on
-- failure too. No error, no warning, wrong behaviour on half its firings. That is the reason this feature
-- exists; the rest is the vocabulary it was hiding in.
--
-- WHY THE WORD HAD TO GO RATHER THAN NARROW. CLAUDE.md: a rename is free and a reshape is not. Keeping
-- QuestDone for the success half would leave every handler already written resolving to a key that quietly
-- stopped running for half its firings -- exactly what a hard cut exists to prevent. Retired keys on NAMES,
-- so the name is what moves: QuestCompleted and QuestFailed, and QuestDone raises naming both.

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

local finished = false
local function finish()
  if finished then return end
  finished = true
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- Every retired spelling must RAISE and must name its replacement in the message. Both halves matter: an
-- addon written against the old word has to stop, and the person reading the stop has to be told where the
-- word went, or the hard cut is just a break.
local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and (err:find(want, 1, true) ~= nil) then n = n + 1
    else why = why or (label .. " -> " .. err) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n) end
  return g
end

local function scored()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    local ok, r = pcall(fn)
    if ok and (r == nil) then return end
    total = total + 1
    if ok and r then n = n + 1 else why = why or (label .. " -> " .. tostring(r)) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n) end
  return g
end

local NOOP = function() end
local SEL = "window[title=__097_matches_nothing__]"     -- a selector no live tree can satisfy

local function run()
  local subs = {}
  local function keep(s) subs[#subs + 1] = s; return s end

  -------------------------------------------------------------------------------------------------
  -- The six bus keys the three edges produce. Subscribing is the whole proof that the key set moved:
  -- BUS_KEYS is CLOSED, so an unknown key throws rather than being accepted and never firing.
  -------------------------------------------------------------------------------------------------
  section("the new keys", function()
    local g = scored()
    for _, k in ipairs({"SessionRemoved", "FlowerMenuAdded", "FlowerMenuRemoved",
                        "MarkerChanged", "QuestCompleted", "QuestFailed"}) do
      g.want(k, function()
        local sub = hafen.event():on(k, NOOP)
        keep(sub)
        return sub ~= nil
      end)
    end
    g.done("the bus answers all six keys of the three edges")
  end)

  -------------------------------------------------------------------------------------------------
  -- and every spelling they replaced is gone, naming where it went.
  -------------------------------------------------------------------------------------------------
  section("the bus retirements", function()
    local g = refusals()
    g.ask("SessionDestroyed", function() return hafen.event():on("SessionDestroyed", NOOP) end, "SessionRemoved")
    g.ask("FlowerMenuOpened", function() return hafen.event():on("FlowerMenuOpened", NOOP) end, "FlowerMenuAdded")
    g.ask("FlowerMenuClosed", function() return hafen.event():on("FlowerMenuClosed", NOOP) end, "FlowerMenuRemoved")
    g.ask("MarkersChanged", function() return hafen.event():on("MarkersChanged", NOOP) end, "MarkerChanged")
    g.done("every retired bus spelling raises naming its replacement")
  end)

  -------------------------------------------------------------------------------------------------
  -- THE POINT OF THE FEATURE. QuestDone is the one retirement that is not a rename, so its message has
  -- to carry three things a rename's does not: that it is gone, BOTH keys that replace it, and WHY --
  -- because the caller's handler body is what needs rewriting, not just the string above it.
  -------------------------------------------------------------------------------------------------
  section("the quest split", function()
    local g = refusals()
    g.ask("names the success key", function() return hafen.event():on("QuestDone", NOOP) end, "QuestCompleted")
    g.ask("names the failure key", function() return hafen.event():on("QuestDone", NOOP) end, "QuestFailed")
    g.ask("says it fired on failure", function() return hafen.event():on("QuestDone", NOOP) end, "FAILED")
    g.done("QuestDone raises naming both keys and the silent wrong answer it made")
  end)

  -------------------------------------------------------------------------------------------------
  -- The two keys a WIDGET answers under a word of their own. Tick and the addon's Update were the same
  -- edge one object apart -- both fire once a frame and both hand the handler the same dt.
  -------------------------------------------------------------------------------------------------
  section("the widget's two", function()
    local w = hafen.ui():window():title("097"):size(120, 40)
    local g = scored()
    g.want("Update is a surface key", function() return w:on("Update", NOOP) ~= nil end)
    g.want("Removed is a universal key", function() return w:on("Removed", NOOP) ~= nil end)
    g.done("a surface answers Update and Removed")

    local r = refusals()
    r.ask("Tick", function() return w:on("Tick", NOOP) end, "Update")
    r.ask("Destroy", function() return w:on("Destroy", NOOP) end, "Removed")
    r.ask("Tick says both are one frame", function() return w:on("Tick", NOOP) end, "dt")
    r.done("the widget's retired keys raise naming their replacements")
    w:destroy()
  end)

  -------------------------------------------------------------------------------------------------
  -- The selector watch was the last lower-case pair in the API, and it is a THREE-argument door, so its
  -- retirement rows write their own opening rather than borrow an arity they have not got.
  -------------------------------------------------------------------------------------------------
  section("the selector watch", function()
    local ui = hafen.session():current():ui()
    local g = scored()
    g.want("Added", function() return keep(ui:on(SEL, "Added", NOOP)) ~= nil end)
    g.want("Removed", function() return keep(ui:on(SEL, "Removed", NOOP)) ~= nil end)
    g.done("the watch answers Added and Removed")

    local r = refusals()
    r.ask("appear", function() return ui:on(SEL, "appear", NOOP) end, "Added")
    r.ask("disappear", function() return ui:on(SEL, "disappear", NOOP) end, "Removed")
    r.ask("the message names the three-argument call", function()
      return ui:on(SEL, "appear", NOOP) end, "on(selector, event, fn)")
    r.done("the lower-case pair raises naming the three edges")
  end)

  -------------------------------------------------------------------------------------------------
  -- A near miss inside the session family is refused naming all four, and one of the four moved.
  -------------------------------------------------------------------------------------------------
  section("the near-miss hint", function()
    local g = refusals()
    g.ask("an unknown session key", function() return hafen.event():on("SessionGone", NOOP) end, "SessionRemoved")
    g.done("the session near-miss hint names the new spelling")
  end)

  -------------------------------------------------------------------------------------------------
  -- Both frame edges FIRE, and hand the same dt. This is the half of the Tick/Update finding a name
  -- check cannot reach: that the two really were one edge, so collapsing them lost nothing.
  -------------------------------------------------------------------------------------------------
  -- EVERYTHING FROM HERE IS INSIDE A pcall. A throw outside one takes the [summary] with it, and a run
  -- that prints checks and no verdict is a run nobody can paste back.
  local busDt, wdgDt = nil, nil
  local w2 = nil
  local armed = section("the frame arming", function()
    w2 = hafen.ui():window():title("097 frames"):size(120, 40)
    keep(hafen.event():on("Update", function(dt) busDt = busDt or dt end))
    w2:on("Update", function(dt) wdgDt = wdgDt or dt end)
    return true
  end)
  if not armed then
    for _, s in ipairs(subs) do pcall(function() s:off() end) end
    return finish()
  end

  local ok = pcall(function() hafen.timer():after(1, function()
    section("the frame edge", function()
      local g = scored()
      g.want("the addon's Update fired", function() return type(busDt) == "number" end)
      g.want("the surface's Update fired", function() return type(wdgDt) == "number" end)
      g.want("both hand a dt in seconds", function()
        return (busDt or -1) >= 0 and (busDt or 1e9) < 1 and (wdgDt or -1) >= 0 and (wdgDt or 1e9) < 1
      end)
      g.done("one word, both levels, and the same dt under it")
    end)
    pcall(function() w2:destroy() end)
    for _, s in ipairs(subs) do pcall(function() s:off() end) end
    manualCheck("finish or fail a quest -- the one thing here no program can cause, since the outcome is"
                  .. " the server's",
                "exactly one event, QuestCompleted for a completed quest or QuestFailed for a failed one,"
                  .. " and never the other. Before 097 both were QuestDone and only q:status() said which")
    finish()
  end) end)
  if not ok then
    check(false, "the frame edge", "the timer could not be armed")
    pcall(function() w2:destroy() end)
    for _, s in ipairs(subs) do pcall(function() s:off() end) end
    finish()
  end
end

hafen.slash():on("t097", run)                  -- the only way in: a suite does not start itself
