-- 076.5 - walking a character you are not looking at. Self-checking suite.
--
-- What it proves: s:player():move(p) is one verb with an address on it. The "player.move" key this
-- addon declares is the whole gate (getting past it to the argument refusal IS the grant, and
-- world.place, which it did NOT declare, still refuses); the Position is resolved against the map of
-- the character NAMED rather than the one drawn, so the refusal says whose reach it is out of; and
-- the send picks its door by who is on screen -- wdgmsg for the drawn session, so an addon's own
-- action hooks see the order exactly as they see a real click, and rawWdgmsg for a background one,
-- so they do not. An armed action hook is what reads both halves of that back.
--
-- Every walk it orders is to where that character is ALREADY standing and so moves nothing, with
-- one exception: the last one walks the background character to the drawn one's spot, which is the
-- one claim here a program cannot watch and so is the [manual] line.
--
-- HOW TO RUN IT. `:t076-5`, in the world. With a second session up (`:session add <account>`,
-- standing near the first) it also proves the reach; with one, it says so and asks for the second.

local pass, fail, manual = 0, 0, 0
local log = hafen.log()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log:write("[pass] " .. what)
  else
    fail = fail + 1
    log:write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log:write("[manual] " .. step .. " -- expect: " .. expect)
end

--- Order a walk, and answer "" or why it did not go. Never throws: a refusal is data here.
local function order(session, p)
  local ok, err = pcall(function() return session:player():move(p) end)
  if ok then
    return (err == session:player()) and "" or "move() did not return the Player"
  end
  return "threw: " .. tostring(err):gsub("^.-%.lua:%d+:%s*", "")
end

local function run()
  pass, fail, manual = 0, 0, 0

  local s = hafen.session():current()
  local me = s and s:player():gob()
  local here = me and me:position()
  if here == nil then
    log:write("[summary] 0 pass, 0 fail, 0 manual -- get into the world and run :t076-5 again")
    return
  end
  local user = s:user()

  -- The action hook, armed for the whole run: it is how BOTH sends are read back. A click on the
  -- map is the only message it counts, and it records the destination so the drawn order can be
  -- told apart from a stray real click. It is ended before the summary -- a suite leaves nothing on.
  local seen, lastGrid = 0, nil
  local sub = hafen.event():action():on("click", function(ev)
    if ev:sender():type() ~= "MapView" then return end
    seen = seen + 1
    local ok, p = pcall(function() return ev:position(2) end)
    lastGrid = (ok and p and p:info() or {}).gridId
  end)

  -- 1. The grant, and the send to the character on screen. The gate is the verb's FIRST statement,
  -- so reaching the walk at all is the consent having held.
  local before = seen
  local why = order(s, here)
  check(why == "", "player.move granted: " .. user .. " was ordered to where it stands", why)

  -- 2. The drawn session sends through UI.wdgmsg, so the action hooks see the order as a real click.
  check((seen > before) and (lastGrid == here:info().gridId),
        "the armed action hook saw the order to :current(), destination and all",
        tostring(seen - before) .. " click(s), grid " .. tostring(lastGrid))

  -- 3. The refusal that changed subject with the address: a place is out of reach FOR A CHARACTER.
  -- Grid 1 is not a grid any server published, so no session can locate it.
  local nowhere = s:world():position({gridId = "1", x = 0, y = 0})
  refuses("a Position " .. user .. " cannot locate is refused naming that character",
          function() s:player():move(nowhere) end, user .. " cannot reach")

  -- 4. And the one it always had: a place in the world is not a point on the screen.
  refuses("a plain {x, y} table is refused naming the Position type",
          function() s:player():move({x = 100, y = 100}) end, "must be a Position")

  -- 5. A key this addon did NOT declare is still refused, so the grant is per verb and not a tier.
  refuses("world.place, which this addon never declared, still refuses",
          function() s:world():place() end, "world.place")

  -- 6. The reach itself. Ordered to its OWN position, so it holds however far apart the two are.
  local other
  for _, o in ipairs(hafen.session():list()) do
    if (o ~= s) and o:player():gob() then other = o end
  end
  if other == nil then
    manualCheck("run `:session add <a second account>`, get it into the world, and re-run :t076-5",
                "three more lines, and the last of them a walk you can watch")
  else
    local there = other:player():gob():position()
    before = seen
    why = order(other, there)
    check(why == "", "the order reached " .. other:user() .. ", which is not the session on screen",
          why)
    -- 7. ...and it went down that session's own socket, raw: the hook chain belongs to the anchor.
    check(seen == before, "the action hook did NOT see the background order (rawWdgmsg)",
          tostring(seen - before) .. " click(s) it should not have seen")
    -- 8. The one thing a program cannot watch: whether the character actually walked.
    why = order(other, here)
    manualCheck("tab to " .. other:user() .. " (`:session anchor " .. other:user() .. "`)",
                (why == "") and ("it walked to where " .. user .. " is standing")
                             or ("nothing moved -- the two are too far apart to share a place: " .. why))
  end

  sub:off()
  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t076-5", run)   -- the only way in: a suite does not start itself
