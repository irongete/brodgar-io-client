-- 115.2 — an addon declares an option. Self-checking suite.
--
-- Type :t115 anywhere, in the world or on the login screen: nothing here needs a session. The six
-- rows are declared when this file runs, not when the command does, so running it twice in one
-- session asserts against the rows that are already there rather than declaring them again.
--
-- The one [manual] line is the round trip a single run cannot see: the values this run leaves are
-- what the next run must find. Run it, reload, run it again, and read the two lines against each
-- other.

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

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the
-- strip has to allow both shapes or the message is scored with its own location glued to the front.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- ---- the declaration ------------------------------------------------------------------------
-- At file scope, which is where an addon declares its options: the AddOns page has to list them
-- whether or not anybody has run the command. It is protected so that a refusal here cannot take
-- the console command down with it -- an addon whose file body throws never registers anything.

local CHOICES = {"alpha", "beta", "gamma"}

local opts = hafen.client():options():addon()
local flagB, flag, size, mode, note, run1, state

local declared, declareErr = pcall(function()
  flagB = opts:boolean("flag"):label("Show the flag"):tooltip("a boolean row"):default(true)
  flag  = flagB:add()
  size  = opts:number("size"):label("Size"):tooltip("a slider"):range(1, 10):default(5):add()
  mode  = opts:choice("mode"):label("Mode"):tooltip("a dropdown"):choices(CHOICES):default("alpha"):add()
  note  = opts:text("note"):label("Note"):tooltip("a text field"):default("0"):add()
  run1  = opts:button("run"):label("Run it"):tooltip("a button")
              :press(function() hafen.log():write("the button ran") end):add()
  state = opts:label("state"):tooltip("a stated line"):text("idle"):add()
end)

-- The value this run writes over what it found: derived from what it found, so no two runs in a row
-- write the same thing and the [manual] comparison cannot pass by accident.
local function nextChoice(v)
  for i, c in ipairs(CHOICES) do
    if c == v then return CHOICES[(i % #CHOICES) + 1] end
  end
  return CHOICES[1]
end

local function four()
  return tostring(flag:value()) .. " | " .. tostring(size:value()) .. " | "
         .. tostring(mode:value()) .. " | " .. tostring(note:value())
end

local function run()
  if not declared then
    check(false, "the six rows are declared", why(declareErr))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  check(opts:option():count() == 6, "this addon declared six rows and the collection holds six",
        opts:option():count())
  check(flag:default() == true and size:default() == 5 and mode:default() == "alpha"
        and note:default() == "0" and opts:option():get("size") == size,
        "the four that carry a value answer the defaults they declared, and :get(name) is the row itself",
        tostring(flag:default()) .. " | " .. tostring(size:default()) .. " | "
        .. tostring(mode:default()) .. " | " .. tostring(note:default()))

  local entryFlag, entrySize = flag:value(), size:value()
  local entryMode, entryNote = mode:value(), note:value()
  check(type(entryFlag) == "boolean" and type(entrySize) == "number"
        and type(entryMode) == "string" and type(entryNote) == "string",
        "the four values on entry: " .. four(), four())

  -- Changed, both halves: it fires on a move carrying the new value, and it is silent on a write of
  -- the value already held -- which is what lets a control re-write what it read without a loop.
  local fired, carried = 0, nil
  local sub = size:on("Changed", function(v) fired = fired + 1; carried = v end)
  local moved = (entrySize < 10) and (entrySize + 1) or 1
  size:value(moved)
  check(fired == 1 and carried == moved, "Changed fires once on a move, carrying the new value",
        fired .. " fire(s), carrying " .. tostring(carried))
  size:value(moved)
  check(fired == 1, "Changed is silent on a write of the value already held", fired .. " fire(s)")
  sub:off()

  -- The write and the read that follows it, on all four -- and these are the values the next run has
  -- to find, so they are written last and reported as they stand.
  flag:value(not entryFlag)
  size:value((entrySize < 10) and (entrySize + 1) or 1)
  mode:value(nextChoice(entryMode))
  note:value(tostring(((tonumber(entryNote) or 0) + 1) % 1000))
  check(flag:value() == (not entryFlag) and size:value() == ((entrySize < 10) and (entrySize + 1) or 1)
        and mode:value() == nextChoice(entryMode)
        and note:value() == tostring(((tonumber(entryNote) or 0) + 1) % 1000),
        "each write is answered by the read that follows; the four values leaving: " .. four(), four())

  -- The two that carry none say so, and say what they carry instead.
  local bOk, bErr = pcall(function() return run1:value() end)
  local lOk, lErr = pcall(function() return state:value() end)
  bErr, lErr = bOk and "<no error>" or why(bErr), lOk and "<no error>" or why(lErr)
  check((not bOk) and (not lOk) and bErr:find(":press(fn)", 1, true) ~= nil
        and lErr:find(":text(s)", 1, true) ~= nil and state:text() == "idle",
        "value() is refused on the button and on the label, each naming what it does carry",
        bErr .. " // " .. lErr)

  refuses("a row that carries a value is refused with no :default, naming the setter",
          function() opts:boolean("nodefault"):label("x"):add() end, "declare it with :default(v)")
  refuses("a second row under a name already declared is refused, naming the row it has",
          function() opts:boolean("flag"):label("x"):default(true):add() end,
          "already declared an option named 'flag'")
  refuses("a default outside the range its own row declared is refused, naming both ways out",
          function() opts:number("bad"):label("x"):range(1, 10):default(50):add() end,
          "outside the range 1..10 it declared")
  refuses("a setter after :add() is refused, naming the rule",
          function() flagB:label("too late") end, "has already been added")
  refuses(":press on a row that is not a button is refused, naming the builder that takes it",
          function() opts:boolean("nope"):press(function() end) end, "only a button row takes it")

  manualCheck("reload this addon and type :t115 again",
              "the new run's \"values on entry\" line reads exactly this run's \"values leaving\" line")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t115", run)   -- the only way in: a suite does not start itself
