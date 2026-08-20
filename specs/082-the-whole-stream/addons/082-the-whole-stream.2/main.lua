-- 082.2 — every inbound message, under the same name. Self-checking suite.
--
-- Nothing here can make the server send an update, so the run scores over what it reached: it opens a
-- window on the whole inbound stream, picks a name out of what actually arrived, and proves the
-- two-list dispatch on the next arrival of that name. No handler here calls preventDefault -- an
-- inbound wildcard that swallows is an inbound wildcard that stops the client.

local WINDOW = 6      -- seconds the collecting window stays open
local WAIT   = 8      -- seconds a later phase waits for the picked name to come round again
local POLL   = 0.5    -- how often a phase looks at what it has

local pass, fail, manual = 0, 0, 0
local subs = {}

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every subscription this run makes, so the next run starts from nothing rather than from the sum of
-- every run since the last `:reload`.
local function on(key, fn)
  local s = hafen.event():message():on(key, fn)
  subs[#subs + 1] = s
  return s
end

local function reset()
  for _, t in ipairs(hafen.timer():list()) do t:cancel() end   -- a previous run's windows, if any
  for _, s in ipairs(subs) do s:off() end
  subs = {}
  pass, fail, manual = 0, 0, 0
end

-- Poll until `ready()` answers true or WAIT seconds have gone by, then hand `cont` the seconds waited.
-- An arrival is the server's to make, so every phase past the first is bounded rather than promised.
local function await(ready, cont)
  local waited, t = 0, nil
  t = hafen.timer():every(POLL, function()
    waited = waited + POLL
    if ready() or (waited >= WAIT) then
      t:cancel()
      cont(waited)
    end
  end)
end

-- Hold `pick` AND the wildcard at once, and read the next arrival of that name off both.
local function proveOnePayload(pick)
  local order, evs, targets = {}, {}, {}
  local nnamed, nwild = 0, 0
  -- The wildcard is registered FIRST on purpose: within one addon handlers fire in registration
  -- order, so named-first can only be the dispatch's doing.
  local w = on("*", function(ev)
    if ev:msg() ~= pick then return end
    nwild = nwild + 1
    if evs.wild == nil then
      order[#order + 1] = "wild"
      evs.wild = ev
      targets.wild = ev:target():type()
    end
  end)
  local n = on(pick, function(ev)
    nnamed = nnamed + 1
    if evs.named == nil then
      order[#order + 1] = "named"
      evs.named = ev
      targets.named = ev:target():type()
    end
  end)

  await(function() return (evs.named ~= nil) and (evs.wild ~= nil) end, function(waited)
    check((evs.named ~= nil) and (evs.wild ~= nil),
          "holding '" .. pick .. "' and the wildcard, both handlers ran",
          (#order == 0) and ("no '" .. pick .. "' arrived in " .. waited .. "s")
                        or table.concat(order, ", "))
    check((order[1] == "named") and (order[2] == "wild"),
          "...the named key first, the wildcard second", table.concat(order, ", "))
    check((evs.named ~= nil) and (evs.named == evs.wild), "...over ONE ev, the very same value",
          (evs.named ~= nil) and (tostring(evs.named) .. " vs " .. tostring(evs.wild))
                             or "nothing fired")
    check((type(targets.named) == "string") and (targets.named ~= "")
          and (targets.named == targets.wild),
          "...and ev:target() answers a live widget class on both",
          tostring(targets.named) .. " / " .. tostring(targets.wild))

    -- sub:off() on the wildcard ends that subscription alone.
    w:off()
    local wasNamed, wasWild = nnamed, nwild
    await(function() return nnamed > wasNamed end, function(waited2)
      check((nnamed > wasNamed) and (nwild == wasWild),
            "sub:off() on the wildcard leaves the named subscription firing",
            (nnamed > wasNamed) and (nwild .. " wildcard call(s) after off()")
                                or ("no '" .. pick .. "' arrived in " .. waited2 .. "s"))
      n:off()
      summary()
    end)
  end)
end

local function run()
  reset()

  refuses("an inbound subscription still takes a function",
          function() hafen.event():message():on("*", "no") end, "(string, function)")

  if hafen.session():current() == nil then
    check(false, "a session is on screen for the server to send updates to", "no current session")
    return summary()
  end

  manualCheck("the suite watches the inbound stream for up to " .. (WINDOW + 2 * WAIT)
              .. " seconds from now -- play normally (walk about, open a window) and watch the client",
              "the frame stays as smooth as it was without the wildcard, and the [note] line below"
              .. " names the updates the window actually saw")

  -- The whole stream, counted and never touched.
  local counts, seen = {}, {}
  local w = on("*", function(ev)
    local m = ev:msg()
    if counts[m] == nil then
      counts[m] = 0
      seen[#seen + 1] = m
    end
    counts[m] = counts[m] + 1
  end)

  hafen.timer():after(WINDOW, function()
    w:off()
    local parts = {}
    for _, m in ipairs(seen) do parts[#parts + 1] = m .. " x" .. counts[m] end
    hafen.log():write("[note] " .. WINDOW .. "s on the whole inbound stream saw " .. #seen
                      .. " distinct name(s): "
                      .. ((#parts > 0) and table.concat(parts, ", ") or "none"))
    check(#seen >= 2, "a wildcard is handed updates it never named (2 or more distinct names)", #seen)
    if #seen == 0 then
      return summary()
    end
    -- the first name the window saw come round again, and the first it saw at all otherwise: a name
    -- that arrived once may never arrive again, and the phases below need an arrival to read.
    local pick = seen[1]
    for _, m in ipairs(seen) do
      if counts[m] > 1 then
        pick = m
        break
      end
    end
    proveOnePayload(pick)
  end)
end

hafen.slash():register("t082-2", run)   -- the only way in: a suite does not start itself
