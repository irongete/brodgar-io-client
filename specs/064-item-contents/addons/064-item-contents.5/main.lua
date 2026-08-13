-- 064.5 -- durability is the two counts, not the arc. Self-checking suite.
--
-- Run :t064-5 with a WORN OR USED TOOL in your inventory or on you -- an axe, a shovel, a pair of boots
-- that has seen some work. Everything happens inside the one call: it reads, it asserts, it prints the
-- verdict, and it changes nothing -- no item is moved, no window is opened, no permission is declared.
--
-- What it proves:
--   * item:durability() is {cur, max}, two WHOLE counts with cur <= max, over every item that carries a
--     wear row -- and nil, never a half-built table, for every item that does not.
--   * the separation this task exists for: the same item's :progress() is read INDEPENDENTLY, before and
--     after the counts, and answers the same both times. The two answers live in different domains -- two
--     absolute counts against a 0..1 fraction -- so neither can be the other's value.
--   * every :progress() over the whole reach is nil or within 0..1, never above 1 -- 064.4's bound,
--     duplicated here rather than assumed of another run, because it is what makes the domains disjoint.
--   * item:info() carries `durability` agreeing with the verb, and no `wear` key.
--   * item:wear() still raises, and names item:durability() among its two replacements.
--   * item:durability() raises on a dot call, naming the colon call.
--
-- And it RECORDS one finding: how many items answer both reads. That is what settles whether the wear row
-- also publishes the arc, and it is a measurement rather than a claim, so it is printed and not scored.

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
    if said and not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

local function label(item)
  return (item and (item:name() or item:res())) or "?"
end

local function num(v)
  return (v == nil) and "nil" or tostring(v)
end

local function summary()
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every item this addon can reach: the inventory, the worn gear, the cursor, and -- recursively -- whatever
-- any of them holds. A contained item is in none of the container reads, so :contents() is the only way down,
-- and a tool stashed inside a creel wears exactly like one lying loose.
local function reach()
  local out, seen = {}, {}
  local function add(it)
    if (it == nil) or seen[it] then return end
    seen[it] = true
    out[#out + 1] = it
    local c = it:contents()
    if c then
      for _, k in ipairs(c:items()) do add(k) end
    end
  end
  local ui = hafen.ui()
  for _, w in ipairs({ ui:inventory(), ui:equipment() }) do
    for _, it in ipairs(w and w:items() or {}) do add(it) end
  end
  local hand = hafen.player():hand()
  if hand then add(hand:item()) end
  return out
end

-- One item's durability, as a complaint or nil. The shape is the whole claim: a table of two WHOLE counts,
-- cur first, with cur <= max. Anything else -- a number, a fraction, one field missing -- is a fail, and a
-- fraction in particular is what the arc would look like if the two reads had been folded into one.
local function badShape(it, d)
  if type(d) ~= "table" then
    return label(it) .. " -> " .. type(d)
  end
  local c, m = d.cur, d.max
  if (type(c) ~= "number") or (type(m) ~= "number") then
    return label(it) .. " -> cur=" .. num(c) .. " max=" .. num(m)
  end
  if (c ~= math.floor(c)) or (m ~= math.floor(m)) then
    return label(it) .. " -> not whole counts: " .. c .. "/" .. m
  end
  if c > m then
    return label(it) .. " -> cur above max: " .. c .. "/" .. m
  end
  return nil
end

-- ============================================================================================= the run

local function run()
  local items = reach()
  if #items == 0 then
    manualCheck("no items reachable: nothing in the inventory, on you or on the cursor",
                "log in with something in your inventory and re-run :t064-5")
    summary()
    return
  end

  local worn, both = {}, 0
  local pbad, dbad, sbad, ibad = nil, nil, nil, nil

  for _, it in ipairs(items) do
    -- :progress() is read BEFORE the counts and again AFTER them: reading one must not disturb the other,
    -- which is the crudest form of "these are two reads" and the one a fold would break outright.
    local p1 = it:progress()
    local d = it:durability()
    local p2 = it:progress()

    if p1 ~= nil then
      if (type(p1) ~= "number") or (p1 <= 0) or (p1 > 1) then
        pbad = pbad or (label(it) .. " -> " .. tostring(p1))
      end
    end

    if d ~= nil then
      dbad = dbad or badShape(it, d)
      if p2 ~= p1 then
        sbad = sbad or (label(it) .. " -> arc " .. num(p1) .. " then " .. num(p2))
      elseif (p1 ~= nil) and ((type(d) ~= "table") or (type(p1) ~= "number")) then
        -- Both answered, so the two domains have to be visibly apart: a pair of counts and a bare fraction
        -- are different shapes, and one shape carrying the other is what a fold would look like from here.
        sbad = sbad or (label(it) .. " -> " .. type(d) .. " beside " .. type(p1))
      end
      worn[#worn + 1] = { it = it, d = d, p = p1 }
      if p1 ~= nil then both = both + 1 end
    end

    local i = it:info()
    if i.wear ~= nil then
      ibad = ibad or (label(it) .. " -> info.wear=" .. tostring(i.wear))
    elseif (d == nil) ~= (i.durability == nil) then
      ibad = ibad or (label(it) .. " -> verb " .. ((d == nil) and "nil" or "answers")
                      .. ", info " .. ((i.durability == nil) and "absent" or "present"))
    elseif (d ~= nil) and ((i.durability.cur ~= d.cur) or (i.durability.max ~= d.max)) then
      ibad = ibad or (label(it) .. " -> info " .. num(i.durability.cur) .. "/" .. num(i.durability.max)
                      .. " vs verb " .. num(d.cur) .. "/" .. num(d.max))
    end
  end

  check(pbad == nil, ("every :progress() over %d reachable items is nil or within 0..1, never above 1")
          :format(#items), pbad)
  check(ibad == nil, "item:info() carries durability agreeing with the verb, and no wear key", ibad)

  if #worn > 0 then
    check(dbad == nil, ("every :durability() (%d of the %d items reached) is {cur, max}, two whole counts"
            .. " with cur <= max"):format(#worn, #items), dbad)
    check(sbad == nil, "the two reads are independent: the arc answers the same before and after the counts,"
          .. " and a pair of counts and a bare fraction stay different shapes", sbad)
    log(("  finding: %d of the %d items carrying wear also paint an arc (0 means the wear row publishes no"
          .. " arc, so the two describe different things and not one thing twice)"):format(both, #worn))
    for i = 1, math.min(5, #worn) do
      local w = worn[i]
      log(("  %s -- durability %d/%d, arc %s"):format(label(w.it), w.d.cur, w.d.max,
          (w.p == nil) and "nil" or ("%.2f"):format(w.p)))
    end
    if #worn > 5 then
      log(("    ...and %d further items carrying wear, not printed"):format(#worn - 5))
    end
    manualCheck("hover each item printed above and read its wear row",
                "the tooltip prints Wear: cur/max with exactly the two numbers printed here, in that order")
  else
    manualCheck(("no item carrying wear among the %d reached: none prints a wear row"):format(#items),
                "put a USED tool -- an axe, a shovel, worn boots -- in your inventory and re-run :t064-5 to"
                .. " score the durability claims")
  end

  local one = items[1]
  refuses("item:wear() raises naming item:durability() among its replacements",
          function() return one:wear() end, "item:durability()", "ABSOLUTE")
  refuses("item:durability() raises on a dot call, naming the colon call",
          function() return one.durability() end, "item:durability()", "COLON call")

  summary()
end

hafen.slash():register("t064-5", run)   -- the only way in: a suite does not start itself
