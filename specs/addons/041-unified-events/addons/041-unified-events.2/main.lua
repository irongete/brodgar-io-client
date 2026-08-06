-- 041.2 — LuaEvent, and the two message streams. Self-checking suite; see specs/addons/TESTING.md.
--
-- WHAT THIS TASK CLAIMS. The two hook levels that intercepted the message streams are gone: an outbound
-- action is hafen.event():action():on(msg, fn) and an inbound update is hafen.event():message():on(msg, fn),
-- both over the same Subs mechanism as the bus, both handing back a Sub. Their key sets are OPEN (any name
-- is accepted -- a wdgmsg name is protocol, not a catalogue the client owns), unlike the bus's closed 26.
-- And the ev they hand the handler is an OBJECT: every member is a colon verb (ev:msg(), ev:args()), an
-- unknown one throws, and ev:sender()/ev:target() are WIDGET HANDLES rather than the class-name strings
-- they used to be -- so a handler can navigate from the event to the thing it is about.
--
-- HOW IT PROVES DELIVERY. Neither stream can be made to fire by a read-only addon: an action is a real
-- player action and a message is real server traffic. So the surface half is asserted immediately and the
-- delivery half rides FOUR gestures the maintainer makes -- two ground clicks and two chat lines -- each
-- with a visible outcome that no program in this env could fake. The lines below print as they happen.
--
-- READ-ONLY, AND EVERYTHING IT DOES UNDOES ITSELF: it declares no permissions, builds no widgets and writes
-- no persistent state. It cancels ONE map click (you do not move; the next click works), re-sends the
-- second one verbatim, appends a marker to ONE inbound chat line and swallows ONE more -- all client-side
-- and one-shot. Every subscription it makes is ended by the run that made it, at the latest by its watchdog.

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

local function nop() end

-- Every spelling this task retires, and the replacement its message must name. Both forms of each: the
-- COLON verb the section no longer has, and the DOTTED pre-039 field read -- whose old message named
-- hafen.hook():action, a verb that stopped existing today. A suite stands alone (D-085), so the rows an
-- older suite also pins are re-asserted here rather than left to a run nobody makes.
local RETIRED = {
  { "hafen.hook():action(msg, fn)", function() hafen.hook():action("click", nop) end,
    "hafen.event():action():on(msg, fn)" },
  { "hafen.hook():message(msg, fn)", function() hafen.hook():message("set", nop) end,
    "hafen.event():message():on(msg, fn)" },
  { "hafen.hook.action", function() return hafen.hook.action end,
    "hafen.event():action():on(msg, fn)" },
  { "hafen.hook.message", function() return hafen.hook.message end,
    "hafen.event():message():on(msg, fn)" },
}

-- The round's state. `subs` holds everything to end at the close, whichever way the run finishes.
local subs, clicks, chats, done = {}, 0, 0, false
local clickFires, ranSecond, ranRewrite, ranPrevent, rewroteText = 0, false, false, false, nil
local shape = "no message seen"

