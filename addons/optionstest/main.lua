-- Brodgar.io Options Test — the standing end-to-end harness for hafen.client:options() (spec 018).
--
-- It lives on its own instead of inside 'hello': 'hello' is already the whole-API regression addon and is
-- too large to absorb another feature demo wholesale.
--
-- Everything it WRITES is a round-trip — read the current value, write a different one, read it back to
-- prove it stuck, then restore the original — so running the harness never leaves your settings changed.
-- 'scale' is never written: it is the one interface option a restart gates.
--
--   :opttest dump    read every subsystem
--   :opttest write   the non-destructive round-trips
--   :opttest chain   several setters in one statement (a setter returns its subsystem handle)
--   :opttest keys    register / get / set / list / unregister
--   :opttest error   invalid writes fail LOUDLY (pcall'd)
--
-- Hotkey 'ping' starts UNBOUND (D-047). Suggested key: Ctrl+Shift+O — assign it in
-- Options > Keybindings > Brodgar.io Options Test.

local TAG = "optionstest: "

local function log(fmt, ...)
  if select("#", ...) > 0 then
    hafen.log(TAG .. fmt:format(...))
  else
    hafen.log(TAG .. fmt)
  end
end

local function opts()
  return hafen.client:options()
end

-- ----------------------------------------------------------------- reads

-- One line per subsystem. video()/audio() answer nil until the client UI exists (login screen), which is
-- exactly what the docs' "before the client is up" section describes — so it is reported, not hidden.
local function dump(where)
  local o = opts()
  local i, v, a, c = o:interface(), o:video(), o:audio(), o:camera()

  log("--- options dump (%s) ---", where)
  log("interface: scale=%s posGran=%s angGran=%s deg",
      tostring(i:scale()), tostring(i:posGran()), tostring(i:angGran()))
  if v:shadows() == nil then
    log("video: nil — the client UI does not exist yet (expected on the login screen)")
  else
    log("video: shadows=%s renderScale=%s vsync=%s fps=%s bgFps=%s lighting=%s lightLimit=%s",
        tostring(v:shadows()), tostring(v:renderScale()), tostring(v:vsync()), tostring(v:fpsLimit()),
        tostring(v:bgFpsLimit()), tostring(v:lightingMode()), tostring(v:lightLimit()))
  end
  if a:masterVolume() == nil then
    log("audio: nil — same reason as video")
  else
    log("audio: master=%s ui=%s event=%s ambient=%s latency=%s ms",
        tostring(a:masterVolume()), tostring(a:uiVolume()), tostring(a:eventVolume()),
        tostring(a:ambientVolume()), tostring(a:latency()))
  end
  log("camera: invertHorizontal=%s invertVertical=%s",
      tostring(c:invertHorizontal()), tostring(c:invertVertical()))
end

-- ----------------------------------------------------------------- writes (round-trips)

-- read -> write -> read back -> restore. `pick(old)` chooses a value that differs from the current one.
local function trip(label, sub, method, pick)
  local old = sub[method](sub)
  if old == nil then
    log("%s: unavailable right now (nil) — skipped", label)
    return
  end
  local want = pick(old)
  local ok, err = pcall(sub[method], sub, want)
  if not ok then
    log("%s: write REJECTED — %s", label, tostring(err))
    return
  end
  local got = sub[method](sub)
  sub[method](sub, old)                        -- restore, always
  log("%s: %s -> %s (read back %s), restored to %s",
      label, tostring(old), tostring(want), tostring(got), tostring(sub[method](sub)))
end

local function write()
  local o = opts()
  log("--- round-trips (every value is restored) ---")
  trip("interface.posGran", o:interface(), "posGran", function(v) return (v == 5) and 6 or 5 end)
  trip("interface.angGran", o:interface(), "angGran", function(v) return (v == 15) and 30 or 15 end)
  trip("video.lightLimit", o:video(), "lightLimit", function(v) return (v < 32) and (v + 1) or 31 end)
  trip("audio.uiVolume", o:audio(), "uiVolume", function(v) return (v > 0.5) and 0.4 or 0.6 end)
  trip("camera.invertHorizontal", o:camera(), "invertHorizontal", function(v) return not v end)
  log("scale is deliberately NOT written here — it needs a client restart to take effect")
end

-- A setter returns its subsystem handle, so writes chain. Read the originals first and chain them back.
local function chain()
  local i = opts():interface()
  local pg, ag = i:posGran(), i:angGran()
  local ret = i:posGran(4):angGran(45)
  log("chained :posGran(4):angGran(45) -> posGran=%s angGran=%s (setter returned the handle: %s)",
      tostring(i:posGran()), tostring(i:angGran()), tostring(ret == i))
  i:posGran(pg):angGran(ag)
  log("chained back -> posGran=%s angGran=%s", tostring(i:posGran()), tostring(i:angGran()))
end

-- ----------------------------------------------------------------- keybindings

local function keys()
  local k = opts():keybindings()

  -- Our own hotkey is addon-scoped: "ping" here is addon/optionstest/ping in the registry. A name that is
  -- not ours falls back to the client's own id, which is how a built-in binding is reached.
  log("my 'ping' hotkey = %s (nil until you assign it in Options > Keybindings)", tostring(k:get("ping")))
  log("the client's 'inv' hotkey = %s", tostring(k:get("inv")))

  -- list() reports FULL registry ids, so ours read addon/optionstest/<name>.
  local all = k:list()
  local total, bound, mine = 0, 0, 0
  for id, key in pairs(all) do
    total = total + 1
    if key ~= "None" then bound = bound + 1 end
    if id:find("addon/optionstest/", 1, true) == 1 then
      mine = mine + 1
      log("  mine: %s = %s", id, key)
    end
  end
  log("list(): %d bindings, %d bound, %d mine", total, bound, mine)

  -- register -> set -> get -> unregister, all on a throwaway hotkey of OUR own, so no client binding is
  -- remapped. The client enforces one-key-one-action, so setting a key that is already taken would silently
  -- unbind its current owner — pick a free one, and if it is taken, skip the set instead of stealing it.
  local want
  for _, candidate in ipairs({ "Ctrl+Shift+P", "Ctrl+Shift+Y", "Ctrl+Shift+K" }) do
    local taken = false
    for _, key in pairs(all) do
      if key == candidate then taken = true end
    end
    if not taken then want = candidate break end
  end
  k:register("temp", function() log("the temporary hotkey fired") end)
  if want then
    k:set("temp", want)
    log("registered 'temp' and set it -> %s", tostring(k:get("temp")))
    k:set("temp", "None")
  else
    log("registered 'temp'; every candidate key is already in use, so it stays unbound")
  end
  k:unregister("temp")
  log("unregistered 'temp' -> %s (no longer dispatched, and gone from the keybind panel)",
      tostring(k:get("temp")))
end

-- ----------------------------------------------------------------- errors

-- An invalid value raises a Lua error instead of being clipped — a bad write fails loudly rather than
-- silently doing nothing. Each of these is expected to be caught.
local function errors()
  local o = opts()
  local cases = {
    { "interface:scale(-1)", function() o:interface():scale(-1) end },
    { "video:lightingMode('fancy')", function() o:video():lightingMode("fancy") end },
    { "audio:masterVolume(3)", function() o:audio():masterVolume(3) end },
    { "keybindings:set('no-such-binding', 'F9')", function() o:keybindings():set("no-such-binding", "F9") end },
  }
  for _, c in ipairs(cases) do
    local ok, err = pcall(c[2])
    if ok then
      log("%s: UNEXPECTED — the invalid write was accepted!", c[1])
    else
      log("%s: correctly rejected — %s", c[1], tostring(err))
    end
  end
end

-- ----------------------------------------------------------------- wiring

hafen.events.on("OnLoad", function()
  dump("OnLoad")
end)

hafen.events.on("OnEnterWorld", function()
  dump("OnEnterWorld")
end)

opts():keybindings():register("ping", function()
  local i = opts():interface()
  log("ping! ui scale=%s, master volume=%s",
      tostring(i:scale()), tostring(opts():audio():masterVolume()))
end)

hafen.slash.register("opttest", function(args)
  local sub = args[1] or "dump"
  if     sub == "dump" or sub == "" then dump("on demand")
  elseif sub == "write"             then write()
  elseif sub == "chain"             then chain()
  elseif sub == "keys"              then keys()
  elseif sub == "error"             then errors()
  else
    log(":opttest sub-commands -> dump | write | chain | keys | error")
    log("   dump = read every subsystem    write = non-destructive round-trips (values are restored)")
    log("   chain = setters chained in one statement    keys = register/get/set/list/unregister")
    log("   error = invalid writes, each expected to fail loudly")
  end
end)

log("loaded (v1.0.0) — run  :opttest  for the demos. Hotkey 'ping' is UNBOUND; suggested Ctrl+Shift+O.")
