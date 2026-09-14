# DOCUMENTATION — how a page under `docs/` is written

> Read it **before** writing a page, not after. **§1–§11 are the standard for `docs/addons/**`**,
> the reader-facing contract: what a page looks like, where it lives, and what a task checks before
> it hands over. **§12 is the standard for `docs/client/**`**, the engine map — a different
> audience, and a much shorter set of rules.

## 1. Scope and audience

`docs/addons/` is the **user-facing tier**: someone who wants to write an addon and has never read `src/`.
It is a developer-first practical reference, not a specification, not a changelog, and not a design essay. The reader wants three things, in this order:
1. Get an addon running quickly.
2. Learn the specific task in front of them.
3. Look up exact API signatures, types, permissions, and return values without wading through narrative prose.

**`docs/` is also the contract `/plan` and `/implement` read.** It has to be accurate, because nothing else states what the API is.

## 2. Where a page lives

1. **Three tiers, and a page belongs to exactly one.**
   - Tutorial (`getting-started.md`, one linear path).
   - Guides (`guides/`, one *task* per page).
   - API Reference (`api/`, one *namespace* per page).
   A guide never restates a full signature table; a reference page never teaches an end-to-end workflow.
2. **One namespace, one path**, named exactly as it is spelled in Lua: `hafen.buff` → `api/buff.md`.
3. **Over the ceiling, a namespace becomes a directory** `api/<namespace>/` whose `README.md` is its hub; the recursion repeats inside (`api/ui/style/`). At the top level of `api/`, **a directory means a namespace** — a page named for a *type* (`gob.md`, `overlay.md`) splits into a **sibling type page**, never a directory.
4. **A hub gives the reading order; the index gives the flat map.** `api/README.md` lists every leaf page, nested ones included — keeping every page **two clicks** from `docs/addons/README.md`.
5. Non-namespace reader-facing pages sit at the top of `docs/addons/` (`runtime.md`, `examples.md`). `api/` is the `hafen.*` contract and nothing else.
6. `README.md` pages are **indexes**: a concise sentence of orientation, then tables of links.

## 3. Voice and Tone

- **Developer-first, direct, concise, and scannable.** Strip out narrative prose, metaphors, philosophy, conversational filler, and rhetorical questions.
- **Second person, present tense, active.** "You get a handle back", not "a handle is returned".
- **The client, the server, your addon** are the three actors. Never "we".
- **Short first sentences.** Every page's and section's first sentence answers *what is this and when do I use it*, in one line.
- **No hedging, no selling.** Avoid "powerful", "simply", "just", "of course", "note that".
- **Limits are facts, not apologies.** State the constraint directly along with the reason.
- Consistency: British English (en-GB) in prose (`colour`), but code identifiers always match the API exact spelling (`color`).

## 4. The Reference Page Structure

One namespace or sub-subject per page, formatted for rapid technical lookup:

````markdown
# hafen.speed: Movement Speed

Brief one-sentence description of the subsystem and its purpose.

```lua
-- Single runnable block (<= 12 lines) showing the most common usage pattern
local current_session = hafen.session():current()
if current_session then
  local speed_controller = current_session:speed()
  speed_controller:set("run")
  hafen.log():write("Speed set to: " .. speed_controller:current())
end
```

---

## API Methods

| Method | Returns | Permission | Description |
|---|---|---|---|
| `speed:current()` | `string` | Unprotected | Current active movement speed identifier. |
| `speed:set(name)` | `Speed` | `player.speed` | Updates character movement speed. Chains. |

---

## Detailed Usage / Error Cases

Concise technical subsections explaining edge cases, lifecycle rules, or non-obvious behaviors.

---

## See Also

- [Player Controls](player.md) — Character position and action management.
- [Permissions](../../guides/permissions.md) — Permission declarations.
````

### Rules for Reference Content:
- **Tables are primary.** All methods, parameters, return types, and permissions must be clearly tabulated.
- **Permission clarity:** Always specify whether a method is `Unprotected` or requires a specific permission (e.g. `player.speed`, `client.settings`).

## 5. Headings and Anchors

- `#` exactly once as the first line. `##` for primary sections, `###` for sub-sections. `####` only for deep variant blocks.
- **Call headings** (if used outside tables) must be the fully qualified call in backticks with descriptive parameter names:
  `### session:world():place(position, angle, mouse_button, modifiers)`
