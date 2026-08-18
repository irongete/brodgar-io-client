-- 077.2 — the roster, and what a permission key names. Self-checking suite.
--
-- What it proves: kin and party are reached through a Session and nowhere else; each is minted once
-- per (addon, session) and interned on the handle; a member is interned on the CHARACTER as well as
-- its key, so one buddy id reached through two sessions is two people; a Session the client does not
-- hold answers nil-shaped rather than raising; both loose spellings are retired, naming where they
-- went; and a protected kin verb keeps the ONE key it has -- refusing by key for the four this addon
-- did not declare, and reaching the ARGUMENT check for the one it did, which is the grant proved
-- without anything leaving the client.
--
-- This addon declares "kin.add" and nothing else, so enabling it raises the consent dialog. That is
-- part of the verification: the four verbs it did NOT ask for must still refuse.

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
local function refuses(what, fn, ...)
  local wants = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local said = not ok
  for _, want in ipairs(wants) do
    if err:find(want, 1, true) == nil then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log:write("[manual] " .. step .. " -- expect: " .. expect)
end

local function count(t) local n = 0 for _ in ipairs(t) do n = n + 1 end return n end

-- The four kin writes this addon deliberately did NOT declare, each with the key it must name. The
-- gate is the first statement of every one of them, so none of these reaches a roster or the server:
-- the id below is nobody's, and the refusal arrives before anything looks for it.
local UNGRANTED = {
  { "kin:rename", "kin.rename", function(k) k:rename("nope") end },
  { "kin:group",  "kin.group",  function(k) k:group(3) end },
  { "kin:endKin", "kin.endKin", function(k) k:endKin() end },
  { "kin:forget", "kin.forget", function(k) k:forget() end },
}

-- ---------------------------------------------------------------- the scored body

local function body(s)
  local kin, party = s:kin(), s:party()

  -- 1. Both sections are minted once for this (addon, session) pair and kept on the handle.
  check((s:kin() == kin) and (s:party() == party),
        "kin and party are interned on the Session handle",
        tostring(s:kin() == kin) .. "/" .. tostring(s:party() == party))

  -- 2. Both answer a list, or are honestly empty. Neither is ever an error.
  local kl, pl = kin:list(), party:list()
  check((type(kl) == "table") and (type(pl) == "table"),
        ("both rosters answer a list (%d kin, %d party)"):format(count(kl), count(pl)),
        tostring(kl) .. "/" .. tostring(pl))

  -- 3. :count agrees with what :list built, on both.
  check((kin:count() == count(kl)) and (party:count() == count(pl)),
        "the counts agree with the lists",
        kin:count() .. "/" .. count(kl) .. " " .. party:count() .. "/" .. count(pl))

  -- 4. A kin is interned per (character, id): :get(id) is never nil, so this holds with an empty
  --    roster too. Reached through two DIFFERENT sessions the same number is two objects, because on
  --    two characters it is two people -- which is the whole reason the account is half the handle.
  local ghost = hafen.session():get("no-such-account")
  check((kin:get(7) == kin:get(7)) and (kin:get(7) ~= ghost:kin():get(7)),
        "a kin is interned on the character as well as the id",
        tostring(kin:get(7) == kin:get(7)) .. "/" .. tostring(kin:get(7) ~= ghost:kin():get(7)))

  -- 5. A Session the client does not hold is still an address: both sections answer nil-shaped.
  local ok, agreed = pcall(function()
    return (count(ghost:kin():list()) == 0) and (count(ghost:party():list()) == 0)
           and (ghost:party():leader() == nil) and (ghost:kin():get("Bob") == nil)
           and (ghost:kin():get(7):exists() == false)
  end)
  check(ok and agreed, "a session the client does not hold answers nil-shaped, not an error",
        ok and "reads disagreed" or agreed)

  -- 6. The hard cut: reading either loose spelling AT ALL throws, naming the address.
  for _, name in ipairs({ "kin", "party" }) do
    refuses("hafen." .. name .. " is retired, naming the Session address",
            function() return hafen[name] end,
            "session:" .. name .. "()", "hafen.session():current()")
  end

  -- 8. Every key this addon did not declare refuses NAMING THE VERB AND THE KEY, before anything is
  --    sent -- and it does so whichever character the verb was addressed at. One line for the four,
  --    naming the first that misbehaved: a suite that spent four lines here would have no room left.
  local nobody = kin:get(2147483647)
  local gated, leak = true, nil
  for _, u in ipairs(UNGRANTED) do
    local ok3, err = pcall(u[3], nobody)
    err = ok3 and "<no error>" or tostring(err)
    if ok3 or (err:find(u[1], 1, true) == nil) or (err:find(u[2], 1, true) == nil) then
      gated, leak = false, u[1] .. " -> " .. err
    end
  end
  check(gated, "every kin write this addon did not declare refuses, naming its verb and its key", leak)

  -- 9..10. The one key this addon DID declare: the gate is passed and the ARGUMENT check is what
  --        answers. Reaching it IS the grant, and nothing here reaches the server -- neither a
  --        non-string nor an empty string is ever sent as a hearth secret.
  refuses("s:kin():add refuses a non-string secret, so the grant was honoured",
          function() s:kin():add(true) end, "secret must be a string")
  refuses("s:kin():add refuses an empty secret", function() s:kin():add("") end,
          "must not be empty")

  -- 11..13. The grammar both rosters keep.
  refuses("a party member is addressed by gob id", function() return party:get("Bob") end, "GOB ID")
  refuses("a string filter on the party is refused, naming why",
          function() return party:list("bob") end, "no name to match a string against")
  refuses("an unknown verb on a roster is refused", function() return kin:nosuchverb() end,
          "has no verb")

  -- The one thing a program cannot judge: whether the roster read belongs to the character named.
  local other
  for _, m in ipairs(hafen.session():list()) do if m ~= s then other = m end end
  if other == nil then
    manualCheck("bring a second character up (:session add) and run :t077-2 again",
                "this line is replaced by a reading of BOTH characters' rosters")
  else
    local ok2 = other:kin():list()
    local names, n = {}, 0
    for _, k in ipairs(ok2) do
      if n < 3 then n = n + 1 ; names[n] = tostring(k:name()) end
    end
    manualCheck(("drawn %s (%s): %d kin | background %s (%s): %d kin, first: %s")
                :format(tostring(s:user()), tostring(s:character()), count(kl),
                        tostring(other:user()), tostring(other:character()), count(ok2),
                        (n == 0) and "<none>" or table.concat(names, ", ")),
                "the second reading is that character's OWN roster, read from a character you are not"
                .. " looking at -- it need not match the drawn one's, and on two characters with"
                .. " different kin it will not")
  end

  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---------------------------------------------------------------- entry

-- The Kin window streams in over the seconds after the HUD is up, so a run taken the instant a
-- character enters the world would score a timing gap as a defect. Wait a bounded window for the
-- roster, then score whatever the run reached -- an empty roster is a legitimate answer, so the wait
-- is on the window existing rather than on it holding anybody.
local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if s == nil then
    log:write("[fail] there is no session on screen -- log a character in and run :t077-2 again")
    log:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local tries = 0
  local function go()
    tries = tries + 1
    if (tries < 12) and (s:kin():count() == 0) then
      hafen.timer():after(0.5, go)
      return
    end
    body(s)
  end
  go()
end

hafen.slash():register("t077-2", run)   -- the only way in: a suite does not start itself
