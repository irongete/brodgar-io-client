-- 129.4 -- the checker resolves what a helper built, and fails on nothing. Self-checking suite: run with :t129.
--
-- The task is a tool. tools/refusalverbs.py matched a literal `put("…",` and so read ONE of the twenty-five
-- refusal rows Refusal.java declares: twenty-four are built by a helper -- uiKept(verb, why), sixteen of
-- those from a loop over control names -- and a key built by concatenation is not a literal. The scanner
-- now evaluates the helper and reads all twenty-five, and zero rows is red rather than a green over nothing.
--
-- A tool's proof is the tool, and it is run beside this. What a suite adds is the other end: that the rows
-- the scanner now reads are rows that FIRE, and fire saying which half the verb is in. So it drives one of
-- each shape the scanner reads -- an explicit uiKept call, one of the sixteen the loop builds, and the one
-- row still written as a literal put -- and asserts a spelling no row answers for still meets the generic
-- refusal, so a row firing is not mistaken for the fallback.
--
-- Then it asks the door each message points at for the very verb it promised. That is the half the static
-- check cannot do: every one of these rows promises a SECTION verb, a section's vocabulary is not a
-- closedIndex one, and refusalverbs resolves none of them and says so where it prints its count.
--
-- That second half runs on the ENGINE STEP rather than in the console handler, because it builds in the
-- addon layer and a console line already holds the tree of the character it was typed in. The rows above
-- it fire from an __index and touch no tree, so they stay where the command lands.

local pass, fail = 0, 0

local function say(line) hafen.log():write(line) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    say("[pass] " .. what)
  else
    fail = fail + 1
    say("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg" -- a SPACE where a Lua error has a colon -- and the
-- sandbox loads a DebugLib for its instruction hard-stop, so a caught error carries a traceback after it.
-- A verdict is ONE line, so both go.
local function why(err)
  err = tostring(err):gsub("\nstack traceback:.*", "")
  return (err:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, fail saying every one of `want`, and say none of `absent`.
local function refuses(what, fn, want, absent)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  for i = 1, #(absent or {}) do
    if err:find(absent[i], 1, true) then said = false end
  end
  check(said, what, err)
end

-- A call that must go through: the value it hands back, or the refusal it raised instead.
local function answers(what, fn, wants)
  local ok, v = pcall(fn)
  check(ok and wants(v), what, ok and tostring(v) or ("raised: " .. why(v)))
  return ok and v or nil
end

-- Build one in the addon layer, and give it back on the same verdict line: a surface left standing is a
-- suite mutating state it does not own, and :destroy() is as much of the promise as the constructor is.
local function builds(what, fn)
  local ok, w = pcall(fn)
  if not ok then check(false, what, "raised: " .. why(w)); return end
  if w == nil then check(false, what, "nil"); return end
  local gone, err = pcall(function() w:destroy() end)
  check(gone, what, gone and "" or ("built, but :destroy() raised: " .. why(err)))
end

-- The clause every uiKept row ends with: the session's half, named verb by verb.
local SEVEN = "The session's half of hafen.ui is the widgets THE CLIENT put up"
local NAMED = ":match, :matchAll, :on, :root, :node, :inventory and :equipment"

-- The half that BUILDS, and so the half that must hold no tree monitor. A console line runs holding the
-- monitor of the tree of the character whose console it was typed in, and writing a widget of the addon
-- layer there is the second tree, which is refused; the engine step holds none, and is the door that
-- refusal names. Everything above this touches no tree at all: those rows fire from an __index.
local function live(s)
  builds("hafen.ui():window() builds one and gives it back, so the row's promise is a live spelling",
         function() return hafen.ui():window() end)
  builds("hafen.ui():button() mints one, so the sixteen promise a live spelling too",
         function() return hafen.ui():button() end)
  answers("hafen.ui():scale() answers a number, whole and on the client's own door",
          function() return hafen.ui():scale() end, function(v) return type(v) == "number" end)
  -- :root() and not :match(sel): :match refuses when a selector matches more than one, so what it proves
  -- depends on what is open on screen. The tree's own top takes no argument and is there either way.
  answers("s:ui():root() answers, so that half is a live door and not only a list",
          function() return s:ui():root() end, function(v) return v ~= nil end)
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

local function run()
  local s = hafen.session():current()
  if not s then
    say("[fail] this suite needs a character logged in -- got: no current session")
    return
  end

  -- ---- the rows fire, one of each shape the scanner reads ------------------------------------------
  refuses("s:ui():window() fires its row: hafen.ui():window() and the session's own seven",
          function() return s:ui():window() end,
          {"s:ui():window(", "hafen.ui():window(", "stand in two trees", SEVEN, NAMED})
  refuses("s:ui():button() fires too -- one of the sixteen rows a loop over control names builds",
          function() return s:ui():button() end,
          {"s:ui():button(", "hafen.ui():button(", "mints a control of YOURS, in the addon layer", NAMED})
  refuses("s:ui():scale() carries the reason its own call site wrote, not another row's",
          function() return s:ui():scale() end,
          {"hafen.ui():scale(", "is the device factor the client is running at"},
          {"mints a control of YOURS"})
  refuses("hafen.console():run() fires the one row still written as a literal put",
          function() return hafen.console():run("lo") end,
          {"hafen.console() has no verb 'run'", "s:console():run(line) is the verb",
           "the commands your addon REGISTERS"})
  -- The generic refusal builds its list off the very methods table it guards, so this is the session half's
  -- vocabulary read from the table rather than from prose -- and it is the seven the rows above name.
  refuses("a spelling no row answers for meets the generic refusal, listing the very seven the rows name",
          function() return s:ui():nosuchverb() end,
          {"session:ui() has no verb 'nosuchverb'", ":match()", ":matchAll()", ":on()", ":root()",
           ":node()", ":inventory()", ":equipment()"},
          {"hafen.ui():", "does not exist", ":window()"})

  -- ---- and the door each message points at answers the verb it promised ----------------------------
  -- Onto the engine step, holding no tree: see `live` above for why a console line cannot do this half.
  hafen.timer():after(0, function() live(s) end)
end

hafen.console():on("t129", run)   -- the only way in: a suite does not start itself
