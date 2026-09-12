-- 143.2 — what you say and what you hear: the mic, the mix, and the peers. Self-checking suite.
--
-- Dry, on a bare link: every setting reads back, the three ranges refuse, speaking() is false, the peer
-- collection is empty, :get(gob) mints a Peer and :get(42) is refused, a peer's mute and volume read back.
-- Then one link to the real server with vad(true) and transmitting(true); on one timer at the connect
-- timeout plus two seconds: the settings still read back, info() carries the six numbers, speaking()
-- read true at least once while the maintainer talked, and close() ended it.

local URL = "wss://voice.brodgar.io"
local TIMEOUT = 10000                       -- the default handshake deadline, in ms

local pass, fail, manual = 0, 0, 0

local function write(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    write("[pass] " .. what)
  else
    fail = fail + 1
    write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

-- Every setter round-trips: what was written is what is read, on this link, in whatever state it is in.
local function settingsReadBack(v, label)
  local want = { transmitting = true, vad = false, threshold = 500, agc = false, muted = true,
                 deafened = true, volume = 2 }
  local bad = nil
  for name, value in pairs(want) do
    v[name](v, value)
    local got = v[name](v)
    if got ~= value then bad = (bad or "") .. name .. "=" .. tostring(got) .. " " end
  end
  check(bad == nil, "every setting reads back what it wrote " .. label, bad)
end

local function summary()
  write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local dry = hafen.voice():connection(URL)
  settingsReadBack(dry, "on a bare link")
  refuses("volume(5) is refused naming 0..4", function() dry:volume(5) end, "from 0 to 4")
  refuses("threshold(-1) is refused naming its range", function() dry:threshold(-1) end, "from 0 to 32767")
  refuses("muted(\"yes\") is refused naming a boolean", function() dry:muted("yes") end, "true or false")
  check(dry:speaking() == false, "speaking() is false on a bare link", dry:speaking())
  check(dry:peer():count() == 0 and #dry:peer():list() == 0, "peer():count() is 0 and peer():list() is empty",
        dry:peer():count())
  local s = hafen.session():current()
  local tree = s and (s:world():gob():nearest(function() return true end) or s:player():gob())
  if tree then
    local peer = dry:peer():get(tree)
    check(peer:exists() == false and peer:gob() == tree and peer:id() == tree:id(),
          "peer():get(gob) answers a Peer: exists() false, gob() == gob, id() == gob:id()",
          tostring(peer) .. " exists=" .. tostring(peer:exists()) .. " gob=" .. tostring(peer:gob()))
    peer:muted(true):volume(2)
    local info = peer:info()
    check(peer:muted() == true and peer:volume() == 2 and info.muted == true and info.volume == 2,
          "peer:muted(true) and peer:volume(2) read back, and peer:info() carries them",
          tostring(peer:muted()) .. "/" .. tostring(peer:volume()))
  else
    check(false, "peer():get(gob) answers a Peer", "no session in the world to read a gob through")
    check(false, "peer:muted(true) and peer:volume(2) read back", "no gob")
  end
  refuses("peer():get(42) is refused naming a Gob", function() dry:peer():get(42) end, "must be a Gob")

  -- The live half: one link, the gate open and the detector on.
  local live = hafen.voice():connection(URL):vad(true):transmitting(true)
  local opened, closed, spoke, ended = false, false, false, nil
  live:on("Open", function(v)
    opened = (v == live) and (v:state() == "open")
    write("[info] talk now -- say something into the microphone for the next 10 seconds")
  end)
  live:on("Close", function(ev) closed = (ev:connection() == live) end)
  live:on("Error", function(ev) ended = ev:error() end)
  live:connect()
  local poll = hafen.timer():every(0.1, function() if live:speaking() then spoke = true end end)

  hafen.timer():after(TIMEOUT / 1000 + 2, function()
    poll:cancel()
    if not opened then
      check(false, "Open came", ended or live:state())
    else
      settingsReadBack(live, "after Open")
      local i = live:info()
      local nums = { "id", "rtt", "sent", "received", "mixed", "streams" }
      local missing = ""
      for _, k in ipairs(nums) do
        if type(i[k]) ~= "number" then missing = missing .. k .. "=" .. tostring(i[k]) .. " " end
      end
      check(missing == "", "info() carries the six numbers: id, rtt, sent, received, mixed, streams", missing)
      check(spoke, "speaking() read true at least once while talking", "sent=" .. tostring(i.sent))
    end
    live:close()
    hafen.timer():after(2, function()
      check(closed and hafen.voice():count() == 0, "close() ended it: Close came and count() read 0",
            "closed=" .. tostring(closed) .. " count=" .. hafen.voice():count() .. " state=" .. live:state())
      summary()
    end)
  end)
end

hafen.console():on("t143", run)   -- the only way in: a suite does not start itself
