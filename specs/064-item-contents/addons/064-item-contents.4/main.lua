-- 064.4 -- two reads answer what the client draws. Self-checking suite.
--
-- Run :t064-4 with your inventory open. Everything happens inside the one call: it reads, it asserts, it
-- prints the verdict, and it changes nothing -- no item is moved, no window is opened, no permission is
-- declared. Put a STACK of anything in the inventory to score the stack claim as well.
--
-- What it proves:
--   * item:progress() is a 0..1 FRACTION over every item the suite can reach -- never a number above 1,
--     which is exactly what reading the old field without converting would have given.
--   * item:quantity() is a whole number or nil, over the same reach.
--   * on a stack, item:quantity() equals #item:contents():items() -- the claim that proves the fold reached
--     the right source, since a stack's number is not in the field the server writes. A stack is found as a
--     container that DRAWS a count: a belt and a creel hold items and draw none, and the client is never
--     told which kind it has, so this is the only way to name one without guessing.
--   * item:info() carries `quantity` and `progress`, agreeing with the verbs, and carries neither `num`
--     nor `wear`.
--   * both retired spellings raise: item:num() naming item:quantity(), and item:wear() naming BOTH of its
--     replacements -- it was two things at once behind a name belonging to neither.
--   * the two new reads raise on a dot call, naming the colon call.

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
local function refusal(fn, ...)
  local want = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  if ok then return false, err end
  for i = 1, #want do
    if not err:find(want[i], 1, true) then return false, err end
  end
  return true, err
end

local function refuses(what, fn, ...)
  local said, err = refusal(fn, ...)
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

local function label(item)
  return (item and (item:name() or item:res())) or "?"
end

local function summary()
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every item this addon can reach: the inventory, the worn gear, the cursor, and -- recursively -- whatever
-- any of them holds. A contained item is in none of the container reads, so :contents() is the only way down.
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

