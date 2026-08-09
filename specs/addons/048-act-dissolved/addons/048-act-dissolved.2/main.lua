-- 048.2 — the Hand: hafen.player():hand(), :item(), :use(target, mods). Self-checking suite; see
-- specs/addons/TESTING.md.
--
-- The one task of 048 that MINTS an object. The cursor stops being a lookup on hafen.ui() and becomes a thing
-- on the character, because it carries a verb no Item can hold: apply what you are holding. Its nil is the
-- capability -- the two verbs it replaces fired the held-item gesture with an EMPTY cursor, a message the
-- client itself cannot produce.
--
-- RUN IT TWICE, THEN ONCE MORE AS `:t048-2 sent`. Once with an empty cursor and once while carrying
-- something: the Hand only exists while you are holding, so its own claims have no receiver otherwise. The
-- first run tells you which item to take. The suite never takes anything itself -- it declares no
-- permissions, and a protected verb is proven here by its REFUSAL. The firing half is the `[manual]` :lua
-- lines, whose console owner declares every permission.
--
-- What is NOT left to the eye is what those lines put on the wire. `use` has three target types and three
-- different messages, and the Gob one is the whole new capability of this task -- so the suite RECORDS every
-- outbound "itemact" as it goes out (041.2's action stream, an observe surface: it only watches, and never
-- preventDefaults), and `:t048-2 sent` asserts all three shapes. That is the 043.2 rule: for anything a
-- gesture makes happen, the only durable evidence is what was recorded while it happened.

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

-- What the inventory showed on the empty-cursor run, so the held run can say the item on the cursor is the
-- one that was taken. It is compared by RESOURCE and not by ==: the server does not move the item widget onto
-- the cursor, it destroys that one and sends a fresh child of the HUD (GameUI addchild, place "hand"), so an
-- identity claim across a take would be a claim about the engine that is simply false.
local taken = nil

-- Every outbound "itemact", keyed by HOW MANY arguments it carried -- which is exactly what distinguishes the
-- three targets, and is why no flag has to be smuggled alongside: {mods} is the item's own message (1),
-- {pc, mc, mods} is the map view aiming at bare ground (3), and the same extended with the object's
-- Gob.GobClick.clickargs tail is the map view aiming at a gob (8). Armed at load, not by a run: a send cannot
-- be observed after the fact, and this records rather than acts -- it prints nothing and writes nothing.
local sent = {}

