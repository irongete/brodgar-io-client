-- 102.4 -- patterns. Self-checking suite.
--
-- WHAT THIS SHIPS. A catalogue's second half. An exact key names one string, but a string the client
-- COMPOSED as it drew it -- a row carrying a number, a line carrying a name -- is a different string
-- every time, so no key can name it. `pattern` names the shape instead: an ORDERED LIST of
-- { surface, match, text }, resolved in the list's own order once every exact key has missed, with the
-- capture groups coming back through %1$s-style arguments that name the group by NUMBER.
--
-- HOW IT IS PROVED. A translation is invisible from Lua by construction -- every readback answers the
-- client's own English -- so :miss() cannot say WHICH of two matching patterns won: neither of them
-- misses. What the client does hand back is the SIZE of a label, and a label resizes itself to the very
-- raster it drew (haven.Label.settext). So a width is what was drawn, measured. This suite measures
-- each candidate display string ONCE, before it installs anything, by drawing that literal as a label's
-- own caption -- and then asserts the label drawn from the ENGLISH is exactly as wide as the candidate
-- it claims was chosen. The candidates are built out of "M" and "i" so that no two of them can collide.
--
-- WHY THE ORDER IS THE WHOLE POINT. Two patterns describing one string is a question a JSON object has
-- no way to answer, which is why this property is an array. So the same pair is installed twice, in
-- both orders, and the FIRST wins each time -- which is the assertion nothing but a list could make.

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

-- One composed string, and the string a second surface composes with two parts of very different widths.
local SRC   = "Level 3 of 8"
local FROM  = "From i to MMMMMMMM"
local PLAIN = "Level three of eight"     -- the same shape in words: no pattern here matches it

-- A `match` is a REGULAR EXPRESSION over the whole string, so a group is ( ) and a digit is \d -- and
-- in a Lua source the backslash is written twice, exactly as it is in the JSON file a translation ships.
local SHAPE = "Level (\\d+) of (\\d+)"   -- one shape, two groups, and two patterns written over it
local PAIR  = "From (\\S+) to (\\S+)"

-- What each of them draws as, spelt out -- the widths measured below are these strings' own.
local WIDE, NARROW = "A3MMMMMMMM", "B3i"
local EXACT = "Xi"
local BOTH, FIRST, SECOND = "[i|MMMMMMMM]", "[i]", "[MMMMMMMM]"

-- A pattern, as a document writes one: the key it goes under, the shape, and what to draw instead.
local function pat(surface, match, text)
  return { surface = surface, match = match, text = text }
end

-- What the client DRAWS for `src`, as the width of the label that drew it, plus that label's own
-- readback. A Label resizes itself to its raster, so the width IS the string that reached the screen.
local function drawn(src)
  local l = hafen.ui():label():text(src)
  local w, back = l:size().w, l:text()
  pcall(function() l:destroy() end)
  return w, back
end

-- Is (surface, text) in this catalogue's miss set? A miss is an object, so the search is a predicate.
local function missed(surface, text)
  return hafen.locale():miss():find(function(m)
    return (m:surface() == surface) and (m:text() == text)
  end) ~= nil
end

