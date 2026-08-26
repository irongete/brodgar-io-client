-- 109.3 — a place resolves through a proved base. Self-checking suite.
-- Type :t109 in the world, with TWO characters logged in and standing far enough apart that neither
-- is streaming the ground the other is standing on. The addon needs `console.run` enabled and
-- approved.
--
-- The claim is that the two bridges agree. One durable place is asked of every session: the one
-- standing on it answers off the terrain it is streaming, and the one far away answers off the map
-- database, through the base this feature made it prove. The difference between the two answers has
-- to be the very offset `:session where` reports for that pair -- and that line is read back out of
-- the character's own System log, where Sessions.say puts it a frame after the run.

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

local WAIT = 1.5        -- the say is queued and drained by the next tick; this is many of those
local GRIDU = 1100      -- world units to a grid: 100 tiles of 11
local EPS = 0.05        -- two answers that agree agree exactly; this is float slack, not tolerance

-- LuaJ ignores the precision on %f, so every number this suite prints is rounded by hand.
local function n(v)
  return tostring(math.floor(v + 0.5))
end

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

-- Does that line report a proved base at all?
local function proved(tail)
  return (tail ~= nil) and (tail:match("^base %x+ tile %(%-?%d+, %-?%d+%), proved") ~= nil)
end

-- The offset that line reports, in grids, or nil.
local function offsetOf(tail)
  local x, y = tail:match("offset %((%-?%d+), (%-?%d+)%) grids")
  if not x then return nil end
  return tonumber(x), tonumber(y)
end

-- What that line names as its reason for not being related to the anchor, or nil. It sits straight
-- after the word `proved`; a line carrying no proved base at all is refused for the reason it gives
-- instead of a base.
local function refusalOf(tail)
  if tail == nil then return nil end
  if not proved(tail) then
    return (tail:match("^no proved base %-%- (.+)$")) or tail
  end
  local why = tail:match("^base %x+ tile %(%-?%d+, %-?%d+%), proved, ([^%-]+)")
  if not why then return nil end
  why = why:gsub("%s+$", "")
  if (why == "") or (why == "the anchor") then return nil end
  return why
end

-- The base a line reports, as segment id and tile coord, or nil when it reports none.
local function baseOf(tail)
  local seg, x, y = tail:match("^base (%x+) tile %((%-?%d+), (%-?%d+)%), proved")
  if not seg then return nil end
  return seg, tonumber(x), tonumber(y)
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

-- How far apart two characters actually stand, in SEGMENT tiles -- base tile plus own tile is the one
-- coordinate they can both be said in. A property of the run, not of the client: it is what says
-- whether a run reached the distance at which the recorded bridge is the only one that can answer.
local function apart(tail, users)
  local at, far = {}, nil
  for _, u in ipairs(users) do
    local _, bx, by = baseOf(tail[u] or "")
    local ox, oy = ownOf(tail[u] or "")
    if bx and ox then at[u] = { bx + ox, by + oy } end
  end
  for _, a in ipairs(users) do
    for _, b in ipairs(users) do
      if (a ~= b) and at[a] and at[b] then
        local d = math.max(math.abs(at[a][1] - at[b][1]), math.abs(at[a][2] - at[b][2]))
        if (far == nil) or (d > far) then far = d end
      end
    end
  end
  return far
end

-- Is that session streaming the ground this place stands on? `grid():get(id)` is the LIVE lookup and
-- takes no base at all, so it is what tells the two bridges apart: a session that answers for a
-- place whose grid it does not hold answered off the map database, through its proved base.
local function streaming(s, gid)
  local ok, g = pcall(function() return s:world():grid():get(gid) end)
  return ok and (g ~= nil)
end

local st = {}

