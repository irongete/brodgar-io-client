# DOCUMENTATION — how a page under `docs/` is written

> Read it **before** writing a page, not after. **§1–§11 are the standard for `docs/addons/**`**,
> the reader-facing contract: what a page looks like, where it lives, and what a task checks before
> it hands over. **§12 is the standard for `docs/client/**`**, the engine map — a different
> audience, and a much shorter set of rules.

## 1. Scope and audience

`docs/addons/` is the **user-facing tier**: someone who wants to write an addon and has never read
`src/`.
It is not a specification, not a changelog and not a design record. The reader wants three things,
in this order — get an addon running, learn the task in front of them, look a verb up — and that is
exactly the tutorial / guides / reference split.

**`docs/` is also the contract `/plan` and `/implement` read.** It has to be true, because nothing
else states what the API is.

## 2. Where a page lives

1. **Three tiers, and a page belongs to exactly one.** The tutorial (`getting-started.md`, one
   path), `guides/` (one *task* per page), `api/` (one *namespace* per page). A guide never
   restates a signature; a reference page never teaches a workflow.
2. **One namespace, one path**, named exactly as it is spelled in Lua: `hafen.buff` →
   `api/buff.md`. A reader who knows the name can type the path.
3. **Over the ceiling, a namespace becomes a directory** `api/<namespace>/` whose `README.md` is
   its hub; the recursion repeats inside (`api/ui/style/`). At the top level of `api/`, **a
   directory means a namespace** — a page named for a *type* (`gob.md`, `overlay.md`) splits into a
   **sibling type page**, never a directory.
4. **A hub gives the reading order; the index gives the flat map.** `api/README.md` lists every
   leaf page, nested ones included — that is what keeps every page **two clicks** from
   `docs/addons/README.md`.
5. Non-namespace reader-facing pages sit at the top of `docs/addons/` (`runtime.md`,
   `examples.md`). `api/` is the `hafen.*` contract and nothing else.
6. `README.md` pages are **indexes**: a sentence of orientation, then tables of links. An index
   carries no explanation its pages do not carry.

## 3. Voice

- **Second person, present tense, active.** "You get a handle back", not "a handle is returned".
- **The client, the server, your addon** are the three actors. Never "we".
- **Short first sentences.** Every page's and every section's first sentence answers *what is this
  and when do I reach for it*, in one line, before any qualification.
- **No hedging, no selling.** Not "powerful", "simply", "just", "of course", "note that".
- **A limit is a fact, not an apology.** "A hidden window swallows its toggle" — then why.
- en-GB or en-US consistently within a page; the tree is en-GB (`colour` in prose, `color` for the
  API name — the code spelling always wins inside backticks).

## 4. The reference page

One namespace or one sub-subject per page, complete and lookup-shaped. The reader arrives knowing
the name they want.

````markdown
# speed: movement speed               <- h1, once, "namespace: subject"

What it is in one sentence. When you reach for it, in one more. A link to the guide that
uses it, if there is one.

```lua
-- one runnable block, <= 12 lines, the single most common use
```

## Read                                 <- h2 groups the verbs
### `s:speed():current()`               <- h3 is the call, and nothing but the call
What it answers, in one line. Arguments and returns as a table when there is more than one.
What it gives back when the data is not there.

## Write (protected)                    <- the heading says THAT it is protected, never which key
### `s:speed():set(n)`                  <- the key goes beside the verb: a column, or a sentence

## See also                             <- required, 2-5 links, last section
````

**Granularity is per verb, not per page.** A verb whose whole contract fits one row lives in the
group's **table** — that is the default. A verb needing an argument table, its own error cases or an
example gets a `###` call heading. One page may use both.

**The permission annotation sits on the group heading, and only on a write group**: `## Write
(protected)` or `## Write (unprotected)`. **A group is a write group when its verbs change
something, wherever the change lands** — a map marker, an icon flag, a sound are writes, and
`(unprotected)` is exactly where "no permission" is information. `## Read` stays plain: a read is
unprotected by construction, and annotating every reader buries the write that is surprisingly
unprotected. **A subscription and your own drawing are not writes** (`hafen.ui():on`,
`hafen.ui():overlay()`, the `g:` verbs) — they change no state, so their heading stays plain and the
page's opening lines say the namespace is unprotected. That also keeps their anchors stable: an
annotation appended to a heading *is* an anchor change.

**Guides** show the shape of a solution and link each verb to its reference page; they end with a
"Next" line. The **tutorial** is one path — no branches, no alternatives, no "you could also" — and
every block is run in order by the maintainer during verification.

## 5. Headings and anchors

- `#` exactly once, as the first line. `##` and `###` for structure; `####` only inside a reference
  page's verb detail. **`#####` and deeper are forbidden** — a page needing them is two pages.
