-- 064.1 -- an item says what it holds, and what holds it. Self-checking suite.
--
-- Run :t064-1 with a STACK of anything in your inventory (a stack of dandelions, of boards, of anything the
-- server piles). Everything happens inside the one call: it reads, it asserts, it prints the verdict, and it
-- changes nothing -- no item is moved, no window is opened, no permission is declared.
--
-- What it proves:
--   * item:contents() hands back an INTERNED object: two reads of one item's contents are ==.
--   * what is inside are real Items -- more than one of them, none of them the stack, each answering :res().
--   * item:container() is the exact INVERSE, asserted both ways: every thing inside answers the stack, while
--     the stack itself and an ordinary item beside it answer nil.
--   * an item inside is not drawn a cell of its own and is not in the container's own read: :cell() is nil
--     and it never appears in inventory:items(), which stays one entry per cell on screen.
--   * item:info() carries `contents` and NO `container`, and contents:info() carries no `items` -- a snapshot
--     holds no live objects.
--   * the reads answer with the hover window DOWN, which is where it is for the whole of this run.

local pass, fail, manual = 0, 0, 0

local function log(s)
  hafen.log():write(s)
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function why(err)
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every one of the words asked for.
local function refuses(what, fn, ...)
  local want = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

local function label(item)
  return item:name() or item:res() or "?"
end

-- ============================================================================================= the run

local function run()
  local inv = hafen.ui():inventory()
  if not inv then
    manualCheck("no inventory: the HUD is not up", "log in and re-run :t064-1")
    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  -- The stack: the first item in the inventory that HOLDS items. Nothing else in the API can find one --
  -- the client is never told which container is a stack -- so what it holds is the whole of the test.
  local items = inv:items()
  local stack, inside, plain
  for _, it in ipairs(items) do
    local held = it:contents()
    if held then
      -- A bucket holds something too and holds no ITEMS, so the stack is the one holding the most of them.
      if (#held:items() > 0) and ((not stack) or (#held:items() > #stack:contents():items())) then
        stack = it
      end
    elseif not plain then
      plain = it                                  -- an ordinary item, holding nothing, for the inverse
    end
  end
  if not stack then
    manualCheck("no stack in the inventory: nothing in it holds items, so nothing is scored",
                "put a stack of anything in your inventory and re-run :t064-1")
    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  local c = stack:contents()
  inside = c:items()
  log(("[pass] the inventory holds a stack: %s, holding %d"):format(label(stack), #inside))
  pass = pass + 1

  check(stack:contents() == stack:contents(), "two reads of one item's contents are ==", tostring(c))
  check(#inside > 1, "the stack holds more than one item", #inside)

  local distinct, named = true, true
  for _, it in ipairs(inside) do
    if it == stack then distinct = false end
    if not it:res() then named = false end
  end
  check(distinct and named, "no entry inside is the stack, and each answers :res()",
        (not distinct) and "an entry IS the stack" or "an entry has no :res()")

  local roundTrip, saw = true, nil
  for _, it in ipairs(inside) do
    local owner = it:container()
    if owner ~= stack then roundTrip = false; saw = tostring(owner) end
  end
  check(roundTrip, "every thing inside answers the stack to :container()", saw)
  check(stack:container() == nil, "the stack itself answers nil to :container()",
        tostring(stack:container()))

  if plain then
    check((plain:container() == nil) and (plain:contents() == nil),
          "an ordinary item beside it answers nil to both :container() and :contents()",
          tostring(plain:container()) .. " / " .. tostring(plain:contents()))
  else
    manualCheck("the inventory holds only containers, so the ordinary-item inverse is unscored",
                "put any single item beside the stack and re-run :t064-1")
  end

  local one = inside[1]
  check((one:cell() == nil) and (stack:cell() ~= nil),
        "a thing inside reads :cell() as nil, while the stack itself reads a cell",
        tostring(one:cell()) .. " / " .. tostring(stack:cell()))

  local listed = false
  for _, it in ipairs(inv:items()) do
    if it == one then listed = true end
  end
  check(not listed, "a thing inside is NOT in inventory:items(): a stack is one entry, as it is one cell",
        listed and "it is listed" or "-")

  local info = stack:info()
  check((info.contents ~= nil) and (info.container == nil),
        "item:info() carries contents and no container",
        tostring(info.contents) .. " / " .. tostring(info.container))
  local cinfo = c:info()
  check((cinfo.items == nil) and (cinfo.name == c:name()),
        "contents:info() carries no items, and its name is what :name() reads",
        tostring(cinfo.items) .. " / " .. tostring(cinfo.name))

  refuses("a dot call on the Item raises naming the colon call",
          function() return stack.contents() end, "item:contents()", "COLON call")
  -- ...and the form that PASSES the item is the colon call itself, in Lua and in every other verb here:
  -- there is no metamethod that can tell the two apart, so this asserts what is true rather than a refusal
  -- that cannot exist.
  check(stack.contents(stack) == c, "the same call with the item passed IS the colon call",
        tostring(stack.contents(stack)))

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t064-1", run)   -- the only way in: a suite does not start itself
