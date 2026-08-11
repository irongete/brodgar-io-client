-- 050.3 — the tier's name leaves the reference. Self-checking suite; see specs/testing/addon-suite.md.
--
-- This suite DECLARES NOTHING, which is the shape its whole claim needs: every check below is a refusal,
-- so there is nothing to enable, nothing to consent to and nothing sent to the server.
--
-- The claim is the reference's, not the engine's. Task 050.3 rewrote every protected group heading and
-- re-pointed every anchor into it, which is exactly the kind of sweep that can quietly mark the wrong verb.
-- So the pairs below are TRANSCRIBED from the pages the sweep touched -- the verb spelling as the page
-- writes it, beside the permission key this task recorded for it -- and each is driven through the gate:
--
--   * a verb the reference marks protected MUST refuse, and the refusal must name THAT key. A page that
--     drifted from the engine -- a heading left on a verb that is not gated, a verb moved to another
--     section's key -- reddens the line and says which page it came from.
--   * a verb the guide's UNPROTECTED table lists must NOT refuse. That is the same transcription read
--     backwards, and it is the half a one-directional sweep never checks.
--   * the keys the guide's own declaration snippet asks the reader to paste are driven too, so a snippet
--     that would fail to load cannot survive the run.
--
-- Every call is given arguments that would be perfectly good, so nothing but the gate can be what refuses;
-- the gate is the first statement of every protected verb (D-213), so nothing is built and nothing is sent.
--
-- THE SUITE WAITS INSTEAD OF ASKING. Two receivers are server-driven and cannot be conjured: the cursor's
-- held item (hafen.player():hand()) and the open recipe (hafen.craft():current()). Rather than make the
-- maintainer arrange them BEFORE typing the command -- which meant holding an item while opening the
-- console, and undoing the arrangement before the read ever happened -- every pair whose receiver is not
-- there yet is retried once a second for WATCH_SECONDS. So the order is: run the command, then pick an item
-- up and open any recipe at your own pace. The block prints itself the moment the last pair resolves, and
-- prints anyway when the window closes, with the unresolved ones reported as skipped rather than failed.

local WATCH_SECONDS = 30

