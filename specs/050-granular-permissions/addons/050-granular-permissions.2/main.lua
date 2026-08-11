-- 050.2 -- the consent dialog enumerates, and the row says how much. Self-checking suite; see
-- specs/testing/addon-suite.md.
--
-- This suite DECLARES two entries of DIFFERENT SHAPES -- the group "flowermenu.*" and the exact key
-- "player.move" -- because the two things this task built both render per ENTRY, and a suite declaring
-- one shape could not tell a dialog that enumerates from one that expands groups into their members.
--
-- What it proves, in the order it prints:
--
--   * the path everything else here reads from, restated where it can fail: a DECLARED verb is past the
--     gate (D-213 -- the gate is the first statement, so reaching the verb's own argument refusal IS the
--     grant), and an UNDECLARED one refuses NAMING ITS OWN KEY. Nothing is sent to the server: every call
--     below is deliberately malformed, and both flowermenu verbs refuse on their argument before they
--     look for a menu, so neither commits anything even if one happens to be open.
--   * then the two surfaces this task changed, READ from the widget tree rather than eyeballed: the
--     AddOns panel row (its marker carries the COUNT, its tooltip the entries) and the consent dialog
--     itself (one plain-language line per declared entry, the group as ONE line, and nothing else).
--
-- THE SUITE WAITS INSTEAD OF ASKING (the 050.3 rule: [manual] is for what a suite cannot OBSERVE, not for
-- what it cannot CAUSE). Both surfaces are the maintainer's to put on screen and neither survives typing a
-- console command, so nothing has to be arranged first: run the command, then open Options > AddOns and
-- re-raise the dialog at your own pace. Each surface is retried once a second for WATCH_SECONDS and the
-- block prints itself the moment the last one resolves -- or when the window closes, with whatever never
-- appeared reported as skipped rather than failed.

local WATCH_SECONDS = 60

local NAME = "050.2"                       -- what this addon's own panel row starts with
local TITLE = "Enable " .. NAME            -- ...and what its consent dialog's caption starts with

-- The two entries as the manifest declares them, in manifest order, each beside the exact line the consent
-- dialog is expected to render for it. Transcribed from the catalogue: a group reads as its members joined,
-- so a dialog that expanded "flowermenu.*" into two lines, dropped the joining, or listed a key nobody
-- declared reddens the line and shows what it printed instead.
local ENTRIES = {
  { "flowermenu.*", "- choose from the radial menu and dismiss the radial menu  (flowermenu.*)" },
  { "player.move",  "- walk your character to a place  (player.move)" },
}
local MARKER = "[protected: 2]"            -- TWO entries -- not the three keys they grant
local TIP = "Permissions: flowermenu.*, player.move"

-- Keys this addon did not declare. None may appear in the dialog (it lists what was asked for, nothing
-- else), and each must refuse when called, naming itself.
local UNDECLARED = { "gob.click", "world.place", "kin.add", "speed.current" }

local watcher = nil        -- the live retry timer, so a second :t050-2 replaces the first rather than racing

