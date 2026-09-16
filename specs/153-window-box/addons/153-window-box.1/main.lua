-- 153.1 — a window's box: the content read, the grip, the cancelable close. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMessage)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMessage, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function hasKey(keys, wanted)
  for _, key in ipairs(keys) do
    if key == wanted then return true end
  end
  return false
end

local standing          -- the window the manual checks are made on; :t153-1 again rebuilds it

local function run()
  pass, fail, manual = 0, 0, 0                     -- a rerun scores itself alone
  if standing and standing:exists() then standing:destroy() end

  -- 1. the read is the write's box, on a window and on a bare canvas alike
  local window = hafen.ui():window():title("t153 box"):size(300, 200):position(200, 150)
  eq("window:size() reads the content width", window:size().w, 300)
  eq("window:size() reads the content height", window:size().h, 200)
  window:size(window:size().w, window:size().h)
  eq("size(size()) is a no-op on a window", window:size().w, 300)
  eq("info().size is the same box", window:info().size.h, 200)
  local chrome = window:chrome()
  check(chrome and chrome.frame and (chrome.frame.w > 300) and (chrome.frame.h > 200),
        "chrome().frame is the outer box, wider than the content", chrome and chrome.frame and chrome.frame.w)
  check(chrome and chrome.content and (chrome.content.w == 300) and (chrome.content.x > 0),
        "chrome().content is the content area inside the frame", chrome and chrome.content and chrome.content.x)
  local canvas = hafen.ui():widget():size(120, 80)
  eq("a bare canvas reads its own box", canvas:size().w, 120)
  canvas:destroy()

  -- 2. the client's own grip, and what refuses it
  eq("resizable() reads nil before anything is armed", window:resizable(), nil)
  eq("resizable(true) chains", window:resizable(true), window)
  eq("resizable() reads true while the grip is on", window:resizable(), true)
  check(window:chrome().sizer ~= nil, "chrome().sizer is drawn once the grip is on", window:chrome().sizer)
  window:resizable(false)
  eq("resizable(false) reads nil again", window:resizable(), nil)
  local bare = hafen.ui():widget()
  refuses("a bare canvas refuses the grip naming the handle form",
          function() bare:resizable(true) end, "widget:resizable(h)")
  bare:destroy()
  refuses("a string is neither a handle nor a switch",
          function() window:resizable("corner") end, "true/false")
  local keys = window:events()
  check(hasKey(keys, "Resized") and hasKey(keys, "Close"), "events() lists Resized and Close", #keys)

  -- 3. a remembered box lands on the CANVAS: what Draw paints follows a resize the addon did not write
  --    (one throwaway name, deleted at the end: the record is this suite's own).
  local painted
  window:on("Draw", function(draw_event) painted = { w = draw_event:w(), h = draw_event:h() } end)
  window:remember("t153-box")
  window:size(360, 240)                          -- the level a remember put there follows the write
  window:destroy()                               -- ...and the box is saved on the way out
  local again = hafen.ui():window():title("t153 box"):size(300, 200):position(200, 150):remember("t153-box")
  eq("the remembered content box comes back", again:size().w, 360)
  again:on("Draw", function(draw_event) painted = { w = draw_event:w(), h = draw_event:h() } end)
  again:resizable(true)
  again:on("Resized", function(resize_event)
    hafen.log():write(("[info] Resized %dx%d, size() reads %dx%d"):format(
      resize_event:w(), resize_event:h(), again:size().w, again:size().h))
  end)
  again:on("Close", function(close_event)
    close_event:preventDefault()
    again:visible(false)
    hafen.log():write("[info] Close cancelled: the window is hidden, exists() = " .. tostring(again:exists()))
  end)
  standing = again
  hafen.timer():after(0.5, function()
    check(painted and (painted.w == 360) and (painted.h == 240), "Draw paints the remembered box", painted and painted.w)
    again:remember(nil)                          -- the record was this suite's: delete it
    manualCheck("drag the bottom-right corner of the 't153 box' window",
                "the dark panel follows the corner live; on release one [info] Resized line whose two sizes agree")
    manualCheck("click the X of the 't153 box' window, then :t153-1 again",
                "it hides instead of closing, [info] says exists() = true; the rerun destroys it and rebuilds")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

-- The only way in: a suite does not start itself. A console line runs under the character's tree monitor and
-- the windows below stand in the addon layer, so the run is handed to the step, which holds none.
hafen.console():on("t153-1", function() hafen.timer():after(0, run) end)
