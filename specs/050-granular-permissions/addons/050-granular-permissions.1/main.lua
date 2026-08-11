-- 050.1 — the catalogue, the matcher, the gate. Self-checking suite; see specs/testing/addon-suite.md.
--
-- This suite DECLARES a deliberate subset in its manifest: the GROUP "item.*" and the EXACT key
-- "player.move". Enabling it in Options > AddOns and approving its consent dialog IS the grant every check
-- below exercises -- there is nothing else to arrange, and nothing here reaches the server:
--
--   * a DECLARED verb is proven by getting past the gate to its own ARGUMENT refusal. The gate is the first
--     statement of every protected verb (D-213), so an argument error can only be read by a caller the gate
--     let through -- which is how a suite proves a grant without acting on the world.
--   * an UNDECLARED verb is proven by its permission refusal NAMING ITS OWN KEY. That is what catches the
--     one bug the catalogue exists to make impossible: a call site wired to the wrong constant.
--   * the two shapes are checked against each other. "item.*" is a group and must reach every item.<verb>
--     and nothing outside the prefix; "player.move" is an exact key and must NOT reach the nested
--     "player.hand.use", which shares its first segment.
--
-- Three verbs need a live object this suite may not create (a held item, an open recipe, an action menu). Each
-- is asserted when it is reachable and reported as a [manual] re-run when it is not.

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

