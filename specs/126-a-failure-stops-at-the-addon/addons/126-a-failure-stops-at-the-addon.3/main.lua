-- 126.3 -- encode refuses a table too deep instead of overflowing the stack. Suite: run with :t126.
--
-- hafen.json():parse has always counted nesting and refused past its cap. hafen.json():encode counted
-- nothing, so a deep acyclic table walked the writer's recursion until the Java stack ran out -- which
-- is not a Lua error an addon can pcall, and which this task replaces with a refusal that names the
-- cap. Both halves are read back here:
--
--   * the deep table FAILS, and fails saying "nesting too deep (> <cap>)". Merely failing proves
--     nothing: an overflow fails too, and an overflow is the thing being removed. The message is the
--     whole assertion.
--   * a table one level inside the cap still encodes AND parses back with every level intact, which is
--     the check that the cap was not set an off-by-one too tight; the table exactly AT the cap is
--     checked beside it, because that is the boundary the two sides have to agree on.
--
-- The cap itself is never written down here. It is read out of parse's own refusal and then used on
-- encode, so the suite scores the same on a client launched with -Dhaven.addon.json.maxdepth -- and so
-- that "encode and parse answer to the same cap" is an assertion rather than two constants that match.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function strip(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or strip(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- The cap as the client itself states it, out of either side's refusal.
local function capIn(msg)
  return tonumber(msg:match("nesting too deep %(> (%d+)%)"))
end

-- A chain of `levels` tables, the innermost holding a leaf, so the round trip has something to find.
local function deepTable(levels)
  local root, cur = {}, nil
  cur = root
  for _ = 2, levels do
    local t = {}
    cur.a = t
    cur = t
  end
  cur.leaf = "ore"
  return root
end

-- Walk a parsed chain back down: how many levels it holds, and what the innermost one carries.
local function levelsOf(t)
  local n, cur = 1, t
  while (type(cur) == "table") and (type(cur.a) == "table") do
    n = n + 1
    cur = cur.a
  end
  return n, (type(cur) == "table") and cur.leaf or nil
end

local function run()
  -- The reader's cap, out of the reader's own refusal. 4096 levels is far past any cap; parse stops at
  -- its own and says where, so nothing here has to know the number.
  local deepDoc = string.rep("[", 4096) .. string.rep("]", 4096)
  local ok, err = pcall(function() return hafen.json():parse(deepDoc) end)
  local readerCap = (not ok) and capIn(strip(err)) or nil
  check(readerCap ~= nil, "parse refuses a document past its depth cap, naming the cap",
        ok and "<no error>" or strip(err))
  readerCap = readerCap or 256          -- score the rest of the run even if that one line failed

  -- The writer's half: past the cap, a Lua error that names the limit -- not a stack overflow, which
  -- would take this addon down and never reach the summary below.
  local eok, eerr = pcall(function() return hafen.json():encode(deepTable(readerCap + 8)) end)
  local emsg = eok and "<no error>" or strip(eerr)
  local writerCap = capIn(emsg)
  check(not eok, "a table nested past the cap is refused", emsg)
  check(writerCap ~= nil, "the refusal names the depth limit", emsg)
  check(writerCap == readerCap, "encode and parse answer to the same cap (" .. readerCap .. ")", writerCap)

  -- The boundary, from the inside: at the cap, and one level in.
  local bok, bs = pcall(function() return hafen.json():encode(deepTable(readerCap)) end)
  check(bok, "a table exactly at the cap encodes", bs)

  local inside = readerCap - 1
  local iok, is = pcall(function() return hafen.json():encode(deepTable(inside)) end)
  local rok, rt = false, nil
  if iok then
    rok, rt = pcall(function() return hafen.json():parse(is) end)
  end
  check(iok and rok, "a table one level inside the cap encodes and parses back", iok and rt or is)
  local n, leaf = 0, nil
  if rok then n, leaf = levelsOf(rt) end
  check((n == inside) and (leaf == "ore"),
        "...with all " .. inside .. " of its levels and its leaf intact",
        n .. " levels, leaf=" .. tostring(leaf))

  -- The guard standing beside the new one: a cycle is still refused by name, so a regression in either
  -- is caught here.
  local cyc = {}
  cyc.self = cyc
  refuses("the cycle refusal beside it still stands",
          function() return hafen.json():encode(cyc) end, "cannot encode a table cycle")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t126", run)   -- the only way in: a suite does not start itself
