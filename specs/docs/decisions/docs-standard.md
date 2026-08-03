# Decisions — the page standard and the shape of the tree

> Category file. Index: [../DECISIONS.md](../DECISIONS.md). Append-only; each `### D-xxx` header
> doubles as the one-liner. The rules themselves live in `../design/style-guide.md` and
> `../design/information-architecture.md` — this file records *why* they are what they are.

### D-001 — The page ceiling is 300 lines, and a page splits by subject, never by length

**Context (001.1).** 39 pages, 5,727 lines, four of them holding 45% of the total (`ui.md` 1171,
`client.md` 688, `fonts.md` 436, `render.md` 336) while 14 sit under 40 lines and nothing sits
between 260 and 330. The distribution is bimodal because the tree grew task by task: a page is
either a stub nobody revisited or the dumping ground for a whole feature run.

**Decision.** 300 lines (`wc -l`) is the ceiling for every page under `docs/`. The *split* is by
subject: a 300-line page that is one subject beats two 150-line halves of one, and a page that
cannot be split by subject is named in the IA with its reason (hard stop 350). There is **no
floor** — a 20-line reference page is right when the namespace is 20 lines' worth; findability
comes from the index, not from padding.

**Consequences.** The target tree is 72 pages, none planned over 270. The operative test for
"too big" is prose, not arithmetic: a page is too big when its opening sentence has to say "and".

### D-002 — One namespace, one path: `api/<namespace>.md`, spelled as it is in Lua

**Context.** Page names drifted from the API they document: `hafen.buff` lived in `buffs.md`,
`hafen.act` in `actions.md`, `hafen.sound` in `audio.md`, `hafen.hook` in `hooks.md`, and
`hafen.char` + `hafen.study` shared one page.

**Decision.** A namespace under the ceiling is `api/<namespace>.md`, spelled exactly as it is in
Lua, so a reader who knows the name can type the path. Over the ceiling it becomes a directory
`api/<namespace>/` whose `README.md` is the hub, and the recursion repeats inside for a
sub-subject that needs more than one page (`api/ui/style/`, `api/client/profiling/`). Hubs carry
the reading order; `api/README.md` lists every **leaf** page, which is what keeps everything two
clicks from the landing page.

**Consequences.** Renames in 001.3–001.5 (`buffs`→`buff`, `meters`→`meter`, `actions`→`act`,
`hooks`→`hook`, `audio`→`sound`) and two splits (`char`→`char`+`study`, `console`→`log`+`slash`),
each re-pointing every inbound link in the same task. Nothing is released, so no alias tier.

### D-003 — The stylesheet is owned by `api/ui/style/`; `api/font.md` keeps typography only

**Context (001.1).** The stylesheet is documented twice — `fonts.md:134-424` and
`ui.md:583-1111` — both current, neither wrong: two entry points into one subject, ~250
duplicated lines. Its sharpest symptom is that `widget:skin{…}` has **no** section in `ui.md`,
which links out to `fonts.md` for it eight times while owning selectors, the cascade, the
property tables and the layout rules.

**Decision.** The sheet is one subject with one owner: `api/ui/style/` (the model + `skin{}` +
`w:style` + `widget:skin` + cascade + boundary, the key grammar, the surface catalogue, and one
page per property group). `api/font.md` keeps only typography: getting a handle, `derive`, `font=`,
`g:text`, the `$font` tag.

**Consequences.** `widget:skin{…}` moves from `fonts.md` to the page that owns the cascade
(closing the 034.3 THIN row), and the eight cross-links disappear rather than being re-pointed.

### D-004 — No em dash in a heading

**Context.** ` — ` between spaces slugs to a **double** hyphen; that is what made 001.1's first
link checker report 208 broken anchors, every one false.

**Decision.** Headings carry no em dash (a subtitle uses a colon), no trailing punctuation and no
internal codes. Call headings are the call in backticks and nothing else — no arrows, no return
types — so anchors stay short and predictable.

**Consequences.** `grep -rn "^#.*—" docs/` is a one-line conformance check, and every anchor in
the tree can be derived from its heading without a slugger.

### D-005 — `docs/` never links `specs/`

**Context (drift D-4).** 16 links leave `docs/` today; seven point at `specs/addons/design/*`,
which 030.4 recorded as historical-at-write-time — the one class of document the user-facing tier
must never cite as truth. All of them resolve, so a tree-local checker calls them healthy.

**Decision.** The only link that may leave `docs/` is one to a shipped example addon under
`addons/`, and only from a page that also describes it. Decision IDs, task numbers and `specs/`
or `src/` paths never appear in `docs/` at all: if the reason behind a rule matters to the reader,
write the sentence; if it does not, drop it.

**Consequences.** The reason a design is what it is has to be *stated* in the docs or left out —
it can no longer be outsourced to a link. A reader of `docs/` cannot tell that `specs/` exists.
</content>
