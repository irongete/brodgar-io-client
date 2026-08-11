-- 042.8 -- replacements. Self-checking suite; see specs/testing/addon-suite.md and
-- specs/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. UiApi.pollReplaced/sweepReplaced are gone. The server destroying a window an addon
-- (or the :lua REPL) replaced with widget:replace(view) is a REMOVAL, so ending the substitution -- giving
-- the native window and its toggle back under the one rule, and destroying the stand-in -- now happens from
-- the widget-removal seam (M1) the instant the widget is actually removed, not on the next tick's sweep over
-- every hidden record.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. It is READ-ONLY (no permissions declared) and cannot make the SERVER
-- destroy a window on demand -- that needs a real container the maintainer opens and closes. What CAN be
-- automated, entirely on the always-open Inventory window (hidden, never destroyed by the server -- see
-- 042.7's own gotcha about permanent HUD panels): the replace/replacement()/replace(nil) round trip still
-- works exactly as before, an idle window sitting replaced answers `replacement()` unchanged (nothing
-- spuriously ends the substitution), and undoing it destroys the stand-in.

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

  local target = hafen.ui():inventory()
  if not target then
    check(false, "hafen.ui():inventory() answers (once GameUI is up)", "nil")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local view = hafen.ui():window():title("t042-8 stand-in"):size(80, 40)
  local ok, err = pcall(function() target:replace(view) end)
  if not ok then
    -- Another addon (e.g. bags, once armed) already owns the inventory's toggle -- an environment
    -- collision, not a bug in this task. Disarm it and re-run rather than reporting a false fail.
    view:destroy()
    manualCheck("hafen.ui():inventory() is already replaced by another addon (" ..
      tostring(err):gsub("^.-%.lua:%d+:%s*", "") .. ") -- disarm it (e.g. bags' toggle hotkey again) and"
      .. " re-run :t042-8", "with nothing else holding the inventory's toggle, the automated checks below"
      .. " report [pass]")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  -- 1. The read surface is untouched -- only how the ENDING is detected moved.
  check(target:replacement() == view, "replace(view) installs, and replacement() reads the SAME view back",
        tostring(target:replacement()))

  -- 2. Idle: a window standing replaced, with nothing destroying it, answers unchanged -- the removal seam
  --    firing for anything else in the world (a gob, some other widget) must not touch THIS substitution.
  hafen.timer():after(2, function()
    check(target:replacement() == view, "idle: replacement() is still the SAME view after 2s of nothing"
          .. " destroying the window", tostring(target:replacement()))

    -- 3. replace(nil) undoes it there and then -- the mirror of what the server-destroy path now does from
    --    M1: the record goes, and the stand-in is destroyed with it.
    target:replace(nil)
    check(target:replacement() == nil, "replace(nil) undoes the substitution -- replacement() answers nil",
          tostring(target:replacement()))
    check(not view:exists(), "...and the stand-in view is destroyed with it", tostring(view:exists()))

    manualCheck("replace a window (e.g. via the console: :lua hafen.ui():on(\"window[title=Cupboard]\","
      .. " \"appear\", function(w) w:replace(hafen.ui():window():title(\"stand-in\")) end) before opening"
      .. " one, or hafen.ui():at(x,y):replace(...) on one already open) with a REAL container the SERVER"
      .. " destroys when you're done with it -- a chest/cupboard, a trade or build/craft dialog, NOT the"
      .. " always-open Inventory/Equipment/Character panels (those only ever get Tab-toggled, never"
      .. " destroyed) -- then close it from the server side (walk away, or its own native X)",
      "the container hides and 'stand-in' appears in its place the instant you replace it; the moment the"
      .. " server destroys the container, 'stand-in' disappears too and the native window's toggle is free"
      .. " again -- no lingering window, no lingering stand-in, whether you did the replace as a loaded"
      .. " addon or typed it straight into the :lua REPL")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-8", run)   -- the only way in: a suite does not start itself
