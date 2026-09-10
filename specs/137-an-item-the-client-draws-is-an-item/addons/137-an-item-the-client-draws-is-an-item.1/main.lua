-- 137.1 -- a depiction is an Item, read through the icon that draws it. Self-checking suite.
--
-- Run it with a CRAFTING RECIPE OPEN: the recipe's input and output slots are the depictions this task
-- makes readable, and a backpack item is the unchanged half beside them. It sends nothing (the one write it
-- tries is the refusal itself, which sends nothing by construction) and it builds no widget.

local pass, fail, manual = 0, 0, 0
local watch                             -- the run's own timer, so a second :t137-1 replaces the first

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
-- adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?[^\n]-%.lua:%d+:?[ \t]*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

-- Every widget of one class under `w`, by :type(). The role is 137.2's, so the class is what this run has.
local function byType(w, ...)
  local want, out = {}, {}
  for _, t in ipairs({...}) do want[t] = true end
  for _, one in ipairs(read(function() return w:matchAll("*") end) or {}) do
    if want[read(function() return one:type() end)] then out[#out + 1] = one end
  end
  return out
end

local function finish()
  say(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0          -- one verdict per run, never a total across two
  if watch then
    read(function() return watch:cancel() end)
    watch = nil
  end
  local s = hafen.session():current()
  if s == nil then
    ok("a character is on screen", false, "no current session")
    finish()
    return
  end

  -- ---- the depictions: a recipe's slots ---------------------------------------------------------------
  -- Makewindow draws its inputs as `Input` and its outputs as `SpecWidget`; both are one recipe slot.
  local slots = byType(s:ui(), "SpecWidget", "Input")
  if #slots == 0 then
    ok("the crafting window's slots are found", false,
       "no SpecWidget/Input in the tree -- open a crafting recipe, then run :t137-1 again")
    finish()
    return
  end

  local kept, drawn = nil, 0
  local same, res, alive = true, true, true
  for _, icon in ipairs(slots) do
    local it = read(function() return icon:item() end)
    if it == nil then
      same = false
    else
      drawn = drawn + 1
      kept = kept or it
      if it ~= read(function() return icon:item() end) then same = false end
      if type(read(function() return it:res() end)) ~= "string" then res = false end
      if read(function() return it:exists() end) ~= true then alive = false end
    end
  end
  ok("every recipe slot draws an item (" .. drawn .. " of " .. #slots .. ")", drawn == #slots,
     drawn .. " answered")
  ok("two reads of one slot are the same Item", same, "a second read differed")
  ok("a depiction answers :res() with a string", res, "not a string")
  ok("a depiction is drawn, so :exists() is true", alive, "false")

  -- ---- what a depiction is NOT -----------------------------------------------------------------------
  local absent = {}
  for _, verb in ipairs({"cell", "handle", "container", "contents"}) do
    local v = read(function() return kept[verb](kept) end)
    if v ~= nil then absent[#absent + 1] = verb .. "=" .. tostring(v) end
  end
  local sl = read(function() return kept:slots() end)
  if (sl == nil) or (#sl ~= 0) then absent[#absent + 1] = "slots is not empty" end
  ok("a depiction has no place: cell, handle, container, contents and slots answer absence",
     #absent == 0, table.concat(absent, ", "))

  local snap = read(function() return kept:info() end)
  ok("its :info() carries res and no cell",
     (type(snap) == "table") and (type(snap.res) == "string") and (snap.cell == nil),
     (type(snap) == "table") and ("res=" .. tostring(snap.res) .. " cell=" .. tostring(snap.cell))
       or tostring(snap))

  -- A refusal is a check: item.* IS declared, so the gate passes and what raises is the depiction itself.
  local good, err = pcall(function() return kept:drop() end)
  local msg = good and "<no error>" or why(err)
  ok("a write on a depiction is refused naming drawn, not held",
     (not good) and (msg:find("drawn, not held", 1, true) ~= nil), msg)

  -- ---- the unchanged half: a real item in a container -------------------------------------------------
  local inv = read(function() return s:ui():inventory() end)
  local held = inv and (read(function() return inv:items():list() end) or {}) or {}
  local icons = inv and byType(inv, "WItem") or {}
  local drawnHeld, matched = nil, false
  for _, icon in ipairs(icons) do
    local it = read(function() return icon:item() end)
    if it ~= nil then
      drawnHeld = drawnHeld or it
      for _, listed in ipairs(held) do
        if listed == drawnHeld then matched = true end
      end
      break
    end
  end
  if drawnHeld == nil then
    ok("a backpack item answers :cell()", false, "nothing in the backpack -- put one item in it")
    ok("a backpack icon's item is its :items() entry", false, "nothing in the backpack")
  else
    ok("a backpack item answers :cell()", type(read(function() return drawnHeld:cell() end)) == "table",
       tostring(read(function() return drawnHeld:cell() end)))
    ok("a backpack icon's item is its :items() entry", matched, "not == any listed item")
  end

  -- ---- the end of a depiction: its icon leaves the tree -----------------------------------------------
  manual = manual + 1
  say("[manual] close the crafting window within 20 s of :t137-1 -- expect: the line below prints pass")
  local left, mine = 40, nil
  mine = hafen.timer():every(0.5, function()
    left = left - 1
    local gone = read(function() return kept:exists() end) == false
    local stillNamed = type(read(function() return kept:res() end)) == "string"
    if gone or (left <= 0) then
      mine:cancel()                     -- its own timer, never whatever a later run put in `watch`
      if watch == mine then watch = nil end
      ok("a depiction whose icon left the tree is stale and still names itself",
         gone and stillNamed, gone and "res stopped answering" or "still :exists() after 20 s")
      finish()
    end
  end)
  watch = mine
end

hafen.console():on("t137-1", run)   -- the only way in: a suite does not start itself
