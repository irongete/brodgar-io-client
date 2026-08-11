-- 048.3 — the Item verbs: item:use(mods), :take(), :drop(n), :transfer(n). Self-checking suite; see
-- specs/testing/addon-suite.md.
--
-- The fifth-argument verb string dies here. hafen.act():item(item, "take", n) was a switch statement standing
-- where four verb names go, and it was the last of that section's item half -- what you can do TO an item is
-- on the item now. The suite declares no permissions, so each verb is proven by its REFUSAL naming the verb;
-- the firing half is the [manual] :lua lines, whose console owner declares every permission.
--
-- The task also rewires how an Item's metatable is BUILT (LuaItem.Cache finally keeps the owner it was already
-- handed, so the gate has a caller to check), so this suite re-asserts the ordinary Item reads and the
-- interning underneath them: they are this task's premise, and a premise is stated where it can fail.
--
-- RUN IT, THEN MOVE THE ITEM IT NAMES AND RUN `:t048-3 stale`. A stale Item is the one claim that cannot be
-- made in a single pass: the handle has to be held ACROSS a move to show that it still reads what it was, that
-- :exists() went false, and that its verbs send nothing rather than landing on whatever inherited the item's
-- recycled server widget id.

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

-- The Item a run stashed, so the `stale` run can ask it what it is after the maintainer has moved it. Held
-- deliberately: the whole point of the entity is that a handle outlives the item's place in the tree.
local stashed = nil

local function run()
  pass, fail, manual = 0, 0, 0

  -- The old door is shut, under BOTH field reads (D-216): the colon call every shipped addon actually wrote,
  -- and the pre-039 dotted one. Its message has to carry all FIVE replacements, because one overloaded call
  -- became five separate names and a porting reader gets exactly one message to work from.
  local wants = { "item:use(mods)", "item:take()", "item:drop(n)", "item:transfer(n)",
                  "hafen.player():hand():use(item)" }
  local ok, err = pcall(function() hafen.act():item() end)
  err = ok and "<no error>" or tostring(err)
  local missing = {}
  for _, s in ipairs(wants) do
    if not err:find(s, 1, true) then missing[#missing + 1] = s end
  end
  check((not ok) and (#missing == 0), "hafen.act():item throws naming all FIVE replacements",
        ok and "<no error>" or ("missing: " .. table.concat(missing, ", ")))
  refuses("the pre-039 dotted hafen.act.item throws naming them too",
          function() return hafen.act.item end, "the verbs are on the item")

  -- ...and the REST of hafen.act() still answers: the section EMPTIES over this feature (D-117), it does not
  -- disappear from under the verbs that have not moved yet.
  eq("the rest of hafen.act() is still callable: :enabled()", hafen.act():enabled(), false)

  local invw = hafen.ui():inventory()
  local it = invw and invw:items()[1] or nil
  check(it ~= nil, "an inventory item is there to address", "your inventory is empty -- put something in it")
  if it == nil then
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  -- The four verbs are on the Item, and they are functions rather than a name that happens to read nil.
  local kinds = {}
  for _, v in ipairs({ "use", "take", "drop", "transfer" }) do
    kinds[#kinds + 1] = v .. "=" .. type(it[v])
  end
  check((type(it.use) == "function") and (type(it.take) == "function") and (type(it.drop) == "function")
        and (type(it.transfer) == "function"),
        "all four verbs are functions on an Item: :use :take :drop :transfer", table.concat(kinds, " "))

  -- Each is PROTECTED, and each refusal names ITS OWN verb -- not a section, and not one shared string, so a
  -- caller reads the exact spelling it has to declare for.
  refuses("item:use refuses this undeclared addon, naming the verb",
          function() it:use() end, "item:use: this addon did not declare")
  refuses("item:take refuses this undeclared addon, naming the verb",
          function() it:take() end, "item:take: this addon did not declare")
  refuses("item:drop refuses this undeclared addon, naming the verb",
          function() it:drop() end, "item:drop: this addon did not declare")
  refuses("item:transfer refuses this undeclared addon, naming the verb",
          function() it:transfer() end, "item:transfer: this addon did not declare")
  -- ...and the gate runs BEFORE the argument is looked at (D-213): an addon that may not act at all must
  -- learn THAT, not that it typed an argument the verb does not take.
  refuses("item:take(1) answers the permission error first, not the argument one",
          function() it:take(1) end, "item:take: this addon did not declare")

  -- The premise this task rewired: the Item metatable is built per OWNER now, so the ordinary reads and the
  -- interning that hands back one object per item are asserted here rather than assumed.
  check(it:res() ~= nil, "the Item's reads still answer through the per-owner metatable: :res()", it:res())
  eq("...and :exists() is true for a live one", it:exists(), true)
  eq("...and the interning still holds: reading the container again hands back the SAME object",
     invw:items()[1], it)
  refuses("...and a retired Item row still throws: item:pos", function() return it.pos end, "item:cell()")

  stashed = it
  local name = it:name() or it:res() or "that item"
  manualCheck("stash it in the console too -- `:lua stale = hafen.ui():inventory():items()[1]` -- then MOVE "
              .. name .. " (drag it to another slot), run  :t048-3 stale , then  :lua stale:drop()",
              "the stale run is all [pass], and stale:drop() errors saying THIS ITEM IS GONE / nothing was"
              .. " sent -- NOT a permission error, since the console declares every permission")
  manualCheck(":lua hafen.ui():inventory():items()[1]:take()  then  :lua hafen.player():hand():item():drop()",
              "the item lifts onto your cursor, then drops at your feet -- and each line answers"
              .. " lua= \"Item(<res>)\", because the verbs chain")
  manualCheck(":lua hafen.ui():inventory():items()[1]:use()",
              "its right-click action fires (open, eat, light, ...) -- the \"iact\" gesture, which is the one"
              .. " of the four that also takes mods")
  manualCheck(":lua hafen.ui():inventory():items()[1]:transfer()",
              "with a container open, the item moves into it (with none open the server ignores it)")
  manualCheck(":lua hafen.ui():inventory():items()[1]:take(1)",
              "an error saying take() takes no arguments and naming item:drop(n) / item:transfer(n) -- the"
              .. " argument refusal this suite can only ever see as the permission one")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- `:t048-3 stale` — the handle held ACROSS a move. It is the same Item object the first run addressed, and
-- what it proves is that the entity goes QUIET rather than repointing: an item that has left is not the item
-- that took its place, so nothing is sent through it ever again.
local function runStale()
  pass, fail, manual = 0, 0, 0
  if stashed == nil then
    check(false, "an Item was stashed by an earlier run", "none -- run :t048-3 first, then move the item")
  else
    eq("the moved Item reports :exists() == false", stashed:exists(), false)
    check(stashed:res() ~= nil, "...and it still READS what it was: :res() answers on a stale handle",
          ("res=%s name=%s"):format(tostring(stashed:res()), tostring(stashed:name())))
    eq("...but not WHERE it is: :cell() is nil, because the id is exactly what is no longer its",
       stashed:cell(), nil)
    refuses("...and its verbs still refuse this addon at the GATE, before staleness is even reached",
            function() stashed:drop() end, "item:drop: this addon did not declare")
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-3", function(args)   -- the only way in: a suite does not start itself
  if args[1] == "stale" then runStale() else run() end
end)
