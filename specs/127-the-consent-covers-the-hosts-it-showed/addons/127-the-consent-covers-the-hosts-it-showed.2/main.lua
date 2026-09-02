-- 127.2 -- an added host re-prompts, and reads as NEW. Suite: run with :t127.
--
-- The policy this task ships is one sentence with two containments: an addon's declared keys AND its
-- declared hosts must both be covered by what the user approved, or it is disabled and asked again. Lua has
-- exactly one door onto that policy -- whether this addon is running at all -- so the automated half below
-- is the containment's PASS direction, asserted three ways:
--
--   this addon answers :t127        -- the scan did not disable it, so its unchanged declaration is still
--                                      contained by the record. Invert the new containment and every
--                                      consented addon is disabled: this command would not exist to run.
--   the approved host is reachable  -- the record round-tripped, and the gate reads it.
--   a second host is refused        -- the grant is the record's list and not a blanket.
--
-- The FAIL direction is the manual half, and it is manual because it needs a manifest the client re-reads:
-- a file edit and a reload, neither of which an addon can do to itself.
--
-- Neither host resolves: ".invalid" is reserved and never has an address, so the one request that passes the
-- gate leaves the machine as a failed DNS lookup and nothing else. It is cancelled straight after, so this
-- suite leaves nothing in flight.

local SUITE = "127-the-consent-covers-the-hosts-it-showed.2"
local ADDED = "added.invalid"        -- the host the manual half adds; never declared, never approved here

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
  -- Door one: this addon is enabled and loaded. It answered :t127, so the scan left it alone -- its
  -- declaration is unchanged since consent and the record still covers both halves of it.
  check(ADDON.id == SUITE, "this addon is enabled and loaded, its declaration unchanged since consent",
        ADDON.id)

  local before = hafen.http():count()

  -- Door two: the one host the dialog showed and the user approved passes the gate.
  local ok, req = pcall(function() return hafen.http():request("https://declared.invalid/probe"):send() end)
  check(ok, "the approved host passes the gate", ok and "<sent>" or strip(req))
  if ok then
    pcall(function() return req:cancel() end)
  end
  check(hafen.http():count() == before, "...and this suite leaves nothing in flight", hafen.http():count())

  -- Door three: a host this addon never declared and the user never approved is refused at the call, naming
  -- it and naming the approved set it was measured against -- which is the record's list, not the internet.
  refused("a host outside the record is refused at the call",
          "https://second.invalid/probe", "second.invalid", "declared.invalid", "approved")

  manualCheck("in this addon's manifest.json replace \"hosts\": [\"declared.invalid\"] with"
    .. " [\"declared.invalid\", \"" .. ADDED .. "\"], then press Reload UI in Options > AddOns",
    "this addon's row reads \"disabled\" -- the added host is not in the record")
  manualCheck("tick this addon's checkbox in Options > AddOns, read the consent dialog, then approve it",
    "its one line reads: - NEW: fetch data from the servers it lists: declared.invalid, NEW "
    .. ADDED .. "  (http.get)")
  manualCheck("put \"hosts\" back to [\"declared.invalid\"], then press Reload UI",
    "no consent dialog at all, and :t127 still answers -- dropping a host re-prompts nothing")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t127", run)   -- the only way in: a suite does not start itself
