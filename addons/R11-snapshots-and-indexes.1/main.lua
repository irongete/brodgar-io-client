-- R11 -- snapshots and indexes.
--
-- Two rules, asked of every surface the block moved onto them. One: every index this API answers is
-- 1-based, and where the server's own number differs it is a separate verb naming itself as the wire's --
-- an inventory cell, a deck card, a meter segment, a petal. Two: a snapshot field names a verb and carries
-- that verb's value -- a gob's place and size, a wound's severity, a channel's name, a recipe's name. With
-- them go the four reads that were answering the wrong thing entirely: a gob's place is now the point it is
-- DRAWN at, a terrain read off streamed ground asks the server for nothing, a speed list agrees with :get,
-- and the grid-area cap counts in long instead of wrapping.
--
-- Run it with :tR11, with a character in the world. It starts nothing by itself and declares no
-- permission. Two of its subjects are things only the player can produce -- a walk, and two radial menus one
-- after the other -- so it WAITS for them rather than timing you out, and prints as soon as it has both (or
-- after a minute, scoring what it reached). A ring needs a right-click on something that OFFERS A CHOICE --
-- a tree, a bush, a stockpile, a kin -- because right-clicking bare ground just walks there and opens
-- nothing. It also wants an item in the TOP-LEFT cell of your backpack and an open container in it (a
-- bucket, a basket); a combat school makes one more check say more.
--
-- Each run starts its own tally: the counters are reset in run(), so a second :tR11 in one session prints
-- that run and nothing of the one before it.

local L = hafen.log()

local CAP = 60                             -- seconds it will wait for the walk and the two rings
local WALK = 15                            -- samples of a moving character it wants before it scores one
local out, pass, fail, man = {}, 0, 0, 0
local waiting = false                      -- one run at a time: two would interleave into one tally

