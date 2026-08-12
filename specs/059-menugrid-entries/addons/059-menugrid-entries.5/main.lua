-- 059.5 — the slot stays yours across a relog. Self-checking suite.
--
-- The claim is that the two ways a hold ends are remembered differently: released BY HAND it is
-- forgotten, and the entry merely going away leaves the slot where that entry belongs. The re-apply
-- runs off hafen.menugrid():add(id) alone, which is exactly the call an addon makes at login.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local ID   = "hold"
local RES  = "addon/059-menugrid-entries.5/hold"
local N    = 143

local function entry()
  return hafen.menugrid():add(ID):name("059.5 hold"):icon(hafen.asset():get("hold.png"))
end

local function run()
  pass, fail, manual = 0, 0, 0           -- the relog check wants a second run: count this one alone
  local mg, slot = hafen.menugrid(), hafen.actionbar():get(N)

  -- 1. The login path. A placement on record is applied by :add and nothing else; on the first run of
  --    a character there is none yet, so this line is the relog manual until there is.
  mg:remove(ID)                          -- inert the first time; a removal never forgets a placement
  local pag = entry()
  if slot:pagina() == pag then
    check(true, "the placement on record put the entry in slot " .. N .. ", with no :pagina call")
  else
    manualCheck("nothing on record for slot " .. N .. " yet (the first run on this character)",
                "a [pass] on this line once you have relogged and run :t059-5 again")
  end

  -- 2. A hold released BY HAND is forgotten: adding the entry again must not take the slot back.
  slot:pagina(nil)
  eq("slot:pagina(nil) ends the hold", slot:pagina(), nil)
  local orig = slot:res()                -- the server's own content, back in the slot
  mg:remove(ID)
  pag = entry()
  eq("a hold released by hand is forgotten: :add leaves the slot alone", slot:pagina(), nil)

  -- 3. Hold it, and read the hold back off the bar.
  slot:pagina(pag)
  check(slot:pagina() == pag, "slot:pagina(pag) holds the slot for the entry", slot:pagina())
  eq("the held slot names the entry: slot:res()", slot:res(), RES)
  eq("the held slot is not empty", slot:empty(), false)

  -- 4. The entry merely goes away: the slot goes back, and the placement stands. Re-adding takes it
  --    again with no :pagina call — the very path a login runs.
  mg:remove(pag)
  eq("the entry leaves: slot:pagina()", slot:pagina(), nil)
  eq("the slot goes back to the server's own content", slot:res(), orig)
  pag = entry()
  check(slot:pagina() == pag, "an entry that merely went away takes its slot back on :add", slot:pagina())

  manualCheck("slot " .. N .. " is left held on purpose; drag the button onto a slot you can see,"
              .. " then relog and run :t059-5 again",
              "the pink button back on the bar off the :add alone, and line 1 reading [pass]")
  manualCheck("then disable this addon, restart the client, enable it again and run :t059-5",
              "the bar empty of it, and line 1 back to this [manual]: a disable forgets the slot")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t059-5", run)
