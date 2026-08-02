# Decision Log (ADR-lite) — index

> The decisions (D-001..D-069) live in `decisions/`, split by category — the same shape
> as `learnings/`. **Never read the whole set**: each line below says which D-numbers a file
> holds; open only the entry you need (every `### D-xxx — <title>` header doubles as its
> one-liner — `grep -h "^### D-" decisions/*.md` lists them all). **Adding a decision:**
> append the full entry to its `decisions/<category>.md` file; add a line here only when a
> new category file is created.

- [filesystem-build](decisions/filesystem-build.md) — addon/savedata dirs, jars via ant, JSON formats, saved-data scopes (D-001, D-002, D-003, D-014, D-015, D-016, D-023)
- [architecture-api](decisions/architecture-api.md) — engine layout, invasiveness (D-011), one canonical way, hafen.* namespacing, refs/handles, addon hotkeys unbound by default (D-047), package layout: addon system vs client features (D-048), profiling is one switch (D-049), profiling reads are snapshot tables / absent ≠ zero (D-050), profiling counters are pull-only (D-051), per-addon cost is the watchdog's own measurement split by category (D-052), per-widget cost is a self-clearing field on Widget (D-053), named render passes nest under the client's own draw part and report self time (D-054), profiling's own cost: control frames + per-tier model (D-055), arity is the verb — `hafen.x()` collection vs `hafen.x(key)` entity (D-056), a fixed-index collection iterates 1-based and the entity carries its own key (D-057), verify a subsystem has content before designing an API over it — there is no `hafen.music` (D-058), playback parameters are call arguments, not entity state (D-059), `:exists()` belongs to entities with a lifetime, not to name-keyed handles (D-060), the API's vocabulary comes from the engine, not from the genre (D-061), performance goes behind the existing surface as a cache, not in front of it as a handle (D-062), an entity's key is what the engine publishes about it, not what the genre calls it (D-063), an intern cache's key strength follows the population it keys (D-064), per-entity state a weak intern cache would lose must be derived, not stored (D-065), a thing that lives inside another is a relation on it, not a section of its own (D-066) (D-008, D-011, D-012, D-013, D-020, D-022, D-047, D-048, D-049, D-050, D-051, D-052, D-053, D-054, D-055, D-056, D-057, D-058, D-059, D-060, D-061, D-062, D-063, D-064, D-065, D-066)
- [lifecycle](decisions/lifecycle.md) — in-game management, Reload UI, WoW enable/disable, load order (D-004, D-005, D-006, D-019)
- [security-sandbox](decisions/security-sandbox.md) — strict sandbox, two-layer watchdog (D-017, D-018)
- [widgets-ui](decisions/widgets-ui.md) — wrap-not-reimplement, hook priority, replacement descriptor, drops/g:resource/mods, WidgetNode + hit-testing, a classifier answers only where the widget IS the thing (D-067), a discovery primitive answers for what is already there (D-068), an addon's hide is authoritative — hiding a native window takes its toggle (D-069) (D-009, D-021, D-024, D-038, D-039, D-040, D-041, D-042, D-067, D-068, D-069)
- [actions-permissions](decisions/actions-permissions.md) — the actions tier and its per-addon permission (D-027 -> D-028) (D-010, D-025, D-027, D-028)
- [virtual-entities](decisions/virtual-entities.md) — client-only ghosts, handles, clickability, gizmo, placement snapping (D-029, D-030, D-031, D-032, D-033)
- [rendering](decisions/rendering.md) — hafen.render namespace, glTF 2.0 static subset (D-034, D-035)
- [network-data](decisions/network-data.md) — hafen.json, hafen.http + network allowlist (D-036, D-037)
- [fonts](decisions/fonts.md) — per-addon font system: private handles + owned overrides (D-043)
- [process](decisions/process.md) — spec language, gap build order (D-007, D-026)
