-- 086.7 -- the optional arguments the sweep did not reach. Self-checking suite.
--
-- The required arguments of the action verbs go through the house helpers and refuse by TYPE. Their
-- OPTIONAL ones were left on LuaJ's own optint/optdouble, which does two wrong things at once: a value
-- of the wrong kind falls through to a bare "bad argument: int expected, got table", naming neither the
-- verb nor the parameter, and a numeric STRING scans as a number, so place(p, 0, "1", "0") was taken
-- and sent. Five of the six sites are protected verbs, where the coerced value reaches the server.
--
-- THE CLAIM IS THAT EVERY ONE OF THEM NOW REFUSES THE WAY THE REST OF THE API DOES: the message names
-- its own verb and its own parameter, a string that merely scans as a number is not a number, an
-- explicit nil in a slot the caller passed is refused, and the required arguments beside them are as
-- required as they were.
--
-- Every check below is an argument refusal firing BEFORE anything is sent, which is also what proves
-- the manifest's grant: the permission gate runs first, so an argument refusal can only be reached by
-- an addon that declared the key. The one call made with arguments that are all good is
-- w:place(p, 0, 1, 0) -- aimed at the character's own feet, and with nothing on the pointer the server
-- ignores it.

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

-- One verdict line per claim, scored -- the claim is one sentence said at every site.
local function scored(hits, of, what, got)
  check(hits == of, ("%s (%d/%d)"):format(what, hits, of), got)
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function has(s, needle)
  return (s ~= nil) and (tostring(s):find(needle, 1, true) ~= nil)
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- ---- what the run can reach ------------------------------------------------------------------------
--
-- The receivers the sites hang off. The cursor and the inventory are the two that depend on what the
-- character happens to be doing, so they are scored over what the run reached rather than assumed.
local function reach()
  local s = hafen.session():current()
  if s == nil then return nil end
  local r = {s = s, w = s:world(), gob = s:player():gob(), hand = s:player():hand()}
  if r.gob == nil then return nil end
  r.p = r.gob:position()
  r.slot = s:actionbar():find(function(sl) return sl:empty() end)
  local inv = s:ui():inventory()
  r.item = (inv ~= nil) and inv:items()[1] or nil
  return r
end

