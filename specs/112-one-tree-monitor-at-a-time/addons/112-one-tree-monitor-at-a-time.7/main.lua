-- 112.7 — the inbound stream decides before the monitor, and the outbound one is told why it cannot.
-- Self-checking suite. Run it with :t112 while a character is in the world, then do the [manual] step.
--
-- This addon declares "speed.set", so enable it in Options > AddOns and grant the key: the movement-speed
-- selector is the one server round trip an addon can CAUSE and then read back, which is what makes
-- ev:preventDefault() and ev:rewrite(t) assertable rather than hopeful. The run puts the speed back where
-- it found it, through two uninterfered round trips, before it scores.

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

-- The message a pcall came back with, with the file:line prefix taken off.
local function why(ok, err)
  if ok then
    return "<no error>"
  end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function has(s, word)
  return (s ~= nil) and (tostring(s):find(word, 1, true) ~= nil)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Is a {x=, y=} read the place we wrote? Within a pixel: a design pixel goes to the screen and back
-- through an interface scale that need not be a whole number.
local function near(got, x, y)
  return (type(got) == "table") and (math.abs(got.x - x) <= 1) and (math.abs(got.y - y) <= 1)
end

local function place(p)
  return (type(p) ~= "table") and "<never read>" or (p.x .. "," .. p.y)
end

local HOLD_X, HOLD_Y = 40, 40     -- where the layer surface the action handler tries to write is built...
local ACT_X,  ACT_Y  = 91, 92     -- ...and where that refused write aims it, which it must never reach
local WIN_X,  WIN_Y  = 40, 96     -- where the message handler builds its window, the whole proof of the hoist

local PREVENT = 5.0               -- the swallowed round trip is read back here...
local REWRITE = 10.0              -- ...the rewritten one here, and the stream is let go
local REST_A, REST_B = 11.0, 14.0 -- two uninterfered round trips that put the speed back
local WINDOW = 26.0               -- the bounded window the manual gesture is waited for

local st = {}

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every key the two off-step handlers write is created HERE, with a value that is never nil, before either
-- can run: a Lua table constructor does not create a key a nil is assigned to, and neither handler is on
-- the step -- a message handler is on the Loader thread applying the update, an action handler on whichever
-- thread sent it. So neither may GROW this table while the step reads it. That is the remainder this task
-- leaves standing rather than a defect in it, and the sentinels double as the "got:" of a check that never
-- reached its handler at all.
local function blank()
  return {
    done = false, msgs = 0, curs = 0, phase = "idle",
    saw = false, step = "<no update arrived>", win = false,
    buildOk = false, buildErr = "<no update arrived>",
    prevSaw = false, rewSaw = false,
    actDone = false, actRan = false, act = "<no action was sent>", actErr = "<no action was sent>",
    actRead = false,
  }
end

local function nowIndex()
  local cur = st.speed and st.speed:current()
  return cur and cur:index() or nil
end

local function tryset(sp)
  local ok, err = pcall(function() st.speed:set(sp) end)
  if (not ok) and (st.setErr == nil) then
    st.setErr = why(ok, err)
  end
  return ok
end

local function finish()
  if st.done then
    return
  end
  st.done = true
  st.actionSub:off()
  st.holdSub:off()
  if st.msgSub then
    st.msgSub:off()
  end

  local standing = (st.win ~= false) and st.win:exists()
  local nomsg = st.msgs .. " updates seen in " .. WINDOW .. "s"

  check(st.saw, "a server update reaches an inbound message handler", nomsg)
  check(st.buildOk and standing,
        "...and a window is built in the LAYER from inside it: the seam holds no tree monitor",
        st.saw and (st.buildErr .. ", standing=" .. tostring(standing)) or nomsg)
  check(st.saw and (st.step == false),
        "...and it still answers on the applying thread, not on the client's step", st.step)

  local speedwhy = st.setErr or ("no speed round trip: " .. st.curs .. " cur updates")
  local origix = st.orig and st.orig:index()
  local otherix = st.other and st.other:index()
  check(st.prevSaw and (st.prevRead == origix),
        "ev:preventDefault() leaves the widget unchanged",
        st.prevSaw and ("speed " .. tostring(st.prevRead) .. ", was " .. tostring(origix)) or speedwhy)
  check(st.rewSaw and (st.rewRead == otherix),
        "ev:rewrite(t) still reaches the widget",
        st.rewSaw and ("speed " .. tostring(st.rewRead) .. ", rewritten to " .. tostring(otherix)) or speedwhy)

  local s = hafen.session():current()
  local held = s and s:user() or "the character"
  check(st.act == false, "a cross-tree write from an ACTION handler is refused", st.actErr)
  check(has(st.actErr, "addon layer") and has(st.actErr, held) and has(st.actErr, "Update"),
        "...and the refusal names both trees and the step that holds neither", st.actErr)
  check(st.actRan and not near(st.actRead, ACT_X, ACT_Y), "...and the refused write did not land",
        st.actRan and (place(st.actRead) .. ", aimed at " .. ACT_X .. "," .. ACT_Y) or st.actErr)

  if st.hold then st.hold:destroy() end
  if st.win then st.win:destroy() end
  report()
