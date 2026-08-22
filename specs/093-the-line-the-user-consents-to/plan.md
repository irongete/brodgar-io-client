# 093 — The line the user consents to: plan

## Approach

Eight rows, one unit, one suite. Seven needed work; the eighth was recounted against the page and found
already written.

**The catalogue is the contract, so five of the eight are one file.** `Permission.java` carries the key, the
Lua spelling and the consent line together precisely so the three lists cannot drift — which is why adding
`map.marker`, `client.settings`, `http.get` and `http.post`, rewording `ACTIONBAR_RES.line` and renaming
`kin.endKin` are all edits to one enum and nothing else has a list to update.

**The gate runs first, except where arity decides there is a gate at all.** D-213 puts the permission check
as the first statement of the verb it guards. Three of these verbs are read/write pairs — `marker:color`,
`marker:onMap`, `binding:key`, and every `OptionsMethod` — where *which arity was called* is what says
whether a write exists to gate. There the gate runs the instant that is known and before anything is
resolved, which is as first as it can be.

## Gotchas found while doing it

**`OptionsMethod` did not know its addon**, and 19 constructions across five files passed only `(handle,
verb)`. Threading the owner through five `create()` signatures is the cost of A-097's "one place", and it is
worth it: one gate covers six subsystem handles and cannot be forgotten when a seventh option is added.

**`LuaBinding.Cache(Addon owner)` took the owner and dropped it on the floor** — the same empty constructor
091 found in `LuaFood.Cache`. Worth grepping for: `Cache(Addon owner) {` with nothing in the body.

**`addons/profiler` writes an option.** The four permanent tools use `keybindings():on(name, fn)`, which
declares a hotkey and stays open — but the profiler also flips `client:profiling(b)`, which persists. It
declares `client.settings` now. Nothing else under `addons/` was reached.

**`conventions.md` went over its 300-line ceiling on the first draft** of A-102's paragraph. Compacted back
to exactly 300 in the same task, which is what the rule asks of writing that crosses the line.

**The first suite deleted one of the maintainer's map pins.** Its `map.marker` checks called `:add(name, p)`
and `:remove(list()[1])` with arguments that would *succeed*, on the assumption that the key would never be
granted — and the suite's own manual asks for it to be granted, to see the consent dialog. The corrected
checks pass an argument that cannot prosper (`:add(name, nil)`, `:remove("093")`, `m:color("x")`), so nothing
behind a granted gate ever runs, and the criterion became **"it raises, naming either the key or the door
behind it"** — the same verdict in both states, while `<no error>` is still a failure. A suite that asks the
maintainer to do something that makes it destructive is a defect in the suite.

## Discarded alternatives

- **Stating the split as a rule instead of folding the network in** — A-098's second option, and the finding
  itself argues against it: *"the consent dialog is the one place a user actually decides, and today the
  network declaration does not appear there in the same words as everything else."*
- **Keeping the `network` block as the whole declaration.** The allowlist is right and `specs/013`'s reason
  for it is untouched — the finding is about which *catalogue* the key lives in. So the block stays and
  becomes the key's argument rather than its replacement.
- **Renaming `kin:endKin()` to `kin:unkin()` or `kin:sever()`**, A-100's other branch. It frees the key and
  the verb both, and costs a `Retired` row and a page — where renaming the key alone costs one string. The
  row's own first sentence is that the key need not follow the verb letter for letter.
- **Stating the marker rule more precisely on `conventions.md` instead of adding `map.marker`** — A-096's
  alternative. The finding picks the key, and the reason is the tier's own definition: deleting a pin is
  plainly an action the player could have performed.
- **A `map.*` group with one member.** It parses, like `gob.*` and `craft.*`, and needs no entry: "any key's
  prefix is a legal group" already covers it.
- **Gating `keybindings:on(name, fn)`.** Declaring an addon's own hotkey starts it **unbound** and reaches
  nothing of the user's; the remap is the write, and it is the one that is keyed.
- **Leaving `client:profiling(b)` outside `client.settings`**, as a debug switch rather than a setting. It
  persists — `ClientOptions`' own javadoc says the checkbox, `:profile on` and this option "always agree" —
  and A-097 covers "the write half of all six subsystem handles". One option outside the key would be a
  second rule to remember.
