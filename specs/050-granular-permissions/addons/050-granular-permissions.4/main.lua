-- 050.4 — the docs teach the catalogue. Self-checking suite; see specs/testing/addon-suite.md.
--
-- This suite DECLARES NOTHING, which is the shape its whole claim needs: every check below is a refusal,
-- so there is nothing to enable, nothing to consent to and nothing sent to the server.
--
-- The claim is the reference's, not the engine's. Task 050.4 put the permission KEYS into the pages: the
-- guide grew a catalogue table of all 22, and each protected page states again, beside its own verb, the key
-- that verb needs. Two places now spell one table, which is exactly the arrangement that drifts. So each row
-- below carries BOTH transcriptions -- the key as the guide's catalogue prints it, and the key as the verb's
-- own page prints it -- and every one of them is driven through the gate:
--
--   * the two transcriptions must AGREE. A key stated one way in the catalogue and another beside the verb
--     reddens a line naming the page, with no engine involved.
--   * the key must be REAL: the verb refuses an addon that declared nothing, and the refusal names that
--     exact key back. A key the docs invented, or spelled the old way, cannot survive this.
--   * the refusal also names the group that would grant the key, so the guide's GROUP table is checked
--     against the engine's own grouping -- including `player.hand.*`, the one nested row.
--
-- Every call is given arguments that would be perfectly good, so nothing but the gate can be what refuses;
-- the gate is the first statement of every protected verb (D-213), so nothing is built and nothing is sent.
--
-- THE SUITE WAITS INSTEAD OF ASKING (TESTING.md). Some receivers are server-driven and cannot be conjured --
-- the cursor's held item, the open recipe, a container's first item. Rather than make the maintainer arrange
-- them BEFORE typing the command, every row whose receiver is not there yet is retried once a second for
-- WATCH_SECONDS. Run the command, then produce what is missing at your own pace; the block prints itself the
-- moment the last row resolves, and prints anyway when the window closes, with the unresolved ones reported
-- as skipped rather than failed.

local WATCH_SECONDS = 30

local watcher = nil        -- the live retry timer, so a second :t050-4 replaces the first rather than racing

local function here()
  return hafen.world():position(0, 0)
end

-- The four item rows share one receiver: the first item of any open container. Resolved fresh on every
-- attempt, like every other receiver, so a container opened after the command was typed still counts.
local function anyItem()
  local invw = hafen.ui():inventory()
  local items = invw and invw:items() or nil
  return items and items[1] or nil
end

