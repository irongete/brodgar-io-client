-- 048.8 — the docs tier. Self-checking suite; see specs/testing/addon-suite.md.
--
-- This task ships no engine change: it moves the user-facing reference onto the shape 048.1-048.7 gave the
-- API. The page that documented one section grouped by PERMISSION is deleted, the permission itself is
-- explained in one place every other page points at, and each verb is documented on the page of the thing it
-- changes. So what a suite can prove here is exactly the two ways a docs tier goes wrong:
--
--   (a) a page teaches a name that does not exist -- an invented verb, or one that moved and was not
--       re-pointed. Every name the pages now claim is read back here, off the very handle the page spells.
--   (b) a page still teaches a spelling the engine retired. Those are asserted to THROW, so a doc that
--       teaches one is caught by this suite rather than by a reader.
--
-- A page's other claim is the permission it prints on a group heading, and that is assertable too: this addon
-- declares nothing, so every verb the pages mark `protected: actions` must refuse it NAMING ITSELF, and every
-- read the same pages call unprotected must still answer in the same run. Those two lines together are what
-- makes a heading a fact rather than a decoration.

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

-- Every name in `names` must be a live function on its handle; returns the misses, spelled for a red line.
local function resolveAll(names)
  local absent = {}
  for _, row in ipairs(names) do
    if type(row[2]) ~= "function" then absent[#absent + 1] = row[1] .. "=" .. type(row[2]) end
  end
  return absent
end

-- Every entry is a protected verb the pages print `protected: actions` over: calling it from THIS addon must
-- fail with the permission error, and that error must name the verb the page named. A verb whose RECEIVER is
-- not there (no session) is reported as skipped rather than as a passing check -- a suite that silently
-- exercises nothing is the one failure mode a green line cannot show.
local function refusesAll(verbs)
  local wrong, skipped, ran = {}, {}, 0
  for _, row in ipairs(verbs) do
    if row[3] == false then
      skipped[#skipped + 1] = row[1]
    else
      ran = ran + 1
      local ok, err = pcall(row[2])
      err = ok and "<no error>" or tostring(err)
      if ok or (err:find(row[1] .. ": this addon did not declare", 1, true) == nil) then
        wrong[#wrong + 1] = row[1] .. "->" .. err:sub(1, 50)
      end
    end
  end
  return wrong, ran, skipped
end

local function run()
  pass, fail, manual = 0, 0, 0

  -- The receivers the pages spell. get(-1) interns rather than searches, so a Gob is a real receiver with no
  -- world behind it; the rest are the doors their own pages open with.
  local gob   = hafen.world():gob():get(-1)
  local item  = hafen.ui():inventory() and hafen.ui():inventory():items()[1]
  local pag   = hafen.menugrid():list()[1]
  local mapv  = hafen.ui():find("@MapView")
  local gameui = hafen.ui():find("@GameUI")
  local slot  = hafen.actionbar():get(0)
  local hand  = hafen.player():hand()

  -- ---- (a) every name the pages now teach RESOLVES LIVE ------------------------------------------------

  check(#resolveAll({
          { "hafen.player():move",  hafen.player().move },
          { "hafen.player():hand",  hafen.player().hand },
          { "gob:click",            gob and gob.click },
        }) == 0,
        "player.md and gob.md: :move(p), :hand() and gob:click(button, mods) resolve",
        table.concat(resolveAll({ { "move", hafen.player().move }, { "hand", hafen.player().hand },
                                  { "click", gob and gob.click } }), " | "))

  check(#resolveAll({
          { "hafen.world():place",     hafen.world().place },
          { "hafen.world():select",    hafen.world().select },
          { "hafen.world():snapPlace", hafen.world().snapPlace },
          { "hafen.world():snapAngle", hafen.world().snapAngle },
        }) == 0,
        "world.md: :place and :select resolve, beside the :snapPlace/:snapAngle the page sends you to",
        table.concat(resolveAll({ { "place", hafen.world().place }, { "select", hafen.world().select },
                                  { "snapPlace", hafen.world().snapPlace },
                                  { "snapAngle", hafen.world().snapAngle } }), " | "))

  if item then
    check(#resolveAll({
            { "item:use",      item.use },
            { "item:take",     item.take },
            { "item:drop",     item.drop },
            { "item:transfer", item.transfer },
          }) == 0,
          "ui/items.md: the four item verbs are on an Item out of a container's :items()",
          table.concat(resolveAll({ { "use", item.use }, { "take", item.take },
                                    { "drop", item.drop }, { "transfer", item.transfer } }), " | "))
  else
    check(false, "ui/items.md: the four item verbs are on an Item out of a container's :items()",
          "no item in hafen.ui():inventory() -- put anything in your backpack and re-run")
  end

  check((mapv ~= nil) and (gameui ~= nil) and (type(mapv.send) == "function")
        and (type(mapv:id()) == "number") and (type(gameui:id()) == "number"),
        "ui/widget.md: :send is on the Widget, and the two selectors the page names are BOUND",
        ("mapview=%s id=%s gameui=%s id=%s send=%s"):format(tostring(mapv),
          tostring(mapv and mapv:id()), tostring(gameui), tostring(gameui and gameui:id()),
          type(mapv and mapv.send)))

  -- The page says the ENTRY is the door and that no path-shaped one stands beside it. A collection's
  -- vocabulary is closed, so the absence of hafen.menugrid():use is a THROW rather than a nil -- assert the
  -- refusal the engine actually raises, not the nil a reader might expect.
  local noPathDoor = select(1, pcall(function() local x = hafen.menugrid().use; return x end)) == false
  check((pag ~= nil) and (type(pag.use) == "function")
        and (type(hafen.menugrid():count()) == "number") and noPathDoor,
        "menugrid.md: pag:use() is the door, and no hafen.menugrid():use stands beside it",
        ("pag=%s use=%s count=%s no-path-door=%s"):format(tostring(pag), type(pag and pag.use),
          tostring(hafen.menugrid():count()), tostring(noPathDoor)))

  -- ---- (b) the permission a page prints on a heading is a FACT ------------------------------------------
  -- Every verb the reference marks `protected: actions` refuses this addon, which declares nothing, and names
  -- itself while doing it. Each is called with NO arguments on purpose: the gate runs before the argument
  -- check, so the permission error is what must come back rather than a complaint about a missing Position.

  local PROTECTED = {
    { "hafen.player():move",       function() hafen.player():move() end },
    { "gob:click",                 function() gob:click() end,                       gob ~= nil },
    { "hafen.world():place",       function() hafen.world():place() end },
    { "hafen.world():select",      function() hafen.world():select() end },
    { "pag:use",                   function() pag:use() end,                         pag ~= nil },
    { "hafen.flowermenu():select", function() hafen.flowermenu():select() end },
    { "hafen.flowermenu():cancel", function() hafen.flowermenu():cancel() end },
    { "widget:send",               function() mapv:send() end,                       mapv ~= nil },
    { "hafen.speed():current",     function() hafen.speed():current(1) end },
    { "slot:use",                  function() slot:use() end,                        slot ~= nil },
    { "slot:res",                  function() slot:res("gfx/hud/act/mine") end,      slot ~= nil },
    { "hafen.kin():add",           function() hafen.kin():add("x") end },
    { "item:use",                  function() item:use() end,                        item ~= nil },
    { "item:take",                 function() item:take() end,                       item ~= nil },
    { "item:drop",                 function() item:drop() end,                       item ~= nil },
    { "item:transfer",             function() item:transfer() end,                   item ~= nil },
  }
  local wrong, ran, skipped = refusesAll(PROTECTED)
  check((#wrong == 0) and (#skipped == 0),
        ("every verb the pages mark protected refuses this undeclared addon, naming itself (%d of %d)")
          :format(ran, #PROTECTED),
        table.concat(wrong, " | ") .. (#skipped > 0 and (" | no receiver: " .. table.concat(skipped, ", ")) or ""))

  -- ...and the reads the same pages call unprotected answer for that same addon in that same run, which is
  -- the half that makes the annotation informative rather than decorative. Each is named on its own so one
  -- red line says WHICH read stopped answering.
  local READS = {
    { "hafen.menugrid():list",     function() return type(hafen.menugrid():list()) == "table" end },
    { "hafen.world():gob():count", function() return type(hafen.world():gob():count()) == "number" end },
    { "hafen.map():marker():count", function() return type(hafen.map():marker():count()) == "number" end },
    { "hafen.ui():root():children", function() return type(hafen.ui():root():children()) == "table" end },
    { "gob:scale(1) (client-local write)", function() return gob:scale(1) == gob end },
    { "hafen.speed():current",     function() local v = hafen.speed():current()
                                              return (v == nil) or (type(v) == "number") end },
  }
  local mute = {}
  for _, r in ipairs(READS) do
    local ok, v = pcall(r[2])
    if not (ok and (v == true)) then
      mute[#mute + 1] = r[1] .. "->" .. (ok and tostring(v) or tostring(v):sub(1, 40))
    end
  end
  check(#mute == 0,
        ("the reads and client-local writes the same pages call unprotected still answer (%d)"):format(#READS),
        table.concat(mute, " | "))

  -- ---- (c) no page can teach a retired spelling, because reading one THROWS ------------------------------

  refuses("hafen.act is gone as a SECTION, and the message names where the verbs went",
          function() local x = hafen.act; return x end,
          "hafen.act() is gone: every verb moved to what it changes")
  refuses("hafen.ui():hand is retired, naming hafen.player():hand():item()",
          function() local x = hafen.ui().hand; return x end,
          "hafen.ui():hand() is now hafen.player():hand():item()")

  -- The cursor's documented contract, in whichever state the maintainer is in: nil while empty, and a Hand
  -- carrying an Item while not. Both are the page's claim, so either state passes and neither throws.
  check((hand == nil) or ((type(hand.item) == "function") and (type(hand.use) == "function")
                          and (hand:item() ~= nil)),
        "hafen.player():hand() is nil with an empty cursor, or a Hand carrying an Item",
        tostring(hand) .. " item=" .. tostring(hand and hand:item()))

  manualCheck("take any item onto the cursor, then :lua hafen.log():write(tostring(hafen.player():hand():item():res()))",
              "the item's resource name, not nil -- hand:item() is what ui/items.md points at")
  -- The console declares every permission, so this is the verb RESOLVING AND FIRING rather than the gate:
  -- the one entry on the guide's protected list whose receiver needs a live window before it exists at all.
  manualCheck("open any recipe window, then :lua hafen.craft():current():make()",
              "the Craft echoed back as lua= \"Craft(<recipe>)\" -- it presses the button, and with the "
              .. "ingredients missing the server refuses it with nothing coming back, as craft.md says")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-8", run)   -- the only way in: a suite does not start itself
