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

### D-011 — Launcher flags and system properties are not part of the user-facing tier

**Context (001.6).** `runtime.md` documents the sandbox, the two CPU budgets and the AddOns panel from
`src/`. Three of the numbers it states are overridable at launch by JVM system properties (the addons
directory, the per-call instruction cap, the per-tick budget). They are shipped and real, and D-008 says an
enforced budget belongs on the page — so the question arrives: if the number is documentation, is the flag
that changes it documentation too?

**Decision.** No. `docs/` addresses the person **writing an addon**; a `-D` flag addresses the person
**launching the client**. The enforced number stays, because an author branches on it; the switch that
changes it does not appear. The same holds for any future command-line switch or environment variable —
they belong to an operator tier `docs/addons/` is not.

**Consequences.** `runtime.md` states "ten million instructions" and "about ten milliseconds, sustained"
flatly, with no escape hatch mentioned, and the omission is deliberate rather than an oversight: 001.7's
matrix lists it with this reason, which is what keeps a later audit from re-filing it as a GAP.

### D-012 — The landing page lists namespaces; the reference index lists every leaf

**Context (001.7).** The migrated tree has 57 reference leaves, 21 of them nested two or three levels
down (`api/ui/style/keys.md`, `api/client/profiling/counters.md`). Style guide §8 requires every page to
sit within two clicks of `docs/addons/README.md`, and there are only two indexes between a reader and a
leaf. Either index could carry the flat list, and carrying it in both means one 57-row table maintained
twice.

**Decision.** The landing page's "API at a glance" links **namespaces only** — the hub of a nested
namespace, never its leaves — and `api/README.md` lists **every leaf page that exists**, grouped, with a
one-line purpose each. Landing → reference index → leaf is exactly two clicks, and the flat list has one
owner. A hub page still gives the reading order for its own subtree; that is a third path, not a
duplicate of the index.

**Consequences.** A new reference page is added to `api/README.md` and nowhere else, unless it introduces
a whole namespace, which also earns a cell on the landing table. `docs/README.md` — the site root — is
linked by nothing below it: it is where a reader arrives from the repository, not somewhere the tree
navigates back to, so it is outside the two-click measurement rather than an orphan.

### D-013 — The retired-name guard greps a spelling, not a name, when the name survives as a refusal

**Context (003.2).** Style guide §7 says a retired name may not appear anywhere under `docs/`, and the
grep list is the regression guard. 038 hard-cut the `follow` / `offset` anchor on `hafen.ghost.new`,
`hafen.render.sprite` and `hafen.render.object` — but the cut is *enforced at runtime*: passing either key
raises, naming `gob:overlay`. That refusal is live behaviour a reader who guesses the option name needs,
so the page that refuses it has to write the word. Meanwhile bare `follow` has 30+ legitimate hits across
the tier ("the ghost follows the cursor", "redirects are followed") and bare `offset` is a live
`gob:overlay` spec field and a live style key. A list entry that fires on any of those cries wolf, and a
list that cries wolf stops being run.

**Decision.** A §7 entry is admitted only in a **spelling** that (a) reads zero on the healthy tree and
(b) catches a planted reintroduction — both directions demonstrated in the admitting task's report, with
the rejected candidates and their reason named. Where the bare name cannot do both, the entry is the
spelling a *reintroduction* uses and a *refusal* does not: `follow =`, `follow=`, `:follow(`, `:offset(`,
never bare `follow` or `offset`. A refusal is written in the form no entry hits — ``a `follow` key`` —
and documenting one is a **boundary**, not history: it says what the API does today and why.

**Consequences.** §7 carries two blocks: the name grid, and a second block for spellings that cannot live
in it. A later task runs both blind, which is the point — the guard operates at name level (001.2) and
must be checkable without knowing what the feature that wrote it was thinking. Adding an entry is not free:
it costs the falsification run, and an entry that cannot be falsified is not added.

### D-014 — A control's own writes state their gating in prose; the heading stays plain

