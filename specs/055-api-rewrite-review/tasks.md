# 055-api-rewrite-review — Tasks

<!-- One task = one session. Test protocol `none` (AREA.md): a task's verification material is its own
     report — counts with offenders named, a `src/` citation per changed claim, `wc -l` per page
     touched, the wrap check run AFTER the last edit (`perl -CSD`, `s/\r?\n?$//`), and any engine
     defect FILED to area `addons`, never fixed here. -->

- [x] **055.1 — The census, and the worklist everything else works from.** Build the surface → page map
      for the seven features, **both directions**, as `census.md` here. Forward: every `hafen.*` section
      and verb registered in `src/io/brodgar/addon/` has an owning page within two clicks of
      `api/README.md`, measured as a **traversal**, never read off an index (`001.7`). Backward: no page
      names a section, verb, key, event or class absent from `src/`. Then the half no symbol grep finds
      (`003.1`): grep the **prose names** of what `043`/`044` moved — "ghost", "sprite", "model", "the
      render namespace", "gob overlay" — across the whole tier, pages on no feature's file list
      included, and list every live mis-point. Classify each finding `OK`/`THIN`/`GAP`/`WRONG`/`N/A`
      with its owning task, **055.2 or 055.3**. Fix nothing here.
      **Reports**: the two counts or their offenders · the traversal · the greps · the worklist.
      <!-- extra context: `specs/codebase/addon-engine.md` -->

- [x] **055.2 — Accuracy: the grammar, the one event door, the end of polling** (`039`, `041`, `042`).
      Work `055.1`'s worklist for these three against the **second oracle**: `LuaError` strings and
      `spec.get("…")` reads over the owning files are where the corrections are (`003.1`: nine of
      nine), and `Args.java` enforces arity-as-verb, so every "`f()` answers X, `f(x)` answers Y" row on
      `conventions.md` is checked rather than inferred. `041` is the volume — the `:on()` door,
      `sub:off()`, closed key sets that throw listing their keys, the open `action`/`message` names,
      `ev:preventDefault()` as the one cancel, a handler's return value **never read**. `042` is prose,
      not tables: a page telling the reader a value is up to a frame late, or to poll for a change, is
      wrong. **Reports**: every correction with its citation · the refusal and absence-case rows checked.
      <!-- extra context: `specs/039-uniform-api/`, `041-unified-events/`, `042-event-driven-reads/` -->

- [x] **055.3 — Accuracy: the world you stand things in** (`043`..`046`). Same method, same worklist.
      The risks: `043`'s `:facing(mode)`, and that `"camera"` stopped raising in `044`; `044`'s
      transparency rule stated as what it **is**, never as what became possible; `045`'s hard cut at
      **both** doors — a raw coordinate over never-visited ground refused, an anchor this session cannot
      locate waiting silently and forever — which `045.3` already carried onto three pages it had not
      named, so the check is whether a fourth holds the old model; and `046`'s validation, which
      deliberately **differs** from its `vr` siblings (a direct verb refuses `0`, a negative, a
      non-finite; the siblings clamp) — a page that harmonises them is wrong.
      <!-- extra context: `specs/043-vr-namespace/`, `044-spatial-ui/`, `045-durable-places/`, `046-gob-scale/` -->

- [x] **055.4 — The standard, the sweep, and the close.** **(a) §7 becomes derived** from
      `Retired.java`'s three tables (69 `hafen.*` keys, 54 entity-verb keys, the retired event keys),
      hand-written only where a retirement has **no row** there, an inadmissible spelling refused **in
      writing** with its reason (`:offset(`'s precedent), and the six names §7 today calls *live* that
      the engine refuses corrected. That is **D-245**; falsify every entry both ways — an admitted
      spelling's proof has a shelf life (`004.2`). **(b) The wording sweep** — no obituary, no rename
      note, no correction addressed to a reader of an earlier page; `api/act.md:34`'s "used to" and its
      peers out. Scope §2's `now`/`already`/`still` rule **first** (152 `still` hits are mostly
      `:exists()` rows) so the sweep fixes change-notes and not correct present tense; if that changes
      the rule rather than clarifying it, **D-246**. An obituary turned description leaves the
      blockquote (`003.2`). **(c) `docs/README.md` and the indexes** — "It covers one thing" out, "the
      ten addons" out (§6, and wrong besides); both glance tables and `api/README.md` against the
      census; the IA's tree, reading orders and §5.3 rows. **(d) The close** — the §12 sweep tree-wide,
      the ROADMAP's six over-110 lines and `_template/spec.md`'s tier spelling, `CLAUDE.md:16`
      **reported** as outside the commit paths.
      **Reports**: D-245 (and D-246 if taken) with its reasoning · the derivation re-run, both
      directions, every entry at zero · links and anchors tree-wide, count and zero broken, checker
      falsified both ways · headings · every `hafen.*` name found in `src/` · columns, table rows
      excluded, introduced separated from inherited · `STATE.md` figures **re-derived from the tree**.
      <!-- extra context: `src/io/brodgar/addon/Retired.java`; `learnings/docs-maintenance.md` -->
