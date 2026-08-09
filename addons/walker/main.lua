-- Walker — the WRITE-ACTIONS demo addon (dormant, opt-in).
--
-- It DECLARES "permissions": ["actions"] in its manifest, so it is DISABLED BY DEFAULT when first discovered
-- (write-actions are a PER-ADDON permission, opt-in per addon — D-027/D-028). To use it, enable it in
-- Options > AddOns: because it can act on your behalf, the panel asks you to CONFIRM first (the consent dialog,
-- slice 4c). Once you enable it and Reload UI it loads like any addon, and hafen.act():enabled() is true here
-- (it declared the permission). There is NO global switch (D-028): the permission is granted purely by YOUR
-- enabling this one addon. hafen.act is the ONE part of hafen.* that DRIVES the character: it sends
-- player-action wdgmsgs to the server (everything else only observes). It stays server-authoritative — an addon
-- can only send what a player click could send; the permission exists so YOU control which addons act for you.
-- Kept SEPARATE from the always-on read-only `hello` regression harness (which declares no permissions).
--
-- Slice 4d adds the rest of the MapView action verbs on top of moveTo (4a); slice 4e adds menu + flower;
-- slice 4f adds the ITEM verbs (hafen.act():item); slice 4g adds the PER-SUBSYSTEM gated verbs that live in
-- their own namespace (not hafen.act()): hafen.speed():current(n), the Craft's :make, the Slot's :use, and
-- the kin verbs on the Kin object (hafen.kin():add(secret) and kin:rename/:group/:endKin/:forget)
-- — all behind the SAME "actions" permission.
-- Each is a DELIBERATE, opt-in trigger — a `:walker <sub>` command — so nothing acts unless you ask.
-- Sub-commands:
--   :walker walk        -- moveTo: walk ~2 tiles south  (the original 4a demo)
--   :walker click       -- clickGob: RIGHT-click the nearest object (opens its context menu — safe/cancelable)
--   :walker use         -- useItemOn: use the item on your cursor on the ground under you (no-op if empty-handed)
--   :walker sel         -- select: area-select the ~3x3 tiles around you (drives tile-area tools)
--   :walker place       -- place: drop the object on your cursor at your feet facing north (no-op if not placing)
--   :walker raw         -- raw: send the same walk "click" straight to the MapView (the escape hatch == moveTo)
--   :walker menu <t...> -- menu: invoke a menu/pagina action by path (e.g. ':walker menu lo cs' = log out to
--                          character select — reversible). Path tokens are content-defined, so YOU supply them.
--   :walker flower <l>  -- flower: RIGHT-click the nearest object, then auto-select its petal named <l> after a
--                          brief delay (a flower menu grabs input, so a timed pick is the only programmatic way).
--   :walker petal <l...>|n <k>|cancel
--                       -- the radial menu's OWN write half: arm the NEXT menu you open and, from inside
--                          FlowerMenuOpened, hafen.flowermenu():select(caption) / :select(position) / :cancel().
--   :walker item [verb] -- item: act on your FIRST inventory item, addressed by the Item OBJECT (from a
--                          read). Default 'take' lifts it to your cursor (safe/reversible: click an empty slot to
--                          undo). Pass a verb: take|drop|transfer|iact|itemact.
--   :walker speed [n]   -- speed:current(n): select movement speed n=0..3 (crawl/walk/run/sprint; default 2=run). Reversible.
--   :walker craft [all] -- craft.make: press Craft on the OPEN recipe (add 'all' for Craft All). CONSUMES ingredients!
--   :walker bar <n>     -- slot:use: activate slot n (raw 0-based index; read hafen.actionbar():get(n) first)
--   :walker setbar <n> <res>  -- slot:res: ASSIGN the action named <res> to slot n (what a drag from the menu
--                          grid does). Overwrites the slot; right-click it in-game to clear.
--   :walker menugrid <name>   -- pagina:use: fire an ACTION MENU entry by display name (e.g. ':walker menugrid Dig')
--                          or by resource name if you pass one containing a '/'. A category errors -- it lists
--                          its :children() instead.
--   :walker kin add <secret>  -- hafen.kin():add: add a kin by the other player's HEARTH SECRET (wdgmsg 'bypwd')
--   :walker kin <name> group <0..7>|rename <new>|endkin|forget
--                       -- kin:group/:rename a named kin (reversible), OR the two-step drop: endkin = End
--                          kinship (stays memorized), then forget = drop the memorized kin from the list

