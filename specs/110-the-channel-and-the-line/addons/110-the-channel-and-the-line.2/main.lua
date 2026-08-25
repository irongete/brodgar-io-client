-- 110.2 -- the channels, the one on screen, and saying a line. Self-checking suite.
--
-- WHAT THIS SHIPS. s:chat(), minted per session: the collection of the channels one character holds,
-- :selected() for the one on screen and :selected(ch) to put another there, a Channel answering :name(),
-- :kind(), :urgency(), :exists() and :info(), ch:send(text) behind "chat.send", and the three events
-- ChannelAdded / ChannelRemoved / ChannelSelected.
--
-- HOW IT IS PROVED. Four things a reader has to be able to trust, and each is read back rather than
-- assumed. (1) The collection is interned: :selected() is not merely a channel with the same name as one
-- in :list(), it is that very object, so a table keyed by a channel works. (2) The kinds are a CLOSED set
-- of four words, which is what makes a rule on one and a read of one name the same thing -- so every kind
-- in :list() is checked against the four rather than sampled. (3) A write is observed twice: read straight
-- back off :selected(), and reported once by ChannelSelected with that channel and that session. Once, not
-- at-least-once -- the events are counted, because a seam that fires twice is the failure the queue exists
-- to prevent and it passes any "did it fire" test. (4) Both refusals SAY what to write instead: the
-- missing :get names its two doors by name, and the System log names the kinds that do take a line.
--
-- The permission is DECLARED here, so the gate is proved from the granted side and the line actually
-- reaches the game -- which is the one thing a program cannot observe and the only [manual] line below.
-- That a key stays per-key is proved from the other side, with a verb this suite did not ask for.

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

-- A refusal is a check: the call must fail, and fail SAYING every one of `wants`.
local function refuses(what, fn, wants)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local missing = nil
  if not ok then
    for _, w in ipairs(wants) do
      if err:find(w, 1, true) == nil then missing = w end
    end
  end
  check((not ok) and (missing == nil), what, ok and err or ("no mention of " .. tostring(missing)
    .. " in: " .. err))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local KINDS = {["chat"] = true, ["chat.system"] = true, ["chat.party"] = true, ["chat.private"] = true}
local LINE = "110.2 suite"
local unreached = {}

local function summary()
  local missed = (#unreached == 0) and "" or (" -- not reached: " .. table.concat(unreached, ", "))
  hafen.log():write(("[summary] %d pass, %d fail, %d manual%s"):format(pass, fail, manual, missed))
end

-- The half that runs after the tick has drained the queue: the event the write above should have made.
local function drained(s, chat, was, other, seen, sub)
  sub:off()
  check(#seen == 1, "ChannelSelected fired exactly once for that write", #seen)
  local e = seen[1]
  check((e ~= nil) and (e.ch == other) and (e.s == s),
        "...carrying that very channel, and its session last",
        (e == nil) and "nothing" or (tostring(e.ch) .. " / " .. tostring(e.s)))
  chat:selected(was)                      -- put the player's own tab back (the sub is off, so silent)

  local say = chat:find(function(c) return c:kind() ~= "chat.system" end)
  if say == nil then
    unreached[#unreached + 1] = "saying a line (no channel with an entry line)"
  else
    check(say:send(LINE) == say, "ch:send(text) hands the channel back", tostring(say))
    manualCheck("read the last line of the '" .. (say:name() or "?") .. "' channel",
                "the line \"" .. LINE .. "\", drawn as one of your OWN lines")
  end
  summary()
end

local function body()
  local s = hafen.session():current()
  if s == nil then
    check(false, "a character is on screen to read a chat from", "no session holds the screen")
    return summary()
  end
  local chat = s:chat()
  check(chat == s:chat(), "s:chat() is the same object every call", tostring(chat))

  local list = chat:list()
  local badkind = nil
  for _, ch in ipairs(list) do
    if not KINDS[ch:kind()] then badkind = tostring(ch:name()) .. " = " .. tostring(ch:kind()) end
  end
  check((#list > 0) and (badkind == nil),
        "every channel's kind is one of the four the client can wear",
        (#list == 0) and "no channels at all" or badkind)

  local was, at = chat:selected(), nil
  for i, ch in ipairs(list) do if ch == was then at = i end end
  check(at ~= nil, "s:chat():selected() IS a member of :list(), by identity",
        (was == nil) and "nothing is selected" or "not in the list")

  local info = (was == nil) and nil or was:info()
  check((info ~= nil) and (info.kind == was:kind()) and (info.urgency == was:urgency())
          and (info.name == was:name()),
        "ch:info() copies out exactly what :name(), :kind() and :urgency() read",
        (info == nil) and "nil" or (tostring(info.name) .. " / " .. tostring(info.kind)
          .. " / " .. tostring(info.urgency)))

  refuses("s:chat():get is refused, naming both doors that do exist",
          function() return chat:get("Party") end,
          {"session:chat():find(needle)", "session:chat():list()[n]"})

  local sys = chat:find(function(c) return c:kind() == "chat.system" end)
  refuses("ch:send on the System log is refused, naming the kinds that take a line",
          function() return sys:send("x") end, {'"chat"', '"chat.party"', '"chat.private"'})

  refuses("a key this suite did not declare still refuses",
          function() return s:speed():set(1) end, {"speed.set"})

  refuses("s:chat():selected(ch) refuses a value that is not a Channel",
          function() return chat:selected("Party") end, {"Channel object"})

  local other = nil
  for _, ch in ipairs(list) do if ch ~= was then other = ch break end end
  if (other == nil) or (was == nil) then
    unreached[#unreached + 1] = "the selection write (this character has only one channel)"
    return summary()
  end

  local seen, sub = {}, nil
  sub = hafen.event():on("ChannelSelected", function(ch, sess)
    seen[#seen + 1] = {ch = ch, s = sess}
  end)
  chat:selected(other)
  check(chat:selected() == other, "s:chat():selected(ch) reads back on the very next line",
        tostring(chat:selected()))

  -- The seam queues and the tick drains it, so the event is a frame away, not a line away.
  hafen.timer():after(0.5, function()
    local ok, err = pcall(drained, s, chat, was, other, seen, sub)
    if not ok then
      check(false, "the deferred half reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      summary()
    end
  end)
end

-- The run is one pcall, so a read that throws becomes one [fail] line and a summary rather than a bare
-- stack trace with no verdict under it.
local function run()
  pass, fail, manual, unreached = 0, 0, 0, {}
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    summary()
  end
end

hafen.console():on("t110", run)   -- the only way in: a suite does not start itself
