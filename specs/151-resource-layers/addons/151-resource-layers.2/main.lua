-- 151.2 — layer writes: add, remove, release, at every load and live. Self-checking suite.
--
-- A write is a spec at an address: resource:layers():add(spec) replaces every layer there with one built
-- from the spec over the first of them, :remove(key) drops them, resource:release() gives the client's
-- own layers back. A loaded resource shows the write at once (its layer list is re-read, so an older
-- Layer handle answers :exists() false); a resource nobody has fetched shows it when it loads. The
-- fixtures are in builtin-res.jar; the uncached one is fetched by the suite's own read.

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

-- Bundled resources no client class names and no preload list carries: the first one not yet cached is
-- the "declared before it loads" fixture.
local UNCACHED = { "gfx/ccscr", "gfx/hud/avakort", "gfx/hud/vilind", "gfx/hud/invsq",
                   "gfx/hud/combat/cmbmeters", "gfx/hud/combat/lframe" }

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0
  local resources = hafen.resource()
  local agi = resources:get("gfx/hud/chr/agi")
  local msg = resources:get("sfx/msg")

  local fresh
  for _, name in ipairs(UNCACHED) do
    if not resources:find(function(candidate) return candidate:name() == name end) then
      fresh = name
      break
    end
  end
  local cold
  if fresh then
    -- Declared by name before anything fetches it: the handle fetches nothing, add() hands back nil.
    cold = resources:get(fresh)
    cold:layers():add({ type = "tooltip", text = "Declared cold" })
  else
    manualCheck("restart the client and run :t151 once", "the six fixtures are all cached, so the declared-cold check did not run")
  end

  -- The loaded fixtures: poll for at most 5 s, then score what landed.
  local started = os.time()
  local poll
  poll = hafen.timer():every(0.2, function()
    local ready = agi:loaded() and msg:loaded() and ((cold == nil) or cold:loaded())
    if (not ready) and (os.time() - started < 5) then return end
    poll:cancel()

    local layers = agi:layers()
    local before = layers:get("tooltip")
    local written = layers:add({ type = "tooltip", text = "Nimbleness" })
    check(written and (written:info().text == "Nimbleness") and (layers:get("tooltip") == written),
          "add(spec) writes agi's tooltip and reads it back at once", written and written:info().text)
    check(before and (before:exists() == false) and written:exists(),
          "the layer handle taken before the write answers :exists() false, the new one true",
          before and before:exists())
    layers:add({ type = "tooltip", text = "Second" })
    eq("a second write at the address wins", layers:get("tooltip"):info().text, "Second")
    layers:remove("tooltip")
    check((layers:count() == 1) and (layers:get("tooltip") == nil), "remove(\"tooltip\") leaves one layer", layers:count())

    refuses("a pagina spec at an empty address is refused naming text",
            function() layers:add({ type = "pagina" }) end, "text is missing")
    local page = layers:add({ type = "pagina", text = "A whole page" })
    check(page and (page:info().text == "A whole page") and (layers:count() == 2),
          "a whole pagina fills the empty address", page and page:info().text)

    refuses("a clip that is not Ogg Vorbis is refused naming Ogg",
            function() msg:layers():add({ type = "audio2", clip = hafen.asset():get("notogg.txt") }) end, "Ogg")
    refuses("{type = \"mesh\"} is refused naming the writable types",
            function() layers:add({ type = "mesh" }) end, "tooltip")

    local clip = msg:layers():get("audio2:cl")
    local volumeBefore = clip:info().volume
    local quiet = msg:layers():add({ type = "audio2", volume = 0.1 })
    check(quiet and (quiet:info().volume == 0.1) and (quiet:id() == "cl") and (msg:layers():count() == 1),
          "sfx/msg at volume = 0.1 reads back 0.1, id and clip kept", quiet and quiet:info().volume)
    hafen.sound():get("sfx/msg"):play()
    manualCheck("listen: the suite just played sfx/msg after the volume write", "the chime, much quieter than usual")

    if cold then
      local tip = cold:layers():get("tooltip")
      eq("a tooltip declared on " .. fresh .. " before it loaded is there when it loads",
         tip and tip:info().text, "Declared cold")
    end

    -- The play resolves its clip on a loader thread: release after it has had its second.
    hafen.timer():after(1.0, function()
      agi:release()
      msg:release()
      if cold then cold:release() end
      local tip = agi:layers():get("tooltip")
      check(tip and (tip:info().text == "Agility") and (agi:layers():count() == 2),
            "release() reads \"Agility\" and two layers again", tip and tip:info().text)
      eq("release() reads the original volume again", msg:layers():get("audio2:cl"):info().volume, volumeBefore)
      summary()
    end)
  end)
end

hafen.console():on("t151", run)   -- the only way in: a suite does not start itself
