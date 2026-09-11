-- 139.3 — a widget you built can be disabled. Self-checking suite.

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
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local win

local function run()
  pass, fail, manual = 0, 0, 0
  if win then win:destroy() end                      -- a second :t139 rebuilds rather than piles up

  win = hafen.ui():window():title("139.3"):position(80, 120)
  local col = hafen.ui():column():gap(4):parent(win)
  local btn = hafen.ui():button():parent(col):size(120):text("Press me")
  local box = hafen.ui():check():parent(col):text("Tick me")
  local entry = hafen.ui():entry():parent(col):size(120)
  win:pack()
  btn:on("Pressed", function() hafen.log():write("PRESSED") end)

  check(win:enabled() and col:enabled() and btn:enabled() and box:enabled() and entry:enabled(),
        "a widget you built is enabled at birth: the window, the column and the three controls", false)

  local r = col:enabled(false)
  check((r == col) and (col:enabled() == false), "col:enabled(false) reads false and chains", col:enabled())
  eq("the checkbox's own :enabled() stays true under a disabled column", box:enabled(), true)
  check((col:info().enabled == false) and (box:info().enabled == true),
        ":info().enabled follows the own flag (column false, checkbox true)", col:info().enabled)
  box:value(true)
  eq("box:value(true) lands while disabled, and :value() reads it", box:value(), true)
  entry:value("x")
  eq("entry:value(\"x\") lands while disabled", entry:value(), "x")

  local s = hafen.session():current()
  local borrowed = s and (s:ui():matchAll("window")[1] or s:ui():root())
  eq("a borrowed widget reads :enabled() true", borrowed and borrowed:enabled(), true)
  refuses("a borrowed widget's :enabled(false) fails naming the client",
          function() borrowed:enabled(false) end, "client")
  refuses("col:enabled(\"no\") is refused: a boolean is true or false",
          function() col:enabled("no") end, "true or false")

  r = col:enabled(true)
  check((r == col) and (col:enabled() == true), "col:enabled(true) restores, and chains", col:enabled())

  col:enabled(false)                                 -- ...and greyed again, for the two by hand
  manualCheck("press the greyed button in the 139.3 window", "no PRESSED line, and the window does not drag")
  manualCheck("look at the three in the 139.3 window", "dimmed, the box and the entry included")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- the only way in: a suite does not start itself. Deferred a tick: the console line runs under the typed
-- tree's monitor, and a window is built into the layer's.
hafen.console():on("t139", function() hafen.timer():after(0, run) end)
