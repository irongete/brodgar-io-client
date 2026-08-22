# 093 — The line the user consents to: tasks

Shipped as **one task with one suite**, in the maintainer's own session: eight rows over one decision (D5),
where splitting them would have left a catalogue that was half one vocabulary and half another — which is
the defect itself.

- [x] **093 — The line the user consents to.** The permission tier's own definition is *an action the player
      could have performed*, and four things reached past it with no key. **`widget.send` is reused** for
      `ev:resend()` and `ev:send(t)`, which put a message on the very wire `widget:send` is gated on — and
      `ev:send(t)` carries arbitrary arguments while neither verb is once-only. **`map.marker`** covers the
      four marker writes, because `:remove(m)` permanently deletes a pin no server can restore.
      **`client.settings`** covers every option write — one gate in `OptionsMethod.invoke`, for all six
      subsystem handles — and `binding:key(k)`, which reaches the **client's own** bindings. **`http.get` /
      `http.post`** fold the network into the one catalogue, with the manifest's `network.hosts` block as
      **the argument of the key**: hosts without a key is a load error, a key without hosts is refused at the
      call, and the consent dialog renders them as one line. Plus `ACTIONBAR_RES.line` reworded to name the
      verb it gates, and `kin.endKin` → **`kin.end`**, the one camelCase key in a lower-case dotted
      catalogue. Every **read** stays open. `addons/profiler` declares `client.settings`, being the one tool
      that writes an option.
      *Its suite* **declares no permissions at all**, which is the point: what 093 ships is the gates, so the
      proof is that each newly protected verb refuses and names its key while the read beside it answers
      freely. Every call is made with an argument that **cannot prosper**, so nothing behind a granted gate
      ever runs, and the criterion is *"it raises, naming either the key or the door behind it"* — the same
      verdict whether or not the key is declared, while `<no error>` is never a pass.
      `[manual]`: one — the consent **dialog**, where A-098's host list and A-099's reworded line land and
      which no program can read.
      *Audit*: **A-095** (`audit/ns-event.md` F3 · ROADMAP 055) · **A-096** (`ns-map.md` F2) · **A-097**
      (`ns-client.md` F4) · **A-098** (`ns-http.md` F4) · **A-099** (`ns-actionbar.md` F4) · **A-100**
      (`ns-kin.md` F2) · **A-101** (`ns-player.md` F4) · **A-102** (`ns-http.md` F4).

## Result

`:t093` — **7 pass, 0 fail, 1 manual**. Clean build from an empty `build/classes`. Twelve pages updated.

Eight rows ticked and struck; the open count went **23 → 15**. **Three carry a correction to the audit
itself**, found by recounting against the source:

- **A-101** was **already true**: `guides/permissions.md` §Groups already said a prefix matches on whole dot
  segments *"and it does reach a nested one"*, with `player.hand.use` as the worked example.
- **A-096's evidence listed `marker:name(s)`, which does not exist** — the read `marker:name()` does. The
  four writes are what the finding's own Blast radius counted.
- **A-097 is one gate site, not three**: `KeybindingsOptions` has no `OptionsMethod` and no write; the only
  hotkey write is `binding:key(k)`. Written in 086's spec and confirmed here.

**No row of another feature's block was implemented here.** ROADMAP 055 is cited by A-095 and was discharged
as that row, not as a ROADMAP entry; the maintainer strikes that line by hand.

## Reported at the close, not changed

**The suite's manual asks the maintainer to grant keys to the suite itself** — the only way to see the
consent dialog, since no program can read it and no third folder may be added under `addons/`. It is
harmless now (every call is argument-safe), but it means the one manual is exercised by editing a manifest
that the scored lines otherwise depend on. A shipped, permanently-declaring example addon would be a better
vehicle; there is no place for one under the current `addons/` rule.

**`marker:name(s)` does not exist**, so a marker cannot be renamed at all — while `map.marker`'s consent line
says "add, **rename** and delete pins on your map", which is the audit's own wording taken verbatim. The
line describes a verb the API has not got. Not in `audit/INVENTORY.md`.
