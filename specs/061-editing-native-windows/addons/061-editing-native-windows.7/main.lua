-- 061.7 — your own controls inside a native window, and widget:pack() on one. Self-checking suite.
--
-- Run it with the Options window open: that is the window the structure half is built into, and it is put
-- back exactly as it was found before the run ends.
--
-- The destroy half is asked TWICE, because one window can only answer half of it. The client's Options
-- window is never destroyed -- closing it hides it -- so the deterministic proof is a window the suite
-- builds and destroys on the spot: the adopted control is a child of the window's chrome there exactly as
-- it is in one of the client's, and it is destroy()'s own recursion that used to walk past it. The other
-- proof is on a window only the SERVER can place and only the maintainer can close, so it is armed on a
-- timer and scored over what the run reaches -- never a silent pass.

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local WAIT = 30                  -- how long a server-placed window has to open and close

local btn, box, rider, guest     -- every control this run builds
local watch, deadline            -- the appear subscription and the end of the run
local live, armed, done          -- in flight / the initial appear scan is over / the verdict is printed
local ownIn, guestIn             -- the two destroy verdicts are in
local riderGone, guestGone       -- ...and what each of them saw

local function box2(a, b)
  return (a ~= nil) and (b ~= nil) and (a.x == b.x) and (a.y == b.y)
end

local function shown(c)
  return (c == nil) and "nil" or (c.x .. "x" .. c.y)
end

-- Both halves of the destroy question have answered: clean up and print the one verdict line.
local function maybeFinish()
  if done or not (ownIn and guestIn) then
    return
  end
  done = true
  if watch then
    watch:remove()
  end
  if deadline then
    deadline:cancel()
  end
  for _, w in ipairs({btn, box, guest}) do   -- the client is left exactly as the run found it
    if w and w:exists() then
      pcall(function() w:destroy() end)
    end
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- One of the client's own windows, once it is gone: the half the suite cannot cause and cannot fake.
local function guestVerdict(why)
  if done or guestIn then
    return
  end
  guestIn = true
  if guestGone == nil then
    check(false, "a control adopted into one of the CLIENT's own windows is told when that window closes", why)
  else
    check(guestGone and not guest:exists(),
          "a control adopted into one of the CLIENT's own windows is told when that window closes",
          "fired: " .. tostring(guestGone) .. ", still there: " .. tostring(guest:exists()))
  end
  maybeFinish()
end

-- A window the SERVER placed is the only one the maintainer can really close, so that is what is taken --
-- and only one that opened AFTER this run started, since a window already up may never be touched again.
-- The control is hidden: what is under test is that it dies with the window, not that it is drawn, and a
-- button lying over a container's slots is in the way of the very click the run is waiting for.
local function appeared(w)
  if done or not armed or guest or not w:id() then
    return
  end
  guest = hafen.ui():button():text("061.7"):parent(w):position(0, 0)
  guest:visible(false)
  guestGone = false
  guest:on("Destroy", function()
    guestGone = true
    guestVerdict()
  end)
end

-- The suite's own window, destroyed on the spot: same chrome, same adopted child, same destroy().
local function ownVerdict()
  check(riderGone, "a control adopted into a window is told when that window is destroyed", riderGone)
  check(not rider:exists(), "...and it reads as gone once it has been", rider:exists())
  ownIn = true
  maybeFinish()
end

local function run()
  if live and not done then
    hafen.log():write("[fail] this suite is already waiting -- let it print its summary first")
    return
  end
  pass, fail, manual = 0, 0, 0
  btn, box, rider, guest, watch, deadline = nil, nil, nil, nil, nil, nil
  live, armed, done, ownIn, guestIn = true, false, false, false, false
  riderGone, guestGone = false, nil

  local win = hafen.ui():find("window[title=Options]")
  if not win then
    check(false, "the Options window is open", "no window[title=Options] -- open Options and re-run")
    done = true
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  -- ---- a control of your own, built INTO one of the client's windows --------------------------------
  local stock = win:size()
  btn = hafen.ui():button():text("061.7"):parent(win):position(0, 0)
  check((btn:parent() == win) and (win:info().owned == false),
        "a control built with :parent(w) hangs under one of the client's own windows",
        tostring(btn:parent()) .. ", owned=" .. tostring(win:info().owned))
  local rp, bsz = btn:rootPos(), btn:size()
  local hit = hafen.ui():at(rp.x + math.floor(bsz.x / 2), rp.y + math.floor(bsz.y / 2))
  check(hit == btn, "...and the client's own hit test finds it there, inside the content area",
        hit and (hit:type() .. " " .. tostring(hit)) or "nothing")

  -- ---- :pack() on a native window, and the size level under it --------------------------------------
  btn:position(0, stock.y)                    -- below everything the window is showing
  win:pack()
  local packed = win:size()
  check(packed.y >= stock.y + bsz.y, "...and :pack() refits the window around it",
        shown(stock) .. " -> " .. shown(packed))
  win:size(nil)
  check(box2(win:size(), stock), "the stock outer box comes back on :size(nil)", shown(win:size()))

  -- A window that packs itself around its own contents takes the call and undoes it: inert, never an
  -- error, which is the rule :size(w, h) already carries on those same windows.
  local grid = hafen.ui():inventory()
  local invw = grid and grid:parent()
  if not invw then
    check(false, "the main inventory window packs itself, so :pack() there is inert", "no inventory window")
  else
    local was = invw:size()
    local ok, err = pcall(function() invw:pack() end)
    check(ok and box2(invw:size(), was), "the main inventory window packs itself, so :pack() there is inert",
          ok and (shown(was) .. " -> " .. shown(invw:size())) or tostring(err))
    invw:size(nil)
  end
  refuses("one of the client's widgets that is not a window refuses :pack(), naming :size(w, h)",
          function() return grid:pack() end, "widget:size(w, h)")

  -- ---- the two windows the destroy half is asked of -------------------------------------------------
  box = hafen.ui():window():title("061.7"):position(120, 120):size(160, 40)
  rider = hafen.ui():button():text("061.7"):parent(box):position(4, 4)
  rider:on("Destroy", function() riderGone = true end)
  watch = hafen.ui():on("window", "appear", appeared)
  armed = true                                -- everything already open has now been offered and skipped

  manualCheck("within " .. WAIT .. "s, open a cupboard, a chest or any container the server places, then"
              .. " close it", "the window opens and closes exactly as it always does")
  deadline = hafen.timer():after(WAIT, function()
    guestVerdict("no server-placed window opened and closed within " .. WAIT .. "s")
  end)

  -- One tick later the button is ARMED, which is what makes the build-time rule assertable at all.
  hafen.timer():after(0.5, function()
    refuses("a control already on screen refuses :parent(w), naming :position(x, y)",
            function() return btn:parent(hafen.ui():root()) end, "widget:position(x, y)")
    check(not riderGone, "building a control into a window is a move: nothing reports it as destroyed", riderGone)
    btn:destroy()                             -- the Options window is left exactly as it was found
    box:destroy()                             -- ...and the rider goes with it, with no remove() of its own
    hafen.timer():after(0.5, ownVerdict)
  end)
end

hafen.slash():register("t061-7", run)   -- the only way in: a suite does not start itself
