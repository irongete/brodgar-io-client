-- 042.9 -- selector watches. Self-checking suite; see specs/testing/addon-suite.md and
-- specs/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. UiApi.pollSelectorWatches is gone. `disappear` now fires from the
-- widget-removal seam (M1) the instant a tracked widget leaves the tree, not on the next tick's
-- sweep. The [title=]/[res=] refiner's bounded re-check now wakes on the window-caption uimsg
-- (CharApi.dispatchUimsg -> UiApi.markCaptionChanged, drained on the tick), not on a per-tick
-- countdown.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. The task's whole point is the LATE [title=] case -- a SERVER
-- widget whose caption streams in a tick after placement -- and this suite is READ-ONLY, so it
-- cannot make the server open one. What it CAN automate, on a window it builds and destroys
-- itself (title known at construction, via the builder's own setter -- so the late-refiner path
-- itself is not exercised, but everything else this task touches is): `appear` matching a
-- selector, `disappear` firing exactly once off the removal seam, a selector that never matches
-- staying silent, and idle producing nothing extra.

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

local function run()
  pass, fail, manual = 0, 0, 0

  local TITLE = "t042-9 probe"
  local SEL = "window[title=" .. TITLE .. "]"

  -- The window is built (and already in the tree structurally -- hafen.ui():window() attaches
  -- synchronously) BEFORE we subscribe, so the "appear" below exercises the same "already open"
  -- scan every subscription does (event.md: "appear also covers what is already open").
  local win = hafen.ui():window():title(TITLE):size(40, 20)

  local appearCount, disappearCount = 0, 0
  local appearHandle = hafen.ui():on(SEL, "appear", function(w) appearCount = appearCount + 1 end)
  local disappearHandle = hafen.ui():on(SEL, "disappear", function(w) disappearCount = disappearCount + 1 end)

  local neverCount = 0
  local neverHandle = hafen.ui():on("window[title=zzz_t042-9_never_zzz]", "appear",
    function(w) neverCount = neverCount + 1 end)

  check(appearCount == 1, "appear fired exactly once, for the window already in the tree at subscribe time",
        appearCount)
  check(disappearCount == 0, "disappear has not fired while the window still stands", disappearCount)
  check(neverCount == 0, "a selector that never matches fires no appear events", neverCount)

  hafen.timer():after(0.3, function()
    check(appearCount == 1, "idle: appear did not fire again while nothing changed", appearCount)
    check(neverCount == 0, "idle: the never-matching selector is still silent", neverCount)

    win:destroy()   -- Widget.destroy() -> remove() synchronously (this window is not a fading one --
                     -- destroy() bypasses Window.reqdestroy()'s fade path); the removal seam still
                     -- only DRAINS on the next tick, so the event itself lands a moment later.

    hafen.timer():after(0.2, function()
      check(disappearCount == 1, "disappear fired exactly once when the window was destroyed", disappearCount)
      check(appearCount == 1, "...and appear did not fire again for the same widget", appearCount)

      appearHandle:remove()
      disappearHandle:remove()
      neverHandle:remove()

      hafen.timer():after(0.1, function()
        check(disappearCount == 1, "after removing the handles, no further events land for the dead widget",
              disappearCount)

        manualCheck(
          "with the console (:lua), register hafen.ui():on(\"window[title=Cupboard]\", \"appear\", " ..
          "function(w) hafen.log():write(\"t042-9: matched\") end) BEFORE opening a cupboard, then open " ..
          "one and close it",
          "\"t042-9: matched\" is logged exactly once, a tick or two after the window opens -- not on " ..
          "the very same frame the window appears (its caption streams in by uimsg after placement) -- " ..
          "and not again while it sits open or when you close it"
        )

        hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      end)
    end)
  end)
end

hafen.slash():register("t042-9", run)   -- the only way in: a suite does not start itself
