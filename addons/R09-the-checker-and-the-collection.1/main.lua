-- R09 -- the checker and the collection.
--
-- Every check goes through a refusal the block wrote or moved: a surplus argument, an iteration over a
-- collection, a dot call on one, a bare :add(), a key outside a closed set, a spelling this API moved.
-- The read half is :count() against :list(), with and without a needle.
--
-- Run it with :tR09, with a character in the world. It starts nothing by itself and declares no
-- permission, so every refusal here is met before any gate.

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

-- Twenty-two verbs, each handed one argument more than it takes: the refusal has to say what the verb takes
-- and what it got. A verb that answered, or refused for another reason, is named in the fail line.
local function surplus()
  local sound = hafen.sound():get("sfx/msg")
  local sheet = hafen.ui():sheet()
  local font = hafen.font():get("sans")
  local calls = {
    {"hafen.log():write", function() hafen.log():write("R09", 99) end},
    {"hafen.event():list", function() hafen.event():list(nil, 99) end},
    {"hafen.event():count", function() hafen.event():count(nil, 99) end},
    {"hafen.locale():load", function() hafen.locale():load({}, 99) end},
    {"hafen.locale():install", function() hafen.locale():install(99) end},
    {"hafen.locale():release", function() hafen.locale():release(99) end},
    {"hafen.locale():info", function() hafen.locale():info(99) end},
    {"hafen.locale():miss", function() hafen.locale():miss(99) end},
    {"font:size", function() font:size(12, 99) end},
    {"options:video", function() hafen.client():options():video(99) end},
    {"profiling:reset", function() hafen.client():profiling():reset(99) end},
    {"sound:res", function() sound:res(99) end},
    {"sound:info", function() sound:info(99) end},
    {"sound:stop", function() sound:stop(99) end},
    {"sound:play", function() sound:play(1, 99) end},
    {"hafen.sound():playing", function() hafen.sound():playing(nil, 99) end},
    {"hafen.map():marker():nearest", function() hafen.map():marker():nearest(nil, 99) end},
    {"sheet:install", function() sheet:install(99) end},
    {"sheet:release", function() sheet:release(99) end},
    {"hafen.timer():list", function() hafen.timer():list(nil, 99) end},
    {"hafen.asset():count", function() hafen.asset():count(nil, 99) end},
    {"binding:get", function() hafen.client():options():keybindings():binding():get("x", 99) end},
  }
  local missed = {}
  for _, c in ipairs(calls) do
    local no, msg = refused(c[2])
    if not (no and says(msg, "takes ") and says(msg, ", got ")) then
      missed[#missed + 1] = c[1] .. " (" .. msg .. ")"
    end
  end
  check(#calls .. " verbs refuse a surplus argument, naming what they take and what they got",
        #missed == 0, table.concat(missed, "; "))
end

local function run()
  local s = hafen.session():current()
  if not s then
    L:write("[fail] a character has to be in the world -- got: no session on screen")
    L:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  surplus()

  local no, msg = refused(function() for _ in pairs(hafen.timer()) do end end)
  local no2, msg2 = refused(function() for _ in ipairs(hafen.timer()) do end end)
  check("pairs(coll) and ipairs(coll) are refused naming coll:list() as the array",
        no and no2 and says(msg, "hafen.timer():list()") and says(msg2, "ipairs is refused"), msg .. " / " .. msg2)

  no, msg = refused(function() hafen.timer().list("x") end)
  check("a dot call on a collection verb names the collection and what it got",
        no and says(msg, "hafen.timer():list()") and says(msg, "got string"), msg)

  no, msg = refused(function() hafen.virtual():ghost():add() end)
  check("a bare :add() is refused naming the argument the collection calls it",
        no and says(msg, "hafen.virtual():ghost():add") and says(msg, "res is required"), msg)

  local t = hafen.timer():after(3600, function() end)
  local n, all = hafen.timer():count(), hafen.timer():list()
  t:cancel()
  local skills = s:char():skill()
  check(":count() with no filter is the size, and a needle that matches everything counts the same",
        (n == #all) and (n >= 1) and (skills:count("") == skills:count()),
        n .. " vs " .. #all .. ", " .. skills:count("") .. " vs " .. skills:count())

  no, msg = refused(function() hafen.font():get("R09 nosuch") end)
  check("hafen.font():get(name) outside the built-ins is refused naming them",
        no and says(msg, "sans") and says(msg, "R09 nosuch"), msg)

  no, msg = refused(function() hafen.map():display():get("R09 nosuch") end)
  check("hafen.map():display():get(tag) outside the switches is refused naming them",
        no and says(msg, "the toggles it owns are") and says(msg, "R09 nosuch"), msg)

  no, msg = refused(function() hafen.event():on("FlowerMenuOpened", function() end) end)
  check("a moved event key names its replacement (FlowerMenuOpened is FlowerMenuAdded)",
        no and says(msg, "FlowerMenuAdded"), msg)

  no, msg = refused(function() return s:ui().find end)
  check("a moved section verb names its replacement (s:ui():find is :match)",
        no and says(msg, "session:ui():match(selector)"), msg)

  no, msg = refused(function() return s:char():skill().available end)
  check("a moved collection verb names its replacement (:available is :buyable)",
        no and says(msg, "session:char():skill():buyable(filter)"), msg)

  no, msg = refused(function() s:study():curiosity():get(1) end)
  check("the study collection refuses :get in its own spelling, naming :find",
        no and says(msg, "session:study():curiosity():find(needle)"), msg)

  no, msg = refused(function() return s:kin():get(1).nosuch end)
  check("a kin's closed index names :group(g) among the writes",
        no and says(msg, ":group(g)"), msg)

  check("s:kin():get(name) for nobody on the roster answers nil",
        s:kin():get("R09 nobody at all") == nil, tostring(s:kin():get("R09 nobody at all")))

  flush()
end

-- The console handler runs under the typed tree's monitor, so the run is deferred by a tick.
hafen.console():on("tR09", function()
  hafen.timer():after(0, run)
end)