end

local function start()
  st = blank()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end

  -- A surface of OUR OWN, in the addon layer. A "click" is always sent by a widget of the character's
  -- tree, under that tree's monitor, so writing this one from an action handler is the second monitor.
  st.hold = hafen.ui():window():title("112.7"):size(150, 34):position(HOLD_X, HOLD_Y)

  st.actionSub = hafen.event():action():on("click", function(ev)
    if st.actDone then
      return                                     -- the first gesture scores; the rest are the player's
    end
    st.actDone = true
    local ok, err = pcall(function() st.hold:position(ACT_X, ACT_Y) end)
    st.act, st.actErr = ok, why(ok, err)
    st.actRan = true                             -- raised LAST: the step reads only a finished refusal
  end)

  -- Where the surface stands ON THE FIRST FRAME AFTER the refusal. A layer window is draggable, and other
  -- addons place their own windows over the same screen, so reading it at the end of the run would be
  -- asking whether anything moved it in 26 seconds rather than whether THIS write landed. The step holds
  -- no monitor (112.1), so this read is free where the action handler's own would have been a second tree.
  st.holdSub = st.hold:on("Update", function()
    if st.actRan and (st.actRead == false) then
      st.actRead = st.hold:position()
    end
  end)

  -- The whole stream, so the hoist is proved by whatever the server sends first rather than by a name
  -- this character happens to produce. The speed halves below ride the one update we can cause.
  st.msgSub = hafen.event():message():on("*", function(ev)
    st.msgs = st.msgs + 1
    if not st.saw then
      st.saw = true
      st.step = hafen.client():stepping()
      -- THE HOIST. A window with no parent is the addon LAYER's, and this handler is on the Loader
      -- thread that is applying a session's update -- the pair that took two monitors until this task.
      local ok, err = pcall(function()
        st.win = hafen.ui():window():title("112.7 in"):size(150, 34):position(WIN_X, WIN_Y)
      end)
      st.buildOk, st.buildErr = ok, why(ok, err)
    end
    local w = ev:widget()
    if (ev:msg() ~= "cur") or (w == nil) or (w:type() ~= "Speedget") then
      return                                     -- Fightview sends "cur" too; only the selector's is ours
    end
    st.curs = st.curs + 1
    if st.phase == "prevent" then
      st.prevSaw = true
      ev:preventDefault()                        -- swallow it: the selector must not move
    elseif st.phase == "rewrite" then
      st.rewSaw = true
      ev:rewrite({ st.other:wire() })            -- apply a DIFFERENT speed than the one the server sent
    end
  end)

  manualCheck("now click the ground once",
              "the three action checks below score -- nothing in Lua sends a click")

  st.speed = s:speed()
  st.orig = st.speed:current()
  for _, sp in ipairs(st.speed:available():list()) do
    if st.orig and (sp:index() ~= st.orig:index()) then
      st.other = sp
      break
    end
  end
  if (st.orig == nil) or (st.other == nil) then
    st.setErr = "the speed selector offers no second speed to swap to"
    return hafen.timer():after(WINDOW, finish)
  end

  -- Server=orig, client=orig. Ask for `other`: the server moves, and its "cur" is swallowed on the way in.
  st.phase = "prevent"
  tryset(st.other)

  hafen.timer():after(PREVENT, function()
    st.prevRead = nowIndex()                     -- the swallowed update must have left the selector alone
    -- Server=other, client=orig. Ask for `orig`: the server moves back, and its "cur" is rewritten to
    -- `other` on the way in, so the selector ends up on a speed the server never sent.
    st.phase = "rewrite"
    tryset(st.orig)
  end)

  hafen.timer():after(REWRITE, function()
    st.rewRead = nowIndex()
    st.phase = "idle"
    st.msgSub:off()
    st.msgSub = false         -- cleared, not deleted: the handler's thread may be inside this table
  end)

  -- Server=orig, client=other. Two real transitions, neither intercepted, and both ends agree again.
  hafen.timer():after(REST_A, function() tryset(st.other) end)
  hafen.timer():after(REST_B, function() tryset(st.orig) end)

  hafen.timer():after(WINDOW, finish)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  hafen.timer():after(0, start)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
