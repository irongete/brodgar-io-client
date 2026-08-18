-- 076.1 — the session is a thing you can name. Self-checking suite.
--
-- What it proves: hafen.session() is one collection object over interned Session refs keyed by the
-- ACCOUNT name; :current() is the session on screen and agrees with hafen.player() about which
-- character that is; a ref for an account the client does not hold still names it, dead; and the
-- four arity refusals name what to write instead.

local pass, fail, manual = 0, 0, 0
local log = hafen.log()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log:write("[pass] " .. what)
  else
    fail = fail + 1
    log:write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log:write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Every account this suite has ever seen live, across runs. A name that was here and is not any
-- more is what the drop below is checked against: the ref outliving the session is the whole point
-- of keying on the account, so the check is made against an account that really ended.
local seen = {}

local function run()
  pass, fail, manual = 0, 0, 0     -- one verdict per run: the suite is re-run, `seen` is what carries over
  local sessions = hafen.session()
  check(sessions == hafen.session(), "the collection is one object every call")

  local list = sessions:list()
  check(sessions:count() == #list, "count() is how many list() carries",
        sessions:count() .. " vs " .. #list)

  local cur = sessions:current()
  local user = cur and cur:user()
  check((user ~= nil) and (user ~= ""), "the session on screen names its account", user)

  -- The account name is the whole of the ref, so both doors onto it hand back the SAME object, and
  -- so does the membership array.
  local inList = false
  for i = 1, #list do
    if list[i] == cur then inList = true end
    local u = list[i]:user()
    if u then seen[u] = true end
  end
  check((user ~= nil) and (sessions:get(user) == cur) and (sessions:get(user) == sessions:get(user))
        and inList, "get(user), current() and list() are one interned ref", inList)

  -- THE check: two doors onto the drawn character agreeing is what says the address resolves.
  local chr, drawn = cur and cur:character(), hafen.player():name()
  check((chr ~= nil) and (chr == drawn),
        "current():character() is the character on screen (" .. tostring(drawn) .. ")",
        tostring(chr) .. " vs " .. tostring(drawn))

  check((user ~= nil) and (sessions:count(user) >= 1) and (sessions:find(user) == cur),
        "a string filter matches the account (" .. tostring(user) .. ")",
        user and sessions:count(user))

  local info = cur and cur:info()
  check((info ~= nil) and (info.user == user) and (info.character == chr)
        and (info.exists == true) and (info.current == true),
        "info() names the account and the character, and reports it live and on screen",
        info and (tostring(info.user) .. "/" .. tostring(info.character)))

  -- A name the client does not hold is still a ref: that is what a SessionDestroyed handler keeps.
  -- An account this suite saw on an earlier run and that is gone now is the real case; with none,
  -- a name nobody could be logged in as proves the same thing.
  local ghost = "no-such-account"
  for name in pairs(seen) do
    if sessions:count(function(s) return s:user() == name end) == 0 then ghost = name end
  end
  local gone = sessions:get(ghost)
  check((gone:user() == ghost) and (gone:exists() == false) and (gone:info().exists == false),
        "an account the client does not hold still names itself, dead (" .. ghost .. ")",
        gone:exists())

  refuses("# is refused on the collection", function() return #hafen.session() end, ":list()")
  refuses("get() with no account is refused naming it", function() return hafen.session():get() end,
          "user")
  refuses("get() by anything but the account name is refused",
          function() return hafen.session():get(42) end, "ACCOUNT name")
  refuses("current() refuses an argument, saying it reads",
          function() return hafen.session():current(cur) end, "takes no arguments")

  manualCheck("with two sessions up, tab to the other character and run :t076-1 again",
              "count() is 2 both times, and the account and character named above are the ones"
              .. " you tabbed to")
  manualCheck("drop one with :session drop USER, then run :t076-1 again",
              "the dead-account line names the account you dropped, and passes")

  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t076-1", run)   -- the only way in: a suite does not start itself
