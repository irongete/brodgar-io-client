-- 123.1 — the layer stops declaring what nothing calls. Self-checking suite.
--
-- The task deletes eighty lines from Sessions.java: seven methods nothing called, one unreachable
-- guard, and three visibilities narrowed. A deletion has no surface of its own, so there is nothing
-- here to demonstrate -- this suite is the NET UNDER WHAT MUST NOT CHANGE, and every line of it
-- passed before the cut as well as after. That is the point: it is run against the new jar and it
-- says whether hafen.session() still answers exactly as it did.
--
-- Three of the seven -- Member.anchorpos, toanchor and tomember -- read Member.offset()'s field
-- WITHOUT the anchor correction Sessions.buildplaced applies at the call site. With them gone,
-- buildplaced is the only caller and the correction is where it already was. The path that runs
-- buildplaced, and therefore offset(), is a change of screen, so the checks below take the screen to
-- a second character and give it straight back: if the offset were being read uncorrected anywhere,
-- the merged patch the [manual] line looks at is where it would show.
--
-- This suite leaves the screen on the character it found it on, and writes nothing else.

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
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The set of keys a table carries, as a sorted "a, b, c" -- LuaJ's string.format pads nothing, so
-- every number and every list in this file is built by hand.
local function keys(t)
  local ks = {}
  for k in pairs(t) do ks[#ks + 1] = tostring(k) end
  table.sort(ks)
  return table.concat(ks, ", ")
end

local function users(list)
  local ns = {}
  for i, s in ipairs(list) do ns[i] = s:user() end
  return table.concat(ns, ", ")
end

local function run()
  pass, fail, manual = 0, 0, 0
  local live = hafen.session()

  local cur = live:current()
  check(cur ~= nil, "the client draws a character, and it is the one the reads below are performed on ("
        .. (cur and cur:user() or "none") .. ")", "the login screen")
  if cur == nil then
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  -- list / count / find agree with each other.
  local list = live:list()
  local u = cur:user()
  local byname = live:find(u)
  check((#list >= 1) and (live:count() == #list) and (live:find() == list[1])
        and (live:count(u) >= 1) and (byname ~= nil) and (byname:user():find(u, 1, true) ~= nil),
        "list, count and find agree with each other (" .. #list .. " live: " .. users(list) .. ")",
        "count " .. live:count() .. ", #list " .. #list)

  -- get addresses, and every hand-back of one login is the SAME object.
  local ok = (live:get(u) == cur) and (live:get(u) == live:get(u))
  for _, s in ipairs(list) do
    if live:get(s:user()) ~= s then ok = false end
  end
  check(ok, "get addresses one login, interns it, and hands back the very entry in list()",
        "get(" .. u .. ") is not the drawn session")

  -- The three reads on a Session itself.
  local ch = cur:character()
  check((type(u) == "string") and (u ~= "") and (cur:exists() == true)
        and ((ch == nil) or (type(ch) == "string")),
        "user, character and exists answer for the drawn session (" .. u .. " playing "
        .. tostring(ch) .. ")", "user " .. tostring(u) .. ", exists " .. tostring(cur:exists()))

  -- info() is the one snapshot, and it carries exactly these keys.
  local want = "current, exists, user"
  if ch ~= nil then want = "character, current, exists, user" end
  local info = cur:info()
  check(keys(info) == want, "info() carries exactly " .. want, keys(info))
  check((info.user == u) and (info.exists == true) and (info.current == true)
        and (info.character == ch),
        "and every field of it says what its own verb says", keys(info))

  -- The screen. This is the path that runs buildplaced, hence Member.offset().
  local other = nil
  for _, s in ipairs(list) do
    if (s ~= cur) and (s:character() ~= nil) then other = s break end
  end
  check(other ~= nil, "a second character is in the world, so the screen move below is a real move ("
        .. (other and other:user() or "only " .. u) .. ")", "one session")

  local target = other or cur
  live:current(target)
  check((live:current() == target) and (target:info().current == true)
        and ((other == nil) or (cur:info().current == false)),
        "the screen goes to " .. target:user() .. ", current() reads it back, and info().current follows",
        live:current() and live:current():user() or "nil")

  live:current(cur)
  check((live:current() == cur) and (cur:info().current == true),
        "and it comes straight back to " .. u, live:current() and live:current():user() or "nil")

  local after = live:list()
  ok = (#after == #list) and (live:count() == #list)
  for i, s in ipairs(after) do
    if (s ~= list[i]) or (live:get(s:user()) ~= s) then ok = false end
  end
  check(ok, "and the collection is the one it was, still interned, after the screen moved twice",
        #after .. " sessions: " .. users(after))

  -- Two refusals, each naming what is wrong.
  refuses("a number is refused as the key, naming the account name that is one",
          function() live:get(42) end, "the ACCOUNT name")
  check(live:get("nobody"):exists() == false,
        "and an account nobody is logged in as is still an object, answering exists() false",
        live:get("nobody"):exists())
  refuses("the screen refuses a session the client does not hold, naming it",
          function() live:current(live:get("nobody")) end,
          "the client holds no session for the account")

  manualCheck("with " .. (other and other:user() or "a second character")
              .. " standing far from " .. u .. ", look at the merged patch its ground makes",
              "its ground and its objects are exactly where they stood before this suite ran, not"
              .. " shifted by a session's offset")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t123", run)   -- the only way in: a suite does not start itself
