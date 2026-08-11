# 050-granular-permissions — Plan

## Approach

**One catalogue, three consumers.** A new `Permission` enum in `src/io/brodgar/addon/` holds the 22 entries, each carrying three
things: its **key** (`gob.click`), the **Lua spelling** of the verb it guards (`gob:click`), and a **plain-language line** for the
consent dialog (*"click objects in the world"*). Everything else reads it — the gate builds its refusal from it, the manifest
validates against it, the dialog renders it, the internal owner enumerates it. Three lists that could drift become one that cannot,
which is the whole reason the tier stops being a single string.

**The declaration becomes a matcher.** `Manifest.permissions` (today a raw `List<String>`) parses into a small `PermissionSet`: an
exact key, or a `<prefix>.*` wildcard matched on the key's own dot segments, so `item.*` can never reach `itemx.y`. The bare `"*"`
parses **nowhere** — the `:lua` REPL owner is built from `Permission.values()` instead, so the allow-all shape has no spelling at
all rather than a privileged one. An entry that is neither a known key nor a known prefix **throws at load**, listing the valid
keys: the addon fails to load and the panel shows why, the ordinary path for every other malformed manifest field.

**The gate keeps its shape and loses its string.** It takes a catalogue constant instead of a verb spelling — one argument instead
of two. The 22 call sites change one line each and **stay the first statement of their verb**: D-213's order is not a detail here,
it is what lets a suite prove a grant without acting on the world (an argument refusal, reached, means the gate passed).

**The user-facing half is where the granularity pays.** The consent dialog stops being three fixed
labels and renders one line per declared entry from the catalogue's plain-language column (a
wildcard as its group); the row marker becomes `[protected: N]` with the keys in the row tooltip,
the shape `[net]` already uses for its hosts. Both read the same `PermissionSet`.

**The persisted set stops recording THAT and starts recording WHAT.** Today it holds ids — *this addon has been defaulted once* —
so an already-enabled addon that changes its declaration is left alone. With one permission that was harmless (declared → declared
cannot change what is granted); with 22 keys it is a silent escalation, the same shape as the bulk-enable bypass. So the pref
becomes a per-addon record of the **consented set** and the policy is one line: *declared ⊆ consented* → the user's choice stands;
otherwise → disable and ask again, naming what is new. An addon declaring nothing is never touched, and asking for **less** never
re-prompts. The rest of the model is untouched (D-028): per-addon, default-disabled, enable-with-consent, no global switch, and the
bulk-enable skip widens with the same predicate.

**Then the name**, in one sweep across `src/` and `docs/` by 048.8's method (see Risks), leaving nothing behind: not the gate, the
manifest predicate, the default policy, the consent window's class, nor the pref key — which is why the record above is re-keyed
rather than kept. The one argument for keeping it (not re-defaulting addons the user already chose) is moot: every addon that
declares today changes its declared set in 050.1, so it re-consents regardless.

## Files to create / modify

- `src/io/brodgar/addon/Permission.java` — **new**: the 22 entries (key · Lua spelling · plain line),
  `parse(String)` for a manifest entry, and the wildcard matcher
- `src/io/brodgar/addon/Manifest.java` — `permissions` becomes a validated `PermissionSet`; the
  declaration predicate is re-expressed per key; `internal()` built from `Permission.values()`
- `src/io/brodgar/addon/AddonManager.java` — the gate takes a constant, its refusal built from the
  catalogue; the tier's block comment rewritten
- the 12 gate-carrying files (`ActApi` `CharApi` `FlowerMenuApi` `LuaCraft` `LuaGob` `LuaHand`
  `LuaItem` `LuaKin` `LuaPagina` `LuaSlot` `LuaWidget` `WorldApi`) — one constant per call site
- `src/io/brodgar/addon/AddonRegistry.java` — the panel's declaration flag becomes the declared set;
  the pure default policy re-keyed on the **consented** set, under a pref key carrying neither the
  retired word nor "seen" (`addons/permissions.consented`)
- `src/io/brodgar/addon/ui/` — the consent window renamed and given its enumerating layout;
  `AddonPanel` gets `[protected: N]` + tooltip, and its bulk-enable skip widens
- `src/io/brodgar/addon/Retired.java` — five refusal texts that name the retired tier
- every installed addon declaring the old permission (grep `addons/*/manifest.json`) — migrated to
  its granular keys, and its `description` paragraph with it
- `docs/addons/guides/actions-and-permissions.md` → `guides/permissions.md`, rebuilt around the
  catalogue; `api/conventions.md` (the model, once), `runtime.md` (manifest row + badge row),
  `api/README.md`, `guides/README.md`, `getting-started.md`, `examples.md`
- the 11 pages carrying a protected heading, plus every page linking into their anchors
- the area's decisions file (renamed) + the new decisions; `STATE.md`, `GLOSSARY.md` — at `/end`

## Risks & gotchas

- **The silent bypass, again** (`learnings/actions-gated.md`, 4c): the consent dialog was once
  bypassable through the panel's **bulk enable**, fixed by skipping declaring addons. That skip is
  keyed on the old predicate and must widen with it, or one click grants every key with no prompt.
  Grep every other path reaching the same state change (the console's enable, the panel checkbox).
- **The old policy is keyed on the TRAIT, not on ids** (4b): a read-only addon that *later* declares
  is re-opted-out. Re-keying on the consented set must preserve that case exactly (empty record ⊉ a
  non-empty declaration → disable) and keep the record **additive per addon**, so change detection
  stays a size compare rather than a diff.
- **The sweep has a method** (048.8): word-boundary grep, subtract the non-permission senses **by
  hand** (a `hasSub` gate, a grab field, the game noun), blanket-rename only what is left, re-grep,
  and read every survivor. A blind `sed` renamed eight comments into saying something false last time.
- **Every renamed heading MOVES AN ANCHOR** (048.8), and the checker's slug rule is GitHub's:
  lowercase, delete every character that is not `[a-z0-9 _-]`, spaces → hyphens — so ` — ` leaves
  **two** hyphens (`learnings/testing-tooling.md`, 030.4/033.3/036.4). Python 3.14 is on this box.
  **Plant a bad link and a bad anchor and confirm they are caught**: a checker reporting 0/700 that
  has never been seen to fail proves nothing.
- **The dialog grows with N** — three fixed labels sized by `pack()` today; a dozen entries must
  still fit on screen. Cap the rendered list or group by prefix.
- **`bin/addons/` is what the client actually reads** — a migrated manifest exists there too, and a
  running client holds it open on Windows (D-183 addendum).

## Discarded alternatives

- **An umbrella key beside the granular ones** — two ways to say one thing, and the coarse one wins
  every time. One canonical way.
- **Accept unknown keys silently** (today's behaviour) — a typo grants nothing and fails at the
  first call, precisely the silent downgrade the catalogue exists to make impossible.
- **A `String` constant table instead of an enum** — the 22 call sites lose compile-time checking,
  and the plain-language column has nowhere to live but a parallel map.
- **Consent per verb, at call time** — modal spam mid-gesture, and it moves the grant off the moment
  D-028 chose for it (enabling the addon) onto one the user cannot anticipate.
- **Reserve the bare `"*"` for the internal owner** (the `network` block's shape) — the REPL builds
  from `values()` instead, so the token needs no meaning anywhere.
- **Keep the persisted record as bare ids, re-prompting on any declaration change** — cheaper, but
  it re-asks when an addon drops a permission, which trains the user to click the dialog away.
