-- 072.1 -- the monitor is the widget's own, not the screen's. Self-checking suite.
--
-- This task rewrote WHICH monitor the engine takes while it writes or reads a widget: the monitor of the
-- UI that widget's own tree belongs to, instead of an ambient field naming the session on screen. Nothing
-- an addon can see was meant to change, so every assertion below is one an earlier feature already made,
-- run again through the very verbs whose implementations moved. A failure here is a bug in the conversion.

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

-- A refusal is a check: the call must fail, and fail SAYING why. Matched against the RAW error and
-- trimmed only for the report -- a message carrying a traceback has a second `.lua:NN:` in it, and
-- trimming first would let the pattern eat the very words being looked for.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  local raw = ok and "<no error>" or tostring(err)
  check((not ok) and (raw:find(wantMsg, 1, true) ~= nil), what, (raw:gsub("^.-%.lua:%d+:%s*", "")))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(p)
  return p and ("{" .. tostring(p.x) .. "," .. tostring(p.y) .. "}") or "nil"
end

local function run()
  -- Built from scratch: no other suite is assumed to have been run, and nothing here reads a widget
  -- another one left behind.
  local win = hafen.ui():window()
  check(win ~= nil and win:type() == "Window",
        "hafen.ui():window() hands back a Window", win and win:type())

  win:title("072.1")
  check(win:title() == "072.1", "a window's caption round-trips through :title(s)", win:title())

  win:position(140, 120)
  local at = win:position()
  check(at and (at.x == 140) and (at.y == 120), "a window's place round-trips through :position(x, y)", xy(at))

  -- The window hangs off the root, so where it is within its parent IS where it is in root coords: two
  -- reads that took the monitor separately, and they have to agree.
  local rp = win:rootPos()
  check(rp and (rp.x == at.x) and (rp.y == at.y),
        ":rootPos() agrees with :position() for a window hanging off the root", xy(rp))

  win:visible(false)
  local gone = win:visible()
  win:visible(true)
  check((gone == false) and (win:visible() == true), "a window you built hides and comes back",
        tostring(gone) .. " then " .. tostring(win:visible()))

  -- A bare widget carries no chrome, so a size write is the whole box and reads back exactly.
  local bare = hafen.ui():widget():size(90, 30)
  local bs = bare:size()
  check(bs and (bs.x == 90) and (bs.y == 30), "a bare widget's box round-trips through :size(w, h)", xy(bs))

  -- A window's :size(w, h) writes the CONTENT and :size() reads the OUTER box, so the pair is an
  -- inequality rather than an equality -- and a second read of an untouched window must not move.
  win:size(160, 80)
  local ws, again = win:size(), win:size()
  check(ws and (ws.x >= 160) and (ws.y >= 80) and (ws.x == again.x) and (ws.y == again.y),
        "a window's :size(w, h) reads back a settled outer box around the content it was given", xy(ws))

  local before = #win:children()
  local corner = hafen.ui():button():parent(win):position(120, 46):text("size")
  check(#win:children() == before + 1,
        ":parent(win) puts a control in that window and :children() counts it",
        tostring(before) .. " -> " .. tostring(#win:children()))
  win:resizable(corner)

  -- The refusal has to name the argument that was missing. The trap it exists for is the opposite
  -- reading: a write whose value went missing must never quietly become the read of the same name.
  refuses(":position(nil, 10) is refused, naming what was missing",
          function() win:position(nil, 10) end, "number expected, got nil")
  local still = win:position()
  check(still and (still.x == 140) and (still.y == 120),
        "...and the refused write moved nothing", xy(still))

  -- The borrowed path: one of the CLIENT's own widgets, hidden on this addon's restore list and given
  -- back by hand. Its tree is the client's, and its monitor is what this task took from the widget.
  local inv = hafen.ui():inventory()
  if inv then
    inv:visible(false)
    local hidden = inv:visible()
    inv:visible(true)
    check((hidden == false) and (inv:visible() == true),
          "one of the client's own widgets hides and comes back",
          tostring(hidden) .. " then " .. tostring(inv:visible()))
  else
    check(false, "one of the client's own widgets hides and comes back",
          "no main inventory -- run this with a character in the world")
  end

  manualCheck("with the 072.1 window on screen, drag it by its caption, then press the 'size' button in"
              .. " its bottom-right and drag that",
              "both follow the mouse exactly as any client window does -- no stutter, no snap-back, and the"
                .. " window's top-left stays put while it resizes")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t072-1", run)   -- the only way in: a suite does not start itself
