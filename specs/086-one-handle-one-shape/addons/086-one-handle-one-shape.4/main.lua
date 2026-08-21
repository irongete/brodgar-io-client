-- 086.4 -- a timer answers for itself. Self-checking suite.
--
-- hafen.timer() is a collection, and :list(pred) / :count(pred) / :find(pred) call pred with each
-- handle -- over a handle that carried :cancel() and nothing else. So the documented filter could test
-- identity and nothing else, which == already answers: surface that existed and nothing could drive.
--
-- THE CLAIM IS THAT THE FILTER NOW MEANS SOMETHING. A timer answers :interval(), :repeats(), :due(),
-- :alive() and :info(); it is userdata, so an addon cannot delete its own :cancel() and a typo raises
-- naming the vocabulary; and :every(0, fn) repeats, which is what a repeating timer has always been
-- documented to do.

local pass, fail, manual = 0, 0, 0

-- A body that has to exist and has to do nothing: every check here is about the handle, not the run.
local function noop() end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, arg)
  local ok, r = pcall(fn, arg)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

local function repeating(t) return t:repeats() end

-- ---- the filter -------------------------------------------------------------------------------------
--
-- The claim the row exists for: with one repeating timer and one one-shot scheduled, a predicate over
-- :repeats() picks out exactly one of them -- and the one it picks is the very handle :every handed
-- back, by ==, so the collection is still answering by identity as well as by value.
local function filterSection()
  local every = hafen.timer():every(5, noop)
  local once = hafen.timer():after(3, noop)

  local repeaters, total = hafen.timer():count(repeating), hafen.timer():count()
  check((repeaters == 1) and (total == 2),
        "hafen.timer():count(function(t) return t:repeats() end) is 1 of the 2 scheduled",
        tostring(repeaters) .. " of " .. tostring(total))

  check(hafen.timer():find(repeating) == every,
        "the same predicate through :find hands back the very handle :every gave you",
        tostring(hafen.timer():find(repeating)))
  return {every = every, once = once}
end

-- ---- the vocabulary ---------------------------------------------------------------------------------
--
-- The five reads, on a repeating timer and a one-shot at once, because half of them only mean anything
-- when the two disagree.
local function readsSection(t)
  local every, once = t.every, t.once

  check((every:interval() == 5) and (once:interval() == 0),
        "t:interval() is the period in seconds, and 0 for a one-shot, which has none (5 / 0)",
        tostring(every:interval()) .. " / " .. tostring(once:interval()))

  local d1, d2 = every:due(), once:due()
  check((type(d1) == "number") and (d1 >= 0) and (d1 <= 5)
          and (type(d2) == "number") and (d2 >= 0) and (d2 <= 3),
        "t:due() is the seconds until it next runs, inside the delay it was scheduled with",
        tostring(d1) .. " / " .. tostring(d2))

  local i = once:info()
  check((i ~= nil) and (i.interval == 0) and (i.repeats == false) and (type(i.due) == "number")
          and (i.alive == true),
        "t:info() carries the interval, whether it repeats, when it is due and whether it is alive",
        (i == nil) and "nil" or (tostring(i.interval) .. " / " .. tostring(i.repeats) .. " / "
          .. tostring(i.due) .. " / " .. tostring(i.alive)))

  -- One cancel, and everything that reads the state has to agree with it -- including :due(), which
  -- has no answer for a timer that is not going to run.
  local wasAlive = every:alive()
  every:cancel()
  every:cancel()                                  -- idempotent, as the page says
  check((wasAlive == true) and (every:alive() == false) and (every:due() == nil)
          and (hafen.timer():count() == 1),
        "t:alive() is true and false either side of t:cancel(), and a dead timer is due nothing",
        tostring(every:alive()) .. " due=" .. tostring(every:due())
          .. " left=" .. tostring(hafen.timer():count()))

  check((tostring(once) == "Timer(after 3s)") and (tostring(every) == "Timer(every 5s, cancelled)"),
        "tostring(t) names the timer rather than table: 0x...",
        tostring(once) .. " / " .. tostring(every))
end

-- ---- what being userdata buys -------------------------------------------------------------------------
--
-- Two mistakes that used to be silent: deleting the verb that stops your own timer, which left the
-- resource running with nothing able to end it, and a typo, which read nil and failed one call later
-- as "attempt to call a nil value", naming neither the verb nor the line that wrote it.
local function shapeSection(t)
  local once = t.once
  local wrote = said(function() once.cancel = nil end)
  check((wrote ~= nil) and (wrote:find("userdata", 1, true) ~= nil) and (once:alive() == true),
        "t.cancel = nil is refused: an addon cannot break its own teardown",
        wrote or "<no error>")

  local typo = said(function() return once:nosuchverb() end)
  check((typo ~= nil) and (typo:find(":cancel()", 1, true) ~= nil)
          and (typo:find(":repeats()", 1, true) ~= nil),
        "t:nosuchverb() raises naming the vocabulary",
        typo or "<no error>")
end

-- ---- the refusal that was already there -------------------------------------------------------------
--
-- A delay that came back nil from a config table is the commonest mistake at this call, and it gets the
-- house's own nil sentence rather than a type message about what a number is.
local function refusalSection()
  local msg = said(function() return hafen.timer():after(nil, noop) end)
  check((msg ~= nil) and (msg:find("must not be nil", 1, true) ~= nil)
          and (msg:find("seconds", 1, true) ~= nil),
        "hafen.timer():after(nil, fn) gives the house nil refusal, naming seconds",
        msg or "<no error>")
end

-- ---- :every(0, fn) ----------------------------------------------------------------------------------
--
-- A zero period is a repeating timer that is due again the moment it has run, which is once a tick.
-- It was stored indistinguishably from :after(0, fn), fired once and was dropped -- so the check is a
-- counter read a second later, by a timer of the other kind, which by then has itself fired and says so.
local function everyZero(rest)
  local ticks = 0
  local zero = hafen.timer():every(0, function() ticks = ticks + 1 end)
  local shot = hafen.timer():after(0.1, noop)

  hafen.timer():after(1, function()
    zero:cancel()
    check(ticks > 1,
          ("hafen.timer():every(0, fn) repeats -- it ran %d times in a second"):format(ticks),
          ticks .. " run(s)")
    check((shot:alive() == false) and (tostring(shot) == "Timer(after 0.1s, fired)"),
          "a one-shot that has run reads dead, and says which of the two it was",
          tostring(shot) .. " alive=" .. tostring(shot:alive()))
    rest()
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0
  for _, t in ipairs(hafen.timer():list()) do t:cancel() end   -- a previous run leaves nothing running

  local t = section("filter", filterSection)
  if t then
    section("reads", readsSection, t)
    section("shape", shapeSection, t)
  end
  section("refusal", refusalSection)

  everyZero(function()
    if t and t.once then t.once:cancel() end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():on("t086-4", run)               -- the only way in: a suite does not start itself
