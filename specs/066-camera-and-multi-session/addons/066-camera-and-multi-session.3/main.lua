-- 066.3 — a Multi session section, and no default keys. Self-checking suite.
--
-- What a program can see here is the registry: both ids present, reading "None", and a key
-- surviving a write and a read. What it cannot see is the panel, so the section itself and the
-- gesture it enables are [manual].

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local NEXT, FOCUS = "rts-next-anchor", "rts-focus"

-- A key nothing else already holds. Assigning a binding is EXCLUSIVE -- it takes the key off
-- whatever else answers to it -- so probing with an occupied key would silently unbind a real
-- action, and writing it back afterwards would only take it off again.
local function freeprobe(bound)
  local probe = nil
  for _, k in ipairs({"F9", "F10", "F11", "F12", "Shift+F9"}) do
    if probe == nil then
      local taken = false
      for _, v in pairs(bound) do
        if v == k then taken = true end
      end
      if not taken then probe = k end
    end
  end
  return probe
end

local function run()
  pass, fail, manual = 0, 0, 0      -- :t066-3 is run again after every fix round; the tallies are per run
  local keys = hafen.client():options():keybindings()
  local before = keys:list()

  check(before[NEXT] ~= nil, "rts-next-anchor is in the registry", before[NEXT])
  check(before[FOCUS] ~= nil, "rts-focus is in the registry", before[FOCUS])

  -- The whole claim of this task. A binding falls back to its default key only while nothing has
  -- been assigned to it, so on a profile that has never assigned this id, what list() reports IS
  -- the default -- and it must now be "None".
  eq("rts-next-anchor carries no default key", before[NEXT], "None")

  -- rts-focus cannot be read the same way once anything has been assigned to it: an assignment
  -- hides the default from Lua completely, and only Backspace in the panel reverts to it. So its
  -- default is proved by the [manual] below rather than here, and this line just says what it reads.
  hafen.log():write("[note] rts-focus currently reads: " .. tostring(before[FOCUS]))

  local probe = freeprobe(before)
  if probe == nil then
    check(false, "a probe key nothing else holds", "every candidate is bound")
  else
    keys:key(NEXT, probe)
    check((keys:key(NEXT) == probe) and (keys:list()[NEXT] == probe),
          "a write round-trips through key() and list() (" .. probe .. ")",
          tostring(keys:key(NEXT)) .. " / " .. tostring(keys:list()[NEXT]))
  end

  keys:key(NEXT, "None")
  check((keys:list()[NEXT] == "None") and (keys:key(NEXT) == nil),
        "\"None\" unbinds it again: list() reads None, key() reads nil",
        tostring(keys:list()[NEXT]) .. " / " .. tostring(keys:key(NEXT)))

  refuses("a bad key string is refused",
          function() keys:key(NEXT, "Ctrl+") end, "cannot parse key")
  refuses("a write to an id that does not exist is refused",
          function() keys:key("no-such-binding", "F9") end, "no binding named")
  check(keys:key("no-such-binding") == nil,
        "but a read of one is plain nil", keys:key("no-such-binding"))

  manualCheck("open Options > Keybindings and scroll past Camera control",
              "a Multi session section, rows Next character and Focus selection; Next character reads None")
  manualCheck("select the Focus selection row and press Backspace (revert to default)",
              "it reads None -- there is no default key left for it to come back to")
  manualCheck("bind Next character, press it in game, then press Tab",
              "the bound key switches the anchor; Tab opens the inventory and switches nothing")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t066-3", run)