local watcher = nil        -- the live retry timer, so a second :t050-3 replaces the first rather than racing

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

  -- ---- the reference's protected set, page by page ------------------------------------------------
  -- Column 1 is the page the pair was read off, column 2 the verb as that page spells it, column 3 the key,
  -- column 4 resolves the receiver FRESH on every attempt (nil = not there yet, retry later), column 5 the
  -- call. `true` is the receiver of a verb whose door is always open.
  local always = function() return true end
  local protected = {
    { "player.md",     "hafen.player():move",       "player.move",       always,
      function() hafen.player():move(here()) end },
    { "player.md",     "hafen.player():hand():use", "player.hand.use",   function() return hafen.player():hand() end,
      function(hand) hand:use(here()) end },
    { "gob.md",        "gob:click",                 "gob.click",         always,
      function() hafen.world():gob():get(-1):click(3) end },
    { "ui/items.md",   "item:use",                  "item.use",          function() return anyItem() end,
      function(it) it:use(0) end },
    { "ui/items.md",   "item:take",                 "item.take",         function() return anyItem() end,
      function(it) it:take() end },
    { "ui/items.md",   "item:drop",                 "item.drop",         function() return anyItem() end,
      function(it) it:drop(1) end },
    { "ui/items.md",   "item:transfer",             "item.transfer",     function() return anyItem() end,
      function(it) it:transfer(1) end },
    { "world.md",      "hafen.world():place",       "world.place",       always,
      function() hafen.world():place(here(), 0) end },
    { "world.md",      "hafen.world():select",      "world.select",      always,
      function() hafen.world():select(here(), hafen.world():position(1, 1)) end },
    { "menugrid.md",   "pag:use",                   "menugrid.use",      function() return hafen.menugrid():list()[1] end,
      function(pag) pag:use() end },
    { "flowermenu.md", "hafen.flowermenu():select", "flowermenu.select", always,
      function() hafen.flowermenu():select(1) end },
    { "flowermenu.md", "hafen.flowermenu():cancel", "flowermenu.cancel", always,
      function() hafen.flowermenu():cancel() end },
    { "craft.md",      "craft:make",                "craft.make",        function() return hafen.craft():current() end,
      function(craft) craft:make() end },
    { "actionbar.md",  "slot:use",                  "actionbar.use",     always,
      function() hafen.actionbar():get(0):use() end },
    { "actionbar.md",  "slot:res",                  "actionbar.res",     always,
      function() hafen.actionbar():get(0):res("gfx/hud/act/mine") end },
    { "kin.md",        "hafen.kin():add",           "kin.add",           always,
      function() hafen.kin():add("secret") end },
    { "kin.md",        "kin:rename",                "kin.rename",        always,
      function() hafen.kin():get(-1):rename("x") end },
    { "kin.md",        "kin:group",                 "kin.group",         always,
      function() hafen.kin():get(-1):group(1) end },
    { "kin.md",        "kin:endKin",                "kin.endKin",        always,
      function() hafen.kin():get(-1):endKin() end },
    { "kin.md",        "kin:forget",                "kin.forget",        always,
      function() hafen.kin():get(-1):forget() end },
    { "speed.md",      "hafen.speed():current",     "speed.current",     always,
      function() hafen.speed():current(1) end },
    { "ui/widget.md",  "widget:send",               "widget.send",       function() return hafen.ui():root() end,
      function(root) root:send("click") end },
  }

  local asserted, guiding, wrong, named, tried = 0, 0, {}, {}, {}

  -- One attempt at one pair. Returns false while the receiver is not there, so the caller retries it later.
  local function attempt(row)
    local page, lua, key, recv, call = row[1], row[2], row[3], row[4], row[5]
    local ok, obj = pcall(recv)
    if (not ok) or (obj == nil) or (obj == false) then
      return false
    end
    asserted = asserted + 1
    tried[key] = true
    local err = record(msg(function() call(obj) end))
    if (err == nil) or not err:find('did not declare the "' .. key .. '"', 1, true) then
      wrong[#wrong + 1] = page .. " marks " .. lua .. " protected as " .. key
                          .. " but got: " .. (err and err:sub(1, 70) or "<no error>")
    else
      named[key] = true
    end
    -- ...and the refusal has to be usable on its own, since the page no longer prints the key: it names the
    -- manifest line to paste and the group that also grants it.
    local group = key:match("^(.*)%.[^.]+$")
    if err and err:find('"permissions": ["' .. key .. '"]', 1, true)
           and err:find('"' .. group .. '.*"', 1, true) then
      guiding = guiding + 1
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

  -- Everything that is left is printed once the pairs have settled -- either because all 22 resolved or
  -- because the watch window closed on the ones whose receiver never appeared.
  local function finish(pending)
    check(#wrong == 0, ("every verb the reference marks protected refuses when undeclared, naming the key"
          .. " this task recorded for it (%d/%d)"):format(asserted - #wrong, #protected),
          table.concat(wrong, " | "))
    check((asserted > 0) and (guiding == asserted),
          ("...and each refusal names the manifest line to paste and the group that also grants it (%d/%d)")
          :format(guiding, asserted), guiding .. " of " .. asserted)

    -- ---- the guide's own declaration snippet -------------------------------------------------------
    -- guides/permissions.md tells the reader to paste ["player.move", "gob.click", "item.*"]. Two exact keys
    -- and one group: a snippet naming a key the catalogue does not have would fail to LOAD for anyone who
    -- copied it, so the keys are pinned here by the very refusals that name them back.
    -- Scored over the keys this run actually reached, exactly like the protected line above: a receiver the
    -- world never offered is the `[manual]` line's business, not a red one here.
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

    -- ---- the retired tier's name survives in no refusal --------------------------------------------
    -- This task's subject is that name leaving the reference, so the engine half is its premise and is
    -- stated here where it can fail: every gate message this run read, plus three retired spellings read on
    -- purpose.
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
      manualCheck(("run :t050-3 again and, in the %d seconds after you type it, put what is missing on"
                   .. " screen -- an item on the cursor for player.hand.use, an open container for item.*,"
                   .. " a recipe window for craft.make, the action menu for menugrid.use. %d of the 22"
                   .. " verbs (%s) were skipped, not failed: nothing is wrong, the receiver was simply"
                   .. " never there. The suite waits for you, so there is nothing to arrange first and"
                   .. " nothing to reload"):format(WATCH_SECONDS, #pending, table.concat(keys, ", ")),
                  "the protected line reads " .. #protected .. "/" .. #protected
                  .. " and the guide-snippet line 6/6")
    end

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end

  -- First pass, then watch for the receivers that were not there.
  local pending = {}
  for _, row in ipairs(protected) do
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

hafen.slash():register("t050-3", run)   -- the only way in: a suite does not start itself
