-- 085.4 -- the answer is the thing. Self-checking suite.
--
-- Three reads stood for a thing instead of being it. hafen.time():season() was the raw index the server
-- publishes, so == "winter" was false forever. s:study():summary() was a plain table where the fight
-- summary beside it is a live object, so one word gave two kinds of answer. w:severity() was a string
-- that is usually a number, so (tonumber(w:severity()) or 0) was the only correct thing to write.
--
-- WHAT PROVES A LIVE OBJECT is not that the verbs answer -- a table with three functions in it would do
-- that too. It is userdata with a CLOSED vocabulary: sum.total raises naming what the summary answers,
-- the dotted sum.lp() raises naming the colon call, and sum == sum says it is interned. All three are
-- asserted, because any one alone is met by something that is not an object.
--
-- Study, wounds and the clock all need the world, and the character sheet streams in a beat behind it, so
-- the run waits for a bounded window and scores what it reached rather than failing on what had not
-- arrived. The one check that needs nothing -- a session nobody is logged in as -- runs either way.

local pass, fail, manual = 0, 0, 0

-- The four seasons, in the order the bridge maps the server's index through. The suite carries its own
-- copy so it can print the raw index beside the name, which is what the [manual] line below is asking
-- about: which index carries which name is nowhere in the client's source.
local SEASONS = {"spring", "summer", "autumn", "winter"}

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

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING every word the reader needs.
local function refuses(what, fn, ...)
  local msg = said(fn)
  local ok = (msg ~= nil)
  for _, want in ipairs({...}) do
    ok = ok and (msg:find(want, 1, true) ~= nil)
  end
  check(ok, what, msg or "<no error>")
end

-- ---- the season is one of four names ----------------------------------------------------------------

local function season()
  local s = hafen.time():season()
  local at
  for i, name in ipairs(SEASONS) do
    if name == s then at = i - 1 end                 -- the bridge indexes from 0, as the server does
  end
  check(at ~= nil, "hafen.time():season() is one of the four season names",
        (s == nil) and "nil -- no astronomy update yet" or (type(s) .. " " .. tostring(s)))

  manualCheck("season() says '" .. tostring(s) .. "', the server's index " .. tostring(at)
                .. ". Open the calendar, top-right corner",
              "report which season the calendar shows")
end

-- ---- the study summary is a live object -------------------------------------------------------------

local function study(s)
  local sum = s:study():summary()
  if sum == nil then
    check(false, "s:study():summary() answers while the study tab is up",
          "nil -- the character sheet had not streamed in")
    return
  end

  check(type(sum) == "userdata", "s:study():summary() is an object, not a table", type(sum))

  local bad = {}
  for _, e in ipairs({{"lp", sum:lp()}, {"attention", sum:attention()}, {"cost", sum:cost()}}) do
    if type(e[2]) ~= "number" then bad[#bad + 1] = ":" .. e[1] .. "() is " .. type(e[2]) end
  end
  check(#bad == 0, "sum:lp(), sum:attention() and sum:cost() are numbers (3/3)", table.concat(bad, ", "))

  local i = sum:info()
  check((sum:exists() == true) and (type(i) == "table") and (type(i.lp) == "number")
          and (type(i.attention) == "number") and (type(i.cost) == "number"),
        "sum:exists() is true and sum:info() carries lp, attention and cost",
        tostring(sum:exists()) .. " / " .. tostring(i))

  -- What makes it an object rather than the table it was: `lp` is a VERB now, so the dotted read hands
  -- back the function and calling it raises naming the colon call -- where the table answered a number.
  refuses("sum.lp() raises naming sum:lp()", function() return sum.lp() end, "sum:lp()")
  refuses("sum.total raises naming what the summary answers",
          function() return sum.total end, ":lp()", ":attention()", ":cost()")

  -- ...and what makes it the SAME object: interned on that character's tab, like every other handle.
  check(s:study():summary() == s:study():summary(),
        "s:study():summary() == s:study():summary()", tostring(s:study():summary()))

  -- Arity is the verb, on this summary as on the fight one: the totals are verbs on what it hands back.
  refuses("s:study():summary(1) refuses, naming the arity",
          function() s:study():summary(1) end, "takes no arguments")
end

-- ---- a wound's magnitude is two reads ---------------------------------------------------------------
--
-- Scored over whatever wounds the character is carrying, and the labels that are NOT numbers are what the
-- second [manual] line reports: the split exists for them, and a run may well meet none.
local function wounds(s)
  local seen, parsed, agree, words, wrong, apart = 0, 0, 0, {}, {}, {}
  for _, w in ipairs(s:wound():list()) do
    seen = seen + 1
    local sev, lab = w:severity(), w:label()
    if (sev ~= nil) and (type(sev) ~= "number") then
      wrong[#wrong + 1] = "w:severity() is a " .. type(sev)
    end
    if (lab ~= nil) and (type(lab) ~= "string") then
      wrong[#wrong + 1] = "w:label() is a " .. type(lab)
    end
    local n = (type(lab) == "string") and tonumber(lab) or nil
    if n ~= nil then
      parsed = parsed + 1
      if n == sev then agree = agree + 1 else apart[#apart + 1] = lab .. " ~= " .. tostring(sev) end
    elseif lab ~= nil then
      words[#words + 1] = tostring(lab)
    end
  end

  check((seen > 0) and (#wrong == 0),
        ("w:severity() is a number or nil and w:label() is a string (%d/%d wounds)")
          :format(seen - #wrong, seen),
        (#wrong > 0) and table.concat(wrong, ", ")
          or "no wounds on this character -- take a scratch and re-run")
  check((#apart == 0) and (parsed > 0),
        ("tonumber(w:label()) == w:severity() wherever the label parses (%d/%d)"):format(agree, parsed),
        (#apart > 0) and table.concat(apart, ", ") or "no label on this character parsed as a number")

  manualCheck(seen .. " wound(s); the label(s) that are not numbers: "
                .. ((#words > 0) and table.concat(words, ", ") or "none"),
              "report whether any wound shows one -- \"they are all numbers\" is a complete answer")
end

-- ---- the refusal: no window is nil, not an error ----------------------------------------------------
--
-- The whole content of the row is that the two summaries answer the same way. s:fight():summary() is nil
-- for a session with no window, so this one must be too -- and must not raise on the way there.
local function absent()
  local sum, err
  err = said(function() sum = hafen.session():get("nobodyhere"):study():summary() end)
  check((err == nil) and (sum == nil),
        "a session nobody is logged in as answers nil from :study():summary(), and does not raise",
        err or ("a " .. type(sum)))
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, arg)
  local ok, err = pcall(fn, arg)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(err))
  end
end

local function run()
  pass, fail, manual = 0, 0, 0
  local tries = 0
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local up = s and s:exists() and (s:study():summary() ~= nil) and (hafen.time():season() ~= nil)
    if (tries < 24) and not up then return end       -- the sheet streams in a beat behind the world
    t:cancel()
    section("season", season)
    if s and s:exists() then
      section("study", study, s)
      section("wound", wounds, s)
    else
      check(false, "a character in the world", "none reached in 12s -- log in and re-run")
    end
    section("absent session", absent)
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t085-4", run)   -- the only way in: a suite does not start itself
