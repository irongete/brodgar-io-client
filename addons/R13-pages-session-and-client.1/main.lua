-- R13 -- pages: session and the client map. Every check below asserts, through the API itself, a
-- sentence the block wrote onto a page. It runs only from :tr13 and leaves nothing behind.

local pass, fail = 0, 0

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

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level
-- error adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?.-%.lua:%d+:?%s*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

local function refuses(name, fn, needle)
  local good, err = pcall(fn)
  if good then
    ok(name, false, "<no error>")
  else
    local msg = why(err)
    ok(name, msg:find(needle, 1, true) ~= nil, msg)
  end
end

-- The named display entry of the action menu, for the case-insensitive lookup below.
local function namedEntry(s)
  for _, pag in ipairs(s:menugrid():list()) do
    local nm = pag:name()
    if nm and not nm:find("/", 1, true) then return nm end
  end
end

local function run(s)
  if not s then
    fail = fail + 1
    say("[fail] a character in the world -- got: no session, so nothing below could be asked")
    say("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- conventions.md: a section's collection is one object; a partition of one is a view.
  ok("the skill collection is interned and :buyable() is a view",
     (s:char():skill() == s:char():skill()) and (s:char():skill():buyable() ~= s:char():skill():buyable()),
     tostring(s:char():skill():buyable()))

  -- wound.md: :roots() is a real verb, and it is a view like :children().
  local roots = s:wound():roots()
  ok("s:wound():roots() answers and is a view",
     (roots:count() >= 0) and (roots ~= s:wound():roots()), tostring(roots:count()))

  -- craft.md: the four slot reads are views minted per call.
  ok("a craft slot read is a view",
     (s:craft():inputs():count() >= 0) and (s:craft():inputs() ~= s:craft():inputs()),
     tostring(s:craft():inputs()))

  -- fight.md: the deck is a collection, so the sequence operators are refused...
  refuses("# is refused on the deck", function() return #s:fight():deck() end, "is how many")
  -- ...and it carries no key, naming the two doors that do exist.
  refuses("the deck has no :get and says what to use",
          function() return s:fight():deck():get(1) end, "a deck card has no key")

  -- party.md: there is no party event of any kind.
  refuses("there is no party event", function()
    return hafen.event():on("PartyChanged", function() end)
  end, "unknown event")

  -- flowermenu.md: :get(n) is a position, and a caption is not one.
  ok("a ring position past the ring is nil", s:flowermenu():get(1) == nil,
     tostring(s:flowermenu():get(1)))
  refuses("a ring position must be a number",
          function() return s:flowermenu():get("Chop") end, "must be a number")

  -- kin.md: every write is gated, and the gate fires before anything is sent.
  refuses("adding a kin without the key names it",
          function() return s:kin():add("no-such-secret") end, "kin.add")

  -- char.md: both food halves carry the liveness verb the page now lists.
  local food = s:char():food()
  ok("the fep and hunger halves answer :exists()",
     (food ~= nil) and (type(food:fep():exists()) == "boolean")
       and (type(food:hunger():exists()) == "boolean"), tostring(food))

  -- player.md: the read is a handle from the moment the HUD is up, never a nil to test.
  ok("the player's gob is a handle, and :exists() is the question",
     (s:player():gob() ~= nil) and (type(s:player():gob():exists()) == "boolean"),
     tostring(s:player():gob()))

  -- menugrid.md: a display name is matched case-insensitively.
  local nm = namedEntry(s)
  ok("a display name is matched case-insensitively",
     (nm ~= nil) and (s:menugrid():get(nm:upper()) == s:menugrid():get(nm))
       and (s:menugrid():get(nm) ~= nil), tostring(nm))

  -- meter.md: the segment collection and the segments in it are views.
  local m = s:meter():list()[1]
  ok("a meter's segments are a view, and so is each segment",
     (m ~= nil) and (m:segment() ~= m:segment())
       and (m:segment():list()[1] ~= m:segment():list()[1]), tostring(m))

  -- chat.md: a channel's lines are a view off the channel.
  local ch = s:chat():list()[1]
  ok("a channel's message collection is a view",
     (ch ~= nil) and (ch:message() ~= ch:message()), tostring(ch))

  say("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

-- Every check but one needs a character in the world with its HUD up, which the server produces and
-- this addon cannot. So the run waits for it on a bounded window and then scores what it reached.
local function ready()
  local s = hafen.session():current()
  if not (s and s:char():food() and s:meter():list()[1] and s:chat():list()[1]) then return nil end
  return (namedEntry(s) ~= nil) and s or nil
end

local function start(tries)
  local s = ready()
  if s or (tries <= 0) then
    run(s or hafen.session():current())
  else
    hafen.timer():after(0.5, function() start(tries - 1) end)
  end
end

hafen.console():on("tr13", function()
  pass, fail = 0, 0
  hafen.timer():after(0, function() start(20) end)
end)