-- The error text of a call that must fail, with the chunk prefix stripped; nil if it did not fail at all.
local function msg(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- This addon's own row label in the AddOns panel: the one label whose text starts with the manifest name.
-- nil until the maintainer has opened Options > AddOns.
local function rowLabel()
  for _, w in ipairs(hafen.ui():all("label")) do
    local t = w:text()
    if t and (t:sub(1, #NAME) == NAME) and t:find("v1.0.0", 1, true) then
      return w
    end
  end
  return nil
end

-- The live consent dialog for this addon, by its caption. nil while it is not up.
local function consentWnd()
  for _, w in ipairs(hafen.ui():all("window")) do
    local t = w:title()
    if t and (t:sub(1, #TITLE) == TITLE) then
      return w
    end
  end
  return nil
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

  -- ---- the declared half: past the gate, at the verb's own argument refusal ------------------------
  -- One call per catalogue key the two entries grant, so the GROUP is proven to cover both its members and
  -- the EXACT key its one. Reaching an argument refusal is the grant; a "did not declare" here would mean
  -- the dialog the maintainer approved granted something other than what it listed.
  local granted, missed = 0, {}
  local function grantedBy(key, fn)
    local err = msg(fn)
    if (err ~= nil) and not err:find("did not declare", 1, true) then
      granted = granted + 1
    else
      missed[#missed + 1] = key .. "=" .. (err and err:sub(1, 60) or "<no error>")
    end
  end
  grantedBy("flowermenu.select", function() hafen.flowermenu():select(true) end)
  grantedBy("flowermenu.cancel", function() hafen.flowermenu():cancel(1) end)
  grantedBy("player.move", function() hafen.player():move({ x = 1, y = 1 }) end)
  check((#missed == 0) and (granted == 3),
        ('the two declared entries grant all three keys: the group "flowermenu.*" covers :select and'
         .. ' :cancel, the exact key "player.move" covers :move (%d/3)'):format(granted),
        table.concat(missed, "; "))

  -- ---- and nothing else -----------------------------------------------------------------------------
  -- The group reaches nothing outside its prefix and the exact key widens to nothing at all: each of these
  -- refuses, naming its OWN key. Called with arguments that would be perfectly good, so only the gate can
  -- be what refuses -- and the gate refuses before anything is built or sent.
  local calls = {
    ["gob.click"]     = function() hafen.world():gob():get(-1):click(3) end,
    ["world.place"]   = function() hafen.world():place(hafen.world():position(0, 0), 0) end,
    ["kin.add"]       = function() hafen.kin():add("secret") end,
    ["speed.current"] = function() hafen.speed():current(1) end,
  }
  local wrong = {}
  for _, key in ipairs(UNDECLARED) do
    local err = msg(calls[key])
    if (err == nil) or not err:find('did not declare the "' .. key .. '"', 1, true) then
      wrong[#wrong + 1] = key .. "=" .. (err and err:sub(1, 60) or "<no error>")
    end
  end
  check(#wrong == 0, ("every key outside those two entries still refuses, naming its own (%d/%d)")
        :format(#UNDECLARED - #wrong, #UNDECLARED), table.concat(wrong, "; "))

  -- ---- the AddOns panel row -------------------------------------------------------------------------
  local function checkRow(lb)
    local t = lb:text() or ""
    check(t:find(MARKER, 1, true) ~= nil,
          'the panel row marks this addon "' .. MARKER .. '" -- the count is the ENTRIES the user reads,'
          .. ' not the three keys they grant', t)
    local tip = lb:tooltip()
    check((tip ~= nil) and (tip:find(TIP, 1, true) ~= nil),
          "...and the row tooltip names both entries, so what it asked for is one hover away",
          tip or "<no tooltip>")
  end

  -- ---- the consent dialog ---------------------------------------------------------------------------
  local dialogRead = false
  local function checkDialog(wnd)
    dialogRead = true
    local shown, others = {}, {}
    for _, w in ipairs(wnd:all("label")) do
      local t = w:text()
      if t then
        if t:sub(1, 2) == "- " then
          shown[#shown + 1] = t
        end
        for _, key in ipairs(UNDECLARED) do
          if t:find(key, 1, true) then others[#others + 1] = key end
        end
      end
    end
    local bad = {}
    for i, row in ipairs(ENTRIES) do
      if shown[i] ~= row[2] then
        bad[#bad + 1] = row[1] .. " -> " .. tostring(shown[i])
      end
    end
    check((#shown == #ENTRIES) and (#bad == 0),
          ("the dialog renders one plain-language line per declared entry, in manifest order, the group as"
           .. " ONE line standing for both its members (%d/%d)"):format(#ENTRIES - #bad, #ENTRIES),
          (#shown ~= #ENTRIES) and (#shown .. " lines: " .. table.concat(shown, " | "))
                                or table.concat(bad, " | "))
    check(#others == 0, "...and nothing the addon did not ask for: no undeclared key appears in the dialog",
          table.concat(others, ", "))
    local marked = {}
    for _, t in ipairs(shown) do
      if t:find("NEW:", 1, true) then marked[#marked + 1] = t end
    end
    check(#marked == 0,
          "...and no entry is marked NEW, because this is the set already consented to -- the mark is for"
          .. " an escalation, not for every prompt", table.concat(marked, " | "))
  end

  -- ---- the two surfaces the maintainer puts on screen ------------------------------------------------
  local targets = {
    { "the AddOns panel row", rowLabel, checkRow, 2 },
    { "the consent dialog", consentWnd, checkDialog, 3 },
  }

  local function finish(pending)
    if #pending > 0 then
      local what, skipped = {}, 0
      for _, row in ipairs(pending) do
        what[#what + 1] = row[1]
        skipped = skipped + row[4]
      end
      manualCheck(("run :t050-2 again and, in the %d seconds after you type it, put on screen what never"
                   .. " appeared: %s. The row wants Options > AddOns open; the dialog wants that row"
                   .. " UNTICKED and then TICKED again, which raises it exactly as enabling this suite"
                   .. " did -- read it, then click Enable to leave the grant as it was. %d check(s) were"
                   .. " skipped, not failed: the suite waits for you, so there is nothing to arrange"
                   .. " first and nothing to reload"):format(WATCH_SECONDS, table.concat(what, " and "),
                                                            skipped),
                  "5 pass on the two surfaces, and this line gone")
    end
    if dialogRead then
      manualCheck("close that dialog with Enable rather than Cancel (Cancel leaves this suite disabled for"
                  .. " the next login, since re-ticking the row is what raised it)",
                  "the row is ticked again and still reads " .. MARKER)
    end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end

  -- First pass, then watch for whatever was not on screen yet.
  local pending = {}
  for _, row in ipairs(targets) do
    local obj = row[2]()
    if obj == nil then
      pending[#pending + 1] = row
    else
      row[3](obj)
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
      local obj = row[2]()
      if obj == nil then
        still[#still + 1] = row
      else
        row[3](obj)
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

hafen.slash():register("t050-2", run)   -- the only way in: a suite does not start itself
