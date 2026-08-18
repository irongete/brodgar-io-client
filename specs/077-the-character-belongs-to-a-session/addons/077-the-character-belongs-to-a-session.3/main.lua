-- 077.3 — the verbs that act, addressed. Self-checking suite.
--
-- What it proves: actionbar, speed, craft and menugrid are reached through a Session and nowhere else;
-- each is minted once per (addon, session) and interned on the handle; a Slot, a Speed and a Pagina
-- carry the CHARACTER as well as their key, so one slot index reached through two sessions is two
-- buttons; a Session the client does not hold answers nil-shaped rather than raising; all four loose
-- spellings are retired, naming where they went; a slot held for one of this addon's own entries reads
-- as that entry and hands the server's own content back untouched when the hold ends; and every
-- protected verb in the family keeps the ONE key it has, refusing by key -- naming the verb and the
-- key, before anything reaches the server.
--
-- This addon declares NO permissions, which is what makes the gate checks below mean something.

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

-- The four sections this task moves, each named by the verb that reaches it on a Session.
local FOUR = { "actionbar", "speed", "craft", "menugrid" }

-- The slot the hold below borrows, when the bar is empty: the last one, which nobody uses by hand.
local SLOT = 143

-- ...but an OCCUPIED slot is what makes the release worth asserting, so the borrow prefers one: putting
-- an empty slot back empty proves nothing about the server's own content surviving a hold.
local function borrow(bar)
  for _, sl in ipairs(bar:list()) do
    if not sl:empty() then return sl:index() end
  end
  return SLOT
end
-- The id this addon's own menu entry carries, and so the identity a held slot reads back.
local ENTRY = "hold"

-- ---------------------------------------------------------------- the scored body

