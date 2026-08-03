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
- **State what is, not what happened.** No "now", "already", "still", "as of", "recently".
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
### `hafen.speed()`                       <- h3 is the call, and nothing but the call
What it answers, in one line. Arguments and returns as a table when there is more than one.
What it gives back when the data is not there.

## Write (gated: `actions`)               <- gating in the group heading, always present
### `hafen.speed.set(n)`

## See also                               <- required, 2-5 links, last section
````

**Granularity is per verb, not per page** (D-006). The skeleton above says what a reference page
*contains*, not how finely it is cut. A verb whose whole contract fits one row lives in the group's
**table** — that is §6's rule and it is the default, and it is what most namespaces are. A verb that
needs an argument table, its own error cases or an example gets a `###` call heading. One page may use
both. The **gating annotation sits on the group heading and only on a write group**:
`## Write (gated: \`actions\`)` or `## Write (ungated)`. `## Read` stays plain — a read is ungated by
construction, and annotating every reader buries the write that is surprisingly ungated. **A
subscription and your own drawing are not writes** for this purpose (`hafen.ui.on`, `:onItemAdded`,
`hafen.ui.overlay`, the `g:` verbs): they change no client state, so their group heading stays plain and
the page's opening lines say the namespace is ungated. That also keeps their anchors stable — an
annotation appended to a heading *is* an anchor change, and 001.4 broke 18 inbound links that way before
the sweep caught it.

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
  `### \`hafen.act.moveTo(x, y)\``, `### \`gob:name()\``. No arrows, no return types, no prose —
  the return goes in the first line below. This keeps anchors short and predictable.
- **A call heading carries its parameters, without `[ ]`** (D-007):
  `### \`hafen.act.clickGob(gob, button, mods)\``, never `(gob [, button [, mods]])`. Which
  parameters are optional is stated in the line below or in the argument table.
- **Topic headings** are sentence case; a subtitle uses a colon: `## Selectors: naming a widget`.
- **No em dash in any heading, ever** — and no other deleted character between two spaces. ` — `
  slugs to a *double* hyphen, which is the trap that made 001.1's first checker report 208 false
  breaks (`specs/docs/learnings/docs-maintenance.md`); ` [, ` and ` / ` do exactly the same. The
  general rule: **no heading holds a slugger-deleted character whose neighbours are spaces.**
  Checkable: `grep -rn "^#.*—" docs/` returns nothing, and no anchor in the tree has `--`.
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
- **Every verb states its gating**, including the ungated ones: `ungated`, `gated: actions`, or
  `gated: network`. `markers.add` and `radar.setVisible` write and are *not* gated, and no page
  says so while `actions.md` tells the reader the permission gates the per-subsystem writes
  (D-3). Silence is not a statement.
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
- **A retired name may not appear anywhere under `docs/`** — not in prose, not in a note, not in
  an example. The names 001.1 found retired are the grep list below; each must return zero hits
  at the close. Verify against `src/` before deleting a mention: the list is a target, not truth.

  ```text
  hafen.items      hafen.buffs      hafen.vitals     hafen.key.bind
  hafen.ui.adopt   hafen.ui.replace hafen.ui.onWidgetCreate    hafen.ui.root
  WidgetNode       hafen.font.load  setFont          resetFont
  hafen.render.image                hafen.render.model
  ```

  Seven of them are still written down today, in `fonts.md` (3), `ui.md` (2), `events.md` and
  `render.md`; the rest are already absent and the grep is their regression guard. Note what is
  **not** on the list: `hafen.ui.node(id)`, `hafen.ui.all`, `widget:items()` and `widget:replace()`
  are live — the flat `hafen.items` section and the free `hafen.ui.replace` are what went.

- **A boundary is allowed, and it is present tense.** A capability the reader would reasonably
  expect and that deliberately does not exist stays on the page, phrased as what the design does
  and why — never as a change: "There is no `hafen.music`: this server sends no music the client
  can address." Not "it was built and removed". The test: delete every clause that only makes
  sense to someone who read the previous version of the page.

## 8. Links

- **Relative paths only.** Never absolute, never a URL to the repo.
- **No link leaves `docs/`, with one exception**: a shipped example addon under `addons/`, and
  only from a page that also *describes* it. **Never link `specs/`** — internal, and historical
  at write time (030.4); the 16 outbound links today are drift D-4. If the reason behind a rule
  matters to the reader, write the sentence; if it does not, drop it.
- Link the **page**, not an anchor, unless the anchor is the actual answer.
- Every page is reachable from an index in **at most two clicks** from `docs/addons/README.md`;
  the reference index therefore lists every leaf page, including nested ones.
- Link text is the thing being linked (`hafen.gob`, "the actions permission"), never "here" or
  "this page".
- **When you retitle a heading or move a page, you re-point every link into it in the same
  task.** The tree is link-clean at every task boundary, not only at the close.

## 9. Size

- **Ceiling: 300 lines** (`wc -l`), for every page under `docs/`.
- **Split by subject, never by line count.** A 300-line page that is one subject beats two
  150-line halves of one. If a page is over the ceiling and cannot be split by subject, it is
  named in the IA with the reason; nothing goes over 350.
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
   count checked, zero broken. Falsify in both directions: plant one break, confirm it is caught,
   remove it. An over-reporting checker is the failure mode that actually happened.
2. **Size** — `wc -l` on every page touched, none over 300.
3. **Headings** — no em dash, no `#####`, no internal codes.
4. **Retired names** — the §7 grep list, zero hits.
5. **Symbols** — every `hafen.*` name the task wrote exists in `src/`.
6. Any engine gap or wrong behaviour found is **filed to the owning area**, named in the report,
   and never fixed here.
