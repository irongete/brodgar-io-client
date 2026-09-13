-- 144.3 — the window, the petal, and the release. Self-checking suite.
--
-- Run with the voice addon enabled, a character in the world and a tree in view. The hold on the link and
-- the three hotkeys are asserted again; then the nearest tree is right-clicked and the ring it puts up is
-- read for a petal that must not be there -- Mute voice belongs to a ring over another player only.

local RING_WAIT = 4        -- seconds for the ring to come and go
local SETTLE    = 0.2      -- seconds after FlowerMenuAdded before the ring is read: every addon has run by then

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

local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function finish()
  manualCheck("type :voice", "the Voice window: a status line reading open with an rtt, a muted and a deafened box")
  manualCheck("drag the window somewhere else, then type :voice twice", "it comes back where you left it")
  manualCheck("with a second client near, read their row",
              "their kin name or #id, two glyphs, a mute box and a slider")
  manualCheck("tick their mute box", "silence from them, and the struck speaker over their head")
  manualCheck("right-click their character, then right-click it again",
              "Mute voice on the first ring, Unmute voice on the next")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local keys = hafen.client():options():keybindings()
  local declared = true
  for _, name in ipairs{"talk", "mute", "deafen"} do
    declared = declared and keys:binding():get("addon/voice/" .. name):exists()
  end
  check(declared, "the voice addon declares talk, mute and deafen: exists()", declared)

  -- The hold: our own link to the same server is refused naming the addon that holds it. Were it not, the
  -- link would be live and ours, so it is closed before the verdict.
  local own = hafen.voice():connection("wss://voice.brodgar.io")
  local ok, err = pcall(own.connect, own)
  if ok then own:close() end
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find("voice already holds a live link", 1, true) ~= nil),
        "a second link to voice.brodgar.io is refused naming voice", err)

  -- The window's place: a remembered window of an addon's own comes back where it stood, and the record it
  -- leaves is deleted before the run ends, so nothing persists past it.
  local first = hafen.ui():window():title("144.3"):position(212, 176)
  first:remember("t144")
  first:destroy()
  local second = hafen.ui():window():title("144.3"):remember("t144")
  local p = second:position()
  check(p ~= nil and p.x == 212 and p.y == 176, "a remembered window comes back where it was left (212,176)",
        p and (p.x .. "," .. p.y) or "nil")
  second:remember(nil):destroy()

  -- The ring over a tree. Read a beat after it is announced, once every addon's handler has had its say,
  -- then cancelled; the dismissal carries nil.
  local s = hafen.session():current()
  local tree = s and s:world():gob():nearest("terobjs/tree")
  check(tree ~= nil, "a tree is in view to right-click", "no tree near this character")
  if not tree then finish(); return end

  local addedSub, removedSub, deadline
  local removed = nil
  local function done()
    if addedSub then addedSub:off(); removedSub:off(); deadline:cancel(); addedSub = nil end
  end
  addedSub = hafen.event():on("FlowerMenuAdded", function(petals, sess)
    hafen.timer():after(SETTLE, function()
      local native, foreign = true, nil
      for _, p in ipairs(sess:flowermenu():list()) do
        if not p:native() then native = false end
        local label = p:label()
        if label == "Mute voice" or label == "Unmute voice" then foreign = label end
      end
      check(native, "every petal on a tree's ring is the server's: native()", "a petal of an addon's own")
      check(foreign == nil, "no Mute voice or Unmute voice on a tree's ring", foreign)
      sess:flowermenu():cancel()
    end)
  end)
  removedSub = hafen.event():on("FlowerMenuRemoved", function(label) removed = {label = label} end)
  deadline = hafen.timer():after(RING_WAIT, function()
    done()
    check(removed ~= nil and removed.label == nil, "the ring was cancelled: FlowerMenuRemoved carried nil",
          removed and tostring(removed.label) or "no ring came within " .. RING_WAIT .. " s")
    finish()
  end)
  local sent, refusal = pcall(function() s:world():click(tree, 3) end)
  if not sent then
    done()
    check(false, "the right-click on the tree goes out", why(refusal))
    finish()
  end
end

hafen.console():on("t144", function() hafen.timer():after(0, run) end)   -- the only way in: a suite does not start itself
