-- 039.9 -- the six existing entity collections: kin, actionbar, menugrid, sound, buff, meter.
-- Self-checking suite; see specs/testing/addon-suite.md. Run  :t039-9
--
-- It stands alone (D-085): every premise it rests on is asserted here, including the ones other suites
-- also make. It declares no permissions, so the only thing it can prove about the gated writes is the
-- one thing a read-only addon can prove -- that the gate refuses, naming the verb.

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- Count how many of a list of {label, thunk, wantedText} rows throw naming their replacement.
local function named(rows)
  local n, miss = 0, nil
  for _, r in ipairs(rows) do
    local ok, err = pcall(r[2])
    if (not ok) and (tostring(err):find(r[3], 1, true) ~= nil) then
      n = n + 1
    elseif not miss then
      miss = r[1] .. " -> " .. (ok and "<no error>" or tostring(err))
    end
  end
  return n, miss
end

local SECTIONS = { "kin", "actionbar", "menugrid", "sound", "buff", "meter" }

-- The old CALL forms: hafen.x(k) is now a verb on the section object.
local CALLS = {
  { "hafen.kin(7)",             function() return hafen.kin(7) end,               "hafen.kin():get" },
  { "hafen.actionbar(0)",       function() return hafen.actionbar(0) end,         "hafen.actionbar():get" },
  { "hafen.menugrid('Dig')",    function() return hafen.menugrid("Dig") end,      "hafen.menugrid():get" },
  { "hafen.sound('sfx/msg')",   function() return hafen.sound("sfx/msg") end,     "hafen.sound():get" },
  { "hafen.buff('x')",          function() return hafen.buff("x") end,            "hafen.buff():find" },
  { "hafen.meter('x')",         function() return hafen.meter("x") end,           "hafen.meter():find" },
}

local function run()
  local kin, bar, menu, snd = hafen.kin(), hafen.actionbar(), hafen.menugrid(), hafen.sound()
  local buff, meter = hafen.buff(), hafen.meter()

  -- 1. every section object is a per-addon singleton, handed back by identity
  local same, tables = 0, 0
  for _, name in ipairs(SECTIONS) do
    if hafen[name]() == hafen[name]() then same = same + 1 end
    if type(hafen[name]) == "table" and type(hafen[name]()) == "userdata" then tables = tables + 1 end
  end
  check(same == #SECTIONS, "every section object is the same object every call (" .. same .. "/6)")
  check(tables == #SECTIONS, "every section is a callable table over a userdata collection (" .. tables .. "/6)")

  -- 2. the retired spellings: the old call form, and the four renamed entity verbs
  local n, miss = named(CALLS)
  check(n == #CALLS, "every old call form throws naming its replacement (" .. n .. "/6)", miss)
  local slot0 = bar:get(0)
  local anyKin = kin:get(999999)                      -- a number is never nil, so this always answers
  local ENTITY = {
    { "kin:setGroup", function() return anyKin.setGroup end, "kin:group(g)" },
    { "kin:endkin",   function() return anyKin.endkin end,   "kin:endKin()" },
    { "slot:set",     function() return slot0.set end,       "slot:res(name)" },
  }
  local firstPag = menu:list()[1]
  if firstPag then
    ENTITY[#ENTITY + 1] = { "pagina:isnew", function() return firstPag.isnew end, "pagina:isNew()" }
  end
  n, miss = named(ENTITY)
  check(n == #ENTITY, "every renamed entity verb throws naming its replacement (" .. n .. "/"
        .. #ENTITY .. (firstPag and ")" or "; no menu entry yet, pagina:isnew not covered)"), miss)

  -- 3. a collection is an object, not a sequence
  refuses("a collection refuses #", function() return #kin end, "hafen.kin():count()")
  refuses("a collection refuses [1]", function() return kin[1] end, "not an array")

  -- 4. interning survives the move -- the ones that can be asserted with nothing streamed in
  check((kin:get(7) == kin:get(7)) and (not kin:get(7):exists()),
        "a kin id is interned and never nil: :get(7) == :get(7), :exists() false for an id you lack")
  check((slot0 == bar:get(0)) and (bar:list()[1] == slot0) and (bar:count() == 144) and (#bar:list() == 144),
        "the bar is 144 interned slots: :list()[1] == :get(0), and :count() agrees with #:list()")
  refuses("an out-of-range slot index throws", function() return bar:get(144) end, "out of range")
  local msg = snd:get("sfx/msg")
  check((msg == snd:get("sfx/msg")) and (msg:res() == "sfx/msg") and (type(snd:list()) == "table"),
        "a sound name is interned, and :get addresses any clip while :list() is only ours")

  -- 5. the two halves of the menu key land on the SAME interned object
  if firstPag then
    local byRes, nm = menu:get(firstPag:res()), firstPag:name()
    local byName = nm and menu:get(nm)
    check((byRes == firstPag) and ((byName == nil) or (byName == firstPag)) and (type(menu:roots()) == "table"),
          "a menu entry is one interned object by res and by name, and :roots() is a plain array")
  else
    check(false, "a menu entry is one interned object by res and by name", "the catalogue is empty")
  end

  -- 6. the two KEYLESS collections: a needle is a search, never an address
  refuses("hafen.buff() has no :get", function() return buff:get("poison") end, "has no verb 'get'")
  refuses("hafen.meter() has no :get", function() return meter:get("hp") end, "has no verb 'get'")
  local m1 = meter:list()[1]
  if m1 and m1:res() then
    check(meter:find(m1:res():match("[^/]+$")) == m1,
          "a meter needle finds the same interned object :list() holds")
  else
    check(false, "a meter needle finds the same interned object :list() holds",
          "no HUD meter with a resolved res -- are you in the world?")
  end
  local b1 = buff:list()[1]
  if b1 and b1:res() then
    check(buff:find(b1:res():match("[^/]+$")) == b1,
          "a buff needle finds the same interned object :list() holds")
  else
    check((buff:count() == #buff:list()) and (buff:find("NoSuchBuffHere") == nil),
          "no buff on the bar: the empty collection counts 0 and a needle misses to nil")
  end

  -- 7. the gated writes still refuse, naming the verb (this addon declares no permissions).
  -- The wanted text is the verb AND the permission clause: every one of these ALSO has a "not in the
  -- world" refusal that names the same verb, so matching the verb alone would pass with the gate gone.
  local function gate(verb) return verb .. ": this addon did not declare" end
  local GATED = {
    { "hafen.kin():add", function() return kin:add("secret") end,      gate("hafen.kin():add") },
    { "kin:group(g)",    function() return anyKin:group(3) end,        gate("kin:group") },
    { "kin:endKin()",    function() return anyKin:endKin() end,        gate("kin:endKin") },
    { "slot:res(name)",  function() return slot0:res("gfx/hud/act/mine") end, gate("slot:res") },
    { "slot:use()",      function() return slot0:use() end,            gate("slot:use") },
  }
  n, miss = named(GATED)
  check(n == #GATED, "every gated write refuses naming the verb AND the permission (" .. n .. "/5)", miss)

  -- 8. ...and the READ half of each collapsed pair is ungated and answers
  local okRead, readErr = pcall(function() return anyKin:group(), slot0:res() end)
  check(okRead and (anyKin:group() == nil),
        "the read arity of the collapsed pairs is ungated: kin:group() and slot:res() answer",
        okRead and tostring(anyKin:group()) or readErr)

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-9", run)   -- the only way in: a suite does not start itself
