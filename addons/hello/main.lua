-- Example addon + standing REGRESSION HARNESS. It exercises the READ / UI / event tiers of hafen.* and re-runs
-- them on every login, so one login re-checks every prior slice. It is READ-ONLY (declares no permissions), so it
-- is default-enabled and needs no consent (D-027/D-028) — the gated WRITE tier (hafen.act: moveTo, …) now lives in
-- the separate, opt-in `walker` addon, which DECLARES "permissions": ["actions"] and is therefore disabled by
-- default; enabling it in Options > AddOns raises a consent dialog (write-actions are a per-addon permission — no
-- global switch).
-- Built on V1+V2+V3: CLIENT-ONLY WORLD GHOSTS — hafen.ghost.new{res, x, y[, a]} places a virtual prop (a Gob with
-- NO server id) in the 3D world; it never reaches the server and grants no advantage, so it is SAFE-tier, NOT
-- gated (D-029) — a visualization, like a HUD overlay (the motivating use is city/base planning). It returns a
-- bridge-owned handle with :move(x,y[,a]) / :pos() / :res() / :clickable(bool) / :destroy(); hafen.ghost.list(
-- [filter]) returns this addon's live ghosts. Ghosts are torn down on reload/disable/relogin (P2). V2 adds OPT-IN
-- CLICKABILITY — clickable=true (or :clickable(bool)) gives a ghost a pick surface; a click on it is detected
-- client-side and CONSUMED before any server "click" (your character never walks/interacts — still SAFE-tier,
-- D-032), firing the per-ghost onClick and the owner-scoped GhostClicked{ghost,button,x,y} event; a non-clickable
-- ghost is click-through. V3 adds LOOK & ORIENTATION — new{...alpha=, tint=, a=, sdt=}, and the handle verbs
-- :rotate(a) / :setRes(res[,sdt]) / :alpha(0..1) / :tint{r,g,b[,a]} / :show() / :hide(). Here, at OnEnterWorld the
-- harness spawns a NON-clickable log cabin a few tiles away — ROTATED 45° and TRANSLUCENT (the V3 look) — reads
-- list()/pos(), :moves it, then LIVE-SWAPS its resource + rotates it (V3), hides & re-shows it, and auto-destroys
-- it after 8s (the V1+V3 regression); ':hello ghost' toggles a CLICKABLE, translucent cabin at your position whose
-- onClick live-cycles :rotate + :alpha (V2 click driving V3 look; you do NOT move — still SAFE-tier).
-- Built on gap subsystem A7: MOVEMENT SPEED — hafen.speed reads the crawl/walk/run/sprint selector
-- (get() -> current speed 0..3, max() -> highest currently-selectable, name([n]) -> display name); read-only
-- here, since changing speed is the gated Phase-4 action tier. Built on gap subsystem A6: KIN / BUDDY ROSTER —
-- hafen.kin reads the Kin window (list([filter]) -> {id,name,group,color,online}, find(nameOrId)) and fires
-- KinChanged when a kin is added/removed or flips online/offline. Built on gap subsystem A2: RADAR / MINIMAP ICONS — hafen.radar reads the character's gob-icon
-- registry (categories() -> {name,res,show,notify} per category) and can flip a category's show (draw it on
-- the minimap) or notify (sound + msg when one appears) flag over every match of a filter
-- (setVisible/setNotify(filter,on); filter = nil=all / name substring / predicate). It IS the same registry
-- the in-client "Icon settings" window edits, so hello only READS it here (mutating would persist to your real
-- radar config); try the setters from :lua (see docs/addons/a2-radar.md). Built on gap subsystem A1: MAP
-- MARKERS — hafen.markers reads the client's on-disk map DB
-- (list([filter]) / nearest([filter]) -> marker snapshots {id,name,type,seg,tc, color|icon, x,y,dist}),
-- ADDS a persistent PLAYER marker at a WORLD position (add(name,x,y[,opts])) and REMOVES it (remove(ref)); the
-- global MarkersChanged event fires when the marker set changes. A marker's PERSISTENT anchor is seg+tc (it
-- survives a relog — there is no global position, coverage-gaps C4); the x,y/dist are session-local (present
-- only while the marker is in your current segment). Ctrl+Shift+M drops (and, pressed again, removes) a "Hello
-- marker" at your position — watch it appear on the map (M) and the corner minimap. add() writes the shared
-- on-disk DB so it persists, but hello removes its own demo marker on disable/reload so the regression harness
-- never pollutes your map. Built on Phase 3b WIDGET MODELS — hafen.ui.adopt(id) adopts a live SERVER widget (by the desc.id a 3a
-- onWidgetCreate observer hands out) as a hidden MODEL you can hide/show, read items() from, and get lifecycle
-- events on (onItemAdded/onItemRemoved/onDestroy) — "wrap, don't reimplement" (D-009). Here we adopt the MAIN
-- INVENTORY: Ctrl+B hides/shows its grid while it stays live, and :reload/disable un-hides it (the 3b DoD). Item
-- MUTATING verbs (take/drop/transfer/use) are NOT here — they are gameplay actions (the gated Phase-4 tier).
-- Built on 3a WIDGET-CREATION INTERCEPTION — hafen.ui.onWidgetCreate(fn) observes the server's OWN UI as the
-- client builds it (fn(desc) runs per server widget; desc = {id,type,place,caption,parentType}). It also demonstrates GLOBAL HOTKEYS —
-- hafen.key.bind(name, defaultKey, fn) binds a remappable, persisted hotkey (over the client's KeyBinding
-- registry) that fires when no widget consumed the keypress first; here Ctrl+H toggles the custom window, plus
-- an unbound "ping". Because this addon registers hotkeys, a "Hello" section appears under Options > Keybindings.
-- On top of the THREE hook levels — 2c hafen.hook.input (L1:
-- intercept a widget's raw input BEFORE its own handler), 2d hafen.hook.action (L2: intercept the OUTBOUND
-- action a widget sends to the server, arguments already RESOLVED — e.g. a move's destination world coord),
-- and 2e-1 hafen.hook.message (L3: intercept an INBOUND server update BEFORE the widget applies it — swallow
-- it with ev:preventDefault() or rewrite its args with ev:rewrite()). Plus 2b overlays (hafen.ui.overlay on
-- the HUD + hafen.ui.gobOverlay over game objects) and 2a custom windows/widgets + the GOut wrapper. It runs
-- inside the Lua SANDBOX (D-017 strict env + D-018 instruction watchdog) over 1e hafen.store (saved
-- variables), 1d-4 actionbar/equip, 1d-3 study/skills (+ A4: the full Lore & Skills window — buyable skills,
-- credos, and experiences/lore via hafen.char.skillsAvailable/credos/experiences), 1d-2 buffs + FEP/food,
-- 1d-1 vitals, the 1c items/char/party reads, the gob/world/map/player/time/sound reads, the 1b event bus,
-- and timers, and can be
-- RELOADED from disk without a relog (:reload, D-005) and enabled/disabled (:addons, D-006). `hafen` is the API
-- facade; `ADDON` describes this addon ({ id, dir }). The file body runs once at load; then OnLoad, then (on
-- entering the world) OnEnterWorld. On :reload the whole cycle repeats. Every call in is watchdog-armed.

hafen.log("hello loaded (v0.38.0)")

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
-- 4f (read side): each Item snapshot now also carries a `handle` (the item's server widget id) — the
-- ItemRef the gated hafen.act.item(item, verb) verb takes. hello is READ-ONLY, so it just OBSERVES the
-- handle here (the write demo lives in the opt-in `walker` addon); a handle proves the 4f plumbing.
local function readInv(tag)
  local inv = hafen.items.inventory()   -- array of Item snapshots {name,res,num,wear,pos,handle}
  local eq = hafen.items.equipment()    -- array of Item snapshots {..., slot, handle}
  local hand = hafen.items.hand()       -- Item snapshot or nil (cursor item)
  local first = inv[1]
  hafen.log(("[%s] inventory=%d item(s), first=%s x%s handle=%s")
    :format(tag, #inv, first and tostring(first.name or first.res) or "nil",
            first and tostring(first.num or 1) or "-",
            first and tostring(first.handle) or "-"))
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

-- A4 (completes study/skills): the rest of the "Lore & Skills" window beyond the KNOWN skills above.
-- skillsAvailable() = the BUYABLE skills {name,res,cost} (cost = LP price). credos() = the Credos tab:
-- { acquired, available (each an array of {name,res}), pursuing = {name,res,level,levelTotal,quest,
-- questTotal,questId} or nil, cost } (nil until the window is up). experiences() = the Lore tab, each
-- {name,res,score,mtime}. Like the known skills these stream in a beat after enter-world, so read at now
-- (often empty/nil) and +3s. There is NO *Changed event — they change only on explicit, infrequent
-- actions (buy / pursue / quest progress), so an addon reads them on demand (e.g. after its own action).
local function readLore(tag)
  local avail = hafen.char.skillsAvailable()
  local cr = hafen.char.credos()
  local lore = hafen.char.experiences()
  hafen.log(("[%s] skillsAvailable=%d, first=%s%s"):format(tag, #avail,
    avail[1] and tostring(avail[1].name) or "none",
    avail[1] and (" cost=%s LP"):format(tostring(avail[1].cost)) or ""))
  if cr then
    local p = cr.pursuing
    hafen.log(("[%s] credos: acquired=%d available=%d cost=%s, pursuing=%s%s"):format(tag,
      #cr.acquired, #cr.available, tostring(cr.cost),
      p and tostring(p.name) or "none",
      p and (" (lvl %s/%s, quest %s/%s)"):format(
        tostring(p.level), tostring(p.levelTotal), tostring(p.quest), tostring(p.questTotal)) or ""))
  else
    hafen.log(("[%s] credos: nil (Lore & Skills window not up yet)"):format(tag))
  end
  hafen.log(("[%s] experiences=%d, first=%s%s"):format(tag, #lore,
    lore[1] and tostring(lore[1].name) or "none",
    lore[1] and (" score=%s"):format(tostring(lore[1].score)) or ""))
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

-- A1: map markers via hafen.markers. list()/nearest() read the client's on-disk map DB; each snapshot is
-- {id, name, type ("player"|"system"), seg, tc={x,y} (the persistent anchor), color|icon, and — when the
-- marker is in your current segment — x,y (world) + dist (from you)}. The DB streams in a beat after
-- enter-world (like the rest of the HUD), so read at now (often 0) and +3s. Most markers a character has are
-- SYSTEM markers the server pushed (quest/tracked pins); a fresh spot may have none until you add one (Ctrl+Shift+M).
local function readMarkers(tag)
  local list = hafen.markers.list()
  local near = hafen.markers.nearest()
  local first = list[1]
  hafen.log(("[%s] markers=%d, first=%s%s, nearest=%s%s"):format(tag, #list,
    first and tostring(first.name or first.type) or "none",
    first and (" @tc %d,%d"):format(first.tc.x, first.tc.y) or "",
    near and tostring(near.name or near.type) or "none",
    (near and near.dist) and (" dist=%.1f"):format(near.dist) or ""))
end

-- A2: RADAR / minimap icon categories via hafen.radar. categories([filter]) lists every gob-icon category the
-- character has seen as {name (tooltip), res (stable id), show, notify}; the registry (GobIcon.Settings) is the
-- SAME one the in-client "Icon settings" window edits. hello is READ-ONLY here: the setters
-- setVisible(filter,on)/setNotify(filter,on) PERSIST to your real radar config, so mutating from the harness
-- would disturb it -- test them yourself from :lua (e.g. `hafen.radar.setVisible("boar", false)`, watch the
-- minimap, then flip it back). Like the rest of the HUD the registry is empty until it streams in and grows as
-- new icon types are seen, so read at now (often 0) and +3s.
local function readRadar(tag)
  local cats = hafen.radar.categories()
  local shown, notif = 0, 0
  for _, c in ipairs(cats) do
    if c.show then shown = shown + 1 end
    if c.notify then notif = notif + 1 end
  end
  local first = cats[1]
  hafen.log(("[%s] radar=%d categor(ies), %d shown, %d notify, first=%s%s"):format(tag, #cats, shown, notif,
    first and tostring(first.name or first.res) or "none",
    first and (" [show=%s notify=%s]"):format(tostring(first.show), tostring(first.notify)) or ""))
end

-- A6: KIN / BUDDY ROSTER via hafen.kin. list([filter]) returns your kin as {id, name, group (0..7),
-- color={r,g,b,a} (the group's colour), online (bool)}; filter is the canonical nil=all / name-substring /
-- predicate. find(nameOrId) returns one entry (number = by id, string = exact case-insensitive name). Like
-- the rest of the HUD the Kin list streams in a beat after enter-world, so read at now (often 0) and +3s.
-- hello is READ-ONLY here (kin add/remove/rename is the gated Phase-4 action tier). We also demo find() on
-- the first listed kin's name, and count how many are currently online.
local function readKin(tag)
  local kin = hafen.kin.list()
  local online = 0
  for _, k in ipairs(kin) do if k.online then online = online + 1 end end
  local first = kin[1]
  local found = first and hafen.kin.find(first.name)     -- round-trip find() by name
  hafen.log(("[%s] kin=%d (%d online), first=%s%s, find(name)->%s"):format(tag, #kin, online,
    first and tostring(first.name) or "none",
    first and (" [group=%d online=%s]"):format(first.group, tostring(first.online)) or "",
    found and tostring(found.name) or "nil"))
end

-- A7: MOVEMENT SPEED via hafen.speed. get() returns the CURRENT speed as 0..3 (0=crawl 1=walk 2=run 3=sprint)
-- or nil if the speed selector (the crawl/walk/run/sprint toggle at the bottom of the HUD) isn't up yet;
-- max() returns the highest speed currently SELECTABLE (speeds 0..max are available); name([n]) returns a
-- speed's display name (default = current). Like the rest of the HUD the widget streams in a beat after
-- enter-world, so read at now (often nil) and +3s. hello is READ-ONLY here -- changing speed is the gated
-- Phase-4 action tier; the classic speed addon reads get() in a keybind and (Phase 4) sets the next speed.
local function readSpeed(tag)
  local cur = hafen.speed.get()
  if cur == nil then
    hafen.log(("[%s] speed: nil (selector not up yet)"):format(tag)); return
  end
  hafen.log(("[%s] speed: cur=%d (%s), max=%s"):format(tag, cur,
    tostring(hafen.speed.name()), tostring(hafen.speed.max())))
end

-- A8: CRAFTING via hafen.craft. current() returns the OPEN recipe/craft window (a Makewindow) as {recipe,
-- inputs, outputs, qmod, tools}, or nil when none is open. inputs/outputs are {res, name, num, opt} specs
-- (res = the DISPLAYED resource's stable name -- the constraint category when the recipe accepts one, else the
-- concrete item; num = required/produced count, -1 = unspecified ~ 1; opt = an optional ingredient / chance
-- byproduct); qmod (quality-affecting inputs) and tools (required tools) are {res, name} arrays. hello is
-- READ-ONLY here -- craft.make (actually crafting the item) is the gated Phase-4 action tier -- and there is NO
-- CraftChanged event (a recipe changes only when you open one), so this is READ ON DEMAND. At login no craft
-- window is open, so the now/+3s passes just show "none"; open any recipe (a crafting-menu entry) and type
--   :hello craft   to dump its inputs / outputs / tools.
local function craftLine(s)                     -- one input/output spec -> "name xN[ opt]"
  return ("%s x%d%s"):format(tostring(s.name or s.res or "?"), s.num or -1, s.opt and " opt" or "")
end
local function readCraft(tag)
  local c = hafen.craft.current()
  if not c then hafen.log(("[%s] craft: none open"):format(tag)); return end
  local i1 = c.inputs[1]
  hafen.log(("[%s] craft '%s': %d input(s), %d output(s), %d qmod, %d tool(s)%s"):format(
    tag, tostring(c.recipe), #c.inputs, #c.outputs, #c.qmod, #c.tools,
    i1 and (", in1=" .. craftLine(i1)) or ""))
end
local function dumpCraft()                       -- :hello craft -- the full breakdown of the open recipe
  local c = hafen.craft.current()
  if not c then hafen.log(":hello craft -> no craft/recipe window open (open one first)"); return end
  hafen.log((":hello craft -> recipe '%s'"):format(tostring(c.recipe)))
  for i, s in ipairs(c.inputs)  do hafen.log(("  input[%d]  %s"):format(i, craftLine(s))) end
  for i, s in ipairs(c.outputs) do hafen.log(("  output[%d] %s"):format(i, craftLine(s))) end
  for i, r in ipairs(c.qmod)    do hafen.log(("  qmod[%d]   %s"):format(i, tostring(r.name or r.res))) end
  for i, r in ipairs(c.tools)   do hafen.log(("  tool[%d]   %s"):format(i, tostring(r.name or r.res))) end
end

-- A9: QUEST LOG via hafen.quests. list([filter]) returns quest snapshots {id, name (the quest title), res
-- (stable id), status ("pending"/"done"/"failed"/"disabled"), mtime} for BOTH the Current (active) and
-- Completed tabs; filter is the canonical nil=all / name-substring / predicate (so "only active" is a status
-- predicate). selected() returns the quest currently OPEN in the log -- the ONLY one whose conditions the
-- client loads -- plus conds={{desc, status ("pending"/"done"/"failed"), text?}}, or nil when nothing is
-- selected. Like the rest of the character sheet the quest log streams in a beat after enter-world, so read
-- at now (often 0) and +3s. hello is READ-ONLY (there is no quest action tier); we subscribe to QuestAdded /
-- QuestDone below, and ':hello quest' dumps the selected quest's objectives on demand.
local function readQuests(tag)
  local all    = hafen.quests.list()
  local active = hafen.quests.list(function(q) return q.status == "pending" or q.status == "disabled" end)
  local first  = all[1]
  local sel    = hafen.quests.selected()
  hafen.log(("[%s] quests=%d (%d active), first=%s%s, selected=%s"):format(tag, #all, #active,
    first and tostring(first.name) or "none",
    first and (" [%s]"):format(tostring(first.status)) or "",
    sel and ("'%s' (%d cond)"):format(tostring(sel.name), #sel.conds) or "none"))
end
local function dumpQuest()                       -- :hello quest -- the selected quest + its objectives
  local q = hafen.quests.selected()
  if not q then hafen.log(":hello quest -> no quest selected (open the Quest Log and click a quest)"); return end
  hafen.log((":hello quest -> '%s' [%s] -- %d condition(s)"):format(
    tostring(q.name), tostring(q.status), #q.conds))
  for i, c in ipairs(q.conds) do
    hafen.log(("  cond[%d] [%s] %s%s"):format(i, tostring(c.status), tostring(c.desc),
      c.text and (" -- " .. c.text) or ""))
  end
end

-- A9-2: WOUNDS via hafen.wounds. list([filter]) returns your wounds as {id, name, res, severity, parentid,
-- level}: wounds form a TREE (parentid = the parent wound's id, -1 = a root wound; level = the client's
-- computed depth for indentation), and severity is the magnitude the client shows beside the wound (a
-- content-defined string, usually a number -- NOT seconds; nil while it resolves). has(needle) tests presence
-- by a name/res substring (like buffs.has). filter is the canonical nil=all / name-substring / predicate. Like
-- the rest of the character sheet the wound list streams in a beat after enter-world, so read at now (often 0)
-- and +3s. hello is READ-ONLY (wounds heal by playing / tending -- there is no wound action tier); we
-- subscribe to WoundChanged below, and ':hello wound' dumps the full wound tree on demand. Most characters
-- have 0 wounds -- an empty read is normal; take a hit (or open Health & Wounds on a wounded char) to see one.
local function readWounds(tag)
  local list = hafen.wounds.list()
  local first = list[1]
  hafen.log(("[%s] wounds=%d, first=%s%s"):format(tag, #list,
    first and tostring(first.name or first.res) or "none",
    (first and first.severity) and (" sev=%s"):format(tostring(first.severity)) or ""))
end
local function dumpWounds()                       -- :hello wound -- the full wound tree (name/severity, indented)
  local list = hafen.wounds.list()
  if #list == 0 then hafen.log(":hello wound -> no wounds (nice)"); return end
  hafen.log((":hello wound -> %d wound(s):"):format(#list))
  for _, w in ipairs(list) do
    hafen.log(("  %s%s%s [id=%s parent=%s]"):format(("  "):rep(w.level or 0),
      tostring(w.name or w.res),
      w.severity and (" (sev " .. tostring(w.severity) .. ")") or "",
      tostring(w.id), tostring(w.parentid)))
  end
end

-- A10: COMBAT SCHOOLS via hafen.fight. This is the OUT-OF-COMBAT maneuver-deck builder (the character sheet's
-- "Martial Arts & Combat Schools" tab, FightWnd) -- distinct from the in-combat hafen.combat.* view (live
-- cooldowns). maneuvers([filter]) returns every combat maneuver/attack you know as {res, name, avail (how many
-- you can slot), used (how many you have slotted)}; deck() returns the current school's configured card LAYOUT
-- as {slot (0-based deck index), key (the hotkey "1".."5"/"⇧1".."⇧5"), res, name, used} for the filled slots;
-- summary() returns the scalars {maxact (the action-point budget cap), used (total spent), nact (deck size),
-- nsave (saved-school slots), usesave (the active slot, 0-based)}, or nil before the tab exists. Like the rest
-- of the character sheet it streams in a beat after enter-world, so read at now (often nil/empty) and +3s. hello
-- is READ-ONLY -- editing a school / switching saved schools is the gated Phase-4 tier -- and there is NO
-- FightChanged event (a school changes only on explicit action), so this is READ ON DEMAND: ':hello fight'
-- dumps the full deck (by hotkey) + your known maneuvers.
local function readFight(tag)
  local s = hafen.fight.summary()
  if not s then hafen.log(("[%s] fight: nil (combat-schools tab not up yet)"):format(tag)); return end
  local man  = hafen.fight.maneuvers()
  local deck = hafen.fight.deck()
  hafen.log(("[%s] fight: %d maneuver(s), deck=%d/%d filled, used=%d/%d, school slot=%d/%d"):format(
    tag, #man, #deck, s.nact, s.used, s.maxact, s.usesave, s.nsave))
end
local function dumpFight()                        -- :hello fight -- the deck (by hotkey) + known maneuvers
  local s = hafen.fight.summary()
  if not s then hafen.log(":hello fight -> combat-schools tab not up yet (enter the world first)"); return end
  hafen.log((":hello fight -> used %d/%d action points, active school slot %d (of %d), deck size %d"):format(
    s.used, s.maxact, s.usesave, s.nsave, s.nact))
  local deck = hafen.fight.deck()
  if #deck == 0 then hafen.log("  deck: (empty -- nothing slotted)") end
  for _, d in ipairs(deck) do
    hafen.log(("  [%s] %s x%d"):format(tostring(d.key), tostring(d.name or d.res), d.used or 0))
  end
  local man = hafen.fight.maneuvers()
  hafen.log(("  known maneuvers: %d"):format(#man))
  for i, m in ipairs(man) do
    if i > 8 then hafen.log(("  ... and %d more"):format(#man - 8)); break end
    hafen.log(("    %s (avail %d, used %d)"):format(tostring(m.name or m.res), m.avail or 0, m.used or 0))
  end
end

-- PHASE 4a/4b/4c: the gated WRITE-ACTIONS tier (hafen.act.moveTo, …) is exercised by the separate, opt-in
-- `walker` addon (see addons/walker/), NOT here. hello is the always-on READ-ONLY regression harness (declares no
-- permissions), so it is default-enabled and needs no consent. A write-declaring addon is instead disabled by
-- default and enabling it raises a consent dialog — write-actions are a PER-ADDON permission with no global switch
-- (D-027/D-028). See docs/addons/phase-4c-enable-consent-dialog.md.

-- 3b: WIDGET MODEL (hafen.ui.adopt). We adopt the MAIN INVENTORY as a model down in the onWidgetCreate observer
-- (the 3a -> 3b flow: observe a widget's creation, then adopt it by desc.id). invModel is that handle (nil until
-- the inventory is observed at login). readBags reads it: item count + first item (via model:items(), the same
-- Item snapshots as hafen.items.inventory) + whether its grid is currently shown. The KEY property: a hidden
-- server widget stays bound to its id, so items() and the onItemAdded/onItemRemoved events keep working while it
-- is hidden -- a perfect headless model. Like the rest of the inventory data, items stream in a beat after
-- enter-world, so this is read at now (often 0) and +3s.
local invModel            -- the adopted inventory model (set in the observer below; nil after :reload until relog)
local itemsAdded, itemsRemoved = 0, 0
-- The ~dozen items already in the backpack fire onItemAdded as they stream in at login (like BuffAdded does for
-- existing buffs). So log only the first few of that initial fill, then flip bagsReady a few seconds in and log
-- EVERY live add/remove after that -- so a pick-up/drop while the grid is hidden is clearly visible in the log.
local bagsReady = false
local function readBags(tag)
  if not invModel then hafen.log(("[%s] bags: inventory not adopted yet"):format(tag)); return end
  local items = invModel:items()
  hafen.log(("[%s] bags: %d item(s) via model, first=%s, grid-visible=%s"):format(tag, #items,
    items[1] and tostring(items[1].name or items[1].res) or "none", tostring(invModel:visible())))
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
  readBuffs("now"); readFood("now"); readStudy("now"); readLore("now"); readActionbar("now"); readBags("now"); readMarkers("now"); readRadar("now"); readKin("now"); readSpeed("now"); readCraft("now"); readQuests("now"); readWounds("now"); readFight("now")
  hafen.timer.after(3, function()
    readPlace("+3s"); readInv("+3s"); readChar("+3s"); readVitals("+3s")
    readBuffs("+3s"); readFood("+3s"); readStudy("+3s"); readLore("+3s"); readActionbar("+3s"); readBags("+3s"); readMarkers("+3s"); readRadar("+3s"); readKin("+3s"); readSpeed("+3s"); readCraft("+3s"); readQuests("+3s"); readWounds("+3s"); readFight("+3s")
    bagsReady = true   -- 3b: initial item fill done -> now log EVERY live inventory add/remove
    if invModel then hafen.log("3b: bags ready -- move an item in/out now (even with the grid hidden via Ctrl+B) and it logs") end
  end)

  -- 1c-2: an audible confirmation ping (a client-bundled sound), proving hafen.sound.play works.
  hafen.sound.play("sfx/msg")
end)

-- V1+V3: CLIENT-ONLY WORLD GHOSTS (hafen.ghost). A ghost is a virtual prop rendered in the 3D world at world
-- coords — a Gob with NO server id, so it never reaches the server and grants no advantage: SAFE-tier, NOT
-- gated (D-029), a visualization like a HUD overlay. hafen.ghost.new{res,x,y[,a]} returns a bridge-owned handle
-- (:move/:pos/:res/:destroy + V3 :rotate/:setRes/:alpha/:tint/:show/:hide); hafen.ghost.list([filter]) lists this
-- addon's live ghosts. The visual streams in a beat later (the resource resolves on a loader thread, dodging
-- Loading), so the handle works immediately while the prop appears shortly after. This handler is the V1+V3
-- REGRESSION: it spawns a log cabin ~3 tiles E of you ROTATED 45° and TRANSLUCENT with a bluish tint (the V3
-- "ghost" look — the DoD), reads list()/pos(), :moves it 2 tiles N (V1), then at +3s LIVE-SWAPS its resource and
-- :rotates it (V3 res-swap DoD), at +5s :hide()s and +6s :show()s it, and auto-destroys it at +8s (watch the
-- translucent rotated cabin appear, jump north, morph into another building, blink, then vanish — :reload/disable
-- would remove it too).
hafen.events.on("OnEnterWorld", function()
  local p = hafen.gob.pos("player")
  if not p then hafen.log("V1: ghost demo skipped -- no player position yet"); return end
  local g = hafen.ghost.new{
    res = "gfx/terobjs/arch/logcabin", x = p.x + 33, y = p.y,     -- +3 tiles E (tile=11)
    a = math.pi / 4,                                              -- V3: rotated 45°
    alpha = 0.5,                                                  -- V3: translucent "ghost" look (the DoD)
    tint = { r = 120, g = 180, b = 255, a = 110 },                -- V3: bluish colour overlay
  }
  if not g then hafen.log("V1: hafen.ghost.new returned nil (no map view yet?)"); return end
  local q = g:pos()
  hafen.log(("V1+V3: ghost spawned (%s) at %.0f,%.0f a=%.2f -- list=%d; translucent+rotated, :move 2 tiles N")
    :format(tostring(g:res()), q.x, q.y, q.a, #hafen.ghost.list()))
  g:move(p.x + 33, p.y + 22)                                     -- prove :move (2 tiles N); the prop follows
  hafen.timer.after(3, function()
    g:setRes("gfx/terobjs/arch/timberhouse"):rotate(math.pi)     -- V3: live res-swap (DoD) + :rotate, chained
    hafen.log(("V3: ghost res-swapped -> %s + rotated 180°"):format(tostring(g:res())))
  end)
  hafen.timer.after(5, function() g:hide(); hafen.log("V3: ghost :hide()") end)   -- V3: remove from scene
  hafen.timer.after(6, function() g:show(); hafen.log("V3: ghost :show()") end)   -- V3: re-add
  hafen.timer.after(8, function()
    g:destroy()                                                  -- prove :destroy; teardown would also do this
    hafen.log(("V1: ghost auto-destroyed -- list=%d"):format(#hafen.ghost.list()))
  end)
end)

-- V2: CLICKABLE GHOSTS + GhostClicked. A ghost is opt-in clickable (clickable=true at new, or g:clickable(bool)).
-- A click on a clickable ghost is detected CLIENT-SIDE and CONSUMED before any server "click" — so your character
-- never walks or interacts, and NOTHING reaches the server (still SAFE-tier, D-032). It fires the per-ghost
-- onClick AND this owner-scoped GhostClicked event {ghost, button, x, y}. A NON-clickable ghost is click-through
-- (normal play unaffected — e.g. the V1 auto-demo cabin above: clicking it walks you there). The ':hello ghost'
-- cabin below is created CLICKABLE with its own onClick, so clicking it logs twice (its onClick + this event) and
-- does NOT move you — that is the V2 DoD. ev.button: 1=left, 3=right; ev.x/ev.y = the clicked world point.
hafen.events.on("GhostClicked", function(ev)
  hafen.log((":GhostClicked -> button=%d at %.0f,%.0f (client-only detection; no server click was sent)")
    :format(ev.button, ev.x, ev.y))
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

-- A1: MarkersChanged fires when the map's marker set changes — the server pushing a system/quest marker
-- (markobj), you or an addon adding/removing one, or a segment merge re-keying them. Payload is {count}.
-- A few may fire at login as server markers stream in; log the first few so it does not flood.
local markersSeen = 0
hafen.events.on("MarkersChanged", function(ev)
  markersSeen = markersSeen + 1
  if markersSeen <= 5 then
    hafen.log(("MarkersChanged: %d marker(s) (%d)"):format(ev.count, markersSeen))
  end
end)

-- A6: KinChanged fires when the roster changes — a kin added/removed, renamed/regrouped, or (the one a
-- kin-alert addon most wants) an online/offline flip. Payload is the new kin list (the same shape as
-- hafen.kin.list()). A few fire at login as the roster streams in; log the first few, then keep our own
-- last-online set so we can name WHO just came online/offline on every later change.
local kinSeen = 0
local kinOnline = {}          -- name -> true while we believe them online (so we can report transitions)
hafen.events.on("KinChanged", function(list)
  kinSeen = kinSeen + 1
  local now = {}
  for _, k in ipairs(list) do
    if k.name then                                   -- names are always present, but keying a table by nil errors
      now[k.name] = k.online or false
      if k.online and not kinOnline[k.name] then hafen.log(("KinChanged: %s came ONLINE"):format(k.name)) end
      if (not k.online) and kinOnline[k.name] then hafen.log(("KinChanged: %s went offline"):format(k.name)) end
    end
  end
  kinOnline = now
  if kinSeen <= 5 then
    hafen.log(("KinChanged: %d kin (%d)"):format(#list, kinSeen))
  end
end)

-- A9: QuestAdded fires when a new ACTIVE quest (pending/disabled) appears; QuestDone when a previously-active
-- quest is completed/failed. Payload = the quest snapshot {id, name, res, status, mtime}. A few QuestAdded
-- may fire at login as active quests stream in (the completed HISTORY is recorded silently -- no event), so
-- log only the first few of those, then narrate every completion in full.
local questAddedSeen = 0
hafen.events.on("QuestAdded", function(q)
  questAddedSeen = questAddedSeen + 1
  if questAddedSeen <= 5 then
    hafen.log(("QuestAdded: '%s' [%s] (%d)"):format(tostring(q.name), tostring(q.status), questAddedSeen))
  end
end)
hafen.events.on("QuestDone", function(q)
  hafen.log(("QuestDone: '%s' -> %s"):format(tostring(q.name), tostring(q.status)))
end)

-- A9-2: WoundChanged fires when the wound set changes -- a wound added, healed/removed, or its severity
-- advancing (a wound getting worse), and as wounds/severity stream in a beat after enter-world. Payload = the
-- new wound list (same shape as hafen.wounds.list()). This is the signal a wound-alert addon lives on. A few
-- may fire at login as wounds resolve; log the first few (with the first wound's name/severity), then keep
-- narrating the count on every later change.
local woundsSeen = 0
hafen.events.on("WoundChanged", function(list)
  woundsSeen = woundsSeen + 1
  if woundsSeen <= 5 then
    local first = list[1]
    hafen.log(("WoundChanged: %d wound(s)%s (%d)"):format(#list,
      first and (", first=" .. tostring(first.name or first.res)
        .. (first.severity and (" sev " .. tostring(first.severity)) or "")) or "",
      woundsSeen))
  end
end)

-- 3a: WIDGET-CREATION INTERCEPTION (hafen.ui.onWidgetCreate). Observe the server's OWN UI as the client builds
-- it — the foundation for replacing native windows (a bag/inventory reskin, etc.). fn(desc) runs for every
-- SERVER widget as it is placed into the tree, with desc = {id, type, place, caption, parentType} (the targeting
-- descriptor): the inventory is {type="inv", place="inv", parentType="GameUI"}; a cupboard is {type="wnd",
-- place="misc", caption="Cupboard", parentType="GameUI"}. A HUD-placed window reports parentType="GameUI"; item
-- widgets streaming into an inventory report their container instead — so we log only HUD-level widgets + any
-- titled window/container (the interesting replace targets), skipping the item churn. This slice is OBSERVE-ONLY
-- (adopting a widget as a hidden MODEL and drawing a custom VIEW over it comes in a later slice); the return is
-- ignored. Registered in the FILE BODY (no live target needed) so it also catches the burst of windows created
-- at login. Bridge-owned: :reload/disable removes it (the handle also exposes :remove()). VERIFY in-game: open a
-- cupboard/chest or a crafting window and a "3a:" line carrying its caption should appear.
local widgetsSeen = 0
hafen.ui.onWidgetCreate(function(desc)
  local hud = desc.parentType == "GameUI"            -- HUD-placed windows (inv/equ/chr/craft/containers/…)
  if not (hud or desc.caption) then return end        -- skip item/nested widgets (no caption, non-GameUI parent)
  widgetsSeen = widgetsSeen + 1
  if widgetsSeen <= 20 or desc.caption then           -- cap the login burst; always log a titled window/container
    hafen.log(("3a: widget created id=%s type=%s place=%s parent=%s caption=%s")
      :format(tostring(desc.id), tostring(desc.type), tostring(desc.place),
              tostring(desc.parentType), tostring(desc.caption)))
  end

  -- 3b: when the MAIN inventory ({type="inv", place="inv", parentType="GameUI"}) is built, ADOPT it as a model
  -- (hafen.ui.adopt(desc.id)) -- the observe -> adopt handoff. We hold the model to hide/show its grid (Ctrl+B)
  -- and to receive item add/remove events. onDestroy fires if the server ever destroys it (it won't for the main
  -- backpack, but a container/cupboard model would). NB: :reload does NOT recreate the existing inventory, so the
  -- freshly-registered observer won't re-fire for it -- re-adoption after :reload waits for a relog (or 3c's
  -- hafen.ui.replace, which FINDS an already-open window by descriptor). adopt() returns nil if the id is gone.
  if desc.type == "inv" and desc.place == "inv" and desc.parentType == "GameUI" and not invModel then
    invModel = hafen.ui.adopt(desc.id)
    if invModel then
      hafen.log(("3b: adopted main inventory (id=%s) -- Ctrl+B hides/shows its grid; it stays live while hidden")
        :format(tostring(desc.id)))
      invModel:onItemAdded(function(item)
        itemsAdded = itemsAdded + 1
        if bagsReady or itemsAdded <= 3 then          -- initial fill: first few only; after +3s: every live add
          hafen.log(("3b: item ADDED to inventory: %s x%s (total seen %d)%s")
            :format(tostring(item.name or item.res), tostring(item.num or 1), itemsAdded,
                    (invModel and not invModel:visible()) and " [grid hidden -- model still live]" or ""))
        end
      end)
      invModel:onItemRemoved(function(item)
        itemsRemoved = itemsRemoved + 1
        if bagsReady or itemsRemoved <= 3 then
          hafen.log(("3b: item REMOVED from inventory: %s (total seen %d)%s")
            :format(tostring(item.name or item.res), itemsRemoved,
                    (invModel and not invModel:visible()) and " [grid hidden -- model still live]" or ""))
        end
      end)
      invModel:onDestroy(function()
        hafen.log("3b: inventory model destroyed by the server")
        invModel = nil
      end)
    end
  end
end)
hafen.log("3a: onWidgetCreate observer installed -- open a cupboard/chest or a crafting window to see it log")

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
local vitalsFreeze = false  -- 2e: while true, the "set" message hook SWALLOWS meter updates -> the HUD vitals bars freeze (toggle: MIDDLE-click)
local msgHookSeen = 0       -- 2e: how many meter "set" messages the hook has observed while OFF (for the "observed" log lines)

-- R1: CUSTOM IMAGE (hafen.render.image). Load a PNG shipped in THIS addon's own folder (icon.png -- a small
-- green "H" disc) into a bridge-owned TexI handle, then draw it below in the 2a window (native + scaled) and
-- the 2b HUD overlay (anchored). This is a CLIENT-ONLY render asset, NOT an engine .res -- SAFE-tier, NOT
-- gated (D-034), like an overlay. Paths are addon-relative and sandboxed (absolute / ".." are rejected, D-017).
-- The handle exposes :size() -> {w,h} and :dispose(); it is disposed AUTOMATICALLY on :reload/disable (P2), so
-- there is no GL leak (the Phase-R1 DoD). Loaded at OnLoad -> re-loaded on every reload (the env is rebuilt).
local icon   -- the image handle (nil until loaded; a fresh reload rebuilds the env -> nil, re-loaded below)
hafen.events.on("OnLoad", function()
  icon = hafen.render.image("icon.png")
  local s = icon:size()
  hafen.log(("R1: loaded icon.png (%dx%d) -- drawn in the 2a window (native + scaled) and the 2b HUD overlay")
    :format(s.w, s.h))
end)

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
  -- 2e: vitals-freeze state (MIDDLE-click the window to toggle; cyan = the L3 message hook is swallowing meter
  -- updates, so the hp/stamina/energy bars above — and the real HUD meters — freeze until toggled off)
  g:color(vitalsFreeze and 90 or 150, vitalsFreeze and 210 or 150, vitalsFreeze and 235 or 150)
  g:text(("vitals-freeze %s (MMB)"):format(vitalsFreeze and "ON" or "OFF"), 6, 118)
  g:color()
  -- R1: draw the custom image (hafen.render.image) two ways in the top-right, above the bars: native 32x32
  -- and the same handle scaled to 16x16 (g:image with/without a w,h). A nil/disposed handle draws nothing.
  if icon then
    g:image(icon, w - 34, 2)                                      -- native size (32x32) in the top-right corner
    g:image(icon, w - 52, 2, 16, 16)                             -- the SAME image scaled to 16x16, just left of it
  end
  g:color(170, 170, 170); g:rect(0, 0, w, h); g:color()          -- 1px border
end

-- 2e-2: GLOBAL HOTKEY (hafen.key.bind). Bind a remappable, persisted hotkey over the client's KeyBinding
-- registry (namespaced addon/hello/toggle) — Ctrl+H toggles this window's visibility, the WoW "show/hide my
-- panel" pattern. Unlike the input/action/message hooks below, a hotkey needs NO live target, so it is bound
-- here in the FILE BODY (it simply does nothing until you are in-world and the window exists). It fires ONLY
-- when no focused widget consumed the keypress first (a focused text field consumes all ORDINARY typing, so a
-- hotkey on a plainly-typed key is naturally suppressed while typing) and no client binding owns Ctrl+H (addon
-- hotkeys are the fallback, walked after the client's — never a hijack). Exactly the engine's own global-hotkey
-- behaviour (Ctrl+H behaves like the client's Ctrl-bindings). The key is user-remappable in the client's
-- keybind options; the handle exposes :key() (the current key's display name) and :remove(). Bridge-owned:
-- :reload or disabling the addon removes it (the KeyBinding entry itself is kept, so a user's re-map survives).
-- Pass nil or "None" as the default for unbound-by-default. Accepts "F5", "Ctrl+M", "Shift+Alt+Left", a bare
-- letter/digit, etc.
local toggleKey = hafen.key.bind("toggle", "Ctrl+H", function()
  if not panel then hafen.log("2e-2: Ctrl+H pressed, but the window is not up yet"); return end
  local show = not panel:visible()                                -- flip the current (settled/animating) state
  if show then panel:show() else panel:hide() end
  hafen.log(("2e-2: Ctrl+H -> window %s"):format(show and "shown" or "hidden"))
end)
hafen.log(("2e-2: global hotkey bound (%s toggles the window) -- remappable in the keybind options")
  :format(toggleKey:key()))

-- 2e-3: a SECOND hotkey, UNBOUND by default (nil). Because this addon registered a hotkey, a "Hello"
-- section now appears in Options > Keybindings (WoW-style) listing BOTH "toggle" (Ctrl+H) and this "ping".
-- "ping" starts as None, so it does nothing until you ASSIGN it a key there — demonstrating the panel's
-- assign-from-scratch flow and the per-addon grouping. Once bound it plays a sound on press, and the choice
-- persists across restarts exactly like every built-in keybinding.
hafen.key.bind("ping", nil, function()
  hafen.sound.play("sfx/msg")
  hafen.log("2e-3: ping hotkey fired (assigned in Options > Keybindings > Hello)")
end)

-- 3b: a THIRD hotkey (Ctrl+B, "bags") toggling the ADOPTED inventory model's visibility -- hide()/show() a real
-- server widget while it stays live. Open your inventory (Tab), press Ctrl+B: the item grid HIDES (the model is
-- still bound, so items() and the add/remove events keep working -- drop something in and 3b still logs it);
-- press again: it SHOWS. Disabling hello or :reload UN-HIDES it automatically (teardown restores the stock UI) --
-- the Phase-3b DoD. This adds a third row to the "Hello" keybind section (2e-3 grouping). If Ctrl+B is already a
-- client binding the client wins (addon hotkeys are the fallback) -- just re-map "bags" in Options > Keybindings.
hafen.key.bind("bags", "Ctrl+B", function()
  if not invModel then
    hafen.log("3b: Ctrl+B -- inventory not adopted yet (relog to re-adopt; 3c will re-find an open window)")
    return
  end
  if invModel:visible() then invModel:hide() else invModel:show() end
  hafen.log(("3b: Ctrl+B -> inventory grid %s (%d item(s) still live via the model)")
    :format(invModel:visible() and "shown" or "hidden", #invModel:items()))
end)

-- A1: a FOURTH hotkey (Ctrl+Shift+M, "marker") — a TOGGLE that drops a persistent "Hello marker" at your
-- current position (hafen.markers.add at your gob's world coord), or removes it if already placed
-- (hafen.markers.remove). Watch it appear on the map (M) and the corner minimap. add() writes the shared
-- on-disk map DB, so it PERSISTS — but hello removes its own marker on disable/reload (see OnDisable) so the
-- regression harness never pollutes your map. Adds a fourth row to the "Hello" keybind section (2e-3 grouping).
local helloMarker   -- the ref of the demo marker while placed (nil = not placed); session-local
hafen.key.bind("marker", "Ctrl+Shift+M", function()
  if helloMarker then
    hafen.markers.remove(helloMarker)
    helloMarker = nil
    hafen.log("A1: Ctrl+Shift+M -> removed the Hello marker")
    return
  end
  local p = hafen.gob.pos("player")
  if not p then hafen.log("A1: Ctrl+Shift+M -> no player position yet"); return end
  helloMarker = hafen.markers.add("Hello marker", p.x, p.y, { color = { r = 80, g = 220, b = 90 }, onmap = true })
  if helloMarker then
    hafen.log(("A1: Ctrl+Shift+M -> dropped 'Hello marker' at %.0f,%.0f (ref %s) -- press again to remove")
      :format(p.x, p.y, tostring(helloMarker)))
  else
    hafen.log("A1: Ctrl+Shift+M -> could not add marker (map/session location not up yet)")
  end
end)

-- A11: SLASH COMMANDS (hafen.slash). Register a WoW-style ":command" routed to a Lua handler. fn(args) runs when
-- you type ":hello a b c" in the console (chat), with args = a 1-based table of the whitespace-split arguments
-- AFTER the name ("quoted words" group, \\ escapes; the command name itself excluded). Like the hotkeys above it
-- needs NO live target, so it is registered here in the FILE BODY. It is RELOAD-SAFE: a single engine-lifetime
-- console dispatcher routes to the CURRENT handler, so editing this file + :reload swaps the handler with NO
-- duplicate or leaked command (coverage-gaps C1); after disabling hello (+ :reload) ":hello" replies "no addon
-- handles :hello". Reserved engine names (lua / addons / reload) and names a client command already owns are
-- refused with a clear error. The handle exposes :remove(). We demo sub-command dispatch off args[1]: bare :hello
-- greets, ":hello toggle" flips the 2a window (a slash command driving live addon state), ":hello ping" plays a
-- sound, and ":hello echo <text...>" shows the args rejoined (quoting survives — :hello echo "a b" c -> a b c).
local demoGhost   -- V1: the handle of the manual :hello ghost demo while placed (nil = none); session-local
hafen.slash.register("hello", function(args)
  if #args == 0 then
    hafen.log("A11: :hello -- hi from the hello addon! try  :hello toggle | ping | echo <text...> | craft | quest | wound | fight | ghost")
    return
  end
  local sub = args[1]
  if sub == "toggle" then
    if not panel then hafen.log(":hello toggle -> the window is not up yet (enter the world first)"); return end
    local show = not panel:visible()
    if show then panel:show() else panel:hide() end
    hafen.log((":hello toggle -> window %s"):format(show and "shown" or "hidden"))
  elseif sub == "ping" then
    hafen.sound.play("sfx/msg")
    hafen.log(":hello ping -> played sfx/msg")
  elseif sub == "echo" then
    local rest = {}
    for i = 2, #args do rest[#rest + 1] = args[i] end
    hafen.log((":hello echo -> %q"):format(table.concat(rest, " ")))
  elseif sub == "craft" then
    dumpCraft()                                  -- A8: dump the currently-open recipe (open one first)
  elseif sub == "quest" then
    dumpQuest()                                  -- A9-1: dump the selected quest + its objectives (select one first)
  elseif sub == "wound" then
    dumpWounds()                                 -- A9-2: dump the full wound tree (name/severity, indented)
  elseif sub == "fight" then
    dumpFight()                                  -- A10: dump the combat-school deck (by hotkey) + known maneuvers
  elseif sub == "ghost" then
    if demoGhost then                            -- V1: TOGGLE a client-only ghost cabin at your position
      demoGhost:destroy(); demoGhost = nil
      hafen.log((":hello ghost -> destroyed (list=%d)"):format(#hafen.ghost.list()))
    else
      local p = hafen.gob.pos("player")
      if not p then hafen.log(":hello ghost -> no player position yet"); return end
      local spin, faded = 0, false                           -- V3: per-spawn live-look state (closed over by onClick)
      demoGhost = hafen.ghost.new{                            -- V2 clickable + V3 look: a translucent, tinted cabin
        res = "gfx/terobjs/arch/logcabin", x = p.x, y = p.y,
        alpha = 0.6,                                          -- V3: translucent
        tint = { r = 255, g = 210, b = 120 },                -- V3: warm colour overlay
        clickable = true,                                     -- opt-in pick surface (the V2 core)
        onClick = function(g, button, x, y)                   -- fires on click (also via the GhostClicked event)
          spin = spin + math.pi / 4                           -- V3: each click rotates 45°...
          faded = not faded                                   -- ...and toggles opacity
          g:rotate(spin):alpha(faded and 0.3 or 0.85)         -- chained V3 verbs, live on the clicked ghost
          hafen.log((":hello ghost onClick -> button=%d -- CONSUMED (no walk); V3 live rotate a=%.2f alpha=%.2f")
            :format(button, spin, faded and 0.3 or 0.85))
        end,
      }
      if demoGhost then
        hafen.log((":hello ghost -> CLICKABLE translucent cabin at you (%.0f,%.0f) -- CLICK it (won't move; each click rotates + re-fades); :hello ghost again to remove"):format(p.x, p.y))
      else
        hafen.log(":hello ghost -> hafen.ghost.new returned nil (not in the world yet?)")
      end
    end
  else
    hafen.log((":hello got %d arg(s): %s  (try: toggle | ping | echo | craft | quest | wound | fight | ghost)")
      :format(#args, table.concat(args, " | ")))
  end
end)
hafen.log("A11: slash command registered -- type  :hello  in the console (chat) to try it")

hafen.events.on("OnEnterWorld", function()
  if panel then return end                                        -- defensive: create the window once
  panel = hafen.ui.window{
    title   = "Hello 3a",
    size    = { 190, 136 },
    pos     = { 80, 120 },
    onDraw  = drawPanel,
    onClick = function(x, y, button)
      clicks = clicks + 1
      if button == 3 then                                        -- RIGHT-click -> 2d: toggle the action hook
        moveIntercept = not moveIntercept
        hafen.log(("panel RMB #%d -> move-intercept %s"):format(clicks, moveIntercept and "ON" or "OFF"))
      elseif button == 2 then                                     -- MIDDLE-click -> 2e: toggle the message hook
        vitalsFreeze = not vitalsFreeze
        hafen.log(("panel MMB #%d -> vitals-freeze %s"):format(clicks, vitalsFreeze and "ON" or "OFF"))
      else                                                        -- LEFT/other -> 2c: toggle the input hook
        mapLock = not mapLock
        hafen.log(("panel click #%d at %d,%d (button %d) -> map-lock %s")
          :format(clicks, x, y, button, mapLock and "ON" or "OFF"))
      end
      return true                                                 -- truthy = consume the click
    end,
    onClose = function() hafen.log("panel closed (X) -- :reload to bring it back") end,
  }
  hafen.log("2a: custom window up -- drag the title bar, LMB=map-lock, RMB=move-intercept, MMB=vitals-freeze, X=close, Ctrl+H=toggle")

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

  -- 2e: MESSAGE HOOK (hafen.hook.message, L3). Intercept an INBOUND server update at the UI.uimsg choke point,
  -- BEFORE the target widget applies it -- the mirror of 2d's outbound L2. We hook the "set" message and scope it
  -- to the HUD vitals meters (ev.target == "IMeter"; "set" is what LayerMeter uses to update a bar). MIDDLE-click
  -- the window to arm vitals-freeze. While ON, ev:preventDefault() SWALLOWS the meter update, so it never reaches
  -- the widget: the hp/stamina/energy bars -- both in this window and the REAL HUD meters -- FREEZE (and no
  -- VitalsChanged fires, since nothing changed). Toggle it off and the next update thaws them -- fully reversible,
  -- purely cosmetic (the server still knows your real vitals). While OFF we only observe-log the first few meter
  -- "set" messages, proving L3 sees inbound traffic. ev.args is a 1-based snapshot (ev:rewrite(t) could apply new
  -- args instead -- not used here). NB: this handler runs on a Loader thread under the UI lock, so keep it light.
  -- The handle is bridge-owned (:reload/disable removes the hook -- no leak).
  hafen.hook.message("set", function(ev)
    if ev.target ~= "IMeter" then return end                     -- only the HUD vitals/stat meters, not every "set"
    if vitalsFreeze then
      ev:preventDefault()                                        -- swallow it -> the meter never updates (bar freezes)
    elseif msgHookSeen < 3 then
      msgHookSeen = msgHookSeen + 1
      hafen.log(("2e: meter 'set' observed (target=%s, %d arg(s)) (passed through) [#%d]")
        :format(ev.target, #ev.args, msgHookSeen))
    end
  end)
  hafen.log("2e: IMeter 'set' message hook installed -- MIDDLE-click the window to freeze the vitals bars")
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
  -- 2c/2d/2e: surface the hook states here too, so the input+action+message hooks have clear on-HUD feedback
  -- (border turns red while map-lock cancels clicks, orange while move-intercept re-sends moves, cyan while
  -- vitals-freeze swallows meter updates).
  local txt = ("2b HUD  gobs=%d  map-lock=%s  move=%s  freeze=%s"):format(
    gobCount, mapLock and "ON" or "OFF", moveIntercept and "ON" or "OFF", vitalsFreeze and "ON" or "OFF")
  local bw = 340
  local x = math.floor(w / 2 - bw / 2)
  g:color(0, 0, 0, 140); g:frect(x, 2, bw, 18); g:color()        -- translucent backdrop
  if mapLock then g:color(235, 90, 90)                           -- red: L1 cancelling map clicks
  elseif moveIntercept then g:color(245, 160, 60)                -- orange: L2 intercepting + re-sending moves
  elseif vitalsFreeze then g:color(90, 210, 235)                 -- cyan: L3 swallowing meter updates
  else g:color(120, 200, 120) end                                -- green: hooks observing only
  g:rect(x, 2, bw, 18); g:color()
  g:text(txt, x + 6, 4)
  -- R1: an ANCHORED image (g:aimage) just LEFT of the readout box -- ax=1 (right edge at x-4), ay=0.5 (centred).
  if icon then g:aimage(icon, x - 4, 11, 1.0, 0.5) end
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
  if helloMarker then                                   -- A1: clean up our demo marker so the harness never
    hafen.markers.remove(helloMarker)                   -- leaves 'Hello marker' pins on your persistent map DB
    helloMarker = nil
  end
  if demoGhost then demoGhost:destroy(); demoGhost = nil end   -- V1: drop the manual ghost (teardown also does)
  hafen.log("OnDisable fired")
end)
