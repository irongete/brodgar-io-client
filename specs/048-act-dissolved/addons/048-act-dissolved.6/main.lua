-- 048.6 — widget:send(msg, ...), and the death of the target tokens. Self-checking suite; see
-- specs/testing/addon-suite.md.
--
-- The escape hatch was hafen.act():raw(target, msg, ...), and its `target` carried a PRIVATE ADDRESS SPACE: a
-- bare numeric server widget id, or one of the tokens "mapview" / "gameui" / "root". On a widget handle the
-- receiver IS the target, so the move does not rehouse that vocabulary -- it DELETES it, and the claim that
-- lets it go is assertable rather than argued: every one of those tokens is already an ordinary selector. That
-- is the first thing this suite checks, because it is the premise the deletion rests on.
--
-- The suite declares no permissions (TESTING.md), so `send` is proven BY ITS REFUSAL naming the verb -- and
-- because the gate runs FIRST (D-213), every argument mistake comes back as the permission error too. That
-- ordering is asserted here directly (a non-string msg, and a widget this addon built itself, both answer the
-- permission error), which is also why the "msg must be a string" and "not BOUND" refusals themselves are a
-- [manual] :lua line: the console owner declares every permission, so it needs no addon.
--
-- RUN IT, FIRE THE FIRST :lua LINE, THEN RUN `:t048-6 sent`. What that line puts on the wire is the one thing
-- not left to the eye: the run RECORDS every outbound "click" (041.2's action stream, an observe surface -- it
-- only watches and never preventDefaults) and asserts that the send left FROM the widget it was called on,
-- which is the whole of what replaced `target`. "click" is a message A PLAYER SENDS constantly, so the
-- recorder has to identify its own: the fired line uses a press coord of {0,0}, which a real ground click
-- essentially never has, and `sent` asserts EXACTLY ONE such while reporting how many others landed in between.

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

-- Every outbound "click", in order, with the widget that sent it. Armed at LOAD, not by a run: a send cannot
-- be observed after the fact. This records and nothing else -- it prints nothing and never cancels a send.
local sent = {}

