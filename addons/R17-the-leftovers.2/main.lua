-- R17.2 -- the player's hand and the chat line: the cases pl-14 and ct-15 say no suite ever covered.
-- It runs only from :tR17-2, sends nothing on the wire (every write here is a call the client refuses
-- before the wire), restores the chat tab it moves, and scores what one run reached over a 10 s window.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level error
-- adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both: the class is
-- [^\n] rather than . so a traceback under the message cannot be eaten as a third prefix.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?[^\n]-%.lua:%d+:?[ \t]*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

-- Did fn refuse, and did it say why? Answers nil when it did, and what went wrong when it did not.
local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

local function allRefuse(name, cases, also, got)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, (bad == nil) and (also ~= false), bad or got)
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

-- The receiver this check needs is one only the PLAYER can put there, and no key this suite holds could
-- put it there itself. So the line says the one gesture that turns it into a [pass].
local function needs(action)
  manual = manual + 1
  say("[manual] " .. action .. ", then type :tR17-2 -- expect: this line becomes a [pass]")
end

local function len(t) return (type(t) == "table") and #t or -1 end

-- ---- what the window saw ----------------------------------------------------------------------------

local st = {samples = 0, agree = true, midLogin = 0, alt = nil, altHand = nil, hand = nil, mark = nil}

local function sample()
  local cur = read(function() return hafen.session():current() end)
  for _, s in ipairs(read(function() return hafen.session():list() end) or {}) do
    -- pl-14: the two reads the page pairs. A character with no HUD has no Gob at all; from the moment it
    -- has one, the Gob is there with :exists() false until the body streams -- which is the beat the row
    -- says nothing covers, and the beat a second login walks through while this window is open.
    local c = read(function() return s:character() end)
    local g = read(function() return s:player():gob() end)
    st.samples = st.samples + 1
    if (c == nil) ~= (g == nil) then st.agree = false end
    if (c == nil) or ((g ~= nil) and (read(function() return g:exists() end) == false)) then
      st.midLogin = st.midLogin + 1
    end
    if (cur ~= nil) and (s ~= cur) and (read(function() return s:exists() end) == true) then
      st.alt = st.alt or s
      st.altHand = st.altHand or read(function() return s:player():hand() end)
    end
  end
  if cur ~= nil then
    st.hand = st.hand or read(function() return cur:player():hand() end)
    -- ct-15: a line carrying the rich-text markup characters, which is what the quoting rule is about.
    local ch = read(function() return cur:chat():selected() end)
    local n = ch and read(function() return ch:message():count() end) or 0
    for i = math.max(1, n - 40), n do
      local m = read(function() return ch:message():get(i) end)
      local raw = m and read(function() return m:raw() end)
      if (type(raw) == "string") and raw:find("[%${}]") then st.mark = st.mark or m end
    end
  end
end

-- ---- pl-14: the hand ---------------------------------------------------------------------------------

-- An item of some OTHER character's tree, for the one refusal that needs two logins.
local function foreignItem()
  if st.alt == nil then return nil end
  return (read(function() return st.alt:ui():inventory():items():list() end) or {})[1]
end

