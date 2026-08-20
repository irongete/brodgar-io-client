-- 083.2 -- the filters fill themselves. Self-checking suite.
--
-- The addon layer has no search verbs, so no suite can find EventStack's own window. This one drives the
-- very API the log drives -- a real dropdown, a seen set, a ring -- and asserts the invariants there.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why. The whole error is what is searched --
-- a prefix only ever adds to it -- and the trimmed one is what is printed.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  local raw = ok and "<no error>" or tostring(err)
  local shown = raw:gsub("^.-%.lua:%d+:%s*", "")
  check((not ok) and (raw:find(wantMsg, 1, true) ~= nil), what, shown)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local ALL = "(all)"

-- ---------------------------------------------------------------------------------------------------
-- The rule the filter obeys, written the way the log writes it.

-- One entry per value, the first time it arrives and never a second.
local function note(seen, order, v)
  if seen[v] then return false end
  seen[v] = true
  order[#order + 1] = v
  return true
end

-- :rows(t) replaces the set and CLEARS the pick, so every repopulation writes the pick back.
local function refill(dd, order, pick)
  local rows = {ALL}
  for i, v in ipairs(order) do rows[i + 1] = v end
  dd:rows(rows)
  dd:value(pick)
end

-- ---------------------------------------------------------------------------------------------------

local function checks(win)
  local dd = hafen.ui():dropdown():parent(win):position(6, 6):size(200)

  -- The trap itself, in three lines: hold a pick, grow the set, and the pick is gone.
  dd:rows{ALL, "a"}
  dd:value("a")
  eq("a dropdown holds the row it was given", dd:value(), "a")
  dd:rows{ALL, "a", "b"}
  eq("rewriting the rows clears the pick", dd:value(), nil)
  dd:value("a")
  eq("writing the pick back restores it", dd:value(), "a")

  refuses("a pick outside the rows is refused, naming the rows that are",
          function() dd:value("zz") end, 'its rows are "(all)", "a", "b"')

  -- The stream, one name at a time, with a pick live from the first "a" onwards.
  local seen, order, added, pick = {}, {}, 0, nil
  for _, name in ipairs{"a", "b", "a", "a", "c", "b"} do
    if note(seen, order, name) then
      added = added + 1
      refill(dd, order, pick or ALL)
    end
    if (name == "a") and (pick == nil) then
      pick = "a"
      dd:value(pick)
    end
  end
  eq("one entry per name, however often it repeats", added, 3)
  eq("the axis is in first-seen order", table.concat(order, ","), "a,b,c")
  eq("the rows are (all) and the axis", table.concat(dd:rows(), ","), "(all),a,b,c")
  eq("the pick survived every repopulation", dd:value(), "a")

  -- The predicate, over a ring of the log's own shape driven past its cap.
  local CAP, ring, count, head = 4, {}, 0, 0
  local function push(src, name)
    head = (head % CAP) + 1
    ring[head] = {src = src, name = name}
    if count < CAP then count = count + 1 end
  end
  local function narrow(fsrc, fname)
    local out, first = {}, (count < CAP) and 1 or ((head % CAP) + 1)
    for i = 1, count do
      local r = ring[((first + i - 2) % CAP) + 1]
      if ((fsrc == ALL) or (r.src == fsrc)) and ((fname == ALL) or (r.name == fname)) then
        out[#out + 1] = r.src .. "/" .. r.name
      end
    end
    return table.concat(out, " ")
  end
  push("out", "rclick")
  push("in", "rclick")
  push("out", "click")
  push("in", "click")
  push("out", "wdgmsg")
  push("bus", "GobAdded")
  eq("past the cap, both picks at (all) keeps the newest in arrival order",
     narrow(ALL, ALL), "out/click in/click out/wdgmsg bus/GobAdded")
  eq("a source pick keeps that source alone", narrow("out", ALL), "out/click out/wdgmsg")
  eq("a name pick keeps that name alone", narrow(ALL, "click"), "out/click in/click")
  eq("the two together keep the intersection", narrow("out", "click"), "out/click")
end

local function run()
  -- Built and destroyed inside one tick, so it never reaches a frame: a control draws nothing until
  -- the tick after the statement that built it.
  local win = hafen.ui():window():title("083.2"):position(60, 60):size(320, 60)
  local ok, err = pcall(checks, win)
  win:destroy()
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the suite ran to the end -- got: " .. tostring(err))
  end

  manualCheck("type :eventstack, pick a source and a name, then leave it a minute",
              "the table narrows to that source and that name, and both picks are STILL selected"
              .. " after names you had not seen have arrived")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t083-2", run)   -- the only way in: a suite does not start itself
