-- 127.4 -- a declaration too large is refused, not thrown. Suite: run with :t127.
--
-- The whole consent record lives in ONE preference value, and that value has a hard byte budget. Nothing
-- measured it before: the record was encoded and handed to the store, and a declaration past the budget came
-- back as a raw Java error thrown out of the consent dialog. It is measured now, and a grant that does not
-- fit is refused by name with the addon left disabled.
--
-- The budget is not reachable from Lua -- no hafen.* verb spells it. What IS reachable is the record the
-- bounded write produced, and the risk a bound carries is that it starts refusing ordinary grants too. So
-- the automated half is this addon's own row, read back through the gate:
--
--   every declared host PASSES -- the ordinary grant was recorded, and recorded WHOLE. A write truncated to
--                                 fit the budget would show up as the last entry of the list missing.
--   an undeclared host REFUSED -- the row that came back is this addon's list and not a wider one.
--   http.post REFUSED          -- the record carries the keys the dialog showed and no others.
--
-- The manual half is the declaration that does not fit, and it ships beside this file as
-- manifest-oversized.json so the setup is a rename rather than four hundred lines of typing.
--
-- No host here resolves: ".invalid" is reserved and never has an address, so a request that passes the gate
-- leaves the machine as a failed DNS lookup and nothing else. Each is cancelled straight after, so this
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

-- A host the record covers: the send must be accepted, and the request is dropped again at once.
local function reaches(what, url)
  local ok, req = pcall(function() return hafen.http():request(url):send() end)
  check(ok, what, ok and "<sent>" or strip(req))
  if ok then
    pcall(function() return req:cancel() end)
  end
end

-- A refusal is a check: the send must fail, and fail SAYING what was refused.
local function refused(what, send, ...)
  local ok, err = pcall(send)
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

  -- The ordinary grant. Three hosts were declared, three were shown, three were approved -- and the bound
  -- accepted all of it, or none of these would reach the gate. The LAST entry is the one that answers the
  -- question a byte budget raises: a record written up to the limit and then cut would lose the tail.
  reaches("the first approved host passes the gate", "https://first.budget-probe.invalid/probe")
  reaches("the middle approved host passes the gate", "https://middle.budget-probe.invalid/probe")
  reaches("the last approved host passes the gate too, so the row was written whole",
          "https://last.budget-probe.invalid/probe")
  check(hafen.http():count() == before, "...and this suite leaves nothing in flight", hafen.http():count())

  -- The record that came back is this addon's own list. A bounded write that wrote a wider row, or read one
  -- back, would show up right here as a grant nobody gave.
  refused("a host outside the record is refused at the call",
          function() return hafen.http():request("https://absent.budget-probe.invalid/probe"):send() end,
          "absent.budget-probe.invalid", "approved")

  -- The other half of what the dialog showed: one key, and the key it did not ask for still refuses.
  refused("http.post, which this addon never declared, is still refused",
          function() return hafen.http():post("https://first.budget-probe.invalid/probe", "x"):send() end,
          "http.post")

  manualCheck("in bin/addons/127-the-consent-covers-the-hosts-it-showed.4/, rename manifest.json to"
    .. " manifest-ok.json and manifest-oversized.json to manifest.json, press Reload UI in Options >"
    .. " AddOns, then tick this addon's box and approve its consent dialog",
    "the box does NOT stay ticked, and a System line names this addon and the 6144 bytes one preference"
    .. " value holds")
  manualCheck("read the terminal for that same moment", "no Java stack trace there -- no"
    .. " \"IllegalArgumentException: Value too long\" (rename the two files back and press Reload UI"
    .. " once you have read this, or :t127 has nothing to answer from)")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t127", run)   -- the only way in: a suite does not start itself
