-- 102.6 -- the readbacks that read a raster. Self-checking suite.
--
-- WHAT THIS SHIPS. `Text.text` is the string that was DRAWN, so every read that went to a rendered Text
-- for its answer started handing back the catalogue's words the moment one named that row. Six verbs did:
-- item:name(), buff:name() and wound:name() (ItemInfo.Name.str), the rows item:contents() prints
-- (Name.str and AdHoc.str), gob:speech() (Speaking.text) and the equipment slot name (Equipory.etts).
-- Each now reads its site's SOURCE -- the string that site was written -- and falls back to the raster
-- only where the caller handed the client a rendered Text and there is no source at all.
--
-- HOW IT IS PROVED. No hover is needed, because reading a name is itself what builds the tip: item:name()
-- rebuilds the info list whenever Fonts.gen() has moved, and a catalogue installing moves it. So the empty
-- round records the name row at "tooltip" off one read, and the named round answers it off the next --
-- with a SECOND item's name row, which the catalogue does not name, witnessing that the round drew name
-- rows at all. A pair going away with the verb still answering English is the whole of criterion 6.

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

local ES = "-es"                  -- what a catalogue says to draw instead
local WORD = "brodgar"            -- the word the maintainer says, and the one a * entry names
local POLL, WINDOW = 1, 45        -- the driven phase: one sweep a second, for three quarters of a minute

-- Is (surface, text) in this catalogue's miss set?
local function missed(surface, text)
  return hafen.locale():miss():find(function(m)
    return (m:surface() == surface) and (m:text() == text)
  end) ~= nil
end

local function firstLine(s)
  return s and s:match("^[^\n]*") or nil
end

-- `name` off a snapshot that may not be there yet -- an info() answers nil while its row is resolving.
local function snapName(t)
  return (t ~= nil) and t.name or nil
end

-- The line an item's contents state, read through whatever may still be resolving under it.
local function contRow(it)
  local c = it and it:contents()
  return firstLine(c and c:text())
end

-- A window this run reads may simply not be open. Whatever it answers, the list is what there was.
local function listOf(fn)
  local ok, v = pcall(fn)
  return (ok and (v ~= nil)) and v or {}
end

local function finish()
  pcall(function() hafen.locale():release() end)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The driven phase runs on a timer, so a throw inside it would be logged by the addon layer and the run
-- would simply stop, leaving the catalogue installed. It is its own pcall instead: whatever it was
-- becomes one [fail] line, and the suite still gives the client its English back.
local function phase(fn, onfail)
  return function()
    local ok, err = pcall(fn)
    if not ok then
      if onfail then pcall(onfail) end
      check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      finish()
    end
  end
end

