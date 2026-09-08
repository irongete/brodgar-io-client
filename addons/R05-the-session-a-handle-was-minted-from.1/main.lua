-- R05 -- the session a handle was minted from.
--
-- One mechanism: a handle carries the login it was minted through, and every read on it resolves in that
-- login and no other. A Gob answers about the character whose door handed it to you, so gob:exists() is
-- that character's own line of sight; the overlay collection under it inherits the same login; a place with
-- NO anchor keeps the login it was read in, and an addressed door handed one for another character refuses
-- rather than answering ground however far apart the two of them stand. Beside it, the two trees the client
-- draws -- the session and the addon layer -- are both walked, so a point over one of your own windows is
-- over that window.
--
-- Everything below is read-only or is a refusal: the one thing it builds is a window of its own, which it
-- destroys before it reports. Run it with :tR05. It starts nothing by itself.
--
-- The checks run on the engine step rather than inside the console handler. A console line runs under the
-- monitor of the tree it was typed into, and the window this builds is in the addon layer -- a second tree,
-- which is the one thing a handler holding one may not reach. hafen.timer():after(0, fn) is the step, and
-- it holds neither.
--
-- With TWO characters logged in, the last two checks run themselves. With one there is nothing to compare a
-- login against, so they are asked for by hand -- log the second character in and run :tR05 again.
--
-- One run is one second: everything but the two trees is read at once, and those are read one beat later,
-- because a window is parented on the next tick and an overlay paints on the next frame.

local out, pass, fail, manual = {}, 0, 0, 0
local BOGUS = 999999999          -- an id no server ever published: no character can hold it
local KEY = "r05probe"

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

-- Is `node` this widget, or anything under it?
local function under(node, w)
  while node do
    if node == w then return true end
    node = node:parent()
  end
  return false
end

local function other(me)
  for _, s in ipairs(hafen.session():list()) do
    if s:user() ~= me:user() then return s end
  end
  return nil
end

local win, drawn, place

local function report()
  if win then pcall(function() win:destroy() end) end
  win = nil
  pcall(function() hafen.ui():overlay():remove(KEY) end)
  for _, l in ipairs(out) do hafen.log():write(l) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- --------------------------------------------------------- the second login, or the ask

local function crossLogin(me)
  local alt = other(me)
  if not alt then
    todo("log a second character in, stand the two apart, and run :tR05 again",
         "the two checks asked for here run themselves and read pass")
    todo("read a place on the first character and hand it to the second with alt:world():snapPlace(p)",
         "refused, naming that the place was read in another login")
    return
  end
  local id = me:player():gob():id()
  local a, b = me:world():gob():get(id), alt:world():gob():get(id)
  ok("two characters holding one object get two handles and one id",
     (a ~= b) and (a:id() == b:id()) and (a:exists() == true),
     "same=" .. tostring(a == b) .. " ids " .. tostring(a:id()) .. "/" .. tostring(b:id()))
  local r, got = refused("read in another login", {function() return alt:world():snapPlace(place) end})
  ok("a place with no anchor is refused to another login, naming how to carry it", r, got)
end

-- --------------------------------------------------------- after a tick and a drawn frame

local function drawnChecks(me)
  local at = win:rootPos()
  local hit = at and hafen.ui():hit(at.x + 30, at.y + 30)
  ok("a point over your own window answers your window, not the tree behind it",
     (hit ~= nil) and under(hit, win), tostring(hit))
  local tip = at and hafen.ui():tipAt(at.x + 30, at.y + 30)
  ok("the tooltip at that point is your window's too",
     (tip ~= nil) and under(tip, win) and (tip:tooltip() == "r05"),
     tostring(tip) .. " tip=" .. tostring(tip and tip:tooltip()))
  ok("a screen overlay paints over the finished screen, windows included", drawn == true, tostring(drawn))
  crossLogin(me)
end

-- --------------------------------------------------------- the checks

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  drawn, win, place = false, nil, nil
  local me = hafen.session():current()
  local mine = me and me:player():gob()
  local here = mine and mine:position()
  if not here then
    line("[fail] no character is in the world -- log one in, wait for the map, and run :tR05 again")
    return report()
  end
  local r, got

  -- 1. The mint carries the login, and interning is per (login, id): one character reaching one object two
  --    ways is one handle, and it reads in that character.
  ok("a Gob is one handle per login and reads in the login it was minted through",
     (mine == me:world():gob():get(mine:id())) and (mine:exists() == true),
     tostring(mine) .. " exists=" .. tostring(mine:exists()))

  -- 2. ...and an object THAT character does not hold reads nothing at all, while its id still answers.
  local none = me:world():gob():get(BOGUS)
  ok("an object that character does not hold answers nothing but its id",
     (none:id() == BOGUS) and (none:exists() == false) and (none:position() == nil)
       and (none:name() == nil) and (none:sdt() == nil),
     "exists=" .. tostring(none:exists()) .. " pos=" .. tostring(none:position()))

  -- 3. The collection hanging off it inherits the same login, both halves of it: the read is empty and the
  --    write is refused, rather than the two of them disagreeing about which copy they are looking at.
  r, got = refused("that gob is gone", {function() return none:overlay():add("r05") end})
  ok("the overlay collection under it reads in the same login: empty, and its writes refused",
     r and (#none:overlay():list() == 0) and (none:overlay():get("r05") == nil),
     got or ("list=" .. #none:overlay():list()))

  -- 4. A thing standing in the scene is anchored to the copy THE SCENE holds, so an object the character on
  --    screen cannot see is refused rather than followed out of somebody else's frame.
  local ring = {here:offset(-6, -6), here:offset(6, -6), here:offset(0, 6)}
  r, got = refused("cannot see that gob",
                   {function() return hafen.virtual():patch():add(ring, none) end,
                    function() return hafen.virtual():sprite():add({}, none) end})
  ok("a thing standing in the world is refused an anchor the character on screen cannot see", r, got)

  -- 5. A place over ground nobody has recorded keeps the world form AND the login it was read in, and its
  --    own verbs re-derive in that login rather than in whichever character is drawn.
  place = me:world():position(1e9, 1e9)
  local moved = place:offset(0, 11)
  ok("a place with no anchor keeps its world form and answers in its own login",
     (place:durable() == false) and (place:x() == 1e9) and (moved:x() == 1e9)
       and (moved:y() == 1e9 + 11),
     "durable=" .. tostring(place:durable()) .. " x=" .. tostring(place:x()))

  -- 6. A grid handle carries its login too, so grid:live() is that character's own streaming.
  local g = me:world():grid():at(here)
  ok("a grid reads in the login it was minted through",
     (g ~= nil) and (g:live() == true) and (g == me:world():grid():get(g:id())),
     tostring(g) .. " live=" .. tostring(g and g:live()))

  -- The two trees the client draws. The window is parented on the next tick and the overlay paints on the
  -- next frame, so both are read one beat later.
  win = hafen.ui():window():title("R05"):position(40, 40):size(120, 60):tooltip("r05")
  hafen.ui():overlay():add(KEY):draw(function() drawn = true end)
  -- The report and the cleanup run whatever that half does: a throw there would otherwise leave the window
  -- standing and print nothing at all, which reads as a suite that did not run.
  hafen.timer():after(1, function()
    local fine, err = pcall(function() drawnChecks(me) end)
    if not fine then
      fail = fail + 1
      line("[fail] the two-tree half raised -- got: " .. why(err))
    end
    report()
  end)
end

hafen.console():on("tR05", function() hafen.timer():after(0, run) end)