-- ---- the wrong type ---------------------------------------------------------------------------------
--
-- The whole assertion is the NEGATIVE: a message that names the verb is exactly what LuaJ cannot
-- produce, so "bad argument" anywhere in it means the call still reaches optint.
local function typeSection(r)
  local sites = {
    {"session:world():place",  "button", function() r.w:place(r.p, 0, {}, 0) end},
    {"session:world():place",  "mods",   function() r.w:place(r.p, 0, 1, {}) end},
    {"session:world():click",  "button", function() r.w:click(r.gob, {}) end},
    {"session:world():click",  "mods",   function() r.w:click(r.gob, 1, {}) end},
    {"session:world():select", "mods",   function() r.w:select(r.p, r.p, {}) end},
  }
  local note = ""
  if r.slot ~= nil then
    sites[#sites + 1] = {"slot:use", "mods", function() r.slot:use({}) end}
  else
    note = note .. ", no empty action-bar slot so slot:use is not among them"
  end
  if r.hand ~= nil then
    sites[#sites + 1] = {"session:player():hand():use", "mods", function() r.hand:use(r.gob, {}) end}
  else
    note = note .. ", an empty cursor so hand:use is not among them"
  end
  local hits, saw = 0, {}
  for _, site in ipairs(sites) do
    local msg = said(site[3])
    if has(msg, site[1]) and has(msg, site[2]) and has(msg, "must be a number")
       and not has(msg, "bad argument") then
      hits = hits + 1
    else
      saw[#saw + 1] = site[1] .. " " .. site[2] .. ": " .. tostring(msg)
    end
  end
  scored(hits, #sites,
         "a wrong type raises naming the verb and the parameter, never \"bad argument\"" .. note,
         (#saw > 0) and table.concat(saw, " | ") or "-")
end

-- ---- the coercion, and the other direction ----------------------------------------------------------
--
-- The half a type test alone would miss: "1" passes LuaJ's isnumber(), so it used to be taken and SENT.
-- Both directions are asserted, because only the pair proves the refusal is about the TYPE and not the
-- value -- the same number written as a number goes through.
local function coercionSection(r)
  local hits, saw = 0, {}
  local function one(ok, got)
    if ok then hits = hits + 1 end
    saw[#saw + 1] = tostring(got)
  end
  local place = said(function() r.w:place(r.p, 0, "1", 0) end)
  one(has(place, "must be a number") and has(place, "tonumber(s)"), place)
  local click = said(function() r.w:click(r.gob, "1") end)
  one(has(click, "must be a number") and has(click, "tonumber(s)"), click)
  -- The same slots with a real number: nothing here may refuse for a type. place reaches the wire, and
  -- an empty slot refuses for being empty -- which is a refusal from past the argument checks.
  local good = said(function() r.w:place(r.p, 0, 1, 0) end)
  one(not has(good, "must be a number"), good or "<sent>")
  if r.slot ~= nil then
    local slot = said(function() r.slot:use(0) end)
    one(has(slot, "is empty") and not has(slot, "must be a number"), slot)
  else
    one(true, "<no empty slot>")
  end
  scored(hits, 4, "a numeric string is refused and the number beside it is taken",
         table.concat(saw, " | "))
end

-- ---- an explicit nil ---------------------------------------------------------------------------------
--
-- place(p, ang, nil, 0) passes a fourth argument and so passes a third: omitted and explicitly nil are
-- not the same thing, and optint(1) could not tell them apart.
local function nilSection(r)
  local msg = said(function() r.w:place(r.p, 0, nil, 0) end)
  check(has(msg, "session:world():place") and has(msg, "button") and has(msg, "must not be nil"),
        "an explicit nil in a passed slot gives the house nil message", msg or "<no error>")
end

-- ---- the refusal that was already there ---------------------------------------------------------------
--
-- Adding an optional helper beside a required one is exactly how a required argument stops being one.
local function requiredSection(r)
  local msg = said(function() r.w:place(r.p) end)
  check(has(msg, "session:world():place") and has(msg, "angle") and has(msg, "is required"),
        "a missing required argument still raises naming it", msg or "<no error>")
end

-- ---- the helper's own file, and the gate in front of it ------------------------------------------------
--
-- item:use is where the helper came from: it was private to one file while six others hand-rolled
-- optint. item:drop is the key this manifest did NOT declare, so its gate answers before its argument.
local function donorSection(r)
  if r.item == nil then
    scored(0, 0, "the helper still serves the file it came from -- no inventory item to reach it with",
           "-")
    return
  end
  local hits, saw = 0, {}
  local function one(ok, got)
    if ok then hits = hits + 1 end
    saw[#saw + 1] = tostring(got)
  end
  local use = said(function() r.item:use({}) end)
  one(has(use, "item:use") and has(use, "mods") and not has(use, "bad argument"), use)
  local drop = said(function() r.item:drop({}) end)
  one(has(drop, "item.drop") and not has(drop, "must be a number"), drop)
  scored(hits, 2, "the helper still serves item:use, and an undeclared key still refuses first",
         table.concat(saw, " | "))
end

local function run()
  pass, fail, manual = 0, 0, 0
  local r = section("reach", reach)
  if r == nil then
    check(false, "run :t086-7 with a character in the world -- no verb below could be reached",
          "no drawn session, or that character is not in the world yet")
  else
    section("type", typeSection, r)
    section("coercion", coercionSection, r)
    section("nil", nilSection, r)
    section("required", requiredSection, r)
    section("donor", donorSection, r)
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t086-7", run)               -- the only way in: a suite does not start itself
