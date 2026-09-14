-- 149.1 — The held slots live in the store. Self-checking suite, in three runs: the phase is kept in
-- this suite's own document across the :reload between them, so each run proves what the last one wrote
-- came back out of the file and not out of memory the reload kept.

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

local ID = "addon/149-holds-in-the-store.1/held"

local function entry(s)
  return s:menugrid():get("held") or s:menugrid():add("held")   -- the :add IS the re-apply
end

local function run()
  local s = hafen.session():current()
  local bar = s:actionbar()
  local run = hafen.store():get("run")
  local phase = run.phase or 0

  if phase == 0 then
    -- run 1: two holds, and the client's table is the client's
    local pag = entry(s)
    bar:get(143):hold(pag)
    bar:get(144):hold(pag)
    check(bar:get(143):hold() == pag, "slot 143 holds the entry", bar:get(143):hold())
    check(bar:get(144):hold() == pag, "slot 144 holds the entry", bar:get(144):hold())
    eq("slot 143 reads the entry's identity", bar:get(143):res(), ID)
    refuses("a statement over hafen_holds is refused naming it",
            function() hafen.store():query("SELECT * FROM hafen_holds") end, "hafen_holds")
    refuses("a declaration of hafen_holds is refused naming the prefix",
            function() hafen.store():table("hafen_holds") end, "hafen_")
    run.phase = 1
    hafen.store():flush()
    manualCheck(":reload, then :t149 again", "run 2's [pass] lines")

  elseif phase == 1 then
    -- run 2: the :add re-applies both slots out of the file (the reload rebuilt this addon), then one is
    -- released by hand
    local pag = entry(s)
    check(bar:get(143):hold() == pag, "slot 143 is held again after the reload: read back from the file",
          bar:get(143):hold())
    check(bar:get(144):hold() == pag, "slot 144 is held again after the reload", bar:get(144):hold())
    bar:get(144):hold(nil)
    eq("slot 144 is released by hold(nil)", bar:get(144):hold(), nil)
    run.phase = 2
    hafen.store():flush()
    manualCheck(":reload, then :t149 again", "run 3's [pass] lines")

  else
    -- run 3: the released slot stayed released across the file; the asked-for write; cleanup
    local pag = entry(s)
    check(bar:get(143):hold() == pag, "slot 143 is held across a second reload", bar:get(143):hold())
    eq("slot 144 stayed forgotten across the reload", bar:get(144):hold(), nil)
    local store = s:store()
    check(store:flush() == store, "s:store():flush() answers the store with a hold on record")
    bar:get(143):hold(nil)
    s:menugrid():remove(pag)
    eq("cleanup: the entry is out of the menu", s:menugrid():get("held"), nil)
    run.phase = nil
    hafen.store():flush()
    manualCheck("ls bin/savedata/ (delete <world>_<char>/ before run 1 if it is there)",
                "no <world>_<char>/ folder was recreated")
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  pass, fail, manual = 0, 0, 0
end

hafen.console():on("t149", run)   -- the only way in: a suite does not start itself
