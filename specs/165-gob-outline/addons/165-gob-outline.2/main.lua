-- 165.2 — entity:outline(color [, width]) on a ghost, a sprite, an object and a panel; a patch refuses it.
-- Self-checking suite.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The refusal text with the chunk prefix LuaJ puts on a Java error stripped, or nil when the call succeeded.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Every call in `calls` must raise, and its text must contain `want`. Answers nil, or what went wrong.
local function allRefuse(calls, want)
  for i, fn in ipairs(calls) do
    local err = refusal(fn)
    if err == nil then return "#" .. i .. " <no error>" end
    if not err:find(want, 1, true) then return "#" .. i .. " " .. err end
  end
  return nil
end

local function isColor(c, r, g, b, a)
  return type(c) == "table" and c.r == r and c.g == g and c.b == b and c.a == a
end

local function show(c, w)
  if type(c) ~= "table" then return tostring(c) .. ", " .. tostring(w) end
  return ("{%s,%s,%s,%s}, %s"):format(tostring(c.r), tostring(c.g), tostring(c.b), tostring(c.a), tostring(w))
end

-- Run `probe(entity)` over every kind; it answers nil or what went wrong. Answers nil, or the first failure.
local function overKinds(kinds, probe)
  for _, k in ipairs(kinds) do
    local why = probe(k.entity)
    if why ~= nil then return k.name .. ": " .. why end
  end
  return nil
end

local standing = {}   -- the last run's entities, {collection, entity}, so a second run starts clean

local function clear(list)
  for _, it in ipairs(list) do
    if it.entity:exists() then it.collection:remove(it.entity) end
    if it.widget and it.widget:exists() then it.widget:destroy() end   -- a removed panel hands its window back
  end
end

local function run()
  pass, fail, manual = 0, 0, 0
  clear(standing)
  standing = {}
  local session = hafen.session():current()
  local me = session and session:player():gob()
  if not me then
    check(false, "the player's gob is there to stand things round", "no session or no player gob")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local virtual = hafen.virtual()
  local here = me:position()
  local ghost = virtual:ghost():add("gfx/terobjs/arch/logcabin", here:offset(33, 0)):scale(0.3)
  local sprite = virtual:sprite():add(hafen.asset():get("disc.png"), here:offset(-33, 0)):scale(2)
  local object = virtual:object():add(hafen.asset():get("cube.glb"), here:offset(0, 33)):scale(1.5)
  local window = hafen.ui():window():title("165.2"):size(96, 64)
  local panel = virtual:widget():add(window, here:offset(0, -33))
  local centre = here:offset(0, 66)
  local square = { centre:offset(-6, -6), centre:offset(6, -6), centre:offset(6, 6), centre:offset(-6, 6) }
  local patch = virtual:patch():add(square, centre)
  standing = {
    { collection = virtual:ghost(), entity = ghost }, { collection = virtual:sprite(), entity = sprite },
    { collection = virtual:object(), entity = object }, { collection = virtual:widget(), entity = panel, widget = window },
    { collection = virtual:patch(), entity = patch },
  }
  local kinds = {
    { name = "ghost", entity = ghost }, { name = "sprite", entity = sprite },
    { name = "object", entity = object }, { name = "panel", entity = panel },
  }

  -- 1. a write hands the entity back; the read is the colour keyed (a = 255), then the default width
  local why = overKinds(kinds, function(e)
    local back = e:outline{255, 160, 0}
    local c, w = e:outline()
    if back ~= e then return "did not hand the entity back" end
    if not (isColor(c, 255, 160, 0, 255) and w == 2) then return show(c, w) end
  end)
  check(why == nil, "outline{255,160,0} hands each kind back and reads {255,160,0,255}, 2", why)

  -- 2. the snapshot key carries the pair; nil takes the ring off and drops the key
  why = overKinds(kinds, function(e)
    e:outline({255, 160, 0}, 6)
    local ring = e:info().outline
    if not (type(ring) == "table" and isColor(ring.color, 255, 160, 0, 255) and ring.width == 6) then
      return "info().outline " .. (type(ring) == "table" and show(ring.color, ring.width) or tostring(ring))
    end
    local back = e:outline(nil)
    if back ~= e or e:outline() ~= nil or e:info().outline ~= nil then
      return "after outline(nil): " .. tostring(e:outline()) .. " / " .. tostring(e:info().outline)
    end
  end)
  check(why == nil, "info().outline is {color, width = 6} on each kind; outline(nil) reads nil and drops it", why)

  -- 3. what is not a colour, the widths, a surplus argument and a width beside nil
  why = overKinds(kinds, function(e)
    return allRefuse({
      function() e:outline(1) end, function() e:outline("red") end,
      function() e:outline(true) end, function() e:outline{"x"} end,
    }, "a colour is a table")
      or allRefuse({function() e:outline({1, 2, 3}, 0) end, function() e:outline({1, 2, 3}, 9) end}, "from 1 to 8")
      or allRefuse({function() e:outline({1, 2, 3}, 1.5) end}, "whole number")
      or allRefuse({function() e:outline({1, 2, 3}, "2") end}, "must be a number")
      or allRefuse({function() e:outline({1, 2, 3}, 2, 3) end}, "takes at most 2 arguments")
      or allRefuse({function() e:outline(nil, 2) end}, "takes no width")
  end)
  check(why == nil, "each kind refuses non-colours, widths 0/9/1.5/\"2\", a third argument and (nil, 2)", why)

  -- 4. a patch's line is patch:border
  why = allRefuse({function() patch:outline{1, 2, 3} end}, "patch:border(color, width)")
  check(why == nil, "patch:outline{1,2,3} raises naming patch:border(color, width)", why)

  -- all four wear the ring; the ghost fades 15..25 s, the sprite faces the screen 30..40 s; all go at 60 s
  for _, k in ipairs(kinds) do k.entity:outline({255, 160, 0}, 3) end
  local timer = hafen.timer()
  timer:after(15, function() ghost:alpha(0.5) end)
  timer:after(25, function() ghost:alpha(1) end)
  timer:after(30, function() sprite:facing("screen") end)
  timer:after(40, function() sprite:facing("fixed") end)
  local mine = standing
  timer:after(60, function() clear(mine) end)

  manualCheck("look at the four things round your character",
              "each has an orange 3 px ring round what is drawn, the sprite's round its disc, not its square")
  manualCheck("watch the log cabin from 15 s to 25 s", "no ring while it is see-through, the ring back after")
  manualCheck("watch the sprite from 30 s to 40 s", "no ring while it faces the screen, the ring back after")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The only way in: a suite does not start itself. A console line holds the character's widget tree, and the
-- panel's window is the addon layer's, so the run waits one engine step, where no tree is held.
hafen.console():on("t165-2", function() hafen.timer():after(0, run) end)
