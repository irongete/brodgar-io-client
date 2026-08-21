-- 085.5 -- the same handle every time. Self-checking suite.
--
-- Every handle under hafen.client() was minted per call, so hafen.client():options() ==
-- hafen.client():options() was false, one would not work as a table key, and a HUD reading
-- opts:video():fpsLimit() allocated a table and a metatable per frame. All eight are held per addon now
-- and handed back by identity, which is what a section has promised since the grammar was written.
--
-- WHAT PROVES IT is not that == says yes -- a value type would say yes too. It is that a table KEYED by
-- one handle is found through a second call for the same handle, which is what a user actually needs
-- identity for, and that nothing else moved: a read still answers, and a write still reaches the store.
--
-- The second half is the font: h:size(nil) and h:aa(nil) undo the layer, the meaning w:size(nil) already
-- carries on the same word, and the ownership guard judges that write like any other.
--
-- Nothing here needs the world. The interface handle is held ACROSS RUNS in an upvalue, because the one
-- thing a program cannot cause is a hand-made edit in the client's own Options window: the first run takes
-- the handle and the second run reads it back.

local pass, fail, manual = 0, 0, 0

-- The interface handle taken on the first run of this suite, and what it read that run. The [manual] line
-- is the pair: an interned handle must still be a window onto the live store, not a copy of it.
local held, heldAt

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING every word the reader needs.
local function refuses(what, fn, ...)
  local msg = said(fn)
  local ok = (msg ~= nil)
  for _, want in ipairs({...}) do
    ok = ok and (msg:find(want, 1, true) ~= nil)
  end
  check(ok, what, msg or "<no error>")
end

-- ---- all eight handles are the same object every call -----------------------------------------------
--
-- Each one is reached from hafen.client() twice over, not read twice off one variable: what is being
-- asserted is that the WAY IN hands back the same thing, not that a local holds still.
local function identity()
  local reach = {
    {"options",     function() return hafen.client():options() end},
    {"interface",   function() return hafen.client():options():interface() end},
    {"video",       function() return hafen.client():options():video() end},
    {"audio",       function() return hafen.client():options():audio() end},
    {"camera",      function() return hafen.client():options():camera() end},
    {"client",      function() return hafen.client():options():client() end},
    {"keybindings", function() return hafen.client():options():keybindings() end},
    {"profiling",   function() return hafen.client():profiling() end},
  }
  local same, apart = 0, {}
  for _, e in ipairs(reach) do
    if e[2]() == e[2]() then same = same + 1 else apart[#apart + 1] = e[1] end
  end
  check(same == #reach,
        ("every hafen.client() handle is the same object every call (%d/%d)"):format(same, #reach),
        table.concat(apart, ", "))

  -- The sharper form, and the one a user actually writes: a handle as a table key.
  local opts = hafen.client():options()
  local by = {}
  by[opts:video()] = "the video panel"
  check(by[opts:video()] == "the video panel",
        "a table keyed by opts:video() is found through a second opts:video()",
        tostring(by[opts:video()]))
end

-- ---- and nothing else moved -------------------------------------------------------------------------
--
-- A handle held forever is still a window onto the live store: a read reaches it, and a write reaches it.
-- The write is the user's own setting, so it is put back before the check is scored.
local function behaviour()
  local opts = hafen.client():options()
  local fps = opts:video():fpsLimit()
  check(type(fps) == "number", "opts:video():fpsLimit() answers a number",
        type(fps) .. " " .. tostring(fps))

  local iface = opts:interface()
  local was = iface:posGran()
  if type(was) ~= "number" then
    check(false, "a value written through opts:interface():posGran(n) reads back", type(was))
    return
  end
  local want = (was == 5) and 6 or 5
  iface:posGran(want)
  local got = iface:posGran()
  iface:posGran(was)                                   -- the user's own setting is put back
  check((got == want) and (iface:posGran() == was),
        "a value written through opts:interface():posGran(n) reads back, and restores",
        tostring(got) .. " then " .. tostring(iface:posGran()) .. ", from " .. tostring(was))
end

-- ---- nil undoes the layer a variant put on ----------------------------------------------------------

local function font()
  local sz = hafen.font():get("sans"):derive():size(12):size(nil):size()
  check(sz == nil, "h:derive():size(12):size(nil):size() is nil", tostring(sz))

  local aa = hafen.font():get("sans"):derive():aa(false):aa(nil):aa()
  check(aa == nil, "h:derive():aa(false):aa(nil):aa() is nil", tostring(aa))
end

-- ---- the two refusals beside this code, which must still fire ---------------------------------------
--
-- Clearing a property is a write, so the ownership guard judges it: a handle a consumer has already read
-- refuses it, naming :derive(). And the range check on a volume is a refusal the identity change ran
-- straight past -- the handle is held now, and a bad value must still be refused by the same words.
local function refusals()
  local h = hafen.font():get("sans"):derive():size(12)
  local w = hafen.ui():widget():size(80, 20):font(h)   -- a consumer reads it here...
  w:destroy()                                          -- ...and it never draws: built and destroyed at once
  refuses("a font handle already in use still refuses h:size(nil), naming :derive()",
          function() h:size(nil) end, "h:derive()")

  refuses("opts:audio():masterVolume(2) still names the 0.0..1.0 range",
          function() hafen.client():options():audio():masterVolume(2) end, "0.0", "1.0")
end

-- ---- the held handle, across two runs ---------------------------------------------------------------

local function liveness()
  if held == nil then
    held = hafen.client():options():interface()
    heldAt = held:scale()
    manualCheck("the handle this run took reads interface():scale() = " .. tostring(heldAt)
                  .. ". Open the client's Options window, change the interface scale by hand, close it,"
                  .. " and run :t085-5 again",
                "the next run's held-handle line shows the NEW scale (then set the scale back)")
  else
    local now = held:scale()
    manualCheck("the handle held since the first run read scale() = " .. tostring(heldAt)
                  .. " and reads " .. tostring(now) .. " now, and is "
                  .. ((held == hafen.client():options():interface()) and "still" or "NO LONGER")
                  .. " the same object as a fresh opts:interface()",
                "report whether that is the scale you typed into the Options window")
  end
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, err = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(err))
  end
end

local function run()
  pass, fail, manual = 0, 0, 0
  section("identity", identity)
  section("behaviour", behaviour)
  section("font", font)
  section("refusal", refusals)
  section("held handle", liveness)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t085-5", run)   -- the only way in: a suite does not start itself