hafen.event():action():on("itemact", function(ev)
  local a = ev:args()
  sent[#a] = a
end)

local function run()
  pass, fail, manual = 0, 0, 0

  -- The door is on the character, and the Player's vocabulary is CLOSED, so a list that forgot this task's
  -- verb would teach a stale one (D-125).
  eq("hafen.player():hand is a function", type(hafen.player().hand), "function")
  refuses("hafen.player():nosuchverb() lists a vocabulary that now includes :hand()",
          function() hafen.player():nosuchverb() end, ":hand()")

  -- The old doors are shut, each naming where it went -- under BOTH field reads (D-216): the colon call every
  -- shipped addon actually wrote, and the pre-039 dotted one.
  refuses("hafen.ui():hand() throws naming hafen.player():hand():item()",
          function() hafen.ui():hand() end, "is now hafen.player():hand():item()")
  refuses("the pre-039 dotted hafen.ui.hand throws naming it too",
          function() return hafen.ui.hand end, "is now hafen.player():hand():item()")
  refuses("hafen.act():useItemOn throws naming hafen.player():hand():use(p, mods)",
          function() hafen.act():useItemOn() end, "is now hafen.player():hand():use(p, mods)")
  refuses("the pre-039 dotted hafen.act.useItemOn throws naming it too",
          function() return hafen.act.useItemOn end, "is now hafen.player():hand():use(p, mods)")

  -- ...and the REST of hafen.act() still answers: the section EMPTIES over this feature (D-117), it does not
  -- disappear from under the verbs that have not moved yet.
  eq("the rest of hafen.act() is still callable: :enabled()", hafen.act():enabled(), false)

  local hand = hafen.player():hand()
  if hand == nil then
    -- The empty cursor. This is the whole reason the Hand is an object rather than the Item: `if hand then`
    -- is a guard neither hafen.ui():hand() nor hafen.act():useItemOn could give a caller.
    check(true, "hafen.player():hand() is nil with an empty cursor, and does not throw")
    local invw = hafen.ui():inventory()
    local first = invw and invw:items()[1] or nil
    taken = first
    check(first ~= nil, "an inventory item is there to take for the second run", first)
    manualCheck("left-click " .. (first and (first:name() or first:res() or "?") or "your first inventory item")
                .. " onto your cursor, then run :t048-2 again",
                "the second run prints the Hand's own checks; click an empty slot afterwards to put it back")
    manualCheck(":lua hafen.act():item(hafen.ui():inventory():items()[1], \"itemact\")",
                "an error naming hafen.player():hand():use(item, mods) -- the one item verb that left in 048.2"
                .. " (the console owner declares the permission, so it reaches the verb; this suite cannot)")
  else
    -- Carrying something: the Hand exists, and so does everything on it.
    check(true, "hafen.player():hand() is a Hand while you are carrying")
    eq("the Hand is a per-addon singleton, so == works", hafen.player():hand(), hand)
    local it = hand:item()
    check(it ~= nil, "hand:item() hands back the Item on the cursor", it)
    check((it ~= nil) and (it:res() ~= nil), "that Item is live: :res() answers on it", it and it:res())
    check((taken ~= nil) and (it ~= nil) and (it:res() == taken:res()),
          "it is the item the inventory showed before the take (same resource)",
          (taken == nil) and "no empty-cursor run recorded one -- run :t048-2 empty-handed first"
                          or ((it and it:res() or "nil") .. " vs " .. tostring(taken:res())))

    -- The verb is PROTECTED, and the refusal names the verb rather than a namespace. A perfectly good target
    -- (the ground under you) is used so nothing but the gate can be what refuses -- and it stays nil-safe,
    -- because the gate runs before the argument either way.
    local pg = hafen.player():gob()
    local here = pg and pg:position() or nil
    refuses("hand:use refuses this undeclared addon, naming the verb",
            function() hand:use(here) end,
            "hafen.player():hand():use: this addon did not declare")
    -- ...and the gate runs BEFORE the argument is looked at (D-213): a caller that may not act at all must
    -- learn THAT, not that it typed the wrong target. So both argument mistakes come back as the permission.
    refuses("hand:use() with no target still answers the permission error first",
            function() hand:use() end, "did not declare")
    refuses("hand:use(42) answers the permission error too, not a type one",
            function() hand:use(42) end, "did not declare")

    manualCheck(":lua hafen.player():hand():use()",
                "an error naming the three target types -- an Item, a Position and a Gob -- and NOT an"
                .. " activation of what you hold (that is hafen.player():hand():item():use())")
    -- The three FIRING lines. Each must come back `lua= \"Hand\"` (the verb chains); what each one actually
    -- put on the wire is not left to the eye -- run `:t048-2 sent` afterwards and the suite asserts it.
    manualCheck(":lua hafen.player():hand():use(hafen.ui():inventory():items()[1])",
                "lua= \"Hand\" -- what you hold is applied ONTO your first inventory item")
    manualCheck(":lua hafen.player():hand():use(hafen.player():gob():position())",
                "lua= \"Hand\" -- the same item applied to the ground under you, as act():useItemOn(p) did")
    manualCheck(":lua local g = hafen.world():gob():nearest(function(o) return not o:isPlayer() end);"
                .. " hafen.player():hand():use(g)",
                "lua= \"Hand\" -- what you hold is applied to the nearest OBJECT, the message no addon could"
                .. " send before 048.2. THEN RUN  :t048-2 sent  to have all three sends asserted")
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- `:t048-2 sent` — what the three `[manual]` firings ACTUALLY put on the wire. The suite cannot send (it
-- declares no permissions), but it can watch, so the one thing a human had to judge by eye is an assertion.
local function runSent()
  pass, fail, manual = 0, 0, 0
  local item, ground, gob = sent[1], sent[3], sent[8]

  check(item ~= nil, "hand:use(item) sent the ITEM's own \"itemact\" -- {mods}, exactly WItem.iteminteract",
        "not seen: fire the :lua item line from the held run first")
  check(ground ~= nil, "hand:use(position) sent the map view's \"itemact\" -- {pc, mc, mods}",
        "not seen: fire the :lua position line from the held run first")
  check(gob ~= nil, "hand:use(gob) sent the map view's \"itemact\" EXTENDED -- 8 arguments, not 3",
        "not seen: fire the :lua gob line from the held run first")

  -- The extension is the capability. It must be Gob.GobClick.clickargs verbatim -- {0, gobId, gobRc, 0, -1},
  -- no overlay and no sub-mesh -- and not an invented shape that happens to be eight long.
  if gob ~= nil then
    check((gob[4] == 0) and (gob[7] == 0) and (gob[8] == -1) and (type(gob[5]) == "number"),
          "...and the tail IS Gob.GobClick.clickargs: {0, gobId, gobRc, 0, -1}, the whole object",
          ("{%s, %s, .., %s, %s}"):format(tostring(gob[4]), tostring(gob[5]), tostring(gob[7]),
                                          tostring(gob[8])))
    -- The hit coordinate a real click would carry is where the click landed; a programmatic aim has only the
    -- object's own position, and must carry THAT in both slots rather than the mouse's meaningless one.
    local hit, rc = gob[2], gob[6]
    check((type(hit) == "table") and (type(rc) == "table") and (hit.x == rc.x) and (hit.y == rc.y),
          "...and it aimed at the object's OWN position: the hit coord is the gob coord",
          ("hit=%s,%s gob=%s,%s"):format(tostring(hit and hit.x), tostring(hit and hit.y),
                                         tostring(rc and rc.x), tostring(rc and rc.y)))
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-2", function(args)   -- the only way in: a suite does not start itself
  if args[1] == "sent" then runSent() else run() end
end)
