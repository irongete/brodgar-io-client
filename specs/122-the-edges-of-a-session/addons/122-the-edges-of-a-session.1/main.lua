-- 122.1 — the screen is published on any thread, and the view moves on the frame's. Self-checking suite.
--
-- Asking for the screen used to move the two views where it was asked, taking the outgoing character's
-- widget-tree monitor inside the session layer's own lock. Every way of asking from the frame -- a
-- keybinding, a control, a draw handler -- arrives already holding that monitor, and a session ending takes
-- the two in the other order from its own thread. So the sharp check is a DRAW handler, which holds a tree
-- monitor for the whole paint pass by construction, asking for the screen from inside it.
--
-- It stands with one character and is sharper with two. Where a second login is live and the two frames are
-- related, the round trip goes through that character and the camera is handed over and back; where it is
-- not, the trip goes through the LOGIN SCREEN, which is a real change of screen with no view of its own.
-- Either way the screen is back before a frame is drawn, and every check below says which trip it made.
--
-- THE VIEW CENTRE IS DISCOVERED, NEVER ASSUMED. A freshly installed rts camera follows the character, so
-- the pixel that character projects to IS the centre of the view -- read with the same verb that reads
-- every other pixel here, in whatever unit that verb speaks. Computing it from the root's size instead
-- compares two numbers that need not share a unit, and a camera aimed perfectly reads hundreds of pixels
-- out.
--
-- Seven stages, because a change of screen is spent by the frame that follows it and a camera converges
-- over several of them: sampled inside one step, none of these numbers could have moved yet.

local pass, fail, manual = 0, 0, 0

local NEAR  = 40     -- pixels: how near the discovered centre the aimed-at place must land
local DRIFT = 40     -- pixels the round trip may move it -- the camera's own smoothing, and nothing else
local PAN   = 60     -- world units the view is panned off the character, so a lost pan is unmistakable

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check too: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local home, away, trip, campref, camarmed, aim, centrepx, aimpx, win
local selected = 0
local sub
local draw, ray = {}, {}
local phase2, phase3, phase4, phase5, phase6, phase7

local REFUSED = "the rts camera was refused -- approve client.settings for this addon"

-- The other screen to visit, and what to call it in a verdict line. A second login only serves if the
-- drawn character can locate it: p:x() resolves in the DRAWN character's frame, so a nil there says the two
-- bases are not related and no pan could be carried across even in principle.
local function pickaway()
  for _, s in ipairs(hafen.session():list()) do
    if s ~= home then
      local g = s:player():gob()
      local p = g and g:position()
      if p and p:x() then
        return s, "the login of " .. s:user()
      end
    end
  end
  return nil, "the login screen"
end

local function charpos()
  local g = home and home:player():gob()
  return g and g:position()
end

local function w2s(p)
  return p and home:world():worldToScreen(p)
end

local function apart(a, b)
  if (a == nil) or (b == nil) then return nil end
  local dx, dy = a.x - b.x, a.y - b.y
  return math.sqrt((dx * dx) + (dy * dy))
end

local function px(d)
  return (d == nil) and "nil" or (math.floor(d + 0.5) .. "px")
end

-- The nesting this task removes: a Draw handler holds this window's tree monitor for the whole pass, and
-- asks for the screen from inside it. Once, on the first pass that paints the window.
local function indraw()
  if draw.done then return end
  draw.done = true
  local S = hafen.session()
  local ok, err = pcall(function()
    S:current(away)          -- a nil here is the login screen, which is the write, not the read
    draw.mid = S:current()   -- THE NEXT LINE: the publication is synchronous or this is stale
    S:current(home)
    draw.back = S:current()
  end)
  draw.ok, draw.err = ok, err
end

