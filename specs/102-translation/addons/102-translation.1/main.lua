-- 102.1 -- the catalogue, and the surface a string is drawn at. Self-checking suite.
--
-- WHAT THIS SHIPS. hafen.locale() is one catalogue per addon: :load(doc) parses a document of what this
-- client DISPLAYS, :install() makes it so, :release() gives the client its own words back, :info() says
-- whether it is in force, and :miss() is the set of strings that reached a routed surface with no entry
-- naming them. An entry is keyed on TWO things -- the surface the string is drawn at and the string itself
-- -- so "Brodgar" on a button and "Brodgar" in a chat line are different entries, and "*" is the one key
-- that answers at every surface.
--
-- WHY THE MISSES ARE THE ORACLE. The catalogue lands at the render and nowhere above it, so every string
-- this API hands back stays the client's own English -- which means nothing in Lua can SEE a translation.
-- What it can see is what missed, and that is also exactly what an author needs to write their first file.
-- So the suite proves the lookup by what it did NOT record: an entry under `button` is proved by the
-- button's pair vanishing from :miss() while the label's, one surface away, stays.

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

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local WORD = "Brodgar"          -- one word, drawn at two surfaces, named at one of them
local OTHER = "Brodgar-es"      -- ...and what a catalogue says to draw instead

-- Is (surface, WORD) in this catalogue's miss set? A miss is an object, so the search is a predicate.
local function missed(surface, text)
  return hafen.locale():miss():find(function(m)
    return (m:surface() == surface) and (m:text() == text)
  end) ~= nil
end

local function run()
  local loc = hafen.locale()
  local made = {}

  -- One button and one label, both reading `text`. Both render AT THE SETTER, so the pair reaches the
  -- catalogue the moment this returns -- no frame has to run, and nothing of the suite's ever paints.
  local function draw(text)
    local b = hafen.ui():button():text(text)
    local l = hafen.ui():label():text(text)
    made[#made + 1] = b
    made[#made + 1] = l
    return b, l
  end

  local function drop()
    for _, w in ipairs(made) do pcall(function() w:destroy() end) end
    made = {}
  end

  local function stop(why, got)
    check(false, why, got)
    pcall(function() loc:release() end)
    drop()
    finish()
  end

  if hafen.session():current() == nil then
    return stop("a character is logged in", "no current session -- run this in the world")
  end

  ---------------------------------------------------------------------------------------------------
  -- Holding one is all it takes. An EMPTY catalogue names nothing and records everything, because
  -- writing the first file depends on nothing but having one installed.
  ---------------------------------------------------------------------------------------------------
  check(loc:info().installed == false, "no catalogue is installed before :install()", loc:info().installed)

  local ok, err = pcall(function() loc:load({}):install() end)
  if not ok then return stop("an empty catalogue loads and installs", err) end
  local i = loc:info()
  check((i.installed == true) and (i.entries == 0),
        "an empty catalogue installs and says so (0 entries)", tostring(i.installed) .. "/" .. tostring(i.entries))

  draw(WORD)
  check(missed("button", WORD) and missed("default", WORD),
        "an empty catalogue records both pairs -- the button's and the label's",
        tostring(missed("button", WORD)) .. "/" .. tostring(missed("default", WORD)))

  ---------------------------------------------------------------------------------------------------
  -- A key names ONE surface. One string, one surface matched and one not: which is what proves the
  -- lookup ran at the RENDER, under the key the site declared, rather than over the document.
  ---------------------------------------------------------------------------------------------------
  loc:load({ text = { button = { [WORD] = OTHER } } }):install()
  draw(WORD)
  check(not missed("button", WORD), "an entry under button matches the button caption", "still missing")
  check(missed("default", WORD),
        "...and does not reach the label, which draws under default", "the label matched it too")

  ---------------------------------------------------------------------------------------------------
  -- :release() gives the client its own words back. A released catalogue is out of the stack, so it is
  -- never asked and records nothing -- the same before/after the line above, on the switch alone.
  ---------------------------------------------------------------------------------------------------
  loc:load({}):install()          -- empty and recording, exactly as three checks ago
  loc:release()
  check(loc:info().installed == false, ":release() leaves nothing installed", loc:info().installed)
  draw(WORD)
  check((not missed("button", WORD)) and (not missed("default", WORD)),
        "a released catalogue records nothing: the client is drawing its own English again",
        "a pair was still recorded")

  ---------------------------------------------------------------------------------------------------
  -- "*" is the key that answers at every surface -- and the model is still not translated.
  ---------------------------------------------------------------------------------------------------
  loc:load({ text = { ["*"] = { [WORD] = OTHER } } }):install()
  local b, l = draw(WORD)
  check((not missed("button", WORD)) and (not missed("default", WORD)),
        "an entry under * answers both surfaces",
        tostring(missed("button", WORD)) .. "/" .. tostring(missed("default", WORD)))
  check((b:text() == WORD) and (l:text() == WORD),
        "the model is not translated: :text() answers the client's own English",
        tostring(b:text()) .. "/" .. tostring(l:text()))

  ---------------------------------------------------------------------------------------------------
  -- Four refusals, each naming what exists. A bad document leaves the catalogue exactly as it was.
  ---------------------------------------------------------------------------------------------------
  refuses("a surface that draws no text is refused, naming the ones that do",
          function() loc:load({ text = { panel = { [WORD] = OTHER } } }) end,
          "is not a surface this client draws text at")
  refuses("textentry is not a catalogue key, and says whose the text is",
          function() loc:load({ text = { textentry = { [WORD] = OTHER } } }) end,
          "what the user types is theirs")
  refuses("an unknown document property is refused, naming text and pattern",
          function() loc:load({ txt = { button = { [WORD] = OTHER } } }) end,
          "is not a catalogue property")
  refuses(":load(nil) is refused", function() loc:load(nil) end, "must not be nil")
  check(loc:info().entries == 1, "a refused document leaves the catalogue as it was", loc:info().entries)

  loc:release()
  drop()
  finish()
end

hafen.slash():on("t102", run)   -- the only way in: a suite does not start itself
