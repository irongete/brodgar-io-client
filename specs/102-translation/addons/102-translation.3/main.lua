-- 102.3 -- the text a resource's own code draws. Self-checking suite.
--
-- WHAT THIS SHIPS. An item tooltip is composed of rows, and most of them are drawn by code that ships
-- INSIDE the resources -- `ui/tt/q/qbuff` writes the quality row, `ui/tt/wear` the wear one -- through
-- private foundries the fork never sees. They are reached for nothing: a catalogue lands under
-- Text.Foundry.render and its RichText twin, beneath everything, so a foundry nothing can route walks
-- through it like any other. What a site owes is its KEY, and this task gave the "tooltip" pair to
-- every place that was resolving the tooltip FONT and still offering its string under "default".
--
-- HOW IT IS PROVED. A catalogue is invisible from Lua by construction, so what reads back is what
-- MISSED. A row reaching :miss() under "tooltip" is the proof that site declared that key; the same row
-- GONE under a catalogue naming it is the proof the lookup ran at the render. A tooltip is drawn by a
-- hover no program can make, so the maintainer makes it and the suite polls for a bounded window --
-- what the hover produced is fully observable even though the suite could not cause it.
--
-- WHY AN ITEM WITH A QUALITY. Its quality row is written by published resource code and by nothing in
-- this tree, so a pair for it under "tooltip" is the whole of the first paragraph, scored.

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
local POLL, WINDOW = 1, 45        -- the driven phase: one sweep a second, for three quarters of a minute

