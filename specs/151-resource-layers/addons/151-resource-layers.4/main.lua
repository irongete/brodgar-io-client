-- 151.4 — the .res file write: resource:layers(file). Self-checking suite.
--
-- A whole .res from the addon's folder replaces every layer of a resource: its version is ignored (agi
-- stays at the server's 3), a file carrying code is refused by name, a file that is not a resource file
-- and an asset that is not a data asset are refused naming what the verb takes, a spec written after
-- the file applies over it, and the file declared on a name nobody has fetched is there when it loads.
-- The fixtures are files built once and committed with the suite: suite.res (version 999: a tooltip
-- "From file" and a 4x4 image id -1), withcode.res (a code layer and a tooltip), notres.txt, red.png.

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

-- Nothing loads these unasked: the first one the client has not cached is the "declared before it
-- loads" fixture.
local UNCACHED = { "gfx/ccscr", "gfx/hud/avakort", "gfx/hud/vilind", "gfx/hud/invsq",
                   "gfx/hud/combat/cmbmeters", "gfx/hud/combat/lframe" }

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0
  local resources = hafen.resource()
  local agi = resources:get("gfx/hud/chr/agi")
  local file = hafen.asset():get("suite.res")

  local fresh
  for _, name in ipairs(UNCACHED) do
    if not resources:find(function(candidate) return candidate:name() == name end) then
      fresh = name
      break
    end
  end
  local cold
  if fresh then
    -- Declared by name before anything fetches it: the handle fetches nothing.
    cold = resources:get(fresh)
    cold:layers(file)
  else
    manualCheck("restart the client and run :t151 once", "the six fixtures are all cached, so the declared-cold check did not run")
  end

  -- The loaded fixture: poll for at most 5 s, then score what landed.
  local started = os.time()
  local poll
  poll = hafen.timer():every(0.2, function()
    local ready = agi:loaded() and ((cold == nil) or cold:loaded())
    if (not ready) and (os.time() - started < 5) then return end
    poll:cancel()

    local layers = agi:layers(file)
    eq("layers(suite.res) leaves agi with the file's two layers", layers:count(), 2)
    local tip = layers:get("tooltip")
    eq("the file's tooltip reads \"From file\"", tip and tip:info().text, "From file")
    local image = layers:get("image:-1")
    local info = image and image:info()
    check(info and (info.size.w == 4) and (info.size.h == 4), "the file's image reads size 4x4 at id -1",
          info and (info.size.w .. "x" .. info.size.h))
    eq("the file's version 999 is ignored: agi still reads version 3", agi:version(), 3)

    refuses("withcode.res is refused naming code",
            function() agi:layers(hafen.asset():get("withcode.res")) end, "\"code\" layer")
    refuses("notres.txt is refused as not a resource file",
            function() agi:layers(hafen.asset():get("notres.txt")) end, "not a resource file")
    refuses("red.png is refused naming a .res data asset",
            function() agi:layers(hafen.asset():get("red.png")) end, "DATA asset holding a .res")

    local over = agi:layers():add({ type = "tooltip", text = "Over the file" })
    check(over and (over:info().text == "Over the file") and (agi:layers():get("tooltip") == over)
          and (agi:layers():count() == 2),
          "a tooltip spec written after the file reads back over it", over and over:info().text)

    if cold then
      local coldTip = cold:layers():get("tooltip")
      check(coldTip and (coldTip:info().text == "From file") and (cold:layers():count() == 2),
            "the file declared on " .. fresh .. " before it loaded is there when it loads",
            coldTip and coldTip:info().text)
    end

    agi:release()
    if cold then cold:release() end
    local restored = agi:layers():get("tooltip")
    check(restored and (restored:info().text == "Agility") and (agi:layers():count() == 2),
          "release() reads \"Agility\" and two layers again", restored and restored:info().text)
    summary()
  end)
end

hafen.console():on("t151", run)   -- the only way in: a suite does not start itself
