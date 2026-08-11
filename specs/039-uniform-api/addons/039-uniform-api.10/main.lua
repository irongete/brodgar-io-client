-- 039.10 -- hafen.act(), speed, store, player, client and the options.
-- Self-checking suite; see specs/testing/addon-suite.md. Run  :t039-10
--
-- The headline is hafen.store(): a saved variable was a declared FIELD (hafen.store.cfg.foo = 1) and is
-- now hafen.store():get("cfg"), which is the one section whose ACCESS PATTERN changed rather than its
-- spelling. What :get hands back has to be the LIVE persisted table and not a copy, or writing into it
-- would go on working and quietly stop saving -- so the run writes through it and the parked 'kept'
-- round, after a ':reload', is what proves the write reached the disk. A suite cannot watch its own
-- reload, which is the one thing here that takes two commands.
--
-- It stands alone (D-085): every premise it rests on is asserted here, including the ones other suites
-- also make. It declares no permissions, so a gated verb is tested by asserting that the GATE refuses,
-- and the refusal wanted is the PERMISSION clause -- every gated verb also has a "not in the world"
-- refusal that names the same verb, so matching the verb alone would pass with the gate gone.
--
-- IT PUTS EVERYTHING BACK. Two real client settings are touched and both are restored in the same run
-- with the restore asserted: one camera toggle (flipped and flipped back) and one throwaway hotkey of
-- this addon's OWN, given a key nothing else holds and then unbound and unregistered.

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---- the parked half: run AFTER a ':reload', on what the main run wrote into the live table ---------
-- The value it looks for carries a STAMP, because a file left on disk by an older session would satisfy
-- "the data is there" without this run's write having reached it at all -- which is the check measuring
-- the world rather than the code. A stamp older than ten minutes is therefore a fail, not a pass.
local STALE = 600

local function keptRound()
  pass, fail, manual = 0, 0, 0
  local t = hafen.store():get("kept")
  local age = t.stamp and (os.time() - t.stamp)
  check((t.foo == 1) and (t.note == "live") and age and (age >= 0) and (age < STALE),
        "what was written INTO the table :get() handed back survived the reload -- it is the persisted"
          .. " table itself, not a copy",
        ("foo=%s note=%s age=%ss (run ':t039-10' first; an age over %d is a file from an older session)")
          :format(tostring(t.foo), tostring(t.note), tostring(age), STALE))
  check(hafen.store():get("kept") == t,
        "...and :get() still hands back one table per name, by identity")
  summary()
end

