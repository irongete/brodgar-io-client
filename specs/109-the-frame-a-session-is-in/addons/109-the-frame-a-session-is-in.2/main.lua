-- 109.2 — the offset is a difference of bases. Self-checking suite.
-- Type :t109 in the world, with TWO characters logged in and standing far enough apart to have no
-- ground in common. The addon needs `console.run` enabled and approved.
--
-- Everything below is read back out of the character's own System log, because that is where both
-- `:session where` and `:session list` put their answers: Sessions.say queues a line and the next
-- frame delivers it to the anchor's notice, which GameUI logs. So the run is one step and the
-- scoring is a timer later.

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

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local WAIT = 1.5      -- the say is queued and drained by the next tick; this is many of those
local GRID = 100      -- tiles to a grid
local BLOCK = 3       -- a session holds a 3x3 block of grids, so further than this is no shared ground

-- The System log of one character: the channel the `:session` lines land in.
local function syslog(s)
  return s:chat():find(function(ch) return ch:kind() == "chat.system" end)
end

-- Every System line the run left, oldest first and deduped. A notice is printed AND logged, so one
-- line arrives twice -- count nothing.
local function since(sys, from)
  local out, seen = {}, {}
  for i = from, sys:message():count() do
    local m = sys:message():get(i)
    local t = m and m:text()
    if t and not seen[t] then
      seen[t] = true
      out[#out + 1] = t
    end
  end
  return out
end

-- The `:session where` line for one account: "session: USER: <tail>".
local function whereFor(lines, user)
  local head = "session: " .. user .. ": "
  for _, l in ipairs(lines) do
    if l:sub(1, #head) == head then
      return l:sub(#head + 1)
    end
  end
  return nil
end

-- The `:session list` line for one account: "session: USER:CHARACTER(Ng) <check>". A character name
-- has spaces in it, so the count of grids is what marks the line; the `where` line above is told
-- apart by the space after the account's colon, which the status line does not have.
local function statusFor(lines, user)
  local wherehead = "session: " .. user .. ": "
  local head = "session: " .. user .. ":"
  for _, l in ipairs(lines) do
    if (l:sub(1, #head) == head) and (l:sub(1, #wherehead) ~= wherehead) then
      local st = l:match("%(%d+g%) (.+)$")
      if st then return st end
    end
  end
  return nil
end

-- The base a line reports, as segment id and tile coord, or nil when it reports none.
local function baseOf(tail)
  local seg, x, y = tail:match("^base (%x+) tile %((%-?%d+), (%-?%d+)%), proved")
  if not seg then return nil end
  return seg, tonumber(x), tonumber(y)
end

-- The offset a line reports, in grids, or nil.
local function offsetOf(tail)
  local x, y = tail:match("offset %((%-?%d+), (%-?%d+)%) grids")
  if not x then return nil end
  return tonumber(x), tonumber(y)
end

-- Where the character stands in its OWN frame, in tiles, from either shape the line takes.
local function ownOf(tail)
  local x, y = tail:match("own %((%-?[%d%.]+), (%-?[%d%.]+)%) %->")
  if not x then
    x, y = tail:match("at %((%-?[%d%.]+), (%-?[%d%.]+)%) in its own frame")
  end
  if not x then return nil end
  return tonumber(x), tonumber(y)
end

-- Where that same place is said to be in the ANCHOR's frame, in tiles.
local function saidOf(tail)
  local x, y = tail:match("%-> anchor %((%-?[%d%.]+), (%-?[%d%.]+)%)")
  if not x then return nil end
  return tonumber(x), tonumber(y)
end

-- What a line names as its reason for having no offset, or nil when it names none. It sits on the
-- base's half of the line, straight after the word `proved`.
local function refusalOf(tail)
  local why = tail:match("^base %x+ tile %(%-?%d+, %-?%d+%), proved, ([^%-]+)")
  if not why then return nil end
  why = why:gsub("%s+$", "")
  if (why == "") or (why == "the anchor") then return nil end
  return why
end

local st = {}

local function score()
  local lines = since(st.sys, st.before)
  local sessions = hafen.session():list()
  local cur = hafen.session():current()

  -- Gather what every session said, and which of them is the anchor.
  local tails, anchoruser = {}, cur and cur:user()
  local inworld = {}
  for _, s in ipairs(sessions) do
    local tail = whereFor(lines, s:user())
    tails[s:user()] = tail
    if tail and s:character() then
      inworld[#inworld + 1] = s:user()
    end
  end

  -- 1. the setup this task is about: two characters in the world, and a proved base for each. A
  --    suite stands alone, so the base is re-proved here rather than left to any other run.
  local unproved = {}
  for _, u in ipairs(inworld) do
    if not baseOf(tails[u]) then
      unproved[#unproved + 1] = u .. " -> " .. tails[u]
    end
  end
  check((#inworld >= 2) and (#unproved == 0),
        "two characters are in the world and each reports a proved base",
        (#inworld < 2) and ("only " .. #inworld .. " in the world -- `:session add` a second and re-run")
          or table.concat(unproved, " | "))
  if (#inworld < 2) or (#unproved > 0) then
    return report()
  end

  -- 2. how far apart the run actually reached, in tiles. Each session streams a 3x3 block of grids
  --    around itself, so past that the two share no ground at all and the intersection the offset
  --    was derived from is empty. This is a property of THE RUN and not of the client -- the two
  --    have to stand together for the manual step below -- so it is not scored on its own; it names
  --    the distance the next check was made at.
  local segtile = {}
  for _, u in ipairs(inworld) do
    local _, bx, by = baseOf(tails[u])
    local ox, oy = ownOf(tails[u])
    if ox then segtile[u] = { bx + ox, by + oy } end
  end
  local sep = nil
  for _, a in ipairs(inworld) do
    for _, b in ipairs(inworld) do
      if (a ~= b) and segtile[a] and segtile[b] then
        local d = math.max(math.abs(segtile[a][1] - segtile[b][1]),
                           math.abs(segtile[a][2] - segtile[b][2]))
        if (sep == nil) or (d > sep) then sep = d end
      end
    end
  end
  -- LuaJ's string.format ignores the precision on %f, so every number the suite prints is whole.
  local tilesapart = sep and math.floor(sep + 0.5)
  local apart = (tilesapart ~= nil) and (tilesapart > BLOCK * GRID)

  -- 3. and they are related. THIS is the pair that has no offset before this task. Two checks,
  --    because a named refusal is a correct answer and silence is not: no session may be left
  --    unrelated without a reason, and the run has to reach at least one live relation or it has
  --    demonstrated nothing.
  local silent, related, why = {}, 0, {}
  for _, u in ipairs(inworld) do
    if u ~= anchoruser then
      if offsetOf(tails[u]) then
        related = related + 1
      elseif refusalOf(tails[u]) then
        why[#why + 1] = u .. ": " .. refusalOf(tails[u])
      else
        silent[#silent + 1] = u .. " -> " .. tails[u]
      end
    end
  end
  check(#silent == 0, "no session is left unrelated to the anchor without a reason on its line",
        table.concat(silent, " | "))
  check(related > 0,
        apart and ("a session %d tiles from the anchor -- past the %d it streams -- is related to it"
                   .. " by an offset"):format(tilesapart, BLOCK * GRID)
              or ("every session is related to the anchor, %s tiles away -- walk them past %d tiles"
                  .. " and re-run to reach the pair that shares no ground")
                   :format(tostring(tilesapart), BLOCK * GRID),
        (#why > 0) and (table.concat(why, " | ") .. " -- run with both characters above ground")
          or ((#silent > 0) and "and no line names a reason either"
              or "only the anchor is in the world -- `:session add` a second and re-run"))

  -- 4. the offset IS the difference of the two bases, and nothing else. Both halves are on the
  --    lines, so this is checkable without asking the client a second time.
  local _, ax, ay = baseOf(tails[anchoruser] or "")
  local wrong, notgrid = {}, {}
  for _, u in ipairs(inworld) do
    if (u ~= anchoruser) and ax then
      local _, bx, by = baseOf(tails[u])
      local gx, gy = offsetOf(tails[u])
      if gx then
        if ((ax - bx) % GRID ~= 0) or ((ay - by) % GRID ~= 0) then
          notgrid[#notgrid + 1] = ("%s: bases (%d, %d) and (%d, %d)"):format(u, bx, by, ax, ay)
        elseif (gx ~= (ax - bx) / GRID) or (gy ~= (ay - by) / GRID) then
          wrong[#wrong + 1] = ("%s: says (%d, %d), the bases give (%d, %d)")
                                :format(u, gx, gy, (ax - bx) / GRID, (ay - by) / GRID)
        end
      end
    end
  end
  check((ax ~= nil) and (#wrong == 0), "each offset is the anchor's base tile minus that session's",
        (ax == nil) and "the anchor reports no base" or table.concat(wrong, " | "))
  check(#notgrid == 0, "each offset is a whole number of grids -- both bases are grid-aligned",
        table.concat(notgrid, " | "))

  -- 5. the offset the line prints is the one the line APPLIES: the character's own place, said in
  --    the anchor's frame, is its own place less the offset.
  local misapplied = {}
  for _, u in ipairs(inworld) do
    if u ~= anchoruser then
      local gx, gy = offsetOf(tails[u])
      local ox, oy = ownOf(tails[u])
      local sx, sy = saidOf(tails[u])
      if gx and ox and sx then
        if (math.abs((ox - gx * GRID) - sx) > 0.05) or (math.abs((oy - gy * GRID) - sy) > 0.05) then
          -- Quoted rather than re-formatted: LuaJ's string.format ignores the precision on %f, so a
          -- number written back out would be longer than the one the line carries, not clearer.
          misapplied[#misapplied + 1] = u .. " -> " .. tails[u]
        end
      end
    end
  end
  check(#misapplied == 0, "the place said in the anchor's frame is the own place less that offset",
        table.concat(misapplied, " | "))

  -- 6. the shape rule, over every line the run reached: a refusal carries NO offset. A stale number
  --    beside a named refusal is the whole failure this task exists to make impossible.
  local leaked, refused = {}, 0
  for _, u in ipairs(inworld) do
    if refusalOf(tails[u]) then
      refused = refused + 1
      if offsetOf(tails[u]) then
        leaked[#leaked + 1] = u .. " -> " .. tails[u]
      end
    end
  end
  check(#leaked == 0,
        ("a line that names a refusal reports no offset (%d of %d refused)"):format(refused, #inworld),
        table.concat(leaked, " | "))

  -- 7. the hard cut: the intersection of two live grid tables is gone, and so is every word it
  --    printed. Nothing may still count shared grids or call a pair unanchored.
  local stale = {}
  for _, l in ipairs(lines) do
    if l:find("shared grid", 1, true) or l:find("unanchored", 1, true)
        or l:find("DISAGREEING", 1, true) then
      stale[#stale + 1] = l
    end
  end
  check(#stale == 0, "no line counts shared grids, calls a pair unanchored, or reports a disagreement",
        table.concat(stale, " | "))

  -- 8. `:session list` reports that same offset in grids -- the short form of the same derivation.
  local badstatus = {}
  for _, u in ipairs(inworld) do
    local status = statusFor(lines, u)
    if not status then
      badstatus[#badstatus + 1] = u .. " -> no `session list` line"
    elseif u == anchoruser then
      if not status:find("the anchor", 1, true) then
        badstatus[#badstatus + 1] = u .. " (the anchor) -> " .. status
      end
    else
      local gx, gy = offsetOf(tails[u])
      local sx, sy = status:match("^off %((%-?%d+), (%-?%d+)%)")
      if gx then
        if (not sx) or (tonumber(sx) ~= gx) or (tonumber(sy) ~= gy) then
          badstatus[#badstatus + 1] = u .. " -> " .. status
        end
      elseif sx then
        -- No offset on its `where` line, so the short form must not carry one either.
        badstatus[#badstatus + 1] = u .. " -> no offset where it says why, but `" .. status .. "`"
      end
    end
  end
  check(#badstatus == 0, "`session list` reports that same offset in grids for every session",
        table.concat(badstatus, " | "))

  manualCheck("walk the two characters together, then one of them into a house and out again,"
              .. " running :t109 after each",
              "standing together: the merged patch, an RTS left-click order and the selection behave"
              .. " as they always have. Inside the house the pair is refused BY NAME (a different"
              .. " segment) and nothing of that character is drawn. Out again: related once more, at"
              .. " the same offset as before, with nothing drawn through the old base in between")
  report()
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t109 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t109 in the world")
    return report()
  end
  local sys = syslog(s)
  if not sys then
    check(false, "the character's System log is readable", "no System channel -- is the HUD up?")
    return report()
  end
  st = { sys = sys, before = sys:message():count() + 1 }
  -- Off the console's own tree and onto the step: the lines below reach every session the client
  -- holds, and the step is the one place that holds no tree monitor.
  hafen.timer():after(0, function()
    local ok, err = pcall(function()
      s:console():run("session where")
      s:console():run("session list")
    end)
    if not ok then
      check(false, "the suite may run a console line", tostring(err))
      return report()
    end
    hafen.timer():after(WAIT, score)
  end)
end

hafen.console():on("t109", run)   -- the only way in: a suite does not start itself