-- 064.1's lookup, duplicated here rather than assumed of another run -- an item whose :contents() carries
-- real items -- and then narrowed to the STACKS among them: the ones that draw a count over the icon. Hands
-- back the labels of every container holding items, how many of them are stacks, and the first disagreement.
local function findStacks(items)
  local held, stacks, bad = {}, 0, nil
  for _, it in ipairs(items) do
    local c = it:contents()
    if c and (#c:items() > 0) then
      held[#held + 1] = label(it)
      local q = it:quantity()
      if q ~= nil then
        stacks = stacks + 1
        if q ~= #c:items() then
          bad = bad or (label(it) .. ": " .. tostring(q) .. " vs " .. #c:items())
        end
      end
    end
  end
  return held, stacks, bad
end

local function num(v)
  return (v == nil) and "nil" or tostring(v)
end

-- Several entries to a printed line. The manual claim has two halves -- these numbers are right, and no
-- OTHER item draws one -- and the second cannot be answered against a list that was cut short, so the run
-- prints every item it reached rather than a sample of them.
local function pack(head, tokens, cap)
  local lines, row = {}, head
  for _, t in ipairs(tokens) do
    if (#row + #t + 3) <= 130 then
      row = ((row == head) and (row .. t)) or (row .. " | " .. t)
    else
      lines[#lines + 1] = row
      row = "    " .. t
    end
  end
  lines[#lines + 1] = row
  for i = 1, math.min(cap, #lines) do log("  " .. lines[i]) end
  if #lines > cap then
    log(("    ...and %d further lines, not printed"):format(#lines - cap))
  end
end

-- ============================================================================================= the run

local function run()
  local items = reach()
  if #items == 0 then
    manualCheck("no items reachable: nothing in the inventory, on you or on the cursor",
                "log in with something in your inventory and re-run :t064-4")
    summary()
    return
  end

  -- The bound the whole task turns on. The old field was a percentage, so an unconverted read answers 63
  -- where the arc is 63% of the way round -- a number this can never produce.
  local pbad, qbad, ibad = nil, nil, nil
  local shown = {}
  for _, it in ipairs(items) do
    local q, p = it:quantity(), it:progress()
    if p ~= nil then
      if (type(p) ~= "number") or (p <= 0) or (p > 1) then
        pbad = pbad or (label(it) .. " -> " .. tostring(p))
      end
    end
    if q ~= nil then
      if (type(q) ~= "number") or (q ~= math.floor(q)) then
        qbad = qbad or (label(it) .. " -> " .. tostring(q))
      end
    end
    local i = it:info()
    if (i.num ~= nil) or (i.wear ~= nil) or (i.quantity ~= q) or (i.progress ~= p) then
      ibad = ibad or (label(it) .. " -> num=" .. num(i.num) .. " wear=" .. num(i.wear)
                      .. " quantity=" .. num(i.quantity) .. " progress=" .. num(i.progress))
    end
    if (q ~= nil) or (p ~= nil) then shown[#shown + 1] = { it = it, q = q, p = p } end
  end

  check(pbad == nil, ("every :progress() over %d reachable items is nil or within 0..1, never above 1")
          :format(#items), pbad)
  check(qbad == nil, ("every :quantity() over those %d items is nil or a whole number"):format(#items), qbad)
  check(ibad == nil, "item:info() carries quantity and progress, agreeing with the verbs, and neither"
        .. " num nor wear", ibad)

  -- The stack: where the number the icon shows is NOT the field the server writes, so it is the one item
  -- that proves the fold reached the item's own published info instead.
  --   A container that SHOWS a count is the only non-guessing way to find one. The client is never told a
  -- stack from a creel (064.1) -- a belt and a creel hold items and draw no number at all, and they are not
  -- what this claim is about -- so "holds items" alone picks the wrong item, while "holds items AND draws a
  -- count" is exactly a stack. Every one reachable is checked, not just the first.
  local held, stacks, qbadstack = findStacks(items)
  if stacks > 0 then
    check(qbadstack == nil,
          ("every container drawing a count (%d of the %d holding items) answers :quantity() as"
            .. " #contents:items()"):format(stacks, #held), qbadstack)
  else
    manualCheck(("no stack reachable: %d containers hold items and none draws a count (%s)")
                  :format(#held, (#held > 0) and table.concat(held, ", ") or "none"),
                "put a stack of anything in your inventory and re-run :t064-4 to score that claim")
  end

  local one = items[1]
  refuses("item:num() raises naming its replacement",
          function() return one:num() end, "item:quantity()")
  refuses("item:wear() raises naming BOTH replacements, and which is which",
          function() return one:wear() end, "item:progress()", "item:durability()", "ARC", "ABSOLUTE")

  local qsaid, qerr = refusal(function() return one.quantity() end, "item:quantity()", "COLON call")
  local psaid, perr = refusal(function() return one.progress() end, "item:progress()", "COLON call")
  check(qsaid and psaid, "a dot call on either new read raises naming the colon call",
        (qsaid and "" or ("quantity: " .. qerr .. " ")) .. (psaid and "" or ("progress: " .. perr)))

  -- The eye check: EVERY item reached, whether it answered or not. The manual claim has two halves -- these
  -- numbers are right, and no other item draws one -- and one list serves both: an entry carrying "=N" must
  -- show N on its icon, and a bare entry must show nothing. A list of only the items that answered would
  -- leave the second half to be scanned from memory, which is the half a mistake actually hides in.
  --   A contained item is marked, because it has no cell of its own to compare against: a stack of four is
  -- FIVE items -- itself and the four inside -- and only the stack is drawn in the container. Reading the
  -- list against the screen without that mark finds two dandelion icons against six entries and reads as a
  -- duplicate, so the mark is what makes the eye check answerable at all.
  local tokens = {}
  for _, it in ipairs(items) do
    local q, p = it:quantity(), it:progress()
    local t = label(it)
    if q ~= nil then t = t .. "=" .. q end
    if p ~= nil then t = t .. " arc=" .. ("%.2f"):format(p) end
    if it:container() ~= nil then t = t .. "*" end
    tokens[#tokens + 1] = t
  end
  pack(("all %d reached (bare = neither number; * = inside another item): "):format(#items), tokens, 7)

  manualCheck("read the list above against the icons, entry by entry",
              "every entry carrying =N shows exactly N on its icon (on gildable gear the number IS the"
              .. " gildings, 0 included), every BARE entry shows no number, and an entry carrying arc= is the"
              .. " wedge sweeping round the icon -- not a fill bar along the bottom. A * entry has NO cell of"
              .. " its own: hover the container it is in to see it")

  summary()
end

hafen.slash():register("t064-4", run)   -- the only way in: a suite does not start itself
