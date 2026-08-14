-- 067.2 -- a raw argument can say which space it is in. Self-checking suite.
--
-- A "click" carries one of each: a screen pixel at argument 1 and a world point scaled by OCache.posres
-- at argument 2, adjacent and identical in shape. The two readers let the caller name the space at the
-- index they mean -- and the whole point is that ev:args() is NOT touched underneath them, because
-- ev:resend()/ev:send(t) round-trip through it and a converted snapshot would reach the server as
-- different bytes. Both halves of that are asserted below.

local pass, fail, manual = 0, 0, 0

local TILE     = 11            -- world units per tile (MCache.tilesz)
local NEAR     = 30 * TILE     -- how far from the player a click may land and still count as "near"
local WINDOW   = 12            -- seconds the walk after ev:resend() is given
local INTERVAL = 0.25          -- seconds between polls
local ARM      = 60            -- seconds the run waits for the click before giving up

-- The very constant this feature exists to delete from addons. The suite knows it because the suite is
-- proving the conversion: position(i) must be EXACTLY args[i] multiplied by it, and nothing else.
local POSRES = 11 / 1024

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

local sub, armTimer          -- the live action subscription, and the give-up timer
local running = false

local function finish()
  manualCheck("the left-click you just made on the ground",
              "the character walks there exactly as it does with no addon loaded")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  running = false
end

-- ev:resend() re-issues the ORIGINAL arguments and bypasses every action handler, so the walk that
-- follows is the walk the client would have made. Polled rather than assumed: the server answers when
-- it answers, and the run scores the distance it actually reached.
local function walked(dest, from)
  local d0 = from:distance(dest)
  local elapsed, timer = 0, nil
  timer = hafen.timer():every(INTERVAL, function()
    elapsed = elapsed + INTERVAL
    local me = hafen.player():gob()
    local now = me and me:position()
    local d = now and now:distance(dest)
    local arrived = d and (d <= 2 * TILE)
    if arrived or (elapsed >= WINDOW) then
      timer:cancel()
      -- Arrived, or at least a tile closer: what is being proved is that the re-sent click REACHED the
      -- server, and any movement toward the destination is that. Idle standing does not drift a tile.
      check(arrived or (d and (d <= d0 - TILE)),
            "ev:resend() re-sent the click and the character still walks",
            ("%.1f units away at the click, %s after %.1fs -- a tile is %d")
              :format(d0, d and ("%.1f"):format(d) or "no position", elapsed, TILE))
      finish()
    end
  end)
end

local function onClick(ev)
  -- Every "click" in the client reaches this stream, not the map's alone (Avaview, ISBox and Partyview
  -- send their own), so the run takes the one it asked for and lets the rest through untouched.
  if ev:sender():type() ~= "MapView" then return end
  sub:off()
  sub = nil
  if armTimer then armTimer:cancel(); armTimer = nil end

  local me = hafen.player():gob()
  local home = me and me:position()

  -- The world half: argument 2 is a wire coordinate, and position(i) undoes posres. Read raw it would
  -- name a place ~93x further out, so "near the player" IS the proof the undo happened.
  local p = ev:position(2)
  check(p:durable(), "ev:position(2) is a durable place", p:durable())
  local d = home and p:distance(home)
  check(d and (d < NEAR), "ev:position(2) lands within a few tiles of the player",
        d and ("%.1f units away, the bar is %d"):format(d, NEAR) or "no distance")

  -- The screen half: argument 1 is the press point in the map view's own pixels, and pixel(i) answers
  -- them in the design space ev:x()/ev:y() already speak.
  local pix = ev:pixel(1)
  local sz = hafen.ui():root():size()
  check((pix.x >= 0) and (pix.y >= 0) and (pix.x <= sz.x) and (pix.y <= sz.y),
        ("ev:pixel(1) is inside the root box (%d x %d)"):format(sz.x, sz.y),
        ("%.1f, %.1f"):format(pix.x, pix.y))

  -- Nothing was converted underneath: args[2].x is still the raw wire number, and position(2) is
  -- exactly that number times posres. One assertion proves both.
  local a = ev:args()
  local raw = a[2] and a[2].x
  check(raw and (math.abs(p:x() - raw * POSRES) < 1e-6),
        "ev:args() is unchanged -- args[2].x is still the raw wire number",
        ("args[2].x = %s, ev:position(2):x() = %.3f, raw * posres = %s")
          :format(tostring(raw), p:x(), raw and ("%.3f"):format(raw * POSRES) or "n/a"))

  -- Each reader refuses an index that is not a coordinate, and names the OTHER one: reading the wrong
  -- index and reading the right index with the wrong verb are the same mistake from two sides.
  refuses("ev:position(3) is refused -- the button is an integer -- naming :pixel(i)",
          function() ev:position(3) end, ":pixel(i)")
  refuses("ev:pixel(3) is refused, naming :position(i)",
          function() ev:pixel(3) end, ":position(i)")
  refuses("an index past the end of the argument list is refused",
          function() ev:position(99) end, "index 99")

  if home then
    ev:resend()
    walked(p, home)
  else
    check(false, "the character is in the world", "hafen.player():gob() is nil")
    finish()
  end
end

local function run()
  if running then
    hafen.log():write("[skip] a run is already armed -- click the ground, or wait for its [summary]")
    return
  end
  running, pass, fail, manual = true, 0, 0, 0

  sub = hafen.event():action():on("click", onClick)
  hafen.log():write(("[wait] armed -- now LEFT-CLICK THE GROUND about five tiles away, on open"
                     .. " walkable terrain and not on an object (%ds)"):format(ARM))
  armTimer = hafen.timer():after(ARM, function()
    armTimer = nil
    if sub then sub:off(); sub = nil end
    check(false, "a map click reached the action stream",
          ("nothing in %ds -- run :t067-2 again and click the ground"):format(ARM))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    running = false
  end)
end

hafen.slash():register("t067-2", run)   -- the only way in: a suite does not start itself