- **Call headings** are the fully qualified call in backticks and nothing else, **with its
  parameters and no `[ ]`**: `### \`s:world():place(p, angle, button, mods)\``. No arrows, no
  return types, no prose — the return goes in the line below, and which parameters are optional is
  stated there or in the argument table.
- **Topic headings** are sentence case; a subtitle uses a colon: `## Selectors: naming a widget`.
- **No slugger-deleted character between two spaces, in any heading, ever.** ` — ` slugs to a
  *double* hyphen; ` / `, ` & ` and ` [, ` do the same damage. Write `Skill, Credo, Experience` and
  `Character and status`. Checkable: `grep -rnE "^#.*( [—/&] |\[, )" docs/` returns nothing, and no
  anchor in the tree contains `--`.
- No trailing punctuation, no bold, no internal codes (`(V2)`, `035.4`). Unique within its page.

## 6. Examples

- Every block is fenced and tagged: `lua` for code, `text` for console output or file layout.
- **Runnable as written** — a complete fragment you can paste into `addons/myaddon/main.lua`, or a
  body shown inside the callback it belongs to. No `...` standing in for code (write
  `-- your code here`), no pseudo-code, no invented verbs.
- **Only symbols that exist.** Every `hafen.*` name in an example is in the reference and in `src/`.
- **A page carries its own example.** Nothing under `addons/` illustrates a surface, so a page writes
  the block it needs and leans on no folder for it. Where a page names one of them it uses bold
  backticks (**`profiler`**) and says what it is for; the **link belongs to `examples.md` alone** — a
  reference page names, it does not link out.
- **A page documents only what the client itself provides.** A `hafen.*` verb, a manifest field, a
  file the client reads — never a Lua library the reader would have to be handed, since nothing
  under `addons/` is a library anything may build on.
- Invented examples use `myaddon`, `addons/myaddon/main.lua`, lowercase word variables. Never
  `foo`, `bar`, `test`.
- A reference block shows **one verb**, a guide block **one task**, the tutorial's blocks build
  **one addon**, cumulatively. Output is shown only when the reader cannot predict it.

## 7. How facts are stated

Rot lives in prose, not in tables. So:

- **Tables carry the facts** — verbs, arguments, keys, properties, events. Prose carries the model:
  why the thing is shaped this way, what it costs, what it will not do.
- **No count in prose that duplicates a list.** Not "the four types", "all three verbs" — say "the
  types below". A count goes stale silently and no grep catches it.
- **Every verb states its permission**, the unprotected ones included: `unprotected`, or
  `protected: actions`. Silence is not a statement.
- **Every verb states its absence case** — what it gives back when the thing is not there — and
  whether it can throw, and on what.
- **A claim made twice on one page will disagree with itself.** Cross-page greps cannot see a
  same-page duplicate. State an argument once and link to it from the other place.
- **A measured figure stays out; a documented cap stays in.** The test is who owns the number.
  "0.08 ms for 625 widgets" is one machine on one day and the sentence works without it. A cache's
  `512 entries / 8 MiB`, a budget of `5%`, a valid range of `2..17` are enforced by the engine, so
  an author branches on them and they stay.

## 8. No history

The docs describe what exists today. There is no release, no migration tier: history is git's.