local function body(s)
  local bar, speed, menu = s:actionbar(), s:speed(), s:menugrid()

  -- 1. All four are minted once for this (addon, session) pair and kept on the handle.
  local interned, offender = true, nil
  for _, name in ipairs(FOUR) do
    if s[name](s) ~= s[name](s) then interned, offender = false, name end
  end
  check(interned, "all four sections are interned on the Session handle", offender)

  -- 2. Each answers, or is honestly absent. A slot object always exists (the bar is a fixed array); a
  --    speed, a recipe and a catalogue may legitimately not be there, and none of them raises for it.
  local slot = bar:get(0)
  local cur, recipe, cat = speed:current(), s:craft():current(), menu:list()
  check((slot ~= nil) and (slot:index() == 0) and (type(cat) == "table")
        and ((cur == nil) or (type(cur:index()) == "number"))
        and ((recipe == nil) or (type(recipe:exists()) == "boolean")),
        ("each of the four answers or is honestly absent (speed %s, recipe %s, %d entries)")
        :format(tostring(cur and cur:name()), tostring(recipe and recipe:name()), count(cat)),
        tostring(slot) .. "/" .. tostring(cur) .. "/" .. tostring(recipe) .. "/" .. tostring(cat))

  -- 3. A Slot, a Speed and a Pagina are interned per (character, key): the same key reached through two
  --    sessions is two objects, because on two characters it names two things. A slot index and a speed
  --    index always address, so this holds against a session the client does not even hold.
  local ghost = hafen.session():get("no-such-account")
  check((bar:get(0) == bar:get(0)) and (bar:get(0) ~= ghost:actionbar():get(0))
        and (speed:get(2) == nil or speed:get(2) ~= ghost:speed():get(2)),
        "a Slot and a Speed are interned on the character as well as their key",
        tostring(bar:get(0) ~= ghost:actionbar():get(0)))

  -- 4. A Session the client does not hold is still an address: every one of the four answers nil-shaped.
  local ok4, agreed = pcall(function()
    return (ghost:actionbar():get(0):empty() == true) and (ghost:speed():current() == nil)
           and (ghost:craft():current() == nil) and (count(ghost:menugrid():list()) == 0)
  end)
  check(ok4 and agreed, "a session the client does not hold answers nil-shaped, not an error",
        ok4 and "reads disagreed" or agreed)

  -- 5. The hard cut: reading any of the four loose spellings AT ALL throws, naming the address.
  local cut, still = true, nil
  for _, name in ipairs(FOUR) do
    local ok5, err = pcall(function() return hafen[name] end)
    err = ok5 and "<no error>" or tostring(err)
    if ok5 or (err:find("session:" .. name .. "()", 1, true) == nil) then cut, still = false, name end
  end
  check(cut, "all four loose spellings are retired, naming the Session address", still)

  -- 6..7. The hold, which is the one write in this family that needs no permission: the client draws
  --       over the slot and keeps what the server has there. A held slot reads as the ENTRY, and the
  --       release hands the server's own content back untouched -- so the bar is exactly as it was.
  local n = borrow(bar)
  local before = bar:get(n):res()
  menu:remove(ENTRY)                        -- inert unless a previous run died between add and remove
  local pag = menu:add(ENTRY):name("077.3 hold")
  bar:get(n):pagina(pag)
  check((bar:get(n):res() == pag:res()) and (bar:get(n):pagina() == pag)
        and (bar:get(n):empty() == false),
        ("a held slot reads as the entry: slot:res() is its identity (slot %d)"):format(n),
        tostring(bar:get(n):res()) .. " vs " .. tostring(pag:res()))
  bar:get(n):pagina(nil)
  check((bar:get(n):res() == before) and (bar:get(n):pagina() == nil),
        ("ending the hold puts the server's own content back: slot %d holds %s again%s")
        :format(n, tostring(before),
                (before == nil) and " (this bar is empty — drag any action onto a slot for a"
                                    .. " stronger reading)" or ""),
        tostring(bar:get(n):res()))

  -- 8. The verb this task's own line names: the gate is the FIRST statement, so a speed that is not
  --    selectable, and an argument of the wrong shape, are both still behind the key.
  refuses("s:speed():set refuses, naming the verb and the key",
          function() speed:set(0) end, "session:speed():set", "speed.set")

  -- 9. And so does every other write in the family. One line for the rest, naming the first that
  --    misbehaved: the gate runs before the receiver is looked at, so nothing here reaches the server.
  local UNGRANTED = {
    { "slot:use", "actionbar.use", function() bar:get(0):use() end },
    { "slot:res", "actionbar.res", function() bar:get(0):res("gfx/hud/act/mine") end },
  }
  if cat[1] ~= nil then
    UNGRANTED[#UNGRANTED + 1] = { "pag:use", "menugrid.use", function() cat[1]:use() end }
  end
  if recipe ~= nil then
    UNGRANTED[#UNGRANTED + 1] =
      { "session:craft():current():make", "craft.make", function() recipe:make() end }
  end
  local gated, leak = true, nil
  for _, u in ipairs(UNGRANTED) do
    local ok9, err = pcall(u[3])
    err = ok9 and "<no error>" or tostring(err)
    if ok9 or (err:find(u[1], 1, true) == nil) or (err:find(u[2], 1, true) == nil) then
      gated, leak = false, u[1] .. " -> " .. err
    end
  end
  check(gated, ("every other write this addon did not declare refuses, naming its verb and its key"
                .. " (%d verbs)"):format(#UNGRANTED), leak)

  -- 10..12. The grammar each of the four keeps.
  refuses("an out-of-range slot index is refused, naming the range",
          function() return bar:get(200) end, "out of range", "0..143")
  refuses("a menu POSITION is refused, naming the two name forms",
          function() return menu:get(1) end, "no positions to address", "display name")
  refuses("an unknown verb on a section is refused", function() return speed:nosuchverb() end,
          "has no verb")

  -- 13. And the suite leaves nothing of its own behind: the entry is out of the menu again.
  menu:remove(pag)
  check((pag:exists() == false) and (menu:get(pag:res()) == nil),
        "the suite's own entry is out of the menu again", tostring(pag:exists()))

  -- The one thing a program cannot cause: a recipe window, which only the player can open.
  local other
  for _, m in ipairs(hafen.session():list()) do if m ~= s then other = m end end
  if other == nil then
    manualCheck("open a crafting recipe, bring a second character up (:session add), tab to it and"
                .. " run :t077-3 again",
                "this line is replaced by a reading of the recipe you left open on the OTHER character")
  else
    local theirs = other:craft():current()
    if theirs ~= nil then
      -- The claim, exercised: a recipe window read through the session of a character nobody is
      -- looking at. Only the reading is manual -- what it has to say is stated here, not judged there.
      manualCheck(("%s is on screen; %s is NOT, and its recipe reads \"%s\"")
                  :format(tostring(s:user()), tostring(other:user()), tostring(theirs:name())),
                  "exactly that: the window the game put up on the character you tabbed away from is"
                  .. " still open and still names its recipe, which is what makes a cross-character"
                  .. " crafting addon possible")
    elseif recipe ~= nil then
      -- The recipe is open on the DRAWN character, so this run reads it through the session that is on
      -- screen and proves nothing about a background one. Name the one gesture that fixes it.
      manualCheck(("the recipe \"%s\" is open on the DRAWN character (%s), so this run has not"
                   .. " exercised the claim — leave it open, run `:session anchor %s` to tab to the"
                   .. " other character, and run :t077-3 again")
                  :format(tostring(recipe:name()), tostring(s:user()), tostring(other:user())),
                  ("the next run reads: %s on screen, %s NOT, and %s's recipe still \"%s\"")
                  :format(tostring(other:user()), tostring(s:user()), tostring(s:user()),
                          tostring(recipe:name())))
    else
      manualCheck(("no recipe is open on either character (%s, %s) — open one on %s, then run"
                   .. " `:session anchor %s` and run :t077-3 again")
                  :format(tostring(s:user()), tostring(other:user()), tostring(s:user()),
                          tostring(other:user())),
                  "the run after that reads the recipe through the character you are NOT looking at")
    end
  end

  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---------------------------------------------------------------- entry

-- The action menu is a child the server places under the HUD, so it arrives some ticks after the HUD
-- itself and its entries resolve over the seconds after that. Wait a bounded window for the catalogue
-- to be non-empty -- the menu existing is what :add and the hold below need -- then score whatever the
-- run reached.
local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if s == nil then
    log:write("[fail] there is no session on screen -- log a character in and run :t077-3 again")
    log:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local tries = 0
  local function go()
    tries = tries + 1
    if (tries < 12) and (s:menugrid():count() == 0) then
      hafen.timer():after(0.5, go)
      return
    end
    body(s)
  end
  go()
end

hafen.slash():register("t077-3", run)   -- the only way in: a suite does not start itself
