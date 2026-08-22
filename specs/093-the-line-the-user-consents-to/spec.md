# 093 — The line the user consents to

Discharges: A-095, A-096, A-097, A-098, A-099, A-100, A-101, A-102.

All eight are rows in `audit/INVENTORY.md`'s own 093 block, and its shape was fixed by **D5**: the tier does
what the page says — *an action the player could have performed* — so four keys land and the consent dialog
gains the two sentences a user most wants to read.

## What and why

`conventions.md` justified the tier with one test — **does the effect leave the client** — and four things
sat on the wrong side of it.

**Three of them reach no server at all**, and the rule read them as harmless:

- **`hafen.map():marker():remove(m)` permanently deletes a pin the player placed.** It took real play to
  make and no server can restore it. Meanwhile `s:speed():set(x)` — a movement mode the user changes back
  with one click — *was* keyed. So the dialog said *"change the movement speed of any of your characters"*
  and said nothing about *"delete pins from your map"*.
- **Every option write persists**, `client/README.md`'s own words being "indistinguishable from the same edit
  made in the Options window". The examples `conventions.md` gave for the client-local rule are a marker, an
  icon flag and a sound; halving someone's render scale, muting their audio and rebinding **every hotkey they
  have** is a different order of thing. And `binding:key(k)` reaches the **client's own** bindings, so an
  addon could take the inventory key.
- **`hafen.http` was a second permission mechanism** with none of the first one's vocabulary: no `Permission`
  constant, no `<prefix>.*` group, a consent surface of its own. A user who had learned that a key is
  `<section>.<verb>` wrote `"http.*"` and the addon failed to load; an addon that declared `network` raised
  **no consent dialog at all**, so the user approving it read about kin and items and nothing about the
  network.

**And the fourth reaches the server through a door with no lock**: `ev:resend()` and `ev:send(t)` call
`UI.rawWdgmsg` where `widget:send` on the same wire is keyed. `conventions.md` covered it in half a sentence
— *"Nor does replacing an action the client is already sending"* — which covers neither of the two things
that matter: `ev:send(t)` carries an **arbitrary argument table**, so replacing an `itemact`'s target is a
different action wearing the same name; and neither verb is once-only, so a handler that loops turns one
user click into as many server messages as it likes.

## What shipped

**Four new catalogue entries and one reuse.** `map.marker`, `client.settings`, `http.get`, `http.post` —
and `widget.send`, unchanged, now gating `ev:resend`/`ev:send` too.

| Key | Gates | Where |
|---|---|---|
| `widget.send` | `ev:resend()`, `ev:send(t)` | `LuaEvent.outbound`, before the receiver is resolved |
| `map.marker` | `:add`, `:remove`, `marker:color(c)`, `:onMap(b)` | `MapApi`'s Source, `LuaMarker`'s two write arities |
| `client.settings` | every option **write**, and `binding:key(k)`/`key(nil)` | `OptionsMethod.invoke`'s write branch — **one place** — plus `LuaBinding` |
| `http.get` / `http.post` | `hafen.http():get`/`:post` | `HttpApi.requireNetwork`, the key before the allowlist |

**The network's allowlist became the key's argument** — the shape `player.hand.use` already had for a nested
one. The key says *whether*, the hosts say *where*, and `PermissionSet.describe(entry, hosts)` renders them
as one dialog line: *"fetch data from the servers it lists: api.example.com"*. A manifest with hosts and no
key is a **load error** naming the key; a key with no hosts is refused at the call.

**Every read stays open.** Reading a marker, a setting or a binding needs nothing, which is what makes a
settings-aware addon that only adapts to what it finds declare no key at all.

**`ACTIONBAR_RES.line`** now reads *"assign one of the game's own actions to…"*, naming the verb it gates
rather than the unprotected `slot:hold(pag)` beside it. **`kin.endKin` became `kin.end`**: the verb cannot
change (`end` is a Lua keyword) but the key never had to follow it letter for letter, and it was the one
camelCase string in a catalogue of lower-case dotted ones.

## Three corrections to the audit's own text

**A-101 was already true.** `guides/permissions.md` §Groups already said the prefix matches on whole dot
segments *"and it does reach a nested one"*, with `player.hand.use` as the worked example and `player.*`
covering it. The finding claimed the fact lived only in `Permission`'s javadoc.

**A-096's evidence listed a write that does not exist.** `marker:name(s)` is read-only — `m.set("name", new
OneArgFunction())`. The four writes are `:add`, `:remove`, `:color(c)`, `:onMap(b)`, which is what the
finding's own Blast radius said ("four gate calls").

**A-097 is one gate site, not three.** Its Blast radius says "one gate in `OptionsMethod` plus two in
`KeybindingsOptions`". `KeybindingsOptions` has no `OptionsMethod` and no write of its own; after 086 the
only hotkey write is `binding:key(k)`. Written in 086's own spec, and confirmed here by reading.

## One tool needed fixing

`addons/profiler` writes `client:profiling(b)` — an option write, persisted, and one of the six subsystem
handles A-097 names. It declares `"permissions": ["client.settings"]` now. The other four tools only
**declare** hotkeys with `keybindings():on(name, fn)`, which stays open.

## Verified

`:t093` — **7 pass, 0 fail, 1 manual**. Clean `ant hafen-client` from an empty `build/classes`. Twelve pages
updated; `conventions.md` was compacted back to its 300-line ceiling in the same task. The open count went
**23 → 15**.
