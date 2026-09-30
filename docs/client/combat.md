# The fight: Fightview, Fightsess and GiveButton

> What the client holds about a fight in progress: the relation to every opponent and its numbers, the buffs
> drawn beside you and beside each opponent, the row of combat actions and their cooldowns, and the messages
> that switch targets, pursue, give and use an action. The deck BUILDER is another widget, in the character
> sheet: [character-sheet.md](character-sheet.md).

## Where it lives

| What | Where |
|---|---|
| The combat view | `Fightview`, `@RName("frv")`, placed by `GameUI.addchild` `place == "fight"` into `urpanel` and held in `GameUI.fv`. ⚠️ **`GameUI.fv` is never cleared**: `GameUI.cdestroy` does not know the field, so a view that has left the tree is still referenced. Ask `fv.hasparent(ui.root)` before trusting it |
| The action row | `Fightsess`, `@RName("fsess")`, placed by `GameUI.addchild` `place == "fsess"` as a **direct child of the `GameUI`**, with no field: `gameui.getchild(Fightsess.class)` finds it. It sizes itself to its parent and draws over the map, centred on the player. The server puts one up for a fight and destroys it after |
| The relations | `Fightview.lsrel`, a `LinkedList<Relation>`. `Relation` is an inner class and **not a widget**: `gobid`, `gst`, `ip`, `oip`, `lastact`, `lastuse`, `invalid`, and two `Bufflist`s, `buffs` and `relbuffs`, whose field initialisers `add` them to the `Fightview` itself |
| The target | `Fightview.current`, set by `setcur` off `uimsg "cur"`. `null` out of a fight, and `null` after a `cur` naming an id `getrel` does not know |
| Your side | `Fightview.buffs` (your buffs in the fight), `Fightview.lastact`/`lastuse` (your last manoeuvre), `Fightview.atkcs`/`atkct` (the global cooldown) |
| Their side | per `Relation`: `buffs` (drawn beside them), `oip` (their IP), `lastact`/`lastuse` (their last manoeuvre) |
| The action slots | `Fightsess.actions`, a public `Action[]` sized by the server's `nact`. `Action{res, cs, ct}`: the manoeuvre and its cooldown's start and end in `Utils.rtime()` seconds |
| The give button | `GiveButton`, `@RName("give")`, with a 2-bit `state`. The relation boxes (`Relbox`, `Mainrel`) copy `rel.gst` into it on every draw |
| The keys | `Fightsess.kb_acts`: ten `KeyBinding`s `fgt/0`..`fgt/9`, labelled "Combat action 1".."Combat action 10" in the options (defaults `1`..`5` and Shift+`1`..`5`). `kb_relcycle`, `fgt-cycle`, is "Switch targets" (Ctrl+Tab; Shift walks the other way) |

## The messages