local function score()
  local lines = since(st.sys, st.before)
  local cur = hafen.session():current()
  if not cur then
    check(false, "a character is on screen", "none")
    return report()
  end
  local anchoruser = cur:user()

  -- 1. the place under test: where the anchor's own character stands. It is durable -- the anchor is
  --    streaming that ground, so the server has minted an id for its grid -- and the durable form is
  --    the whole of what one session can hand another.
  local me = cur:player():gob()
  local p = me and me:position()
  local info = p and p:info()
  local gid = info and info.gridId
  check(gid ~= nil, "the place under test is durable, so it names a grid the server minted",
        (p == nil) and "no character gob yet" or "no durable form -- is the anchor in the world?")
  if not gid then return report() end

  -- Ask every session in the world where that one place is, in its own frame.
  local inworld, sess, answer, tail = {}, {}, {}, {}
  for _, s in ipairs(hafen.session():list()) do
    local u = s:user()
    tail[u] = whereFor(lines, u)
    if s:character() and tail[u] then
      inworld[#inworld + 1] = u
      sess[u] = s
      local ok, c = pcall(function() return s:world():components(p) end)
      if ok then
        answer[u] = c
      else
        check(false, "asking a session where a place is answers rather than raising", tostring(c))
      end
    end
  end
  check(#inworld >= 2, "two characters are in the world",
        ("only %d -- `:session add` a second and re-run"):format(#inworld))
  if #inworld < 2 then return report() end

  -- 2. the shape rule, and it holds in every state this can be run in: a session answers for that
  --    place, or its own `where` line says why it cannot. Nothing goes silent.
  local silent = {}
  for _, u in ipairs(inworld) do
    if (answer[u] == nil) and (refusalOf(tail[u]) == nil) then
      silent[#silent + 1] = u .. " -> " .. tostring(tail[u])
    end
  end
  check(#silent == 0,
        ("every session in the world answers where that place is, or its line says why (%d in world)")
          :format(#inworld),
        table.concat(silent, " | "))

  -- 3. THE claim: the two bridges agree. The difference between what two sessions answer for one
  --    place is the offset their lines report for that pair -- one read it off the terrain it is
  --    streaming and the other off the database through its base, and the two land on one patch of
  --    ground.
  local anchorat = answer[anchoruser]
  local npairs, wrong, refused = 0, {}, 0
  for _, u in ipairs(inworld) do
    if u ~= anchoruser then
      if refusalOf(tail[u]) then
        refused = refused + 1
      elseif anchorat and answer[u] then
        local gx, gy = offsetOf(tail[u])
        if not gx then
          wrong[#wrong + 1] = u .. ": answers, but its line reports no offset -> " .. tostring(tail[u])
        else
          npairs = npairs + 1
          local dx, dy = answer[u].x - anchorat.x, answer[u].y - anchorat.y
          if (math.abs(dx - (gx * GRIDU)) > EPS) or (math.abs(dy - (gy * GRIDU)) > EPS) then
            wrong[#wrong + 1] = ("%s: the answers differ by (%s, %s) units, its line says (%d, %d) grids")
                                  :format(u, n(dx), n(dy), gx, gy)
          end
        end
      else
        wrong[#wrong + 1] = u .. ": no answer, and its line names no refusal -> " .. tostring(tail[u])
      end
    end
  end
  check(((npairs > 0) or (refused > 0)) and (#wrong == 0),
        ("the difference between two sessions' answers is the offset their lines report"
         .. " (%d pair%s, %d refused)"):format(npairs, (npairs == 1) and "" or "s", refused),
        (#wrong > 0) and table.concat(wrong, " | ")
          or "no session is either related to the anchor or refused by name")

  -- 4. and an answer came through the BASE rather than off live terrain. A session holding that grid
  --    answers from what it is streaming and proves nothing about the record; the claim is about the
  --    one that does not hold it and answers anyway.
  local recorded, sep = {}, apart(tail, inworld)
  for _, u in ipairs(inworld) do
    if (answer[u] ~= nil) and not streaming(sess[u], gid) then
      recorded[#recorded + 1] = u
    end
  end
  check((#recorded > 0) or ((npairs == 0) and (refused > 0)),
        ("a session answers for a place whose grid it is not streaming -- the recorded bridge,"
         .. " through its proved base (%d of %d, %s tiles apart)")
          :format(#recorded, #inworld, sep and n(sep) or "?"),
        ("every session is streaming that grid, so both answered off live terrain and the base was"
         .. " never asked. The two characters stand %s tiles apart; the server keeps a session's"
         .. " grids around its own character, so walk them several grids apart -- 300 tiles or so"
         .. " -- and re-run"):format(sep and n(sep) or "an unreported number of"))

  -- 5. and a session whose base that same line calls refused answers NOTHING for it. A number here
  --    would be a place the character has never been, said with a straight face, which is the whole
  --    of what this task exists to make impossible. Scored over the sessions that are NOT streaming
  --    that grid: one standing on it answers off the live terrain, refused base or not, and rightly.
  local leaked = {}
  for _, u in ipairs(inworld) do
    if refusalOf(tail[u]) and not streaming(sess[u], gid) and (answer[u] ~= nil) then
      leaked[#leaked + 1] = ("%s: answered (%s, %s) -> %s")
                              :format(u, n(answer[u].x), n(answer[u].y), tostring(tail[u]))
    end
  end
  check(#leaked == 0,
        ("a session whose line names a refused base answers nothing for that place, not a number"
         .. " (%d refused this run)"):format(refused),
        table.concat(leaked, " | "))

  manualCheck("tab to the second character with `:session next`, run `:chrmap alt` on it, then"
              .. " `:session drop USER` and `:session add USER` -- a map database is read once, at"
              .. " enter-world -- and run :t109 again from the first character",
              "that session's `:session where` line reports its own proved base and then a MapFile"
              .. " refusal by name (a different map database from the anchor's), and carries no"
              .. " offset. The run's own lines then read `0 pairs, 1 refused` and `1 refused this"
              .. " run`, with every check still passing: the refused session answers nothing at all"
              .. " for the anchor's place")
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
  -- Off the console's own tree and onto the step: the line below reaches every session the client
  -- holds, and the step is the one place that holds no tree monitor.
  hafen.timer():after(0, function()
    local ok, err = pcall(function() s:console():run("session where") end)
    if not ok then
      check(false, "the suite may run a console line", tostring(err))
      return report()
    end
    hafen.timer():after(WAIT, score)
  end)
end

hafen.console():on("t109", run)   -- the only way in: a suite does not start itself