-- --------------------------------------------------- stage 1: name the drawn session, install the camera
local function run()
  pass, fail, manual = 0, 0, 0
  selected, draw, ray = 0, {}, {}
  home = hafen.session():current()
  if charpos() == nil then
    check(false, "run it with a character in the world",
          home and "no character yet" or "the login screen")
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end
  away, trip = pickaway()

  -- The one camera with a centre of its own to aim, and the one protected verb this suite calls: the
  -- manifest declares client.settings for it, so enabling the addon is where it is granted. Guarded, so a
  -- refusal costs two verdict lines rather than the whole run. Installed BARE and left following the
  -- character -- the pan comes one stage later, once the centre it moves from has been read.
  local cam = hafen.client():options():camera()
  campref = cam:mode()
  camarmed = pcall(function() cam:mode("rts") end)

  -- Naming the session already on screen: it must change nothing and say nothing.
  sub = hafen.event():on("SessionSelected", function() selected = selected + 1 end)
  hafen.session():current(home)
  hafen.timer():after(1.2, phase2)
end

-- ---------------------------------- stage 2: score that; read the centre off the character, then pan away
phase2 = function()
  check(selected == 0, "naming the session already on screen fires no SessionSelected",
        selected .. " fired")
  sub:off()
  local here = charpos()
  if camarmed then
    centrepx = w2s(here)              -- a following camera looks AT the character: this pixel is the centre
    aim = here and here:offset(PAN, 0)
    if aim then home:world():focus(aim) end
  end
  hafen.timer():after(1.2, phase3)
end

-- ---------------------------------------- stage 3: the pan took, measured against that same pixel; leave
phase3 = function()
  aimpx = w2s(aim)
  local off = apart(aimpx, centrepx)
  check(camarmed and (off ~= nil) and (off <= NEAR),
        "focus(p) aims the rts view at p: p lands on the pixel the character held",
        (not camarmed) and REFUSED or (px(off) .. " apart, over " .. NEAR))
  hafen.session():current(away)     -- out, and spent by the frame after this one
  hafen.timer():after(1.0, phase4)
end

-- ------------------------------------------------------------------------------ stage 4: and back again
phase4 = function()
  hafen.session():current(home)
  hafen.timer():after(1.2, phase5)
end

-- ------------------------------------------------ stage 5: the pan came back, then arm the Draw handler
phase5 = function()
  local moved = apart(w2s(aim), aimpx)
  check(camarmed and (moved ~= nil) and (moved <= DRIFT),
        "and it is still aimed there after the round trip, out through " .. trip .. " and back",
        (not camarmed) and REFUSED or (px(moved) .. " of drift, over " .. DRIFT))
  win = hafen.ui():window():title("122.1"):size(70, 18):position(6, 6)
  win:on("Draw", indraw)
  hafen.timer():after(1.0, phase6)
end

-- ------------------------------------------- stage 6: what the Draw handler saw, then read the click-map
phase6 = function()
  check(draw.done and draw.ok,
        "the screen is asked for from inside a Draw handler, which holds a widget tree",
        (not draw.done) and "the handler never ran" or (draw.ok and "ok" or why(draw.err)))
  check(draw.mid == away,
        "current() reads the new screen on the line after the write (" .. trip .. ")",
        draw.mid and draw.mid:user() or "nil")
  check(draw.back == home,
        "and away and straight back inside one step leaves the same character on screen",
        draw.back and draw.back:user() or "nil")
  win:destroy()
  -- detachscene takes the click-map out of the scene, so a pair that slept the drawn view answers nothing.
  -- Fired at the discovered centre, which is ground by construction: the character was standing on it.
  local at = centrepx or w2s(charpos())
  if at then
    home:world():screenToWorld(at, function(p) ray.fired = true; ray.p = p end)
  end
  hafen.timer():after(1.0, phase7)
end

-- ------------------------------------------------------- stage 7: the click-map, the refusal, and the log
phase7 = function()
  check(ray.fired and (ray.p ~= nil),
        "the drawn view keeps its click-map through that pair: screenToWorld still answers",
        (not ray.fired) and "no answer at all" or (ray.p and "a place" or "nil"))
  refuses("a session the client does not hold is refused, naming the account",
          function() hafen.session():current(hafen.session():get("no-such-account")) end,
          "the client holds no session for the account")
  if camarmed and campref then
    pcall(function() hafen.client():options():camera():mode(campref) end)
  end

  manualCheck("with two characters live, hold the session-manager cycle hotkey down for ten seconds",
              "every press switches character, and the client never freezes")
  manualCheck("with two characters live, run ':session drop <the account now on screen>'",
              "the screen moves to the other character and the client keeps drawing")
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t122", run)   -- the only way in: a suite does not start itself
