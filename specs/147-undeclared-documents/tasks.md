# 147 — tasks

Each task ships its suite at `addons/147-undeclared-documents.<X>/`, run with `:t147`. A suite's manifest
declares no document — that is the point — and it clears what it wrote before it asserts, so a rerun is
clean.

- [x] **147.1 — A document exists when `get(name)` first names it.** `Manifest.SavedVar`,
      `savedVariables` and `savedvars` go; `Manifest.load` refuses a manifest carrying `saved_variables`
      with the message `plan.md` gives. `StoreApi`: `owner.store` minted empty; both doors' `get` through
      the `document` helper — the live table if held, else the row read now (or an empty table), set,
      primed into the scope's last-written map; `name` a non-empty string or refused; `loadAccount`,
      `hasScope`, `declaredVar`, `declared`, `index` deleted; `loadChar` refilling in place what `cs.vars`
      holds; `scopeRows`, `uncarriable`, `carriable`, `degraded` over the live keys; the
      `savedVariables.isEmpty()` returns gone; `names` the sorted union of `Db.names(scope)` (new) and the
      live keys. The 15 sibling manifests lose the field, and `ant bin` carries them.
      *Its suite*, declaring nothing: `hafen.store():get("seen")` answers a table, `== ` itself on a second
      call, and `:list()` names `seen`; `seen.n = 1` then `:flush()`, and a run counter — a count from an
      earlier run is `[pass]`, none prints `[manual] :reload and run :t147 again -- expect: [pass] an
      earlier run is counted`; `s:store():get("seen").n` is `nil` after the client-scope write (two
      documents), and `s:store():list()` names only the character's; `get(nil)`, `get(42)`, `get("")` each
      fail naming `name`; `hafen.session():get("nobody"):store()` still fails naming the session.
      `[manual]`: add `"saved_variables": ["x"]` to the suite's manifest and `:reload` -- expect: its panel
      row reads a manifest error naming `hafen.store():get(name)`; remove the line.
      `[manual]`: `:reload` with the sibling addons rebuilt -- expect: no `manifest error` row in the AddOns
      panel, and `:gobcache stats` reports your cache.

- [x] **147.2 — The pages.** `documents.md` rewritten by `DOCUMENTATION.md` §4: the definition (a Lua table
      you name at `get`, saved for you), the two doors as the two scopes, the verb table without
      "declared", `:list()` as what exists, the one paragraph on what an undeclared name costs, and *Where
      a widget sits is saved for you* in place of the retired heading, its three inbound anchors
      (`api/store/README.md`, `event/bus/lifecycle.md`, `ui/native.md`) re-pointed. Then the impact set:
      the hub's five sites and its manifest link, `api/README.md`'s row, `manifest.md`'s row (the field
      appears nowhere), `getting-started.md`'s manifest blocks and the sentence that declares,
      `guides/saved-data.md`'s declaration block and doors, `guides/translating.md`'s comment and
      "declared", `api/position.md`'s comment, `runtime.md:16`'s "account saved variables" → your addon's
      own, read when asked; `api/ui/native.md:222` discharged.
      Both checkers run bare and exit 0; the §11 checks over every page touched, counts reported; the
      change-note grep of §8 read, not counted.
      *Its suite* is `documents.md`'s and the hub's example blocks, pasted as written, one `[pass]` per
      line each prints — a page whose example does not run is the defect this suite exists to catch.