-- The error text of a call that must fail, with the chunk prefix stripped; nil if it did not fail at all.
local function msg(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function run()
  if watcher then                       -- a previous run is still waiting: it is superseded, not doubled
    watcher:cancel()
    watcher = nil
  end

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

  local texts = {}                      -- every refusal this run read, for the retired-name scan at the end
  local function record(s)
    if s then texts[#texts + 1] = s end
    return s
  end

  -- ---- the catalogue, as the reference now prints it -----------------------------------------------
  -- [1] the page that owns the verb · [2] the verb as the guide's catalogue writes it · [3] the key from the
  -- GUIDE's catalogue table · [4] the key printed beside that verb on its OWN page · [5] every group the
  -- guide's group section says covers the key · [6] the receiver, resolved FRESH on every attempt (nil = not
  -- there yet, retry later) · [7] the call. `always` is a verb whose door is never shut.
  local always = function() return true end
  local catalogue = {
    { "player.md",     "hafen.player():move",            "player.move",       "player.move",
      { "player.*" },                       always,
      function() hafen.player():move(here()) end },
    { "player.md",     "hafen.player():hand():use",      "player.hand.use",   "player.hand.use",
      { "player.*", "player.hand.*" },      function() return hafen.player():hand() end,
      function(hand) hand:use(here()) end },
    { "gob.md",        "gob:click",                      "gob.click",         "gob.click",
      { "gob.*" },                          always,
      function() hafen.world():gob():get(-1):click(3) end },
    { "ui/items.md",   "item:use",                       "item.use",          "item.use",
      { "item.*" },                         function() return anyItem() end,
      function(it) it:use(0) end },
    { "ui/items.md",   "item:take",                      "item.take",         "item.take",
      { "item.*" },                         function() return anyItem() end,
      function(it) it:take() end },
    { "ui/items.md",   "item:drop",                      "item.drop",         "item.drop",
      { "item.*" },                         function() return anyItem() end,
      function(it) it:drop(1) end },
    { "ui/items.md",   "item:transfer",                  "item.transfer",     "item.transfer",
      { "item.*" },                         function() return anyItem() end,
      function(it) it:transfer(1) end },
    { "world.md",      "hafen.world():place",            "world.place",       "world.place",
      { "world.*" },                        always,
      function() hafen.world():place(here(), 0) end },
    { "world.md",      "hafen.world():select",           "world.select",      "world.select",
      { "world.*" },                        always,
      function() hafen.world():select(here(), hafen.world():position(1, 1)) end },
    { "menugrid.md",   "pag:use",                        "menugrid.use",      "menugrid.use",
      { "menugrid.*" },                     function() return hafen.menugrid():list()[1] end,
      function(pag) pag:use() end },
    { "flowermenu.md", "hafen.flowermenu():select",      "flowermenu.select", "flowermenu.select",
      { "flowermenu.*" },                   always,
      function() hafen.flowermenu():select(1) end },
    { "flowermenu.md", "hafen.flowermenu():cancel",      "flowermenu.cancel", "flowermenu.cancel",
      { "flowermenu.*" },                   always,
      function() hafen.flowermenu():cancel() end },
    { "craft.md",      "hafen.craft():current():make",   "craft.make",        "craft.make",
      { "craft.*" },                        function() return hafen.craft():current() end,
      function(craft) craft:make() end },
    { "actionbar.md",  "slot:use",                       "actionbar.use",     "actionbar.use",
      { "actionbar.*" },                    always,
      function() hafen.actionbar():get(0):use() end },
    { "actionbar.md",  "slot:res",                       "actionbar.res",     "actionbar.res",
      { "actionbar.*" },                    always,
      function() hafen.actionbar():get(0):res("gfx/hud/act/mine") end },
    { "kin.md",        "hafen.kin():add",                "kin.add",           "kin.add",
      { "kin.*" },                          always,
      function() hafen.kin():add("secret") end },
    { "kin.md",        "kin:rename",                     "kin.rename",        "kin.rename",
      { "kin.*" },                          always,
      function() hafen.kin():get(-1):rename("x") end },
    { "kin.md",        "kin:group",                      "kin.group",         "kin.group",
      { "kin.*" },                          always,
      function() hafen.kin():get(-1):group(1) end },
    { "kin.md",        "kin:endKin",                     "kin.endKin",        "kin.endKin",
      { "kin.*" },                          always,
      function() hafen.kin():get(-1):endKin() end },
    { "kin.md",        "kin:forget",                     "kin.forget",        "kin.forget",
      { "kin.*" },                          always,
      function() hafen.kin():get(-1):forget() end },
    { "speed.md",      "hafen.speed():current",          "speed.current",     "speed.current",
      { "speed.*" },                        always,
      function() hafen.speed():current(1) end },
    { "ui/widget.md",  "widget:send",                    "widget.send",       "widget.send",
      { "widget.*" },                       function() return hafen.ui():root() end,
      function(root) root:send("click") end },
  }

  -- ---- the guide's catalogue against the pages, before anything is called --------------------------
  -- The guide says "Twenty-two keys", one per protected verb, and each page repeats the one its verb needs.
  -- Both halves are read here: the count and the distinctness the guide claims, and the two spellings of
  -- every key. Nothing in this block touches the engine -- it is the reference checked against itself.
  local disagree, dupes, seenKey = {}, {}, {}
  for _, row in ipairs(catalogue) do
    local page, guideKey, pageKey = row[1], row[3], row[4]
    if guideKey ~= pageKey then
      disagree[#disagree + 1] = page .. " prints " .. pageKey .. " where the catalogue prints " .. guideKey
    end
    if seenKey[guideKey] then
      dupes[#dupes + 1] = guideKey
    end
    seenKey[guideKey] = true
  end
  check((#catalogue == 22) and (#dupes == 0),
        ("the guide's catalogue is the twenty-two distinct keys it says it is (%d rows, %d repeated)")
        :format(#catalogue, #dupes), table.concat(dupes, ", "))
  check(#disagree == 0,
        ("every protected page states the same key for its verb as the guide's catalogue does (%d/%d)")
        :format(#catalogue - #disagree, #catalogue), table.concat(disagree, " | "))

  local asserted, wrong, grouped, badGroup, named, tried = 0, {}, 0, {}, {}, {}

  -- One attempt at one row. Returns false while the receiver is not there, so the caller retries it later.
  local function attempt(row)
    local page, lua, key, groups, recv, call = row[1], row[2], row[3], row[5], row[6], row[7]
    local ok, obj = pcall(recv)
    if (not ok) or (obj == nil) or (obj == false) then
      return false
    end
    asserted = asserted + 1
    tried[key] = true
    local err = record(msg(function() call(obj) end))
    if (err == nil) or not err:find('did not declare the "' .. key .. '"', 1, true) then
      wrong[#wrong + 1] = page .. " prints " .. key .. " beside " .. lua
                          .. " but got: " .. (err and err:sub(1, 70) or "<no error>")
    else
      named[key] = true
    end
    -- The refusal also names the group that grants the key. That is the engine's own grouping, so it is what
    -- the guide's group table is checked against -- one row of that table per key, `player.hand.*` included.
    local engineGroup = key:match("^(.*)%.[^.]+$")
    engineGroup = engineGroup and (engineGroup .. ".*") or "?"
    local documented = false
    for _, g in ipairs(groups) do
      if g == engineGroup then documented = true end
    end
    if err and documented and err:find('the group "' .. engineGroup .. '"', 1, true) then
      grouped = grouped + 1
    else
      badGroup[#badGroup + 1] = key .. " is granted by " .. engineGroup
                                .. ", which the guide's group table does not list for it"
    end
    return true
  end

  -- ---- the same transcription, backwards -----------------------------------------------------------
  -- The guide's second table says these write only to your own client and none of it is protected. A
  -- protected verb ALWAYS refuses an addon that declared nothing, so "did not refuse" is the whole proof --
  -- and each is called with an argument no writer would accept, so nothing is changed either way. Nothing
  -- here waits on the world, so it prints at once and the run shows life before any watching starts.
  local unprotected = {
    { "hafen.sound():play", function() hafen.sound():play(false) end },
    { "hafen.ui():sheet",   function() hafen.ui():sheet(false) end },
    { "hafen.vr():ghost",   function() hafen.vr():ghost(false) end },
  }
  local gatedByMistake = {}
  for _, row in ipairs(unprotected) do
    local err = record(msg(row[2]))
    if err and err:find("did not declare", 1, true) then
      gatedByMistake[#gatedByMistake + 1] = row[1] .. " -> " .. err:sub(1, 60)
    end
  end
  check(#gatedByMistake == 0,
        ("the verbs the guide's unprotected table lists are not gated (%d/%d)")
        :format(#unprotected - #gatedByMistake, #unprotected), table.concat(gatedByMistake, " | "))

  -- Everything that is left is printed once the rows have settled -- either because all 22 resolved or
  -- because the watch window closed on the ones whose receiver never appeared.
  local function finish(pending)
    check((asserted > 0) and (#wrong == 0),
          ("every key the reference prints is real: the verb refuses an addon that declared nothing, naming"
           .. " that exact key back (%d/%d reached this run)"):format(asserted - #wrong, asserted),
          table.concat(wrong, " | "))
    check((asserted > 0) and (grouped == asserted),
          ("...and the group the refusal offers instead is the one the guide's group table lists for that"
           .. " key, the nested player.hand.* included (%d/%d)"):format(grouped, asserted),
          table.concat(badGroup, " | "))

    -- ---- the guide's own declaration snippet -------------------------------------------------------
    -- guides/permissions.md tells the reader to paste ["player.move", "gob.click", "item.*"]. Two exact keys
    -- and one group: a snippet naming a key the catalogue does not have would fail to LOAD for anyone who
    -- copied it, so the keys are pinned here by the very refusals that name them back. Scored over the keys
    -- this run actually reached: a receiver the world never offered is the skip line's business, not a red one.
    local snippet = { "player.move", "gob.click", "item.use", "item.take", "item.drop", "item.transfer" }
    local missing, seen, reach = {}, 0, 0
    for _, key in ipairs(snippet) do
      if tried[key] then
        reach = reach + 1
        if named[key] then
          seen = seen + 1
        else
          missing[#missing + 1] = key
        end
      end
    end
    check((reach > 0) and (seen == reach),
          ('the keys the permissions guide tells you to paste are real: "player.move", "gob.click" and'
           .. ' every key the group "item.*" covers (%d/%d reached this run)'):format(seen, reach),
          table.concat(missing, ", "))

    -- ---- the vocabulary the pages print is the current one -----------------------------------------
    -- The pages now print keys, so the premise of every line above is that the engine's own vocabulary has
    -- no trace of the tier's retired name. Stated here where it can fail: every gate message this run read,
    -- plus three retired spellings read on purpose.
    local retired = 0
    for _, fn in ipairs({ function() return hafen.act end,
                          function() return hafen.speed.set end,
                          function() return hafen.craft.make end }) do
      if record(msg(fn)) then retired = retired + 1 end
    end
    local carries = {}
    for _, s in ipairs(texts) do
      if s:find("actions", 1, true) then carries[#carries + 1] = s:sub(1, 50) end
    end
    check((#carries == 0) and (retired == 3),
          ("no refusal names the retired tier (%d messages read, 3 of them retired spellings)")
          :format(#texts),
          (#carries > 0) and table.concat(carries, " | ") or (retired .. " retired rows read"))

    -- ---- what the world never offered ---------------------------------------------------------------
    -- Only ever reached when a receiver stayed absent for the whole window, and then it asks for one thing:
    -- run it again and let it see what it is waiting for. No reload, and nothing to hold while typing.
    if #pending > 0 then
      local keys = {}
      for _, row in ipairs(pending) do
        keys[#keys + 1] = row[3]
      end
      manualCheck(("run :t050-4 again and, in the %d seconds after you type it, put what is missing on"
                   .. " screen -- an item on the cursor for player.hand.use, an open container for item.*,"
                   .. " a recipe window for craft.make, the action menu for menugrid.use. %d of the 22"
                   .. " keys (%s) were skipped, not failed: nothing is wrong, the receiver was simply never"
                   .. " there. The suite waits for you, so there is nothing to arrange first and nothing to"
                   .. " reload"):format(WATCH_SECONDS, #pending, table.concat(keys, ", ")),
                  "the key line and the group line both read " .. #catalogue .. "/" .. #catalogue
                  .. ", and the guide-snippet line 6/6")
    end

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end

  -- First pass, then watch for the receivers that were not there.
  local pending = {}
  for _, row in ipairs(catalogue) do
    if not attempt(row) then
      pending[#pending + 1] = row
    end
  end
  if #pending == 0 then
    finish(pending)
    return
  end
  local ticks = 0
  watcher = hafen.timer():every(1, function()
    ticks = ticks + 1
    local still = {}
    for _, row in ipairs(pending) do
      if not attempt(row) then
        still[#still + 1] = row
      end
    end
    pending = still
    if (#pending == 0) or (ticks >= WATCH_SECONDS) then
      watcher:cancel()
      watcher = nil
      finish(pending)
    end
  end)
end

hafen.slash():register("t050-4", run)   -- the only way in: a suite does not start itself
