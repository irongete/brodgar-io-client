# 087 — One way to undo it: tasks

Three tasks, four rows, all from `audit/INVENTORY.md`'s own 087 block. **The order matters**: 1
renames, 2 fixes the returns *including on what 1 renamed*, 3 writes the rule that describes the
result. A page that describes a vocabulary the bridge does not have yet is false at the commit that
lands it.

Every suite keeps to **≤ 15 output lines**, so group: one verdict line per claim, scored
(`7/7 reached`) rather than one line per verb.

- [x] **087.1 — Three endings take the vocabulary's own word.** `rule:remove()` becomes
      **`rule:release()`** — a per-widget rule is a **layer you took**, not a member of a collection,
      and `:remove(x)` is what a collection does to a member. `sheet:drop()` becomes
      **`sheet:release()`** — the same act on the sheet that rule belongs to, and `drop` reads as
      discarding data. `asset:dispose()` becomes **`hafen.asset():remove(a)`** — the asset collection
      exists and owns its members, which is **D2**'s rule. The asset move is structural rather than a
      rename: the ending leaves the member's methods table (the one `addAssetVerbs` contributes to,
      built in 086.5) and becomes the collection's `Source.destroyable()` plus `removeMember(x)`,
      which runs the existing `Disposer`. **The view metatable gains nothing**: `AssetApi.imageFor`
      and `FontApi.handleFor` carried no ending before, because freeing a file is the owner's to do —
      so `removeMember` must **refuse an asset this addon does not own**, since the guarantee was
      structural and is now a refusal that needs a message. `MapImages`' handle is reached through
      `grid:image(n)` rather than through `hafen.asset()` and keeps its own ending; a font **variant**
      carries no ending at all, as `font.md` already says. Three `Retired` rows.
      *Its suite* asserts each new spelling ends what the old one ended, and the old one raises — the
      whole of a rename: `w:rule():release()` drops the layer and `w:style()` reads back the client's
      own again, `hafen.ui():sheet():release()` drops the sheet, and
      `hafen.asset():remove(hafen.asset():get("dot.png"))` frees the image, with
      `hafen.asset():count()` falling by one. Then `rule:remove()`, `sheet:drop()` and
      `img:dispose()` each **raise** naming their own replacement, not another's. Then the guarantee
      the move turned into a refusal: an image reached as a **view** — through `w:style().bg.image`
      off a rule another sheet wrote — must be refused by `hafen.asset():remove(…)` naming the owner.
      Its refusal: `hafen.asset():remove("dot.png")` handed a **path** rather than a handle must raise
      naming the handle, because `references.md`'s rule is that you pass the handle.
      `[manual]`: none.
      *Audit*: **A-047** — *"`rule:remove()` → `rule:release()`; `sheet:drop()` → `sheet:release()`;
      `asset:dispose()` → `hafen.asset():remove(a)`"* (`audit/03-lifecycle.md`, §The teardown family
      and §The proposal). **Read that page before starting** — it carries the seventeen sites, the
      three real distinctions the eleven spellings fail to track, and why each of these three moves.
      `/end` ticks and strikes this id in `audit/INVENTORY.md`, and nothing else in that file is
      touched.
      <!-- extra context: src/io/brodgar/addon/AssetApi.java (addAssetVerbs, Disposer, imageFor and its javadoc on why a view carries no dispose, teardownAssets), MapImages.java (its own disposal), docs/addons/api/references.md (you pass the handle, never a path) -->

