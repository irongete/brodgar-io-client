-- 061.4 — Submitted on a native text entry, and an uncancelable Changed on a native slider and
-- scrollbar. Self-checking suite.
--
-- Run :t061-4 with the Options window OPEN and both "Audio settings" and "Keybindings" visited at least
-- once in this session: a panel nobody has opened is not in the tree at all, so its controls cannot be
-- found. The run arms its handlers, prints what to do, and scores 30 seconds later over what it reached.

local WAIT = 30

local pass, fail, manual = 0, 0, 0

local function log(s)
  hafen.log():write(s)
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
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
  log("[manual] " .. step .. " -- expect: " .. expect)
end

local function first(t)
  return (t ~= nil) and t[1] or nil
end

-- The Audio panel's own slider: the label names it, and the panel's first slider is the one under it.
local function audioSlider(opts)
  if opts == nil then return nil end
  local lbl = first(opts:all("label[text=Master audio volume]"))
  if lbl == nil then return nil end
  return first(lbl:parent():all("@HSlider"))
end

-- The Keybindings panel's scrolling box, and the bar down its right edge.
local function bindingBar(opts)
  if opts == nil then return nil end
  local sp = first(opts:all("@Scrollport"))
  if sp == nil then return nil end
  return first(sp:all("@Scrollbar"))
end

local NO_AUDIO = "Options > Audio settings was never opened -- its slider is not in the tree"
local NO_KEYS = "Options > Keybindings was never opened -- its scrollport is not in the tree"

local function run()
  pass, fail, manual = 0, 0, 0
  local opts = hafen.ui():find("window[title=Options]")
  local entries = hafen.ui():all("chat @TextEntry")
  local entry, slider, bar = first(entries), audioSlider(opts), bindingBar(opts)

  -- ---- what a program can read back with no gesture at all ------------------------------------
  if entry ~= nil then
    check((entry:info().owned == false) and (type(entry:value()) == "string"),
          "the chat entry is borrowed, and :value() reads the line it holds",
          tostring(entry:info().owned) .. " / " .. tostring(entry:value()))
    refuses("Changed on a native text entry is refused, naming Submitted",
            function() entry:on("Changed", function() end) end, "Submitted")
  else
    check(false, "the chat entry is borrowed, and :value() reads the line it holds", "no chat entry found")
    check(false, "Changed on a native text entry is refused, naming Submitted", "no chat entry found")
  end

  local label = first(hafen.ui():all("label"))
  if label ~= nil then
    refuses("Submitted on a native Label is refused, naming what a Label does answer",
            function() label:on("Submitted", function() end) end, "MouseDown")
  else
    check(false, "Submitted on a native Label is refused, naming what a Label does answer", "no label found")
  end

  if slider ~= nil then
    refuses("Submitted on a native slider is refused, naming Changed",
            function() slider:on("Submitted", function() end) end, "Changed")
  else
    check(false, "Submitted on a native slider is refused, naming Changed", NO_AUDIO)
  end

  -- ---- arm the three gestures ----------------------------------------------------------------
  local cLine, cEntry, cSubs = nil, nil, {}
  for _, e in ipairs(entries) do
    cSubs[#cSubs + 1] = e:on("Submitted", function(ev)
      if cLine == nil then                 -- the FIRST line only: the chat is the maintainer's afterwards
        cLine, cEntry = ev:value(), e
        ev:preventDefault()
      end
    end)
  end

  local sVals, sLive, sSteps, sRefusal, sSub = {}, true, true, nil, nil
  if slider ~= nil then
    sSub = slider:on("Changed", function(ev)
      local v = ev:value()
      if sRefusal == nil then              -- once, on the first step: it must RAISE, and change nothing
        local ok, err = pcall(function() ev:preventDefault() end)
        sRefusal = ok and "<no error>" or tostring(err)
      end
      if v ~= slider:value() then sLive = false end     -- it reports a value already written
      if (#sVals > 0) and (sVals[#sVals] == v) then sSteps = false end
      sVals[#sVals + 1] = v
    end)
  end

  local bN, bLast, bSub = 0, nil, nil
  if bar ~= nil then
    bSub = bar:on("Changed", function(ev)
      bN, bLast = bN + 1, ev:value()
    end)
  end

  manualCheck("within " .. WAIT .. "s: type 061 in the chat, press Enter, then leave the field alone",
              "nothing appears in the chat, and 061 is still sitting in the field")
  manualCheck("drag \"Master audio volume\" on Options > Audio settings, slowly, across the track",
              "the volume follows the drag the whole way -- nothing here cancels it")
  manualCheck("on Options > Keybindings, scroll the wheel over the list and drag the bar's thumb",
              "the list scrolls with both, as it does without this addon")

  hafen.timer():after(WAIT, function()
    for _, s in ipairs(cSubs) do s:off() end
    if sSub ~= nil then sSub:off() end
    if bSub ~= nil then bSub:off() end

    check(cLine ~= nil, "Submitted fired on the chat entry, carrying the line typed into it",
          "no line was submitted within " .. WAIT .. "s")
    check((cEntry ~= nil) and (cEntry:value() == cLine),
          "the cancelled line is STILL in the field -- the client never took it",
          (cEntry ~= nil) and cEntry:value() or "nothing was submitted")

    local sN = #sVals
    check((slider ~= nil) and (sN > 0), "the slider's Changed fired from the drag (" .. sN .. " steps)",
          (slider ~= nil) and ("no drag reached it within " .. WAIT .. "s") or NO_AUDIO)
    check((sN > 0) and sLive and sSteps and (sVals[sN] == slider:value()),
          "its values arrive in order, and the last is what the slider now holds",
          (sN == 0) and "no values" or (tostring(sVals[sN]) .. " vs " .. tostring(slider:value())
            .. ", live=" .. tostring(sLive) .. ", steps=" .. tostring(sSteps)))
    local r = sRefusal or "<the handler never fired>"
    check(r:find("already moved", 1, true) ~= nil,
          "ev:preventDefault() inside it raises, naming that the value has already moved", r)
    check(sN >= 2, "...and the drag ran on after that refusal, which cancelled nothing",
          sN .. " step(s) -- drag further")

    check((bar ~= nil) and (bN > 0) and (bLast == bar:value()),
          "the scrollbar's Changed fired (" .. bN .. " reports), and its last value is what the bar holds",
          (bar == nil) and NO_KEYS or (tostring(bN) .. " reports, last " .. tostring(bLast)
            .. " vs " .. tostring((bar ~= nil) and bar:value())))

    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t061-4", run)
