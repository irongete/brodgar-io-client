-- 085.7 -- a refusal names a fix that works. Self-checking suite.
--
-- This feature cut three colour spellings and renamed a bounds key, and the bridge's own strings went on
-- teaching what it cut: a font:derive() refusal offering :color(255, 200, 200), and two retirement rows
-- listing a rule's properties as :color(r,g,b). Retired cannot catch that -- it keys on a NAME, and an
-- argument shape has no name -- so the sweep was the fix and this suite is what stops it coming back.
--
-- IT ASSERTS THE ABSENCE, NOT THE CORRECTNESS. A check that the messages say the right thing would pass
-- on any wording anyone later writes; a check that no message among them carries a cut spelling fails the
-- moment one grows back. That is the only shape that keeps a copy from returning.
--
-- The same reason kills the two vocabulary re-lists. hafen.wounds and hafen.quests each restated their
-- member's whole verb list, and the Wound one had already drifted -- it was written before 085.4 added
-- :label(). So the rows now carry the ADDRESS, and the check is that no message re-lists (:exists() was
-- the tail of both) while the verbs themselves go on answering on the objects that own them.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
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

-- ---- the three refusals that taught a colour spelling this feature cut ------------------------------
--
-- A colour write takes a TABLE now, so a message offering :color(255, 200, 200) or :color(r,g,b) hands a
-- reader a line that raises. Two marks catch every form of it: ":color(" followed by a digit is the loose
-- literal, and an "r," is the loose signature written out. Neither appears in :color(c) or :color({...}).
local function refusals()
  local win = hafen.ui():window():title("085-7-probe")
  local sites = {
    {"hafen.ui.skin", function() return hafen.ui.skin end},
    {"widget:skin", function() return win:skin{} end},
    {"font:derive(t)", function() return hafen.font():get("sans"):derive({}) end},
  }
  local clean, bad = 0, {}
  for _, site in ipairs(sites) do
    local msg = said(site[2])
    if msg == nil then
      bad[#bad + 1] = site[1] .. " did not raise"
    elseif msg:find(":color%(%s*%d") or msg:find("r,", 1, true) then
      bad[#bad + 1] = site[1] .. ": " .. msg
    else
      clean = clean + 1
    end
  end
  check(clean == 3,
        ("no refusal teaches a cut colour spelling -- ui.skin, widget:skin, font:derive (%d/3)")
          :format(clean),
        (#bad > 0) and table.concat(bad, " | ") or "nothing raised")

  -- Deleting a vocabulary from a row must not delete the row: the address is the whole of its job.
  refuses("widget:skin{} still raises naming widget:rule()", function() return win:skin{} end,
          "widget:rule()")

  -- And the sweep took nothing live with it: a rule's colour is still written, and read back keyed.
  local r = win:rule()
  local err = said(function() r:color({200, 210, 220}) end)
  local c = r:color()
  check((err == nil) and (type(c) == "table") and (c.r == 200) and (c.g == 210) and (c.b == 220),
        "w:rule():color({200, 210, 220}) is taken and reads back keyed",
        err or ((type(c) ~= "table") and ("a " .. type(c))
                  or (tostring(c.r) .. "," .. tostring(c.g) .. "," .. tostring(c.b))))

  win:destroy()
end

-- ---- the two rows that re-listed a member's whole vocabulary ----------------------------------------
--
-- Each must still say WHERE the collection went, and must no longer say what its members answer.
-- ":exists()" was the tail of both lists, so it is the mark that says the copy is gone.
local function rows()
  local kept, lost = 0, {}
  for _, row in ipairs({{"hafen.wounds", "s:wound()", function() return hafen.wounds end},
                        {"hafen.quests", "s:quest()", function() return hafen.quests end}}) do
    local msg = said(row[3])
    if msg == nil then
      lost[#lost + 1] = row[1] .. " did not raise"
    elseif not msg:find(row[2], 1, true) then
      lost[#lost + 1] = row[1] .. " does not name " .. row[2]
    elseif msg:find(":exists()", 1, true) then
      lost[#lost + 1] = row[1] .. " still re-lists the member's verbs"
    else
      kept = kept + 1
    end
  end
  check(kept == 2,
        ("a retirement row is the address, not the member's vocabulary -- wounds, quests (%d/2)")
          :format(kept),
        (#lost > 0) and table.concat(lost, " | ") or "nothing raised")
end

-- ---- what the deleted list used to say, still answered by the object that owns it --------------------
--
-- :label() is the reason the Wound copy was a liability: it was added by 085.4 and the row never learned
-- it. Scored over whatever wounds the character is carrying, which is the only place the verb lives now.
local function wounds(s)
  local seen, wrong = 0, {}
  for _, w in ipairs(s:wound():list()) do
    seen = seen + 1
    if type(w:label()) ~= "string" then wrong[#wrong + 1] = "w:label() is a " .. type(w:label()) end
  end
  check((seen > 0) and (#wrong == 0),
        ("a wound answers the :label() the deleted list never named (%d/%d wounds)")
          :format(seen - #wrong, seen),
        (#wrong > 0) and table.concat(wrong, ", ")
          or "no wounds on this character -- take a scratch and re-run")
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
  pass, fail = 0, 0
  section("refusal", refusals)
  section("retirement row", rows)

  -- Only the last check needs the world, so the rest have already printed. A character sheet streams in a
  -- beat behind the world, so wait a bounded window for one and score what the run reached.
  local tries = 0
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    if (tries < 24) and not (s and s:exists()) then return end
    t:cancel()
    if s and s:exists() then
      section("wound", wounds, s)
    else
      check(false, "a character in the world", "none reached in 12s -- log in and re-run")
    end
    hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
  end)
end

hafen.slash():register("t085-7", run)   -- the only way in: a suite does not start itself
