-- 042.10 -- the layout cascade: redrive() is deleted, D-091 is superseded by D-181. Self-checking
-- suite; see specs/addons/TESTING.md and specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. Layout.poll()/redrive() are gone. An anchored widget now re-derives on its
-- OWN inputs' events instead of a per-tick fold over every anchor: the target's size (Widget.resize,
-- the new core tap) and a window packing itself (pack() -> resize()) ride that one tap; a target's
-- drag rides a Widget.listen(MouseMoveEvent) installed on that one target; a target's own removal
-- rides M1 (dispatchRemoved), which also drops the derived record and, if nothing else names the
-- same target, its drag listener.
--
-- WHAT THIS SUITE CAN AUTOMATE, entirely with its own addon-built windows (no server round trip
-- needed for any of this): the initial anchor resolution, following a target moved through the API,
-- following a target that PACKS ITSELF (via pack(), which unlike :size()/:position() makes no
-- explicit Layout.moved() call of its own -- so a follower re-deriving here can only be the new
-- Widget.resize core tap, not the pre-existing explicit call the position/size verbs already make).
-- The resize tap is marshalled onto the NEXT tick (resize() is not guaranteed to run on the UI
-- thread, same as the removal seam), so the pack() check below waits one, exactly like 042.9 waits
-- for the removal-driven disappear.
--
-- WHAT THIS SUITE CANNOT AUTOMATE: a real mouse drag (no synthetic input door) and the game CLIENT's
-- own outer window resizing -- both [manual], and both THE 036.3 "hold"/"drop" SHAPE so neither ever
-- points outside this addon's own windows: ":t042-10 hold" parks a target (for the drag) and TWO
-- followers of its own -- one anchored to the target (the drag half of M4), one anchored to the
-- SCREEN (the other half) -- and prints exactly what to touch and expect; ":t042-10 drop" tears it
-- all back down. Two separate followers, not one, because "resize" is ambiguous on a single parked
-- pair: the target itself has no resize grip (H&H windows never do without dragsize, which this
-- addon's own builder does not expose), so the only "resize" a maintainer CAN act on here is the
-- game client's own outer window -- named explicitly, never left to be misread as a widget inside it.

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

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end
local function pt(x, y) return ("%d,%d"):format(x, y) end

-- The follower's expected root position for anchor { to = target, at = "topright", offset = {dx, 0} }:
-- the FOLLOWER's own topright corner lands on the TARGET's topright corner, plus the offset -- corner
-- ON corner (Layout.Anchor's own contract), so the follower's own width comes off the target's.
local function wantTopright(target, follower, dx)
  local tp, tsz, fsz = target:rootPos(), target:size(), follower:size()
  return pt(tp.x + tsz.x + dx - fsz.x, tp.y)
end

-- One sheet for the whole addon: the automated run and hold/drop all share it, exactly like 036.3.
local sheet = hafen.ui():sheet()

-- Two SEPARATE parked windows for the two SEPARATE M4 inputs a mouse and a keyboard can actually
-- drive -- a widget-to-widget anchor (the drag) and a widget-to-SCREEN anchor (the client's own
-- outer window resizing, 036.3's own pattern) -- so each [manual] step names exactly one thing to
-- touch and there is no room to read "resize" as "resize the parked window" (it has no resize grip;
-- H&H windows never do without dragsize, which this addon's own builder does not expose).
local HOLD_TARGET = "t042-10 hold target"
local HOLD_DRAG_FOLLOWER = "t042-10 hold drag-follower"
local HOLD_SCREEN_FOLLOWER = "t042-10 hold screen-follower"
local dragSel = "window[title=" .. HOLD_DRAG_FOLLOWER .. "]"
local screenSel = "window[title=" .. HOLD_SCREEN_FOLLOWER .. "]"

local function run(args)
  local mode = args and args[1]
  pass, fail, manual = 0, 0, 0   -- so a re-run reports its own counts

  if mode == "hold" then
    sheet:drop()
    local target = hafen.ui():window():title(HOLD_TARGET):size(120, 80):position(300, 300)
    hafen.ui():window():title(HOLD_DRAG_FOLLOWER):size(70, 30):position(0, 0)
    hafen.ui():window():title(HOLD_SCREEN_FOLLOWER):size(70, 30):position(0, 0)
    sheet:load{
      [dragSel] = { anchor = { to = target, at = "topright", offset = {8, 0} } },
      [screenSel] = { anchor = { to = "screen", at = "bottomright", offset = {-8, -8} } },
    }:install()
    hafen.log():write("[manual] drag \"" .. HOLD_TARGET .. "\" by its caption bar (left-click the bar and move"
      .. " the mouse, same as any window) -- expect: \"" .. HOLD_DRAG_FOLLOWER .. "\" tracks its topright"
      .. " corner on every pointer move, live, not a frame later")
    hafen.log():write("[manual] from your DESKTOP -- not inside the game -- resize the game CLIENT's own"
      .. " outer window: drag its edge/corner the way you would any other application (un-maximize it first"
      .. " if it is maximized) -- expect: \"" .. HOLD_SCREEN_FOLLOWER .. "\" stays 8px in from the SCREEN's"
      .. " bottom-right corner the whole time, snapping to the new size immediately, once -- then run"
      .. " \":t042-10 drop\" to clean up")
    return
  end
  if mode == "drop" then
    sheet:drop()
    hafen.log():write("[manual] the parked windows are gone -- the client is back to stock")
    return
  end

  sheet:drop()   -- start from a client this suite is holding nothing on

  local FOLLOWER = "t042-10 follower"
  local sel = "window[title=" .. FOLLOWER .. "]"

  local target = hafen.ui():window():title("t042-10 target"):size(60, 40):position(260, 220)
  local follower = hafen.ui():window():title(FOLLOWER):size(40, 20):position(0, 0)
  sheet:load{ [sel] = { anchor = { to = target, at = "topright", offset = {6, 0} } } }:install()

  check(xy(follower:rootPos()) == wantTopright(target, follower, 6),
        "the follower lands on the target's topright corner as soon as the rule installs",
        xy(follower:rootPos()))

  target:position(340, 260)
  check(xy(follower:rootPos()) == wantTopright(target, follower, 6),
        "moving the target through the API re-derives the follower in the same call", xy(follower:rootPos()))

  -- Grow the target's content with a spacer, then PACK it -- pack() makes no explicit Layout.moved()
  -- call of its own (unlike :size()/:position()), so a follower catching up here can only be the new
  -- Widget.resize core tap (042.10), marshalled onto the next tick.
  local beforeSz = target:size()
  hafen.ui():widget():parent(target):position(0, 0):size(280, 200)
  target:pack()
  check((target:size().x > beforeSz.x) or (target:size().y > beforeSz.y),
        "packing the target after growing its content actually resized it (this half is synchronous)",
        xy(target:size()))

  hafen.timer():after(0.2, function()
    check(xy(follower:rootPos()) == wantTopright(target, follower, 6),
          "...and the follower re-derived to the PACKED size once the resize seam drained, on the next tick",
          xy(follower:rootPos()))

    local held = xy(follower:rootPos())
    target:destroy()
    check(xy(follower:rootPos()) == held,
          "a target that left the tree leaves the follower right where it was (the anchor goes inert, D-091's own invariant kept)",
          xy(follower:rootPos()))

    follower:destroy()   -- its derived record and (the only anchor on this target) its drag listener are
                          -- dropped at M1 -- see the hold/drop [manual] lines below for the listener half,
                          -- which is not observable through hafen.*

    hafen.timer():after(0.2, function()
      -- A fresh pair proves nothing was left broken by the departure above: same selector grammar, a
      -- new live target, resolving cleanly.
      local target2 = hafen.ui():window():title("t042-10 target 2"):size(60, 40):position(400, 300)
      local follower2 = hafen.ui():window():title(FOLLOWER):size(40, 20):position(0, 0)
      sheet:load{ [sel] = { anchor = { to = target2, at = "topright", offset = {6, 0} } } }:install()
      check(xy(follower2:rootPos()) == wantTopright(target2, follower2, 6),
            "a fresh anchor on a fresh target resolves cleanly after the first pair's teardown",
            xy(follower2:rootPos()))

      hafen.timer():after(0.3, function()
        check(xy(follower2:rootPos()) == wantTopright(target2, follower2, 6),
              "idle: nothing moved while nothing changed", xy(follower2:rootPos()))

        sheet:drop()
        target2:destroy()
        follower2:destroy()

        manualCheck("run \":t042-10 hold\", then drag the parked target by its caption bar (widget-to-widget"
          .. " anchor, the drag half of M4)",
          "the parked drag-follower tracks the target's topright corner on every pointer move, live, not a"
          .. " frame later")
        manualCheck("with \":t042-10 hold\" still parked, resize the game CLIENT's own outer window from your"
          .. " DESKTOP -- not any window inside the game (widget-to-SCREEN anchor, the other half of M4);"
          .. " then run \":t042-10 drop\" to clean up",
          "the parked screen-follower stays 8px in from the screen's bottom-right corner throughout, snapping"
          .. " to the new size immediately, once")
        manualCheck("run \":t042-10 hold\" again, then :reload the addon layer while that rule is installed",
          "no error appears in the log during the reload -- the reload tears this addon's own hold windows"
          .. " down along with everything else it owns, so \":t042-10 hold\" once more afterward parks a"
          .. " fresh set exactly as before, with nothing left over from the reloaded one")

        hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      end)
    end)
  end)
end

hafen.slash():register("t042-10", run)   -- the only way in: a suite does not start itself
