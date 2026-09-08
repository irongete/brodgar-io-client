-- R07 -- one way to send.
--
-- One mechanism: every message this API puts on the wire leaves through one door. That door resolves the
-- tree of the character the verb was addressed at, refuses a widget that is not in it, takes that tree's
-- monitor, checks the SHAPE of the message against what the player's own mouse, entry line or kin window
-- could have composed, and bounds the RATE to one send per frame -- per character, per verb.
--
-- So what this asserts is one rule read from six verbs at once. The rate half is read from `move`, which
-- goes out once and then refuses for the rest of the frame, and from `place` beside it, which still goes
-- out in that same frame because the bound is the verb's and not the addon's. The shape half is read from
-- a button no mouse has, a modifier key the client does not send, an area no drag can span, a chat line no
-- entry line can compose and a hearth secret with a newline in it. Every one of those is a refusal, so
-- nothing goes out for any of them.
--
-- WHAT IT DOES SEND, and why it is inert: two walks to the ground the character is already standing on, and
-- two "place" messages with nothing on the cursor, which the server ignores. It refuses to run at all while
-- something IS on the cursor, since that is the one state in which a place would land.
--
-- Run it with :tR07. It starts nothing by itself. The checks run on the engine step rather than inside the
-- console handler, because a console line runs under the monitor of the tree it was typed into.

local out, pass, fail, manual = {}, 0, 0, 0

local function line(s) out[#out + 1] = s end

local function ok(desc, cond, got)
  if cond then
    pass = pass + 1
    line("[pass] " .. desc)
  else
    fail = fail + 1
    line("[fail] " .. desc .. " -- got: " .. tostring(got))
  end
end

local function todo(action, expect)
  manual = manual + 1
  line("[manual] " .. action .. " -- expect: " .. expect)
end

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Every call in `fns` must raise, and every message must carry `needle`.
local function refused(needle, fns)
  for i = 1, #fns do
    local fine, err = pcall(fns[i])
    if fine then return false, "call " .. i .. " raised nothing" end
    local msg = why(err)
    if not msg:find(needle, 1, true) then return false, "call " .. i .. " said " .. msg end
  end
  return true
end

local function raisedNothing(fn)
  local fine, err = pcall(fn)
  return fine, fine and "" or why(err)
end

-- The first channel with an entry line under it: those are the kinds that take one.
local function entryChannel(s)
  for _, ch in ipairs(s:chat():list()) do
    local k = ch:kind()
    if k == "chat" or k == "chat.party" or k == "chat.private" then return ch end
  end
end

local function report()
  for _, l in ipairs(out) do hafen.log():write(l) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---------------------------------------------------------------- the checks

local function nextFrame(s, here)
  ok("the bound lifts with the frame: the same verb sends again a moment later",
     raisedNothing(function() s:player():move(here) end))
  todo("open the chat and read the lines said since the run",
       "nothing said by the suite -- every refused send above put nothing on the wire")
  report()
end

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  local s = hafen.session():current()
  local gob = s and s:player():gob()
  local here = gob and gob:position()
  if not here then
    line("[fail] no character is in the world -- log one in, wait for the HUD, and run :tR07 again")
    return report()
  end
  if s:world():placing() then
    line("[fail] something is on your cursor -- put it down or cancel it, then run :tR07 again")
    return report()
  end
  local far = here:offset(100000, 100000)
  local r, got

  -- The rate half. Everything from here to the end of run() happens inside ONE frame.
  local first, firstwhy = raisedNothing(function() s:player():move(here) end)
  r, got = refused("once per frame", {
    function() return s:player():move(here) end,
    function() return s:player():move(here) end,
    function() return s:player():move(here) end,
    function() return s:player():move(here) end,
  })
  ok("one write goes out per frame, and every one after it in that frame raises naming the bound",
     first and r, got or firstwhy)

  local other = raisedNothing(function() s:world():place(here, 0) end)
  r, got = refused("once per frame", {function() return s:world():place(here, 0) end})
  ok("the bound is the VERB's: another verb still sends in that same frame, and is then bounded itself",
     other and r, got)

  -- The shape half. A shape is refused before the frame bound is spent, so each of these reaches its own
  -- row on a verb whose bound is already gone -- which is what makes the message it names the shape's.
  r, got = refused("button must be 1, 2 or 3", {function() return s:world():place(here, 0, 9) end})
  ok("a button no mouse has is refused, naming the three it has", r, got)

  r, got = refused("Shift=1, Ctrl=2 and Alt=4", {
    function() return s:world():place(here, 0, 1, 99) end,
    function() return s:world():place(here, 0, 1, 8) end,
  })
  ok("a modifier field outside the three keys the client sends is refused, naming them", r, got)

  r, got = refused("a drag spans at most", {function() return s:world():select(here, far) end})
  ok("an area larger than a drag can span is refused, naming its extent in tiles", r, got)

  local ch = entryChannel(s)
  if ch then
    r, got = refused("one typed line is at most", {function() return ch:send(string.rep("a", 5000)) end})
    ok("a chat line longer than one a player can type is refused, naming the cap", r, got)
    r, got = refused("control character", {
      function() return ch:send("one\ntwo") end,
      function() return ch:send("one\ttwo") end,
    })
    ok("a chat line carrying a newline or a tab is refused: an entry line composes ONE line", r, got)
  else
    ok("a chat line longer than one a player can type is refused, naming the cap", false, "no entry channel")
    ok("a chat line carrying a newline or a tab is refused: an entry line composes ONE line",
       false, "no entry channel")
  end

  local kinLong, longwhy = refused("one typed line is at most",
    {function() return s:kin():add(string.rep("x", 500)) end})
  r, got = refused("control character", {function() return s:kin():add("one\ntwo") end})
  ok("the kin window's own field is bounded the same way, by length and by line",
     kinLong and r, got or longwhy)

  hafen.timer():after(0.5, function() nextFrame(s, here) end)
end

hafen.console():on("tR07", function() hafen.timer():after(0, run) end)
