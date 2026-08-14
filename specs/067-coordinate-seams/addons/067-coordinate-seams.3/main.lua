-- 067.3 -- a Position can be written back to the server. Self-checking suite.
--
-- The read half already lets a handler name the space an argument is in. Without a write half the only
-- way to put a destination BACK was to multiply by 11/1024 by hand, which is the constant this API
-- exists to keep out of addons. So ev:send(t) takes a Position where a coordinate goes and encodes the
-- wire form itself -- and only a Position, because a Position always means a place and never a screen
-- pixel, which is exactly the property a {x=, y=} table lacks. Both halves of that are asserted below:
-- the Position converts, and the table beside it in the same argument list still does not.

local pass, fail, manual = 0, 0, 0

local TILE     = 11            -- world units per tile (MCache.tilesz)
local CENTRE   = TILE / 2      -- where snapPlace with no SHIFT puts a point inside its tile
local AT       = 1.0           -- world units: how close to a destination counts as standing on it
local APART    = 1.5           -- world units the click must be off-centre for the two points to differ
local WINDOW   = 15            -- seconds the walk after ev:send() is given
local INTERVAL = 0.25          -- seconds between polls
local ARM      = 60            -- seconds the run waits for the click before giving up

-- A grid id no session locates. Ids are server-published 64-bit numbers, so a Position rebuilt on this
-- one holds a durable anchor and has no coordinate anywhere -- the case ev:send has to refuse.
local NOWHERE = "1"

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
  manualCheck("watch where the character stops after the click you just made",
              "it stops at the CENTRE of that tile, not at the pixel you clicked")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  running = false
end

-- Where the character came to rest. Polled rather than assumed: the server answers when it answers, and
-- the run scores the distance it actually reached inside a bounded window.
local function rested(snapped, raw)
  local elapsed, timer = 0, nil
  timer = hafen.timer():every(INTERVAL, function()
    elapsed = elapsed + INTERVAL
    local me = hafen.player():gob()
    local now = me and me:position()
    local ds = now and now:distance(snapped)
    if (ds and (ds <= AT)) or (elapsed >= WINDOW) then
      timer:cancel()
      local dr = now and now:distance(raw)
      -- The whole claim in one assertion: the character is standing on the SNAPPED point, and the raw
      -- click point -- the one the client itself would have sent -- is further away than that.
      check(ds and (ds <= AT) and dr and (dr > ds),
            "the character came to rest on the snapped tile centre, not on the click point",
            ("%s from the snap, %s from the click, after %.1fs -- the bar is %.1f")
              :format(ds and ("%.2f"):format(ds) or "no position",
                      dr and ("%.2f"):format(dr) or "n/a", elapsed, AT))
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

  local a = ev:args()
  check(#a == 4, "the click landed on open ground (pc, mc, button, mods)",
        ("%d arguments -- a click on an object carries more; re-run and click bare ground"):format(#a))

  -- The table branch is untouched, so a hand-built argument list goes on working -- and a table that is
  -- not a coord is still refused there, which is what says the branch is the one it always was.
  refuses("a table that is not {x=, y=} is still refused at a coordinate argument",
          function() ev:send({ {x = 1, y = 2}, {z = 1}, 1, 0 }) end, "must be a coord")

  -- A place with no coordinate THIS session has no number to send, and a stand-in would walk the
  -- character somewhere arbitrary, so it is refused rather than encoded.
  local ghost = hafen.world():position{ gridId = NOWHERE, x = 0, y = 0 }
  check(ghost:x() == nil, "the test place really is one this session cannot locate", ghost:x())
  refuses("ev:send refuses a Position this session cannot locate, saying it has no coordinate",
          function() ev:send({ {x = 1, y = 2}, ghost, 1, 0 }) end, "has no coordinate")

  local raw = ev:position(2)
  local snapped = hafen.world():snapPlace(raw)
  check((math.abs(snapped:x() % TILE - CENTRE) < 1e-6)
        and (math.abs(snapped:y() % TILE - CENTRE) < 1e-6),
        "snapPlace answers the centre of the clicked tile",
        ("%.3f, %.3f -- a tile centre sits at %.1f inside its tile"):format(snapped:x(), snapped:y(),
                                                                            CENTRE))
  local off = raw:distance(snapped)
  check(off >= APART, "the click was far enough off-centre to tell the two points apart",
        ("%.2f units from the tile centre -- re-run and click nearer a tile edge"):format(off))

  -- ev:args() is a SNAPSHOT: writing a Position into it changes what is about to be sent and changes
  -- nothing about what the event holds, so the reader still names the place the client resolved.
  a[2] = snapped
  local still = ev:position(2)
  check(math.abs(still:x() - raw:x()) < 1e-9, "ev:position(2) still names the click after a[2] is rewritten",
        ("%.3f, was %.3f"):format(still:x(), raw:x()))

  local pc = a[1]
  local sent = pcall(function() ev:send(a) end)
  check(sent, "ev:send took the Position at 2 and the {x=, y=} table at 1 in one argument list", sent)
  check((a[1] == pc) and (type(pc) == "table") and (type(pc.x) == "number") and (type(pc.y) == "number"),
        "the {x=, y=} table round-tripped through ev:args() -> ev:send unchanged",
        pc and (tostring(pc.x) .. ", " .. tostring(pc.y)) or "no table")

  if sent then
    rested(snapped, raw)
  else
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
          ("nothing in %ds -- run :t067-3 again and click the ground"):format(ARM))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    running = false
  end)
end

hafen.slash():register("t067-3", run)   -- the only way in: a suite does not start itself