-- The error text of a call that MUST fail, with the chunk prefix stripped; nil if it did not fail at all.
local function msg(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local texts = {}                    -- every refusal this run read, for the retired-name scan at the end
local function record(s)
  if s then texts[#texts + 1] = s end
  return s
end

local function run()
  -- ---- what this run can reach ------------------------------------------------------------------
  local invw = hafen.ui():inventory()
  local it = invw and invw:items()[1] or nil
  local hand = hafen.player():hand()
  local craft = hafen.craft():current()
  local pag = hafen.menugrid():list()[1]
  local root = hafen.ui():root()

  -- ---- the 17 keys this suite did NOT declare: each refuses, naming its OWN key ------------------
  -- Every one of them is called with arguments that would be perfectly good, so nothing but the gate can
  -- be what refuses. Nothing is sent: the refusal happens before the message is built.
  local undeclared = {
    { "player.hand.use",    hand ~= nil,  function() hand:use(hafen.world():position(0, 0)) end },
    { "gob.click",          true,         function() hafen.world():gob():get(-1):click(3) end },
    { "world.place",        true,         function() hafen.world():place(hafen.world():position(0, 0), 0) end },
    { "world.select",       true,         function() hafen.world():select(hafen.world():position(0, 0),
                                                                         hafen.world():position(1, 1)) end },
    { "menugrid.use",       pag ~= nil,   function() pag:use() end },
    { "flowermenu.select",  true,         function() hafen.flowermenu():select(1) end },
    { "flowermenu.cancel",  true,         function() hafen.flowermenu():cancel() end },
    { "craft.make",         craft ~= nil, function() craft:make() end },
    { "actionbar.use",      true,         function() hafen.actionbar():get(0):use() end },
    { "actionbar.res",      true,         function() hafen.actionbar():get(0):res("gfx/hud/act/mine") end },
    { "kin.add",            true,         function() hafen.kin():add("secret") end },
    { "kin.rename",         true,         function() hafen.kin():get(-1):rename("x") end },
    { "kin.group",          true,         function() hafen.kin():get(-1):group(1) end },
    { "kin.endKin",         true,         function() hafen.kin():get(-1):endKin() end },
    { "kin.forget",         true,         function() hafen.kin():get(-1):forget() end },
    { "speed.current",      true,         function() hafen.speed():current(1) end },
    { "widget.send",        root ~= nil,  function() root:send("click") end },
  }
  local asserted, wrong, unreachable, guiding = 0, {}, {}, 0
  local handRefused = false
  for _, row in ipairs(undeclared) do
    local key, reachable, fn = row[1], row[2], row[3]
    if not reachable then
      unreachable[#unreachable + 1] = key
    else
      asserted = asserted + 1
      local err = record(msg(fn))
      if (err == nil) or not err:find('did not declare the "' .. key .. '"', 1, true) then
        wrong[#wrong + 1] = key .. "=" .. (err and err:sub(1, 60) or "<no error>")
      elseif key == "player.hand.use" then
        handRefused = true
      end
      -- ...and the refusal has to be usable: it names the manifest line to add, and the group that also
      -- grants it, so an author never has to guess the spelling of either.
      local group = key:match("^(.*)%.[^.]+$")
      if err and err:find('"permissions": ["' .. key .. '"]', 1, true)
             and err:find('"' .. group .. '.*"', 1, true) then
        guiding = guiding + 1
      end
    end
  end
  check(#wrong == 0, ("every undeclared protected verb refuses, naming its own key (%d/%d)")
        :format(asserted - #wrong, asserted), table.concat(wrong, "; "))
  check((asserted > 0) and (guiding == asserted),
        ("...and each refusal names the manifest line to add and the group that also grants it (%d/%d)")
        :format(guiding, asserted), guiding .. " of " .. asserted)

  -- ---- the DECLARED half: past the gate, into the verb's own argument refusal --------------------
  -- Reaching an argument error IS the grant (D-213: the gate is the first statement), and every call here
  -- is deliberately malformed, so the grant is proven with nothing sent to the server.
  local granted, missed = 0, {}
  local function grantedBy(what, wantMsg, fn)
    local err = record(msg(fn))
    if (err ~= nil) and err:find(wantMsg, 1, true) and not err:find("did not declare", 1, true) then
      granted = granted + 1
    else
      missed[#missed + 1] = what .. "=" .. (err and err:sub(1, 60) or "<no error>")
    end
  end
  grantedBy("player.move", "p must be a Position",
            function() hafen.player():move({ x = 1, y = 1 }) end)
  if it ~= nil then
    grantedBy("item.use", "mods must be a number", function() it:use("x") end)
    grantedBy("item.take", "takes no arguments", function() it:take(1) end)
    grantedBy("item.drop", "n must be a number", function() it:drop("x") end)
    grantedBy("item.transfer", "n must be a number", function() it:transfer("x") end)
  end
  local wantGrants = (it ~= nil) and 5 or 1
  check((#missed == 0) and (granted == wantGrants),
        ("every declared verb reachable this run is past the gate, at its own ARGUMENT refusal (%d/%d)")
        :format(granted, wantGrants), table.concat(missed, "; "))

  -- ---- the two shapes, checked against each other -----------------------------------------------
  -- The group reached all four item.<verb> keys and NONE of the seventeen it does not cover; the exact key
  -- reached player.move and stopped there. That second half is the sharp one: "player.move" shares its first
  -- segment with "player.hand.use", so a matcher that compared prefixes instead of keys would leak into it.
  if it ~= nil then
    check((granted == 5) and (#wrong == 0),
          'the group "item.*" grants every item.<verb> and nothing outside the prefix',
          granted .. " granted, " .. #wrong .. " mis-keyed")
  end
  if hand ~= nil then
    check(handRefused, 'the exact key "player.move" does not grant the nested "player.hand.use"',
          "hafen.player():hand():use did not refuse naming its own key")
  end

  -- ---- the retired tier's name survives in no refusal --------------------------------------------
  -- Every gate message this run read, plus the three Retired rows that used to name the tier (the two on
  -- hafen.act():menu / :enabled are shadowed by the section row, which is read here instead).
  local retired = 0
  for _, fn in ipairs({ function() return hafen.act end,
                        function() return hafen.speed.set end,
                        function() return hafen.craft.make end }) do
    if record(msg(fn)) then retired = retired + 1 end
  end
  local named = {}
  for _, s in ipairs(texts) do
    if s:find("actions", 1, true) then named[#named + 1] = s:sub(1, 50) end
  end
  check((#named == 0) and (retired == 3) and (#texts >= asserted + 3),
        ("no refusal names the retired tier (%d messages read, 3 of them retired spellings)"):format(#texts),
        (#named > 0) and table.concat(named, " | ")
                      or (retired .. " retired rows, " .. #texts .. " messages"))

  -- ---- what a program cannot do ------------------------------------------------------------------
  if #unreachable > 0 then
    manualCheck("put the missing thing in reach and re-run :t050-1 -- not asserted this run: "
                .. table.concat(unreachable, ", ")
                .. " (player.hand.use needs an item on your cursor, craft.make an open recipe,"
                .. " menugrid.use the action menu, widget.send a live UI root)",
                "the undeclared line reads 17/17")
  end
  if it == nil then
    manualCheck("put any item in your inventory and re-run :t050-1", "the declared line reads 5/5")
  end
  manualCheck("confirm how this suite came to be running: you ticked it in Options > AddOns and a consent"
              .. " dialog appeared BEFORE it was enabled",
              "the dialog appeared, you approved it, and that approval is the grant the checks above exercised")
  manualCheck("add \"gob.click\" to addons/050-granular-permissions.1/manifest.json, :reload, and open"
              .. " Options > AddOns; then misspell it as \"gob.clik\", :reload and HOVER the row; then put"
              .. " both back and :reload",
              "after the added key the suite is UNTICKED again (a wider declaration re-asks); after the"
              .. " misspelling the row reads 'manifest error (hover)' with its checkbox UNTICKED and dead,"
              .. " and the tooltip names the bad entry and lists the valid keys; after putting both back it"
              .. " is tickable again")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t050-1", run)   -- the only way in: a suite does not start itself