| Message | Widget | Direction | Arguments | Effect |
|---|---|---|---|---|
| `new` | `Fightview` | in | gob, gst, ip, oip | a new `Relation` goes to the **front** of `lsrel` |
| `del` | `Fightview` | in | gob | `Relation.remove()` destroys its two lists and sets `invalid`; out of `lsrel`, and the target is cleared if it was this one |
| `upd` | `Fightview` | in | gob, gst, ip, oip | `Relation.give(gst)`, `ip`, `oip` |
| `used` | `Fightview` | in | res or null | `Fightview.use`: your `lastact`, and `lastuse = Utils.rtime()` |
| `ruse` | `Fightview` | in | gob, res or null | that relation's `lastact` and `lastuse` |
| `cur` | `Fightview` | in | gob | moves that relation to the front of `lsrel` and `setcur`s it |
| `atkc` | `Fightview` | in | ticks | `atkcs = now`, `atkct = now + ticks * 0.06` |
| `blk`, `atk` | `Fightview` | in | res / res, res | stored in `blk`, `batk`, `iatk`, which nothing in the client reads |
| `act` | `Fightsess` | in | n, res? | `actions[n] = new Action(res)`, or `null` without a res |
| `acool` | `Fightsess` | in | n, ticks | `actions[n].cs = now`, `ct = now + ticks * 0.06` |
| `use` | `Fightsess` | in | n, nb? | the two highlighted frames, `use` and `useb`; nothing in the client says what either means |
| `used` | `Fightsess` | in | — | ignored there. The same name reaches `Fightview`, so a tap on the name alone must test the widget's class |
| `click` | `Fightview` | out | gob, button | a relation box's portrait (`Avaview`) |
| `give` | `Fightview` | out | gob, button | a relation box's `GiveButton`; `mousedown` sends the mouse button |
| `prs` | `Fightview` | out | gob | a relation box's Pursue button |
| `bump` | `Fightview` | out | gob | "Switch targets": `Fightsess.globtype` rotates `lsrel` locally, then bumps the new front |
| `use` | `Fightsess` | out | n, 1, modflags, [place] | a combat key. `place` is the map coordinate under the pointer, floored to `OCache.posres`, and only when the pick hits ground |
| `rel` | `Fightsess` | out | n | the key's release, posted through a `Release` fenced on the render |

## The buffs of a fight

| What | Where |
|---|---|
| Routing | `Fightview.addchild`: `("buff", null)` goes to `Fightview.buffs`, `("buff", gob)` to that relation's `buffs`, `("relbuff", gob)` to its `relbuffs`. Each is a plain `add`, so a buff is a direct child of its list |
| Painting | `Fightsess.draw` alone paints them: `fv.buffs` to the left of the player, `fv.current.buffs` to the right. Another relation's buffs are painted only while it is the target, and `relbuffs` are painted by nothing. The lists themselves are hidden widgets, so a buff's own `Widget.c` does not say where its icon is |
| ⚠️ A relation's end | `Relation.remove()` calls `buffs.destroy()` and `relbuffs.destroy()`. `Widget.destroy` runs `remove()` on the LIST only and `rdispose()` on the buffs, so every buff stays a child of a detached list, never runs `remove()`, and is not `dest`: `buff.hasparent(ui.root)` is the only test that goes false |
| ⚠️ The view outlives the fight | `Fightsess`, the only painter, is destroyed with the fight, while the `Fightview` stays in the tree with the buffs of yours the server has not yet expired: a debuff can sit in `Fightview.buffs` after the last `del`, drawn by nothing. `fv.buffs` is what is drawn only while a `Fightsess` stands under the `GameUI` |
| ⚠️ `children(Class)` recurses | On the `Fightview` it reaches every relation's buffs. Read the lists one by one |

## Threading and gotchas

| What | Detail |
|---|---|
| The monitor | The `uimsg`s are applied on a loader thread under `synchronized(ui)`: `lsrel`, `current`, a relation's ints and `Fightsess.actions` are written there. "Switch targets" reorders `lsrel` on the UI thread. Read all of it under the tree's monitor |
| ⚠️ `getrel` throws | `Notfound` for an id not in `lsrel`, so a `del`, `upd` or `ruse` for an unknown gob aborts that message |
| ⚠️ Whose number is whose | `ip` is **yours** and `oip` theirs (`Fightsess` paints `IP: n` left and right), while `Relation.lastact` is **theirs**: the field names mix the two sides |
| ⚠️ The give bits | `GiveButton.draw` paints `state & 1` as the left half (`ol` open, `sl` shut) and `state & 2` as the right half (`or`/`sr`), tinted red at 0, blue at 1, green at 2. The left half is the side the fight view paints as yours |
| ⚠️ A key off the map | `Fightsess.globtype` sends no `use` while the pointer is outside the map view but still records the key as held, so a lone `rel` goes out on key-up |
| ⚠️ `acool` on an empty slot | throws a `NullPointerException` inside `Fightsess.uimsg`; the server never sends one |
| Ticks | `atkc` and `acool` count 0.06 s ticks |
