-- 039.14 -- the Item entity: widget:items(), hafen.ui():hand() and EquipChanged hand back objects.
-- Self-checking suite; see specs/addons/TESTING.md. Run  :t039-14
--
-- The headline is the IDENTITY question, and it is the reason this type exists at all: an item has no
-- content id, only a server widget id, and the server RE-USES that number. An entity keyed on the number
-- would therefore not go stale -- it would quietly start naming a different item, and a gated write
-- through it would move the wrong thing. So an Item is keyed on the item itself, and the parked `moved`
-- round below is where that is measured rather than described: an item you are holding across a move
-- never turns into another item.
--
-- It stands alone (D-085): every premise it rests on is asserted here, including the ones other suites
-- also make. It declares no permissions and writes nothing; the one gated verb is tested by its REFUSAL.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    local shown = "?"
    pcall(function() shown = tostring(got) end)
    hafen.log():write("[fail] " .. what .. " -- got: " .. shown)
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
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Does a snapshot carry every REQUIRED key, and no key outside required+optional?
local function shape(t, required, optional)
  if type(t) ~= "table" then return false, "not a table: " .. type(t) end
  local allowed = {}
  for _, k in ipairs(required) do allowed[k] = true end
  for _, k in ipairs(optional or {}) do allowed[k] = true end
  for k in pairs(t) do
    if not allowed[k] then return false, "unexpected field " .. tostring(k) end
  end
  for _, k in ipairs(required) do
    if t[k] == nil then return false, "missing " .. k end
  end
  return true, "ok"
end

-- What was stashed by the last main run, for the parked `moved` round. A plain table, so the Item
-- object inside it is the very one the round stashed -- which is the whole point of the round.
local stash = nil

