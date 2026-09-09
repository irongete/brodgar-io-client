-- R10 -- interning through one helper.
--
-- One question, asked of every surface the block moved onto the one intern cache: is a read the SAME
-- object twice? A font handle, a subscription, a widget's own Rule (alive and destroyed), the Widget and
-- marker collections, the FEP bar and its food events, the Player and its Hand, and the petals of a ring
-- against the very objects FlowerMenuAdded hands over. Two refusals go with them, on the two paths whose
-- mint changed: a font name that is not a built-in, and the keyless collection of food events.
--
-- Run it with :tR10, with a character in the world. It starts nothing by itself and declares no
-- permission. The last check scores what a ring opened in its eight-second window shows it, so the run
-- prints its results after that window rather than at once.

local L = hafen.log()

local out, pass, fail, man = {}, 0, 0, 0

local function ok(what)
  pass = pass + 1
  out[#out + 1] = "[pass] " .. what
end

local function bad(what, got)
  fail = fail + 1
  out[#out + 1] = "[fail] " .. what .. " -- got: " .. tostring(got)
end

local function check(what, cond, got)
  if cond then ok(what) else bad(what, got) end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the strip has
-- to allow both shapes or every message read starts with the chunk name.
local function why(e)
  e = tostring(e or "")
  return (e:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Call fn and answer: was it refused, and what did it say.
local function refused(fn)
  local done, err = pcall(fn)
  if done then return false, "<no error>" end
  return true, why(err)
end

local function says(text, needle)
  return text:find(needle, 1, true) ~= nil
end

local function flush()
  for _, line in ipairs(out) do L:write(line) end
  L:write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. man .. " manual")
end

local function run()
  local s = hafen.session():current()
  if not s then
    L:write("[fail] a character has to be in the world -- got: no session on screen")
    L:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The built-in font: one handle per name, and the collection that lists it reads the same cache.
  local f, listed = hafen.font():get("mono"), false
  for _, h in ipairs(hafen.font():list()) do
    if h == f then listed = true end
  end
  check("hafen.font():get(\"mono\") is one handle, and hafen.font():list() lists that very handle",
        (f == hafen.font():get("mono")) and listed and (hafen.font():count("mono") >= 1),
        tostring(f) .. " listed=" .. tostring(listed))

  local no, msg = refused(function() hafen.font():get("R10nosuch") end)
  check("a font name outside the built-ins mints nothing and is refused naming them",
        no and says(msg, "sans") and says(msg, "R10nosuch"), msg)

  -- A subscription is the object :on() handed back, and the one the hub's own walk hands back.
  local sub, found = hafen.event():on("SessionAdded", function() end), false
  for _, x in ipairs(hafen.event():list()) do
    if x == sub then found = true end
  end
  sub:off()
  check("a subscription is the object :on() gave back, and hafen.event():list() hands back that one",
        found, "the walk minted a second handle")

  -- A widget's own level, while it is in the tree and after it has left it.
  local win = hafen.ui():window():title("R10")
  local w1 = win:rule()
  local live = (w1 == win:rule())
  win:destroy()
  check("widget:rule() is one Rule object, and the SAME one after the widget is destroyed",
        live and (w1 == win:rule()), "live=" .. tostring(live))

  check("s:ui():root() and hafen.map():marker() are each one object on every call",
        (s:ui():root() == s:ui():root()) and (hafen.map():marker() == hafen.map():marker()),
        "a second read was a different object")

  -- The character's food: two live objects and the members of the bar.
  local food = s:char():food()
  local fep = food and food:fep()
  if not fep then
    bad("food:fep(), food:hunger() and the food events on the bar are one object each",
        "no character sheet: s:char():food() is " .. tostring(food))
  else
    local a, b, same = fep:entry():list(), fep:entry():list(), true
    for i = 1, #a do
      if a[i] ~= b[i] then same = false end
    end
    check("food:fep(), food:hunger() and every food event are one object each (entries=" .. #a .. ")",
          (fep == food:fep()) and (food:hunger() == food:hunger()) and same, "a second read differed")
    no, msg = refused(function() fep:entry():get(1) end)
    check("fep:entry():get(n) is refused, naming :find(needle) and the position instead",
          no and says(msg, "fep:entry():find(needle)") and says(msg, "list()[n]"), msg)
  end

  -- The Player, and the Hand interned on it. An empty cursor has no Hand, and the line says which it was.
  local p = s:player()
  local hand = p:hand()
  check("s:player() is one object, and s:player():hand() is one object (cursor: "
        .. (hand and "holding" or "empty") .. ")",
        (p == s:player()) and (p:hand() == p:hand()), "a second read was a different object")

  -- The petals of a ring, against the very objects the event handed over. Scored over what opens.
  local rings, petals, petalsOk = 0, 0, true
  local ring = hafen.event():on("FlowerMenuAdded", function(payload)
    rings = rings + 1
    local now = s:flowermenu():list()
    for i = 1, #payload do
      petals = petals + 1
      if (payload[i] ~= now[i]) or (payload[i] ~= s:flowermenu():get(i)) then petalsOk = false end
    end
  end)
  L:write("[R10] right-click something within 8 s so a ring opens; the results follow that window")
  hafen.timer():after(8, function()
    ring:off()
    check("every petal of a ring is the object FlowerMenuAdded handed over (rings=" .. rings
          .. ", petals=" .. petals .. ")", petalsOk, "a petal read back as a different object")
    flush()
  end)
end

-- The console handler runs under the typed tree's monitor, so the run is deferred by a tick.
hafen.console():on("tR10", function()
  hafen.timer():after(0, run)
end)
