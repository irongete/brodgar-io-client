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
-- hafen.kin reads the Kin window and is CALLABLE-ONLY (020-kin-oop): hafen.kin() is the roster (an array of
-- interned Kin objects, plus :find/:list/:add), hafen.kin(idOrName) is one Kin (:id/:name/:group/:color/
-- :online/:exists/:gob/:info, with gob:kin() as :gob()'s inverse); it fires KinChanged -- the whole roster
-- as Kin objects -- when a kin is added/removed, renamed/regrouped or flips online/offline. Built on gap subsystem A2: RADAR / MINIMAP ICONS — hafen.radar reads the character's gob-icon
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
-- only while the marker is in your current segment). The 'marker' hotkey drops (and, pressed again, removes) a "Hello
-- marker" at your position — watch it appear on the map (M) and the corner minimap. add() writes the shared
-- on-disk DB so it persists, but hello removes its own demo marker on disable/reload so the regression harness
-- never pollutes your map. Built on the WIDGET ENTITY (029) — every hafen.ui door (root/node/at/inventory/window)
-- hands back ONE interned type, and a container answers for what is inside it: w:items() plus the lifecycle verbs
-- w:onItemAdded/:onItemRemoved/:onDestroy, read with the window VISIBLE (hafen.ui.adopt, which hid a window just so
-- you could look in it, is GONE, and so are hafen.items, :same() and :move()). `==` is the identity test, arity is
-- the verb on geometry, and the write verbs answer only on a widget THIS addon created — readWidgets (once per
-- login, and ':hello widget') asserts that whole contract, refusals included. Here the container is the MAIN
-- INVENTORY: the 'bags' hotkey hides/shows its grid with w:hide()/:show() — the ONE write that answers on a native
-- widget — and the reads keep working, and :reload/disable gives the grid back. Item
-- MUTATING verbs (take/drop/transfer/use) are NOT here — they are gameplay actions (the gated Phase-4 tier).
-- Built on 031 WINDOW LIFECYCLE — hiding one of the windows the CLIENT itself opens now TAKES ITS TOGGLE (its key
-- and its menu button stop reopening it, and the tick goes off), and w:replace(view) — THE VERB (032; the old
-- hafen.ui.replace namespace function and its {id,type,place,caption,parentType} descriptor are a hard cut) — binds
-- the view you pass to that same hide record, so the client's own key drives YOUR window and the tick reads it. The
-- verb hides the ENCLOSING window (w:hide() still hides exactly what you point at), and there is no verb for the
-- toggle itself: ownership follows the hide, and the restore is ONE rule — the window ends up as the user was
-- seeing it. readToggle (once per login, and ':hello wnd') asserts the swallow, the one-owner refusal, both
-- halves of that rule, the verb's THREE ARITIES and the hop itself (install through the grid, read the same view
-- back through its window: ONE record); ':hello wnd swallow' parks the swallowed state so you can press Tab at it
-- yourself.
-- Built on 030.2 SELECTOR EVENTS — hafen.ui.on(selector, "appear"|"disappear", fn) watches the client's OWN UI for
-- a part of it, named with the same selector a lookup uses, and hands the callback the Widget ENTITY (the old
-- hafen.ui.onWidgetCreate and its {id,type,place,caption,parentType} descriptor are GONE). It also demonstrates GLOBAL HOTKEYS —
-- hafen.client:options():keybindings():register(name, fn) declares a remappable, persisted hotkey (over the
-- client's KeyBinding registry) that fires when no widget consumed the keypress first; here 'toggle' shows/hides
-- the custom window, plus 'ping'. Addon hotkeys start UNBOUND: this addon's "Hello" section under
-- Options > Keybindings is where you assign the keys.
-- On top of the THREE hook levels — 2c hafen.hook.input (L1:
-- intercept a widget's raw input BEFORE its own handler), 2d hafen.hook.action (L2: intercept the OUTBOUND
-- action a widget sends to the server, arguments already RESOLVED — e.g. a move's destination world coord),
-- and 2e-1 hafen.hook.message (L3: intercept an INBOUND server update BEFORE the widget applies it — swallow
-- it with ev:preventDefault() or rewrite its args with ev:rewrite()). Plus 2b overlays (hafen.ui.overlay on
-- the HUD + hafen.ui.gobOverlay over game objects) and 2a custom windows/widgets + the GOut wrapper. It runs
-- inside the Lua SANDBOX (D-017 strict env + D-018 instruction watchdog) over 1e hafen.store (saved
-- variables), 1d-4 actionbar/equip, 1d-3 study/skills (+ A4: the full Lore & Skills window — buyable skills,
-- credos, and experiences/lore via hafen.char.skillsAvailable/credos/experiences), 1d-2 buffs + FEP/food,
-- 1d-1 the HUD meters, the 1c items/char/party reads, the gob/world/map/player/time/sound reads, the 1b event bus,
-- and timers, and can be
-- RELOADED from disk without a relog (:reload, D-005) and enabled/disabled (:addons, D-006). `hafen` is the API
-- facade; `ADDON` describes this addon ({ id, dir }). The file body runs once at load; then OnLoad, then (on
-- entering the world) OnEnterWorld. On :reload the whole cycle repeats. Every call in is watchdog-armed.

-- Built on 023 THE ACTION MENU — hafen.menugrid is the catalogue of everything the character can DO (the 4x4
-- grid), callable-only: hafen.menugrid() = every entry as interned Pagina objects (+ :find/:roots/:list),
-- hafen.menugrid(key) = one, keyed by SHAPE ('/' => resource name = the identity, anything else => display
-- name = a search convenience). hello scans it at login (short at first — resources resolve async — then
-- full at +3s), re-checks the OOP contract, and ':hello actions' dumps the category tree; the verb
-- pag:use() is a real action, so only the opt-in `walker` fires it (':walker menugrid Dig').

-- And on 024 AUDIO — hafen.sound is callable-only too: hafen.sound(name) = one interned Sound
-- (:res/:play([volume])/:stop/:playing/:info), hafen.sound() = the Sounds THIS addon still has in the air,
-- silenced for it on disable/:reload. The flat hafen.sound.play is gone and hafen.music does not exist at
-- all (this server has no MIDI content; the "music" you hear is ambient audio, on the ambientVolume slider).
-- hello checks that contract at login (readSound), pings with ':hello ping' and toggles a long clip through
-- the live set with ':hello sound'.
hafen.log("hello loaded (v0.64.0)")

-- LuaJ 3.0.1's string.format is NOT C's: it ignores the PRECISION of %f/%g/%e ("%.3f" prints
-- 10.852199999987988, the raw double) and the WIDTH of %s ("%-12s" pads nothing); only %d honours a
-- width. So any number meant to be read as "0.031 ms" has to be rounded and padded by hand. `fx` rounds
-- to d decimals and keeps the trailing zeros tostring() drops; `padr` left-aligns into n columns.
-- (Used by the 019 profiling dumps below; the older dumps in this file still print raw doubles.)
local function fx(v, d)
  if v == nil then return "--" end
  d = d or 2
  local neg = v < 0
  if neg then v = -v end
  local m = 10 ^ d
  local n = math.floor(v * m + 0.5)
  local i = math.floor(n / m)
  local f = tostring(math.floor(n - i * m))
  while #f < d do f = "0" .. f end
  return (neg and "-" or "") .. tostring(i) .. ((d > 0) and ("." .. f) or "")
end

local function padr(s, n)
  s = tostring(s)
  return (#s >= n) and s or (s .. string.rep(" ", n - #s))
end

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

-- N1: self-check hafen.json (parse/encode) — the JSON serializer, ungated (pure CPU), independent of the
-- network. Encode a mixed Lua table to compact JSON and parse it straight back, proving: the round-trip
-- (objects, a numeric array, a boolean, a float), the null->nil caveat (a JSON null yields an ABSENT key,
-- not a false value), integer cleanliness ({"n":5} -> 5, not 5.0), and that malformed input raises a
-- pcall-able error instead of crashing the addon. Logged so one login re-verifies the serializer too.
hafen.events.on("OnLoad", function()
  local src = { iron = 5, name = "ore", frac = 2.5, nums = { 10, 20, 30 }, flag = true }
  local enc = hafen.json.encode(src)
  local back = hafen.json.parse(enc)
  local roundtrip = (back.iron == 5) and (back.name == "ore") and (back.frac == 2.5)
    and (#back.nums == 3) and (back.nums[2] == 20) and (back.flag == true)
  local nul = hafen.json.parse('{"a":1,"b":null}')
  local nullhole = (nul.a == 1) and (nul.b == nil)                 -- JSON null -> absent key
  local intclean = tostring(hafen.json.parse('{"n":5}').n) == "5"   -- integer, not "5.0"
  local okParse = pcall(function() return hafen.json.parse("{bad}") end)   -- malformed -> error
  hafen.log(("json: roundtrip=%s null->nil=%s int-clean=%s malformed-caught=%s enc=%s")
    :format(tostring(roundtrip), tostring(nullhole), tostring(intclean), tostring(not okParse), enc))
end)

-- 1c-2: read the map/projection data at the player's position and log it with a tag. The grid, terrain
-- height, and camera for the current spot stream in shortly AFTER entering the world, so right at
-- OnEnterWorld these may be nil (the reads are Loading-guarded); we call this again after a short delay
-- to show them resolve. worldToTile is pure math and always works.
local function readPlace(tag)
  local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
  local p = me and me:pos()
  if not p then return end
  local tile = hafen.map.tile(p.x, p.y)
  local gp = hafen.map.gridPos()               -- no args = player: the persistent grid anchor
  local t = hafen.map.worldToTile(p.x, p.y)
  local s = hafen.player():worldToScreen(p.x, p.y)
  hafen.log(("[%s] tile=%s height=%s worldToTile=%d,%d"):format(tag,
    tile and (tile.name or tile.id) or "nil", tostring(hafen.map.height(p.x, p.y)), t.x, t.y))
  hafen.log(("[%s] gridPos=%s worldToScreen=%s"):format(tag,
    gp and (gp.gridId .. " @" .. ("%.0f,%.0f"):format(gp.x, gp.y)) or "nil",
    s and ("%.0f,%.0f"):format(s.x, s.y) or "nil"))
end

-- 1c-3: read the inventory / equipment / cursor. 029.3 HARD-CUT hafen.items: items are a RELATION on their
-- container now, so the backpack and the equipory are looked up as WIDGETS (hafen.ui.inventory() /
-- hafen.ui.equipment(), the same entity every other hafen.ui entry point hands back) and asked for :items().
-- The cursor item is the odd one out — it is not a widget you can walk — so hafen.ui.hand() stays a snapshot.
-- Item NAMES come from resolved item info, which (like the inventory widget itself) can stream in a beat after
-- enter-world, so this is read twice — immediately and after a short delay — like the map reads above.
-- 4f (read side): each Item snapshot still carries a `handle` (the item's server widget id) — the ItemRef the
-- gated hafen.act.item(item, verb) verb takes. hello is READ-ONLY, so it just OBSERVES the handle here (the
-- write demo lives in the opt-in `walker` addon); a handle proves the 4f plumbing.
local function readInv(tag)
  local invw, eqw = hafen.ui.inventory(), hafen.ui.equipment()   -- Widget objects, or nil before the HUD is up
  local inv = invw and invw:items() or {}   -- array of Item snapshots {name,res,num,wear,pos,handle}
  local eq = eqw and eqw:items() or {}      -- array of Item snapshots {..., slot, handle}
  local hand = hafen.ui.hand()              -- Item snapshot or nil (cursor item)
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

-- 1d-1: the HUD meter bars, OOP since 027-meters-oop (hafen.player():vitals() is GONE). hafen.meter is
-- CALLABLE-ONLY (D-056): hafen.meter() is the 1-based array of Meter objects in HUD order and
-- hafen.meter(needle) the FIRST whose res name contains that substring. There is no hp/stamina/energy
-- triple -- a meter is identified by its SERVER-published bg resource name, which is why this logs
-- :res() for every bar: that is how you read the real names off a live client. The meters stream in a
-- beat after enter-world (like char/items), so the "now" pass is usually empty and "+3s" has the bars.
local function readMeters(tag)
  local list = hafen.meter()
  hafen.log(("[%s] meters=%d"):format(tag, #list))
  for i = 1, #list do
    local m = list[i]
    hafen.log(("[%s]   [%d] res=%s value=%s color=%s segments=%d"):format(tag, i,
      tostring(m:res()), tostring(m:value()),
      m:color() and ("%d,%d,%d"):format(m:color().r, m:color().g, m:color().b) or "nil",
      #m:segments()))
  end
  -- 027.3: the OOP contract itself, once per login (on the +3s scan, when the bars have streamed in) --
  -- the needle lookup landing on the SAME interned object the array holds, a miss being plain nil, a
  -- NUMBER key and the EMPTY string both erroring, :index()/:exists() answering for a live bar, :info()
  -- as the snapshot escape hatch, and the hard cut (D-013): hafen.player():vitals() is gone ENTIRELY.
  if tag == "+3s" then
    local first = list[1]
    local okNum = pcall(function() return hafen.meter(1) end)
    local okEmpty = pcall(function() return hafen.meter("") end)
    -- The needle is the TAIL of the first bar's own server-published res, so the lookup must land back on
    -- it (the scan is HUD order and this IS entry 1). res() can still be nil for a beat, hence the guard;
    -- an ASCII tail is also why we slice the res instead of typing a name (one real name is non-ASCII).
    local res = first and first:res()
    local byNeedle = res and hafen.meter(res:match("[^/]+$") or res)
    local info = first and first:info()
    hafen.log(("[%s] meter oop: interned=%s miss=%s numErrors=%s emptyErrors=%s index=%s exists=%s info={res=%s value=%s segs=%d} vitalsGone=%s"):format(tag,
      res and tostring(byNeedle == first) or "n/a (no meter res yet)",
      tostring(hafen.meter("NoSuchMeterHere")),
      tostring(not okNum), tostring(not okEmpty),
      first and tostring(first:index()) or "n/a",
      first and tostring(first:exists()) or "n/a",
      info and tostring(info.res) or "n/a", info and tostring(info.value) or "n/a",
      info and #info.segments or 0,
      tostring(hafen.player().vitals == nil)))
  end
end

-- 1d-2: the active buffs, OOP since 025-buffs-oop. hafen.buff is CALLABLE-ONLY (D-056): hafen.buff()
-- is the 1-based array of Buff objects in bar order (a buff the server just removed is already out, even
-- though it is still fading on screen) and hafen.buff(needle) is the FIRST whose res or name contains it
-- -- the old buffs.has() predicate, now handing back the object. The reads live on the object
-- (:res/:name/:amount/:duration/:number/:exists/:info) and amount/duration are 0..1 fractions (NOT
-- seconds -- :duration() is the share of the buff's run still left, the radial meter), often nil -- a brand-new buff is routinely res-only for a beat. The buff bar streams in after
-- enter-world, so like the meters this is read at now + a delay; most characters carry a buff or two at login.
local function readBuffs(tag)
  local list = hafen.buff()
  local first = list[1]
  hafen.log(("[%s] buffs=%d, first=%s%s"):format(tag, #list,
    first and tostring(first:name() or first:res()) or "none",
    (first and first:duration()) and (" left=%.2f"):format(first:duration()) or ""))
  -- 025.3: the OOP contract itself, once per login (on the +3s scan, when the bar has streamed in) --
  -- the lookup landing on the SAME interned object the array holds, a miss being plain nil, a NUMBER key
  -- erroring (positions are not addresses -- hafen.buff()[n] is), :exists() true for a live buff, :info()
  -- as the snapshot escape hatch, and the hard cut (D-013): the flat hafen.buffs is gone ENTIRELY.
  if tag == "+3s" then
    local okNum = pcall(function() return hafen.buff(1) end)
    -- The needle is the tail of the first buff's own res, so the lookup must land back on it (the scan is
    -- bar order and this IS entry 1). res() can still be nil for a beat, hence the guard.
    local needle = first and first:res()
    local byNeedle = needle and hafen.buff(needle:sub(-6))
    local info = first and first:info()
    hafen.log(("[%s] buff oop: interned=%s miss=%s numErrors=%s exists=%s info={res=%s name=%s} buffsGone=%s"):format(tag,
      needle and tostring(byNeedle == first) or "n/a (no buff res yet)",
      tostring(hafen.buff("NoSuchBuffHere")),
      tostring(not okNum),
      first and tostring(first:exists()) or "n/a",
      tostring((info or {}).res), tostring((info or {}).name),
      tostring(hafen.buffs == nil)))
  end
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

-- 1d-4 + 021: action bar / hotbar slots (the engine calls it the "belt"), now OOP. hafen.actionbar(n) is
-- the Slot at the RAW 0-based game index (0..143 — the same index :use takes); hafen.actionbar() is the
-- iteration view, a 1-based array of all 144 Slots, so slot:index() is what gives the game index back.
-- Reads live per call: :empty()/:res()/:name()/:cooldown() (0..1 on ability slots only, not seconds).
-- The hotbar streams in a beat after enter-world like the rest of the HUD, so scan at now (often empty)
-- and +3s (populated). The write verbs (:use, and :set(res) since 022) are gated on "actions" — hello
-- declares none, so it only CHECKS that :set refuses; ':walker setbar <n> <res>' is the working demo.
local function readActionbar(tag)
  local bar = hafen.actionbar()
  local occupied, first = 0, nil
  for _, slot in ipairs(bar) do
    if not slot:empty() then
      occupied = occupied + 1
      if not first then first = slot end
    end
  end
  local cd = first and first:cooldown()
  hafen.log(("[%s] actionbar=%d/%d slot(s), first[%s]=%s%s"):format(tag, occupied, #bar,
    first and tostring(first:index()) or "-",
    first and tostring(first:name() or first:res()) or "none",
    cd and (" cd=%.2f"):format(cd) or ""))
  -- 021.3: the OOP contract itself, once per login (on the +3s scan, when the bar has streamed in) —
  -- interning (the array hands back the SAME objects
  -- hafen.actionbar(n) does, and position 1 is game index 0), :info() as the snapshot escape hatch, the
  -- bounds check, and the hard cut (the flat .slot/.use fields are gone, so they read as plain nil).
  if tag == "+3s" then
    local info = first and first:info()
    local ok = pcall(function() return hafen.actionbar(144) end)   -- 144 is one past the last index
    local flat = hafen.actionbar                              -- the namespace is callable-ONLY: no fields
    -- 022: the WRITE verb slot:set(res) is gated on "actions" (D-027/D-028) and hello declares NO permissions,
    -- so calling it must ERROR before anything reaches the server -- the bar is left untouched. That refusal
    -- IS the check here; the working write lives in the opt-in `walker` addon (':walker setbar <n> <res>').
    local okSet = pcall(function() return hafen.actionbar(0):set("gfx/hud/act/mine") end)
    hafen.log(("[%s] actionbar OOP: interned=%s zeroBased=%s info=%s oobThrows=%s flatGone=%s setGated=%s"):format(tag,
      tostring(bar[1] == hafen.actionbar(0)),
      tostring(bar[1]:index() == 0),
      info and tostring(info.res or info.name) or "none",
      tostring(not ok),
      tostring((flat.slot == nil) and (flat.use == nil)),
      tostring(not okSet)))
  end
end

-- 023: THE ACTION MENU (the 4x4 "scm" grid) via hafen.menugrid -- the catalogue of everything the character
-- can DO, OOP from the start and CALLABLE-ONLY: hafen.menugrid() is the whole catalogue (a 1-based array of
-- interned Pagina objects in the grid's own sort order, plus :find(text) / :roots() / :list()), while
-- hafen.menugrid(key) is ONE Pagina. The key is always a STRING and splits by SHAPE, not by fallback: it
-- contains a '/' => a RESOURCE NAME ("paginae/act/dig", the identity and the intern key), anything else =>
-- a DISPLAY NAME ("Dig"), a search convenience that needs the resource fully loaded and is NOT unique.
-- Both forms hand back the SAME interned object. A miss is plain nil (unlike hafen.kin(id)); there are NO
-- positions to address (the catalogue grows on every discovery), so hafen.menugrid(1) ERRORS.
-- A Pagina reads with :res/:name/:tooltip/:hotkey/:path/:parent/:children/:isnew/:exists/:info, and the
-- catalogue is flat but COMPLETE -- it holds the categories too, so :parent() always lands on something
-- readable and "is this a category" is #pag:children() > 0.
-- Names come from resources that resolve asynchronously, so the "now" scan is typically SHORT (or empty)
-- and fills in sub-second -- exactly what the two passes below show. The one verb, pag:use(), is a real
-- game action, so hello (the read-only harness) never calls it: ':walker menugrid <name>' is the demo.
local function readMenu(tag)
  local cat = hafen.menugrid()
  local roots = cat:roots()
  local first = cat[1]
  hafen.log(("[%s] menugrid=%d entr(ies), %d root(s), first=%s%s"):format(tag, #cat, #roots,
    first and tostring(first:name() or first:res()) or "none",
    first and (" [res=%s hotkey=%s]"):format(first:res(), tostring(first:hotkey())) or ""))
  if tag == "+3s" and first then
    -- The OOP contract itself, once per login (on the +3s scan, when the catalogue has filled in): both key
    -- forms landing on the SAME interned object, a miss being nil in both shapes, a NUMBER key erroring, the
    -- tree closing (a child's :parent() is in the catalogue and lists it back among its :children()), and
    -- :info() as the snapshot escape hatch (parent as a RESOURCE NAME there, not an object).
    local byRes = hafen.menugrid(first:res())
    local nm = first:name()
    local byName = nm and hafen.menugrid(nm)
    local okNum = pcall(function() return hafen.menugrid(1) end)      -- positions are not addresses
    -- Find any entry that HAS a parent, and check the tree closes both ways on it.
    local kid, par
    for _, p in ipairs(cat) do
      local up = p:parent()
      if up then kid, par = p, up; break end
    end
    local closes = false
    if kid then
      for _, c in ipairs(par:children()) do
        if c == kid then closes = true; break end
      end
    end
    local info = first:info()
    hafen.log(("[%s] menugrid oop: byRes=%s byName=%s noSuchRes=%s noSuchName=%s numErrors=%s"):format(tag,
      tostring(byRes == first), tostring((byName == nil) and "n/a" or (byName == first)),
      tostring(hafen.menugrid("nope/nope")), tostring(hafen.menugrid("NoSuchActionHere")),
      tostring(not okNum)))
    hafen.log(("[%s] menugrid tree: %s under %s (closes=%s, %d sibling(s)) | info.res=%s info.parent=%s exists=%s"):format(tag,
      kid and tostring(kid:name() or kid:res()) or "none",
      par and tostring(par:name() or par:res()) or "none",
      tostring(closes), par and #par:children() or 0,
      tostring((info or {}).res), tostring((info or {}).parent), tostring(first:exists())))
  end
end
local function dumpMenu()                        -- :hello actions -- the action menu as a tree, one login's catalogue
  local cat = hafen.menugrid()
  if #cat == 0 then hafen.log(":hello actions -> the action menu is empty (not in the world yet?)"); return end
  local roots = cat:roots()
  hafen.log((":hello actions -> %d entr(ies), %d root(s)  [pag:use() fires one -- ':walker menugrid <name>']"):format(#cat, #roots))
  for _, r in ipairs(roots) do
    local kids = r:children()
    hafen.log(("  %s%s  [%s]"):format(tostring(r:name() or r:res()),
      (#kids > 0) and (" (category, %d)"):format(#kids) or "", r:res()))
    for i, c in ipairs(kids) do
      if i > 6 then hafen.log(("      ... and %d more"):format(#kids - 6)); break end
      hafen.log(("      %s%s"):format(tostring(c:name() or c:res()),
        c:hotkey() and (" [alt-" .. c:hotkey() .. "]") or ""))
    end
  end
end

-- 024: AUDIO -- hafen.sound is CALLABLE-ONLY (D-056), one interned Sound object per resource NAME:
-- hafen.sound(name) is that Sound (:res/:play([volume])/:stop/:playing/:info), hafen.sound() (no argument)
-- is the array of the Sounds THIS addon still has in the air. Volume is the FIRST argument of the play call
-- and never state on the Sound (D-059) -- the object is interned and shared, so a stored level would leak
-- between unrelated uses of the same clip. There is NO :exists() (D-060): a resource name has no lifetime to
-- go stale, so a bogus name is simply silent (no Lua error). And there is NO hafen.music at all (D-058) --
-- haven.Music is MIDI, which this server never sends; the "music" you hear is ambient audio, governed by
-- hafen.client:options():audio():ambientVolume(). This is the contract check, once per login; the audible
-- live-set demo is ':hello sound' and the ping is ':hello ping'.
local function readSound(tag)
  local msg = hafen.sound("sfx/msg")
  local interned = (msg == hafen.sound("sfx/msg"))            -- D-045: the same name is the same object
  local okVol = pcall(function() return msg:play(2) end)      -- volume is 0..1; outside it errors by name
  local okNum = pcall(function() return hafen.sound(1) end)   -- the key is a resource NAME, not a number
  -- A name that does not resolve never errors INTO LUA (D-060: there is no :exists() to ask first) -- the
  -- client logs its own "addon: could not play ..." line a beat later, and that line is the expected proof.
  hafen.sound("no/such/sound/here"):play()
  local info = msg:info()
  hafen.log(("[%s] sound: res=%s info={res=%s playing=%s} interned=%s badVolErrors=%s numErrors=%s missSilent=ok live=%d")
    :format(tag, msg:res(), tostring(info.res), tostring(info.playing),
      tostring(interned), tostring(not okVol), tostring(not okNum), #hafen.sound()))
  -- The hard cut (D-013), both halves: the flat hafen.sound.play is gone (hafen.sound is callable, and the
  -- old field reads as plain nil) and hafen.music is ABSENT ENTIRELY -- not flattened, not stubbed.
  local okAmb, amb = pcall(function() return hafen.client:options():audio():ambientVolume() end)
  hafen.log(("[%s] sound contract: flatPlayGone=%s musicGone=%s ambientVolume=%s"):format(tag,
    tostring(hafen.sound.play == nil), tostring(hafen.music == nil),
    okAmb and tostring(amb) or "n/a"))
end

-- A1: map markers via hafen.markers. list()/nearest() read the client's on-disk map DB; each snapshot is
-- {id, name, type ("player"|"system"), seg, tc={x,y} (the persistent anchor), color|icon, and — when the
-- marker is in your current segment — x,y (world) + dist (from you)}. The DB streams in a beat after
-- enter-world (like the rest of the HUD), so read at now (often 0) and +3s. Most markers a character has are
-- SYSTEM markers the server pushed (quest/tracked pins); a fresh spot may have none until you add one ('marker' key).
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

-- A6: KIN / BUDDY ROSTER via hafen.kin -- now OOP (020-kin-oop), and the arity IS the verb: hafen.kin() is
-- the ROSTER (a plain array of Kin objects in Kin-window sort order -- #roster / roster[1] / ipairs -- plus
-- :find(nameOrId), :list([filter]) and the gated :add(secret)), while hafen.kin(idOrName) is ONE Kin object
-- (a number = by id and always an object, a string = an exact case-insensitive name or nil). A Kin reads with
-- :id/:name/:group/:color/:online/:exists, :gob() (the kin's game object, or nil when not in view) and
-- :info() (the old flat KinEntry snapshot, the escape hatch). gob:kin() is :gob()'s inverse.
-- The old FLAT table (list/find/add/remove/forget/rename/setGroup as fields) is GONE (hard cut, D-013):
-- indexing the namespace now reads as plain nil.
-- Kin objects are INTERNED per addon, so hafen.kin(id) == hafen.kin(id) and roster[n] is literally the same
-- object as hafen.kin(<that id>) -- we assert both below. Like the rest of the HUD the Kin list streams in a
-- beat after enter-world, so read at now (often 0) and +3s. hello is READ-ONLY here (the write verbs are the
-- gated action tier -- ':walker kin' exercises those).
local function readKin(tag)
  local roster = hafen.kin()
  local online = #roster:list(function(k) return k:online() end)   -- a FUNCTION filter receives a Kin object
  local first = roster[1]
  local found = first and roster:find(first:name())                -- round-trip :find() by name
  hafen.log(("[%s] kin=%d (%d online), first=%s%s, find(name)->%s"):format(tag, #roster, online,
    first and tostring(first:name()) or "none",
    first and (" [group=%d online=%s color=%s]"):format(first:group(), tostring(first:online()),
      first:color() and "yes" or "nil") or "",
    found and tostring(found:name()) or "nil"))
  if first then
    -- The OOP invariants, checked live: interning by id, :find() landing on that SAME object, an unknown
    -- name resolving to nil, and :info() still handing back the flat snapshot shape.
    hafen.log(("[%s] kin oop: intern=%s find==roster[1]=%s noSuchName=%s info.name=%s"):format(tag,
      tostring(hafen.kin(first:id()) == first), tostring(found == first),
      tostring(hafen.kin("NoSuchName")), tostring((first:info() or {}).name)))
  end
  -- KIN <-> GOB, both ways (020.2). The link is SERVER-side: the game marks a kinned player's gob with
  -- their buddy id, so gob:kin() is one attribute read and kin:gob() is the reverse lookup (a sweep of
  -- the loaded objects). Both are nil when there is nothing to link -- kin:gob() for a kin who is
  -- offline / out of view / not streamed in, gob:kin() for anyone not on your roster (or not a player
  -- at all) -- and that nil is ambiguous by design. A kin marks MORE than their body: their hearth fire
  -- carries the mark too (that is how it draws their name in their kin colour), so an OFFLINE kin whose
  -- hearth fire is in view still resolves -- to 'gfx/terobjs/pow', not a body. :gob() prefers the body
  -- when it is loaded; for every gob marked as theirs, filter the world by the inverse
  -- (hafen.world.gobs(function(g) return g:kin() == k end)). The round-trip below proves both
  -- directions agree on the SAME interned objects.
  local other = hafen.world.nearest(function(g) return g:isplayer() end)
  local okin = other and other:kin()
  local fgob = first and first:gob()
  hafen.log(("[%s] kin<->gob: nearest player -> kin=%s%s | roster[1]:gob()=%s%s"):format(tag,
    okin and tostring(okin:name()) or "nil",
    okin and (" (interned=%s)"):format(tostring(okin == hafen.kin(okin:id()))) or "",
    fgob and tostring(fgob:name()) or "nil",
    fgob and (" (g:kin()==roster[1]: %s)"):format(tostring(fgob:kin() == first)) or ""))
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
-- by a name/res substring (like hafen.buff(needle)). filter is the canonical nil=all / name-substring / predicate. Like
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

-- 3b/029.3: CONTAINER READS + EVENTS, with NOTHING HIDDEN. hafen.ui.adopt is GONE (029.2) and with it the whole
-- "take the window over to look inside it" trade: hafen.ui.inventory() hands back the Widget entity for the main
-- backpack, w:items() reads it while the grid is VISIBLE and INTERACTIVE, and the lifecycle events are subscribed
-- on the entity itself (w:onItemAdded/:onItemRemoved/:onDestroy, wired in the hafen.ui.on("inventory","appear")
-- subscription below to keep the discover -> read handoff). The property that made a hidden model work still holds and is now just a bonus: a
-- hidden server widget stays bound to its id, so the reads and the events keep working with the grid hidden too
-- (the 'bags' hotkey proves it). Like the rest of the inventory data, items stream in a beat after enter-world,
-- so this is read at now (often 0) and +3s.
local invWdg              -- the main-inventory Widget object subscribed in the observer (nil until it is observed)
local itemsAdded, itemsRemoved = 0, 0
-- The ~dozen items already in the backpack fire onItemAdded on the first poll after we subscribe (like BuffAdded
-- does for existing buffs). So log only the first few of that initial fill, then flip bagsReady a few seconds in
-- and log EVERY live add/remove after that -- so a pick-up/drop while the grid is hidden is clear in the log.
local bagsReady = false
local function readBags(tag)
  local w = invWdg or hafen.ui.inventory()   -- either door leads to the SAME interned entity (==)
  if not w then hafen.log(("[%s] bags: no inventory widget yet"):format(tag)); return end
  local items = w:items()
  hafen.log(("[%s] bags: %d item(s) via widget:items(), first=%s, grid-visible=%s"):format(tag, #items,
    items[1] and tostring(items[1].name or items[1].res) or "none", tostring(w:visible())))
end

-- 029.4: THE WIDGET ENTITY CONTRACT, re-checked once per login (and on demand with ':hello widget'). 029 collapsed
-- the THREE objects hafen.ui used to hand back for one widget -- the window handle from hafen.ui.window{}, the model
-- handle from adopt/replace, and the transient WidgetNode from root/node/at -- into ONE interned entity: what you
-- CREATE and what you FIND are the same type. This asserts the whole collapse in one pass: every door hands back
-- that type; `==` is the identity test (which is why :same() could be cut); arity is the verb on geometry
-- (:pos()/:size() read, :pos(x,y)/:size(w,h) write and chain, so :move() is gone); the write verbs answer only on a
-- widget THIS addon created, with two DISTINCT refusals on a native one (geometry names layout, feature E;
-- :pack()/:destroy() name the creation doors); a stale entity reads nil/empty with :exists() false while a write on
-- it is a silent chaining no-op; containers are readable with NOTHING hidden; and the four hard cuts (hafen.items,
-- hafen.ui.adopt, :same, :move) are plain nil, not shims (D-013).
local function readWidgets(tag)
  local root = hafen.ui()
  if not root then hafen.log(("[%s] widget: no UI yet"):format(tag)); return end
  local function why(f, ...)
    local ok, err = pcall(f, ...)
    if ok then return "ACCEPTED (BUG)" end
    return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))   -- drop the chunk:line prefix, keep the whole message
  end
  -- ONE TYPE FROM EVERY DOOR + INTERNING. root() / node(id) / at(x,y) / inventory() / equipment() and the creation
  -- doors all hand back the same entity, and two lookups of ONE live widget are the SAME Lua value. node(id) is the
  -- round-trip that proves it across doors: take the inventory's own :id() back through the id door. hand() is the
  -- deliberate exception -- the cursor item is not a widget, so it stays an Item snapshot.
  local inv, eq = hafen.ui.inventory(), hafen.ui.equipment()
  local byId = (inv and inv:id()) and hafen.ui.node(inv:id()) or nil
  local m = hafen.ui.mouse()
  local at = m and hafen.ui.at(m.x, m.y) or nil
  hafen.log(("[%s] widget doors: root=%s inv=%s eq=%s at(mouse)=%s hand=%s | node(id)==inv=%s root()==root=%s at()==at=%s")
    :format(tag, tostring(root), tostring(inv), tostring(eq), tostring(at),
            tostring(hafen.ui.hand() and "item" or nil),
            tostring((inv ~= nil) and (byId == inv)), tostring(hafen.ui() == root),
            tostring((at == nil) or (hafen.ui.at(m.x, m.y) == at))))
  -- OWNED vs BORROWED. A throwaway widget of our own (destroyed at the end of this check) exercises the writes; the
  -- client's root exercises the two refusals. Provenance is DERIVED from the tree, never stored on the handle, so
  -- :info().owned is how you ASK instead of provoking the error -- and it is per-addon: the same root reads
  -- owned=false for us, while our own widget would read owned=false for any OTHER addon.
  local own = hafen.ui.widget{ size = {40, 20}, pos = {8, 8} }
  own:pos(12, 14):size(48, 24):pack()                  -- arity is the verb, and every write chains on self
  local p, s = own:pos(), own:size()
  hafen.log(("[%s] owned: own.owned=%s root.owned=%s | chained pos(x,y)->%d,%d size(w,h)->%d,%d")
    :format(tag, tostring((own:info() or {}).owned), tostring((root:info() or {}).owned), p.x, p.y, s.x, s.y))
  hafen.log(("[%s] borrowed refusals: root:pos(1,1) -> %s"):format(tag, why(root.pos, root, 1, 1)))
  hafen.log(("[%s]                    root:destroy() -> %s"):format(tag, why(root.destroy, root)))
  -- STALENESS + the no-op write. Destroying our own widget makes every read answer nil/empty with :exists() false,
  -- while a WRITE on it is a silent no-op that STILL CHAINS: a write is not a question, so it does not error and no
  -- call site has to guard :exists() first.
  own:destroy()
  hafen.log(("[%s] stale: exists=%s type=%s info=%s items=%d writeStillChains=%s")
    :format(tag, tostring(own:exists()), tostring(own:type()), tostring(own:info()),
            #own:items(), tostring(own:pos(1, 1) == own)))
  -- READ WITHOUT HIDING -- the point of the whole feature. Walk the live tree for every item container and report
  -- what it holds AND whether it is visible: open a Cupboard/chest (or the study window) and re-run ':hello widget'
  -- -- it is listed here, read through the very same entity, with its window still open and usable. hafen.ui.adopt,
  -- which hid the window as the price of looking inside it, is gone.
  local conts = {}
  root:walk(function(n)
    local t = n:type()
    if (t == "Inventory") or (t == "Equipory") then
      conts[#conts + 1] = ("%s#%s:%ditem(s)%s")
        :format(t, tostring(n:id()), #n:items(), n:visible() and "" or " HIDDEN")
      return false                                     -- prune: below a grid there is nothing but its items
    end
  end)
  hafen.log(("[%s] containers readable with nothing hidden: %d [%s]")
    :format(tag, #conts, table.concat(conts, ", ")))
  -- The hard cuts (D-013): all four read as plain nil -- not flattened, not stubbed, no deprecation alias.
  hafen.log(("[%s] widget contract: itemsGone=%s adoptGone=%s sameGone=%s moveGone=%s (hafen.items=%s hafen.ui.adopt=%s)")
    :format(tag, tostring(hafen.items == nil), tostring(hafen.ui.adopt == nil),
            tostring(root.same == nil), tostring(root.move == nil),
            tostring(hafen.items), tostring(hafen.ui.adopt)))
end

-- 030.4: THE SELECTOR CONTRACT, re-checked once per login (and on demand with ':hello selector'). 030 gave 029's ONE
-- entity the vocabulary to NAME one: a selector is a STRING and hafen.ui IS the lookup (D-056) -- hafen.ui(sel) is the
-- first match in tree order, hafen.ui.all(sel) every match (an empty array, never nil), hafen.ui() the root. This
-- asserts the whole grammar in one pass against the LIVE HUD: `*`, a role, @Class, [title=], [res=] and a combination;
-- the classifier's CENSUS (:role() answers what a widget IS, or an honest nil -- never a guess in place of no answer,
-- D-067); the two rules that are easiest to get wrong ([title=] resolves against the nearest ENCLOSING WINDOW, so it
-- reaches the widgets INSIDE it; @Class is an EXACT typeName, not a superclass walk); the five promoted font-scope
-- names that are valid grammar and classify NOTHING; the parse-error catalogue; an hafen.ui.on() ROUND TRIP, whose
-- point is D-068 -- registration SCANS the live tree, so `appear` fires for what is ALREADY open, synchronously,
-- inside the on() call, handing back the very entity a lookup gives; interning, which is why "hold your result" is
-- free advice; and the two hard cuts (hafen.ui.root, hafen.ui.onWidgetCreate) as plain nil.
local SEL_ROLES = { "window", "inventory", "button", "label", "textentry", "chat", "menu" }
local SEL_SITES = { "window.title", "heading", "tooltip", "world.nick", "world.speech" }
local function readSelectors(tag)
  local root = hafen.ui()
  if not root then hafen.log(("[%s] selector: no UI yet"):format(tag)); return end
  local function why(f, ...)
    local ok, err = pcall(f, ...)
    if ok then return "ACCEPTED (BUG)" end
    return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))   -- drop the chunk:line prefix, keep the whole message
  end
  -- `*` AND THE ROLE CENSUS. ONE walk of the whole tree (never a deep helper per node -- that is O(n^2)), asking
  -- :role() per widget: the counts below ARE the classifier's answer over a real HUD, and the nil majority is the
  -- honest one (layout containers, scroll ports, images and item icons are none of these things). `*` matches every
  -- widget including the root, and tree order is pre-order -- so hafen.ui("*") IS hafen.ui().
  local every = hafen.ui.all("*")
  local census, classified = {}, 0
  for i = 1, #every do
    local r = every[i]:role()
    if r then census[r] = (census[r] or 0) + 1; classified = classified + 1 end
  end
  local parts = {}
  for _, r in ipairs(SEL_ROLES) do parts[#parts + 1] = ("%s %d"):format(r, census[r] or 0) end
  hafen.log(("[%s] selector *: %d widget(s), %d classified (%s), %d nil | ui('*')==ui()=%s interned=%s")
    :format(tag, #every, classified, table.concat(parts, " "), #every - classified,
            tostring(hafen.ui("*") == root), tostring(hafen.ui.all("*")[1] == every[1])))
  -- EACH GRAMMAR ELEMENT, and the first-vs-all contract: hafen.ui(sel) is exactly all(sel)[1] -- never a different
  -- widget -- and a miss is plain nil, never an error and never an empty stand-in. @Class goes through the same
  -- typeName :type() reports (Hafen builds most widgets as ANONYMOUS subclasses, so getSimpleName would match almost
  -- nothing) and is EXACT: the two counts below differ by exactly the Window SUBCLASSES open right now, which is the
  -- whole reason "any window" is the ROLE and not @Window.
  local wnds, byCls = hafen.ui.all("window"), hafen.ui.all("@Window")
  local inv = hafen.ui.inventory()
  local invByCls = inv and hafen.ui("@" .. inv:type()) or nil
  hafen.log(("[%s] grammar: window -> %s (#all=%d, first==all[1]=%s) | @Window -> %d (role window=%d: the rest are"
             .. " SUBCLASSES, @Class does not walk up) | @%s -> %s (==inventory()=%s) | miss 'textentry@Label' -> %s")
    :format(tag, tostring(hafen.ui("window")), #wnds, tostring(hafen.ui("window") == wnds[1]),
            #byCls, #wnds, inv and inv:type() or "?", tostring(invByCls),
            tostring((inv ~= nil) and (invByCls == inv)), tostring(hafen.ui("textentry@Label"))))
  -- [res=] -- the STABLE key (D-063), and the honest scoping 030.1 measured in-game: NO window on this server carries
  -- a resource. What does: items (gfx/invobjs/...), the HUD meters, and the chat channels whose code ships inside a
  -- .res. So [res=] is the right key for everything item-shaped and [title=] the only one for windows -- w:res() is
  -- how you find out which you are holding, never a client-side alias list.
  local withRes, meters = 0, hafen.ui.all("[res=gfx/hud/meter]")
  for i = 1, #every do if every[i]:res() then withRes = withRes + 1 end end
  hafen.log(("[%s] [res=]: %d of %d widget(s) carry one | [res=gfx/hud/meter] -> %d (first res=%s) | windows with a"
             .. " res: %d (that is why [title=] is the key for windows)")
    :format(tag, withRes, #every, #meters, meters[1] and tostring(meters[1]:res()) or "none",
            (function() local n = 0; for i = 1, #wnds do if wnds[i]:res() then n = n + 1 end end; return n end)()))
  -- [title=] RESOLVES AGAINST THE NEAREST ENCLOSING WINDOW, not the widget's own text. That is the single easiest way
  -- to ship a selector engine that looks right and never matches: a bare widget the engine wraps in a titled window
  -- (an Inventory inside a Hidewnd) has NO caption of its own, so inventory[title=Cupboard] -- the most obvious
  -- selector anyone will write -- would silently never match. Proof, taken from whatever titled window is open right
  -- now: the refiner-only selector [title=<cap>] matches the window AND everything INSIDE it, the window first (tree
  -- order), and window[title=<cap>] narrows back to the window itself.
  local titled, cap
  for i = 1, #wnds do
    local t = wnds[i]:text()
    if t and t ~= "" then titled, cap = wnds[i], t; break end
  end
  if titled then
    local scoped = hafen.ui.all(("[title=%s]"):format(cap))
    hafen.log(("[%s] [title=%s]: %d widget(s) inside that window's scope, first==the window itself=%s |"
               .. " window[title=%s]==it=%s | inventory[title=%s] -> %s (the GRID, one hop below)")
      :format(tag, cap, #scoped, tostring(scoped[1] == titled), cap,
              tostring(hafen.ui(("window[title=%s]"):format(cap)) == titled), cap,
              tostring(hafen.ui(("inventory[title=%s]"):format(cap)))))
  else
    hafen.log(("[%s] [title=]: no titled window open right now -- open a cupboard/chest and re-run ':hello selector'")
      :format(tag))
  end
  -- THE FIVE RENDER-SITE ROLES (D-067). They are promoted Fonts.SCOPES names -- ONE vocabulary shared with the font
  -- system -- but they name a render SITE, not a widget: a caption is drawn by Window.Deco, a tooltip is painted
  -- rather than placed, and the world scopes live over the 3D view. So they stay VALID GRAMMAR (no error) and match
  -- NOTHING, because guessing that a Label is a "heading" is exactly the wrong answer.
  local sites = {}
  for _, r in ipairs(SEL_SITES) do sites[#sites + 1] = ("%s=%d"):format(r, #hafen.ui.all(r)) end
  hafen.log(("[%s] render-site roles (valid grammar, classify nothing): %s"):format(tag, table.concat(sites, " ")))
  -- THE PARSE-ERROR CATALOGUE -- every shape distinguishable, each naming the offending part, and a bad role listing
  -- every valid one (the one error worth spelling out in full: nobody guesses a role).
  local function sel(s) return function() return hafen.ui(s) end end
  hafen.log(("[%s] selector errors: bad role -> %s"):format(tag, why(sel("windo"))))
  hafen.log(("[%s]                  unclosed [ -> %s"):format(tag, why(sel("window[title=X"))))
  hafen.log(("[%s]                  bad refiner key -> %s"):format(tag, why(sel("window[caption=X]"))))
  hafen.log(("[%s]                  refiner twice -> %s"):format(tag, why(sel("window[title=A][title=B]"))))
  hafen.log(("[%s]                  empty -> %s"):format(tag, why(sel("   "))))
  hafen.log(("[%s]                  a number -> %s"):format(tag, why(hafen.ui.all, 1)))
  -- hafen.ui.on() ROUND TRIP (030.2, D-068). "appear" does not mean "was created": registration SCANS the live tree,
  -- so it fires for every match ALREADY in it -- synchronously, inside this very call, which is why the counter below
  -- is already set when on() returns. That is the difference that killed onWidgetCreate: a creation feed could never
  -- fire for a widget that existed before the addon layer was rebuilt, so every :reload lost every open window. The
  -- payload is the SAME interned entity a lookup hands back, which is what makes `==` the join between the two events.
  local fired, sawInv = 0, false
  local watch = hafen.ui.on("inventory", "appear", function(w)
    fired = fired + 1
    if w == inv then sawInv = true end
  end)
  watch:remove()
  hafen.log(("[%s] on() round trip: 'inventory' appear fired %d time(s) DURING registration (the live-tree scan --"
             .. " containers open now: %d), payload==inventory()=%s; handle:remove() dropped the subscription")
    :format(tag, fired, #hafen.ui.all("inventory"), tostring(sawInv)))
  -- The hard cuts (D-013): both read as plain nil -- not flattened, not stubbed, no deprecation alias. hafen.ui() IS
  -- the root (the no-arg collection form is the tree), and a selector IS the discovery primitive.
  hafen.log(("[%s] selector contract: rootGone=%s onWidgetCreateGone=%s (hafen.ui.root=%s hafen.ui.onWidgetCreate=%s)")
    :format(tag, tostring(hafen.ui.root == nil), tostring(hafen.ui.onWidgetCreate == nil),
            tostring(hafen.ui.root), tostring(hafen.ui.onWidgetCreate)))
end

-- 031.3: THE WINDOW-TOGGLE CONTRACT, re-checked once per login (and on demand with ':hello wnd'). 029 made hiding a
-- native widget record a RESTORE; 031 makes the hide AUTHORITATIVE. MenuCheckBox calls setgkey, so the client's key
-- and its menu button fire the SAME click and both land in one private GameUI method that flipped `visible` straight
-- back on the very window an addon had hidden -- which is why a replaced inventory used to come back on Tab, sitting
-- on top of its replacement. Now hiding one of the windows the client itself opens TAKES ITS TOGGLE, and there is no
-- verb for the toggle: ownership follows the hide, and w:replace(view) -- the one place that knows BOTH halves, the
-- window it hid and the view you passed -- binds them itself. This asserts the whole contract from Lua in one
-- pass, on the main inventory's own wrapper window: the SWALLOW (hidden with nothing in its place, so the key does
-- nothing and the tick reads false), the one-owner rule (idempotent for us, refused for anybody else), and BOTH
-- HALVES of the one teardown rule -- the window ends up AS THE USER WAS SEEING IT -- driven through two live
-- grid:replace(view)/grid:replace(nil) rounds, one with the view on screen (=> the stock window opens) and one with
-- it hidden (=> it stays closed). The second round is also how the check puts the HUD back exactly as it found it:
-- the rule that is being tested is the same rule that restores. Press Tab yourself with ':hello wnd swallow', which
-- parks the client in the swallowed state (the one thing Lua cannot observe: nothing here can press a key).
local swallowedWnd        -- the window ':hello wnd swallow' is holding hidden (nil = not parked); session-local
local function readToggle(tag)
  local grid = hafen.ui.inventory()
  if not grid then hafen.log(("[%s] toggle: no inventory widget yet (the HUD is not up)"):format(tag)); return end
  local wnd = grid:parent()                    -- the Hidewnd AROUND the grid: what the client's Tab toggles
  if not wnd then hafen.log(("[%s] toggle: the inventory grid has no enclosing window"):format(tag)); return end
  if swallowedWnd then
    hafen.log((("[%s] toggle: ':hello wnd swallow' is parked on %s -- run it again to give the window back, then"
      .. " re-run this check"):format(tag, wnd:type())))
    return
  end
  local wasVis = wnd:visible()
  -- 1. THE HIDE TAKES THE TOGGLE, AND WITH NOTHING IN ITS PLACE THE TOGGLE IS SWALLOWED. The hide is refused outright
  -- if another addon (or the :lua REPL) already holds this window -- one window, one owner, because its toggle can
  -- only ever drive one thing and two owners would leave the menu tick lying about both. That refusal is the check's
  -- gate as well: with 'bags' replaced (or after a :lua hide) this reports the owner by name and stops, rather than
  -- asserting against a HUD somebody else is driving.
  local ok, err = pcall(wnd.hide, wnd)
  if not ok then
    hafen.log(("[%s] toggle: %s is already owned -- one window, one owner: %s")
      :format(tag, wnd:type(), (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))))
    return
  end
  local again = pcall(wnd.hide, wnd)           -- ...but OUR own second hide is idempotent (one record, not two)
  hafen.log(("[%s] toggle: hid %s '%s' (was visible=%s, now %s) -- nothing stands in for it, so the client's key AND"
      .. " its menu button are SWALLOWED and the tick reads false; our own second hide is idempotent (no error=%s)")
    :format(tag, wnd:type(), tostring(wnd:text()), tostring(wasVis), tostring(wnd:visible()), tostring(again)))
  -- 2+3. BOTH HALVES OF THE ONE TEARDOWN RULE. Each round replaces the main inventory with grid:replace(view) --
  -- THE VERB (032.2; hafen.ui.replace and its server descriptor are a hard cut). It hops to the ENCLOSING window,
  -- so it joins the very record the hide above made on `wnd` -- one record per window, never a second copy -- and
  -- binds the view to it; then grid:replace(nil) undoes it live, which is the same rule :reload/disable runs. The
  -- rule has NO branches and no bookkeeping boolean: the restored window's visibility IS "was the view on screen".
  local replaceErr
  local readBack, hopRead, readGone            -- 032.3: the three arities, and the hop read from both ends
  local function round(open)
    local view = hafen.ui.window{ title = "hello: toggle check", size = { 150, 28 }, pos = { 40, 40 },
                                  onDraw = function(g) g:text("toggle check", 4, 4) end }
    local ok, err = pcall(grid.replace, grid, view)   -- the verb REFUSES (throws) where the old function logged
    if not ok then replaceErr = err; view:destroy(); return nil end
    -- ARITY IS THE VERB (032.3): replace(view) installed above, replace() READS the view standing in for this
    -- window. Reading it back through `wnd` -- the ENCLOSING window we never pointed at -- is the hop itself: the
    -- record lives on the window the client toggles, so the grid and its window reach the SAME one view.
    readBack = (grid:replace() == view)
    hopRead  = (wnd:replace() == view)
    if not open then view:hide() end           -- "nothing was on screen" -- an owned widget, so no record of its own
    local shown = view:visible()
    grid:replace(nil)                          -- the live undo: the one rule runs here, before the view is destroyed
    readGone = (grid:replace() == nil)         -- ...and the read arity says so: nothing stands in for it any more
    return shown
  end
  local openA = round(true)
  if openA == nil then
    wnd:show()                                 -- release our record by hand; the window is back and so is the key
    hafen.log(("[%s] toggle: grid:replace(view) refused (%s) -- gave %s back with :show() (it is OPEN now: press"
      .. " Tab to close it)"):format(tag, (tostring(replaceErr):gsub("^.-%.lua:%d+:%s*", "")), wnd:type()))
    return
  end
  local afterA = wnd:visible()
  local openB = round(false)
  local afterB                                 -- NB a plain `and ... or nil` would collapse a false here
  if openB ~= nil then afterB = wnd:visible() end
  hafen.log(("[%s] toggle: teardown rule (the window ends up AS THE USER WAS SEEING IT) -- view open=%s => stock"
      .. " window visible=%s (expected true) | view hidden=%s => visible=%s (expected false); one expression, no"
      .. " branches, and it is what makes a bare hide stay hidden through a :reload")
    :format(tag, tostring(openA), tostring(afterA), tostring(openB), tostring(afterB)))
  -- 3b. THE VERB'S OWN CONTRACT (032.3): three arities over ONE record, reached from either end of the hop, and the
  -- namespace function that used to do all of this in one call reading plain nil -- a hard cut, no alias (D-013).
  hafen.log(("[%s] toggle: replace() arities -- installed via the GRID, read back=%s, and the same view read"
      .. " through its enclosing %s=%s (one record: the verb hops to the window, which is why the whole stock"
      .. " frame goes and not just the grid); after replace(nil) the read is nil=%s. replaceGone=%s"
      .. " (hafen.ui.replace=%s -- the old namespace function is a hard cut; ui.on waits, w:replace replaces)")
    :format(tag, tostring(readBack), wnd:type(), tostring(hopRead), tostring(readGone),
            tostring(hafen.ui.replace == nil), tostring(hafen.ui.replace)))
  -- 4. LEAVE THE HUD AS WE FOUND IT -- with the rule itself: round B ended hidden, which is the wrapper's own default
  -- state, so only a HUD that had the inventory open needs the last :show() (which owns nothing and records nothing).
  if wasVis and not wnd:visible() then wnd:show() end
  local now = wnd:visible()
  hafen.log(("[%s] toggle: no verb for the TOGGLE itself (widget.onToggle=%s hafen.ui.toggle=%s -- ownership follows"
      .. " the hide, and w:replace(view) binds the view); %s left visible=%s, as found=%s%s")
    :format(tag, tostring(grid.onToggle), tostring(hafen.ui.toggle), wnd:type(), tostring(now), tostring(wasVis),
            (now == wasVis) and "" or "  <-- MISMATCH (press Tab to put it right)"))
end

hafen.events.on("OnEnterWorld", function()
  readWidgets("login")                        -- once per login, like readAssets/readMeters/readBuffs
  readSelectors("login")                      -- 030.4: the selector contract, same cadence
  readToggle("login")                         -- 031.3: the window-toggle contract, same cadence
  hafen.timer.after(3, function()             -- ...and once the inventory/equipment widgets have streamed in
    readWidgets("+3s")
    readSelectors("+3s")                      -- the HUD is fully built by now: the census is the real one
    readToggle("+3s")                         -- ...and the inventory wrapper exists, so this one really runs
  end)
end)

hafen.events.on("OnEnterWorld", function()
  hafen.log("entered the world")

  -- 1c-1 / 017: read the player through the Gob CLASS (D-044). hafen.player():gob() is the composition
  -- anchor (Player forwards nothing — D-046); every gob method re-resolves, so a handle is always fresh
  -- and answers nil once the gob is gone. :info() is the one snapshot escape hatch. NB: :health() is nil
  -- for the player — GobHealth is object integrity, not the player's HUD meters (those land in 1d).
  local me = hafen.player():gob()
  if me then
    local p = me:pos()
    hafen.log(("player gob: %s name=%s health=%s isplayer=%s at %.1f,%.1f (info().name=%s)")
      :format(tostring(me), tostring(me:name()), tostring(me:health()), tostring(me:isplayer()),
              p and p.x or 0, p and p.y or 0, tostring((me:info() or {}).name)))
  else
    hafen.log("017: hafen.player():gob() is nil -- the player gob isn't up yet")
  end
  hafen.log(("world has %d gob(s)"):format(hafen.world.count()))
  local near = hafen.world.nearest(function(g) return not g:isplayer() end)   -- filter gets a Gob now
  if near then
    hafen.log(("nearest non-player gob: id=%s name=%s dist=%s moving=%s")
      :format(tostring(near:id()), tostring(near:name()), tostring(near:distance()), tostring(near:moving())))
  end

  -- 1c-2: player identity (data with no per-gob equivalent — the local character name). exists()/id() are
  -- GONE (D-046): hafen.player():gob() and gob:id() already answer both.
  hafen.log(("player: name=%s gob=%s"):format(tostring(hafen.player():name()), tostring(me)))

  -- 017 HARNESS: the invariants the hard cut has to keep true. Identity by per-addon weak interning
  -- (D-045), freshness per method call (D-012 kept), and the flat table actually GONE (no shim, D-013).
  if me then
    local same = (hafen.gob(me:id()) == me)                       -- interning: one object per id per addon
    local seen, uniq, sweep = {}, 0, 0
    for _ = 1, 2 do                                              -- TWO sweeps must not double-count
      local all = hafen.world.gobs()
      sweep = #all
      for i = 1, #all do
        if not seen[all[i]] then seen[all[i]] = true; uniq = uniq + 1 end
      end
    end
    local ghostId = 1                                            -- an id that (almost certainly) never existed
    local okTok = pcall(function() return hafen.gob("player") end)
    hafen.log(("017: identity hafen.gob(id)==player:gob() -> %s | seen[gob] de-dup -> %d unique over 2 sweeps of %d")
      :format(tostring(same), uniq, sweep))
    hafen.log(("017: hard cut -> hafen.gob.health=%s hafen.player.exists=%s hafen.gob('player') errors=%s | unloaded gob(%d): exists=%s pos=%s id=%d")
      :format(tostring(hafen.gob.health), tostring(hafen.player.exists), tostring(not okTok),
              ghostId, tostring(hafen.gob(ghostId):exists()), tostring(hafen.gob(ghostId):pos()),
              hafen.gob(ghostId):id()))
    -- Freshness: the SAME stashed handle, re-read 12s later. Walk in between -- the coords must change,
    -- proving the object holds only the id and re-resolves (a snapshot would be frozen).
    local p0 = me:pos()
    hafen.timer.after(12, function()
      local p1 = me:pos()
      if p0 and p1 then
        hafen.log(("017: stashed Gob freshness -> %.1f,%.1f => %.1f,%.1f (moved %.1f -- walk to see it change)")
          :format(p0.x, p0.y, p1.x, p1.y, math.sqrt((p1.x - p0.x) ^ 2 + (p1.y - p0.y) ^ 2)))
      else
        hafen.log("017: stashed Gob freshness -> pos() is nil now (gob gone: methods go quiet, :id() still answers "
          .. tostring(me:id()) .. ")")
      end
    end)
  end

  -- 1c-2: time + astronomy (astronomy readers are nil until the first astro update).
  hafen.log(("time: clock=%.1f day=%s night=%s season=%s moon=%s")
    :format(hafen.time.clock() or 0, tostring(hafen.time.dayFraction()),
            tostring(hafen.time.isNight()), tostring(hafen.time.season()),
            tostring(hafen.time.moon())))

  -- 1c-2/1c-3: map, projection, item and char/party reads — now (often still loading/streaming) and
  -- again after 3s (resolved). char attrs, lp/weight and the inventory all stream in shortly AFTER
  -- enter-world (same as the map data), so the "now" pass typically shows nil/0 and "+3s" the real data.
  readPlace("now"); readInv("now"); readChar("now"); readMeters("now")
  readBuffs("now"); readFood("now"); readStudy("now"); readLore("now"); readActionbar("now"); readMenu("now"); readBags("now"); readMarkers("now"); readRadar("now"); readKin("now"); readSpeed("now"); readCraft("now"); readQuests("now"); readWounds("now"); readFight("now")
  hafen.timer.after(3, function()
    readPlace("+3s"); readInv("+3s"); readChar("+3s"); readMeters("+3s")
    readBuffs("+3s"); readFood("+3s"); readStudy("+3s"); readLore("+3s"); readActionbar("+3s"); readMenu("+3s"); readBags("+3s"); readMarkers("+3s"); readRadar("+3s"); readKin("+3s"); readSpeed("+3s"); readCraft("+3s"); readQuests("+3s"); readWounds("+3s"); readFight("+3s")
    bagsReady = true   -- 3b: initial item fill done -> now log EVERY live inventory add/remove
    if invWdg then hafen.log("3b: bags ready -- move an item in/out now (even with the grid hidden via the 'bags' key) and it logs") end
  end)

  -- 1c-2 / 024: an audible confirmation ping (a client-bundled sound), proving hafen.sound(name):play()
  -- works -- plus the whole audio contract, re-checked once per login (024.4). See readSound() above.
  hafen.sound("sfx/msg"):play()
  readSound("login")
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
  local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
  local p = me and me:pos()
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
  s.name = hafen.player():name() or s.name          -- remember the character name across sessions
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

-- Count gob spawns; log only the first few so it does not flood. Since 017 the payload is a Gob OBJECT
-- (D-044), so we read the type name live off it. On GobRemoved the gob is ALREADY gone, so only :id()
-- answers there — index the name on GobAdded if you need it later.
local spawned, despawned = 0, 0
hafen.events.on("GobAdded", function(g)
  spawned = spawned + 1
  if spawned <= 3 then
    hafen.log(("GobAdded %s name=%s (%d so far)"):format(tostring(g), tostring(g:name()), spawned))
  end
end)
hafen.events.on("GobRemoved", function(g)
  despawned = despawned + 1
  if despawned <= 3 then                          -- name() is expected to be nil here — the gob is gone
    hafen.log(("GobRemoved id=%d (name=%s -- nil is CORRECT, it already despawned) (%d so far)")
      :format(g:id(), tostring(g:name()), despawned))
  end
end)

-- 1d-1: the three meter events (027.2), all carrying the Meter OBJECT itself. The bars stream in a beat
-- after enter-world, so MeterAdded fires once per bar at login (and two MORE when you mount a horse,
-- which the old positional vitals triple could never see); MeterRemoved fires when one goes away --
-- the object still READS there, with :exists() false. MeterChanged fires only on a REAL change (the
-- whole segment array is diffed, so a pure recolour counts too), which is often for stamina/energy, so
-- log only the first few of it. NOTE the 027.2 gotcha, deliberately visible here: a MeterAdded payload
-- can be younger than its resource -- :res() is nil AT FIRE TIME (tostring is "Meter(?)") and the SAME
-- object answers a beat later, so never name-match inside the MeterAdded handler.
local metersSeen, meterChanges = 0, 0
hafen.events.on("MeterAdded", function(m)
  metersSeen = metersSeen + 1
  if metersSeen <= 8 then
    hafen.log(("MeterAdded: %s (res=%s -- nil here is NORMAL, it is still loading) value=%s (%d)")
      :format(tostring(m), tostring(m:res()), tostring(m:value()), metersSeen))
  end
end)
hafen.events.on("MeterRemoved", function(m)
  hafen.log(("MeterRemoved: %s (value=%s still readable, exists=%s)")
    :format(tostring(m), tostring(m:value()), tostring(m:exists())))
end)
hafen.events.on("MeterChanged", function(m)
  meterChanges = meterChanges + 1
  if meterChanges <= 5 then
    local c = m:color()
    hafen.log(("MeterChanged: %s value=%s color=%s (%d)"):format(tostring(m), tostring(m:value()),
      c and ("%d,%d,%d"):format(c.r, c.g, c.b) or "nil", meterChanges))
  end
end)

-- 1d-2: buff add/remove/change. Buffs the character already has re-appear as BuffAdded shortly after
-- enter-world (the bar streams in). Content updates (e.g. the duration meter ticking down a step) fire
-- BuffChanged. Log the first few of each so it does not flood. Since 025.2 the payload is the Buff
-- OBJECT, so these read it with colon calls; a removed buff still answers, with :exists() false.
local buffsSeen = 0
hafen.events.on("BuffAdded", function(b)
  buffsSeen = buffsSeen + 1
  if buffsSeen <= 5 then
    hafen.log(("BuffAdded: %s (%s)"):format(tostring(b:name() or b:res()), tostring(b:res())))
  end
end)
hafen.events.on("BuffRemoved", function(b)
  hafen.log(("BuffRemoved: %s (exists=%s)"):format(tostring(b:name() or b:res()), tostring(b:exists())))
end)
hafen.events.on("BuffChanged", function(b)
  hafen.log(("BuffChanged: %s amount=%s duration=%s"):format(
    tostring(b:name() or b:res()), tostring(b:amount()), tostring(b:duration())))
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

-- 1d-4: ActionbarChanged{slot} fires when an action-bar slot changes — slots stream in at login (a burst,
-- one per occupied slot) and then on any set/clear/drag. Action-bar changes are user-driven (not
-- per-frame), so — unlike the meters/gobs — we log EVERY one (with a running ordinal) to make it easy to
-- verify live: put an item/action on a slot or clear one and you should see a line each time. The payload
-- is the Slot OBJECT itself (021.2) — same interned object as hafen.actionbar(n), reading live.
local actionbarSeen = 0
hafen.events.on("ActionbarChanged", function(slot)
  actionbarSeen = actionbarSeen + 1
  local same = (slot == hafen.actionbar(slot:index()))   -- interning: the payload IS hafen.actionbar(n)
  hafen.log(("ActionbarChanged: slot %d -> %s (interned=%s) (%d)"):format(slot:index(),
    (not slot:empty()) and tostring(slot:name() or slot:res()) or "empty",
    tostring(same), actionbarSeen))
end)

-- 1d-4: EquipChanged fires when worn equipment changes (equip/unequip) — the payload is the same array
-- as hafen.ui.equipment():items(). Equipment streams in at login (a few fires), then on any change. Log the
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
-- kin-alert addon most wants) an online/offline flip. Since 020.3 the payload is the whole roster as
-- Kin OBJECTS (not the old flat snapshot list), in Kin-window sort order and interned like everywhere
-- else -- so payload[n] is literally the same object as hafen.kin(<that id>), and a Kin works as a TABLE
-- KEY. We use that here: kinOnline is keyed BY THE OBJECT, which survives a rename (the old block keyed
-- by name and would have reported a renamed kin as one going offline and another coming online).
-- The event says the roster CHANGED, never what changed, so keeping our own last-state map is the way to
-- name who flipped. A few fire at login as the roster streams in; log the first few of those.
local kinSeen = 0
local kinOnline = {}          -- Kin object -> true while we believe them online (so we can report transitions)
hafen.events.on("KinChanged", function(roster)
  kinSeen = kinSeen + 1
  local now = {}
  for _, k in ipairs(roster) do
    local on = k:online() or false
    now[k] = on                                      -- the Kin object itself as the key (interning makes it stable)
    if on and not kinOnline[k] then hafen.log(("KinChanged: %s came ONLINE"):format(tostring(k:name()))) end
    if (not on) and kinOnline[k] then hafen.log(("KinChanged: %s went offline"):format(tostring(k:name()))) end
  end
  kinOnline = now
  if kinSeen <= 5 then
    local first = roster[1]
    -- ...and the payload objects ARE the interned ones: first == hafen.kin(first:id()) must be true.
    hafen.log(("KinChanged: %d kin (%d)%s"):format(#roster, kinSeen,
      first and (" first=%s interned=%s"):format(tostring(first:name()),
        tostring(first == hafen.kin(first:id()))) or ""))
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

-- 030.2: SELECTOR EVENTS (hafen.ui.on). WATCH the client's own UI for a part of it, named with the SAME selector a
-- lookup uses -- hafen.ui.onWidgetCreate and its {id, type, place, caption, parentType} descriptor are HARD CUT,
-- and with them the second vocabulary you had to learn to say "wait for the cupboard". fn(w) receives the Widget
-- ENTITY (029): the very value hafen.ui(sel) hands back, interned -- so `==` identifies it and a plain Lua table
-- keyed by it carries state across the two events (used below to remember a window's title).
--   "appear"    -- a matching widget was PLACED into the tree, or was ALREADY in it when you subscribed:
--                  registration scans the live tree once, which is exactly what the old observer could NOT do
--                  (a :reload lost every window that was already open -- see the inventory handoff below).
--   "disappear" -- a widget that had matched is GONE, where GONE means the SERVER destroyed it (its id stops
--                  resolving): the moment it stops being REAL, not the moment it stops being DRAWN. A Window only
--                  starts a fade-out when it is destroyed, so it lingers in the tree -- unbound and still
--                  readable -- for the length of that animation, which is why the line below can print
--                  exists=true. Take the entity as a KEY to match against what you kept at appear; do not count
--                  on reading it (a client-only widget really is gone by then).
-- Neither is about visibility: a window the client merely HIDES (the inventory's Tab toggle) never left the tree,
-- so it fires neither -- that is a property of the tree, and the honest answer. One event per call.
-- Bridge-owned: :reload/disable drops the subscription and fires NOTHING (a reload is not a destroy); the handle
-- also exposes :remove(). VERIFY in-game: open a cupboard/chest -> one "window APPEARED" line naming it, plus one
-- from the [title=] subscription; close it -> exactly one "DISAPPEARED" of each, naming the same window.
local windowsSeen, windowsGone = 0, 0
local wndTitle = {}                                   -- Widget entity -> its title at appear (a stable table key)
hafen.ui.on("window", "appear", function(w)
  windowsSeen = windowsSeen + 1
  local title = w:text()                              -- may be nil here: a .res window's caption can land a tick late
  wndTitle[w] = title
  if windowsSeen <= 20 or title then                  -- cap the login burst; always log a titled window
    hafen.log(("030.2: window APPEARED %s role=%s title=%s res=%s (%d seen)")
      :format(tostring(w), tostring(w:role()), tostring(title), tostring(w:res()), windowsSeen))
  end
end)
hafen.ui.on("window", "disappear", function(w)
  windowsGone = windowsGone + 1
  hafen.log(("030.2: window DISAPPEARED %s title=%s -- server-destroyed (id gone); still fading: intree=%s text=%s;"
             .. " %d seen / %d gone")
    :format(tostring(w), tostring(wndTitle[w]), tostring(w:exists()), tostring(w:text()), windowsSeen, windowsGone))
  wndTitle[w] = nil
end)

-- The same, with a [title=] REFINER -- the case the bounded re-check exists for. A caption arrives by uimsg, so a
-- window can be placed a tick or two BEFORE it is titled; a candidate that matches the selector's structure but
-- not yet its refiner is re-offered for a bounded number of ticks, so this fires exactly ONCE, not zero times and
-- not twice. Remember: [title=] resolves against the nearest enclosing Window (030.1), which is why "window" is
-- the role here and "inventory[title=Cupboard]" would hand you the GRID inside that same window.
for _, cap in ipairs({ "Cupboard", "Chest" }) do
  hafen.ui.on(("window[title=%s]"):format(cap), "appear", function(w)
    hafen.log(("030.2: [title=%s] APPEARED %s -- %d item(s) inside, grid=%s")
      :format(cap, tostring(w), #w:items(), tostring(hafen.ui(("inventory[title=%s]"):format(cap)))))
  end)
  hafen.ui.on(("window[title=%s]"):format(cap), "disappear", function(w)
    hafen.log(("030.2: [title=%s] DISAPPEARED %s"):format(cap, tostring(w)))
  end)
end

-- 3b/029.3: the discover -> read handoff. Every open container carries the `inventory` role, so pick the player's
-- OWN backpack by identity (hafen.ui.inventory() is the same interned entity, 029.3) and SUBSCRIBE to its item
-- lifecycle. Nothing is hidden and nothing is taken over: the grid stays visible and usable while we read it.
-- Subscribing IS the registration (an unwatched widget is never polled), and passing nil to any of the three verbs
-- unsubscribes. NB this is where 030.2 beats the old observer outright: a :reload does NOT recreate the existing
-- inventory, so onWidgetCreate never re-fired for it -- the registration SCAN finds it anyway, so the handoff now
-- survives a reload. onDestroy fires if the widget ever leaves the tree.
hafen.ui.on("inventory", "appear", function(w)
  if invWdg or w ~= hafen.ui.inventory() then return end   -- containers also have this role; we want the player's own
  invWdg = w
  hafen.log(("3b: watching the main inventory (id=%s, %s) -- items read with NOTHING hidden; the 'bags' hotkey"
             .. " hides/shows the grid and the reads keep working"):format(tostring(w:id()), tostring(invWdg)))
  invWdg:onItemAdded(function(item)
    itemsAdded = itemsAdded + 1
    if bagsReady or itemsAdded <= 3 then              -- initial fill: first few only; after +3s: every live add
      hafen.log(("3b: item ADDED to inventory: %s x%s (total seen %d)%s")
        :format(tostring(item.name or item.res), tostring(item.num or 1), itemsAdded,
                (invWdg and not invWdg:visible()) and " [grid hidden -- still readable]" or ""))
    end
  end)
  invWdg:onItemRemoved(function(item)
    itemsRemoved = itemsRemoved + 1
    if bagsReady or itemsRemoved <= 3 then
      hafen.log(("3b: item REMOVED from inventory: %s (total seen %d)%s")
        :format(tostring(item.name or item.res), itemsRemoved,
                (invWdg and not invWdg:visible()) and " [grid hidden -- still readable]" or ""))
    end
  end)
  invWdg:onDestroy(function()
    hafen.log("3b: the inventory widget left the tree (server destroy)")
    invWdg = nil
  end)
end)
hafen.log("030.2: selector subscriptions installed -- open a cupboard/chest to see appear/disappear log")

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
local meterFreeze = false  -- 2e: while true, the "set" message hook SWALLOWS meter updates -> the HUD meter bars freeze (toggle: MIDDLE-click)
local msgHookSeen = 0       -- 2e: how many meter "set" messages the hook has observed while OFF (for the "observed" log lines)
local dropWidget            -- U1: the borderless drop-target widget handle (nil until created / after reload)
local droppedRes           -- U1: the .res name of the last menu-grid action dropped on it (drawn via g:resource)
local fontWin               -- F2: a small window rendered in the addon's OWN font (font=demoFont) + a $font mix line

-- R1: CUSTOM IMAGE (hafen.asset). Load a PNG shipped in THIS addon's own folder (icon.png -- a small
-- green "H" disc) into a bridge-owned TexI handle, then draw it below in the 2a window (native + scaled) and
-- the 2b HUD overlay (anchored). This is a CLIENT-ONLY render asset, NOT an engine .res -- SAFE-tier, NOT
-- gated (D-034), like an overlay. Paths are addon-relative and sandboxed (absolute / ".." are rejected, D-017).
-- The handle exposes :size() -> {w,h} and :dispose(); it is disposed AUTOMATICALLY on :reload/disable (P2), so
-- there is no GL leak (the Phase-R1 DoD). Loaded at OnLoad -> re-loaded on every reload (the env is rebuilt).
local icon   -- the image handle (nil until loaded; a fresh reload rebuilds the env -> nil, re-loaded below)
hafen.events.on("OnLoad", function()
  icon = hafen.asset("icon.png")   -- 028.1: ONE loader for every file this addon ships (was hafen.render.image)
  local s = icon:size()
  hafen.log(("R1: loaded icon.png (%dx%d) -- drawn in the 2a window (native + scaled) and the 2b HUD overlay")
    :format(s.w, s.h))
end)

-- R3a/R3b: CUSTOM 3D MODEL (hafen.asset). Load a glTF .glb shipped in THIS addon's folder (tank.glb -- a
-- real TEXTURED, MULTI-MATERIAL model) into a bridge-owned mesh handle. Like the R1 image it is a CLIENT-ONLY
-- render asset, NOT an engine .res -- SAFE-tier, NOT gated (D-034); paths are addon-relative + sandboxed (absolute
-- / ".." rejected, D-017). The parser bakes the glTF (+Y up) into H&H model space (Z up; 1 glTF metre = 1 tile);
-- R3b decodes TEXCOORD_0 + the baseColorTexture (embedded PNGs) into shared TexIs and builds one material per
-- primitive (texture x baseColorFactor, alpha mode, cull). R3c adds LIGHTING: the parser bakes per-vertex NORMALs
-- (or computes them when absent) and each material adds a Phong light state, so the model now SHADES with the world
-- lights instead of drawing fullbright -- :info().lit counts the lit primitives. The handle exposes :bounds() ->
-- {min,max,size} world units, :info() -> {prims,textured,lit,textures,verts,tris}, and :dispose() (also automatic
-- on reload/disable, P2). Stand it with hafen.render.object{model=cube, x=, y=}; ':hello object' places one.
local cube   -- the model handle (nil until loaded; a fresh reload rebuilds the env -> nil, re-loaded below)
hafen.events.on("OnLoad", function()
  cube = hafen.asset("tank.glb")   -- 028.1: same door as the image; the .glb extension picks the mesh loader
  local b, nfo = cube:bounds(), cube:info()
  hafen.log(("R3c: loaded tank.glb -- %d prims (%d textured, %d LIT, %d textures), %d tris; baked size %.0f x %.0f x %.0f world units (~%.1f tiles tall); :hello object to place it (now shaded by the world lights)")
    :format(nfo.prims, nfo.textured, nfo.lit, nfo.textures, nfo.tris, b.size.x, b.size.y, b.size.z, b.size.z / 11))
end)

-- F1: PER-ADDON FONTS (hafen.font / hafen.asset). A font handle is PRIVATE to this addon (no shared registry,
-- D-043) and comes from one of two places (028.1): hafen.font(name) for a BUILT-IN ("sans"/"serif"/"mono"/
-- "fraktur" -- engine-owned, so addressed by name, interned, no lifetime) and hafen.asset(path) for a .ttf/.otf
-- THIS addon ships (sandboxed like every asset: absolute / ".." are rejected, D-017). Neither takes options --
-- the size/style variant is :derive{size=..}, which is also what hafen.font.load(source, opts) became. The handle
-- exposes :derive(opts) (a cheap variant), :family() (the AWT family, for a $font tag in F2) and :size().
-- We prefer a bundled .ttf if one is present (drop any .ttf at addons/hello/fonts/demo.ttf to exercise the
-- file-load + AWT-register path -- the DoD's "loads a TTF"); otherwise we fall back to the
-- built-in "serif", which still proves the whole loop. Applying it to a GLOBAL surface is an OWNED override:
-- hafen.font.setFont("default", h) restyles most UI text LIVE (the "default" scope cascades to Text.std / Text.render
-- / every default Label), and it is reverted automatically on :reload/disable (the stock UI is always restorable).
-- ':hello font' toggles the override; while ON it also stacks a SECOND override (mono) on top to prove LAST-WINS,
-- then drops it back to the first. SAFE-tier (cosmetic, client-only). See docs/addons/api/fonts.md.
local demoFont          -- the loaded FontHandle (nil until OnLoad; env rebuilt on reload -> nil, re-loaded below)
local monoFont          -- F2: a second handle (built-in "mono") for the per-call { font = } demo in the F2 window
local fontApplied = false   -- is our "default" override currently installed? (session-local; teardown reverts it)
local titleApplied = false  -- F3a: is our "window.title" override installed? (session-local; teardown reverts it)
local btnApplied = false    -- F3b: is our "button" override installed? (session-local; teardown reverts it)
local entryApplied = false  -- F3c: is our "textentry" override installed? (session-local; teardown reverts it)
local labelApplied = false  -- F3c: is our "label" override installed? (session-local; teardown reverts it)
local headApplied = false   -- F3e: is our "heading" override installed? (session-local; teardown reverts it)
local menuApplied = false   -- F3d: is our "menu" override installed? (session-local; teardown reverts it)
local tipApplied = false    -- F3d: is our "tooltip" override installed? (session-local; teardown reverts it)
local chatApplied = false   -- F3d: is our "chat" override installed? (session-local; teardown reverts it)
local speechApplied = false -- F4: is our "world.speech" override installed? (session-local; teardown reverts it)
local nickApplied = false   -- F4: is our "world.nick" override installed? (session-local; teardown reverts it)
local nodeFontApplied = false -- F5: is our PER-INSTANCE override installed on one window? (teardown reverts it)
local nodeFontTarget        -- F5: the WidgetNode we styled (a transient handle; nil once reset / after a reload)
hafen.events.on("OnLoad", function()
  fontApplied = false                                   -- a reload rebuilt the env; the override was torn down (P2)
  titleApplied = false                                  -- F3a: likewise for the window.title override (P2)
  btnApplied = false                                    -- F3b: ...and for the button override (P2)
  entryApplied = false                                  -- F3c: ...and for the textentry override (P2)
  labelApplied = false                                  -- F3c: ...and for the label override (P2)
  headApplied = false                                   -- F3e: ...and for the heading override (P2)
  menuApplied = false                                   -- F3d: ...and for the menu override (P2)
  tipApplied = false                                    -- F3d: ...and for the tooltip override (P2)
  chatApplied = false                                   -- F3d: ...and for the chat override (P2)
  speechApplied = false                                 -- F4: ...and for the world.speech override (P2)
  nickApplied = false                                   -- F4: ...and for the world.nick override (P2)
  nodeFontApplied, nodeFontTarget = false, nil          -- F5: ...and for the per-instance (node:setFont) override (P2)
  monoFont = hafen.font("mono"):derive{ size = 12 }     -- F2: a distinct font for the per-call g:text{font=} line
  local ok, ttf = pcall(hafen.asset, "fonts/demo.ttf")   -- try a bundled .ttf first (the file-load path)...
  if ok and ttf then
    demoFont = ttf
    hafen.log(("F1: loaded bundled font fonts/demo.ttf -- family '%s', size %s -- :hello font to flip the default font")
      :format(demoFont:family(), tostring(demoFont:size() or "stock")))
  else
    demoFont = hafen.font("serif"):derive{ size = 11 }  -- ...else a built-in (no TTF shipped by default). size in logical px.
    hafen.log(("F1: loaded built-in font 'serif' (size 11) -- family '%s'; drop a .ttf at addons/hello/fonts/demo.ttf to load a real TTF -- :hello font to flip the default font")
      :format(demoFont:family()))
  end
  hafen.log("F1: hafen.font.scopes() = " .. table.concat(hafen.font.scopes(), ", "))
end)

-- 028.3: THE ASSET CONTRACT (hafen.asset), checked once per login. ONE door for every file this addon ships:
-- hafen.asset(path) is one interned, typed handle (the TYPE comes from the EXTENSION: .png/.jpg/.jpeg/.gif/.bmp
-- -> image, .ttf/.otf -> font, .glb/.gltf -> mesh) and hafen.asset() -- arity is the verb, D-056 -- is the array
-- of the assets this addon currently HOLDS, in load order. The loader takes a PATH AND NOTHING ELSE: a font's
-- size/style is :derive{..}, never a load option, so the signature is the same for all three types and interning
-- never depends on an options table. Interning is keyed by the RESOLVED path ('./icon.png' and 'icon.png' are one
-- asset and one TexI) and identity is stable only WHILE ALIVE -- :dispose() drops the entry, so the next load of
-- that path is a NEW object (':hello assets dispose' proves that half). Every asset answers :type()/:path()/
-- :dispose(); a BUILT-IN font (hafen.font("serif")) and a :derive'd variant carry NONE of them and are never
-- listed -- no file, no path, no lifetime (D-060). The three old loaders (hafen.font.load / hafen.render.image /
-- hafen.render.model) are a HARD CUT and read as plain nil, and the use sites are HANDLE-ONLY (D-012): a path
-- string into render.sprite{image=} / object{model=} is an error that points back at hafen.asset.
local function readAssets(tag)
  local live = hafen.asset()
  local parts = {}
  for i = 1, #live do parts[#parts + 1] = ("%s:%s"):format(live[i]:type(), live[i]:path()) end
  hafen.log(("[%s] assets: hafen.asset() = %d live [%s]"):format(tag, #live, table.concat(parts, ", ")))
  -- Interning + the resolved-path key. icon.png and tank.glb are the two this addon always ships; the .ttf is
  -- optional (drop one at addons/hello/fonts/demo.ttf) so its type is reported rather than asserted.
  local interned = (icon == hafen.asset("icon.png")) and (cube == hafen.asset("tank.glb"))
  local resolved = (hafen.asset("./icon.png") == icon) and (hafen.asset("img/../icon.png") == icon)
  local fontAsset = (demoFont and demoFont.type) and demoFont:type() or "none (built-in serif -- not an asset)"
  hafen.log(("[%s] asset types: icon=%s tank=%s font=%s | interned=%s resolvedKey=%s")
    :format(tag, icon and icon:type() or "?", cube and cube:type() or "?", fontAsset,
      tostring(interned), tostring(resolved)))
  -- The ERROR catalogue -- every shape distinguishable, and each one naming hafen.asset (028.1 acceptance).
  local function why(f, ...)
    local ok, err = pcall(f, ...)
    if ok then return "ACCEPTED (BUG)" end
    return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))   -- drop the chunk:line prefix, keep the whole message
  end
  hafen.log(("[%s] asset errors: absolute -> %s"):format(tag, why(hafen.asset, "/etc/passwd")))
  hafen.log(("[%s]              '..' -> %s"):format(tag, why(hafen.asset, "../planner/main.lua")))
  hafen.log(("[%s]              unknown ext -> %s"):format(tag, why(hafen.asset, "manifest.json")))
  hafen.log(("[%s]              missing -> %s"):format(tag, why(hafen.asset, "nope.png")))
  hafen.log(("[%s]              number key -> %s"):format(tag, why(hafen.asset, 1)))
  -- HANDLE-ONLY (D-012): the two world builders refuse a PATH STRING with an error naming hafen.asset. The option
  -- check runs AFTER the world check, so outside the world they simply answer nil -- gate on it, or the harness
  -- would report a refusal that never happened.
  local okp, mygob = pcall(function() return hafen.player():gob() end)
  if okp and mygob then
    hafen.log(("[%s] handle-only: sprite{image='icon.png'} -> %s"):format(tag,
      why(function() return hafen.render.sprite{ image = "icon.png", x = 0, y = 0 } end)))
    hafen.log(("[%s]              object{model='tank.glb'} -> %s"):format(tag,
      why(function() return hafen.render.object{ model = "tank.glb", x = 0, y = 0 } end)))
  else
    hafen.log(("[%s] handle-only: skipped -- not in the world yet (sprite/object answer nil before the option check)")
      :format(tag))
  end
  -- D-060: a BUILT-IN font is engine-owned -- addressed by name, interned, and carrying none of the asset verbs
  -- (that is also why hafen.asset() above lists 2, not 3, when no .ttf is shipped: the built-in is not a file).
  local serif = hafen.font("serif")
  hafen.log(("[%s] builtin font: interned=%s noAssetVerbs=%s badName -> %s"):format(tag,
    tostring(serif == hafen.font("serif")),
    tostring((serif.type == nil) and (serif.path == nil) and (serif.dispose == nil)),
    (function() local ok, e = pcall(hafen.font, "comic"); return (not ok) and "refused" or "ACCEPTED (BUG)" end)()))
  -- The hard cut (D-013): all three old loaders read as plain nil -- not flattened, not stubbed.
  hafen.log(("[%s] asset contract: loadersGone=%s (font.load=%s render.image=%s render.model=%s)"):format(tag,
    tostring((hafen.font.load == nil) and (hafen.render.image == nil) and (hafen.render.model == nil)),
    tostring(hafen.font.load), tostring(hafen.render.image), tostring(hafen.render.model)))
end

hafen.events.on("OnEnterWorld", function()
  readAssets("login")   -- once per login: this whole section re-checked, like readSound/readMeters/readBuffs
end)

local function drawPanel(g, w, h)
  g:color(0, 0, 0, 150); g:frect(0, 0, w, h); g:color()          -- translucent backdrop
  g:text(("clock %.0f"):format(hafen.time.clock() or 0), 6, 6)
  g:text(("clicks %d"):format(clicks), 6, 22)
  -- 1d-1: EVERY HUD meter, drawn in its OWN colour (027-meters-oop) -- not a hard-coded hp/stam/en
  -- triple read by position. The label is the tail of the server-published res name, and the bar takes
  -- meter:color(), which is state the old flat vitals snapshot never exposed.
  local meters = hafen.meter()
  if #meters > 0 then
    for i = 1, #meters do
      local m, y = meters[i], 42 + (i - 1) * 15
      local res, c = m:res(), m:color()
      g:text((res and res:match("[^/]+$") or "?"):sub(1, 6), 6, y)
      g:color(60, 60, 60); g:frect(44, y + 2, 110, 9); g:color()
      if c then g:color(c.r, c.g, c.b) end
      g:frect(44, y + 2, math.floor(110 * (m:value() or 0)), 9); g:color()
    end
  else
    g:text("meters loading...", 6, 42)
  end
  -- The three hook-state lines are anchored to the BOTTOM of the window: the meter block above them grows
  -- and shrinks (mounting adds two bars), so a fixed y would collide with it.
  -- 2c: map-lock state (LEFT-click the window to toggle; red = map clicks are being cancelled by the L1 hook)
  g:color(mapLock and 235 or 150, mapLock and 90 or 150, 90)
  g:text(("map-lock %s (LMB)"):format(mapLock and "ON" or "OFF"), 6, h - 46)
  g:color()
  -- 2d: move-intercept state (RIGHT-click the window to toggle; orange = moves are intercepted + re-sent by L2)
  g:color(moveIntercept and 245 or 150, moveIntercept and 160 or 150, moveIntercept and 60 or 150)
  g:text(("move-hook %s (RMB)"):format(moveIntercept and "ON" or "OFF"), 6, h - 32)
  g:color()
  -- 2e: meter-freeze state (MIDDLE-click the window to toggle; cyan = the L3 message hook is swallowing the
  -- meter updates, so the bars above — and the real HUD meters — freeze until toggled off)
  g:color(meterFreeze and 90 or 150, meterFreeze and 210 or 150, meterFreeze and 235 or 150)
  g:text(("meter-freeze %s (MMB)"):format(meterFreeze and "ON" or "OFF"), 6, h - 18)
  g:color()
  -- R1: draw the custom image (a hafen.asset image handle) two ways in the top-right, above the bars: native 32x32
  -- and the same handle scaled to 16x16 (g:image with/without a w,h). A nil/disposed handle draws nothing.
  if icon then
    g:image(icon, w - 34, 2)                                      -- native size (32x32) in the top-right corner
    g:image(icon, w - 52, 2, 16, 16)                             -- the SAME image scaled to 16x16, just left of it
  end
  g:color(170, 170, 170); g:rect(0, 0, w, h); g:color()          -- 1px border
end

-- 2e-2: GLOBAL HOTKEYS (hafen.client:options():keybindings()). register(name, fn) declares a remappable,
-- persisted hotkey over the client's KeyBinding registry (namespaced addon/hello/<name>) — "toggle" flips this
-- window's visibility, the WoW "show/hide my panel" pattern. An addon hotkey starts UNBOUND (D-047): the addon
-- names the ACTION, you assign the KEY in Options > Keybindings > Hello (suggested here: Ctrl+H). Unlike the
-- input/action/message hooks below, a hotkey needs NO live target, so it is registered here in the FILE BODY
-- (it simply does nothing until you are in-world and the window exists). It fires ONLY when no focused widget
-- consumed the keypress first (a focused text field consumes all ORDINARY typing, so a hotkey on a plainly-typed
-- key is naturally suppressed while typing) and no client binding owns the same key (addon hotkeys are the
-- fallback, walked after the client's — never a hijack). Bridge-owned: :reload or disabling the addon removes
-- the handler (the KeyBinding entry itself is kept, so your assignment survives). get(name) reads the current
-- key's display name ("Ctrl+H") or nil while unassigned; set(name, key) accepts "F5", "Ctrl+M",
-- "Shift+Alt+Left", a bare letter/digit, or "None"; unregister(name) drops one of this addon's hotkeys.
local keys = hafen.client:options():keybindings()
keys:register("toggle", function()
  if not panel then hafen.log("2e-2: 'toggle' pressed, but the window is not up yet"); return end
  local show = not panel:visible()                                -- flip the current (settled/animating) state
  if show then panel:show() else panel:hide() end
  hafen.log(("2e-2: 'toggle' -> window %s"):format(show and "shown" or "hidden"))
end)
hafen.log(("2e-2: global hotkey 'toggle' registered (key = %s) -- assign/remap it in Options > Keybindings > Hello")
  :format(keys:get("toggle") or "unassigned, suggested Ctrl+H"))

-- 2e-3: a SECOND hotkey, "ping". Because this addon registered hotkeys, a "Hello" section appears in
-- Options > Keybindings (WoW-style) listing every one of them. They all start as None, so nothing fires until
-- you ASSIGN keys there — demonstrating the panel's assign-from-scratch flow and the per-addon grouping. Once
-- bound this one plays a sound on press, and the choice persists across restarts like every built-in keybinding.
keys:register("ping", function()
  hafen.sound("sfx/msg"):play()
  hafen.log("2e-3: ping hotkey fired (assigned in Options > Keybindings > Hello)")
end)

-- 3b/029.2: a THIRD hotkey ("bags", suggested Ctrl+B) toggling the native inventory grid's visibility --
-- widget:hide()/:show(), the ONE write that answers on a widget you did NOT create. Open your inventory (Tab),
-- press it: the item grid HIDES (it stays bound to its id, so :items() and the add/remove events keep working --
-- drop something in and 3b still logs it); press again: it SHOWS. Hiding a native widget records the RESTORE -- what
-- hafen.ui.adopt used to do implicitly, now explicit. 031: what you own is what you POINT AT, and the restore is one
-- rule -- "the window ends up as the user was seeing it". This hides the GRID, not its window, so Tab keeps toggling
-- the inventory exactly as stock (it just opens empty); and because nothing stands in for the grid, a :reload leaves
-- it HIDDEN rather than replaying the visibility it had -- press the key again (or hide the WINDOW instead,
-- hafen.ui.inventory():parent(), which is what takes Tab over: ':hello wnd swallow'). A relog skips the restore
-- entirely, that session's widgets being gone. This adds a third row to the "Hello" keybind section (2e-3). If you
-- assign a key a client binding already owns, the client wins (addon hotkeys are the fallback) -- pick another one.
keys:register("bags", function()
  local w = hafen.ui.inventory()        -- the same interned entity the observer above subscribed to (==)
  if not w then
    hafen.log("3b: 'bags' -- no inventory widget yet (the HUD isn't up)")
    return
  end
  if w:visible() then w:hide() else w:show() end
  hafen.log(("3b: 'bags' -> inventory grid %s (%d item(s) still readable, nothing adopted)")
    :format(w:visible() and "shown" or "hidden", #w:items()))
end)

-- A1: a FOURTH hotkey ("marker", suggested Ctrl+Shift+M) — a TOGGLE that drops a persistent "Hello marker" at your
-- current position (hafen.markers.add at your gob's world coord), or removes it if already placed
-- (hafen.markers.remove). Watch it appear on the map (M) and the corner minimap. add() writes the shared
-- on-disk map DB, so it PERSISTS — but hello removes its own marker on disable/reload (see OnDisable) so the
-- regression harness never pollutes your map. Adds a fourth row to the "Hello" keybind section (2e-3 grouping).
local helloMarker   -- the ref of the demo marker while placed (nil = not placed); session-local
keys:register("marker", function()
  if helloMarker then
    hafen.markers.remove(helloMarker)
    helloMarker = nil
    hafen.log("A1: 'marker' -> removed the Hello marker")
    return
  end
  local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
  local p = me and me:pos()
  if not p then hafen.log("A1: 'marker' -> no player position yet"); return end
  helloMarker = hafen.markers.add("Hello marker", p.x, p.y, { color = { r = 80, g = 220, b = 90 }, onmap = true })
  if helloMarker then
    hafen.log(("A1: 'marker' -> dropped 'Hello marker' at %.0f,%.0f (ref %s) -- press again to remove")
      :format(p.x, p.y, tostring(helloMarker)))
  else
    hafen.log("A1: 'marker' -> could not add marker (map/session location not up yet)")
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
-- sound, ":hello sound" toggles a long clip through the 024.2 live set, and ":hello echo <text...>" shows the args rejoined (quoting survives — :hello echo "a b" c -> a b c).
local demoGhost   -- V1: the handle of the manual :hello ghost demo while placed (nil = none); session-local
local demoSprite  -- R2a: the handle of the manual :hello sprite demo while placed (nil = none); session-local
local demoFollow  -- R2a anchor: the handle of the :hello follow demo (a sprite anchored to you); session-local
local demoBill    -- R2b: the handle of the :hello billboard demo (a camera-facing sprite); session-local
local demoObject  -- R3a: the handle of the :hello object demo (a glTF cube in the world); session-local
-- 026.2: the text-cache bound check. The toggle is assigned beside the HUD overlay far below (that is where the
-- drawing happens); its two constants live here so `:hello textcache` can quote them in the same breath.
local textcacheStress
local STRESS_POOL, STRESS_PER_FRAME = 2000, 32
hafen.slash.register("hello", function(args)
  if #args == 0 then
    hafen.log("A11: :hello -- hi from the hello addon! try  :hello toggle | ping | sound | echo <text...> | craft | quest | wound | fight | actions | ghost | sprite | billboard | follow | object | assets | font | title | button | entry | label | heading | menu | tip | chat | speech | nick | widget | selector | wnd [swallow] | prof | widgets | passes | textcache")
    return
  end
  local sub = args[1]
  if sub == "toggle" then
    if not panel then hafen.log(":hello toggle -> the window is not up yet (enter the world first)"); return end
    local show = not panel:visible()
    if show then panel:show() else panel:hide() end
    hafen.log((":hello toggle -> window %s"):format(show and "shown" or "hidden"))
  elseif sub == "ping" then
    hafen.sound("sfx/msg"):play()
    hafen.log(":hello ping -> played sfx/msg")
  elseif sub == "sound" then
    -- 024.2: the live set. A TOGGLE over the addon's OWN clips: hafen.sound() (no argument) is the array of
    -- the Sounds THIS addon still has in the air -- pruned as you ask, so it drops back to 0 by itself when a
    -- clip ends. Anything left playing is silenced for us on disable/:reload (try it: start it, then :reload).
    local live = hafen.sound()
    if #live > 0 then
      for i = 1, #live do live[i]:stop() end                 -- :stop() cuts it mid-clip, and chains on self
      hafen.log((":hello sound -> stopped %d live sound(s); now #hafen.sound()=%d"):format(#live, #hafen.sound()))
    else
      local bell = hafen.sound("sfx/hud/mmap/bell3")         -- a long-ish client-bundled clip
      bell:play(0.6)                                          -- volume is the FIRST argument of the play call
      hafen.log((":hello sound -> playing %s at 0.6 (playing=%s, #hafen.sound()=%d) -- :hello sound again to stop")
        :format(bell:res(), tostring(bell:playing()), #hafen.sound()))
    end
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
  elseif sub == "actions" then
    dumpMenu()                                   -- 023: dump the ACTION MENU as a tree (roots + their children)
  elseif sub == "ghost" then
    if demoGhost then                            -- V1: TOGGLE a client-only ghost cabin at your position
      demoGhost:destroy(); demoGhost = nil
      hafen.log((":hello ghost -> destroyed (list=%d)"):format(#hafen.ghost.list()))
    else
      local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
      local p = me and me:pos()
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
  elseif sub == "sprite" then
    -- R2a: CLIENT-ONLY WORLD SPRITE (hafen.render.sprite). Stand our own PNG (the R1 icon.png handle) UPRIGHT in
    -- the 3D world as a fixed textured quad -- the non-.res sibling of a ghost, on the SAME virtual-entity core:
    -- a Gob with no server id, so it never reaches the server (SAFE-tier, NOT gated, D-034). It is a transform
    -- handle like a ghost (:move/:rotate/:scale/:pos/:destroy), so 2s after placing we move + rotate + scale it to
    -- prove the gizmo-compatible transform works live; :hello sprite again removes it. Torn down on reload/disable.
    if demoSprite then
      demoSprite:destroy(); demoSprite = nil
      hafen.log(":hello sprite -> destroyed")
    else
      if not icon then hafen.log(":hello sprite -> icon.png not loaded yet (OnLoad)"); return end
      local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
      local p = me and me:pos()
      if not p then hafen.log(":hello sprite -> no player position yet"); return end
      demoSprite = hafen.render.sprite{ image = icon, x = p.x, y = p.y, scale = 3 }  -- ~3 tiles tall so it's clearly visible
      if not demoSprite then hafen.log(":hello sprite -> hafen.render.sprite returned nil (not in the world yet?)"); return end
      local pos = demoSprite:pos()
      hafen.log((":hello sprite -> icon.png STANDING at (%.0f,%.0f) scale=%.0f -- transforms in 2s; :hello sprite again to remove")
        :format(pos.x, pos.y, pos.scale))
      local this = demoSprite                              -- capture, so a quick toggle-off/on doesn't transform the new one
      hafen.timer.after(2.0, function()
        if demoSprite == this then
          this:move(p.x + 22, p.y):rotate(math.pi / 2):scale(4)   -- +2 tiles E, face 90°, grow x4 (chained handle verbs)
          hafen.log(":hello sprite -> moved +2 tiles E, rotated 90 deg, scaled x4 (gizmo-compatible transform handle)")
        end
      end)
    end
  elseif sub == "billboard" then
    -- R2b: CAMERA-FACING BILLBOARD SPRITE (hafen.render.sprite{billboard=true}). The SAME PNG, but drawn as a
    -- screen-space blit at the projected world point, so it ALWAYS faces the camera and is a constant screen size
    -- (rotate the camera / zoom -- it stays square-on and the same pixel size). Position + gizmo-move still apply
    -- (world-rotate/scale do not: it's 2D). Same handle as a fixed sprite; :hello billboard again removes it.
    if demoBill then
      demoBill:destroy(); demoBill = nil
      hafen.log(":hello billboard -> destroyed")
    else
      if not icon then hafen.log(":hello billboard -> icon.png not loaded yet (OnLoad)"); return end
      local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
      local p = me and me:pos()
      if not p then hafen.log(":hello billboard -> no player position yet"); return end
      demoBill = hafen.render.sprite{ image = icon, x = p.x, y = p.y, billboard = true, scale = 2 }  -- 2x native px, faces camera
      if not demoBill then hafen.log(":hello billboard -> hafen.render.sprite returned nil (not in the world yet?)"); return end
      hafen.log((":hello billboard -> icon.png standing at (%.0f,%.0f) FACING THE CAMERA (screen-sized) -- rotate the camera to see; :hello billboard again to remove")
        :format(p.x, p.y))
    end
  elseif sub == "follow" then
    -- R2a ANCHOR: a world sprite anchored to a gob that FOLLOWS it automatically, like a gob overlay (no polling).
    -- hafen.render.sprite{ follow = <Gob object>, offset = {x=,y=,z=} } -> the sprite tracks the gob every frame;
    -- z = up, so offset {z=18} floats it ~1.6 tiles above your head. Since 017 `follow` takes the Gob OBJECT, not
    -- an id or a token (D-044). It keeps its own facing/scale; :offset moves it relative to the gob (keeps
    -- following) and a plain :move detaches. Here it anchors to YOU -- walk around and it follows.
    if demoFollow then
      demoFollow:destroy(); demoFollow = nil
      hafen.log(":hello follow -> destroyed")
    else
      if not icon then hafen.log(":hello follow -> icon.png not loaded yet (OnLoad)"); return end
      local me = hafen.player():gob()
      if not me then hafen.log(":hello follow -> no player gob yet"); return end
      demoFollow = hafen.render.sprite{ image = icon, scale = 2, follow = me, offset = { z = 18 } }
      if not demoFollow then hafen.log(":hello follow -> hafen.render.sprite returned nil (not in the world yet?)"); return end
      hafen.log(":hello follow -> icon.png now FLOATS above your head and FOLLOWS you -- walk around; :hello follow again to remove")
    end
  elseif sub == "object" then
    -- R3a/R3b: CLIENT-ONLY WORLD 3D MODEL (hafen.render.object). Stand our own glTF model (the tank.glb handle) in
    -- the 3D world -- the mesh sibling of a sprite/ghost, on the SAME virtual-entity core: a Gob with no server id, so
    -- it never reaches the server (SAFE-tier, NOT gated, D-034). R3b makes it render TEXTURED (its embedded PNGs) with
    -- one material per primitive. It is a transform handle like a sprite (:move/:rotate/:scale/:pos/:destroy), so 2s
    -- after placing we move + rotate + grow it to prove the gizmo-compatible transform works live on a MESH; :hello
    -- object again removes it. Torn down on reload/disable. The scale is derived from :bounds() so ANY model (this
    -- tank is authored in big units) stands ~2 tiles tall.
    if demoObject then
      demoObject:destroy(); demoObject = nil
      hafen.log(":hello object -> destroyed")
    else
      if not cube then hafen.log(":hello object -> tank.glb not loaded yet (OnLoad)"); return end
      local me = hafen.player():gob()                 -- your character's Gob OBJECT (nil pre-world)
      local p = me and me:pos()
      if not p then hafen.log(":hello object -> no player position yet"); return end
      local b = cube:bounds()
      local tall = (b.size.z and b.size.z > 0.01) and b.size.z or 11       -- world-unit height
      local scale = (2 * 11) / tall                       -- stand ~2 tiles tall regardless of the model's authored units
      demoObject = hafen.render.object{ model = cube, x = p.x, y = p.y, scale = scale }
      if not demoObject then hafen.log(":hello object -> hafen.render.object returned nil (not in the world yet?)"); return end
      local pos = demoObject:pos()
      hafen.log((":hello object -> tank.glb (textured + LIT/R3c) STANDING at (%.0f,%.0f) scale=%.3f -- shaded by the world lights (rotate/relocate it to see the shading change); transforms in 2s; :hello object again to remove")
        :format(pos.x, pos.y, pos.scale))
      local this = demoObject                             -- capture, so a quick toggle-off/on doesn't transform the new one
      hafen.timer.after(2.0, function()
        if demoObject == this then
          this:move(p.x + 22, p.y):rotate(math.pi / 4):scale(scale * 1.5)   -- +2 tiles E, face 45 deg, grow x1.5 (chained handle verbs)
          hafen.log(":hello object -> moved +2 tiles E, rotated 45 deg, grew x1.5 (gizmo-compatible transform handle on a mesh)")
        end
      end)
    end
  elseif sub == "assets" then
    -- 028.3: re-run the whole asset contract on demand (it also runs once per login -- see readAssets above),
    -- and, with the 'dispose' argument, the one part that is DESTRUCTIVE and so cannot live in the login pass.
    readAssets("cmd")
    if (args[2] == "dispose") and not cube then
      hafen.log("   dispose: tank.glb not loaded yet (OnLoad) -- skipped")
    elseif args[2] == "dispose" then
      -- ':hello assets dispose' -- what disposing a MESH under a LIVE object really does (measured 028.2): the
      -- object keeps drawing, textured and unchanged, because it captured the texture sampler when it was built.
      -- Disposing buys nothing while an object draws it -- the memory is not reclaimed until that object is
      -- destroyed. The re-load then proves identity is stable only WHILE ALIVE: the same path, a NEW asset.
      local old = cube
      old:dispose()
      cube = hafen.asset("tank.glb")                    -- the same path, a NEW asset (the disposed one is never served)
      hafen.log(("   dispose: tank.glb disposed -- a STANDING object keeps its textures (it captured the sampler"
        .. " at build time); re-load == the old handle -> %s (false = a NEW asset, as documented)")
        :format(tostring(cube == old)))
      hafen.log(("   hafen.asset() still lists %d live asset(s) -- the disposed entry was DROPPED and the re-load"
        .. " added a NEW one in its place; the corpse is never served and never listed"):format(#hafen.asset()))
    end
  elseif sub == "font" then
    -- F1: toggle a GLOBAL font override on the "default" scope + prove LAST-WINS. setFont installs THIS addon's
    -- override (owner-tagged); it restyles most UI text live and is reverted automatically on :reload/disable.
    if not demoFont then hafen.log(":hello font -> font not loaded yet (OnLoad)"); return end
    if fontApplied then
      hafen.font.reset("default")                       -- drop our override -> the stock font returns live
      fontApplied = false
      hafen.log(":hello font -> reset('default') -- stock font restored (also happens on :reload/disable)")
    else
      hafen.font.setFont("default", demoFont)           -- override #1 (serif/ttf)
      -- LAST-WINS: stack a SECOND override (mono) on top of the same scope, then drop back to #1, to prove the
      -- owner-tagged stack resolves most-recent-first (a real addon would only set one; this is the harness proof).
      local mono = hafen.font("mono")
      hafen.font.setFont("default", mono)               -- override #2 now wins (mono)
      hafen.timer.after(3.0, function()
        hafen.font.setFont("default", demoFont)          -- re-apply #1 -> it wins again (last-wins), back to serif/ttf
        hafen.log(":hello font -> last-wins: was mono for 3s, now back to the first font")
      end)
      fontApplied = true
      hafen.log((":hello font -> setFont('default', %s) -- most UI text should change; showing 'mono' for 3s first (last-wins), then '%s'; :hello font again to reset")
        :format(demoFont:family(), demoFont:family()))
    end
  elseif sub == "title" then
    -- F3: toggle a font override on the "window.title" scope (WINDOW CAPTIONS only) -- INDEPENDENT of "default".
    -- With ONLY "window.title" set, captions change but body text stays stock; with ONLY "default" set (:hello
    -- font), the cascade restyles captions too until a "window.title" override refines them. Owner-tagged,
    -- reverted automatically on :reload/disable. SAFE-tier (cosmetic, client-only). See docs/addons/api/fonts.md.
    if not demoFont then hafen.log(":hello title -> font not loaded yet (OnLoad)"); return end
    if titleApplied then
      hafen.font.reset("window.title")                  -- drop our override -> stock window captions return live
      titleApplied = false
      hafen.log(":hello title -> reset('window.title') -- stock window captions restored (also on :reload/disable)")
    else
      hafen.font.setFont("window.title", demoFont:derive{ size = 15 })  -- caption size ~= the stock fraktur 15
      titleApplied = true
      hafen.log((":hello title -> setFont('window.title', %s) -- open/focus any window: its CAPTION font changes (body text stays stock unless :hello font); :hello title again to reset")
        :format(demoFont:family()))
    end
  elseif sub == "button" then
    -- F3b: toggle a font override on the "button" scope (BUTTON CAPTIONS only) -- independent of "default" and of
    -- "window.title". Buttons rasterize their caption into an image, so the client re-renders each visible button
    -- when the override moves: open the Options window (or any window with buttons) and watch the captions change
    -- LIVE. With ONLY "button" set, window titles and body text stay stock; with ONLY "default" set (:hello font),
    -- the cascade restyles button captions too, until a "button" override refines them. Owner-tagged, reverted
    -- automatically on :reload/disable. SAFE-tier (cosmetic, client-only). See docs/addons/api/fonts.md.
    -- NOTE (harness gotcha): the STOCK button caption font is *bold serif 12*, so overriding "button" with a
    -- serif handle is a no-op TO THE EYE even though the override is installed. We deliberately pick a visibly
    -- different family (mono) so the DoD is observable; the size stays 12 to keep the captions inside the buttons.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello button -> font not loaded yet (OnLoad)"); return end
    if btnApplied then
      hafen.font.reset("button")                        -- drop our override -> stock button captions return live
      btnApplied = false
      hafen.log(":hello button -> reset('button') -- stock button captions restored (also on :reload/disable)")
    else
      hafen.font.setFont("button", h:derive{ size = 12, bold = true })  -- mono bold 12 vs the stock serif bold 12
      btnApplied = true
      hafen.log((":hello button -> setFont('button', %s) -- open the Options window: its BUTTON captions change (titles/body stay stock unless :hello title / :hello font); :hello button again to reset")
        :format(h:family()))
    end
  elseif sub == "entry" then
    -- F3c: toggle a font override on the "textentry" scope (TEXT-INPUT FIELDS only) -- independent of "default",
    -- "window.title" and "button". It covers both ReadLine-backed entry surfaces: every TextEntry field (the chat
    -- input, search boxes, the login name field, ...) AND the console command line (the ':' prompt you are typing
    -- this command into). Each field drops its cached line when the override moves, so the change is LIVE: type
    -- into any field and the glyphs are already the new font. With ONLY "textentry" set, titles/buttons/body stay
    -- stock; with ONLY "default" set (:hello font), the cascade restyles fields too, until a "textentry" override
    -- refines them. Owner-tagged, reverted automatically on :reload/disable. SAFE-tier (cosmetic, client-only).
    -- NOTE: an entry field's HEIGHT is fixed by its background texture, so keep the size near the stock 12 or tall
    -- glyphs will be clipped -- that is a client-geometry fact, not an API limit. See docs/addons/api/fonts.md.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello entry -> font not loaded yet (OnLoad)"); return end
    if entryApplied then
      hafen.font.reset("textentry")                     -- drop our override -> stock entry font returns live
      entryApplied = false
      hafen.log(":hello entry -> reset('textentry') -- stock text-field font restored (also on :reload/disable)")
    else
      hafen.font.setFont("textentry", h:derive{ size = 12 })   -- mono 12 vs the stock serif 12 (visibly different)
      entryApplied = true
      hafen.log((":hello entry -> setFont('textentry', %s) -- click any text field (or this console line) and type: the glyphs change; :hello entry again to reset")
        :format(h:family()))
    end
  elseif sub == "label" then
    -- F3c: toggle a font override on the "label" scope = the client's BODY TEXT. F1 already routed the DEFAULT
    -- labels through the "default" scope; this covers the other half -- the surfaces the client renders with its
    -- own hand-picked foundry, which until now were frozen: the CHARACTER SHEET's attribute rows (base + study),
    -- every skill / lore / quest / wound / maneuver LIST ITEM, the menu-search results, the radar icon-settings
    -- list, and the few Labels built with an explicit foundry (credo Level/Quest lines, wound quality, the
    -- combat-schools counter, the login screen). Each site re-resolves its foundry and re-renders on the next
    -- frame it draws (lazily, per visible row), keeping its own colour. The override inherits each site's STOCK
    -- SIZE unless we pass one, so families swap without moving layouts. With ONLY "label" set, titles/buttons/
    -- fields stay stock; with ONLY "default" set (:hello font), the cascade restyles body text too, until a
    -- "label" override refines it. Owner-tagged, reverted automatically on :reload/disable. SAFE-tier.
    -- NOTE: we deliberately pass NO size here -- rows keep the HEIGHT they were built with (from the stock
    -- font), so a bigger size would clip; the family swap is the safe, observable demo. See api/fonts.md.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello label -> font not loaded yet (OnLoad)"); return end
    if labelApplied then
      hafen.font.reset("label")                         -- drop our override -> stock label font returns live
      labelApplied = false
      hafen.log(":hello label -> reset('label') -- stock explicit-foundry labels restored (also on :reload/disable)")
    else
      hafen.font.setFont("label", h)                    -- no size -> every routed label keeps its own stock size
      labelApplied = true
      hafen.log((":hello label -> setFont('label', %s) -- open the character sheet (attribute rows) or Skills & Lore / Quests / Wounds (list items): the BODY TEXT changes family but keeps its sizes; :hello label again to reset")
        :format(h:family()))
    end
  elseif sub == "heading" then
    -- F3e: toggle a font override on the "heading" scope = the client's in-window SECTION HEADINGS, the big
    -- embossed fraktur captions inside a window: "Base Attributes", "Food Satiations", "Abilities", "Study
    -- Report", "Lore & Skills", "Entries", "Quest Log", "Health & Wounds", "Martial Arts & Combat Schools",
    -- "Kin", the credo group captions ("Pursuing"/"Credos Available"/"Credos Acquired") and a village name. They
    -- are NOT window titles (that is "window.title") and NOT body text (that is "label") -- their own scope, so
    -- you can restyle them independently. A heading is an embossed FURNACE baked into an image, so the client
    -- rebuilds the furnace and re-renders each visible heading when the override moves -- keep the window open
    -- while toggling and watch it change. Owner-tagged, reverted automatically on :reload/disable. SAFE-tier.
    -- NOTE: no size passed -- each heading keeps its own stock size (the big ones are 25px, the credo group
    -- captions 18px), so the layout around them does not move. See docs/addons/api/fonts.md.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello heading -> font not loaded yet (OnLoad)"); return end
    if headApplied then
      hafen.font.reset("heading")                       -- drop our override -> stock headings return live
      headApplied = false
      hafen.log(":hello heading -> reset('heading') -- stock section headings restored (also on :reload/disable)")
    else
      hafen.font.setFont("heading", h)                  -- no size -> every heading keeps its own
      headApplied = true
      hafen.log((":hello heading -> setFont('heading', %s) -- open the character sheet: 'Base Attributes' / 'Food Satiations' / 'Abilities' change (titles + body text stay stock); :hello heading again to reset")
        :format(h:family()))
    end
  elseif sub == "menu" then
    -- F3d: toggle a font override on the "menu" scope = the client's ACTION MENUS. Two surfaces: the petal
    -- captions of a flower menu (right-click a tree/the ground and the ring of options that opens) and the
    -- keybind letter the action-menu grid paints over its buttons (hold the show-keys modifier over the menu
    -- grid). A petal re-renders AND re-sizes around its own centre when the override moves, so a menu that is
    -- already open changes live; the size is capped by nothing here, so a big size is fine on a petal (unlike a
    -- text field). Independent of every other scope; with ONLY "default" set (:hello font) the cascade restyles
    -- menus too, until a "menu" override refines them. Owner-tagged, reverted on :reload/disable. SAFE-tier.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello menu -> font not loaded yet (OnLoad)"); return end
    if menuApplied then
      hafen.font.reset("menu")                          -- drop our override -> stock petal captions return live
      menuApplied = false
      hafen.log(":hello menu -> reset('menu') -- stock flower-menu font restored (also on :reload/disable)")
    else
      hafen.font.setFont("menu", h:derive{ size = 14 })  -- mono 14 vs the stock sans 12 (visibly different + bigger)
      menuApplied = true
      hafen.log((":hello menu -> setFont('menu', %s) -- right-click something: the PETAL captions are in the new font (and a petal already open re-sizes around its centre); :hello menu again to reset")
        :format(h:family()))
    end
  elseif sub == "tip" then
    -- F3d: toggle a font override on the "tooltip" scope = every TOOLTIP the client pops up. The bulk of it is the
    -- client's tooltip ENGINE (ItemInfo), which composes the tip of an INVENTORY ITEM, a buff, a HUD meter, a
    -- craft recipe input/output, a minimap marker/object, a character-sheet attribute row and an action-menu icon
    -- -- so hover an item in your inventory and you see it immediately. On top of that: plain string tips
    -- (rendered at display time, so the tip already under the cursor changes), a widget's rich settip() tip with
    -- its "Keyboard shortcut: ..." tail, resource pagina descriptions, and the food/curiosity/terrain/keybind
    -- tips. Markup inside a tooltip ($b, $col, $img) keeps working over the override, because the provider swaps
    -- the FAMILY+SIZE and not the whole font attribute. Independent of every other scope. Owner-tagged, reverted
    -- automatically on :reload/disable. SAFE-tier (cosmetic, client-only).
    -- NOTE: a tooltip sizes its own box around its text, so a bigger size is safe here -- unlike a text field.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello tip -> font not loaded yet (OnLoad)"); return end
    if tipApplied then
      hafen.font.reset("tooltip")                       -- drop our override -> stock tooltips return live
      tipApplied = false
      hafen.log(":hello tip -> reset('tooltip') -- stock tooltip font restored (also on :reload/disable)")
    else
      hafen.font.setFont("tooltip", h:derive{ size = 13 })   -- mono 13 vs the stock sans 10 (bigger + different)
      tipApplied = true
      hafen.log((":hello tip -> setFont('tooltip', %s) -- hover an INVENTORY ITEM (or a buff / a HUD meter / a craft input / an action-menu icon / a HUD button): the tooltip is in the new font, and it changes while you keep hovering; :hello tip again to reset")
        :format(h:family()))
    end
  elseif sub == "chat" then
    -- F3d: toggle a font override on the "chat" scope = the whole CHAT window: every message line (area/party/
    -- private/system), the channel tabs down its left side, and the quick-line you type into. Only the messages
    -- currently VISIBLE re-render (the scrollback re-renders as you scroll it into view), and a message's height
    -- is re-measured, so the log re-flows correctly with a bigger font. URLs stay clickable -- the override keeps
    -- the chat's own link parser. Independent of every other scope; with ONLY "default" set (:hello font) the
    -- cascade restyles chat too, until a "chat" override refines it. Owner-tagged, reverted on :reload/disable.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello chat -> font not loaded yet (OnLoad)"); return end
    if chatApplied then
      hafen.font.reset("chat")                          -- drop our override -> stock chat font returns live
      chatApplied = false
      hafen.log(":hello chat -> reset('chat') -- stock chat font restored (also on :reload/disable)")
    else
      hafen.font.setFont("chat", h:derive{ size = 13 })  -- mono 13 vs the stock sans 12
      chatApplied = true
      hafen.log((":hello chat -> setFont('chat', %s) -- open the chat window (Ctrl+C): the message lines, the channel tabs and the line you type are in the new font; :hello chat again to reset")
        :format(h:family()))
    end
  elseif sub == "speech" then
    -- F4: toggle a font override on the "world.speech" scope = the SPEECH BUBBLES that pop up over a character's
    -- head when someone talks in area chat (your own included -- just say something and watch your bubble). The
    -- bubble measures its frame around the text every frame, so a bigger font simply gives a bigger bubble: this
    -- is the one scope where a large size is completely safe. A bubble already on screen re-renders live (lazily,
    -- only the visible ones). Independent of every other scope; with ONLY "default" set (:hello font) the cascade
    -- restyles bubbles too, until a "world.speech" override refines them. Owner-tagged, reverted automatically on
    -- :reload/disable. SAFE-tier (cosmetic, client-only). See docs/addons/api/fonts.md.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello speech -> font not loaded yet (OnLoad)"); return end
    if speechApplied then
      hafen.font.reset("world.speech")                  -- drop our override -> stock bubble font returns live
      speechApplied = false
      hafen.log(":hello speech -> reset('world.speech') -- stock speech-bubble font restored (also on :reload/disable)")
    else
      hafen.font.setFont("world.speech", h:derive{ size = 16 })  -- mono 16 vs the stock sans 10 (clearly bigger)
      speechApplied = true
      hafen.log((":hello speech -> setFont('world.speech', %s) -- say something in area chat (Enter): the BUBBLE over your head is in the new font and its frame grows with it; :hello speech again to reset")
        :format(h:family()))
    end
  elseif sub == "nick" then
    -- F4: toggle a font override on the "world.nick" scope = the floating KIN NAMES drawn over the characters of
    -- people on your kin (buddy) list, in their kin-group colour. Note you need a KIN VISIBLE ON SCREEN to see
    -- this one -- no kin nearby, nothing to restyle. The label is composed by code that ships inside the game's
    -- own resources, so the client re-composes it (and re-centres it over the character) when the override moves;
    -- the name keeps its group colour. Independent of every other scope; with ONLY "default" set (:hello font) the
    -- cascade restyles the names too, until a "world.nick" override refines them. Owner-tagged, reverted
    -- automatically on :reload/disable. SAFE-tier (cosmetic, client-only). See docs/addons/api/fonts.md.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello nick -> font not loaded yet (OnLoad)"); return end
    if nickApplied then
      hafen.font.reset("world.nick")                    -- drop our override -> stock kin names return live
      nickApplied = false
      hafen.log(":hello nick -> reset('world.nick') -- stock floating kin-name font restored (also on :reload/disable)")
    else
      hafen.font.setFont("world.nick", h:derive{ size = 16, bold = true })  -- mono bold 16 vs the stock sans bold 12
      nickApplied = true
      hafen.log((":hello nick -> setFont('world.nick', %s) -- look at a KIN standing nearby: the name floating over them is in the new font (its colour stays the kin-group colour); :hello nick again to reset")
        :format(h:family()))
    end
  elseif sub == "node" then
    -- F5: a PER-INSTANCE font override -- the last piece of the font system and the only one that is not a named
    -- scope. node:setFont(h) restyles ONE native widget and everything drawn inside it (its title, its labels, its
    -- button captions, even text drawn by the game's own resource code), while its SIBLINGS keep the scope/"default"
    -- font: it sits at the TOP of the resolution chain (instance > scope > "default" > stock). The node comes from
    -- the W1 widget-tree walk (hafen.ui():walk), so any widget in the client can be targeted -- here we pick
    -- the FIRST open window and leave the rest stock, which is exactly the thing to look at. A window is
    -- recognised as "has a caption AND has children" (a Label/Button/TextEntry has a caption but no children).
    -- Owner-tagged like every other font override: reverted automatically on :reload/disable, and it dies with the
    -- widget (close the window and the override goes with it). SAFE-tier (cosmetic, client-only). See api/fonts.md.
    local h = (monoFont or demoFont)
    if not h then hafen.log(":hello node -> font not loaded yet (OnLoad)"); return end
    if nodeFontApplied then
      if nodeFontTarget then nodeFontTarget:resetFont() end   -- a no-op if that window was closed meanwhile
      nodeFontTarget, nodeFontApplied = nil, false
      hafen.log(":hello node -> resetFont() -- that window is back to the stock/scope font (a :reload/disable reverts it too)")
    else
      local root = hafen.ui()
      if not root then hafen.log(":hello node -> no UI yet (try in-world)"); return end
      -- Collect the open captioned windows AND count the restylable text in each subtree. Picking "the first
      -- window" is a trap: the Inventory/Equipment windows contain only WItem icons, so their ONLY text is the
      -- caption -- styling one of those looks like the override reaches nothing but the title bar. So we score each
      -- candidate by how many descendants report a :text() (labels, button captions, fields) and style the richest
      -- one, reporting every score so it is obvious what was chosen and why.
      local wins = {}
      root:walk(function(n)
        if n:visible() and n:text() and (#n:children() > 0) then
          local texts = 0
          -- :walk reuses the SAME handle for the node it was called on, so `c ~= n` excludes the window itself
          n:walk(function(c) if (c ~= n) and c:visible() and c:text() then texts = texts + 1 end end)
          wins[#wins + 1] = { node = n, name = n:text() or "?", texts = texts }
        end
      end)
      if #wins == 0 then
        hafen.log(":hello node -> no captioned window open. Open a TEXT-RICH window (the Character Sheet, or Options) plus any second one, then try again: only the richest restyles")
        return
      end
      local best, report = 1, {}
      for i, w in ipairs(wins) do
        if w.texts > wins[best].texts then best = i end
        report[#report + 1] = ("%s=%d"):format(w.name, w.texts)
      end
      if wins[best].texts == 0 then
        hafen.log((":hello node -> the only open window(s) [%s] carry NO text but their caption (an Inventory holds item icons, not labels), so an override there would only show on the title bar. Open the CHARACTER SHEET (or Options) and try again")
          :format(table.concat(report, ", ")))
        return
      end
      wins[best].node:setFont(h:derive{ size = 13 })           -- mono 13 (bigger + a different family, so it is obvious)
      nodeFontTarget, nodeFontApplied = wins[best].node, true
      hafen.log((":hello node -> setFont on ONE widget: the '%s' window (%d text bits inside) is now in %s 13 -- caption, labels, list rows and button captions included; every OTHER open window stays stock. Candidates+text counts: [%s]. That is the per-instance override; :hello node again to reset")
        :format(wins[best].name, wins[best].texts, h:family(), table.concat(report, ", ")))
    end
  elseif sub == "selector" then
    -- 030.4: re-run the whole selector contract on demand (it also runs once per login and at +3s -- see
    -- readSelectors above). Worth re-running with a TITLED CONTAINER OPEN: open a cupboard or a chest and the
    -- [title=] block below reports the real enclosing-window scope, the window first and its grid one hop below.
    -- To learn a selector for something you are LOOKING at, enable the 'widgetstack' addon and hover it: the
    -- inspector names the widget's role/class/title/res and offers only selectors it has already resolved.
    readSelectors("cmd")
  elseif sub == "wnd" then
    -- 031.3: re-run the whole window-toggle contract on demand (it also runs once per login and at +3s -- see
    -- readToggle above), and, with the 'swallow' argument, PARK the client in the state Lua cannot observe for
    -- itself: nothing in the API can press Tab, so the swallow is checked by hand. Worth running with 'bags'
    -- replaced too -- then the contract check reports the one-owner refusal, naming bags.
    if args[2] ~= "swallow" then
      readToggle("cmd")
      return
    end
    if swallowedWnd then
      swallowedWnd:show()                     -- :show() gives the widget back AND drops the record: the key is the
      swallowedWnd = nil                      -- client's again (the same restore :reload/disable would have done)
      hafen.log(":hello wnd swallow -> gave the window back: the stock inventory is OPEN again and Tab toggles it"
        .. " as stock (press Tab to close it)")
      return
    end
    local grid = hafen.ui.inventory()
    local wnd = grid and grid:parent()
    if not wnd then hafen.log(":hello wnd swallow -> no inventory window yet (enter the world first)"); return end
    local ok, err = pcall(wnd.hide, wnd)
    if not ok then
      hafen.log((":hello wnd swallow -> refused, one window one owner: %s")
        :format((tostring(err):gsub("^.-%.lua:%d+:%s*", ""))))
      return
    end
    swallowedWnd = wnd
    hafen.log((":hello wnd swallow -> %s is hidden with NOTHING standing in for it. Press Tab and click the"
      .. " inventory button in the menu: nothing opens and the tick stays off -- the toggle is swallowed, because"
      .. " hiding is authoritative now. Every OTHER window (equipment, character sheet, kin, options, the map)"
      .. " toggles exactly as stock. ':hello wnd swallow' again hands it back OPEN; a :reload hands the KEY back"
      .. " and leaves the window closed (nothing was standing in for it), so Tab opens it")
      :format(wnd:type()))
  elseif sub == "widget" then
    -- 029.4: re-run the whole widget-entity contract on demand (it also runs once per login and at +3s -- see
    -- readWidgets above). Worth re-running with a CONTAINER OPEN: open a cupboard, a chest or the study window and
    -- it is listed among the readable containers, VISIBLE, read through the same entity every other door hands back.
    readWidgets("cmd")
    readBags("cmd")
  elseif sub == "prof" then
    -- 019.4: PER-ADDON COST + CUSTOM SCOPES. p:measure(name, fn) brackets a section of OUR code with a named
    -- marker and charges it to THIS addon -- the ProfilerMarker equivalent. Names are per-addon, so another
    -- addon's "scan-gobs" is a different scope, and the whole map dies with us on :reload/disable.
    -- Both p:measure and p:scope run the wrapped code whether profiling is armed or not, so instrumentation
    -- left in a shipped addon costs nothing with the checkbox off -- which is also why this prints an empty
    -- table then: p:addons() is armed-only (Options > Client > Enable profiling).
    local p = hafen.client:profiling()
    local n = p:measure("scan-gobs", function()          -- the wrapper form: cannot forget to finish
      local c = 0
      for _, g in ipairs(hafen.world.gobs()) do if g:name() then c = c + 1 end end
      return c
    end)
    local s = p:scope("spin")                            -- the explicit form: begin/finish around a section
    s:begin()
    local acc = 0
    for i = 1, 200000 do acc = acc + i end
    s:finish()
    hafen.log((":hello prof -> measured a %d-gob scan + a %d-iteration spin; the numbers land NEXT frame (they are"
      .. " charged when the frame closes), so the row is dumped on a short timer -- by then the scopes' per-frame"
      .. " ms is back to 0 and their cost shows in peak/avg"):format(n or 0, acc > 0 and 200000 or 0))
    hafen.timer.after(0.5, function()
      local rows = p:addons()
      if not rows[1] then
        hafen.log(":hello prof -> p:addons() is empty: profiling is OFF (tick Options > Client > Enable profiling)")
        return
      end
      for _, r in ipairs(rows) do                        -- sorted most expensive first
        local sc = {}
        for scopeName, e in pairs(r.scopes) do
          -- ms/calls are THIS frame, and this dump runs half a second after the work -- so the frame being
          -- reported ran no scope at all and both read 0. The measured cost is in peak/avg, which are rolling.
          sc[#sc + 1] = ("%s=%sms/%d now, peak %sms, avg %sms")
            :format(scopeName, fx(e.ms, 3), e.calls, fx(e.msPeak, 3), fx(e.msAvg, 3))
        end
        hafen.log(("  %s ms=%s avg=%s peak=%s share=%s%%  calls{ev=%d,tm=%d,dr=%d,hk=%d,wg=%d}%s")
          :format(padr(r.id, 12), fx(r.ms, 3), fx(r.msAvg, 3), fx(r.msPeak, 3), fx((r.share or 0) * 100, 1),
                  r.calls.events, r.calls.timers, r.calls.draw, r.calls.hooks, r.calls.widgets,
                  (#sc > 0) and ("  scopes{" .. table.concat(sc, ", ") .. "}") or ""))
      end
      local f = p:frame()
      hafen.log(("  total=%sms (%s%% of the frame) -- p:frame().addons=%sms, the SAME accounting")
        :format(fx(rows.total.ms, 3), fx((rows.total.share or 0) * 100, 1), fx(f.addons or 0, 3)))
    end)
  elseif sub == "widgets" then
    -- 019.5: PER-WIDGET COST. p:frame() says the widget tree cost `utick` + `draw` ms; this says WHO. Every
    -- widget is timed by its PARENT, around the call that ticks/draws its whole subtree -- so tickMs/drawMs
    -- are INCLUSIVE of children and selfMs is that minus them. A container with one expensive child therefore
    -- shows a big inclusive and a near-zero self, and only the child is blamed. total is the root's inclusive
    -- tick+draw, and the self times of every row sum to it: the breakdown reconciles, it is not indicative.
    local p = hafen.client:profiling()
    local w = p:widgets()
    if not w.byType then
      hafen.log(":hello widgets -> p:widgets() is empty: profiling is OFF (tick Options > Client > Enable profiling)")
      return
    end
    local tot = w.total or {}
    hafen.log((":hello widgets -> the whole widget tree cost %sms last frame (tick %s + draw %s) over %d"
      .. " live widgets; heaviest TYPES first, self time (children subtracted):")
      :format(fx(tot.ms or 0, 3), fx(tot.tickMs or 0, 3), fx(tot.drawMs or 0, 3), tot.count or 0))
    local sumself = 0
    for i, r in ipairs(w.byType) do
      sumself = sumself + r.selfMs
      if i <= 8 then
        hafen.log(("  %s x%s self=%sms (tick %s + draw %s)   inclusive=%sms")
          :format(padr(r.type, 24), padr(r.count, 3), fx(r.selfMs, 3), fx(r.tickSelfMs, 3),
                  fx(r.drawSelfMs, 3), fx(r.tickMs + r.drawMs, 3)))
      end
    end
    hafen.log(("  ... %d types in all, their self times summing to %sms vs the root's inclusive %sms")
      :format(#w.byType, fx(sumself, 3), fx(tot.ms or 0, 3)))
    -- The heaviest individual widgets, and the owner link: a widget an ADDON put in the tree carries the
    -- addon's id, so the same cost shows up itemised here and rolled up in that addon's p:addons() row --
    -- two views of one measurement, not two measurements. hello's own panel is a `LuaWidget`.
    local mine = {}
    for _, r in ipairs(w.top) do
      if r.owner then mine[#mine + 1] = ("%s owned by '%s' self=%sms"):format(r.type, r.owner, fx(r.selfMs, 3)) end
    end
    local t1 = w.top[1]
    hafen.log(("  heaviest single widget: %s (self=%sms%s)  |  addon-owned in the top list: %s")
      :format(t1 and t1.type or "-", fx(t1 and t1.selfMs or 0, 3), (t1 and t1.id) and (", server id " .. t1.id) or "",
              (#mine > 0) and table.concat(mine, ", ") or "none this frame (look for the LuaWidget row above)"))
  elseif sub == "passes" then
    -- 019.6: NAMED RENDER PASSES + the armed-only GL counters. p:frame() says the frame cost N ms on the CPU
    -- and M ms on the GPU; this says WHERE the GPU time went, over a fixed, curated list of named sections
    -- with CPU and GPU side by side. The list is short on purpose: every boundary is a real GL timestamp
    -- query, so passes are curated, never swept per draw call.
    -- The rows are DISJOINT: `shadow` and `scene` run inside the widget draw (the MapView is a widget), so
    -- each row is SELF time -- its span minus the passes nested in it -- exactly like p:widgets(). That is
    -- why `ui2d` means the 2D UI and why the three sum to less than the frame instead of double-counting.
    local p = hafen.client:profiling()
    local ps = p:passes()
    if not ps[1] then
      hafen.log(":hello passes -> p:passes() is empty: profiling is OFF (tick Options > Client > Enable"
        .. " profiling), or no frame's GL timestamps have come back yet -- they arrive several frames late")
      return
    end
    local sum = 0
    for _, r in ipairs(ps) do sum = sum + r.gpuMs end
    hafen.log((":hello passes -> frame #%d cost %sms on the CPU / %sms on the GPU; the named passes:")
      :format(ps.frameno, fx(ps.ms, 3), fx(ps.gpuMs, 3)))
    for _, r in ipairs(ps) do
      hafen.log(("  %s cpu=%sms  gpu=%sms  (%s%% of the GPU frame)")
        :format(padr(r.name, 8), fx(r.cpuMs, 3), fx(r.gpuMs, 3),
                fx((ps.gpuMs > 0) and (r.gpuMs / ps.gpuMs * 100) or 0, 1)))
    end
    hafen.log(("  the three sum to %sms of the %sms GPU frame -- turn Video > Shadows OFF and the"
      .. " `shadow` row falls to zero and the GPU frame drops by about what it was reporting")
      :format(fx(sum, 3), fx(ps.gpuMs, 3)))
    -- The armed-only submission counters: unlike p:render(), which exposes numbers the client already keeps
    -- (so it answers with profiling off), NOTHING counts these -- they are new counting behind the switch.
    -- programBinds far below drawCalls is the draw list's program sort doing its job.
    local g = p:gl()
    hafen.log(("  p:gl() frame #%d: %d draw calls, %d program binds, %s vertices, %s triangles")
      :format(g.frameno, g.drawCalls, g.programBinds,
              ("%d"):format(g.vertices), ("%d"):format(g.triangles)))
  elseif sub == "overhead" then
    -- 019.7: WHAT PROFILING ITSELF COSTS, per tier. 019 promises armed overhead of no more than 5% of frame
    -- time (target 2%), and that promise is only enforceable if the cost is known AND attributed: a tier
    -- that misses the budget moves behind its own checkbox, which needs the tier to be identifiable.
    -- Every figure is a MEAN PER FRAME since the switch was armed (or p:reset()).
    -- Two numbers, cross-checking each other:
    --   * modelled  -- probe hits x a per-hit cost calibrated once when the switch armed. Available at once,
    --                  and errs HIGH (the calibration loops run cold; the real probes run JIT-compiled).
    --   * measured  -- one frame in 64 runs with every probe DISARMED (a "control frame"); each period gives
    --                  one delta (median armed WORK time minus the control frame's), and the median of those
    --                  is the overhead, actually measured. `method` says which of the two totalMs is.
    -- Work time, not frame time: under vsync the frame total is pinned to the cap and would never move.
    -- measuredMs <= 0 is the NORMAL outcome here and does not mean profiling made the client faster: it
    -- means the cost is under measuredSpreadMs, the comparison's own noise floor. The model then has the
    -- say -- it at least knows how many probes ran -- and the measurement is printed anyway.
    local p = hafen.client:profiling()
    local o = p:overhead()
    if not o.totalMs then
      hafen.log(":hello overhead -> p:overhead() is empty: profiling is OFF (tick Options > Client > Enable"
        .. " profiling)")
      return
    end
    hafen.log((":hello overhead -> %sms/frame of a %sms frame = %s%% (budget %s%%: %s), method=%s")
      :format(fx(o.totalMs, 4), fx(o.frameMs or 0, 2), fx((o.shareOfFrame or 0) * 100, 2),
              fx(o.budget * 100, 0), (o.withinBudget == false) and "OVER" or "ok", o.method))
    hafen.log(("  aggregator=%sms (timed)  gpuQuery=%sms (timed)  probes=%sms (modelled)")
      :format(fx(o.aggregatorMs, 4), fx(o.gpuQueryMs, 4), fx(o.probeMs, 4)))
    if o.measuredMs then
      hafen.log(("  control frames measure %s +/- %s ms/frame (spread %s) -> %s")
        :format(((o.measuredMs >= 0) and "+" or "") .. fx(o.measuredMs, 4), fx(o.measuredErrorMs, 4),
                fx(o.measuredSpreadMs, 4),
                (o.method == "control") and "resolved, and it has the say"
                  or "inside its own error bar: too cheap to measure, so the model has the say"))
    end
    for _, r in ipairs(o.tiers) do
      hafen.log(("  %s %sms  (%s%% of frame, modelled %sms, %s%s)")
        :format(padr(r.name, 8), fx(r.ms, 4), fx((r.share or 0) * 100, 2), fx(r.modelledMs, 4), r.method,
                r.hits and (", %s probe hits/frame"):format(fx(r.hits, 0)) or ""))
    end
    hafen.log(("  %d armed frames, %d control frames, %d paired periods%s")
      :format(o.armedFrames, o.controlFrames, o.periods,
              (o.periodsNeeded > 0)
                and (" -- %d more period(s) before the measurement counts, ~%ss")
                    :format(o.periodsNeeded, fx(o.periodsNeeded * 64 * (o.frameMs or 16) / 1000, 0))
                or ""))
  elseif sub == "textcache" then
    -- 026.2: THE TEXT CACHE. g:text/g:atext used to rasterise their string AND create + destroy a GL texture
    -- EVERY FRAME (~0.28ms a line, ~50x the cost of geometry); since 026 the wrapper HOLDS the rendered raster
    -- in a per-addon, content-keyed, bounded LRU, so an unchanged string is rasterised once and blitted after.
    -- p:textcache() is PULL-ONLY like p:memory()/p:net()/p:loader()/p:render(): it answers with the profiling
    -- checkbox OFF, because every number in it is one the cache keeps anyway in order to bound itself.
    -- The top level is THIS addon's cache; `total` sums every Lua owner (addons + the :lua REPL) -- which is
    -- also the leak check: disable every addon and total.bytes goes to ~0, because teardown disposes them.
    -- The HUD overlay deliberately draws one STATIC line (a hit every frame) beside one VOLATILE line (a miss
    -- every frame, its text carries the frame number), so both rates below are real and always non-zero.
    local p = hafen.client:profiling()
    if args[2] == "stress" then
      -- The BOUND check. A pool of 2000 distinct strings -- far past the entry cap -- drawn 32 per frame in
      -- the HUD overlay at alpha 0 (rasterised and cached for real, painted not at all). Entries climb to the
      -- cap and then STOP, bytes stop rising with them, and evictions start counting: bounded, not growing.
      -- It is genuinely expensive while it runs (32 fresh rasterisations a frame is the pre-026 cost, by
      -- design), so it is a toggle and it is off by default.
      hafen.log(textcacheStress())
      return
    end
    local c = p:textcache()
    local function pct(v) return (v ~= nil) and (fx(v * 100, 1) .. "%") or "-" end
    local function mib(v) return fx(v / 1048576, 2) .. " MiB" end
    hafen.log((":hello textcache -> hello's own cache: %d entries / %s held, %d hits + %d misses = %s hit rate,"
      .. " %d evictions"):format(c.entries, mib(c.bytes), c.hits, c.misses, pct(c.hitRate), c.evictions))
    hafen.log(("  bounded by %d entries AND %s -- entries stop at the cap, they do not grow (`:hello textcache"
      .. " stress` proves it: %d distinct strings, %d a frame, invisible)")
      :format(c.maxEntries, mib(c.maxBytes), STRESS_POOL, STRESS_PER_FRAME))
    hafen.log(("  every Lua owner together (%d): %d entries / %s, %s hit rate, %d evictions -- disable every"
      .. " addon and this drops to ~0 bytes (teardown disposes each cache: the leak check)")
      :format(c.total.owners, c.total.entries, mib(c.total.bytes), pct(c.total.hitRate), c.total.evictions))
    hafen.log("  a MISS is not a fault: it is a string never drawn before in that font. The HUD's `026 static"
      .. " line` hits every frame; `026 volatile line` misses every frame because its text changes every frame"
      .. " -- budget a live readout by how often its TEXT changes, not by how many lines it has.")
  else
    hafen.log((":hello got %d arg(s): %s  (try: toggle | ping | echo | craft | quest | wound | fight | ghost | sprite | billboard | follow | object | assets | font | title | button | entry | label | heading | menu | tip | chat | speech | nick | node | prof | widgets | passes | overhead | textcache)")
      :format(#args, table.concat(args, " | ")))
  end
end)
hafen.log("A11: slash command registered -- type  :hello  in the console (chat) to try it")

hafen.events.on("OnEnterWorld", function()
  if panel then return end                                        -- defensive: create the window once
  panel = hafen.ui.window{
    title   = "Hello 3a",
    size    = { 190, 166 },                                        -- 027.3: room for 5 meter rows (mounted) above the bottom-anchored hook lines
    pos     = { 80, 120 },
    onDraw  = drawPanel,
    onClick = function(x, y, button)
      clicks = clicks + 1
      if button == 3 then                                        -- RIGHT-click -> 2d: toggle the action hook
        moveIntercept = not moveIntercept
        hafen.log(("panel RMB #%d -> move-intercept %s"):format(clicks, moveIntercept and "ON" or "OFF"))
      elseif button == 2 then                                     -- MIDDLE-click -> 2e: toggle the message hook
        meterFreeze = not meterFreeze
        hafen.log(("panel MMB #%d -> meter-freeze %s"):format(clicks, meterFreeze and "ON" or "OFF"))
      else                                                        -- LEFT/other -> 2c: toggle the input hook
        mapLock = not mapLock
        hafen.log(("panel click #%d at %d,%d (button %d) -> map-lock %s")
          :format(clicks, x, y, button, mapLock and "ON" or "OFF"))
      end
      return true                                                 -- truthy = consume the click
    end,
    onClose = function() hafen.log("panel closed (X) -- :reload to bring it back") end,
  }
  hafen.log("2a: custom window up -- drag the title bar, LMB=map-lock, RMB=move-intercept, MMB=meter-freeze, X=close, 'toggle' key=show/hide")

  -- U1: DROP TARGET + g:resource + mouse mods. A borderless custom widget (hafen.ui.widget) that is a
  -- DROP TARGET for the client's own drag gesture (D-038): open the menu grid (bottom-right), drag an
  -- action onto this box, and onDrop(x, y, drop) fires with drop = { kind="pagina", res="<name>" } (a
  -- neutral descriptor -- a resource name, plain data, so this is UNGATED). We remember the res and DRAW
  -- ITS ICON via g:resource(name, ...) -- the engine .res sibling of g:image (D-039), async + cached +
  -- Loading-guarded. onClick logs the mods table (D-040): Shift+click the box and the log shows shift=true.
  -- Bridge-owned (P2): :reload/disable destroys it. The DoD: drop an action -> icon renders + res logs;
  -- Shift+click -> shift=true; :reload leaks nothing.
  droppedRes = nil
  dropWidget = hafen.ui.widget{
    size = { 96, 96 },
    pos  = { 80, 270 },
    onDraw = function(g, w, h)
      g:color(0, 0, 0, 150); g:frect(0, 0, w, h); g:color()        -- own slot background (invsq is not a .res)
      g:color(150, 150, 170); g:rect(0, 0, w, h); g:color()
      if droppedRes then
        g:resource(droppedRes, 8, 8, w - 16, h - 32)               -- the dropped action's engine icon, scaled
        g:atext("dropped", w / 2, h - 4, 0.5, 1.0)
      else
        g:atext("drag an", w / 2, h / 2 - 8, 0.5, 0.5)
        g:atext("action here", w / 2, h / 2 + 6, 0.5, 0.5)
      end
    end,
    onDrop = function(x, y, drop)
      if drop.res then
        droppedRes = drop.res
        hafen.log(("U1: onDrop at %d,%d -> kind=%s res=%s (drawing its icon via g:resource)")
          :format(x, y, tostring(drop.kind), tostring(drop.res)))
      else
        hafen.log(("U1: onDrop at %d,%d -> kind=%s (id-only pagina, no stable res -- not persistable)")
          :format(x, y, tostring(drop.kind)))
      end
      return true                                                  -- truthy = consume the drop
    end,
    onClick = function(x, y, button, mods)
      hafen.log(("U1: drop-widget click at %d,%d btn=%d mods={shift=%s,ctrl=%s,alt=%s}")
        :format(x, y, button, tostring(mods.shift), tostring(mods.ctrl), tostring(mods.alt)))
      return true
    end,
  }
  hafen.log("U1: drop-target widget up -- open the menu grid, drag an action onto the box (icon draws via g:resource); Shift+click logs shift=true")

  -- F2: OWN-WIDGET FONTS + $font MIXING. This window declares font = demoFont (the F1-loaded handle), so EVERY
  -- g:text/g:atext inside it defaults to the addon's OWN font -- fully ISOLATED (no global override, nothing the
  -- other addons or the stock UI can see). A per-call { font = h } / { color = {..} } overrides one line, and a
  -- $font[family,sz]{...} tag mixes TWO fonts on ONE line (the F2 headline) -- it works because loading the .ttf
  -- AWT-registered the family, so we just feed demoFont:family() to the existing rich-text tag (zero engine
  -- markup change). SAFE-tier, client-only. Disabling/:reload leaves the stock UI untouched. See api/fonts.md.
  if demoFont then
    local fam = demoFont:family()
    fontWin = hafen.ui.window{
      title = "Hello F2 (fonts)",
      size  = { 240, 118 },
      pos   = { 290, 120 },
      font  = demoFont,                                            -- the window's DEFAULT font for its g:text draws
      onDraw = function(g, w, h)
        g:color(0, 0, 0, 150); g:frect(0, 0, w, h); g:color()
        -- 1) plain line -> uses the window's font= default (the addon's own font), no per-call opts:
        g:text("This line uses the window font=", 6, 6)
        -- 2) a $font mix on ONE line: the first run in demoFont via its family, the rest in the client sans:
        g:text(("$font[%s,16]{Fancy} + $font[SansSerif,12]{plain} on one line"):format(fam), 6, 26)
        -- 3) per-call font override (mono) + per-call colour, proving g:text(str,x,y,{font=,color=}):
        g:text("per-call mono, coloured", 6, 52, { font = monoFont, color = { 120, 220, 255 } })
        -- 4) rich colour/bold tags also work now that g:text renders through rich text:
        g:text("$col[235,180,80]{$b{rich} tags} work too", 6, 74)
        g:color(150, 150, 150); g:rect(0, 0, w, h); g:color()
      end,
      onClose = function() hafen.log("F2: font window closed (X) -- :reload to bring it back") end,
    }
    hafen.log(("F2: font window up -- rendered in font '%s'; one line mixes two fonts via $font (disable/:reload restores stock)")
      :format(fam))
  else
    hafen.log("F2: demoFont not loaded (OnLoad) -- font window skipped")
  end

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
  -- to the HUD meter bars (ev.target == "IMeter"; "set" is what LayerMeter uses to update a bar). MIDDLE-click
  -- the window to arm meter-freeze. While ON, ev:preventDefault() SWALLOWS the meter update, so it never reaches
  -- the widget: the bars -- both in this window and the REAL HUD meters -- FREEZE (and no
  -- MeterChanged fires, since nothing changed). Toggle it off and the next update thaws them -- fully reversible,
  -- purely cosmetic (the server still knows your real values). While OFF we only observe-log the first few meter
  -- "set" messages, proving L3 sees inbound traffic. ev.args is a 1-based snapshot (ev:rewrite(t) could apply new
  -- args instead -- not used here). NB: this handler runs on a Loader thread under the UI lock, so keep it light.
  -- The handle is bridge-owned (:reload/disable removes the hook -- no leak).
  hafen.hook.message("set", function(ev)
    if ev.target ~= "IMeter" then return end                     -- only the HUD meter bars, not every "set"       
    if meterFreeze then
      ev:preventDefault()                                        -- swallow it -> the meter never updates (bar freezes)
    elseif msgHookSeen < 3 then
      msgHookSeen = msgHookSeen + 1
      hafen.log(("2e: meter 'set' observed (target=%s, %d arg(s)) (passed through) [#%d]")
        :format(ev.target, #ev.args, msgHookSeen))
    end
  end)
  hafen.log("2e: IMeter 'set' message hook installed -- MIDDLE-click the window to freeze the HUD meter bars")
end)

-- 2b: HUD OVERLAY (hafen.ui.overlay). Paint on top of the HUD WITHOUT owning a widget — fn(g, w, h) runs
-- every frame with the shared GOut wrapper and the SCREEN size, drawing at absolute screen coords. It is
-- drawn AFTER the whole HUD (via a re-queued UI.drawafter), so it lands on top. Bridge-owned (P2): :reload
-- or disabling the addon removes it automatically. Here: a small readout box at top-centre + a crosshair at
-- the exact screen centre. The gob count is refreshed once a second by a timer (NOT scanned every frame —
-- draw callbacks should stay cheap; the per-frame draw time is not covered by the soft CPU budget).
local gobCount = 0
hafen.timer.every(1, function() gobCount = hafen.world.count() end)

-- 026.2: THE TEXT CACHE, made visible. g:text/g:atext hold their rendered raster across frames now (a per-addon,
-- content-keyed, bounded LRU), so a line whose STRING is unchanged is rasterised once and blitted thereafter --
-- but a line whose string changes every frame misses every frame and costs exactly what it always did. Both are
-- drawn here, side by side and permanently, so `:hello textcache` always has a real hit rate AND a real miss
-- rate to report: `hudFrames` ticks once per drawn frame, so the volatile line is guaranteed new text every
-- frame no matter what the world is doing.
local hudFrames = 0
-- The bound check: a POOL of distinct strings, far more than the entry cap, drawn a slice at a time so the
-- client stays usable while it runs. Toggled by `:hello textcache stress`; nil = off. Alpha 0, so it rasterises
-- and caches for real without painting anything -- the cost and the cache churn are the point, not the pixels.
local stressPool, stressAt = nil, 0

local function drawHud(g, w, h)
  -- 2c/2d/2e: surface the hook states here too, so the input+action+message hooks have clear on-HUD feedback
  -- (border turns red while map-lock cancels clicks, orange while move-intercept re-sends moves, cyan while
  -- meter-freeze swallows meter updates).
  local txt = ("2b HUD  gobs=%d  map-lock=%s  move=%s  freeze=%s"):format(
    gobCount, mapLock and "ON" or "OFF", moveIntercept and "ON" or "OFF", meterFreeze and "ON" or "OFF")
  local bw = 340
  local x = math.floor(w / 2 - bw / 2)
  g:color(0, 0, 0, 140); g:frect(x, 2, bw, 18); g:color()        -- translucent backdrop
  if mapLock then g:color(235, 90, 90)                           -- red: L1 cancelling map clicks
  elseif moveIntercept then g:color(245, 160, 60)                -- orange: L2 intercepting + re-sending moves
  elseif meterFreeze then g:color(90, 210, 235)                 -- cyan: L3 swallowing meter updates
  else g:color(120, 200, 120) end                                -- green: hooks observing only
  g:rect(x, 2, bw, 18); g:color()
  g:text(txt, x + 6, 4)
  -- R1: an ANCHORED image (g:aimage) just LEFT of the readout box -- ax=1 (right edge at x-4), ay=0.5 (centred).
  if icon then g:aimage(icon, x - 4, 11, 1.0, 0.5) end
  -- 026.2: the cache pair. The first string never changes -> one rasterisation for the whole session, a cache
  -- HIT every frame after the first. The second changes every frame -> a MISS every frame, by construction.
  hudFrames = hudFrames + 1
  g:color(150, 150, 150)
  g:text("026 static line -- rasterised once, blitted thereafter", x + 6, 22)
  g:color(200, 170, 120)
  g:text(("026 volatile line -- frame %d (a miss, every frame)"):format(hudFrames), x + 6, 36)
  g:color()
  if stressPool then                                             -- the bound check, a slice per frame
    g:color(0, 0, 0, 0)                                          -- fully transparent: cached for real, painted not at all
    for i = 1, STRESS_PER_FRAME do
      stressAt = (stressAt % STRESS_POOL) + 1
      g:text(stressPool[stressAt], x + 6, 50)
    end
    g:color()
  end
  local cx, cy = math.floor(w / 2), math.floor(h / 2)            -- crosshair at the exact screen centre
  g:color(255, 90, 90, 200)
  g:line(cx - 8, cy, cx + 8, cy, 1); g:line(cx, cy - 8, cx, cy + 8, 1)
  g:color()
end

-- 026.2: the bound check's toggle (forward-declared far above, next to the slash command that calls it). ON
-- builds a pool of STRESS_POOL distinct strings and lets drawHud feed STRESS_PER_FRAME of them per frame to
-- the cache; OFF drops the pool -- the entries it left behind are not freed here, they simply age out of the
-- LRU as normal drawing reuses it, which is itself worth watching in `:hello textcache`.
textcacheStress = function()
  if stressPool then
    stressPool, stressAt = nil, 0
    return (":hello textcache stress -> OFF (the %d entries it pushed in stay until the LRU ages them out --"
      .. " watch `:hello textcache` entries fall back as normal drawing reuses the cache)"):format(STRESS_POOL)
  end
  stressPool = {}
  for i = 1, STRESS_POOL do stressPool[i] = ("stress line %d of %d -- distinct by construction"):format(i, STRESS_POOL) end
  stressAt = 0
  return (":hello textcache stress -> ON: %d distinct strings, %d drawn per frame at alpha 0 (invisible, but"
    .. " rasterised and cached for real). Watch `:hello textcache`: entries climb to the cap and STOP, bytes"
    .. " stop with them, evictions start counting. FPS will drop hard while it runs -- %d fresh rasterisations"
    .. " a frame IS the pre-026 cost, which is the point. `:hello textcache stress` again turns it off.")
    :format(STRESS_POOL, STRESS_PER_FRAME, STRESS_PER_FRAME)
end

-- 2b: WORLD-SPACE gob overlay (hafen.ui.gobOverlay). filter(gob) selects gobs (here: players — :isplayer()
-- is true when the base sprite is the borka body); draw(g, gob, sx, sy) paints at the gob's projected screen
-- point (just above the head). Your OWN gob always matches, so you will see at least your own tag. Since 017
-- the gob is a live Gob OBJECT (D-044), so the callbacks read it with methods — and self-identification is
-- plain identity now: gob == hafen.player():gob() (interning, D-045), no id compare. There is no display-name
-- field for other players (a client limitation), so we show the char name for self. Auto-removed on teardown.
local function drawPlayerTag(g, gob, sx, sy)
  local label = (gob == hafen.player():gob()) and (hafen.player():name() or "you") or "player"
  g:color(80, 220, 90); g:frect(sx - 3, sy - 3, 6, 6); g:color()   -- a marker dot at the anchor
  g:atext(label, sx, sy - 6, 0.5, 1.0)                             -- name centred just above the marker
end

local overlaysUp = false
hafen.events.on("OnEnterWorld", function()
  if overlaysUp then return end                                   -- register the overlays once
  overlaysUp = true
  hafen.ui.overlay(drawHud)                                       -- returns a handle with :remove() (also auto)
  hafen.ui.gobOverlay(function(gob) return gob:isplayer() end, drawPlayerTag)
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