-- ---- the main run ----------------------------------------------------------------------------------
local function run(args)
  if args and (args[1] == "kept") then return keptRound() end
  pass, fail, manual = 0, 0, 0

  local speed, store, client = hafen.speed(), hafen.store(), hafen.client()

  -- 1. Every one of this task's sections is a per-addon SINGLETON handed back by identity, and the
  --    section is a callable TABLE over it -- which is what makes a retired field read reach the refusal.
  local SECTIONS = { "speed", "store", "client", "player", "act" }
  local same, shaped = 0, 0
  for _, nm in ipairs(SECTIONS) do
    if hafen[nm]() == hafen[nm]() then same = same + 1 end
    if (type(hafen[nm]) == "table") and (type(hafen[nm]()) == "userdata") then shaped = shaped + 1 end
  end
  check((same == #SECTIONS) and (shaped == #SECTIONS),
        ("all %d sections are one object every call, reached through a callable table (%d/%d, %d/%d)")
          :format(#SECTIONS, same, #SECTIONS, shaped, #SECTIONS))
  refuses("a section takes no arguments", function() return hafen.speed(2) end, "takes no arguments")

  -- 2. Every retired spelling THROWS naming its replacement (2.10) -- including hafen.store.<name>,
  --    which no static table could carry, because the name is this addon's own manifest declaration.
  local kb = client:options():keybindings()
  local RETIRED = {
    { "hafen.speed.get",       function() return hafen.speed.get end,       "hafen.speed():current()" },
    { "hafen.speed.set",       function() return hafen.speed.set end,       "hafen.speed():current(n)" },
    { "hafen.speed.max",       function() return hafen.speed.max end,       "hafen.speed():max" },
    { "hafen.speed.name",      function() return hafen.speed.name end,      "hafen.speed():name" },
    { "hafen.store.flush",     function() return hafen.store.flush end,     "hafen.store():flush()" },
    { "hafen.store.kept",      function() return hafen.store.kept end,      'hafen.store():get("kept")' },
    { "hafen.client.options",  function() return hafen.client.options end,  "hafen.client():options()" },
    { "hafen.client.profiling",function() return hafen.client.profiling end,"hafen.client():profiling()" },
    { "kb:get",                function() return kb.get end,                "kb:key(name)" },
    { "kb:set",                function() return kb.set end,                "kb:key(name, key)" },
  }
  local n, miss = named(RETIRED)
  check(n == #RETIRED, ("all %d retired spellings throw, and each names its replacement"):format(#RETIRED),
        miss)

  -- 3. THE HEADLINE, first half: :get() hands back ONE table per name, and writing into it is the whole
  --    of saving. A copy would answer every read here and lose the write at the door.
  local kept = store:get("kept")
  kept.foo, kept.note, kept.stamp = 1, "live", os.time()
  check((store:get("kept") == kept) and (store:get("kept").foo == 1),
        "hafen.store():get(name) is the live persisted table: a write through it is visible through a"
          .. " second :get(), which is the same object",
        ("identity=%s foo=%s"):format(tostring(store:get("kept") == kept),
                                      tostring(store:get("kept").foo)))
  refuses("an undeclared saved variable throws, listing the ones that are declared",
          function() return store:get("nosuchvar") end, "declares no saved variable")
  store:flush()

  -- 4. hafen.speed(): one name reads the speed and writes it, and the write is still gated.
  local cur, max = speed:current(), speed:max()
  check(((cur == nil) or (type(cur) == "number")) and ((max == nil) or (type(max) == "number"))
          and ((cur == nil) or (speed:name() ~= nil)),
        ("the speed reads through one name (current=%s max=%s name=%s)")
          :format(tostring(cur), tostring(max), tostring(speed:name())))
  refuses("...and the write half refuses this addon, naming the verb AND the permission",
          function() return speed:current(2) end, "hafen.speed():current: this addon did not declare")
  refuses("an explicit nil is not the read arity", function() return speed:name(nil) end,
          "must not be nil")

  -- 5. The options: arity is the verb, an explicit nil is refused, and the one real setting this suite
  --    touches is put back in the same breath -- with the restore asserted, not assumed.
  local cam = client:options():camera()
  local was = cam:invertHorizontal()
  local ret = cam:invertHorizontal(not was)
  check((ret == cam) and (cam:invertHorizontal() == (not was)),
        "an option written through :name(v) reads back, and the write answers the handle so it chains",
        tostring(cam:invertHorizontal()))
  cam:invertHorizontal(was)
  check(cam:invertHorizontal() == was, "...and the user's own setting is exactly as it was found",
        tostring(cam:invertHorizontal()))
  refuses("an option refuses an explicit nil rather than reading instead",
          function() return cam:invertHorizontal(nil) end, "must not be nil")

  -- 6. The keybinding registry: the API's LAST get/set pair, on one name. The remap is done on a
  --    throwaway hotkey of our own and on a key nothing in the registry holds, so no binding is stolen.
  --    The client CANONICALISES a key's spelling ("Ctrl+Shift+Y" reads back "Shift+Ctrl+Y"), so what the
  --    read has to agree with the write about is the KEY, not the string -- compared as a set of tokens.
  local function sameKey(a, b)
    if (a == nil) or (b == nil) then return false end
    local seen, n, m = {}, 0, 0
    for tok in tostring(a):gmatch("[^+]+") do seen[tok:lower()] = true; n = n + 1 end
    for tok in tostring(b):gmatch("[^+]+") do
      if not seen[tok:lower()] then return false end
      m = m + 1
    end
    return n == m
  end
  local all, want = kb:list(), nil
  for _, candidate in ipairs({ "Ctrl+Shift+Y", "Ctrl+Shift+K", "Ctrl+Shift+J" }) do
    local taken = false
    for _, key in pairs(all) do if key == candidate then taken = true end end
    if not taken then want = candidate break end
  end
  kb:register("t03910", function() end)
  check(kb:key("t03910") == nil, "a hotkey starts unbound, so the read half answers nil",
        tostring(kb:key("t03910")))
  if want then
    kb:key("t03910", want)
    check(sameKey(want, kb:key("t03910")),
          "kb:key(name, key) remaps and kb:key(name) reads back the same key",
          ("wrote %s, read %s"):format(want, tostring(kb:key("t03910"))))
    kb:key("t03910", "None")
    check(kb:key("t03910") == nil, "...and \"None\" unbinds it again", tostring(kb:key("t03910")))
  else
    check(false, "kb:key(name, key) remaps and kb:key(name) reads back the same key",
          "every candidate key is already bound -- nothing free to remap onto without stealing one")
  end
  kb:unregister("t03910")
  refuses("a write to a binding nobody has is an error, unlike a read of one",
          function() return kb:key("no-such-binding", "F9") end, "no binding named")

  -- 7. hafen.player(): a section of one, and worldToScreen takes a PLACE and answers PIXELS. Handing it
  --    a widget's position -- the same two numbers, the other space -- is refused rather than projected.
  local me = hafen.player():gob()
  local p = me and me:position()
  if p then
    local s = hafen.player():worldToScreen(p)
    check((s == nil) or ((type(s.x) == "number") and (type(s.y) == "number")),
          "worldToScreen projects a Position to plain screen pixels (not a Position)",
          s and ("%.0f,%.0f"):format(s.x, s.y) or "off screen")
  else
    check(false, "worldToScreen projects a Position to plain screen pixels (not a Position)",
          "no player gob -- are you in the world?")
  end
  refuses("...and it refuses a plain {x, y} table, the shape both spaces used to share",
          function() return hafen.player():worldToScreen({ x = 1, y = 2 }) end, "must be a Position")
  local root = hafen.ui():root()
  if root then
    refuses("...which is exactly what a widget's PIXEL position is: refused, not projected",
            function() return hafen.player():worldToScreen(root:position()) end, "must be a Position")
  else
    check(false, "a widget's PIXEL position is refused, not projected", "no UI root -- is the client up?")
  end
  refuses("an unknown verb on the section throws, rather than reading nil one call early",
          function() return hafen.player():nosuchverb() end, "has no verb")

  -- 8. The gate this suite tests every write against is itself readable without throwing, and the
  --    profiler's pull-only counters answer with the profiler off.
  check(hafen.act():enabled() == false, "hafen.act():enabled() reports the grant without throwing",
        tostring(hafen.act():enabled()))
  check(type(client:profiling():memory().heapUsed) == "number",
        "hafen.client():profiling() answers off the colon-on-the-namespace it lost")

  hafen.log():write("[note] now run ':reload' and then ':t039-10 kept' -- a suite cannot watch its own reload")
  summary()
end

hafen.slash():register("t039-10", run)   -- the only way in: a suite does not start itself