hafen.log():write("walker loaded -- write-actions demo (4d MapView verbs + 4e menu/flower + 4f item + 4g speed/craft/bar/setbar/kin + menugrid)")

-- At login, confirm we're granted (we only load once YOU enabled us, and we declared the permission).
hafen.event():on("EnterWorld", function()
  hafen.log():write(("walker: write-actions %s -- run  :walker  for the list of action demos")
    :format(hafen.act():enabled() and "GRANTED" or "NOT granted"))
end)

local SOUTH = 22   -- world units ~ 2 tiles (tilesz = 11) to the south

-- The single armed one-shot of `:walker petal` (047.2). Held out here so a second `:walker petal` replaces
-- the first instead of stacking a second picker onto the same menu.
local petalSub = nil

-- One command with sub-verbs, each exercising one gated hafen.act() MapView verb.
hafen.slash():register("walker", function(args)
  local sub = args[1] or "help"

  if sub == "help" then
    hafen.log():write(":walker sub-commands -> walk | click | use | sel | place | raw | menu | flower | petal | item | speed | craft | bar | setbar | menugrid | kin")
    hafen.log():write("   walk=moveTo  click=clickGob(right)  use=useItemOn  sel=select  place=place  raw=raw escape hatch")
    hafen.log():write("   menu=hafen.act():menu(path...)  e.g. ':walker menu lo cs' = log out to char select (reversible)")
    hafen.log():write("   flower=hafen.act():flower(label)  e.g. ':walker flower Harvest' = right-click nearest, pick a petal")
    hafen.log():write("   petal <label...>|n <k>|cancel=hafen.flowermenu():select/:cancel  arms the NEXT menu you open, picked from FlowerMenuOpened")
    hafen.log():write("   item [verb]=hafen.act():item(firstInvItem, verb)  default take (lifts to cursor); take|drop|transfer|iact|itemact")
    hafen.log():write("   -- 4g per-subsystem gated verbs (own namespace, same permission):")
    hafen.log():write("   speed [n]=hafen.speed():current(n)  0..3 crawl/walk/run/sprint (default 2=run, reversible)")
    hafen.log():write("   craft [all]=hafen.craft():current():make(all)  press Craft on the OPEN recipe (CONSUMES ingredients; 'all'=Craft All)")
    hafen.log():write("   bar <n>=hafen.actionbar():get(n):use()  activate action-bar slot n (raw 0-based index)")
    hafen.log():write("   setbar <n> <res>=hafen.actionbar():get(n):res(name)  assign an action by resource name (e.g. gfx/hud/act/mine)")
    hafen.log():write("   menugrid <name>=hafen.menugrid():get(name):use()  fire an action-menu entry (e.g. ':walker menugrid Dig')")
    hafen.log():write("   kin add <secret> =add by hearth secret; kin <name> group/rename =kin:group/:rename; endkin =End kinship, forget =drop memorized kin")
    return
  end

  -- Every verb is gated: bail with a clear hint if this addon somehow isn't granted (it declared the perm,
  -- so this only trips if you edited the manifest). enabled() never throws, so no pcall is needed.
  if not hafen.act():enabled() then
    hafen.log():write((":walker %s -> write-actions not granted (enable this addon + confirm the consent dialog)."):format(sub))
    return
  end

  local me = hafen.player():gob()                    -- the Gob OBJECT for your character (nil before enter-world)
  local p = me and me:position()                     -- a POSITION: computable (p:offset) and durable (p:info)
  if not p then hafen.log():write(":walker -> no player position yet"); return end

  if sub == "walk" then
    -- p:offset asks the ENGINE for the point 22 units south. A grid is 1100 units wide, so `p.y + SOUTH`
    -- was only ever right until it was not: past the edge the answer is a different grid.
    local to = p:offset(0, SOUTH)
    hafen.act():moveTo(to)                             -- the MapView "click" a left-click on that spot sends
    hafen.log():write((":walker walk -> moveTo(%.1f, %.1f)  [~2 tiles south -- watch your character walk]"):format(to:x(), to:y()))

  elseif sub == "click" then
    local g = hafen.world():gob():nearest(function(g) return not g:isPlayer() end)  -- nearest non-player Gob OBJECT
    if not g then hafen.log():write(":walker click -> no object nearby"); return end
    hafen.act():clickGob(g, 3)                            -- clickGob takes the Gob itself; 3 = RIGHT-click (safe/cancelable)
    hafen.log():write((":walker click -> right-clicked %s (id %d) -- its context menu should open"):format(g:name() or "?", g:id()))

  elseif sub == "use" then
    hafen.act():useItemOn(p)                           -- apply the cursor item to the ground under you
    hafen.log():write(":walker use -> useItemOn at your feet (hold something on your cursor first, else the server ignores it)")

  elseif sub == "sel" then
    hafen.act():select(p:offset(-11, -11), p:offset(11, 11))   -- ~3x3 tiles centred on you
    hafen.log():write(":walker sel -> area-selected the ~3x3 tiles around you (visible only with a tile-area tool active)")

  elseif sub == "place" then
    hafen.act():place(p, 0)                            -- angle 0 rad = north; no-op unless something is on your cursor
    hafen.log():write(":walker place -> place at your feet facing north (start building something first, else nothing happens)")

  elseif sub == "raw" then
    -- The escape hatch: send the walk "click" straight to the MapView, building the wire args by hand.
    -- Server units = world * 1024/11 (posres = 11/1024 world-units per server-unit), floored — exactly what
    -- moveTo does internally. So this walks ~2 tiles south too, proving raw(target, msg, ...) reaches the widget.
    local su = 1024 / 11
    local mc = { x = math.floor(p:x() * su), y = math.floor((p:y() + SOUTH) * su) }
    hafen.act():raw("mapview", "click", { x = 0, y = 0 }, mc, 1, 0)
    hafen.log():write((":walker raw -> raw('mapview','click', pc, {x=%d,y=%d}, 1, 0)  [== moveTo ~2 tiles south]"):format(mc.x, mc.y))

  elseif sub == "menu" then
    -- Menu paths are content-defined / localized (not a stable address space, see docs) and some COMMIT
    -- real actions, so YOU supply the tokens. With none given we just show the guaranteed client example.
    if not args[2] then
      hafen.log():write(":walker menu <token...> -> invoke a menu/pagina action by path. Tokens are content-defined;")
      hafen.log():write("   the one client-guaranteed example is  :walker menu lo cs  (log out to character select -- reversible).")
      return
    end
    local path = {}
    for i = 2, #args do path[#path + 1] = args[i] end
    hafen.act():menu(table.unpack(path))                 -- table.unpack (Lua 5.2 / LuaJ); the sandbox has no global unpack
    hafen.log():write((":walker menu -> hafen.act():menu(%s)"):format(table.concat(path, ", ")))

  elseif sub == "flower" then
    -- A flower menu grabs the mouse+keyboard while open, so you can't type a command to pick a petal by hand.
    -- The programmatic route: right-click a target (opens its flower after a server round-trip), then a TIMER
    -- auto-selects the petal by label. Give the label you expect (e.g. 'Harvest' on a bush, 'Pick' on a plant).
    local label = args[2]
    if not label then
      hafen.log():write(":walker flower <label> -> right-clicks the nearest object, then auto-picks that petal (e.g. ':walker flower Harvest').")
      return
    end
    local g = hafen.world():gob():nearest(function(g) return not g:isPlayer() end)
    if not g then hafen.log():write(":walker flower -> no object nearby"); return end
    hafen.act():clickGob(g, 3)                            -- button 3 = RIGHT-click => opens its flower menu (after a round-trip)
    hafen.log():write((":walker flower -> right-clicked %s (id %d); auto-picking petal '%s' in 0.5s..."):format(g:name() or "?", g:id(), label))
    hafen.timer():after(0.5, function()
      local ok = hafen.act():flower(label)               -- returns true iff a matching petal was selected
      hafen.log():write((":walker flower -> hafen.act():flower('%s') => %s"):format(
        label, ok and "chosen" or "no such petal / no menu open (right-click gave a direct action, or retry)"))
    end)

  elseif sub == "petal" then
    -- 047.2: the radial menu's OWN write half. hafen.act():flower(label) fires blind on a timer -- it has no way
    -- to know a menu came up, so the delay is a guess. The section does know: the pick happens from INSIDE
    -- FlowerMenuOpened, the earliest moment there is, during the opening animation that swallows real clicks.
    -- It picks by caption or by the petal's 1-based position on the ring, and :cancel() closes it as Esc does.
    local what = args[2]
    if not what then
      hafen.log():write(":walker petal <label...> | n <k> | cancel -> arms the NEXT radial menu you open:")
      hafen.log():write("   :walker petal Pick branch -> hafen.flowermenu():select('Pick branch')  (caption, case-insensitive)")
      hafen.log():write("   :walker petal n 2         -> hafen.flowermenu():select(2)              (1-based ring position)")
      hafen.log():write("   :walker petal cancel      -> hafen.flowermenu():cancel()               (exactly as Esc does)")
      hafen.log():write("   then RIGHT-CLICK anything: a tree, another player, a kin row in the Kin window.")
      return
    end
    local key, shown
    if what == "cancel" then
      key, shown = nil, "cancel()"
    elseif what == "n" then
      key = tonumber(args[3])
      if not key then hafen.log():write(":walker petal n <k> -> k must be a number (the 1-based ring position)"); return end
      shown = ("select(%s)"):format(tostring(key))
    else
      local parts = {}
      for i = 2, #args do parts[#parts + 1] = args[i] end
      key = table.concat(parts, " ")                       -- captions have spaces ("Pick branch")
      shown = ("select('%s')"):format(key)
    end
    if petalSub then petalSub:off() end                    -- only ever ONE armed picker
    petalSub = hafen.event():on("FlowerMenuOpened", function(petals)
      if petalSub then petalSub:off(); petalSub = nil end   -- one-shot, and disarmed BEFORE we act
      hafen.log():write((":walker petal -> menu opened with %d petals: %s"):format(#petals, table.concat(petals, ", ")))
      local ok, err = pcall(function()
        if key == nil then hafen.flowermenu():cancel() else hafen.flowermenu():select(key) end
      end)
      hafen.log():write((":walker petal -> hafen.flowermenu():%s => %s"):format(shown, ok and "chosen" or tostring(err)))
    end)
    hafen.log():write((":walker petal -> armed hafen.flowermenu():%s for the NEXT menu -- right-click something now."):format(shown))

  elseif sub == "item" then
    -- 4f: item verbs act on the Item OBJECT itself -- you get one from a READ (widget:items() on any
    -- container, or hafen.ui():hand()). Deliberately not a number: the server re-uses an item's widget id, so
    -- a verb aimed at a number would move whatever holds it now. The object carries its own item, so a moved
    -- or eaten one errors (item:exists() is false) and nothing is sent. Demo: act on the FIRST inventory item;
    -- default 'take' is the
    -- safest + most visible (it lifts the item onto your cursor -- click an empty slot to put it back).
    local verb = args[2] or "take"
    local invw = hafen.ui():inventory()                -- the backpack's Widget object (029.3; hafen.items is GONE)
    local inv = invw and invw:items() or {}            -- array of Item objects, live while the item is
    local it = inv[1]
    if not it then hafen.log():write(":walker item -> your inventory is empty (put something in it, then retry)"); return end
    hafen.act():item(it, verb)                            -- gated; resolves the item's own widget, sends `verb`
    hafen.log():write((":walker item -> hafen.act():item('%s' [handle %s], '%s')")
      :format(it:name() or it:res() or "?", tostring(it:handle()), verb))
    if verb == "take" then
      hafen.log():write("   (take lifts the item onto your cursor -- left-click an empty inventory slot to put it back)")
    end

  -- 4g: per-subsystem gated verbs. These live in their OWN namespace (hafen.speed/craft/actionbar/kin) — on the
  -- OBJECT itself where the subsystem is OOP (a Slot, a Kin) — not
  -- under hafen.act(), but share the exact same "actions" permission gate (requireActions) as the verbs above.
  elseif sub == "speed" then
    -- speed:current(n): pick a movement speed 0..3, the write half of the one name that reads it. Fully reversible (just set another), so a safe default is fine.
    local n = tonumber(args[2]) or 2                   -- default 2 = run
    local before = hafen.speed():current()
    hafen.speed():current(n)                                 -- gated; drives the client's own Speedget.set
    hafen.log():write((":walker speed -> hafen.speed():current(%d) [%s]  (was %s; max selectable=%s)")
      :format(n, hafen.speed():name(n) or "?", tostring(before), tostring(hafen.speed():max())))

  elseif sub == "craft" then
    -- craft.make([all]): press the OPEN recipe's Craft (or Craft All) button. This CONSUMES ingredients like a
    -- manual craft, so open a recipe you actually want to make first. With none open the verb errors (caught below).
    local all = (args[2] == "all")
    local cur = hafen.craft():current()               -- nil while no recipe is open: the guard still guards
    if not cur then hafen.log():write(":walker craft -> no recipe window open (open one in the crafting menu first)"); return end
    cur:make(all)                                      -- gated; wdgmsg("make", all and 1 or 0)
    hafen.log():write((":walker craft -> cur:make(%s) on '%s'  (%s -- ingredients consumed)")
      :format(tostring(all), cur:name() or "?", all and "Craft All" or "Craft one"))

  elseif sub == "bar" then
    -- slot:use(): activate action-bar slot n (the RAW 0-based index hafen.actionbar():get(n) takes).
    -- Require an explicit n -- there is no safe default (a slot could be food, a curio, an ability...).
    local n = tonumber(args[2])
    if not n then hafen.log():write(":walker bar <n> -> activate action-bar slot n (0-based). Read a slot: :lua hafen.actionbar():get(0):info()"); return end
    local slot = hafen.actionbar():get(n)              -- throws if n is outside 0..143
    local held = (not slot:empty()) and (slot:name() or slot:res() or "?") or "empty"
    slot:use()                                         -- gated; the belt "act" a left-click on the slot sends
    hafen.log():write((":walker bar -> hafen.actionbar():get(%d):use()  [slot holds: %s]"):format(n, held))

  elseif sub == "setbar" then
    -- 022: slot:res(name): ASSIGN an action to slot n by RESOURCE NAME -- the same wdgmsg("setbelt", n, "res",
    -- name) dragging that action off the menu grid sends. Overwrites whatever was there (drag it back, or
    -- right-click the slot to clear), so require BOTH args explicitly. The resource name is the string
    -- slot:res() reads back: put an action on the bar by hand once and read it to learn the name.
    local n, res = tonumber(args[2]), args[3]
    if not n or not res then
      hafen.log():write(":walker setbar <n> <res> -> assign a resource-backed action to slot n (0-based)."
        .. "  e.g. ':walker setbar 5 gfx/hud/act/mine'  (read a name: :lua hafen.actionbar():get(0):res())")
      return
    end
    local slot = hafen.actionbar():get(n)              -- throws if n is outside 0..143
    local was = (not slot:empty()) and (slot:name() or slot:res() or "?") or "empty"
    slot:res(res)                                      -- gated; wdgmsg("setbelt", n, "res", res) -- returns self
    -- The write is ASYNCHRONOUS (the server echoes a "setbelt" back), so the slot still reads the OLD content
    -- right here -- re-read after a beat to show it landed. An unknown res name is silently ignored (no change).
    hafen.log():write((":walker setbar -> hafen.actionbar():get(%d):res('%s')  [slot held: %s -- sent, watch ActionbarChanged]")
      :format(n, res, was))
    hafen.timer():after(0.5, function()
      local now = (not slot:empty()) and (slot:name() or slot:res() or "?") or "empty"
      hafen.log():write((":walker setbar +0.5s -> slot %d now holds: %s%s"):format(n, now,
        (now == was) and "  (unchanged -- is that resource name right?)" or ""))
    end)

  elseif sub == "menugrid" then
    -- 023: pagina:use(): fire an ACTION MENU entry -- exactly what left-clicking that button in the 4x4 grid
    -- does. The key splits by SHAPE: a '/' makes it a resource name ("paginae/act/dig"), anything else a
    -- display name ("Dig"). Display names can contain spaces, so join the rest of the args back together.
    local key = table.concat(args, " ", 2)
    if key == "" then
      hafen.log():write(":walker menugrid <name> -> fire an action-menu entry, e.g. ':walker menugrid Dig'."
        .. "  List them: :lua for _,a in ipairs(hafen.menugrid():list()) do hafen.log():write(a:name() or a:res()) end")
      return
    end
    local pag = hafen.menugrid():get(key)              -- nil when you do not have that action (either key form)
    if not pag then
      hafen.log():write((":walker menugrid -> no menu entry for '%s'  (display names need the resource loaded and are"
        .. " not unique; search with  :lua hafen.menugrid():list('%s'))"):format(key, key))
      return
    end
    local kids = pag:children()
    if kids and #kids > 0 then                         -- a category: :use() would error, so say what it holds
      local names = {}
      for i, c in ipairs(kids) do names[i] = c:name() or c:res() end
      hafen.log():write((":walker menugrid -> '%s' is a CATEGORY (%s), not an action -- it holds: %s")
        :format(key, pag:res(), table.concat(names, ", ")))
      return
    end
    pag:use()                                          -- PagButton.use: the "act"-by-path / "use"-by-id message
    hafen.log():write((":walker menugrid -> hafen.menugrid():get('%s'):use()  [%s -- res %s]")
      :format(key, pag:name() or "?", pag:res()))

  elseif sub == "kin" then
    -- 4g kin verbs, now on the Kin OBJECT (020-kin-oop): hafen.kin() is the roster collection (with the
    -- gated :add(secret)) and :get(name) is one Kin, whose gated verbs are :rename/:group/:endKin/:forget
    -- and each returns SELF, so they chain. 'add' takes a HEARTH SECRET (not a name): ':walker kin add
    -- <secret>'. The rest act on a NAMED kin (exact name) + an explicit op -- these mutate your real roster.
    -- group/rename are reversible. endkin + forget are the TWO STEPS of dropping a kin (the game's "End
    -- kinship" then "Forget"): endkin ENDS THE KINSHIP (the kin stays memorized in your list), then forget
    -- DROPS the memorized kin. Require the args explicitly (like ':walker menu' requires its tokens).
    if args[2] == "add" then
      local secret = args[3]
      if not secret then hafen.log():write(":walker kin add <hearth-secret> -> add a kin by the other player's hearth secret"); return end
      hafen.kin():add(secret)                           -- gated; wdgmsg("bypwd", secret) -- the "Add kin" field
      hafen.log():write((":walker kin -> hafen.kin():add('%s')  (sent -- the server adds them if the secret is valid)"):format(secret))
      return
    end
    local name, op = args[2], args[3]
    if not name or not op then
      hafen.log():write(":walker kin add <secret> | <name> group <0..7> | <name> rename <newname> | <name> endkin | <name> forget")
      return
    end
    local who = hafen.kin():get(name)                  -- read first: confirm the name resolves + show the id
    if not who then hafen.log():write((":walker kin -> no kin named '%s' on your roster"):format(name)); return end
    if op == "group" then
      local grp = tonumber(args[4])
      if not grp then hafen.log():write(":walker kin <name> group <0..7> -> a group number is required"); return end
      -- The server takes 0..254, but the client only DRAWS 8 kin colours -- stay in 0..7 in-game.
      local was = who:group()
      who:group(grp)                                   -- gated; wdgmsg("grp", id, grp) -- their colour (reversible)
      hafen.log():write((":walker kin -> hafen.kin():get('%s') [id %d]:group(%d)  (was group %d, now %d)")
        :format(name, who:id(), grp, was, who:group()))   -- re-read: the SAME object already tracks the change
    elseif op == "rename" then
      local newname = args[4]
      if not newname then hafen.log():write(":walker kin <name> rename <newname> -> a new name is required"); return end
      who:rename(newname)                              -- gated; wdgmsg("nick", id, newname) -- reversible (rename back)
      hafen.log():write((":walker kin -> hafen.kin():get('%s') [id %d]:rename('%s')"):format(name, who:id(), newname))
    elseif op == "endkin" then
      who:endKin()                                     -- gated; Buddy.endkin ("End kinship") -> wdgmsg("rm", id)
      hafen.log():write((":walker kin -> hafen.kin():get('%s') [id %d]:endKin()  (END KINSHIP -- they stay MEMORIZED; ':walker kin %s forget' to drop them)"):format(name, who:id(), name))
    elseif op == "forget" then
      local id = who:id()                              -- read the id BEFORE they leave the roster
      who:forget()                                     -- gated; Buddy.forget ("Forget") -> wdgmsg("rm", id) -- drops a memorized kin
      hafen.log():write((":walker kin -> hafen.kin():get('%s') [id %d]:forget()  (FORGOTTEN -- re-add via hearth secret/right-click)"):format(name, id))
    else
      hafen.log():write((":walker kin -> unknown op '%s'  (group | rename | endkin | forget)"):format(op))
    end

  else
    hafen.log():write((":walker -> unknown sub-command '%s'  (try  :walker help)"):format(sub))
  end
end)
