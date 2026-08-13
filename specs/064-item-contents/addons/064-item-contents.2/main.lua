-- 064.2 -- a bucket says what it holds, which is not items. Self-checking suite.
--
-- Run :t064-2 with a LIQUID CONTAINER in your inventory -- a bucket, a jug, a waterskin, a barrel, anything
-- holding a liquid and not items. Everything happens inside the one call: it reads, it asserts, it prints the
-- verdict, and it changes nothing -- no item is moved, no window is opened, no permission is declared.
--
-- What it proves:
--   * contents:text() is the line the tooltip STATES about what is inside -- a non-empty string.
--   * contents:level() is the fill meter's two counts, cur and max, both numbers with cur <= max.
--   * contents:quality() is the CONTENT's own number, read beside item:quality() so the two are seen to be
--     independent reads of one item.
--   * contents:items() is an EMPTY ARRAY and not nil: a liquid container holds something and holds no items,
--     which is exactly what nil-vs-empty means here.
--   * contents:info() carries those three tooltip reads and NO items -- a snapshot holds no live objects.
--   * the two halves stay separable: a STACK, read through the same verb, answers nil to :text() and :level().
--   * the identity claims hold on this half too, and are asserted here rather than assumed of another run:
--     the contents are interned, and an item holding nothing answers nil to :contents().

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

-- A stated line may state more than one; it prints on ONE output line either way.
local function oneline(s)
  if type(s) ~= "string" then return tostring(s) end
  return (s:gsub("\n", " | "))
end

local function showLevel(lv)
  if type(lv) ~= "table" then return tostring(lv) end
  return tostring(lv.cur) .. "/" .. tostring(lv.max)
end

local function summary()
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ============================================================================================= the run

local function run()
  local inv = hafen.ui():inventory()
  if not inv then
    manualCheck("no inventory: the HUD is not up", "log in and re-run :t064-2")
    summary()
    return
  end

  -- The liquid container: the item that HOLDS something and holds no ITEMS. The client is never told which
  -- container is which -- the same verb answers both insides -- so what it holds is the whole of the test.
  -- The stack beside it is found the same way, by holding the most items, and is what makes the two halves
  -- separable rather than one shape with optional fields.
  local liquid, stack, plain
  for _, it in ipairs(inv:items()) do
    local held = it:contents()
    if held then
      local n = #held:items()
      if n == 0 then
        if not liquid then liquid = it end
      elseif (not stack) or (n > #stack:contents():items()) then
        stack = it
      end
    elseif not plain then
      plain = it                                  -- an ordinary item, holding nothing
    end
  end
  if not liquid then
    manualCheck("no liquid container in the inventory: nothing in it states what it holds, so nothing is scored",
                "put a bucket, a jug or a waterskin holding something in your inventory and re-run :t064-2")
    summary()
    return
  end

  local c = liquid:contents()
  log(("[pass] the inventory holds a liquid container: %s"):format(label(liquid)))
  pass = pass + 1

  check(liquid:contents() == c, "two reads of one item's contents are == (interned)", tostring(liquid:contents()))

  local its = c:items()
  check((type(its) == "table") and (#its == 0),
        "contents:items() is an EMPTY ARRAY and not nil", tostring(its) .. ", #" .. tostring(its and #its))

  local text = c:text()
  check((type(text) == "string") and (#text > 0),
        ("contents:text() is a non-empty string: %q"):format(oneline(text)), tostring(text))

  local lv = c:level()
  local lcur = (type(lv) == "table") and lv.cur or nil
  local lmax = (type(lv) == "table") and lv.max or nil
  check((type(lcur) == "number") and (type(lmax) == "number") and (lcur <= lmax),
        ("contents:level() gives cur and max numbers with cur <= max: %s"):format(showLevel(lv)),
        showLevel(lv))

  -- The content's quality and the container's, side by side: two independent reads of one item.
  local cq, iq = c:quality(), liquid:quality()
  check((type(cq) == "number") and (type(iq) == "number"),
        ("contents:quality() and item:quality() both read: %s (content) / %s (container)")
          :format(tostring(cq), tostring(iq)),
        tostring(cq) .. " / " .. tostring(iq))

  local ci = c:info()
  check((ci.text == text) and (ci.quality == cq) and (ci.level ~= nil)
          and (ci.level.cur == lcur) and (ci.level.max == lmax) and (ci.items == nil),
        "contents:info() carries text, quality and level, and no items",
        ("text=%s quality=%s level=%s items=%s"):format(tostring(ci.text ~= nil), tostring(ci.quality),
                                                       showLevel(ci.level), tostring(ci.items)))

  if plain then
    check(plain:contents() == nil, "an item holding nothing answers nil to :contents()",
          tostring(plain:contents()))
  else
    manualCheck("the inventory holds only containers, so the holds-nothing claim is unscored",
                "put any single ordinary item beside the container and re-run :t064-2")
  end

  if stack then
    local sc = stack:contents()
    check((sc:text() == nil) and (sc:level() == nil),
          ("a stack (%s) answers nil to :text() and :level(), so the two halves stay separable")
            :format(label(stack)),
          tostring(sc:text()) .. " / " .. showLevel(sc:level()))
  else
    manualCheck("no stack in the inventory, so the separability of the two halves is unscored",
                "put a stack of anything beside the container and re-run :t064-2")
  end

  refuses("a dot call on the Contents raises naming the colon call",
          function() return c.text() end, "contents:text()", "COLON call")

  manualCheck(("hover %s and read its tooltip, and look at the fill bar on its icon"):format(label(liquid)),
              ("the tooltip states %q, and the bar is %s of the way along")
                :format(oneline(text), showLevel(lv)))

  summary()
end

hafen.slash():register("t064-2", run)   -- the only way in: a suite does not start itself
