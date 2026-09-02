-- 127.1 -- the record carries the hosts, and the gate asks the record. Suite: run with :t127.
--
-- This addon declares ONE host, "declared.invalid", and the consent dialog showed it when you enabled the
-- addon. Everything below is the pair that tells a gate reading the grant apart from a gate reading nothing
-- at all:
--
--   the approved host PASSES  -- which it cannot do unless grantConsent wrote the host into the consent row
--                                and loadAll read it back out of that row onto this addon.
--   a second host is REFUSED  -- naming the host, and naming the approved set it was measured against, which
--                                is the record's own list and not the manifest's.
--
-- Neither host resolves: ".invalid" is reserved and never has an address, so the one request that passes the
-- gate leaves the machine as a failed DNS lookup and nothing else. It is cancelled straight after, so this
-- suite leaves nothing in flight.

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

local function run()
  local before = hafen.http():count()

  -- The approved host. It passes the gate, and passing is the whole assertion: the record round-tripped
  -- through the preference row and reached this addon, or nothing here would be reachable.
  local ok, req = pcall(function() return hafen.http():request("https://declared.invalid/probe"):send() end)
  check(ok, "the approved host passes the gate", ok and "<sent>" or strip(req))
  if ok then
    pcall(function() return req:cancel() end)
  end
  check(hafen.http():count() == before, "...and this suite leaves nothing in flight", hafen.http():count())

  -- A second host. Never declared, never approved, refused at the call by name.
  refused("a host outside the record is refused at the call",
          "https://second.invalid/probe", "second.invalid", "declared.invalid", "approved")

  -- The approved host is an EXACT entry, so a sub-domain of it is a different host. This is the wildcard
  -- rule being applied to the granted list rather than to the manifest's.
  refused("a sub-domain of an exactly approved host is refused",
          "https://sub.declared.invalid/probe", "sub.declared.invalid")

  manual = manual + 1
  hafen.log():write("[manual] quit the client, start it again, and run :t127 -- expect: no consent dialog,"
    .. " this addon still enabled, and the same verdict line (the record survives a restart)")
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t127", run)   -- the only way in: a suite does not start itself
