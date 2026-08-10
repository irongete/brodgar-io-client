# Docs style guide

> The standard for everything under `docs/`. Written by 001.2 from 001.1's evidence
> (`specs/docs/001-docs-overhaul/audit.md`); every later task of every feature is checkable
> against it. The companion is [`information-architecture.md`](information-architecture.md),
> which says *where* a page lives; this file says *what a page looks like*.

## 1. Scope and audience

`docs/` is the **user-facing tier**: a person who wants to write an addon and has never read
`src/`. It is not a specification, not a changelog and not a design record — those are `specs/`,
git and the `NNN-` folders. The reader wants three things in this order: get an addon running,
learn how to do the task in front of them, look a verb up. That is the tutorial / guides /
reference split the IA implements.

## 2. Voice

- **Second person, present tense, active.** "You get a handle back", not "a handle is returned".
- **State what is, not what happened.** What goes is the **change-note**: a sentence that only makes
  sense to a reader who knew an earlier version of the API or of the page. The ban is on that use and
  not on a word list — `now`, `already` and `still` are ordinary present tense in this tier ("is it
  still there", "the client already owns it", "what is standing right now"), 243 hits of which none is
  a change-note, so grepping the bare words cries wolf and stops being run (D-016). What the sweep
  greps is the construction, and it is short enough to read every hit: `used to`, `was called`,
  `formerly`, `previously`, `renamed`, `as before`, `as it always did`, `before this`, `these days`,
  `Corrected`. `no longer` is on neither list — in this tier it is nearly always runtime state ("a
  marker no longer there"), so it is read, not grepped.
- **The client, the server, your addon** — those are the three actors. Never "we".
- **Short first sentences.** Every page's and every section's first sentence answers *what is this
  and when do I reach for it*, in one line, before any qualification.
- **No hedging and no selling.** Not "powerful", "simply", "just", "of course", "note that".
- **A limit is a fact, not an apology.** "A hidden window swallows its toggle" — then why.
- English, en-GB or en-US consistently within a page; the tree today is en-GB (`colour` in prose,
  `color` for the API name — the code spelling always wins inside backticks).

## 3. The three page kinds

**Reference** (`api/**`) — one namespace or one sub-subject per page, complete and lookup-shaped.
The reader arrives knowing the name they want.

````markdown
# hafen.speed: movement speed            <- h1, once, "namespace: subject"

What it is in one sentence. When you reach for it, in one more. A link to the guide that
uses it, if there is one.

```lua
-- one runnable block, <= 12 lines, the single most common use
```

## Read                                   <- h2 groups the verbs
### `hafen.speed():current()`             <- h3 is the call, and nothing but the call
What it answers, in one line. Arguments and returns as a table when there is more than one.
What it gives back when the data is not there.

## Write (protected: `actions`)           <- the permission in the group heading, always present
### `hafen.speed():current(n)`

## See also                               <- required, 2-5 links, last section
````

**Granularity is per verb, not per page** (D-006). The skeleton above says what a reference page
*contains*, not how finely it is cut. A verb whose whole contract fits one row lives in the group's
**table** — that is §6's rule and it is the default, and it is what most namespaces are. A verb that
needs an argument table, its own error cases or an example gets a `###` call heading. One page may use
both. The **permission annotation sits on the group heading and only on a write group**:
`## Write (protected: \`actions\`)` or `## Write (unprotected)`. **A group is a write group when its verbs
change something, wherever the change lands** (D-010): a client-local change — a map marker, an icon flag,
a sound — is a write and its heading says `(unprotected)`, which is exactly where "no permission" is
information. `## Read` stays plain — a read is unprotected by construction, and annotating every reader
buries the write that is surprisingly unprotected. **A subscription and your own drawing are not writes**
(`hafen.ui():on`, `widget:on("ItemAdded", fn)`, `hafen.ui():overlay()`, the `g:` verbs): they change no
state at all, so their group heading stays plain and the page's opening lines say the namespace is
unprotected. That also keeps their anchors stable — an annotation appended to a heading *is* an anchor
change, and 001.4 broke 18 inbound links that way before the sweep caught it.

**Guide** (`guides/**`) — one *task* per page, start to finish, and it **never restates a
signature**: it shows the shape of the solution and links each verb to its reference page. A
guide may be read top to bottom; it ends with a "Next" line pointing at the next guide or at the
reference section it leans on.

**Tutorial** (`getting-started.md`) — one path, no branches, no alternatives, no "you could
also". Every block is run in order by the maintainer during verification; a block that cannot be
run as written is a defect.

`README.md` pages are **indexes**: a sentence of orientation, then tables of links. An index
carries no explanation that its pages do not carry.

## 4. Headings and anchors

- `#` exactly once, as the first line. `##` and `###` for structure; `####` only inside a
  reference page's verb detail. **`#####` and deeper are forbidden** — a page needing them is two
  pages.
- **Call headings** are the fully qualified call in backticks and nothing else:
  `### \`hafen.player():move(p)\``, `### \`gob:name()\``. No arrows, no return types, no prose —
  the return goes in the first line below. This keeps anchors short and predictable.
- **A call heading carries its parameters, without `[ ]`** (D-007):
  `### \`hafen.world():place(p, angle, button, mods)\``, never `(p, angle [, button [, mods]])`. Which
  parameters are optional is stated in the line below or in the argument table.
- **Topic headings** are sentence case; a subtitle uses a colon: `## Selectors: naming a widget`.
- **No em dash in any heading, ever** — and no other deleted character between two spaces. ` — `
  slugs to a *double* hyphen, which is the trap that made 001.1's first checker report 208 false
  breaks (`specs/docs/learnings/docs-maintenance.md`); ` [, `, ` / ` and ` & ` do exactly the same.
  The general rule: **no heading holds a slugger-deleted character whose neighbours are spaces.**
  Write `Skill, Credo, Experience` and `Character and status`, not `Skill / Credo / Experience` and
  `Character & status`. Checkable — and the em-dash grep alone is **not** the check, since it read
  clean while six ` / ` and ` & ` anchors survived (001.5): `grep -rnE "^#.*( [—/&] |\[, )" docs/`
  returns nothing, and no anchor in the tree has `--`.
- No trailing punctuation, no internal codes (`(V2)`, `035.4`, `D-092`), no bold in headings.
- Heading text is unique within its page.

## 5. Examples

- Every block is fenced and tagged: `lua` for code, `text` for console output or file layout.
- **Runnable as written.** A block is either a complete fragment you can paste into
  `addons/myaddon/main.lua`, or a body shown inside the callback it belongs to. No `...`
  standing in for code — write `-- your code here`. No pseudo-code, no invented verbs.
- **Only symbols that exist.** Every `hafen.*` name in an example is in the reference and in
  `src/`. A task's report says it checked this.
- **The example addon is the source.** Where a shipped addon under `addons/` demonstrates the
  surface, the page's example is cut down from it and the page **names** it in bold backticks
  (**`bags`**), saying what it demonstrates. The **link belongs to `examples.md` alone** (D-009) — a
  reference page names, it does not link out. Nothing under `docs/` may be the *only* place a piece
  of working code exists.
- The naming convention in invented examples: the addon is `myaddon`, the file
  `addons/myaddon/main.lua`, variables are lowercase words. Never `foo`, `bar`, `test`.
- Reference blocks show **one verb**; guide blocks show **one task**; the tutorial's blocks build
  **one addon**, cumulatively.
- Output is shown only when the reader cannot predict it, as a `text` block.

## 6. How facts are stated

Rot lives in prose, not in tables (035.4, and 001.1 confirmed it: the tables were right, the
notes were wrong). So:

- **Tables carry the facts** — verbs, arguments, keys, properties, events. Prose carries the
  model: why the thing is shaped this way, what it costs, what it will not do.
- **No count in prose that duplicates a list.** Not "the four types", "the seven site keys",
  "all three verbs" — say "the types below". A count is a fact that goes stale silently and
  cannot be checked by grep; `asset.md`'s "identical for all three types" sat 25 lines under a
  heading reading "The four types" (D-12).
- **Every verb states its permission**, including the unprotected ones: `unprotected` or
  `protected: actions`. A map marker's `:add`, an icon's `:show(on)` and a sound's `:play` write and are
  *not* protected, while the actions guide tells the reader what the permission covers and what it does
  not (D-3). `hafen.http` is the one namespace that declares separately — a `network` host allowlist —
  and it states that on its own page. Silence is not a statement.
- **Every verb states its absence case** — what it returns when the thing is not there (usually
  `nil`, per `conventions.md`) — and whether it can throw, and on what.
- **A measured figure stays out; a documented cap or budget stays in** (D-008). The test is who owns
  the number. "174 of 625 widgets classified", "0.08 ms for 625 widgets", "50x what the geometry calls
  cost" are one machine on one day — the sentence works without them. The text cache's `512 entries /
  8 MiB`, `overhead().budget` = 5%, a valid range like `2..17` are enforced or reported by the engine,
  so an author branches on them and they stay.
- **A claim made twice on one page is a claim that will disagree with itself.** `render.md` described
  `follow` in an options table *and* in a prose section, and the two had drifted apart (001.4). The
  audit's greps look across pages; a same-page duplicate is invisible to them. State an argument once,
  and link to it from the other place.
- A claim about behaviour is backed by `src/` or by the `NNN-` folder that shipped it. The
  citation goes in the **task's report**, never on the page.

## 7. No history

The docs describe what exists today. There is no release, no user and no migration tier: history
lives in git and in `specs/`.

- **Delete, do not relocate.** No obituaries ("`hafen.font.load` is gone"), no renames ("was
  called X"), no corrections addressed to a reader of a previous version ("**Corrected.** Until
  this was measured the table claimed…"), no promises ("a later task of the same feature").
- **A retired name may not appear anywhere under `docs/`** — not in prose, not in a note, not in an
  example. The list is **derived from the engine's own refusal table**, not remembered (D-015):
  `src/io/brodgar/addon/Retired.java` maps every spelling this API replaced to the message that names its
  replacement, in three tables — section and verb keys (`hafen.<section>`, `hafen.<section>.<verb>`),
  entity-verb keys (`<entity>:<verb>`), and retired event keys. The derivation below is re-run and
  reported by every task that sweeps (§12), never carried over from an earlier report: an entry's proof
  has a shelf life (004.2), and a *derived* name can collide with a live spelling exactly as a
  hand-written one can.

  **The `hafen.*` half is one expression, seven names and one regex.** Every retired verb spelling is
  *dotted*, because the grammar is that a section is **called** — so one regex covers all of them, and
  covers a dotted spelling of a section the table has no row for:

  ```text
  grep -rnE 'hafen\.[a-z]+\.[a-zA-Z]' docs/
  ```

  and the sections that went whole are bare names, grepped with `grep -rnF`:

  ```text
  hafen.events   hafen.quests   hafen.wounds   hafen.gob
  hafen.hook     hafen.ghost    hafen.render
  ```

  **The eighth section is the first entry that needs a regex of its own.** `hafen.act` is a *prefix* of a
  live spelling, `hafen.actionbar`, so `grep -rnF hafen.act` reads 19 lines on a healthy tree and every
  one of them is legitimate — the bare name fails D-013's first half. What is admitted is the bounded
  form, which reads zero and still catches a reintroduction:

  ```text
  grep -rnE 'hafen\.act\b' docs/
  ```

  Its verbs need no entry of their own. The table holds each of them twice, and both spellings are
  covered: `hafen.act.moveTo` is dotted, so the expression above carries it, and `hafen.act():moveTo`
  begins with `hafen.act`, so the bounded form does.

  **The entity half is guarded backwards, not by a name list.** A retired entity verb is keyed
  `<entity>:<verb>` — `widget:onClick`, `gob:pos`, `overlay:scale` — and the entity is a *variable* at
  every call site (`w:on(…)`, `ov:offset(…)`), so the key's own spelling reads zero on any tree for the
  wrong reason: nothing writes `widget:` in an example, so nothing would catch a reintroduction either.
  The guard is the other direction: **every colon verb the tier uses, against the registration set** —
  `grep -rnoE '[\w)\]]:(\w+)\('` over `docs/`, each verb tested with `grep -rn 'set("<verb>"'` over
  `src/io/brodgar/addon/`. A verb the tier calls and nothing registers is a defect either way round — a
  retirement nobody re-pointed, or a name a page invented, which no retired-name list could ever carry
  (005.1). Its output is a list to **account for**, not to act on: `set("<verb>"` matches a string literal,
  so a verb registered under a **computed** name reads as unregistered though it is live — `ev:sender()` is
  `m.set(noun, …)`, with `noun` picked by the event's shape (006.4). An example addon's own handle verbs and
  Lua stdlib on a string literal come back the same way. Chase each name into the file that owns it.
  **What that sweep cannot see, stated rather than pretended**: a verb retired on one entity and
  live on another. `widget:onClick` is retired while `sprite:onClick` is live; `overlay:scale` is retired
  while `gob:scale` is live; `:position(`, `:destroy(`, `:show(`, `:text(`, `:alpha(`, `:tint(` and
  `:rotate(` are each in the table for one entity and registered for another. No spelling separates them,
  so none is admitted (D-013) — the page's own accuracy pass against the owning file is what catches
  those, and the page that refuses one writes it as a boundary.

  **The event keys** are string *arguments*, so no field read carries them and the refusal lives at the
  emitter's door. They are grepped as a subscription writes them, `grep -rnF`:

  ```text
  "OnLoad"   "OnEnterWorld"   "OnUpdate"   "OnDisable"
  ```

  **The residue is hand-written**: a name cut before the refusal table existed leaves nothing to throw,
  so it has no row to derive from. Everything else 001.1 listed is dotted, and the regex above already
  carries it (`hafen.key.bind`, `hafen.ui.adopt`, `hafen.ui.replace`, `hafen.ui.onWidgetCreate`,
  `hafen.ui.root`, `hafen.font.load`, `hafen.map.tile`, and the `hafen.map.*` spelling of the live-world
  verbs).

  ```text
  hafen.items    hafen.buffs    hafen.vitals   hafen.markers   hafen.radar
  WidgetNode     setFont        resetFont      gobOverlay
  ```

  Three spellings cannot be grepped as bare names, because the bare word is ordinary English or a live
  option elsewhere. They are admitted in the **spelling** that reads zero on a healthy tree and still
  catches a reintroduction (D-013), and the grep is `grep -rnF`:

  ```text
  follow =         follow=          :follow(
  ```

  Bare `follow` is **not** admitted: 30+ legitimate hits ("the ghost follows the cursor", "redirects are
  followed"), and a list that cries wolf stops being run. Nor is bare `offset`, which is a live spec
  field on `gob:overlay` and a live style key. What went is the `follow`/`offset` **anchor** on the
  world-entity builders and the handle methods of the same names; anchoring to a game object is the
  anchor argument. Naming the refused option on the page that refuses it is a boundary, not history —
  write it as `a \`follow\` key`, which no spelling above hits.

  **`:offset(` is not admitted either (004.2), for the same reason as bare `offset`.** `ov:offset(x, y[,
  z])` — a gob overlay's own anchor — and `p:offset(dx, dy)` — a Position's own translate — are both live
  and share the retired handle method's exact call spelling, so the entry cannot be falsified: it read 15
  hits on a healthy tree, not zero. The retired anchor has no admissible spelling of its own — the
  boundary sentence on the page that refuses it is what a reader needs, not a guard that cannot tell the
  two `:offset(`s apart. `entry:text(` is refused on the entity-half rule above: `entry` is not a name
  the docs give a variable, so the entry reads zero however wrong the tree gets, while a reintroduction
  would be written `e:text(s)` — and `:text(` is live on every text-bearing widget. The boundary belongs
  on the control's own page, which states that the bare read answers and the write refuses.

  **What is live, and is on no list.** The *called* spelling of everything above: `hafen.ui():node(id)`,
  `hafen.ui():all(sel)`, `hafen.ui():root()`, `hafen.world():screenToWorld(…)`, `:snapPlace`,
  `:snapAngle`, `hafen.map():marker()`, `widget:items()`, `widget:replace()`. What went is the *dotted*
  spelling of each, which is why the regex is anchored on the dot and not on the verb: admitting bare
  `:root(` or `:all(` instead would read non-zero on a healthy tree, which is `:offset(`'s failure
  exactly.

- **A boundary is allowed, and it is present tense.** A capability the reader would reasonably
  expect and that deliberately does not exist stays on the page, phrased as what the design does
  and why — never as a change: "There is no `hafen.music`: this server sends no music the client
  can address." Not "it was built and removed". The test: delete every clause that only makes
  sense to someone who read the previous version of the page.

## 8. Links

- **Relative paths only.** Never absolute, never a URL to the repo.
- **No link leaves `docs/`, with one exception**: a shipped example addon under `addons/`, and
  only from a page that also *describes* it — which today is `examples.md` and nothing else
  (D-009). **Never link `specs/`** — internal, and historical at write time (030.4). If the reason
  behind a rule matters to the reader, write the sentence; if it does not, drop it.
- Link the **page**, not an anchor, unless the anchor is the actual answer.
- Every page is reachable from an index in **at most two clicks** from `docs/addons/README.md`;
  the reference index therefore lists every leaf page, including nested ones.
- Link text is the thing being linked (`hafen.world`, "the actions permission"), never "here" or
  "this page".
- **When you retitle a heading or move a page, you re-point every link into it in the same
  task.** The tree is link-clean at every task boundary, not only at the close.

## 9. Size

- **Ceiling: 300 lines** (`wc -l`), for every page under `docs/`.
- **Split by subject, never by line count.** A 300-line page that is one subject beats two
  150-line halves of one. If a page is over the ceiling and cannot be split by subject, it is
  named in the IA with the reason; nothing goes over 350.
- **Price the seam by its inbound anchors before choosing it, not by the size of the candidate**
  (006.3). Count the *page* links and the *anchor* links separately: an anchor link breaks when
  the heading moves to another page, a bare page link only when the page itself moves. Promoting
  `###` to `##` keeps the slug, so a split changes only the *page* half of every target. Where
  the new page goes is the IA's rule 3 — and D-018, for a page named after a type.
- **No floor.** A 20-line reference page is correct when the namespace is 20 lines' worth; what
  makes it findable is the index, not padding.
- The split rule, applied: a page is too big when its opening sentence has to say "and".

## 10. Mechanics

- Prose wraps at **110 columns**; code inside a fence stays under **100** so it does not scroll
  on GitHub; table rows are exempt and never wrapped.
- Blockquote `>` is for **one** thing: a callout the reader must not miss (a gate, a cost, a
  footgun). Never for asides, never two in a row.
- Bold for the first use of a term the page defines; backticks for every identifier, path, key,
  file name and console command. No italics for emphasis.
- Lists: `-` for bullets, `1.` for ordered. A list item is a sentence or a fragment, not a
  paragraph — a paragraph is a paragraph.
- No HTML, no front matter, no badges, no emoji, no horizontal rules except above a page's
  final index block.

## 11. What never appears under `docs/`

Task and feature numbers · decision IDs (`D-092`) · `specs/` paths · `src/` paths, class names
and line numbers · measurement results and benchmark figures · TODOs, "coming soon", "not yet" ·
the maintainer, the areas, or the process. A reader of `docs/` cannot tell that `specs/` exists.

## 12. What every docs task runs and reports

No tooling ships (AREA.md), so these are ad-hoc `grep`/`awk` runs, reported as counts with the
offenders listed — that report *is* the task's verification material:

1. **Links and anchors** over every page the task touched **and every page linking into them** —
   count checked, zero broken. Falsify in **four** directions and confirm the *total* moves with the
   broken count each time: a bad path, a cross-page anchor, a same-page anchor, and a link whose text
   **wraps across a newline**, which a line-oriented checker skips silently and so reports zero for the
   wrong reason (006.3). Then plant a *valid* wrapped link and confirm it reads clean — an
   over-reporting checker is the failure mode that actually happened. Remove every plant.
   A move also leaves **link text** stale where no link is broken: grep the text against its target's
   page name, not only the targets.
2. **Size** — `wc -l` on every page touched, none over 300.
3. **Headings** — no em dash, no `#####`, no internal codes.
4. **Retired names** — §7's list, **derived again** from `Retired.java` rather than copied from the
   last report: the dotted regex, the seven bare sections, `hafen\.act\b`, the event keys, the
   hand-written residue and the three admitted spellings, each at zero, and the falsification (plant
   one, confirm it is caught, remove it). A count of the table's rows goes in the report, so a table
   that grew since the last sweep is visible.
5. **Symbols, both directions** — every `hafen.*` name the task wrote exists in `src/`; and the
   backward sweep of §7, every colon verb the tier uses against the registration set, with each
   unregistered verb named and accounted for. That is what catches an invented verb, which no
   retired-name list can.
6. **Wording** — §2's change-note constructions, every hit read rather than counted.
7. Any engine gap or wrong behaviour found is **filed to the owning area**, named in the report,
   and never fixed here.