local function run()
  local loc = hafen.locale()

  local function stop(why, got)
    check(false, why, got)
    pcall(function() loc:release() end)
    finish()
  end

  if hafen.session():current() == nil then
    return stop("a character is logged in", "no current session -- run this in the world")
  end

  ---------------------------------------------------------------------------------------------------
  -- The ruler. Every candidate is measured with NOTHING installed, so each width is that literal
  -- string's own -- and two candidates that measured the same would make every check below vacuous.
  ---------------------------------------------------------------------------------------------------
  loc:release()                   -- ...whatever a previous run left behind: a ruler is measured bare
  local W = {}
  for _, s in ipairs({WIDE, NARROW, EXACT, BOTH, FIRST, SECOND}) do W[s] = drawn(s) end
  if (W[WIDE] == W[NARROW]) or (W[EXACT] == W[WIDE]) or (W[FIRST] == W[SECOND]) then
    return stop("the candidate strings measure apart, so a width tells them apart",
                W[WIDE] .. "/" .. W[NARROW] .. "/" .. W[EXACT] .. "/" .. W[FIRST] .. "/" .. W[SECOND])
  end

  local A = pat("default", SHAPE, "A%1$sMMMMMMMM")     -- both describe SRC...
  local B = pat("default", SHAPE, "B%1$si")            -- ...and the list says which of them wins

  ---------------------------------------------------------------------------------------------------
  -- The array's own order, both ways round. Installed the other way it is the other one that wins, so
  -- what decides is the POSITION -- not which pattern is longer, tighter or written first in the file.
  ---------------------------------------------------------------------------------------------------
  loc:load({ pattern = { A, B } }):install()
  local first = drawn(SRC)
  check(first == W[WIDE], "the first of two patterns describing one string wins", first)

  loc:load({ pattern = { B, A } }):install()
  local second = drawn(SRC)
  check(second == W[NARROW], "...and reversed it is the other one, so the order is the answer", second)

  ---------------------------------------------------------------------------------------------------
  -- Exact keys first, all of them, and only then the patterns. A string a key names never reaches one.
  ---------------------------------------------------------------------------------------------------
  loc:load({ text = { default = { [SRC] = EXACT } }, pattern = { A } }):install()
  local keyed = drawn(SRC)
  check(keyed == W[EXACT], "a string an exact entry names is never offered to a pattern", keyed)

  ---------------------------------------------------------------------------------------------------
  -- ...and a pattern is an answer, so what it matched is not a miss, while what it did not still is.
  ---------------------------------------------------------------------------------------------------
  loc:load({ pattern = { A } }):install()
  drawn(SRC)
  drawn(PLAIN)
  check((not missed("default", SRC)) and missed("default", PLAIN),
        "a pattern answers its string, and one no pattern matches is still a miss",
        tostring(missed("default", SRC)) .. "/" .. tostring(missed("default", PLAIN)))

  ---------------------------------------------------------------------------------------------------
  -- The arguments. An argument names its capture group by NUMBER, so a template that takes only the
  -- second one takes the second one -- which is what a positional argument is for.
  ---------------------------------------------------------------------------------------------------
  loc:load({ pattern = { pat("*", PAIR, "[%1$s|%2$s]") } }):install()
  local w, back = drawn(FROM)
  check(w == W[BOTH], "a two-group pattern substitutes both groups", w)
  check(back == FROM, "the model is not translated: :text() answers English under a pattern", back)

  loc:load({ pattern = { pat("*", PAIR, "[%1$s]") } }):install()
  local one = drawn(FROM)
  check(one == W[FIRST], "%1$s takes the first group", one)

  loc:load({ pattern = { pat("*", PAIR, "[%2$s]") } }):install()
  local two = drawn(FROM)
  check(two == W[SECOND], "%2$s takes the second", two)

  ---------------------------------------------------------------------------------------------------
  -- Three refusals, each at :load and each naming what went wrong -- and a document is parsed whole
  -- before a word of it is committed, so the catalogue installed above is still the one in force.
  ---------------------------------------------------------------------------------------------------
  refuses("a group that never closes is refused, naming it",
          function() loc:load({ pattern = { pat("default", "Level (\\d+ of", "x") } }) end,
          "is not a pattern this client can read")
  refuses("patterns given as an object are refused, naming the order an object has not got",
          function() loc:load({ pattern = { button = pat("default", SHAPE, "x") } }) end,
          "the patterns are an ORDERED LIST")
  refuses("an argument past the last capture group is refused",
          function() loc:load({ pattern = { pat("default", SHAPE, "%3$s") } }) end,
          "asks for capture group 3")
  check(loc:info().patterns == 1,
        ":info() counts what a catalogue matches, and three refusals left it holding what it held",
        loc:info().patterns)

  loc:release()
  finish()
end

hafen.slash():on("t102", run)   -- the only way in: a suite does not start itself
