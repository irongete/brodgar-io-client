-- Walker — the WRITE-ACTIONS demo addon (dormant, opt-in).
--
-- It DECLARES "permissions": ["actions"] in its manifest, so it is DISABLED BY DEFAULT when first discovered
-- (write-actions are a PER-ADDON permission, opt-in per addon — D-027/D-028). To use it, enable it in
-- Options > AddOns: because it can act on your behalf, the panel asks you to CONFIRM first (the consent dialog,
-- slice 4c). Once you enable it and Reload UI it loads like any addon, and hafen.act.enabled() is true here
-- (it declared the permission). There is NO global switch (D-028): the permission is granted purely by YOUR
-- enabling this one addon. hafen.act is the ONE part of hafen.* that DRIVES the character: it sends
-- player-action wdgmsgs to the server (everything else only observes). It stays server-authoritative — an addon
-- can only send what a player click could send; the permission exists so YOU control which addons act for you.
-- Kept SEPARATE from the always-on read-only `hello` regression harness (which declares no permissions).
--
-- Slice 4d adds the rest of the MapView action verbs on top of moveTo (4a); slice 4e adds menu + flower;
-- slice 4f adds the ITEM verbs (hafen.act.item); slice 4g adds the PER-SUBSYSTEM gated verbs that live in
-- their own namespace (not hafen.act.*): hafen.speed.set, hafen.craft.make, hafen.actionbar.use, and
-- the kin verbs on the Kin object (hafen.kin():add(secret) and kin:rename/:setGroup/:endkin/:forget)
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
--   :walker item [verb] -- item: act on your FIRST inventory item, addressed by its HANDLE (item.handle, from a
--                          read). Default 'take' lifts it to your cursor (safe/reversible: click an empty slot to
--                          undo). Pass a verb: take|drop|transfer|iact|itemact.
--   :walker speed [n]   -- speed.set: select movement speed n=0..3 (crawl/walk/run/sprint; default 2=run). Reversible.
--   :walker craft [all] -- craft.make: press Craft on the OPEN recipe (add 'all' for Craft All). CONSUMES ingredients!
--   :walker bar <n>     -- actionbar.use: activate action-bar slot n (raw 0-based index; read hafen.actionbar.slot first)
--   :walker kin add <secret>  -- hafen.kin():add: add a kin by the other player's HEARTH SECRET (wdgmsg 'bypwd')
--   :walker kin <name> group <0..7>|rename <new>|endkin|forget
--                       -- kin:setGroup/:rename a named kin (reversible), OR the two-step drop: endkin = End
--                          kinship (stays memorized), then forget = drop the memorized kin from the list

hafen.log("walker loaded (v0.6.0) -- write-actions demo (4d MapView verbs + 4e menu/flower + 4f item + 4g speed/craft/bar/kin)")

-- At login, confirm we're granted (we only load once YOU enabled us, and we declared the permission).
hafen.events.on("OnEnterWorld", function()
  hafen.log(("walker: write-actions %s -- run  :walker  for the list of action demos")
    :format(hafen.act.enabled() and "GRANTED" or "NOT granted"))
end)

local SOUTH = 22   -- world units ~ 2 tiles (tilesz = 11) to the south

