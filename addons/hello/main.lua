-- Example addon (Phase 2d): HOOKS now span two levels — 2c hafen.hook.input (L1: intercept a widget's raw
-- input BEFORE its own handler) AND 2d hafen.hook.action (L2: intercept the OUTBOUND action a widget sends
-- to the server, with the arguments already RESOLVED — e.g. a move's destination world coord). Both are
-- pre-hooks with ev:preventDefault(); L2 also has ev:resend()/ev:send() to re-issue the action. On top of 2b
-- overlays (hafen.ui.overlay on the HUD + hafen.ui.gobOverlay over game objects) and 2a custom windows/widgets
-- + the GOut wrapper. It runs inside the Lua SANDBOX (D-017 strict env + D-018
-- instruction watchdog) over 1e hafen.store (saved variables), 1d-4 actionbar/equip, 1d-3 study/skills,
-- 1d-2 buffs + FEP/food, 1d-1 vitals, the 1c items/char/party reads, the gob/world/map/player/time/sound
-- reads, the 1b event bus, and timers, and can be RELOADED from disk without a relog (:reload, D-005) and
-- enabled/disabled (:addons, D-006). `hafen` is the API facade; `ADDON` describes this addon ({ id, dir }).
-- The file body runs once at load; then OnLoad, then (on entering the world) OnEnterWorld. On :reload the
-- whole cycle repeats. Every call in is watchdog-armed — a runaway loop aborts, no freeze.

hafen.log("hello loaded (v0.16.0)")

-- 1f-2: Reload UI + enabled set. Edit any .lua here, run `:reload` in the console, and the addon layer
-- rebuilds from disk with NO relog (D-005): OnDisable fires (handler at the bottom), owned resources are
-- torn down, then the files re-run and OnLoad + OnEnterWorld fire again (the WoW PLAYER_LOGIN analog — so
-- the login counters further down also tick on each reload). The log line below is a RELOAD MARKER: change
-- its text, `:reload`, and the new text should appear — that is the whole WoW dev loop. acct.loads counts
-- every OnLoad (incl. reloads) and is persisted, proving saved variables survive a reload. Enabling/
-- disabling is operator-driven from the console and applies on reload (D-006):  :addons  (list + status) ·
-- :addons disable hello  +  :reload  (hello stops loading) ·  :addons enable hello  +  :reload  (loads again).
hafen.events.on("OnLoad", function()
  local a = hafen.store.acct
  a.loads = (a.loads or 0) + 1
  hafen.log(("OnLoad fired — reload marker: edit me and :reload  [OnLoad #%d]"):format(a.loads))
end)

-- 1f-1: self-check the sandbox from inside the live client. Addons get a STRICT environment (D-017):
-- the dangerous stdlib is withheld — no `io`, no `os.execute`/`exit`/`getenv`, no `require`/`load`/
-- `loadfile`/`dofile`, no `debug`, and no `luajava` (the Java-reflection escape hatch) — while the safe
-- stdlib (string/table/math/os.time/pcall/…) stays. Referencing a withheld global just yields nil (Lua
-- has no strict-global error by default), and a guarded call to one is safely caught by pcall. This logs
-- what is locked down, proving the sandbox is active in-game. The other half — the instruction watchdog
-- (D-018) — is best tested manually: run  :lua while true do end  in the console; it aborts (~35 ms)
-- with an "addon watchdog" error instead of freezing the client. (Do NOT bake such a loop into an addon.)
hafen.events.on("OnLoad", function()
  local blocked = {}
  local function chk(name, v) if v == nil then blocked[#blocked + 1] = name end end
  chk("io", io); chk("require", require); chk("load", load); chk("loadfile", loadfile)
  chk("dofile", dofile); chk("debug", debug); chk("luajava", luajava); chk("package", package)
  chk("os.execute", os and os.execute)
  local safe = not (string == nil or table == nil or math == nil or os == nil
    or os.time == nil or pcall == nil or tostring == nil)
  -- Prove a shell-exec attempt is genuinely unusable (not merely absent) and is catchable:
  local ranExec = pcall(function() return os.execute("echo pwned") end)   -- os.execute is nil -> errors
  hafen.log(("sandbox: withheld={%s}; safe-stdlib=%s; os.execute usable=%s")
    :format(table.concat(blocked, ","), tostring(safe), tostring(ranExec)))
end)

-- 1c-2: read the map/projection data at the player's position and log it with a tag. The grid, terrain
-- height, and camera for the current spot stream in shortly AFTER entering the world, so right at
-- OnEnterWorld these may be nil (the reads are Loading-guarded); we call this again after a short delay
-- to show them resolve. worldToTile is pure math and always works.
local function readPlace(tag)
  local p = hafen.gob.pos("player")
  if not p then return end
  local tile = hafen.map.tile(p.x, p.y)
  local gp = hafen.map.gridPos()               -- no args = player: the persistent grid anchor
  local t = hafen.map.worldToTile(p.x, p.y)
  local s = hafen.player.worldToScreen(p.x, p.y)
  hafen.log(("[%s] tile=%s height=%s worldToTile=%d,%d"):format(tag,
    tile and (tile.name or tile.id) or "nil", tostring(hafen.map.height(p.x, p.y)), t.x, t.y))
  hafen.log(("[%s] gridPos=%s worldToScreen=%s"):format(tag,
    gp and (gp.gridId .. " @" .. ("%.0f,%.0f"):format(gp.x, gp.y)) or "nil",
    s and ("%.0f,%.0f"):format(s.x, s.y) or "nil"))
end

-- 1c-3: read the inventory / equipment / cursor through hafen.items. Item NAMES come from resolved
-- item info, which (like the inventory widget itself) can stream in a beat after enter-world, so this
-- is read twice — immediately and after a short delay — the same pattern as the map reads above.
local function readInv(tag)
  local inv = hafen.items.inventory()   -- array of Item snapshots {name,res,num,wear,pos}
  local eq = hafen.items.equipment()    -- array of Item snapshots {..., slot}
  local hand = hafen.items.hand()       -- Item snapshot or nil (cursor item)
  local first = inv[1]
  hafen.log(("[%s] inventory=%d item(s), first=%s x%s")
    :format(tag, #inv, first and tostring(first.name or first.res) or "nil",
            first and tostring(first.num or 1) or "-"))
  hafen.log(("[%s] equipment=%d slot(s), hand=%s")
    :format(tag, #eq, hand and tostring(hand.name or hand.res) or "empty"))
end

-- 1c-3: character attributes + learning points + weight, and the party size. Like items and map, the
-- char data (Glob cattrs, CharWnd.exp/enc) STREAMS IN a beat after enter-world, so this too is read at
-- OnEnterWorld (often still nil) and again after the delay (resolved).
local function readChar(tag)
  local str = hafen.char.attr("str")   -- {base, comp} or nil
  hafen.log(("[%s] char: str=%s lp=%s weight=%s"):format(tag,
    str and (str.base .. "/" .. str.comp) or "nil",
    tostring(hafen.char.lp()), tostring(hafen.char.weight())))
  hafen.log(("[%s] party: %d member(s)"):format(tag, #hafen.party.members()))
end

-- 1d-1: player vitals — hp/stamina/energy as 0..1 bar fractions (no absolute numbers exist). Read
-- through the widget-tree mechanism (the HUD meters). The meters stream in a beat after enter-world
-- (like char/items), so the "now" pass is usually nil and "+3s" has the bars.
local function readVitals(tag)
  local v = hafen.player.vitals()
  if v then
    hafen.log(("[%s] vitals: hp=%s stamina=%s energy=%s"):format(tag,
      tostring(v.hp), tostring(v.stamina), tostring(v.energy)))
  else
    hafen.log(("[%s] vitals: nil (meters not up yet)"):format(tag))
  end
end

-- 1d-2: active buffs/debuffs. Each is a snapshot {res,name,amount,cooldown,number} — amount/cooldown
-- are 0..1 fractions (NOT seconds) and often nil. The buff bar streams in a beat after enter-world,
-- so like vitals this is read at now + a delay. Most characters carry a buff or two at login.
local function readBuffs(tag)
  local list = hafen.buffs.list()
  local first = list[1]
  hafen.log(("[%s] buffs=%d, first=%s%s"):format(tag, #list,
    first and tostring(first.name or first.res) or "none",
    (first and first.cooldown) and (" cd=%.2f"):format(first.cooldown) or ""))
end

-- 1d-2: FEP + hunger via the character sheet. food() = { fep={cap,total,entries={{res,name,amount}}},
-- hunger={level,label,efficacy} } or nil until the base-attributes tab exists (streams in after
-- enter-world, like char/items). No absolute vital numbers exist, but FEP/hunger DO (this is them).
local function readFood(tag)
  local f = hafen.char.food()
  if f and f.fep then
    hafen.log(("[%s] food: fep=%.0f/%.0f (%d type(s)) hunger=%s efficacy=%s"):format(tag,
      f.fep.total or 0, f.fep.cap or 0, #f.fep.entries,
      f.hunger and tostring(f.hunger.label or f.hunger.level) or "nil",
      f.hunger and tostring(f.hunger.efficacy) or "nil"))
  else
    hafen.log(("[%s] food: nil (char sheet not up yet)"):format(tag))
  end
end

-- 1d-3: study/curiosity slots + known skills, both off the character sheet (widget-tree). slots() gives
-- each curiosity's study profile {res,name,lp,attention,cost,time,progress?}; summary() the live totals
-- {lp,attention,cost}; char.skills() the KNOWN skills as {name,res}. Like the rest of the char sheet the
-- study window and skill list stream in a beat after enter-world, so read at now (often empty) and +3s.
local function readStudy(tag)
  local slots = hafen.study.slots()
  local sum = hafen.study.summary()
  local first = slots[1]
  hafen.log(("[%s] study=%d slot(s), first=%s, totals=%s"):format(tag, #slots,
    first and tostring(first.name or first.res) or "none",
    sum and ("lp=%s att=%s cost=%s"):format(tostring(sum.lp), tostring(sum.attention), tostring(sum.cost)) or "nil"))
  local skills = hafen.char.skills()
  hafen.log(("[%s] skills=%d known, first=%s"):format(tag, #skills,
    skills[1] and tostring(skills[1].name) or "none"))
end

-- 1d-4: action bar / hotbar slots (the engine calls it the "belt"). slot(n) takes the RAW 0-based game
-- index (0..143 — the same index action-bar USE will take in Phase 4), returning {res,name,cooldown} for
-- an occupied slot or nil for an empty one. cooldown (0..1) appears only on ability slots (not seconds).
-- The hotbar streams in a beat after enter-world like the rest of the HUD, so scan at now (often empty)
-- and +3s (populated).
local function readActionbar(tag)
  local occupied, first, firstn = 0, nil, nil
  for n = 0, 143 do
    local s = hafen.actionbar.slot(n)
    if s then
      occupied = occupied + 1
      if not first then first, firstn = s, n end
    end
  end
  hafen.log(("[%s] actionbar=%d slot(s), first[%s]=%s%s"):format(tag, occupied,
    firstn and tostring(firstn) or "-",
    first and tostring(first.name or first.res) or "none",
    (first and first.cooldown) and (" cd=%.2f"):format(first.cooldown) or ""))
end

hafen.events.on("OnEnterWorld", function()
  hafen.log("entered the world")

  -- 1c-1: read the player through the canonical per-gob accessor. info() is a full snapshot. NB: hp is
  -- nil for the player — GobHealth is object integrity, not the player's vitals (those land in 1d).
  local me = hafen.gob.info("player")
  if me then
    hafen.log(("player gob: name=%s hp=%s at %.1f,%.1f")
      :format(tostring(me.name), tostring(me.hp), me.x or 0, me.y or 0))
  end
  hafen.log(("world has %d gob(s)"):format(hafen.world.count()))
  local near = hafen.world.nearest()
  if near then
    hafen.log(("nearest gob: id=%s name=%s dist=%.1f")
      :format(tostring(near.id), tostring(near.name), hafen.gob.distance(near.id)))
  end

  -- 1c-2: player identity (data with no per-gob equivalent — the local character name).
  hafen.log(("player: exists=%s id=%s name=%s")
    :format(tostring(hafen.player.exists()), tostring(hafen.player.id()),
            tostring(hafen.player.name())))

  -- 1c-2: time + astronomy (astronomy readers are nil until the first astro update).
  hafen.log(("time: clock=%.1f day=%s night=%s season=%s moon=%s")
    :format(hafen.time.clock() or 0, tostring(hafen.time.dayFraction()),
            tostring(hafen.time.isNight()), tostring(hafen.time.season()),
            tostring(hafen.time.moon())))

  -- 1c-2/1c-3: map, projection, item and char/party reads — now (often still loading/streaming) and
  -- again after 3s (resolved). char attrs, lp/weight and the inventory all stream in shortly AFTER
  -- enter-world (same as the map data), so the "now" pass typically shows nil/0 and "+3s" the real data.
  readPlace("now"); readInv("now"); readChar("now"); readVitals("now")
  readBuffs("now"); readFood("now"); readStudy("now"); readActionbar("now")
  hafen.timer.after(3, function()
    readPlace("+3s"); readInv("+3s"); readChar("+3s"); readVitals("+3s")
    readBuffs("+3s"); readFood("+3s"); readStudy("+3s"); readActionbar("+3s")
  end)

  -- 1c-2: an audible confirmation ping (a client-bundled sound), proving hafen.sound.play works.
  hafen.sound.play("sfx/msg")
end)

-- 1e: saved variables (hafen.store). Each name declared in the manifest is a persisted Lua table:
--   persist -> per-character  (savedata/<genus>_<char>/hello.json)
--   acct    -> account-wide   (savedata/account/hello.json)
-- The per-char store is restored just BEFORE OnEnterWorld (so it is ready here — no streaming delay,
-- unlike the read API above), the account store before the file body. We bump a login counter in each
-- to prove the values survive a relog (per character) and are shared across all characters (account).
-- s.recent is a small bounded array, demonstrating a nested JSON array round-tripping intact.
-- (A second OnEnterWorld handler — the bus dispatches to every subscriber in order.)
hafen.events.on("OnEnterWorld", function()
  local s = hafen.store.persist
  s.logins = (s.logins or 0) + 1
  s.name = hafen.player.name() or s.name          -- remember the character name across sessions
  s.recent = s.recent or {}                        -- history array (round-trips as a JSON array)
  s.recent[#s.recent + 1] = ("login #%d"):format(s.logins)
  while #s.recent > 5 do table.remove(s.recent, 1) end

  local a = hafen.store.acct
  a.logins = (a.logins or 0) + 1

  hafen.log(("store: %s entered %d time(s) [account total %d]; recent: %s")
    :format(tostring(s.name), s.logins, a.logins, table.concat(s.recent, ", ")))
  hafen.store.flush()                              -- write now (also autosaved + flushed on relog)
end)

-- OnUpdate fires every frame; throttle a heartbeat to once every 5 seconds so it is readable.
-- local acc = 0
-- hafen.events.on("OnUpdate", function(dt)
--   acc = acc + dt
--   if acc >= 5 then
--     acc = acc - 5
--     hafen.log(("tick heartbeat (dt=%.3f s)"):format(dt))
--   end
-- end)

-- Count gob spawns; log only the first few so it does not flood. The GobAdded payload is a full
-- snapshot (same shape as hafen.gob.info), so we can log the gob's type name too.
local spawned = 0
hafen.events.on("GobAdded", function(g)
  spawned = spawned + 1
  if spawned <= 3 then
    hafen.log(("GobAdded id=%s name=%s (%d so far)"):format(tostring(g.id), tostring(g.name), spawned))
  end
end)

-- 1d-1: VitalsChanged fires when the server updates a vital bar (stamina drain, energy change,
-- taking damage) — the payload is the same {hp,stamina,energy} snapshot as hafen.player.vitals().
-- Stamina/energy change often, so log only the first few to avoid flooding.
local vitalsSeen = 0
hafen.events.on("VitalsChanged", function(v)
  vitalsSeen = vitalsSeen + 1
  if vitalsSeen <= 5 then
    hafen.log(("VitalsChanged: hp=%s stamina=%s energy=%s (%d)"):format(
      tostring(v.hp), tostring(v.stamina), tostring(v.energy), vitalsSeen))
  end
end)

-- 1d-2: buff add/remove/change. Buffs the character already has re-appear as BuffAdded shortly after
-- enter-world (the bar streams in). Content updates (e.g. a cooldown ticking down a step) fire
-- BuffChanged. Log the first few of each so it does not flood.
local buffsSeen = 0
hafen.events.on("BuffAdded", function(b)
  buffsSeen = buffsSeen + 1
  if buffsSeen <= 5 then
    hafen.log(("BuffAdded: %s (%s)"):format(tostring(b.name or b.res), tostring(b.res)))
  end
end)
hafen.events.on("BuffRemoved", function(b)
  hafen.log(("BuffRemoved: %s"):format(tostring(b.name or b.res)))
end)
hafen.events.on("BuffChanged", function(b)
  hafen.log(("BuffChanged: %s amount=%s cooldown=%s"):format(
    tostring(b.name or b.res), tostring(b.amount), tostring(b.cooldown)))
end)

-- 1d-2: FEP/hunger changes. The FEP bar and hunger level stream in as "food"/"glut" updates a beat
-- after enter-world (so FepChanged fires a few times at login) and again whenever you eat.
local fepSeen = 0
hafen.events.on("FepChanged", function(f)
  fepSeen = fepSeen + 1
  if fepSeen <= 5 then
    local total = (f.fep and f.fep.total) or 0
    local hunger = f.hunger and (f.hunger.label or f.hunger.level)
    hafen.log(("FepChanged: fep total=%.0f hunger=%s (%d)"):format(total, tostring(hunger), fepSeen))
  end
end)

-- 1d-3: StudyChanged fires when the study slots change — a curiosity added/finished, or study data
-- streaming in a beat after enter-world (a few fires at login). Payload is the same array as
-- hafen.study.slots(). Log the first few so it does not flood.
local studySeen = 0
hafen.events.on("StudyChanged", function(slots)
  studySeen = studySeen + 1
  if studySeen <= 5 then
    hafen.log(("StudyChanged: %d slot(s)%s (%d)"):format(#slots,
      slots[1] and (", first=" .. tostring(slots[1].name or slots[1].res)) or "", studySeen))
  end
end)

-- 1d-4: ActionbarChanged{n} fires when action-bar slot n changes — slots stream in at login (a burst, one
-- per occupied slot) and then on any set/clear/drag. Action-bar changes are user-driven (not per-frame),
-- so — unlike vitals/gobs — we log EVERY one (with a running ordinal) to make it easy to verify live:
-- put an item/action on a slot or clear one and you should see a line each time. The payload is the slot
-- index; read it back to show its new content.
local actionbarSeen = 0
hafen.events.on("ActionbarChanged", function(n)
  actionbarSeen = actionbarSeen + 1
  local s = hafen.actionbar.slot(n)
  hafen.log(("ActionbarChanged: slot %s -> %s (%d)"):format(tostring(n),
    s and tostring(s.name or s.res) or "empty", actionbarSeen))
end)

-- 1d-4: EquipChanged fires when worn equipment changes (equip/unequip) — the payload is the same array
-- as hafen.items.equipment(). Equipment streams in at login (a few fires), then on any change. Log the
-- first few so it does not flood.
local equipSeen = 0
hafen.events.on("EquipChanged", function(eq)
  equipSeen = equipSeen + 1
  if equipSeen <= 5 then
    hafen.log(("EquipChanged: %d slot(s), first=%s (%d)"):format(#eq,
      eq[1] and tostring(eq[1].name or eq[1].res) or "none", equipSeen))
  end
end)

-- 2a: CUSTOM UI (hafen.ui). Create a small DRAGGABLE window that draws live state through the GOut
-- wrapper `g` and counts clicks — the Phase 2 "draggable custom window" DoD. The window is bridge-owned
-- (P2): :reload or disabling the addon DESTROYS it automatically (no leak) — no OnDisable cleanup needed.
-- It is client-side (it cannot talk to the server; that is hafen.act, Phase 4). Created at OnEnterWorld
-- because the HUD must be up. Drag it by the title bar; click the body (onClick consumes and logs); close
-- it with the X (onClose fires, then it is destroyed). onDraw runs every frame with (g, width, height).
local panel          -- the window handle (nil until created; a fresh reload rebuilds the Lua env -> nil)
local clicks = 0
local mapLock = false -- 2c: while true, the MapView mousedown hook cancels map clicks (toggle: LEFT-click the window)
local mapDowns = 0    -- 2c: how many map mousedowns the hook has seen (for the "observed" log lines)
local moveIntercept = false -- 2d: while true, the "click" action hook intercepts moves and re-sends them (toggle: RIGHT-click)
local moveHookSeen = 0      -- 2d: how many moves the action hook has observed while OFF (for the "observed" log lines)

local function drawPanel(g, w, h)
  g:color(0, 0, 0, 150); g:frect(0, 0, w, h); g:color()          -- translucent backdrop
  g:text(("clock %.0f"):format(hafen.time.clock() or 0), 6, 6)
  g:text(("clicks %d"):format(clicks), 6, 22)
  local v = hafen.player.vitals()
  if v then                                                       -- draw hp/stamina/energy as 0..1 bars
    local bars = {{"hp", v.hp, 235, 80, 80}, {"stam", v.stamina, 235, 210, 70}, {"en", v.energy, 110, 170, 255}}
    for i = 1, #bars do
      local b, y = bars[i], 42 + (i - 1) * 15
      g:text(b[1], 6, y)
      g:color(60, 60, 60); g:frect(44, y + 2, 110, 9); g:color()
      g:color(b[3], b[4], b[5]); g:frect(44, y + 2, math.floor(110 * (b[2] or 0)), 9); g:color()
    end
  else
    g:text("vitals loading...", 6, 42)
  end
  -- 2c: map-lock state (LEFT-click the window to toggle; red = map clicks are being cancelled by the L1 hook)
  g:color(mapLock and 235 or 150, mapLock and 90 or 150, 90)
  g:text(("map-lock %s (LMB)"):format(mapLock and "ON" or "OFF"), 6, 90)
  g:color()
  -- 2d: move-intercept state (RIGHT-click the window to toggle; orange = moves are intercepted + re-sent by L2)
  g:color(moveIntercept and 245 or 150, moveIntercept and 160 or 150, moveIntercept and 60 or 150)
  g:text(("move-hook %s (RMB)"):format(moveIntercept and "ON" or "OFF"), 6, 104)
  g:color()
  g:color(170, 170, 170); g:rect(0, 0, w, h); g:color()          -- 1px border
end

hafen.events.on("OnEnterWorld", function()
  if panel then return end                                        -- defensive: create the window once
  panel = hafen.ui.window{
    title   = "Hello 2c/2d",
    size    = { 184, 122 },
    pos     = { 80, 120 },
    onDraw  = drawPanel,
    onClick = function(x, y, button)
      clicks = clicks + 1
      if button == 3 then                                        -- RIGHT-click -> 2d: toggle the action hook
        moveIntercept = not moveIntercept
        hafen.log(("panel RMB #%d -> move-intercept %s"):format(clicks, moveIntercept and "ON" or "OFF"))
      else                                                        -- LEFT/other -> 2c: toggle the input hook
        mapLock = not mapLock
        hafen.log(("panel click #%d at %d,%d (button %d) -> map-lock %s")
          :format(clicks, x, y, button, mapLock and "ON" or "OFF"))
      end
      return true                                                 -- truthy = consume the click
    end,
    onClose = function() hafen.log("panel closed (X) -- :reload to bring it back") end,
  }
  hafen.log("2a: custom window up -- drag the title bar, LMB=map-lock, RMB=move-intercept, X=close")

  -- 2c: INPUT HOOK (hafen.hook.input, L1). Pre-hook MapView's mousedown through the engine's built-in
  -- Widget.listen seam (ZERO core edit): fn(ev) runs BEFORE MapView's own mousedown, at SCREEN coords, before
  -- any hit-test. While map-lock is ON (LEFT-click the window to toggle), ev:preventDefault() cancels the click
  -- so it never reaches MapView -- your character does NOT move (the Phase-2c DoD). While OFF the hook only
  -- observes (logs the first few). ev.x/ev.y are MapView-local pixels; ev.button is 1=left/2=middle/3=right.
  -- The handle (returned, with :remove()) is bridge-owned, so :reload/disable removes the hook automatically.
  hafen.hook.input("mapview", "mousedown", function(ev)
    mapDowns = mapDowns + 1
    if mapLock then
      ev:preventDefault()                                         -- MapView.mousedown never runs
      hafen.log(("2c: map click CANCELLED at %d,%d btn=%d (map-lock ON) [#%d]")
        :format(ev.x, ev.y, ev.button, mapDowns))
    elseif mapDowns <= 3 then
      hafen.log(("2c: map mousedown observed at %d,%d btn=%d (passed through) [#%d]")
        :format(ev.x, ev.y, ev.button, mapDowns))
    end
  end)
  hafen.log("2c: MapView mousedown hook installed -- LEFT-click the window to toggle map-lock, then click the map")

  -- 2d: ACTION HOOK (hafen.hook.action, L2). Intercept the OUTBOUND "click" action MapView sends to the
  -- server to move -- the UI.wdgmsg choke point, where the arguments are ALREADY RESOLVED: ev.args[2] is the
  -- destination WORLD coordinate (impossible to know at 2c's L1 mousedown, before the hit-test). RIGHT-click
  -- the window to arm move-intercept. While ON, a plain move-to-ground click is intercepted: ev:preventDefault()
  -- drops the server send, we log the resolved destination, then ev:resend() re-issues it ourselves -- the
  -- Phase-2d DoD (intercept a move-click, run logic, then re-send; the character still moves, via our resend).
  -- While OFF we only observe-log the first few, proving L2 sees every resolved move without altering it.
  -- We target MapView moves precisely: ev.sender == "MapView" and #ev.args == 4 (clicking a gob appends more
  -- args). resend()/send() bypass the hook chain, so re-issuing cannot loop. NB: if map-lock (2c) is ON, the L1
  -- hook cancels the click before any hit-test, so no "click" is ever sent and this L2 hook never fires -- turn
  -- map-lock OFF to see move-intercept. The handle is bridge-owned (:reload/disable removes it -- no leak).
  hafen.hook.action("click", function(ev)
    if ev.sender ~= "MapView" or #ev.args ~= 4 then return end    -- only plain MapView move-to-ground clicks
    local w = ev.args[2]                                          -- resolved destination (world coord {x,y})
    if moveIntercept then
      ev:preventDefault()                                         -- do NOT send the move to the server...
      hafen.log(("2d: MOVE intercepted -> %d,%d (btn %s) -- resending")
        :format(w.x, w.y, tostring(ev.args[3])))
      ev:resend()                                                 -- ...then issue it myself (unchanged) -> still moves
    else
      moveHookSeen = moveHookSeen + 1
      if moveHookSeen <= 3 then
        hafen.log(("2d: move observed -> %d,%d (passed through) [#%d]"):format(w.x, w.y, moveHookSeen))
      end
    end
  end)
  hafen.log("2d: MapView 'click' action hook installed -- RIGHT-click the window to arm move-intercept, then click the map")
end)

-- 2b: HUD OVERLAY (hafen.ui.overlay). Paint on top of the HUD WITHOUT owning a widget — fn(g, w, h) runs
-- every frame with the shared GOut wrapper and the SCREEN size, drawing at absolute screen coords. It is
-- drawn AFTER the whole HUD (via a re-queued UI.drawafter), so it lands on top. Bridge-owned (P2): :reload
-- or disabling the addon removes it automatically. Here: a small readout box at top-centre + a crosshair at
-- the exact screen centre. The gob count is refreshed once a second by a timer (NOT scanned every frame —
-- draw callbacks should stay cheap; the per-frame draw time is not covered by the soft CPU budget).
local gobCount = 0
hafen.timer.every(1, function() gobCount = hafen.world.count() end)

local function drawHud(g, w, h)
  -- 2c/2d: surface the hook states here too, so the input+action hooks have clear on-HUD feedback (border
  -- turns red while map-lock cancels clicks, orange while move-intercept re-sends them).
  local txt = ("2b HUD  gobs=%d  clock=%.0f  map-lock=%s  move=%s"):format(
    gobCount, hafen.time.clock() or 0, mapLock and "ON" or "OFF", moveIntercept and "ON" or "OFF")
  local bw = 320
  local x = math.floor(w / 2 - bw / 2)
  g:color(0, 0, 0, 140); g:frect(x, 2, bw, 18); g:color()        -- translucent backdrop
  if mapLock then g:color(235, 90, 90)                           -- red: L1 cancelling map clicks
  elseif moveIntercept then g:color(245, 160, 60)                -- orange: L2 intercepting + re-sending moves
  else g:color(120, 200, 120) end                                -- green: hooks observing only
  g:rect(x, 2, bw, 18); g:color()
  g:text(txt, x + 6, 4)
  local cx, cy = math.floor(w / 2), math.floor(h / 2)            -- crosshair at the exact screen centre
  g:color(255, 90, 90, 200)
  g:line(cx - 8, cy, cx + 8, cy, 1); g:line(cx, cy - 8, cx, cy + 8, 1)
  g:color()
end

-- 2b: WORLD-SPACE gob overlay (hafen.ui.gobOverlay). filter(gob) selects gobs (here: players — `isplayer`
-- is set on the snapshot when the base sprite is the borka body); draw(g, gob, sx, sy) paints at the gob's
-- projected screen point (just above the head). Your OWN gob always matches, so you will see at least your
-- own tag. The gob passed is the same snapshot shape as hafen.gob.info (no display-name field for players —
-- a client limitation — so we show the char name for self and "player" otherwise). Auto-removed on teardown.
local function drawPlayerTag(g, gob, sx, sy)
  local me = hafen.player.id()
  local label = (me and gob.id == me) and (hafen.player.name() or "you") or "player"
  g:color(80, 220, 90); g:frect(sx - 3, sy - 3, 6, 6); g:color()   -- a marker dot at the anchor
  g:atext(label, sx, sy - 6, 0.5, 1.0)                             -- name centred just above the marker
end

local overlaysUp = false
hafen.events.on("OnEnterWorld", function()
  if overlaysUp then return end                                   -- register the overlays once
  overlaysUp = true
  hafen.ui.overlay(drawHud)                                       -- returns a handle with :remove() (also auto)
  hafen.ui.gobOverlay(function(gob) return gob.isplayer end, drawPlayerTag)
  hafen.log("2b: HUD overlay (top-centre + crosshair) + player gob-tags up -- :reload/disable removes them")
end)

-- One-shot timer: proves the timer wheel fires exactly once, ~2s after load.
hafen.timer.after(2, function()
  hafen.log("timer.after(2) fired once")
end)

hafen.events.on("OnDisable", function()
  hafen.log("OnDisable fired")
end)
