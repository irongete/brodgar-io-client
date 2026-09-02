-- 127.3 -- a row escapes its own delimiters. Suite: run with :t127.
--
-- The codec is not reachable from Lua: enc/dec are private to AddonRegistry and no hafen.* verb spells
-- them. What IS reachable is the row they round-trip -- this addon's own consent row, written when you
-- approved it and parsed back at every load -- so the automated half below is 127.1's pair again, and it is
-- here because that pair is exactly what breaks if the codec corrupts a row it passes through:
--
--   the approved host PASSES  -- the row was written, parsed back, and its host half reached this addon.
--   two other hosts REFUSED   -- the parse produced THAT list and not a wider one.
--
-- (a suite stands alone: this duplicates 127.1's assertion rather than asking for 127.1 to be run.)
--
-- The manual half is the row that could not be written at all before this task. An id carrying the row's
-- own '=' was cut at that '=', recorded under the prefix in front of it, and never matched itself again --
-- so that addon was disabled and re-prompted at every single load, for ever. It records and reads back now.
--
-- Neither host resolves: ".invalid" is reserved and never has an address, so the one request that passes
-- the gate leaves the machine as a failed DNS lookup and nothing else. It is cancelled straight after, so
-- this suite leaves nothing in flight.

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

local function strip(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the send must fail, and fail SAYING which host and against what.
local function refused(what, url, ...)
  local ok, err = pcall(function() return hafen.http():request(url):send() end)
  local msg = ok and "<no error>" or strip(err)
  check(not ok, what, msg)
  for _, want in ipairs({...}) do
    check((not ok) and (msg:find(want, 1, true) ~= nil), "..." .. want .. " is in the refusal", msg)
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  local before = hafen.http():count()

  -- The approved host. It passes the gate, and passing is the whole assertion: this addon's row survived
  -- the codec in both directions, or the host half would not have reached the gate at all.
  local ok, req = pcall(function() return hafen.http():request("https://declared.invalid/probe"):send() end)
  check(ok, "the approved host passes the gate", ok and "<sent>" or strip(req))
  if ok then
    pcall(function() return req:cancel() end)
  end
  check(hafen.http():count() == before, "...and this suite leaves nothing in flight", hafen.http():count())

  -- A second host, never declared and never approved: the row parsed back to this addon's own list and not
  -- to a wider one. A codec that dropped an escape would show up here as a grant nobody gave.
  refused("a host outside the record is refused at the call",
          "https://second.invalid/probe", "second.invalid", "declared.invalid", "approved")

  -- The approved host is an EXACT entry, so a sub-domain of it is a different host -- the record round-trips
  -- as the entry that was written, not as a prefix of one.
  refused("a sub-domain of an exactly approved host is refused",
          "https://sub.declared.invalid/probe", "sub.declared.invalid")

  manualCheck("copy this addon's folder beside itself as"
    .. " \"127-the-consent-covers-the-hosts-it-showed.3=x\", set that copy's manifest \"id\" to the same"
    .. " string, then press Reload UI in Options > AddOns",
    "the copy is listed and DISABLED -- a declaration nobody has consented to yet")
  manualCheck("tick the copy's checkbox, approve its consent dialog, then press Reload UI again",
    "the copy is STILL ticked and no dialog is raised -- its row read back under an id carrying the row's"
    .. " own '=' (delete the copy folder once you have read this; :t127 answers from whichever copy loaded"
    .. " last, and both are this same file)")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t127", run)   -- the only way in: a suite does not start itself