-- One command with sub-verbs, each exercising one gated hafen.act.* MapView verb.
hafen.slash.register("walker", function(args)
  local sub = args[1] or "help"

  if sub == "help" then
    hafen.log(":walker sub-commands -> walk | click | use | sel | place | raw | menu | flower | item | speed | craft | bar | kin")
    hafen.log("   walk=moveTo  click=clickGob(right)  use=useItemOn  sel=select  place=place  raw=raw escape hatch")
    hafen.log("   menu=hafen.act.menu(path...)  e.g. ':walker menu lo cs' = log out to char select (reversible)")
    hafen.log("   flower=hafen.act.flower(label)  e.g. ':walker flower Harvest' = right-click nearest, pick a petal")
    hafen.log("   item [verb]=hafen.act.item(firstInvItem, verb)  default take (lifts to cursor); take|drop|transfer|iact|itemact")
    hafen.log("   -- 4g per-subsystem gated verbs (own namespace, same permission):")
    hafen.log("   speed [n]=hafen.speed.set(n)  0..3 crawl/walk/run/sprint (default 2=run, reversible)")
    hafen.log("   craft [all]=hafen.craft.make(all)  press Craft on the OPEN recipe (CONSUMES ingredients; 'all'=Craft All)")
    hafen.log("   bar <n>=hafen.actionbar.use(n)  activate action-bar slot n (raw 0-based index)")
    hafen.log("   kin add <secret> =add by hearth secret; kin <name> group/rename =kin:setGroup/:rename; endkin =End kinship, forget =drop memorized kin")
    return
  end

  -- Every verb is gated: bail with a clear hint if this addon somehow isn't granted (it declared the perm,
  -- so this only trips if you edited the manifest). enabled() never throws, so no pcall is needed.
  if not hafen.act.enabled() then
    hafen.log((":walker %s -> write-actions not granted (enable this addon + confirm the consent dialog)."):format(sub))
    return
  end

  local me = hafen.player():gob()                    -- the Gob OBJECT for your character (nil before enter-world)
  local p = me and me:pos()
  if not p then hafen.log(":walker -> no player position yet"); return end

  if sub == "walk" then
    hafen.act.moveTo(p.x, p.y + SOUTH)                 -- the MapView "click" a left-click on that spot sends
    hafen.log((":walker walk -> moveTo(%.1f, %.1f)  [~2 tiles south -- watch your character walk]"):format(p.x, p.y + SOUTH))

  elseif sub == "click" then
    local g = hafen.world.nearest(function(g) return not g:isplayer() end)  -- nearest non-player Gob OBJECT
    if not g then hafen.log(":walker click -> no object nearby"); return end
    hafen.act.clickGob(g, 3)                            -- clickGob takes the Gob itself; 3 = RIGHT-click (safe/cancelable)
    hafen.log((":walker click -> right-clicked %s (id %d) -- its context menu should open"):format(g:name() or "?", g:id()))

  elseif sub == "use" then
    hafen.act.useItemOn(p.x, p.y)                       -- apply the cursor item to the ground under you
    hafen.log(":walker use -> useItemOn at your feet (hold something on your cursor first, else the server ignores it)")

  elseif sub == "sel" then
    hafen.act.select(p.x - 11, p.y - 11, p.x + 11, p.y + 11)   -- ~3x3 tiles centred on you
    hafen.log(":walker sel -> area-selected the ~3x3 tiles around you (visible only with a tile-area tool active)")

  elseif sub == "place" then
    hafen.act.place(p.x, p.y, 0)                        -- angle 0 rad = north; no-op unless something is on your cursor
    hafen.log(":walker place -> place at your feet facing north (start building something first, else nothing happens)")

  elseif sub == "raw" then
    -- The escape hatch: send the walk "click" straight to the MapView, building the wire args by hand.
    -- Server units = world * 1024/11 (posres = 11/1024 world-units per server-unit), floored — exactly what
    -- moveTo does internally. So this walks ~2 tiles south too, proving raw(target, msg, ...) reaches the widget.
    local su = 1024 / 11
    local mc = { x = math.floor(p.x * su), y = math.floor((p.y + SOUTH) * su) }
    hafen.act.raw("mapview", "click", { x = 0, y = 0 }, mc, 1, 0)
    hafen.log((":walker raw -> raw('mapview','click', pc, {x=%d,y=%d}, 1, 0)  [== moveTo ~2 tiles south]"):format(mc.x, mc.y))

  elseif sub == "menu" then
    -- Menu paths are content-defined / localized (not a stable address space, see docs) and some COMMIT
    -- real actions, so YOU supply the tokens. With none given we just show the guaranteed client example.
    if not args[2] then
      hafen.log(":walker menu <token...> -> invoke a menu/pagina action by path. Tokens are content-defined;")
      hafen.log("   the one client-guaranteed example is  :walker menu lo cs  (log out to character select -- reversible).")
      return
    end
    local path = {}
    for i = 2, #args do path[#path + 1] = args[i] end
    hafen.act.menu(table.unpack(path))                 -- table.unpack (Lua 5.2 / LuaJ); the sandbox has no global unpack
    hafen.log((":walker menu -> hafen.act.menu(%s)"):format(table.concat(path, ", ")))

  elseif sub == "flower" then
    -- A flower menu grabs the mouse+keyboard while open, so you can't type a command to pick a petal by hand.
    -- The programmatic route: right-click a target (opens its flower after a server round-trip), then a TIMER
    -- auto-selects the petal by label. Give the label you expect (e.g. 'Harvest' on a bush, 'Pick' on a plant).
    local label = args[2]
    if not label then
      hafen.log(":walker flower <label> -> right-clicks the nearest object, then auto-picks that petal (e.g. ':walker flower Harvest').")
      return
    end
    local g = hafen.world.nearest(function(g) return not g:isplayer() end)
    if not g then hafen.log(":walker flower -> no object nearby"); return end
    hafen.act.clickGob(g, 3)                            -- button 3 = RIGHT-click => opens its flower menu (after a round-trip)
    hafen.log((":walker flower -> right-clicked %s (id %d); auto-picking petal '%s' in 0.5s..."):format(g:name() or "?", g:id(), label))
    hafen.timer.after(0.5, function()
      local ok = hafen.act.flower(label)               -- returns true iff a matching petal was selected
      hafen.log((":walker flower -> hafen.act.flower('%s') => %s"):format(
        label, ok and "chosen" or "no such petal / no menu open (right-click gave a direct action, or retry)"))
    end)

  elseif sub == "item" then
    -- 4f: item verbs act on a LIVE item addressed by its HANDLE (item.handle = the item's server widget id),
    -- which every item snapshot carries -- you get it from a READ (hafen.items.* / model:items()). The verb
    -- re-resolves that handle to the live GItem each call (a stale/used item errors, like a GobRef) and sends
    -- exactly the GItem.wdgmsg a click sends. Demo: act on the FIRST inventory item; default 'take' is the
    -- safest + most visible (it lifts the item onto your cursor -- click an empty slot to put it back).
    local verb = args[2] or "take"
    local inv = hafen.items.inventory()                -- array of Item snapshots, each with a `handle`
    local it = inv[1]
    if not it then hafen.log(":walker item -> your inventory is empty (put something in it, then retry)"); return end
    hafen.act.item(it, verb)                            -- gated; resolves it.handle -> the live GItem, sends `verb`
    hafen.log((":walker item -> hafen.act.item('%s' [handle %s], '%s')")
      :format(it.name or it.res or "?", tostring(it.handle), verb))
    if verb == "take" then
      hafen.log("   (take lifts the item onto your cursor -- left-click an empty inventory slot to put it back)")
    end

  -- 4g: per-subsystem gated verbs. These live in their OWN namespace (hafen.speed/craft/actionbar/kin), not
  -- under hafen.act.*, but share the exact same "actions" permission gate (requireActions) as the verbs above.
  elseif sub == "speed" then
    -- speed.set(n): pick a movement speed 0..3. Fully reversible (just set another), so a safe default is fine.
    local n = tonumber(args[2]) or 2                   -- default 2 = run
    local before = hafen.speed.get()
    hafen.speed.set(n)                                 -- gated; drives the client's own Speedget.set
    hafen.log((":walker speed -> hafen.speed.set(%d) [%s]  (was %s; max selectable=%s)")
      :format(n, hafen.speed.name(n) or "?", tostring(before), tostring(hafen.speed.max())))

  elseif sub == "craft" then
    -- craft.make([all]): press the OPEN recipe's Craft (or Craft All) button. This CONSUMES ingredients like a
    -- manual craft, so open a recipe you actually want to make first. With none open the verb errors (caught below).
    local all = (args[2] == "all")
    local cur = hafen.craft.current()
    if not cur then hafen.log(":walker craft -> no recipe window open (open one in the crafting menu first)"); return end
    hafen.craft.make(all)                              -- gated; wdgmsg("make", all and 1 or 0)
    hafen.log((":walker craft -> hafen.craft.make(%s) on '%s'  (%s -- ingredients consumed)")
      :format(tostring(all), cur.recipe or "?", all and "Craft All" or "Craft one"))

  elseif sub == "bar" then
    -- actionbar.use(n): activate action-bar slot n (the RAW 0-based index, same as hafen.actionbar.slot(n)).
    -- Require an explicit n -- there is no safe default (a slot could be food, a curio, an ability...).
    local n = tonumber(args[2])
    if not n then hafen.log(":walker bar <n> -> activate action-bar slot n (0-based). Read a slot: :lua actionbar.slot(0)"); return end
    local s = hafen.actionbar.slot(n)
    hafen.actionbar.use(n)                             -- gated; the belt "act" a left-click on the slot sends
    hafen.log((":walker bar -> hafen.actionbar.use(%d)  [slot holds: %s]")
      :format(n, s and (s.name or s.res or "?") or "empty"))

  elseif sub == "kin" then
    -- 4g kin verbs, now on the Kin OBJECT (020-kin-oop): hafen.kin() is the roster (with the gated
    -- :add(secret)) and hafen.kin(name) is one Kin, whose gated verbs are :rename/:setGroup/:endkin/:forget
    -- and each returns SELF, so they chain. 'add' takes a HEARTH SECRET (not a name): ':walker kin add
    -- <secret>'. The rest act on a NAMED kin (exact name) + an explicit op -- these mutate your real roster.
    -- group/rename are reversible. endkin + forget are the TWO STEPS of dropping a kin (the game's "End
    -- kinship" then "Forget"): endkin ENDS THE KINSHIP (the kin stays memorized in your list), then forget
    -- DROPS the memorized kin. Require the args explicitly (like ':walker menu' requires its tokens).
    if args[2] == "add" then
      local secret = args[3]
      if not secret then hafen.log(":walker kin add <hearth-secret> -> add a kin by the other player's hearth secret"); return end
      hafen.kin():add(secret)                           -- gated; wdgmsg("bypwd", secret) -- the "Add kin" field
      hafen.log((":walker kin -> hafen.kin():add('%s')  (sent -- the server adds them if the secret is valid)"):format(secret))
      return
    end
    local name, op = args[2], args[3]
    if not name or not op then
      hafen.log(":walker kin add <secret> | <name> group <0..7> | <name> rename <newname> | <name> endkin | <name> forget")
      return
    end
    local who = hafen.kin(name)                        -- read first: confirm the name resolves + show the id
    if not who then hafen.log((":walker kin -> no kin named '%s' on your roster"):format(name)); return end
    if op == "group" then
      local grp = tonumber(args[4])
      if not grp then hafen.log(":walker kin <name> group <0..7> -> a group number is required"); return end
      -- The server takes 0..254, but the client only DRAWS 8 kin colours -- stay in 0..7 in-game.
      local was = who:group()
      who:setGroup(grp)                                -- gated; wdgmsg("grp", id, grp) -- their colour (reversible)
      hafen.log((":walker kin -> hafen.kin('%s' [id %d]):setGroup(%d)  (was group %d, now %d)")
        :format(name, who:id(), grp, was, who:group()))   -- re-read: the SAME object already tracks the change
    elseif op == "rename" then
      local newname = args[4]
      if not newname then hafen.log(":walker kin <name> rename <newname> -> a new name is required"); return end
      who:rename(newname)                              -- gated; wdgmsg("nick", id, newname) -- reversible (rename back)
      hafen.log((":walker kin -> hafen.kin('%s' [id %d]):rename('%s')"):format(name, who:id(), newname))
    elseif op == "endkin" then
      who:endkin()                                     -- gated; Buddy.endkin ("End kinship") -> wdgmsg("rm", id)
      hafen.log((":walker kin -> hafen.kin('%s' [id %d]):endkin()  (END KINSHIP -- they stay MEMORIZED; ':walker kin %s forget' to drop them)"):format(name, who:id(), name))
    elseif op == "forget" then
      local id = who:id()                              -- read the id BEFORE they leave the roster
      who:forget()                                     -- gated; Buddy.forget ("Forget") -> wdgmsg("rm", id) -- drops a memorized kin
      hafen.log((":walker kin -> hafen.kin('%s' [id %d]):forget()  (FORGOTTEN -- re-add via hearth secret/right-click)"):format(name, id))
    else
      hafen.log((":walker kin -> unknown op '%s'  (group | rename | endkin | forget)"):format(op))
    end

  else
    hafen.log((":walker -> unknown sub-command '%s'  (try  :walker help)"):format(sub))
  end
end)