- **Delete, do not relocate.** No obituaries ("`hafen.font.load` is gone"), no renames ("was called
  X"), no corrections addressed to a reader of a previous version, no promises ("a later feature").
- **A retired name may not appear anywhere under `docs/`** — not in prose, not in a note, not in an
  example. Derive the list from the engine's own refusal table (`Retired.NAMES`/`KEYS`) in the task
  that sweeps; never carry a previous report forward.
- The banned construction is writing to a reader who knew an earlier version, **not a word list**.
  `now`, `already`, `still` and `no longer` are ordinary present tense here ("is it still there", "a
  marker no longer there"). What a sweep greps is: `used to`, `was called`, `formerly`,
  `previously`, `renamed`, `as before`, `before this`, `Corrected` — and **every hit is read**.
- **A boundary is allowed, and it is present tense.** A capability the reader would reasonably
  expect and that deliberately does not exist stays on the page, phrased as what the design does and
  why: "There is no `hafen.music`: this server sends no music the client can address." The test —
  delete every clause that only makes sense to someone who read the previous version.

## 9. Links, size, mechanics

- **Relative paths only**, never a URL to the repo. Link the **page**, not an anchor, unless the
  anchor is the actual answer. Link text is the thing linked (`s:world()`), never "here".
- **No link leaves `docs/`**, with one exception: a shipped example addon under `addons/`, from the
  page that also describes it — `examples.md`, and nothing else.
- **When you retitle a heading or move a page, you re-point every link into it in the same task.**
  The tree is link-clean at every task boundary, not only at a close.
- **Ceiling: 300 lines**, and **split by subject, never by line count** — a 300-line page that is
  one subject beats two 150-line halves of one. Nothing goes over 350. There is no floor: a 20-line
  page is correct when the namespace is 20 lines' worth. Applied rule: a page is too big when its
  opening sentence has to say "and".
- **Price a split by its inbound anchors, not by the size of the candidate.** Count page links and
  anchor links separately: an anchor link breaks when the heading moves to another page, a bare page
  link only when the page moves. Promoting `###` to `##` keeps the slug.
- Prose wraps at **110 columns**; code inside a fence stays under **100**; table rows are never
  wrapped. Blockquote `>` is for one thing only — a callout the reader must not miss (a gate, a
  cost, a footgun) — never for asides, never two in a row.
- Bold for the first use of a term the page defines; backticks for every identifier, path, key, file
  name and console command. No italics for emphasis. No HTML, no front matter, no badges, no emoji,
  no horizontal rules.

## 10. What never appears under `docs/addons/`

Task and feature numbers · `specs/` paths · `src/` paths, class names and line numbers · benchmark
figures · TODOs, "coming soon", "not yet" · the maintainer or the process. **A reader of
`docs/addons/` cannot tell that `specs/` exists.** (`docs/client/` inverts the `src/` half of this,
and only that half — see §12.)

## 11. What a task that touched `docs/addons/` checks before handing over

Over the pages it touched. Report counts, with the offenders listed.

1. **Links and anchors** — resolve every link and every anchor on those pages; broken must be zero.
   The scan **matches across newlines**: a link whose text wraps onto a second line is invisible to
   a line-oriented pattern and reads as clean. Three shapes are resolved, not one — a page path, an
   anchor into another page, an anchor within the same page. A move also leaves **link text** stale
   where no link is broken, so grep the text, not only the targets.
2. **Size** — `wc -l` on every page touched, against the 300-line ceiling. **A page your own
   writing pushes over it is split here**, by §9: split by subject, price the split by its inbound
   anchors, and re-point them in this same task.
3. **Headings** — `grep -rnE "^#.*( [—/&] |\[, )" docs/` returns nothing; no `#####`, no internal
   codes, no trailing punctuation.
4. **Wording** — the change-note constructions from §8. Every hit read, not counted.
5. **Symbols** — every `hafen.*` name written on a page exists in `src/io/brodgar/addon/**`, and no
   retired name appears anywhere under `docs/` (derive the refusal table again).
6. **The impact set** — discharged **once per feature**, by the task whose surface owns it: each
   page the `spec.md` derived is either revised or **explicitly discharged with its reason**. This
   is the check that exists because a feature documents the pages it opens,
   while the stale sentence sits in a page it never opened, written in the negative, invisible to
   every grep aimed at the new syntax.
7. **Findings** — a gap or a defect in the surface being documented belongs to the feature that
   ships it: it is raised at the close and becomes a task of that feature. A trap in upstream
   `haven` becomes a **gotcha on its `docs/client/` page** (§12.4), written in this same task.
   Nothing is filed anywhere else.

## 12. `docs/client/` — the engine map

A different subtree, a different reader: **you, on the next feature**, wanting to know where a
subsystem lives before opening `src/`. One page per subsystem, named after it (`widgets.md`,
`network.md`, `render-gl.md`), listed in `docs/client/README.md`.

It exists because `src/haven/**` is 100k lines of unannotated Java and searching it from scratch
every feature is the cost this subtree buys down. It is **a map, not an authority**: when it
disagrees with `src/`, `src/` wins and the task that noticed fixes the page in the same task.

Six rules, and they are the whole standard:

1. **Never a line number.** Cite the **class and the member** — `MapView.click`, `Widget.resize`,
   `Session.sendmsg`. A `file:line` anchor dies the moment anyone edits a line above it, and
   policing that is what made the previous attempt at this need a guard script of its own. A name
   is greppable, survives an upstream pull, and `grep -n` costs a second.
2. **Map, not narrative.** Tables of *where a thing lives and what owns it*. Prose only for the
   model — the lifecycle, the threading rule, the ownership that the names alone do not show.
3. **Upstream `haven` only. Never `io.brodgar`.** Your own layer is already stated by `src/` and by
   `docs/addons/`; a third copy is the one that goes stale.
4. **A gotcha lives on its subsystem's page**, not in a tier of its own, and only when it cost real
   time. That split — where things are, over here; what bit you, over there — is exactly what made
   the last version unsweepable.
5. **A page is born when a feature had to read that source anyway.** The reading is paid once.
   There is no completeness goal and no page for a subsystem nobody has touched.
6. **Ceiling 150 lines**, and it is corrected in place — present truth, like `docs/addons/`. A page
   that outgrows the ceiling has stopped being a map.

Everything else from §1–§11 is off: no tutorial/guide/reference split, no permission annotations,
no two-clicks rule, no ban on `src/` paths — naming them is the point. What still holds: relative
links, no `specs/` paths, no history, and English.
