-- 144.2 — who is talking, over their heads. Self-checking suite.
--
-- Run with the voice addon enabled and a character in the world. The overlays are voice's own and no
-- other addon can list them, so what is drawn is read by eye; what a program can read is the hold on the
-- link and the three hotkeys, asserted here again.

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
    eq("the voice addon declares its " .. name .. " hotkey: exists()",
       keys:binding():get("addon/voice/" .. name):exists(), true)
  end

  -- The hold: our own link to the same server is refused naming the addon that holds it. Were it not, the
  -- link would be live and ours, so it is closed before the verdict.
  local own = hafen.voice():connection("wss://voice.brodgar.io")
  local ok, err = pcall(own.connect, own)
  if ok then own:close() end
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find("voice already holds a live link", 1, true) ~= nil),
        "a second link to voice.brodgar.io is refused naming voice", err)

  manualCheck("in Options > AddOns > Voice set Mode to voice detection, then say a sentence",
              "a speaker icon over your own character's head while you talk, gone once you stop")
  manualCheck("set Mode to push to talk, hold the talk key and talk",
              "the same speaker over your own head, only while the key is held")
  manualCheck("with a second client near, have them talk",
              "a speaker icon over their character's head while their voice arrives")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t144", run)   -- the only way in: a suite does not start itself
