-- 084.5 -- a mistake is loud. Self-checking suite.
--
-- The whole of it is the predicate, both ways: a mistake inside one has to reach the line that wrote it,
-- and a read that is merely not ready still must not spoil the call containing it. Everything else here
-- is a silence that became a refusal, and each is checked beside the older refusal it must not swallow.
--
-- It declares one ACCOUNT-scope saved variable, "probe", and plants a function in it: that is what the
-- store half is about, and the second [manual] line reads what the timer says about it 30 seconds later.

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

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it
-- did not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil), what, msg or "<no error>")
end

-- The same, and the message must NOT carry the other sentence -- the older refusal is still the one that
-- fires, rather than being swallowed by the one this task added.
local function refusesOnly(what, fn, wantMsg, forbidMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil)
        and (msg:find(forbidMsg, 1, true) == nil), what, msg or "<no error>")
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  -- 1. THE PREDICATE RAISES. hafen.font() is a collection the suite fills itself -- :get("serif") interns
  -- a member -- so the raising half needs no world, and it runs over all three verbs that share the one
  -- filter. If the collection were empty the predicate would never run and this reads "<no error>".
  hafen.font():get("serif")
  local coll, boom, missed = hafen.font(), "084.5 predicate mistake", {}
  for _, v in ipairs({"list", "count", "find"}) do
    local msg = said(function() coll[v](coll, function() error(boom) end) end)
    if (msg == nil) or (msg:find(boom, 1, true) == nil) then
      missed[#missed + 1] = ":" .. v .. " -> " .. (msg or "<no error>")
    end
  end
  check(#missed == 0, "a mistake inside a predicate raises out of :list, :count and :find, saying what it said",
        table.concat(missed, " | "))
  local typo = said(function() coll:list(function(h) return h:nosuchverb() end) end)
  check(typo ~= nil, "a TYPO inside a predicate raises too, instead of reading as an empty result", "<no error>")

  -- 2. THE TREE BEFORE THE KEY. The live check first, so the refusal below is about a widget that WAS in
  -- the tree answering that very key -- otherwise it would pass on a widget that was never placed.
  local win = hafen.ui():window():title("084.5"):visible(false)
  local b = hafen.ui():button():parent(win):position(0, 0):size(80):text("x")
  local sub = b:on("Pressed", function() end)
  check(sub ~= nil, "the button is in the tree and answers Pressed", sub)
  sub:off()
  b:destroy()
  refusesOnly("w:on(\"Pressed\", fn) on a widget it destroyed names the TREE, not the key",
              function() b:on("Pressed", function() end) end,
              "no longer in the tree", "has no event")
  local b2 = hafen.ui():button():parent(win):position(0, 24):size(80):text("y")
  refuses("...and a LIVE widget still names the key it has not got",
          function() b2:on("Nosuchevent", function() end) end, "has no event 'Nosuchevent'")

  -- 3. A FACE CARRYING A COLOUR, on a client surface.
  local face = hafen.font():get("serif"):derive():size(11):color(255, 0, 0)
  refuses("w:rule():font(h) refuses a face carrying a colour, naming rule:color",
          function() b2:rule():font(face) end, "rule:color(r, g, b)")
  local plain = hafen.font():get("serif"):derive():size(11)
  local took = pcall(function() b2:rule():font(plain) end)
  check(took, "...and the same face without a colour is taken", took)
  win:destroy()

  -- 4. THE STORE. flush() still refuses, naming the path -- and the function stays planted, which is what
  -- the timer 30 seconds from now has to say something about.
  hafen.store():get("probe").fn = function() end
  refuses("hafen.store():flush() still refuses a function, naming the path it sits at",
          function() hafen.store():flush() end, "\"probe\".fn holds a function")

  -- 5. THE REFUSAL THIS TASK MUST NOT SWALLOW: an account the client holds no session for is still the
  -- account's refusal, not the new no-screen one.
  refusesOnly("hafen.session():current(<an account not logged in>) still names the account",
              function() hafen.session():current(hafen.session():get("nobodyhere")) end,
              "nobodyhere", "no screen of its own")

  -- 6. THE OTHER HALF OF THE PREDICATE, which needs a live set: a read that may not have arrived must
  -- COMPLETE. Retry on a timer for a bounded window and score what the run reached.
  local tries, done = 0, false
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    if (not done) and s and s:exists() then
      local gobs = s:world():gob()
      if gobs:count() > 0 then
        done = true
        local ok, res = pcall(function()
          return gobs:list(function(g) return ((g:name() or ""):find("x")) ~= nil end)
        end)
        check(ok, "a predicate reading a name that may not have arrived completes over the whole gob set",
              ok and "" or res)
        local msg = said(function() gobs:list(function() error(boom) end) end)
        check((msg ~= nil) and (msg:find(boom, 1, true) ~= nil),
              "a mistake in a predicate over that same live set raises out of :list", msg or "<no error>")
      end
    end
    if done or (tries >= 20) then
      t:cancel()
      if not done then
        check(false, "the predicate pair over a live gob set", "no session with gobs reached in 10s")
      end
      manualCheck("with a second character still arriving, or mid character-switch, run from :lua --"
                  .. " hafen.session():current(hafen.session():get(\"<that account>\"))",
                  "it RAISES, naming SessionSelected, rather than doing nothing")
      manualCheck("stay logged in for 30 more seconds and watch the console (the function planted above"
                  .. " is still in \"probe\")",
                  "one line naming \"probe\".fn as saved-as-text, and the file still written")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end
  end)
end

hafen.slash():register("t084-5", run)   -- the only way in: a suite does not start itself