- [x] **087.2 — Every ending returns the receiver.** `conventions.md` teaches that a write hands the
      object back so writes chain, and the family where a user chains most is the one where the rule
      is a coin flip: **eight endings return `nil` and eight return the receiver**. Seven of the eight
      are fixed here — `sub:off()` (`LuaSub`), `timer:cancel()` (`AddonManager`'s timer methods),
      `w:destroy()` (`LuaWidget`), `rule:release()` (`LuaRule`, after 087.1), `grab:release()`
      (`LuaGrab`), `req:cancel()` (`HttpApi`) and `s:flowermenu():cancel()` (`FlowerMenuApi`).
      **`ov:destroy()` is deliberately left alone**: A-048 says *"Skip `ov:destroy` here — A-120
      replaces it with `:remove(key)` one feature later"*. The eight that already chain are read, not
      written — `toggle:release`, `sheet:release`, `sound:stop`, `s:close`, `coll:remove` (returns
      `me`), `scope:finish` (returns `a.arg1()`, the model to copy), `item:drop` and the asset ending.
      **The row's own enumeration predates 086** and names watch/slash `:remove`, which 086.1 turned
      into `sub:off()`; the seven above are the recount against the code as it stands.
      *Its suite* asserts the chain, which is the claim and the only thing that proves it: each of the
      seven is called and its result compared by `==` to the receiver it was called on — one scored
      line over the seven, reaching what it can within a bounded `hafen.timer()` window (the flower
      menu and the HTTP request need a state). Then that the ending still **ended**: after
      `sub:off()`, `hafen.event():count()` has fallen; after `timer:cancel()`, `t:alive()` is false;
      after `w:destroy()`, `w:exists()` is false — a verb that returns the receiver and does nothing
      would pass the first check alone. Then idempotence, which the pages promise: a second
      `sub:off()` and a second `timer:cancel()` each answer the receiver again and raise nothing. Its
      refusal: `ov:destroy()` must still answer **`nil`**, so the sweep stopped where A-048 says.
      `[manual]`: one. Open the radial menu on something and report whether
      `s:flowermenu():cancel()` dismisses it and what it prints — the menu is the server's and only
      you can put one up.
      *Audit*: **A-048** — *"every ending returns the receiver — nine return `nil` today (`sub:off`,
      `timer:cancel`, `w:destroy`, `rule:remove`, `grab:release`, `ov:destroy`, `req:cancel`,
      `flowermenu:cancel`, watch/slash `:remove`). Skip `ov:destroy` here — A-120 replaces it with
      `:remove(key)` one feature later"* (`audit/ns-timer.md` F4, `audit/ns-http.md` F6,
      `audit/ns-flowermenu.md` F4, `audit/ns-craft.md` F4, `audit/ns-vr.md` F2).
      **Read those `audit/` pages before starting.** `/end` ticks and strikes this id.
      <!-- extra context: src/io/brodgar/addon/ProfScope.java (finish returns a.arg1() — the model), and note that AddonManager's timer cancel and LuaGrab's release are ZeroArgFunctions, which cannot see their receiver and must be widened -->