local function ok(what)
  pass = pass + 1
  out[#out + 1] = "[pass] " .. what
end

local function bad(what, got)
  fail = fail + 1
  out[#out + 1] = "[fail] " .. what .. " -- got: " .. tostring(got)
end

local function check(what, cond, got)
  if cond then ok(what) else bad(what, got) end
end

local function manual(what, expect)
  man = man + 1
  out[#out + 1] = "[manual] " .. what .. " -- expect: " .. expect
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the strip has
-- to allow both shapes or every message read starts with the chunk name.
local function why(e)
  e = tostring(e or "")
  return (e:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Call fn and answer: was it refused, and what did it say.
local function refused(fn)
  local done, err = pcall(fn)
  if done then return false, "<no error>" end
  return true, why(err)
end

local function says(text, needle)
  return text:find(needle, 1, true) ~= nil
end

local function flush()
  for _, line in ipairs(out) do L:write(line) end
  L:write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. man .. " manual")
end

-- ---- rule one: an index is 1-based, and the wire's number is its own verb -------------------------

local function items(s)
  local pack = s:ui():inventory()
  return pack and pack:items():list() or {}
end

local function indexes(s)
  -- An inventory cell. The top-left cell of a container is {1, 1}, so nothing here is ever 0.
  local cells, minx, miny, corner = 0, nil, nil, false
  for _, it in ipairs(items(s)) do
    local c = it:cell()
    if c then
      cells = cells + 1
      if (minx == nil) or (c.x < minx) then minx = c.x end
      if (miny == nil) or (c.y < miny) then miny = c.y end
      if (c.x == 1) and (c.y == 1) then corner = true end
    end
  end
  check("item:cell() is 1-based, and the top-left cell reads {1, 1} (" .. cells .. " items, smallest {"
        .. tostring(minx) .. ", " .. tostring(miny) .. "})",
        (cells > 0) and (minx ~= nil) and (minx >= 1) and (miny >= 1) and corner,
        cells .. " items, corner=" .. tostring(corner))

  -- A deck card. :index() is the position in the very list it came out of, whatever the empty hotkeys
  -- before it; :wire() is the raw 0-based slot, which is what the write path takes. The collection states
  -- that contract in its own refusal, which is readable whether or not a school is loaded, and the numbers
  -- are checked over whatever cards the character has.
  local deck, deckOk, wireOk = s:fight():deck():list(), true, true
  for n, card in ipairs(deck) do
    if card:index() ~= n then deckOk = false end
    if (card:wire() < 0) or (card:wire() < n - 1) then wireOk = false end
  end
  local no, msg = refused(function() s:fight():deck():get(1) end)
  check("deck():get is refused naming card:index() as that position back, and list()[n]:index() is n ("
        .. #deck .. " cards)",
        no and says(msg, "card:index() is that same position back") and deckOk and wireOk,
        #deck .. " cards, index=" .. tostring(deckOk) .. " wire=" .. tostring(wireOk) .. ", get: " .. msg)

  -- A meter segment. The bar is read compacted, so the list, the array in the snapshot and the position a
  -- segment reports are one count: an empty slot is not a segment and leaves no hole for ipairs.
  local bars, segs, segOk = 0, 0, true
  for _, m in ipairs(s:meter():list()) do
    bars = bars + 1
    local list = m:segment():list()
    local arr = m:info().segments
    for n, seg in ipairs(list) do
      segs = segs + 1
      if seg:index() ~= n then segOk = false end
      if (arr[n] == nil) or (arr[n].value ~= seg:value()) then segOk = false end
    end
    if #arr ~= #list then segOk = false end
  end
  check("meter:segment():list()[n]:index() is n, and info().segments has no hole (" .. bars
        .. " bars, " .. segs .. " segments)", (segs > 0) and segOk, "bars=" .. bars .. " segs=" .. segs)

  -- The speed selector. :list() and :get() answer the same question or neither does.
  local sp, spOk = s:speed():list(), true
  for n = 1, 4 do
    if sp[n] ~= s:speed():get(n) then spOk = false end
  end
  check("s:speed():list()[n] is s:speed():get(n) for all four (" .. #sp .. " listed)",
        (#sp == 4) and spOk, #sp .. " listed, agree=" .. tostring(spOk))
end

-- ---- rule two: a snapshot field carries what its verb carries -------------------------------------

local function snapshots(s)
  local g = s:player():gob()
  local info = g and g:info()
  local p = g and g:position()
  check("gob:info() carries the drawn place and the size the write pair reads (scale="
        .. tostring(info and info.scale) .. ")",
        (info ~= nil) and (info.scale == g:scale()) and (info.x ~= nil) and (p ~= nil)
        and (math.abs(info.x - p:x()) < 1) and (math.abs(info.y - p:y()) < 1),
        "scale=" .. tostring(info and info.scale) .. " x=" .. tostring(info and info.x)
        .. " vs " .. tostring(p and p:x()))

  -- A wound's severity is the NUMBER its verb answers, never the label string beside it; a channel's name
  -- is absent rather than the client's own "???" placeholder; a recipe with no name states that with no
  -- key. Each is scored over whatever the character actually carries, and the counts say which.
  local subj, agree = 0, true
  for _, w in ipairs(s:wound():list()) do
    subj = subj + 1
    local sev = w:info().severity
    if (sev ~= w:severity()) or ((sev ~= nil) and (type(sev) ~= "number")) then agree = false end
  end
  for _, ch in ipairs(s:chat():list()) do
    subj = subj + 1
    if (ch:info().name ~= ch:name()) or (ch:name() == "???") then agree = false end
  end
  if s:craft():exists() then
    subj = subj + 1
    if s:craft():info().recipe ~= s:craft():recipe() then agree = false end
  end
  check("a wound's severity, a channel's name and a recipe's name read the same from :info() and from the"
        .. " verb (" .. subj .. " subjects)", (subj > 0) and agree, subj .. " subjects, agree="
        .. tostring(agree))

  -- A refusal names the verb the reader is to write. contents:fill() files its pair under `level` in the
  -- snapshot, and the bad-receiver message used to promise a contents:level() that does not exist.
  local held
  for _, it in ipairs(items(s)) do
    held = held or it:contents()
  end
  if held == nil then
    bad("a bad receiver on contents:fill() is refused naming contents:fill()", "no container in the pack")
  else
    local no, msg = refused(function() held.fill({}) end)
    check("a bad receiver on contents:fill() is refused naming contents:fill()",
          no and says(msg, "contents:fill()") and not says(msg, "contents:level()"), msg)
  end
end

-- ---- the four reads that answered the wrong thing --------------------------------------------------

local function cap()
  local seg = hafen.map():segment():current()
  if seg == nil then
    bad("seg:grid():list(area) refuses 65536x65536 as the 4294967296 grids it is", "no segment yet")
    return
  end
  local no, msg = refused(function() seg:grid():list{x = 0, y = 0, w = 65536, h = 65536} end)
  check("seg:grid():list(area) refuses 65536x65536 as the 4294967296 grids it is",
        no and says(msg, "4294967296") and says(msg, "65536x65536"), msg)
end

-- ---- the run -----------------------------------------------------------------------------------------

local function run()
  if waiting then
    L:write("[R11] a run is already waiting for its walk and its two rings; this one is ignored")
    return
  end
  out, pass, fail, man = {}, 0, 0, 0        -- this run's own tally, not the last one's as well
  local s = hafen.session():current()
  if not s then
    L:write("[fail] a character has to be in the world -- got: no session on screen")
    L:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  waiting = true

  -- Off streamed ground, well outside anything this character has loaded. A read there answers nil and
  -- must ask the server for NOTHING: a request would be served, and the point would start answering.
  local me = s:player():gob()
  local here = me and me:position()
  local far = here and here:offset(40000, 40000)
  local farStart = far and (s:world():tile(far) == nil) and (s:world():height(far) == nil)
  if far then
    for _ = 1, 50 do s:world():tile(far) end
    for _ = 1, 50 do s:world():height(far) end
  end

  -- Where the character is DRAWN moves every frame while it walks; where the server last said it was moves
  -- a few times a second. Sampled twenty times a second -- under a second of walking is enough -- and
  -- scored over the samples taken while it actually moved.
  local moved, seen, last = 0, 0, nil
  local sampler = hafen.timer():every(0.05, function()
    local g = s:player():gob()
    if g and g:moving() then
      local p = g:position()
      if p then
        moved = moved + 1
        if (last == nil) or (math.abs(p:x() - last) > 0.0001) then seen = seen + 1 end
        last = p:x()
      end
    end
  end)

  -- A petal belongs to the ring it came off. Once a second ring is up, the first ring's petals are dead --
  -- they used to read, and pick from, whatever was on screen instead.
  local rings, wireOk, staleAlive, stale = 0, true, false, nil
  local ring = hafen.event():on("FlowerMenuAdded", function(petals)
    rings = rings + 1
    for _, petal in ipairs(petals) do
      if petal:wire() ~= petal:index() - 1 then wireOk = false end
    end
    if rings == 1 then
      stale = petals
    elseif stale then
      for _, petal in ipairs(stale) do
        if petal:exists() or (petal:label() ~= nil) then staleAlive = true end
      end
    end
  end)

  L:write("[R11] WALK a second, then RIGHT-CLICK A TREE (or a bush, or a kin) TWICE -- bare ground opens"
          .. " no ring. It prints as soon as it has a walk and two rings, and after " .. CAP .. " s"
          .. " whatever it reached")

  local waited = 0
  local watcher
  local function score()
    waiting = false
    sampler:cancel()
    ring:off()
    watcher:cancel()

    indexes(s)
    snapshots(s)
    cap()

    check("a terrain read off streamed ground answers nil and leaves it nil, asking the server for nothing",
          (far ~= nil) and farStart and (s:world():tile(far) == nil) and (s:world():height(far) == nil),
          "start=" .. tostring(farStart) .. " after=" .. tostring(far and s:world():tile(far)))

    check("gob:position() is the drawn point and moves between server messages (" .. seen .. " of "
          .. moved .. " samples while moving)", (moved >= WALK) and (seen >= (moved * 8) / 10),
          seen .. " of " .. moved .. " -- it wanted " .. WALK .. " samples of a walk")

    check("a petal knows its own ring: petal:wire() is index() - 1, and ring one's petals are dead once"
          .. " ring two is up (" .. rings .. " rings)", (rings >= 2) and wireOk and not staleAlive,
          rings .. " rings -- it wanted two, off a right-click that opens one -- wire=" .. tostring(wireOk)
          .. " stale-alive=" .. tostring(staleAlive))

    manual("copy a savedata store file, corrupt the copy by hand, put it back and relaunch",
           "the log names the file and calls that scope read-only, the addon reads empty, and the file on"
           .. " disk is byte-identical after a write")
    flush()
  end

  -- It waits for the player's own two subjects rather than racing them, and scores what it reached at CAP.
  watcher = hafen.timer():every(1, function()
    waited = waited + 1
    if ((rings >= 2) and (moved >= WALK)) or (waited >= CAP) then score() end
  end)
end

-- The console handler runs under the typed tree's monitor, so the run is deferred by a tick.
hafen.console():on("tR11", function()
  hafen.timer():after(0, run)
end)