local function body()
  local loc, s = hafen.locale(), hafen.session():current()
  if s == nil then
    check(false, "a character is logged in", "no current session -- run this in the world")
    return finish()
  end
  loc:release()   -- a previous run of this suite may still be holding one

  -------------------------------------------------------------------------------------------------
  -- What the backpack, the equipment window and the character give this run to key on. Every one of
  -- them is read with NOTHING installed, so each is the client's own English by construction.
  -------------------------------------------------------------------------------------------------
  local bag = listOf(function() return s:ui():inventory():items():list() end)

  local item, NAME, witness, WNAME
  for _, it in ipairs(bag) do
    local n = it:name()
    if n ~= nil then
      if NAME == nil then item, NAME = it, n
      elseif (WNAME == nil) and (n ~= NAME) then witness, WNAME = it, n end
    end
  end
  if (NAME == nil) or (WNAME == nil) then
    check(false, "the backpack holds two differently named items", "named items: "
          .. tostring(NAME) .. " / " .. tostring(WNAME) .. " -- put two unlike things in your backpack")
    return finish()
  end

  local cont, ROW = nil, nil
  for _, it in ipairs(bag) do
    local r = contRow(it)
    if (r ~= nil) and (r ~= "") and (r ~= NAME) and (r ~= WNAME) then cont, ROW = it, r; break end
  end

  local buff, BUFF = nil, nil
  for _, b in ipairs(listOf(function() return s:buff():list() end)) do
    local n = b:name()
    if (n ~= nil) and (n ~= NAME) and (n ~= WNAME) then buff, BUFF = b, n; break end
  end

  local wound, WOUND = nil, nil
  for _, w in ipairs(listOf(function() return s:wound():list() end)) do
    local n = w:name()
    if (n ~= nil) and (n ~= NAME) and (n ~= WNAME) then wound, WOUND = w, n; break end
  end

  local worn, SLOT = nil, nil
  for _, it in ipairs(listOf(function() return s:ui():equipment():items():list() end)) do
    local sl = it:slots()[1]
    if (sl ~= nil) and (sl ~= NAME) and (sl ~= WNAME) then worn, SLOT = it, sl; break end
  end

  -------------------------------------------------------------------------------------------------
  -- ROUND ONE. A catalogue that names NOTHING records everything, and reading item:name() is what
  -- draws the row: the tip is rebuilt because installing moved Fonts.gen().
  -------------------------------------------------------------------------------------------------
  loc:load({}):install()
  item:name(); witness:name(); contRow(cont)

  check(missed("tooltip", NAME),
        "reading item:name() is itself what draws the name row, at tooltip",
        "no (tooltip, " .. NAME .. ") pair in " .. loc:info().misses .. " misses")

  -------------------------------------------------------------------------------------------------
  -- ROUND TWO. ...and now a catalogue naming every row these six verbs read. A pair going away is
  -- the lookup running at the render; the verb still answering English is what this task ships.
  -------------------------------------------------------------------------------------------------
  local tips = { [NAME] = NAME .. ES }
  if ROW   then tips[ROW]   = ROW   .. ES end
  if BUFF  then tips[BUFF]  = BUFF  .. ES end
  if WOUND then tips[WOUND] = WOUND .. ES end
  if SLOT  then tips[SLOT]  = SLOT  .. ES end
  loc:load({ text = { tooltip = tips, ["*"] = { [WORD] = WORD .. ES } } }):install()

  item:name(); witness:name()
  local c2 = contRow(cont)

  -- The witness: the second item's name row is NOT named by the catalogue above, so its coming back
  -- says name rows were drawn in THIS round. Without it, an absence would prove nothing.
  check(missed("tooltip", WNAME) and not missed("tooltip", NAME),
        "an entry under tooltip answers the name row this very read drew",
        missed("tooltip", NAME) and "still recorded as a miss"
          or ("no witness: " .. WNAME .. " was not drawn either"))
  check(item:name() == NAME, "item:name() answers the client's own English", item:name())
  check(snapName(item:info()) == NAME, "...and so does item:info().name", snapName(item:info()))
  check((ROW ~= nil) and (c2 == ROW) and not missed("tooltip", ROW),
        "the rows item:contents() prints answer English, and the entry answered that row",
        (ROW == nil) and "no container in the backpack states a line -- carry a bucket or a waterskin"
          or (tostring(c2) .. (missed("tooltip", ROW) and " (still a miss)" or "")))
  check((BUFF ~= nil) and (buff:name() == BUFF) and (snapName(buff:info()) == BUFF),
        "buff:name() and buff:info().name answer English under an entry naming that row",
        (BUFF == nil) and "no named buff on the bar -- eat or drink something and run this again"
          or tostring(buff:name()))
  check((WOUND ~= nil) and (wound:name() == WOUND) and (snapName(wound:info()) == WOUND),
        "wound:name() and wound:info().name answer English under an entry naming that row",
        (WOUND == nil) and "no named wound on the character -- run this on one that carries any"
          or tostring(wound:name()))
  check((SLOT ~= nil) and (worn:slots()[1] == SLOT),
        "the equipment slot name answers English under an entry naming it",
        (SLOT == nil) and "nothing worn -- put on a hat and run this again"
          or tostring(worn:slots()[1]))

  -------------------------------------------------------------------------------------------------
  -- gob:speech(). A bubble is the server echoing what was SAID, which no program can cause, so the
  -- maintainer says it -- and what it produced is fully observable: the bubble is the catalogue's
  -- words and the verb is the client's own.
  -------------------------------------------------------------------------------------------------
  manualCheck("within " .. WINDOW .. "s: say exactly " .. WORD .. " in AREA chat",
              "the bubble over your character reading " .. WORD .. ES)

  local left, poll, seen = WINDOW, nil, nil
  poll = hafen.timer():every(POLL, phase(function()
    left = left - POLL
    for _, g in ipairs(listOf(function() return s:world():gob():list() end)) do
      local sp = g:speech()
      if (sp == WORD) or (sp == WORD .. ES) then seen = sp; break end
    end
    if (seen ~= nil) or (left <= 0) then
      poll:cancel()
      check(seen == WORD, "gob:speech() answers what was SAID, not the bubble the catalogue drew",
            (seen == nil) and ("no bubble carrying " .. WORD .. " in " .. WINDOW .. "s") or seen)
      finish()
    end
  end, function() poll:cancel() end))
end

-- The synchronous half is one pcall as well: a read that throws while its row is still resolving would
-- otherwise stop the run with the catalogue still installed, and the client still speaking Spanish.
local function run()
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    finish()
  end
end

hafen.slash():on("t102", run)   -- the only way in: a suite does not start itself