- **Topic headings** are sentence case; subtitles use colons: `## Selectors: Matching Widgets`.
- **Clean anchors:** Avoid punctuation that breaks slugs (`—`, `/`, `&`, `[, ]`). No double hyphens (`--`) in generated anchors.

## 6. Code Examples & Variable Naming Standards

- **Strictly descriptive variable names. Single-letter variables are FORBIDDEN.**
  - **Forbidden:** `local s = ...`, `local w = ...`, `local p = ...`, `local g = ...`, `function(ev)`, `function(it)`, `function(cb)`
  - **Mandatory:** Use complete, meaningful words:
    - `session` (not `s`)
    - `widget`, `window`, `button`, `root_column` (not `w` or `p`)
    - `position` (not `p`)
    - `graphics` (not `g`)
    - `event`, `draw_event`, `close_event` (not `ev`)
    - `item`, `container` (not `it` or `c`)
    - `binding`, `keybindings` (not `b` or `k`)
    - `peer_handle`, `voice_connection` (not `p` or `v`)
    - `profiler`, `frame_stats` (not `p` or `f`)
    - `asset`, `collection` (not `a` or `c`)
- **Runnable as written:** Valid Lua snippets that can be pasted directly into `main.lua`. No pseudo-code, no `...` placeholders (use `-- implementation here`).
- **Only real symbols:** All `hafen.*` symbols in examples must exist in the runtime and in `src/`.
- Fenced and tagged: ```` ```lua ```` for code, ```` ```text ```` or ```` ```json ```` for manifests and outputs.

## 7. How Facts Are Stated

- **Tables carry the facts.** Prose explains context and mechanics.
- **No redundant count statements:** Say "the types below", never "the four types below" (avoids doc rot when lists change).
- **Explicit absence behavior:** Always state what methods return when an entity is missing (`nil`, `false`, or an empty table).
- **Distinguish hardware measurements from hard caps:** Do not hardcode unstable benchmark numbers in prose ("takes 0.04 ms"). Document concrete engine ceilings (e.g., "maximum 256 scopes", "timeout 10,000 ms").

## 8. No Historical Clutter

The documentation describes the present system state:
- **No changelogs or post-mortems:** Do not write "`hafen.foo` was renamed from `bar`" or "in version 2 this was removed".
- **Delete obsolete material completely.** Git tracks history; documentation tracks current truth.
- State deliberate design boundaries in the present tense: "There is no `hafen.music`: the server transmits no music streams."

## 9. Links and Formatting Mechanics

- **Relative markdown links only.** Always link to relative files: `[Session](session.md)`. Never repo-absolute GitHub URLs.
- **Link the page directly**, or an explicit stable heading anchor if targeting a specific subsection.
- **Concise page length:** Target under 300 lines per page. Split logically by subject if a page grows beyond that.
- Keep table rows readable and un-wrapped.
- Blockquote (`>`) is reserved for critical warnings, security gates, or permission requirements. Do not use for casual commentary.

## 10. Prohibited Content in `docs/addons/`

- Task/issue tracker IDs, internal sprint tickets, or PR numbers.
- `specs/` paths.
- Internal Java `src/` paths, Java class names, or internal JVM line numbers. (These belong exclusively to `docs/client/`).
- TODOs, "coming soon", or speculative future promises.

## 11. Pre-Commit Quality Checklist

Before submitting changes to `docs/addons/`:
1. **Variable naming audit:** Grep for single-letter variables in code blocks (`local s =`, `local w =`, `function(ev)`, etc.). All must be descriptive.
2. **Table validation:** Verify every method signature has Return Type, Permission, and Parameters documented.
3. **Link integrity:** Check that all relative markdown links resolve to existing files and valid anchors.
4. **No literary prose:** Review text to ensure explanations are technical, scannable, and direct.
5. **No obsolete references:** Ensure deleted or moved methods do not appear in examples or descriptions.

---

## 12. `docs/client/` — The Engine Map

Audience: Developers working on the Java client internals in `src/`.
- **Maps, not narrative:** Tables mapping Java subsystems, classes, and packages to their responsibilities.
- Cite Java classes and methods (`MapView.click`, `Widget.resize`), never transient line numbers.
- Documents upstream `haven.*` architectural gotchas and threading boundaries.
