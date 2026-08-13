-- 064.3 -- an item entering a stack is an event on the container holding it. Self-checking suite.

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

local function label(item)
  return (item and (item:name() or item:res())) or "?"
end

-- The same lookup 064.1's suite makes: an inventory item whose :contents() actually carries real items.
local function findStack(items)
  for _, it in ipairs(items) do
    local c = it:contents()
    if c and #c:items() > 0 then
      return it, c
    end
  end
  return nil, nil
end

local function run()
  local inv = hafen.ui():inventory()
  local seeded, seededCount = {}, 0
  local seeding = true   -- silence the log for the synchronous seed burst; only LIVE moves get printed

  -- Subscribing SEEDS synchronously with the container's CURRENT top-level items before :on returns
  -- (docs/addons/api/ui/items.md), which is the very claim the first check below holds to account.
  inv:on("ItemAdded", function(item)
    if seeding then
      if not seeded[item] then
        seeded[item] = true
        seededCount = seededCount + 1
      end
    else
      local c = item:container()
      hafen.log():write("ItemAdded: " .. label(item) .. (c and (" -- in " .. label(c)) or ""))
    end
  end)
  inv:on("ItemRemoved", function(item)
    if not seeding then
      hafen.log():write("ItemRemoved: " .. label(item))
    end
  end)
  seeding = false

  local items = inv:items()
  local matches = (seededCount == #items)
  for _, it in ipairs(items) do
    if not seeded[it] then matches = false end
  end
  check(matches, "the seeding equals inventory:items() exactly -- same count, same objects",
        seededCount .. " seeded vs " .. #items .. " listed")

  local st, cont = findStack(items)
  if st then
    local inside = cont:items()[1]
    check(inside:container() == st,
          "a contained item answers :container() as the stack -- what a handler uses to place a payload",
          tostring(inside:container()))
    local listed = false
    for _, it in ipairs(inv:items()) do
      if it == inside then listed = true end
    end
    check(not listed, "inventory:items() never lists a contained item (duplicates 064.1)", tostring(listed))
  else
    hafen.log():write("[manual] no stack with contents in the inventory -- put one there and re-run"
      .. " to score the two checks above")
  end

  manualCheck("take one thing out of a stack and drop it back in, then read the log above",
              "one ItemRemoved and one ItemAdded, each naming the thing and not the stack")
  manualCheck("move the whole stack to another container, then read the log above",
              "exactly one ItemRemoved, for the stack, not one per thing inside it")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t064-3", run)