-- The EquipChanged latch: the payload became Item objects. Passive -- a subscription is not a start.
local equipEvent, equipFires = nil, 0
hafen.event():on("EquipChanged", function(list)
  equipFires = equipFires + 1
  if (type(list) == "table") and (#list > 0) then equipEvent = equipEvent or list end
end)

-- The container-lifecycle latch, armed by the main run and disarmed when it reports.
local added = nil

-- ---- the parked round: the item MOVED ------------------------------------------------------------
local function movedRound()
  pass, fail, manual = 0, 0, 0
  if not stash then
    check(false, "the parked round re-reads the item the last run stashed",
          "nothing stashed -- run :t039-14 first, then move the item, then :t039-14 moved")
  else
    local it, was = stash.item, stash.res
    local hand = hafen.ui():hand()
    local gone = not it:exists()
    -- The invariant, whichever way the client moved it: the object never became a DIFFERENT item.
    check((it:res() == was) and (gone or (it:cell() == nil) or (it:cell().x ~= stash.x)
                                 or (it:cell().y ~= stash.y)),
          ("the item held across the move is still '%s' and never became another item -- it is now %s")
          :format(tostring(was), gone and "gone (:exists() false)"
                  or ((hand == it) and "the item on your cursor, the SAME object"
                      or "still in the container, at another cell")),
          ("res=%s exists=%s"):format(tostring(it:res()), tostring(it:exists())))
    if gone then
      check((it:handle() == nil) and (it:cell() == nil) and (#it:slots() == 0),
            "a departed item has no place and no handle: the server id is no longer its, so nothing"
            .. " here can address whatever was given that number next",
            ("handle=%s"):format(tostring(it:handle())))
    else
      check((it:handle() ~= nil) and (hafen.ui():node(it:handle()) ~= nil),
            ("the moved item is the same live object, still addressed by widget id %d")
            :format(it:handle()), tostring(it:handle()))
    end
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---- the main run ---------------------------------------------------------------------------------
local function run(args)
  if args and (args[1] == "moved") then return movedRound() end
  pass, fail, manual = 0, 0, 0   -- per RUN, not per session: this suite asks to be run again

  local invw, eqw = hafen.ui():inventory(), hafen.ui():equipment()
  local inv = invw and invw:items() or {}
  local eq = eqw and eqw:items() or {}

  -- 1. A container hands back OBJECTS, and two reads of one item are the same object.
  local it = inv[1]
  if it then
    check((type(it) == "userdata") and (invw:items()[1] == it) and it:exists(),
          ("the backpack holds %d item(s) as objects; two reads of the first are the same object")
          :format(#inv), type(it))
  else
    check(false, "a container hands back interned Item objects",
          "your backpack is empty -- put something in it and run :t039-14 again")
  end

  -- 2. :info() is the snapshot escape hatch and loses nothing the verbs answer.
  if it then
    local ok, why = shape(it:info(), { "handle" },
                          { "res", "name", "num", "wear", "quality", "cell", "slots" })
    local i = it:info()
    check(ok and (i.res == it:res()) and (i.name == it:name()) and (i.num == it:num())
          and (i.quality == it:quality()) and (i.handle == it:handle()),
          ("item:info() is the same reading as the verbs: %s q=%s x%s")
          :format(tostring(it:name() or it:res()), tostring(it:quality() or "-"),
                  tostring(it:num() or 1)), why)
  end

  -- 3. THE KEY CLAIM's premise: :handle() really is a server widget id, which is what makes re-use a
  --    hazard rather than a story. The id resolves to a live widget through the OTHER door.
  if it then
    local h = it:handle()
    check((type(h) == "number") and (hafen.ui():node(h) ~= nil) and hafen.ui():node(h):exists(),
          ("item:handle() (%s) is a live server widget id -- the number the server re-uses, and the"
           .. " reason nothing here is keyed on it"):format(tostring(h)), tostring(h))
  end

  -- 4. The two PLACE reads say which you meant, and the old single field throws naming both.
  if it then
    local c = it:cell()
    check((type(c) == "table") and (type(c.x) == "number") and (#it:slots() == 0),
          ("an item in the backpack reads a grid cell (%s,%s) and no equipment slot")
          :format(tostring(c and c.x), tostring(c and c.y)), tostring(c))
    refuses("the old single place field throws naming :cell() and :slots()",
            function() return it.pos end, "item:cell()")
  end

  -- 5. The gate: this suite declares nothing, so the refusal names the PERMISSION. Nothing is moved.
  if it then
    refuses("the gated hafen.act():item refuses an addon that declared no permission",
            function() return hafen.act():item(it, "take") end, "did not declare")
  end

  -- 6. Worn gear: each item ONCE, however many slots it fills, and the slots are read off the item.
  if #eq > 0 then
    -- The sum RECONCILES rather than merely counting: `filled >= #eq` is satisfied by an under-report,
    -- which is how a worn item in a slot the window does not name once read as not worn at all.
    local seen, dup, filled, multi, bare = {}, 0, 0, nil, nil
    for _, w in ipairs(eq) do
      if seen[w] then dup = dup + 1 end
      seen[w] = true
      local s = w:slots()
      filled = filled + #s
      if #s > 1 then multi = w end
      if #s == 0 then bare = bare or w end
    end
    check((dup == 0) and (bare == nil) and (filled >= #eq) and (eq[1]:cell() == nil),
          ("%d worn item(s) fill %d slot(s), each item listed once and every one naming its place%s")
          :format(#eq, filled, multi and (" (" .. tostring(multi:name() or multi:res())
                                          .. " fills " .. #multi:slots() .. ")") or ""),
          bare and ("'" .. tostring(bare:name() or bare:res()) .. "' is worn and names no slot")
          or ("%d duplicate object(s) in the list"):format(dup))
  else
    check(false, "worn gear lists each item once, whatever number of slots it fills",
          "you are wearing nothing the equipment window shows")
  end

  -- 7. EquipChanged carries the SAME objects the equipment container lists.
  if equipEvent then
    local m, sameAsList = equipEvent[1], false
    for _, w in ipairs(eq) do if w == m then sameAsList = true end end
    check((type(m) == "userdata") and (m:res() ~= nil) and sameAsList,
          ("EquipChanged carried %d Item object(s), the same objects the equipment container lists")
          :format(#equipEvent), type(m))
  else
    check(equipFires > 0,
          ("EquipChanged fired %d time(s) this session with nothing worn, so there is no member to"
           .. " assert here"):format(equipFires),
          "EquipChanged has not fired at all -- are you in the world?")
  end

  -- 8. The cursor item is an Item like any other -- with no container, so no cell and no slots.
  local hand = hafen.ui():hand()
  if hand then
    check((type(hand) == "userdata") and (hand:cell() == nil) and (#hand:slots() == 0)
          and (hand:handle() ~= nil),
          ("the cursor item ('%s') is an Item object with no container: no cell, no slot")
          :format(tostring(hand:name() or hand:res())), type(hand))
  else
    check(true, "nothing is on the cursor, so hafen.ui():hand() is plain nil rather than an empty item")
  end

  -- 9. The container lifecycle hands OBJECTS too: arm it now, report it after a poll has run.
  added = nil
  if invw then
    invw:onItemAdded(function(x) added = added or x end)
  end

  -- Stash the first item for the parked round, and ask for the one thing a program cannot do.
  if it then
    local c = it:cell()
    stash = { item = it, res = it:res(), x = c and c.x, y = c and c.y }
    manualCheck(("pick up '%s' from your backpack with the mouse (or drag it to another cell), then run"
                 .. "  :t039-14 moved"):format(tostring(it:name() or it:res() or "?")),
                "2 more [pass]: the item held across the move is still that same item, and never became"
                .. " the one that took its place")
  end

  hafen.timer():after(0.8, function()
    if invw then
      check((added ~= nil) and (type(added) == "userdata") and (added:res() ~= nil),
            "a container's :onItemAdded hands the Item object, not a copy of it",
            (added == nil) and "no add fired within 0.8s -- is the backpack empty?" or type(added))
      invw:onItemAdded(nil)                      -- unsubscribe: a suite leaves nothing running
    end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t039-14", run)   -- the only way in: a suite does not start itself