**Context (004.2).** `controls.md` and `lists.md` (040) ship `## Builders` / `## Setters` / `## Rows`
groups that carry no gating annotation at all — neither `## Write (gated: …)` nor `## Write (ungated)`,
which §3 and D-006 otherwise require of a write group. Every setter a control has changes only client-side
widget state, the same shape `widget.md`'s `## Owned vs borrowed` is, and D-010's test (does the verb change
something, wherever it lands) says yes: a control's writes qualify as a write group needing the annotation.
But every single group on both pages is that same shape — there is no gated sibling on either page to
contrast against, which is exactly the failure 001.3 named: "annotating every setter group `(ungated)` is
exactly how `## Write (ungated)` stopped being a signal on the twenty group-A pages." `widget.md` already
solved this once, for its own all-ungated `## Owned vs borrowed` table: one sentence in prose ("None of
these writes is gated: they are client-side state, and every one of them restores"), no heading annotation.

**Decision.** A page whose write groups are *all* ungated — no gated sibling anywhere on the page to
contrast against — states the gating once, in prose, near the writes it covers, and leaves the group heading
plain. The `## Write (gated: …)` / `## Write (ungated)` heading annotation is reserved for a page that mixes
the two, which is where D-006's annotation is a signal rather than noise. `controls/README.md`'s `## Setters`
section closes on "None of this is gated…", and `lists.md` states the same in its own opening section.

**Consequences.** Neither page's group headings change, so no anchor moves. The rule generalises past this
feature: a future control or a future row-source verb that reaches the server gets its own gated write group
with the annotation, and the day that happens is the day these two pages' plain prose sentence is wrong and
has to be replaced by the heading form — which is the same trade D-010 made for `sound.md`.

### D-015 — §7's retired-name list is derived from the engine's refusal table, not maintained by hand

**Context (005.4).** §7's guard was 28 hand-written names with no relationship to what the engine actually
refuses. `src/io/brodgar/addon/Retired.java` is that relationship, written as data: three tables mapping
every replaced spelling to the message naming its replacement — 93 `hafen.*` section and verb keys, 75
entity-verb keys and 4 retired event keys once the `section(…)` helper and the three loops are expanded (69
and 54 literal `put(` rows before expansion). The hand-written list omitted `hafen.gob`, `hafen.hook`,
`hafen.ghost`, `hafen.render` and every one of the entity verbs, and it annotated as **live** six names the
engine now throws on: `hafen.world.gridPos`, `hafen.world.fromGridPos`, `hafen.world.screenToWorld`,
`hafen.world.snapPlace`, `hafen.world.snapAngle`, `hafen.map.markers`. Three features had moved under it and
nobody had re-derived it — which is the failure mode a list maintained by memory has, not a lapse.

**Decision.** §7 states a **derivation** and the sweep re-runs it (§12), rather than carrying names. The
derivation collapses the table into four checks, because the retirements have shapes, not just names: every
retired verb spelling is *dotted* (a section is called), so one regex `hafen\.[a-z]+\.[a-zA-Z]` carries all
86 of them **and** a dotted spelling of a section the table has no row for; the 7 sections that went whole
are bare names; the 4 event keys are grepped as a subscription writes them; and the entity half is guarded
**backwards** — every colon verb the tier uses against the registration set — because the receiver is a
variable at every call site, so a `<entity>:<verb>` entry reads zero for the wrong reason and would catch no
reintroduction. Hand-writing survives only for a name cut before the table existed (`hafen.items`,
`WidgetNode`, `setFont`, `gobOverlay`, …), which has nothing to throw and therefore no row to derive from.
D-013's admissibility test is unchanged and now applies to a *derived* entry too: zero on the healthy tree,
and it catches a plant.

**Consequences.** The list stops rotting between features, and it grew from 28 names to a guard that covers
172 refusals. Two things it does not do, stated in §7 rather than papered over: a verb retired on one entity
and live on another (`widget:onClick` vs `sprite:onClick`) has no admissible spelling at all, and the page's
own accuracy pass is what catches it; and the backward verb sweep it now leans on is also the only guard
against an **invented** verb, which no retired-name list can express (005.1's `ghost:move`).

### D-016 — A voice rule stated as a word ban is admitted only in a spelling that reads zero

**Context (005.4).** §2 said "state what is, not what happened" and made it checkable as a word ban: no
"now", "already", "still", "as of", "recently". Over the tree those words read **243** hits and **not one**
is a change-note — `is it still there`, `the client already owns it`, `what is standing right now` are the
tier's ordinary present tense, and `:exists()` rows alone account for a third of them. So the rule as
written condemns 243 correct sentences and a sweep run literally against it would damage prose to satisfy a
grep. That is D-013's finding on the other side of the standard: a guard that cries wolf stops being run,
and one that manufactures work is worse than one that finds nothing.

**Decision.** The prohibition is on the **change-note** — a sentence that only makes sense to a reader who
knew an earlier version of the API or of the page — and a voice rule is only stated as a word ban when the
bare word reads zero on the healthy tree. Where it cannot, the rule names the **construction** instead:
`used to`, `was called`, `formerly`, `previously`, `renamed`, `as before`, `as it always did`, `before
this`, `these days`, `Corrected`. Those read 3 hits tree-wide, few enough that the check is grep **and
read**, not a count. `no longer` is on neither list: in this tier it is nearly always runtime state ("a
marker no longer there").

**Consequences.** §2's rule becomes runnable for the first time — the sweep that took it literally found
seven real change-notes (`api/act.md`'s "used to walk you somewhere wrong", `ui/widget.md`'s "before this"
and two "as it always did", `vr/widgets.md`'s two, `client/profiling/attribution.md`'s "has always") and
left 243 correct present-tense sentences alone. The admissibility test D-013 wrote for a retired-name entry
is now the standard's general rule for any grep-checkable prohibition, wherever it appears.

### D-017 — The permission's adjective is `protected` / `unprotected`, everywhere the standard spells it

**Context (006.2).** Area `addons`' `048-act-dissolved` renamed the tier's own adjective across `src/` and
`docs/`: a write group heading now reads `## Write (protected: \`actions\`)` or `## Write (unprotected)`,
and both spellings read **0** under `docs/`. The standard did not follow. Style guide §3's reference
skeleton still showed `## Write (gated: \`actions\`)`, §6's per-verb bullet still demanded "its gating"
in `ungated` / `gated: actions`, and the IA's tree still annotated `markers` with "the ungated writes" —
so the file a docs task is checked against taught a heading no page in the tree carries, which is the
shape of drift D-015 found in §7's name list: a standard nobody re-derived after the thing it describes
moved.

**Decision.** The standard reads in the shipped adjective. Style guide §3 (the skeleton and the write-group
rule), §6 (the per-verb bullet) and the IA's §3 tree say `protected` / `unprotected`; the concept noun is
**the permission**, not "gating", which is the word `docs/` itself uses (`conventions.md`'s "The actions
permission"). **D-006, D-010 and D-014 are not rewritten**: a decision records what was decided in the
words it was decided in, and a later one supersedes it — the D-015 / D-016 precedent. Their `gated` /
`ungated` bodies are read as this entry's earlier spelling.

**Consequences.** §5.3 and §6 of the IA keep theirs too, being the record of one migration rather than the
current map. The rename costs nothing at page level, because the shipping feature carried every heading
already; what it closes is the standard, which is the only copy a later task or a later area reads before
writing. `hafen.http`'s `network` allowlist is stated on its own page rather than as a heading annotation,
so §6 stops offering `gated: network` as a form no page uses.