local function hand()
  local h = st.hand
  if h == nil then
    needs("pick any item up onto the cursor")
    return
  end
  local it = read(function() return h:item() end)
  local foreign = foreignItem()
  -- Every one of these is refused BEFORE anything reaches the wire: the missing target, the two bad
  -- modifier values (the bitfield is checked by the wire's own shape row, the wholeness by the verb), the
  -- table that is none of the three types, and the item that belongs to another character's tree.
  local cases = {
    {"use()",           function() return h:use() end,          "target is required"},
    {"use(item, 8)",    function() return h:use(it, 8) end,     "mods is a bitfield"},
    {"use(item, 1.5)",  function() return h:use(it, 1.5) end,   "mods must be a whole number"},
    {"use{x=1, y=2}",   function() return h:use({x = 1, y = 2}) end,
                                                                "target must be an Item"},
  }
  if foreign ~= nil then
    cases[#cases + 1] = {"use(an alt's item)", function() return h:use(foreign) end,
                         "another character's tree"}
  end
  allRefuse("the hand refuses the target it cannot take, and the wire refuses mods 8"
            .. ((foreign ~= nil) and ", a foreign item included" or ""), cases)
  if foreign == nil then
    needs("log a second character in with something in its backpack (the foreign-item refusal)")
  end
end

local function backgroundHand(cur)
  -- The item branch asks for the SENDING view first, so ANY Item at all reaches the refusal this line is
  -- about: the target is never looked at for a character that is not the one on screen.
  local it = foreignItem() or (read(function() return cur:ui():inventory():items():list() end) or {})[1]
  if (st.altHand == nil) or (it == nil) then
    needs("log a second character in and put any item on ITS cursor")
    return
  end
  local h = st.altHand
  allRefuse("a hand of a character that is not on screen refuses, naming the one that is", {
    {"alt hand:use", function() return h:use(it) end, "that character is not on screen"},
  })
end

local function gob()
  if st.midLogin == 0 then
    needs("log a second character in while this 10 s window runs (the beat before its body streams)")
    return
  end
  ok("a character's Gob and its HUD arrive together, and the Gob is there before the body is",
     st.agree, st.samples .. " samples, " .. st.midLogin .. " of them mid-login")
end

-- ---- ct-15: the chat line ----------------------------------------------------------------------------

-- The rich-text quoting the chat draws every line through: $, { and } each gain a $ in front.
local function quote(s) return (s:gsub("([%${}])", "$%1")) end

-- The three kinds that HAVE an entry line. The System log is written by the client and nobody says
-- anything in it, so it is the channel the last case below needs and the wrong one for the rest --
-- and on this client it is the first tab, which is why the channel is chosen by kind and never by index.
local ENTRY = {chat = true, ["chat.party"] = true, ["chat.private"] = true}

local function speaking(cur)
  local from = st.alt or cur
  local sys = read(function() return from:chat():find(function(c) return c:kind() == "chat.system" end) end)
  local ch = read(function() return from:chat():find(function(c) return ENTRY[c:kind()] == true end) end)
  if ch == nil then
    ok("a line is refused before the wire, whichever login the channel belongs to", false,
       "that login has no channel with an entry line -- open Area Chat or a party")
    return
  end
  -- Nothing here reaches the wire: the permission is held, and each of these raises in front of it.
  local cases = {
    {"send(empty)", function() return ch:send("") end, "text is empty"},
    {"send(newline)", function() return ch:send("a\nb") end, "carries a control character"},
    {"send(513 chars)", function() return ch:send(string.rep("x", 513)) end,
                       "one typed line is at most 512"},
    {"send(a number)", function() return ch:send(42) end, "text must be a string"},
  }
  if sys ~= nil then
    cases[#cases + 1] = {"send in the System log", function() return sys:send("x") end, "entry line"}
  end
  allRefuse("a line is refused before the wire, on the "
            .. ((st.alt ~= nil) and "background" or "drawn") .. " login's channel", cases,
            true, tostring(read(function() return ch:name() end)))
end

local function markup(cur)
  local m = st.mark
  if m == nil then
    needs("say a line carrying $img{} in any channel")
    return
  end
  local raw, txt = read(function() return m:raw() end), read(function() return m:text() end)
  ok("a line's markup is quoted in :text() and left standing in :raw()",
     (type(raw) == "string") and (txt == quote(raw)) and (txt ~= raw),
     tostring(raw) .. " -> " .. tostring(txt))
end

local function tabs(cur)
  local chans = read(function() return cur:chat():list() end) or {}
  local orig = read(function() return cur:chat():selected() end)
  if len(chans) < 1 then
    ok("the chat tab is written and read back, and refuses what is not this character's", false,
       "no channel")
    return
  end
  local cases = {
    {"selected(1)",   function() return cur:chat():selected(1) end,     "expected a Channel object"},
    {"selected(cur)", function() return cur:chat():selected(cur) end,   "expected a Channel object"},
  }
  local foreign = st.alt and (read(function() return st.alt:chat():list() end) or {})[1]
  if foreign ~= nil then
    cases[#cases + 1] = {"selected(an alt's channel)",
                         function() return cur:chat():selected(foreign) end, "another character's"}
  end
  -- The write itself needs no permission: the tab moves for anybody, and only the keyboard behind it is
  -- the ui.focus key's. The run puts the player's own tab back before it scores.
  local moved, now, back = nil, orig, orig
  if orig ~= nil then
    moved = read(function() return cur:chat():selected(chans[1]) end)
    now = read(function() return cur:chat():selected() end)
    read(function() return cur:chat():selected(orig) end)
    back = read(function() return cur:chat():selected() end)
  end
  allRefuse("the chat tab is written and read back unprotected, and refuses what is not this character's",
            cases, (orig == nil) or ((moved ~= nil) and (now == chans[1]) and (back == orig)),
            tostring(now) .. " then back to " .. tostring(back))
end

-- Seconds per call, over batches of 200 -- os.clock is a coarse clock, so the batch is repeated until it
-- has moved far enough to divide by, and the answer is scaled back to the 200 the bound is written for.
local function perCall(fn)
  local t0, calls = os.clock(), 0
  repeat
    for _ = 1, 200 do fn() end
    calls = calls + 200
  until ((os.clock() - t0) >= 0.03) or (calls >= 100000)
  return (os.clock() - t0) / calls
end

local function cost(cur)
  local ch = read(function() return cur:chat():selected() end)
             or (read(function() return cur:chat():list() end) or {})[1]
  if ch == nil then
    ok("counting a scrollback costs a bounded time, where copying one does not", false, "no channel")
    return
  end
  local n = read(function() return ch:message():count() end) or 0
  local c200 = perCall(function() ch:message():count() end) * 200 * 1000        -- ms per 200 calls
  local l200 = (n >= 20) and (perCall(function() ch:message():list() end) * 200 * 1000) or nil
  -- LuaJ prints a double raw whatever precision a format asks for, so the number is rounded by hand.
  local function us(ms) return tostring(math.floor((ms * 1000) + 0.5)) end
  ok("counting a scrollback costs a bounded time, where copying one does not",
     (c200 <= 2) and ((l200 == nil) or (l200 > c200)),
     n .. " lines: count " .. us(c200) .. " us/200, list "
       .. ((l200 ~= nil) and (us(l200) .. " us/200") or "not measured"))
end

-- ---- the run -----------------------------------------------------------------------------------------

local WINDOW, EVERY = 10.0, 0.5

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function report()
  local cur = read(function() return hafen.session():current() end)
  if cur == nil then
    fail = fail + 1
    say("[fail] no character was on screen for the whole window -- log in and run :tR17-2 again")
    finish()
    return
  end
  hand()
  backgroundHand(cur)
  gob()
  speaking(cur)
  markup(cur)
  tabs(cur)
  cost(cur)
  finish()
end

hafen.console():on("tR17-2", function()
  pass, fail, manual = 0, 0, 0
  st = {samples = 0, agree = true, midLogin = 0, alt = nil, altHand = nil, hand = nil, mark = nil}
  -- The typed command runs under the console tree's monitor, so the run is deferred to the step exactly as
  -- every suite that reads a widget tree is. The window is what a second login has to walk through.
  local left, ticker = WINDOW, nil
  ticker = hafen.timer():every(EVERY, function()
    sample()
    left = left - EVERY
    if left <= 0 then
      ticker:cancel()
      report()
    end
  end)
end)
