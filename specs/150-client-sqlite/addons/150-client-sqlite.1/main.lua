-- 150.1 — the client's file, and the prefs in it. Self-checking suite.
--
-- An option declared through hafen.client():options():addon() is a preference of the client's: written
-- through the same store every Options setting lands in, which this task makes savedata/client.sqlite.
-- The suite proves the store works end to end from Lua — a write reads back, and the store's two limits
-- refuse naming themselves — and leaves the row for the two checks only a shell can make.

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

local opts = hafen.client():options():addon()
local greeting = opts:text("greeting"):default("hello"):add()

local function run()
  local before = greeting:value()
  eq("the option is declared as a text option", greeting:type(), "text")

  greeting:value("hello from :t150")
  eq("a written value reads back", greeting:value(), "hello from :t150")
  eq("the collection addresses the same option", opts:option():get("greeting"), greeting)

  -- The store takes 8192 characters in a value (Preferences.MAX_VALUE_LENGTH); one more is refused by
  -- the option, naming the limit, before it can reach the store as a raw Java error.
  refuses("a value of 8193 characters is refused naming the limit",
          function() greeting:value(string.rep("x", 8193)) end, "preference store takes 8192")
  eq("the refused write left the value in force", greeting:value(), "hello from :t150")

  -- The store takes 80 characters in a key; this addon's prefix alone is 30, so a 60-character name
  -- is refused at :add(), naming the limit.
  refuses("a name too long for the store is refused naming the limit",
          function() opts:text(string.rep("n", 60)):default(""):add() end, "preference store takes 80")

  greeting:value(before)
  eq("the option is restored to the value the run found", greeting:value(), before)

  manualCheck("sqlite3 savedata/client.sqlite \"select value from prefs where key like 'addon/150-client-sqlite.1/%'\"",
              "one row, value \"hello\" -- the value the suite restored, in a row its write created")
  manualCheck("reg query HKCU\\Software\\JavaSoft\\Prefs\\haven\\hafen | findstr 150-client",
              "nothing")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t150", run)   -- the only way in: a suite does not start itself
