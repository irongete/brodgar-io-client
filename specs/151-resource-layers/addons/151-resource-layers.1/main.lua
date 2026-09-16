-- 151.1 — hafen.resource(): the client's resources and their layers, read. Self-checking suite.
--
-- A Resource is addressed by name and interned; a content read fetches it, holding it does not. Its
-- layers are a collection keyed "type" / "type:id", each a Layer whose :info() decodes the wire. The
-- fixtures are in builtin-res.jar, so every read here needs no server; a name the pool cannot find ends
-- in :error(). Loads are asynchronous, so the suite polls :loaded() on a bounded timer and never blocks.

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
-- the fetch fixture. (gfx/hud/bosq/* and gfx/hud/emote/* are loaded at start by the IBox statics.)
local UNCACHED = { "gfx/ccscr", "gfx/hud/avakort", "gfx/hud/vilind", "gfx/hud/invsq",
                   "gfx/hud/combat/cmbmeters", "gfx/hud/combat/lframe" }

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0
  local resources = hafen.resource()
  local agi = resources:get("gfx/hud/chr/agi")
  check((resources:get("gfx/hud/chr/agi") == agi) and (agi:name() == "gfx/hud/chr/agi"),
        "get(name) is interned and knows its name", agi)

  refuses("an empty segment is refused naming the rule",
          function() resources:get("gfx//hud") end, "no empty segment")
  refuses("a .. segment is refused naming the rule",
          function() resources:get("gfx/../hud") end, "no \"..\" segment")
  refuses("a leading / is refused naming the rule",
          function() resources:get("/gfx/hud") end, "no leading \"/\"")

  -- Holding a handle fetches nothing: an uncached fixture stays out of the client's set until read.
  local fresh
  for _, name in ipairs(UNCACHED) do
    if not resources:find(function(candidate) return candidate:name() == name end) then
      fresh = name
      break
    end
  end
  local cold
  if fresh then
    cold = resources:get(fresh)
    eq("holding a handle fetches nothing: " .. fresh .. " is not listed", resources:find(fresh), nil)
  else
    manualCheck("restart the client and run :t151 once", "the six fixtures are all cached, so the fetch check did not run")
  end

  local sun = resources:get("gfx/hud/calendar/sun")
  local msg = resources:get("sfx/msg")
  local missing = resources:get("gfx/no/such/thing")
  local reads = { agi, sun, msg }
  if cold then reads[#reads + 1] = cold end

  -- The content reads: each :loaded() enqueues the fetch; poll for at most 5 s, then score what landed.
  local started = os.time()
  local poll
  poll = hafen.timer():every(0.2, function()
    local ready = true
    for _, resource in ipairs(reads) do
      if not resource:loaded() then ready = false end
    end
    if missing:error() == nil then ready = false end
    if (not ready) and (os.time() - started < 5) then return end
    poll:cancel()

    local layers = agi:layers()
    check((agi:version() == 3) and (layers:count() == 2), "agi loads at version 3 with two layers",
          tostring(agi:version()) .. "/" .. layers:count())
    local tooltip = layers:get("tooltip")
    eq("the tooltip layer decodes its text", tooltip and tooltip:info().text, "Agility")
    local image = layers:get("image")
    check(image and (image:id() == -1) and (image == layers:get("image:-1")) and image:exists()
          and (image:type() == "image"), "get(\"image\") is the id -1 image, get(\"image:-1\") is the same object",
          image and image:id())
    eq("a key naming no layer answers nil", layers:get("audio2"), nil)
    local info = agi:info()
    check(info and (info.version == 3) and (#info.layers == 2) and (info.layers[1] == "image:-1"),
          "resource:info() carries the version and the layer keys", info and info.layers[1])

    local anim = sun:layers():get("anim")
    local frames = anim and anim:info().frames
    check(frames and (#frames == 13) and (frames[1] == 128) and (sun:layers():count("image") == 13),
          "the sun's anim snapshot names 13 frames over 13 images", frames and #frames)

    local clip = msg:layers():get("audio2:cl")
    eq("the clip's volume is a number", type(clip and clip:info().volume), "number")

    check(resources:count("gfx/hud/chr/") >= 1, "count(substring) lists the loaded agi", resources:count("gfx/hud/chr/"))
    if cold then
      check(resources:find(fresh) ~= nil, "a content read fetched " .. fresh, resources:find(fresh))
    end

    local why = missing:error()
    check(why and why:find("gfx/no/such/thing", 1, true) and (missing:version() == nil) and (missing:loaded() == false),
          "a name the server lacks reaches :error(), reads :version() nil and :loaded() false", why)
    summary()
  end)
end

hafen.console():on("t151", run)   -- the only way in: a suite does not start itself