hafen.event():action():on("click", function(ev)
  sent[#sent + 1] = { args = ev:args(), sender = ev:sender() }
end)

local function run()
  pass, fail, manual = 0, 0, 0
  -- Clear the recorder: from here to `:t048-6 sent`, the one "click" carrying a {0,0} press coord should be
  -- the one the [manual] line below is about to fire. That is what makes the count an assertion, not a guess.
  sent = {}

  -- THE PREMISE FOR THE DELETION. raw's three tokens were not a capability, they were a second spelling of a
  -- lookup the selector grammar already does: "@Class" resolves through the widget's type name, MapView is not
  -- subclassed in this fork, and both widgets the tokens named are SERVER-BOUND (:id() is a number), which is
  -- the one property widget:send needs of a receiver.
  local mv = hafen.ui():find("@MapView")
  check((mv ~= nil) and (type(mv:id()) == "number"),
        "hafen.ui():find(\"@MapView\") resolves to a BOUND widget -- the token \"mapview\" was a selector",
        (mv == nil) and "no match (are you in the world?)" or ("id=" .. tostring(mv:id())))
  local gui, root = hafen.ui():find("@GameUI"), hafen.ui():root()
  check((gui ~= nil) and (type(gui:id()) == "number") and (root ~= nil) and (type(root:id()) == "number"),
        "...and so do the other two: find(\"@GameUI\") is \"gameui\" and hafen.ui():root() is \"root\"",
        ("gameui=%s root=%s"):format(gui and tostring(gui:id()) or "nil",
                                     root and tostring(root:id()) or "nil"))

  -- The verb is on the widget, and it is a function rather than a name that happens to read nil.
  check((mv ~= nil) and (type(mv.send) == "function"),
        "widget:send is a function on that widget",
        (mv == nil) and "no widget to read it off" or type(mv.send))

  -- It is PROTECTED, and the refusal names the verb a caller has to declare for rather than a section. Perfect
  -- arguments are handed in, so nothing but the gate can be what refuses.
  refuses("widget:send refuses this undeclared addon, naming the verb",
          function() mv:send("click", { x = 0, y = 0 }, { x = 0, y = 0 }, 1, 0) end,
          "widget:send: this addon did not declare")

  -- ...and the gate runs BEFORE the message name is looked at (D-213). An addon that may not act at all must
  -- learn THAT, not that it mistyped an argument -- which is also why this suite can never see the argument
  -- refusals, and why they are the [manual] line below.
  refuses("...and the gate runs first: a non-string msg still answers the permission error",
          function() mv:send(42) end, "widget:send: this addon did not declare")

  -- BOUND WIDGETS ONLY is the one rule that survived raw, and this is the read that answers the same question
  -- the refusal does: a widget this addon built has no server id, so there is no one to send to. Built and
  -- destroyed inside this run, so nothing is left on screen.
  local built, ownw = pcall(function() return hafen.ui():widget() end)
  ownw = built and ownw or nil
  check((ownw ~= nil) and (ownw:id() == nil),
        "a widget this addon BUILT is unbound: widget:id() is nil, so it has no one to send to",
        (ownw == nil) and "the builder handed back nothing" or tostring(ownw:id()))
  refuses("...and the gate runs before the bound check too: send on it answers the permission error",
          function() ownw:send("click") end, "widget:send: this addon did not declare")
  if ownw then pcall(function() ownw:destroy() end) end

  -- The old door is shut, under BOTH field reads (D-216): the colon call a shipped addon actually wrote, and
  -- the pre-039 dotted one. The two assert different sentences of the same message -- where the verb lives
  -- now, and that the target vocabulary was deleted rather than rehoused.
  refuses("hafen.act():raw throws naming widget:send(msg, ...)",
          function() hafen.act():raw("mapview", "click") end, "is now widget:send(msg, ...)")
  refuses("the pre-039 dotted hafen.act.raw throws, and says the tokens are ordinary selectors now",
          function() return hafen.act.raw end, "hafen.ui():find(\"@MapView\")")

  -- The section EMPTIES over this feature (D-117); it does not disappear from under the verbs still on it.
  check(hafen.act():enabled() == false,
        "the rest of hafen.act() is still callable: :enabled() (false)",
        tostring(hafen.act():enabled()))

  -- The firing demo. The destination is the tile you are already standing on, so a real send proves the door
  -- without moving you; the press coord is the {0,0} the `sent` run identifies its own send by.
  manualCheck("fire this ONE line and then run  :t048-6 sent  (do not click the ground in between):"
              .. "  :lua local mv = hafen.ui():find(\"@MapView\"); local p = hafen.player():gob():position();"
              .. " local su = 1024/11; mv:send(\"click\", {x=0,y=0},"
              .. " {x=math.floor(p:x()*su), y=math.floor(p:y()*su)}, 1, 0)",
              "no error and no visible movement -- you are asked to walk to where you already stand, which"
              .. " proves the door rather than the aim. The line begins with `local`, so it echoes nothing;"
              .. " :t048-6 sent then asserts what actually went out")

  -- The two argument refusals the gate hides from this suite. The console owner declares every permission.
  manualCheck("the two refusals this suite cannot reach:"
              .. "  :lua local w = hafen.ui():widget(); local ok, e = pcall(function() w:send(\"click\") end);"
              .. " w:destroy(); hafen.log():write(tostring(e))"
              .. "   and then   :lua hafen.ui():find(\"@MapView\"):send(42)",
              "the first LOGS an error saying the widget is not BOUND (widget:id() is nil) and pointing at"
              .. " hafen.ui():find(\"@MapView\"); the second RAISES `msg must be a string`. Nothing is left on"
              .. " screen -- the widget is built and destroyed in the same statement")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- `:t048-6 sent` — what the [manual] firing ACTUALLY put on the wire. The suite cannot send (it declares no
-- permissions), so this is the half of the proof that only a real, permitted send can supply: that the message
-- left FROM the widget it was called on, and that the arguments crossed unchanged.
local function runSent()
  pass, fail, manual = 0, 0, 0

  local mv = hafen.ui():find("@MapView")
  local mine = {}
  for i = 1, #sent do
    local a = sent[i].args
    if (type(a[1]) == "table") and (a[1].x == 0) and (a[1].y == 0) then mine[#mine + 1] = sent[i] end
  end
  check(#mine == 1,
        "the fired widget:send put exactly one \"click\" with a {0,0} press coord on the wire",
        ("%d matching, %d \"click\" sends in total since :t048-6 -- run :t048-6, then fire the :lua line ONCE"
         .. " without clicking the ground in between"):format(#mine, #sent))

  local ev = mine[1]
  -- THE CLAIM. It was sent BY the widget the verb was called on: that identity is the whole of what replaced
  -- raw's `target`, and it is why the token vocabulary had nothing left to address.
  check((ev ~= nil) and (ev.sender ~= nil) and (ev.sender:type() == "MapView")
        and (mv ~= nil) and (ev.sender:id() == mv:id()),
        "...and it was sent BY that very MapView widget -- the receiver IS the target",
        (ev == nil) and "nothing recorded" or ("%s #%s"):format(tostring(ev.sender and ev.sender:type()),
                                                                tostring(ev.sender and ev.sender:id())))

  -- ...and the arguments crossed unchanged: a {x=,y=} table IS a Coord on the wire, numbers stay numbers.
  local a = (ev ~= nil) and ev.args or {}
  check((type(a[2]) == "table") and (type(a[2].x) == "number") and (type(a[2].y) == "number")
        and (a[3] == 1) and (a[4] == 0),
        "...and the arguments marshalled unchanged: both {x=,y=} tables are Coords, both numbers are numbers",
        ("%s / %s / %s"):format(type(a[2]), tostring(a[3]), tostring(a[4])))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-6", function(args)   -- the only way in: a suite does not start itself
  if args[1] == "sent" then runSent() else run() end
end)
