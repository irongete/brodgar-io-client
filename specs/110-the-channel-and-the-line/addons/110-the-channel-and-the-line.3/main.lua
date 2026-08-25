-- 110.3 -- the lines. Self-checking suite.
--
-- WHAT THIS SHIPS. ch:message(): a channel's scrollback as a collection -- :list/:count/:find and
-- :get(i), 1-based and oldest first -- and the Message object it holds, answering :text(), :kind(),
-- :color(), :time(), :speaker(), :mine(), :channel(), :exists() and :info(). MessageAdded fires for
-- every line that lands, carrying the Message and that character's session last.
--
-- HOW IT IS PROVED. The suite makes its own line rather than waiting for one: hafen.log():write goes to
-- the System log of the character on screen, so one write is one line whose every field is known in
-- advance -- text, kind, "not mine", no speaker. That is what makes the read a proof instead of a report.
-- Four things a reader has to be able to trust. (1) The event and the collection are the SAME object:
-- the payload is compared by identity against :get(:count()) and against :find(text), so an addon that
-- indexes lines as they arrive is indexing what it will read back. Once, not at-least-once -- the count
-- is asserted to have risen by exactly one, because a seam that fires twice passes any "did it fire"
-- test. (2) :channel() is interned equal to the channel the line was read from, which is what lets a
-- handler route a line without a name. (3) :time() is asserted through string.format("%d", ...) and
-- scanned back as a number, because tostring() renders it in scientific notation and a stamp nothing can
-- read back is not a stamp. (4) The refusal SAYS where the indices start, and a position past the newest
-- line is told apart from a position that is not one at all.
--
-- The two halves a program cannot cause -- a line YOU said, and a line somebody else said -- are read out
-- of what this character has already heard this login, newest first. Where the login has not carried one
-- yet the run says so and asks for it, and the next :t110 scores it.

local pass, fail, manual = 0, 0, 0
local runs = 0

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The scrollback read the way the page teaches it: the count, then the positions, newest first, and never
-- a :list() of a whole login's chat. Bounded, so a long login costs the same as a short one.
local function scanback(ch, limit, pred)
  local n = ch:message():count()
  for i = n, math.max(1, n - limit + 1), -1 do
    local m = ch:message():get(i)
    if (m ~= nil) and pred(m) then return m end
  end
  return nil
end

-- A line THIS character said, anywhere in the chat: its kind is its own rather than its channel's, in
-- every channel but a private conversation, where both halves wear the channel's and :mine() is the one
-- read that tells them apart.
local function ownLine(chat)
  for _, ch in ipairs(chat:list()) do
    local m = scanback(ch, 200, function(x) return x:mine() == true end)
    if m ~= nil then
      local want = (ch:kind() == "chat.private") and "chat.private" or "chat.mine"
      check((m:kind() == want) and (m:channel() == ch),
            'a line you said reads :mine() true and :kind() "' .. want .. '"',
            tostring(m:kind()) .. " in " .. tostring(ch:name()))
      return
    end
  end
  manualCheck("say a line in Area Chat, then run :t110 again",
              'a [pass] line reading: a line you said reads :mine() true and :kind() "chat.mine"')
end

-- A line SOMEBODY ELSE said, in a channel that names its speakers. :speaker() is a Kin of this
-- character's own roster, so it answers :id() and :name() and is not merely a string lifted off the line.
local function otherLine(chat)
  for _, ch in ipairs(chat:list()) do
    local m = scanback(ch, 200, function(x) return x:speaker() ~= nil end)
    if m ~= nil then
      local k = m:speaker()
      check((type(k:id()) == "number") and (m:mine() == false),
            ":speaker() is the person who said it, as a Kin of this character's roster",
            tostring(k) .. " / " .. tostring(k:name()))
      return
    end
  end
  manualCheck("have a kin say a line you can see, then run :t110 again",
              "a [pass] line reading: :speaker() is the person who said it")
end

