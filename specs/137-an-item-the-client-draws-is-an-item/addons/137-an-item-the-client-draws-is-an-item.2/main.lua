-- 137.2 -- found where it is drawn: the role, the collection, the door. Self-checking suite.
--
-- Run it with a CRAFTING RECIPE OPEN. One door -- s:ui():on("item", "Added"/"Removed") -- now seeds and fires
-- for a recipe's slots as well as for a container's cells, the `item` role classifies them, and widget:items()
-- lists them. Everything the API can read back is scored here. The one thing a program cannot cause is the
-- destruction of a recipe's slots, so the run asks for a recipe change and scores what it reaches over 20 s.
-- It sends nothing, builds no widget and declares no permission: every verb below is a read.

local pass, fail, manual = 0, 0, 0
local open = nil                        -- the run in flight, so a second :t137-2 replaces the first

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

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

-- Give back the timer and the subscriptions a run took out. Called at the start of the next run and at the
-- end of this one, so nothing this suite registers outlives its own verdict.
local function release(r)
  if r == nil then return end
  if r.timer then read(function() r.timer:cancel() end) end
  for _, sub in ipairs(r.subs) do read(function() sub:off() end) end
  if open == r then open = nil end
end

local function finish(r)
  release(r)
  say(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0          -- one verdict per run, never a total across two
  release(open)

  local s = hafen.session():current()
  if s == nil then
    ok("a character is on screen", false, "no current session")
    finish(nil)
    return
  end

  local rec = {subs = {}}
  open = rec

  -- ---- the door: it seeds what is already drawn ------------------------------------------------------
  -- An "Added" subscription is called back inside its own registration for everything already in the tree,
  -- so `seeded` is complete the moment :on returns.
  local seeded, seeding = {}, true
  local arrivals = {}                   -- filled by the same handler once the seed is in (see the end of run)
  rec.subs[#rec.subs + 1] = s:ui():on("item", "Added", function(icon)
    if seeding then
      seeded[icon] = true
    else
      local t = read(function() return icon:type() end)
      if (t == "SpecWidget") or (t == "Input") then arrivals[icon] = true end
    end
  end)

  local slots, isSlot = {}, {}
  for icon in pairs(seeded) do
    local t = read(function() return icon:type() end)
    if (t == "SpecWidget") or (t == "Input") then    -- Makewindow draws an output as one and an input as the other
      slots[#slots + 1] = icon
      isSlot[icon] = true
    end
  end

  local ins = read(function() return s:craft():inputs():list() end) or {}
  local outs = read(function() return s:craft():outputs():list() end) or {}
  local want = #ins + #outs
  if want == 0 then
    ok("a crafting recipe is open", false, "no recipe -- open one, then run :t137-2 again")
    finish(rec)
    return
  end
  ok(("the door seeds every recipe slot (%d of %d)"):format(#slots, want), #slots == want, #slots)

  -- ---- the collection and the role, over the widget drawing them --------------------------------------
  local mw = read(function() return s:ui():match("@Makewindow") end)
  local drawn = mw and read(function() return mw:items():count() end)
  ok(("the crafting window lists its slots (%d)"):format(want), drawn == want, tostring(drawn))

  local q = read(function() return s:craft():qualityInputs():count() end) or 0
  local tl = read(function() return s:craft():tools():count() end) or 0
  local byRole = mw and read(function() return mw:matchAll("item") end) or {}
  ok(("the role finds those slots and nothing else (q=%d t=%d draw no icon)"):format(q, tl),
     #byRole == want, #byRole)

  local slot = slots[1]
  local r1 = slot and read(function() return slot:role() end)
  local r2 = mw and read(function() return mw:role() end)
  ok("a slot's role is item, and the widget drawing them has none",
     (r1 == "item") and (r2 == nil), tostring(r1) .. " / " .. tostring(r2))

  -- Every slot's item is the entry the window lists: the read and the collection are one object, not two.
  local listed = mw and read(function() return mw:items():list() end) or {}
  local kept, joined = {}, 0
  for _, icon in ipairs(slots) do
    local it = read(function() return icon:item() end)
    if it ~= nil then
      kept[#kept + 1] = it
      for _, one in ipairs(listed) do
        if one == it then joined = joined + 1 break end
      end
    end
  end
  ok(("every slot's item is one the crafting window lists (%d of %d)"):format(joined, #slots),
     joined == #slots, joined)

  -- ---- the unchanged half: a real container ----------------------------------------------------------
  local inv = read(function() return s:ui():inventory() end)
  local invIcons = inv and read(function() return inv:matchAll("item") end) or {}
  local invItems = inv and read(function() return inv:items():count() end)
  ok(("a real container is unchanged: its icons and its items agree (%d)"):format(#invIcons),
     (inv ~= nil) and (#invIcons == invItems), tostring(invItems))

  local unseeded = 0
  for _, icon in ipairs(invIcons) do
    if not seeded[icon] then unseeded = unseeded + 1 end
  end
  ok(("a container's icons came through the same door (%d)"):format(#invIcons), unseeded == 0,
     unseeded .. " never seeded")

  -- ---- the end of a slot, and the arrival of the next -----------------------------------------------
  -- The client puts up one crafting window PER RECIPE, so opening another one destroys the widget holding
  -- these slots and they die inside it -- which is a departure the tree makes no announcement about, here
  -- as anywhere else a window closes on what it holds. So what is scored for the end is what the API
  -- promises: the Item goes stale and the role stops listing the icon. The announcements the run did see
  -- are reported beside it, since a slot rebuilt inside a LIVE window does announce. The new recipe's own
  -- slots are the other half of the door: an icon that arrives after the seed must be announced, not
  -- merely findable.
  local announced = {}
  seeding = false
  rec.subs[#rec.subs + 1] = s:ui():on("item", "Removed", function(icon)
    if isSlot[icon] then announced[icon] = true end
  end)

  manual = manual + 1
  say("[manual] open a DIFFERENT crafting recipe within 20 s of :t137-2 -- expect: the three lines below"
      .. " print pass")

  local left, mine = 40, nil
  mine = hafen.timer():every(0.5, function()
    left = left - 1
    local stale = 0
    for _, it in ipairs(kept) do
      if read(function() return it:exists() end) == false then stale = stale + 1 end
    end
    if ((stale < #kept) or (next(arrivals) == nil)) and (left > 0) then return end
    read(function() mine:cancel() end)
    rec.timer = nil

    local said = 0
    for _ in pairs(announced) do said = said + 1 end
    ok(("every slot's item is stale once its icon has gone (%d of %d, %d announced)"):format(
         stale, #kept, said), stale == #kept, stale)

    local still, now = 0, read(function() return s:ui():matchAll("item") end) or {}
    for _, one in ipairs(now) do
      if isSlot[one] then still = still + 1 end
    end
    ok("the role no longer lists a slot that went", still == 0, still .. " still listed")

    local fresh = 0
    for _ in pairs(arrivals) do fresh = fresh + 1 end
    ok(("the door announces the slots that arrive after it (%d icons)"):format(fresh), fresh > 0,
       "nothing arrived in 20 s")
    finish(rec)
  end)
  rec.timer = mine
end

hafen.console():on("t137-2", run)   -- the only way in: a suite does not start itself