local function keep(s)
  subs[#subs + 1] = s
  return s
end

local function finish(why)
  if done then return end
  done = true
  for _, s in ipairs(subs) do s:off() end
  if why then check(false, why[1], why[2]) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---- the click round: what an action handler sees, and what cancelling does --------------------------
local function onClick(ev)
  if ev:sender():type() ~= "MapView" then return end          -- only ground clicks, not every "click"
  clickFires = clickFires + 1
  if clicks == 0 then
    clicks = 1
    local a = ev:args()
    check((ev:msg() == "click") and (#a >= 3) and (type(ev:sender():parent():type()) == "string"),
          "an action handler reads ev:msg(), ev:args() and ev:sender():type(), and ev:sender():parent()"
          .. " navigates from the event to the widget it is about",
          ("%s, %d arg(s), sender=%s, parent=%s"):format(tostring(ev:msg()), #a,
            tostring(ev:sender():type()), tostring(ev:sender():parent():type())))
    check(type(ev.msg) == "function",
          "the payload has no FIELDS left: ev.msg finds the verb, and only ev:msg() answers the name",
          type(ev.msg))
    refuses("an unknown verb on the ev throws listing what the shape does answer",
            function() return ev:buton() end, "an action event answers")
    ev:preventDefault()                                       -- this click never reaches the server
  elseif clicks == 1 then
    clicks = 2
    clickFires = 1                                            -- count THIS click's fires: resend must not re-enter
    ev:resend()                                               -- ...so the second click moves you after all
    hafen.timer():after(0.4, function()
      check(clickFires == 1, "ev:resend() re-issues the action without re-entering the stream that ran it",
            ("the handler fired %d time(s) for one click"):format(clickFires))
    end)
  end
end

local function onClickB(ev)
  if (ev:sender():type() == "MapView") and (clicks == 1) then
    ranSecond = true                                          -- ...even though the handler before us cancelled
  end
end

-- ---- the message round: the inbound mirror, on real server traffic ----------------------------------
-- What the first inbound message actually looked like: the receiving widget and the type of every argument
-- up to the last non-nil one. Recorded so the rewrite verdict below can DIAGNOSE itself if the shape is one
-- the round does not know how to rewrite, instead of failing with a bare "false".
local function shapeOf(ev)
  local a, n, t = ev:args(), 0, {}
  for i = 1, 8 do
    if a[i] ~= nil then n = i end
  end
  for i = 1, n do t[i] = type(a[i]) end
  return ("%s(%s)"):format(tostring(ev:target():type()), table.concat(t, ","))
end

local function onMsg(ev)
  chats = chats + 1
  if chats == 1 then
    shape = shapeOf(ev)
    check((ev:msg() == "msg") and (type(ev:target():type()) == "string")
          and (type(ev:args()) == "table"),
          "a message handler reads ev:msg(), ev:args() and ev:target():type() -- the receiving widget, as a"
          .. " handle", ("%s -> %s"):format(tostring(ev:msg()), shape))
    refuses("ev:resend() is refused on a message event (it answers :rewrite(t) instead)",
            function() return ev:resend() end, "a message event answers")
  end
end

local function onMsgRewrite(ev)
  local a = ev:args()
  -- The LAST argument, found by scanning rather than by #a: a chat line arrives as (nil, line) -- a null
  -- sender is how the client knows the line is yours -- and a nil is a HOLE, so #a reads 0 on the very
  -- message this round is about. The text is always the last argument, whichever channel sent it.
  local n = 0
  for i = 1, 8 do
    if a[i] ~= nil then n = i end
  end
  if n < 2 or type(a[n]) ~= "string" then return end           -- a shape we do not know: observe only
  if chats == 1 then
    rewroteText = a[n] .. " [041.2 rewrote this]"
    a[n] = rewroteText
    ev:rewrite(a)                                              -- the widget applies THESE args instead
  elseif chats == 2 then
    ranRewrite = true
    ev:rewrite(a)                                              -- ...and this one loses to the cancel below
  end
end

local function onMsgPrevent(ev)
  if chats ~= 2 then return end
  ranPrevent = true
  ev:preventDefault()                                          -- swallowed: the widget never applies it
  hafen.timer():after(0.4, function()
    check(ranRewrite and ranPrevent,
          "with a rewrite and a cancel on one message, BOTH handlers ran and preventDefault won",
          ("rewrote=%s prevented=%s, shape=%s"):format(tostring(ranRewrite), tostring(ranPrevent), shape))
    check(rewroteText ~= nil, "ev:rewrite(t) gave the first message new args -- the marker in the chat line"
          .. " above is the widget having applied THEM and not the originals",
          ("%s, shape=%s"):format(tostring(rewroteText), shape))
    finish()
  end)
end

-- ---- the run ----------------------------------------------------------------------------------------
local function run()
  pass, fail, manual = 0, 0, 0
  subs, clicks, chats, done = {}, 0, 0, false
  clickFires, ranSecond, ranRewrite, ranPrevent, rewroteText = 0, false, false, false, nil
  shape = "no message seen"

  -- 1. Two emitters, one verb, one handle -- and the section itself is the door, so it takes no arguments.
  check(hafen.event():action() == hafen.event():action(), "hafen.event():action() is a per-addon singleton")
  local s = hafen.event():action():on("click", nop)
  check((tostring(s):find("Sub(", 1, true) ~= nil) and (type(s.off) == "function"),
        "a stream's :on(msg, fn) hands back a Sub whose one verb is :off()",
        ("%s, off=%s"):format(tostring(s), type(s.off)))
  s:off()
  check(pcall(function() s:off() end), "a second sub:off() is a no-op rather than an error")

  -- 2. The key sets: OPEN here, CLOSED on the bus -- the same emitter grammar, two different questions.
  local ok, s2 = pcall(function() return hafen.event():message():on("nosuchmessageatall", nop) end)
  if ok then s2:off() end
  check(ok, "an unknown message name is ACCEPTED: a wdgmsg name is protocol, not a client catalogue", s2)
  refuses("...while the bus's own key set stays closed and refuses a typo",
          function() hafen.event():on("GobAdded ", nop) end, "unknown event 'GobAdded '")
  refuses("an unknown verb on a stream throws naming what it does answer",
          function() return hafen.event():action():listeners() end, ":on(msg, fn)")

  -- 3. The doors that closed. FOUR spellings, not two: this task moved the colon verbs, and the dotted
  --    pre-039 form of each one has to say the same thing rather than go on naming a verb that is gone.
  --    Asserted HERE, in the suite whose own claims rest on them, and not left to an older suite's run.
  local named = 0
  for _, r in ipairs(RETIRED) do
    local ok2, err = pcall(r[2])
    if (not ok2) and (tostring(err):find(r[3], 1, true) ~= nil) then named = named + 1 end
  end
  check(named == #RETIRED, ("every retired spelling of the two hook levels throws naming its replacement"
        .. " (%d/%d: the colon verbs and the dotted forms)"):format(named, #RETIRED), named)

  -- 4. ...and what this task did NOT move is still there: the bus itself still takes a key. (hafen.hook()'s
  --    third verb, :input, was untouched by THIS task the same way -- 041.3 retired it in its turn, and
  --    that retirement is :t041-3's premise to assert, not this suite's to keep pinning after the fact.)
  local busSub = hafen.event():on("GobAdded", nop)
  check(type(busSub.off) == "function", "the bus still hands back a Sub for one of its own 26 keys", busSub)
  busSub:off()

  -- 4. Arm both rounds. Registration order within an addon is the firing order, which is what lets the
  --    second handler on each key judge the first one's cancel.
  keep(hafen.event():action():on("click", onClick))
  keep(hafen.event():action():on("click", onClickB))
  keep(hafen.event():message():on("msg", onMsg))
  keep(hafen.event():message():on("msg", onMsgRewrite))
  keep(hafen.event():message():on("msg", onMsgPrevent))

  manualCheck("LEFT-click the ground twice (a second apart), then type two short lines in area chat",
              "the 1st click does NOT move you (cancelled) and the 2nd does (re-sent); then the 1st chat"
              .. " line appears with ' [041.2 rewrote this]' appended (rewrite applied) and the 2nd does not"
              .. " appear at all (the cancel beat the rewrite). Any INBOUND chat counts, so if someone else"
              .. " speaks first, theirs are the two lines")

  -- The click round's own verdict, once both clicks are in (the chat round closes the run).
  local poll
  poll = hafen.timer():every(0.5, function()
    if clicks >= 2 then
      check(ranSecond, "two handlers on one key both ran, though the first one cancelled -- cancelling is"
            .. " OR-accumulated, never a short circuit", ranSecond)
      poll:cancel()
    elseif done then
      poll:cancel()
    end
  end)

  -- The watchdog: a run nobody finishes still ends, says which half is missing, and unsubscribes.
  hafen.timer():after(150, function()
    if not done then
      finish({ "the delivery round completed", ("%d/2 clicks, %d/2 chat messages in 150 s -- are you in the"
               .. " world?"):format(clicks, chats) })
    end
  end)
end

hafen.slash():register("t041-2", run)   -- the only way in: a suite does not start itself
