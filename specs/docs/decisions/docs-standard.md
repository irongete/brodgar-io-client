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

### D-006 — A reference page's verbs are a table; a `###` call heading is for a verb that needs prose

**Context (001.3).** The style guide's reference template shows `## Read` / `### \`hafen.speed()\`` —
one call heading per verb — while §6 says tables carry the facts, because 001.1 found the tables were
right and the prose around them was wrong. Applied literally to group A, the template would have given
`hafen.gob` fifteen `###` sections for fifteen one-line readers.

**Decision.** Both, chosen by what the verb needs. A verb whose whole contract fits a row lives in the
group's **table** — that is §6's rule and it is the default. A verb that needs an argument table, its
own error cases or an example gets a `### <the call in backticks>` heading. Across group A only
`act.md` needed the second form. The **gating annotation lives on the group heading** and only on a
write group: `## Write (gated: \`actions\`)` or `## Write (ungated)`. `## Read` stays plain — a read is
ungated by construction, and annotating twenty of them is noise that hides the two cases that matter.

**Consequences.** Group A's pages average ~67 lines, the reference index still reaches every verb, and
`markers`/`radar`/`menugrid` — the three ungated *writes* (drift D-2, D-3) — are the pages where the
annotation actually carries information.

### D-007 — A call heading carries its parameter list, written without `[ ]`

**Context (001.3).** D-004 fixed the em dash because ` — ` slugs to a double hyphen. Optional-argument
brackets have exactly the same failure: `### \`hafen.act.clickGob(gob [, button [, mods]])\`` slugs to
`hafenactclickgobgob--button--mods`, two double hyphens, and every inbound link then depends on a
reader reproducing them.

**Decision.** The heading is the call with its parameters and no optionality syntax —
`### \`hafen.act.clickGob(gob, button, mods)\``. Which parameters are optional, and what they default
to, is stated in the line below or in the argument table. The rule generalises: **no heading contains a
character that the slugger deletes while its neighbours are spaces**, because that is the shape that
produces a double hyphen.

**Consequences.** `docs/` anchors are derivable from the heading by one rule (lowercase, drop
punctuation, spaces to hyphens) with no double-hyphen special case outside the four oversized pages
001.4 still owns.

### D-008 — A measured figure stays out of `docs/`; a documented cap or budget stays in

**Context (001.4).** The UI stack carried a dozen numbers that came out of a measurement session:
"174 of 625 widgets classified", "about 0.08 ms for 625 widgets", "roughly 50x what the geometry
calls cost", "overhead 0.54%". Every one was true when it was written, and style guide §11 already
bans "measurement results and benchmark figures" — but the same pages also carry numbers that look
identical and are not measurements at all: the text cache's `512 entries / 8 MiB`, `overhead().budget`
= 5%, the placement granularity range `2..17`.

**Decision.** The test is **who owns the number**. A figure produced by measuring *this* client on
*that* machine is not the reader's business: it rots silently, it cannot be checked by grep, and the
sentence around it works without it ("walking the whole tree once per event is nothing; sixty times a
second it is a real slice of your frame budget"). A figure the **engine enforces or reports** — a cap,
a ceiling, a valid range, the value behind an API key — is part of the contract and stays, because an
addon author branches on it.

**Consequences.** Prose keeps the *shape* of a cost ("far more expensive than any geometry call")
and drops the multiplier. Where a number is a budget, the page says which key reads it, so a reader
who wants the measurement takes it themselves.

### D-009 — A reference page **names** the example addon; only `examples.md` links it

**Context (001.4).** D-005 permits exactly one link out of `docs/`: to a shipped addon under
`addons/`, and only from a page that also describes it. Group B's pages held fifteen such links —
`planner`, `theme`, `profiler`, `widgetstack` — and not one of those pages describes the addon; they
link out mid-sentence for a *demonstration*, which is the audit's G-3 gap seen from the other side.

**Decision.** A reference page names the addon in prose, in bold backticks (**`theme`**), and says
what it demonstrates. The link is `examples.md`'s alone, because that is the only page that describes
what ships. Until `examples.md` exists (001.6), the name stands without a link — a page never links a
path a later task will create (style guide §8).

**Consequences.** Group B went from fifteen outbound links to zero without losing a single mention,
and 001.6 gains a concrete list of which pages want an `examples.md` link once it exists.

### D-010 — A client-local write group carries `(ungated)`; only reads and subscriptions stay plain

**Context (001.5).** D-006 puts the gating annotation on write group headings and leaves `## Read`
plain, and style guide §3 exempts "a subscription and your own drawing" from counting as writes.
Group C has a third shape neither line resolves: `sound:play`/`sound:stop` change client state that a
later read reports (`sound:playing()`), they are not a subscription and not drawing, and they send
nothing to the server — the same shape as `markers.add` and `radar.setVisible`, which drift D-3 was.

**Decision.** The question a group heading answers is *does this verb need a permission*, and it is
asked of every verb that **changes something**, wherever the change lands. A verb that changes
client-local state gets a write group with `(ungated)`; a read, a subscription and drawing into your
own frame stay plain, because they change nothing at all. So `sound.md` reads
`## Play and stop (ungated)`.

**Consequences.** The annotation now appears on exactly the groups where "no permission" is
information — `markers`, `radar`, `menugrid`, `sound` — and the rule is stated as a property of the
verb (does it change state?) rather than as a list of exempt shapes, which is what made the third
case ambiguous.