-- The half that runs after the tick has drained the queue: what the one written line should have made.
-- The reads that must not see this suite's OWN output come first, before a single check() prints a word.
local function drained(s, sys, before, mark, seen, sub)
  sub:off()
  local after = sys:message():count()
  local newest = sys:message():get(after)
  local found = sys:message():find(mark)
  local stamp = string.format("%d", (newest ~= nil) and newest:time() or 0)
  local past = sys:message():get(after + 1000)
  local info = (newest ~= nil) and newest:info() or nil

  local mine = nil
  for _, m in ipairs(seen) do
    if (m.msg:text() or ""):find(mark, 1, true) ~= nil then
      mine = (mine == nil) and m or false          -- false marks a second firing for the one line
    end
  end

  check(after == before + 1, "one write put exactly one line in the System log",
        tostring(before) .. " -> " .. tostring(after))
  check((mine ~= nil) and (mine ~= false), "MessageAdded fired exactly once for that line",
        (mine == nil) and "nothing" or "more than once")
  if (mine == nil) or (mine == false) then return summary() end
  check((mine.msg == newest) and (mine.s == s) and (mine.msg:exists() == true),
        "...carrying that very line -- the one :get(:count()) answers -- and its session last",
        tostring(mine.msg) .. " vs " .. tostring(newest) .. " / " .. tostring(mine.s))
  check(mine.msg:channel() == sys, "msg:channel() is the channel it landed in, by identity",
        tostring(mine.msg:channel()))
  check((newest:kind() == "chat.system") and (newest:mine() == false) and (newest:speaker() == nil),
        'a System line reads kind "chat.system", :mine() false and no speaker',
        tostring(newest:kind()) .. " / " .. tostring(newest:mine()) .. " / "
          .. tostring(newest:speaker()))
  check((#stamp == 10) and (tonumber(stamp) > 1600000000) and (tonumber(stamp) < 2000000000),
        'msg:time() written with "%d" scans back as an epoch second (' .. stamp .. ")", stamp)
  check(found == newest, "ch:message():find(text) picks that very line", tostring(found))
  check((info ~= nil) and (info.kind == newest:kind()) and (info.mine == newest:mine())
          and (info.time == newest:time()) and (info.text == newest:text())
          and (info.speaker == nil),
        "msg:info() copies out exactly what the reads answer",
        (info == nil) and "nil" or (tostring(info.kind) .. " / " .. tostring(info.text)))

  refuses("ch:message():get(0) is refused, saying where the indices start",
          function() return sys:message():get(0) end, {"start at one"})
  check(past == nil, "a position past the newest line is a miss, not a mistake", tostring(past))

  local chat = s:chat()
  ownLine(chat)
  otherLine(chat)
  summary()
end

local function body()
  local s = hafen.session():current()
  if s == nil then
    check(false, "a character is on screen to read a chat from", "no session holds the screen")
    return summary()
  end
  local sys = s:chat():find(function(c) return c:kind() == "chat.system" end)
  if sys == nil then
    check(false, "this character has a System log to write a line into", "no chat.system channel")
    return summary()
  end

  local before = sys:message():count()
  local mark = "110.3 marker " .. runs .. "/" .. before
  local seen, sub = {}, nil
  sub = hafen.event():on("MessageAdded", function(msg, sess)
    seen[#seen + 1] = {msg = msg, s = sess}
  end)
  hafen.log():write(mark)          -- the one line this run makes, and the last word before the timer

  -- Channel.append queues and the tick drains it, so the event is a frame away, not a line away.
  hafen.timer():after(0.5, function()
    local ok, err = pcall(drained, s, sys, before, mark, seen, sub)
    if not ok then
      sub:off()
      check(false, "the deferred half reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      summary()
    end
  end)
end

-- The run is one pcall, so a read that throws becomes one [fail] line and a summary rather than a bare
-- stack trace with no verdict under it.
local function run()
  pass, fail, manual, runs = 0, 0, 0, runs + 1
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    summary()
  end
end

hafen.console():on("t110", run)   -- the only way in: a suite does not start itself