-- Every text this catalogue has recorded at `surface`, in arrival order.
local function rowsAt(surface)
  local out = {}
  for _, m in ipairs(hafen.locale():miss():list()) do
    if m:surface() == surface then out[#out + 1] = m:text() end
  end
  return out
end

-- Is (surface, text) in this catalogue's miss set?
local function missed(surface, text)
  return hafen.locale():miss():find(function(m)
    return (m:surface() == surface) and (m:text() == text)
  end) ~= nil
end

-- Every surface `text` reached, whatever the key -- what a failing "it is keyed here" line reports.
local function surfacesOf(text)
  local out = {}
  for _, m in ipairs(hafen.locale():miss():list()) do
    if m:text() == text then out[#out + 1] = m:surface() end
  end
  return table.concat(out, ",")
end

-- A bounded, readable sample of what a phase actually caught, for the `got` of whatever failed.
local function sample(rows)
  local out = {}
  for i = 1, math.min(#rows, 3) do out[i] = "[" .. rows[i] .. "]" end
  return "#" .. #rows .. " " .. table.concat(out, " ")
end

local function finish()
  pcall(function() hafen.locale():release() end)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- A phase runs on a timer, so a throw inside one would be logged by the addon layer and the run would
-- simply stop, leaving the catalogue installed. Every phase is its own pcall instead: whatever it was
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

-- Poll `look` once a second until it says it has everything or the window closes, then run `done`.
local function drive(look, done)
  local left, poll = WINDOW, nil
  poll = hafen.timer():every(POLL, phase(function()
    left = left - POLL
    if look() or (left <= 0) then
      poll:cancel()
      done()
    end
  end, function() poll:cancel() end))
end

local function run()
  local loc, s = hafen.locale(), hafen.session():current()
  if s == nil then
    check(false, "a character is logged in", "no current session -- run this in the world")
    return finish()
  end

  -- An item with a QUALITY: its tooltip is the name row plus at least one row published resource code
  -- composed, which is the pair of things this task has to reach.
  local item = nil
  for _, it in ipairs(s:ui():inventory():items():list()) do
    if (it:name() ~= nil) and (it:quality() ~= nil) then item = it; break end
  end
  if item == nil then
    check(false, "the backpack holds an item with a quality to hover",
          s:ui():inventory():items():count() .. " items, none of them both named and qualitied")
    return finish()
  end
  local NAME = item:name()

  local pag = s:menugrid():list()[1]
  local PAG = pag and pag:name()
  if PAG == nil then
    check(false, "the action menu names an action to read back", "no pagina carrying a name")
    return finish()
  end

  -------------------------------------------------------------------------------------------------
  -- A catalogue that names NOTHING records everything, so what the hover draws lands in :miss()
  -- under the key each row's own site declared.
  -------------------------------------------------------------------------------------------------
  loc:load({}):install()
  manualCheck("within " .. WINDOW .. "s: hover " .. NAME .. " in your backpack until its FULL tooltip"
              .. " shows (three rows or more), then move off it",
              "the tooltip in the client's own English, unchanged")

  drive(function()
    return missed("tooltip", NAME) and (#rowsAt("tooltip") > 1)
  end, phase(function()
    local rows = rowsAt("tooltip")
    check(missed("tooltip", NAME), "the item name is recorded under tooltip",
          (surfacesOf(NAME) ~= "") and surfacesOf(NAME)
            or ("no pair for it in " .. WINDOW .. "s; " .. loc:info().misses .. " misses held"))

    local other = {}
    for _, t in ipairs(rows) do if t ~= NAME then other[#other + 1] = t end end
    check(#other > 0, "the rows composed under the name are recorded under tooltip too", sample(rows))

    local qrow = nil
    for _, t in ipairs(other) do if t:find("Quality", 1, true) then qrow = t; break end end
    check(qrow ~= nil, "a row the item's OWN resource code drew, through a foundry the fork cannot"
          .. " route, reaches tooltip", sample(other))
    qrow = qrow or other[1]

    -- pag:name() is the readback this task carries: the action grid draws that same name at "tooltip"
    -- (MenuGrid.PagButton.rendertt), so the entry installed below names a string that IS on screen, and
    -- the verb still has to answer the client's own word for it.
    check((pag:name() == PAG) and (item:name() == NAME),
          "a catalogue in force changes no readback: pag:name() and item:name() answer English",
          tostring(pag:name()) .. " / " .. tostring(item:name()))

    -- The WITNESS of the second hover: a row of THIS item that the catalogue below does not name. Some
    -- other widget's tooltip would satisfy "the surface still records" without the item having been
    -- hovered at all, and then the two absences below would prove nothing.
    local witness = {}
    for _, t in ipairs(other) do if t ~= qrow then witness[#witness + 1] = t end end
    if (qrow == nil) or (#witness == 0) then
      check(false, "the hovered item has a third row to witness the second hover",
            "only " .. #rows .. " rows -- hover something with a wear or an armour row instead")
      return finish()
    end

    local function witnessed()
      for _, t in ipairs(witness) do if missed("tooltip", t) then return t end end
      return nil
    end

    ---------------------------------------------------------------------------------------------
    -- ...and now a catalogue that NAMES the name row, that resource-drawn row, and the action the
    -- readback above reads. A pair going away is the lookup running at the render.
    ---------------------------------------------------------------------------------------------
    loc:load({ text = { tooltip = { [NAME] = NAME .. ES, [qrow] = qrow .. ES },
                        ["*"]   = { [PAG] = PAG .. ES } } }):install()
    manualCheck("hover the same item again, the same way",
                "its first row reading " .. NAME .. ES .. ", the rest of the tooltip unchanged")

    drive(function()
      return (witnessed() ~= nil) and not missed("tooltip", NAME) and not missed("tooltip", qrow)
    end, phase(function()
      local w = witnessed()
      -- The hover has to be proved LIVE in this round, or an absence says nothing: a row of this very
      -- item that the catalogue does NOT name is what says the tip was drawn again.
      check(w ~= nil, "the same tooltip was drawn again, recording the rows this catalogue leaves alone",
            "none of this item's other rows came back in " .. WINDOW .. "s -- nothing was hovered")
      check((w ~= nil) and not missed("tooltip", NAME),
            "an entry under tooltip answers the item name", "still recorded as a miss")
      check((w ~= nil) and not missed("tooltip", qrow),
            "...and answers the row the resource's own code drew", "still recorded as a miss")
      check(pag:name() == PAG,
            "the model is still not translated: pag:name() answers English under an entry naming it",
            tostring(pag:name()))
      finish()
    end))
  end))
end

hafen.slash():on("t102", run)   -- the only way in: a suite does not start itself