- [x] **087.3 — A collection destroys its member, and the rule is written down.** `hafen.session()`
      is already a `LuaCollection` — mounted in `SessionApi` with `current` as its only extra verb —
      and `LuaCollection` already carries the whole mechanism: `if(coll.src.destroyable())` installs a
      `:remove(keyOrMember)` that calls `src.removeMember(…)` and **returns the collection**, so
      removals chain. The session `Source` declares `destroyable()` true and implements
      `removeMember(x)` as `Sessions.byuser(user).drop()` behind **`Permission.SESSION_CLOSE`** — the
      same key, the same act — with the gate **first**, as D-213 requires and 084.5 checked at all 24
      sites. It gives the same refusal `s:close()` gives when the client holds no session for that
      account, naming `s:exists()` as the test, or the two doors answer differently for one mistake.
      **`s:close()` stays beside it**: A-049 says *beside*, and ending a login is its own act. Then
      `conventions.md` gains the section this whole feature exists for: the **seven** teardown verbs
      (`off` · `remove` · `release` · `destroy` · `cancel` · `stop` · `finish`), which kind of
      receiver each belongs to, that **every ending returns the receiver**, that **where a collection
      exists the destroy verb is on it**, and that three acts are **not** endings — `item:drop(n)` (a
      protected game action), `s:close()` (ending a login) and `w:revert()` (an undo of your own
      layer, like `w:replace(nil)` and `w:size(nil)`). **Do not name `slot:hold(nil)`** — that verb is
      089's rename of `slot:pagina()`, and naming it here would put a verb the bridge does not have on
      a page; 089.4 adds it when it creates it.
      *Its suite* declares `session.close` and asserts the new door is the collection's, which is
      D2's rule made real: `hafen.session():remove(s)` on a second login returns **the collection**
      (`== hafen.session()`), and that login's `s:exists()` is then false. Then the gate: without the
      permission the same call raises naming **`session.close`**, and the refusal fires **before**
      anything is dropped — the check that proves the ordering. Then that the two doors agree:
      `hafen.session():remove(hafen.session():get("nobodyhere"))` raises the same refusal
      `s:close()` gives, naming `s:exists()`. Then the rule is a rule: a sample of endings the page
      now names — `sound:stop()`, `toggle:release()`, `coll:remove(m)` — each answers its receiver, so
      the section describes the bridge rather than an intention. Its refusal:
      `hafen.session():remove("alice")` handed a **string** must raise naming a Session, since the
      collection's members are objects and `keyOrMember` is what the verb takes.
      `[manual]`: two. Log a second character in, run the suite, and report whether that client window
      closes. Then read the consent dialog for the suite's addon and report the line beside
      `session.close` — the string is user-visible and only you can see the dialog.
      Before handing over, run `DOCUMENTATION.md` §11 over every page touched and report the counts —
      **`conventions.md` is at 291 lines** and this rule may push it over 300, in which case the split
      is this task's by §11.2. Write the rule as a seven-row table plus three sentences and link
      `references.md` rather than re-listing every site.
      *Audit*: **A-046** — *"eleven teardown spellings → seven, with the rule on `conventions.md`
      (`off` · `remove` · `release` · `destroy` · `cancel` · `stop` · `finish`)"*
      (`audit/03-lifecycle.md`) · **A-049** — *"`hafen.session():remove(s)` beside `s:close()`, and the
      rule on `conventions.md`: where a collection exists, the destroy verb is on it. `timer:cancel()`
      and `sound:stop()` are unaffected — aborting and silencing are not destroying"*
      (`audit/ns-session.md` F1, `audit/ns-kin.md` F3 · **D2**).
      **Read those `audit/` pages before starting.** Note that `03-lifecycle.md` proposes a **four**-verb
      vocabulary and A-046 records **seven**; the row is what ships, and `spec.md` §Where the row and
      the page differ says why. `/end` ticks and strikes these two ids.
      **This task also carries the feature's sweep** (AC6, which no single task owns otherwise):
      confirm that a `:reload` after building a rule, a sheet and an asset still drops all three, and
      grep `addons/` for `:dispose()`, `sheet:drop` and `rule:remove` — report both counts.
      <!-- extra context: src/io/brodgar/addon/LuaCollection.java (Source.destroyable, removeMember, and the :remove closure that returns me), SessionApi.java (the mount and its Source), src/io/brodgar/session/Sessions.java (byuser, Member.drop), LuaSession.java (close — the refusal to match) -->

## When the feature closes

`/end` runs per task and ticks that task's own rows:

| Rows | Ticked by |
|---|---|
| A-047 | 087.1 |
| A-048 | 087.2 |
| A-046, A-049 | 087.3 |

Nothing else in the file is touched. An id never moves. **No row of another feature's block is
implemented here**, and none of these is implemented elsewhere — **088** owns `ov:destroy()` becoming
`:remove(key)` (A-120) and `rule:close(…)` becoming `:closeButton(…)` (A-053), and **094** owns
`hafen.ui()` becoming a collection (A-113), which is what would move `w:destroy()`.

With 086 closed the open count stands at **74**. When the last task closes,
`grep -c '^| ☐' audit/INVENTORY.md` must print **70**, and

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

must no longer name A-046, A-047, A-048 or A-049.

**No `specs/ROADMAP.md` line is covered by this scope**, and one goes stale in its wording: *"a font
asset is loaded from the `File` itself … and `:dispose()` does not release it"* (filed 065) describes
an engine limitation that survives this feature, but the verb it names becomes
`hafen.asset():remove(a)`. Re-spelling that line is the maintainer's.

**One cross-feature note for whoever plans second:** 087.3 and **089.5** both add a rule to
`conventions.md`, which is at 291 lines. Whichever runs second inherits the §11.2 split.
