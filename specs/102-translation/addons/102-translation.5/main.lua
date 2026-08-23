-- 102.5 -- the pages. Self-checking suite.
--
-- WHAT THIS SHIPS. Two pages: `hafen.locale`, the reference, and `translating`, the guide that walks the
-- workflow end to end -- install a catalogue that names nothing, read back what missed, write the file,
-- ship it. No verb changes here, so what can be wrong is the WRITING: a file the guide prints that does
-- not parse, an escape the fence got wrong, a count :info() does not agree with, a refusal the page
-- promises and the client does not make.
--
-- HOW IT IS PROVED. `es.json` beside this file is the guide's own JSON fence, byte for byte, and the run
-- below is the guide's own shipping block, verbatim: parse the file, load it, install it. Everything
-- after that asks whether the page told the truth about what just happened.
--
-- THE ORACLE, TWICE. A translation is invisible from Lua by construction -- every readback answers the
-- client's own English -- so the raster is read two ways. A Label resizes itself to the very raster it
-- drew, so its width IS the string that reached the screen: a label built from "Water" measuring exactly
-- as wide as one built from "Agua" is the display string, measured. And :miss() says which KEY answered
-- -- the file names "Cancel" under `button` alone, so a label reading it still misses under `default`.

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

-- The pair the file's "*" entry names, and the string it names under `button` and nowhere else.
local EN, ES, KEYED = "Water", "Agua", "Cancel"

-- What the client DRAWS for `src`, as the width of the label that drew it, plus that label's own
-- readback. A Label resizes itself to its raster, so the width IS the string that reached the screen.
local made = {}
local function drawn(src)
  local l = hafen.ui():label():text(src)
  made[#made + 1] = l
  return l:size().w, l:text()
end

-- Is (surface, text) in this catalogue's miss set? A miss is an object, so the search is a predicate.
local function missed(surface, text)
  return hafen.locale():miss():find(function(m)
    return (m:surface() == surface) and (m:text() == text)
  end) ~= nil
end

local function run()
  local loc = hafen.locale()

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
  -- The ruler. Both candidates are measured with NOTHING installed, so each width is that literal
  -- string's own -- and two that measured the same would make the display check below vacuous.
  ---------------------------------------------------------------------------------------------------
  loc:release()                   -- ...whatever a previous run left behind: a ruler is measured bare
  local WEN, WES, WKEYED = drawn(EN), drawn(ES), drawn(KEYED)
  if WEN == WES then
    return stop("the two candidate strings measure apart, so a width tells them apart",
                EN .. "=" .. WEN .. " / " .. ES .. "=" .. WES)
  end

  ---------------------------------------------------------------------------------------------------
  -- The guide's shipping block, verbatim, over the guide's own file. A wrong escape in that fence is a
  -- pattern that does not compile, and :load raises on it rather than counting it.
  ---------------------------------------------------------------------------------------------------
  local ok, doc = pcall(function() return hafen.json():parse(hafen.asset():get("es.json"):text()) end)
  if not ok then return stop("the file the guide ships parses, escapes and all", doc) end
  check(type(doc) == "table", "the file the guide ships parses, escapes and all", type(doc))

  local ok2, err = pcall(function() hafen.locale():load(doc):install() end)
  if not ok2 then return stop("...and the guide's own shipping block loads and installs it", err) end
  local i = loc:info()
  check((i.installed == true) and (i.entries == 7) and (i.patterns == 1),
        "...and installs: the page's file is 7 entries and 1 pattern, in force",
        tostring(i.installed) .. "/" .. tostring(i.entries) .. "/" .. tostring(i.patterns))

  ---------------------------------------------------------------------------------------------------
  -- One asserted string changes surface. The label is built from the ENGLISH and comes out exactly as
  -- wide as one built from the Spanish, which is the display string measured off the raster it drew.
  ---------------------------------------------------------------------------------------------------
  local w, back = drawn(EN)
  check(w == WES, "the guide's own file changes one string at one surface", w .. " (want " .. WES .. ")")
  check(back == EN, "the model is not translated: :text() answers the client's own English", back)

  ---------------------------------------------------------------------------------------------------
  -- ...and the KEY is what did it. "Cancel" is named under `button` and nowhere else, so a label still
  -- misses it under `default` and draws its own width -- one file, one surface matched and one not.
  ---------------------------------------------------------------------------------------------------
  local wk = drawn(KEYED)
  check((wk == WKEYED) and missed("default", KEYED),
        "a key names one surface: the file's button entry does not reach a label",
        wk .. "/" .. tostring(missed("default", KEYED)))

  ---------------------------------------------------------------------------------------------------
  -- The round trip the guide opens with: what missed, keyed by surface, English doubled -- which is the
  -- shape of the very `text` block the file above is written in.
  ---------------------------------------------------------------------------------------------------
  local out = {}
  for _, m in ipairs(hafen.locale():miss():list()) do
    out[m:surface()] = out[m:surface()] or {}
    out[m:surface()][m:text()] = m:text()     -- English to English, ready to edit
  end
  check((out["default"] ~= nil) and (out["default"][KEYED] == KEYED),
        "the miss round trip builds the next file, keyed by surface",
        tostring(out["default"] and out["default"][KEYED]))

  ---------------------------------------------------------------------------------------------------
  -- The guide's two inline blocks, verbatim. A page whose example does not run is the defect this
  -- catches, and a `match` written with the wrong number of backslashes is exactly that defect.
  ---------------------------------------------------------------------------------------------------
  local okA = pcall(function()
    hafen.locale():load{
      text = {
        button = { Cancel = "Cancelar" },
        ["window.title"] = { Inventory = "Inventario" },
      },
    }:install()
  end)
  check(okA and (loc:info().entries == 2), "the guide's first block loads: an exact key per surface",
        tostring(okA) .. "/" .. tostring(loc:info().entries))

  local okB = pcall(function()
    hafen.locale():load{
      pattern = {
        { surface = "tooltip", match = "(\\d+) uses left", text = "quedan %1$s usos" },
        { surface = "heading", match = "(\\w+) of (\\w+)",  text = "%2$s: %1$s" },
      },
    }:install()
  end)
  check(okB and (loc:info().patterns == 2),
        "the guide's pattern block loads, both groups and the reordered argument",
        tostring(okB) .. "/" .. tostring(loc:info().patterns))

  ---------------------------------------------------------------------------------------------------
  -- The two refusals the pages promise by name, each said in the words the page says it in.
  ---------------------------------------------------------------------------------------------------
  refuses("a surface that draws no text is refused, naming the ones that do",
          function() loc:load({ text = { checkbox = { [EN] = ES } } }) end,
          "is not a surface this client draws text at")
  refuses("textentry is refused, saying what the user types is theirs",
          function() loc:load({ text = { textentry = { [EN] = ES } } }) end,
          "what the user types is theirs")

  ---------------------------------------------------------------------------------------------------
  -- ...and the client's own words come back, measured the same way they went.
  ---------------------------------------------------------------------------------------------------
  loc:release()
  local wr = drawn(EN)
  check((wr == WEN) and (loc:info().installed == false),
        ":release() puts the client's own words back", wr .. " (want " .. WEN .. ")")

  drop()
  finish()
end

hafen.slash():on("t102", run)   -- the only way in: a suite does not start itself
