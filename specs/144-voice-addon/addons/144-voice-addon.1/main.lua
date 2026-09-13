-- 144.1 — the link, the options page and the keys. Self-checking suite.
--
-- Run with the voice addon enabled and a character in the world: it holds the one link to
-- voice.brodgar.io, and that hold is the one thing about it another addon can read.

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

local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  local keys = hafen.client():options():keybindings()
  for _, name in ipairs{"talk", "mute", "deafen"} do
    local b = keys:binding():get("addon/voice/" .. name)
    eq("the voice addon declares its " .. name .. " hotkey: exists()", b:exists(), true)
  end

  -- The hold: our own link to the same server is refused naming the addon that holds it. Were it not, the
  -- link would be live and ours, so it is closed before the verdict.
  local own = hafen.voice():connection("wss://voice.brodgar.io")
  local ok, err = pcall(own.connect, own)
  if ok then own:close() end
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find("voice already holds a live link", 1, true) ~= nil),
        "a second link to voice.brodgar.io is refused naming voice", err)

  -- Not a refusal of every link: one to another server goes, and is closed before it can go anywhere.
  local other = hafen.voice():connection("wss://ws.invalid")
  local went, whynot = pcall(other.connect, other)
  check(went, "a link to another server is not refused", went or why(whynot))
  eq("that link reads connecting", other:state(), "connecting")
  other:close()
  eq("and closing once closed", other:state(), "closing")

  manualCheck("open Options > AddOns > Voice",
              "seven controls -- Voice on, Mode, Threshold, Automatic gain, Spatial, Volume, Bitrate -- and Voice on ticked")
  manualCheck("untick Voice on, wait a moment, tick it again",
              "'[voice] closed: ' and then '[voice] open, session N' in the log")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t144", run)   -- the only way in: a suite does not start itself
