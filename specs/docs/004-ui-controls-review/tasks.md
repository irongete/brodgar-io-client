# 004-ui-controls-review — Tasks

<!-- One task = one session. Test protocol is `none` (AREA.md): each task's verification material is
     its own report — counts with the offenders named, and a citation into `src/` for every claim it
     changed. The maintainer reads the changed pages as rendered Markdown against the spec. -->

- [x] **004.1 — Accuracy, and the roster both ways.** Read `controls.md` and `lists.md` line by line
      against `src/`, plus the `040` edits to `widget.md`, `custom.md`, `asset.md` and `examples.md`.
      Every refusal sentence is checked against the `LuaError` text, not the name's existence: the
      pending-rebuild rule on `:image()`, the bare-builder argument refusal, `:value(nil)`, the
      progress bar's `0..1` refusal against the slider's **clamp**, and every "reads `nil` on a control
      that has none". Then the roster, stated as a list in the report: each of `040`'s **16** builders
      is documented, and no page names a builder, verb, key or class that does not exist. Delete what
      §6 forbids — the prose counts on both pages, and any claim a page makes twice (`widget.md`'s two
      tables are where to look; they may be two different facts, and the report says which). Fix
      `controls.md:69` at 115 columns, and re-wrap what deletion leaves ragged.
      **Reports**: every correction with its `src/` citation · the 16-builder roster, both directions ·
      the prose counts and duplicates removed · `wc -l` on every page touched, which is 004.2's input ·
      any engine gap **filed to area `addons`** and named, never fixed here.
      <!-- extra context: `specs/codebase/ui-controls.md`, `src/io/brodgar/addon/` (the control
           builders and their `LuaError` strings) -->

- [ ] **004.2 — Shape, the standard's own files, and the close.** Four decisions and a sweep.
      **(a) Gating** — settle whether a setter group on a control you built carries `(ungated)` or stays
      plain, and write it into `decisions/docs-standard.md` as **D-014**; §3's `hafen.ui.overlay`
      precedent, D-010 and 001.3's "an annotation everywhere is a signal nowhere" are the three inputs.
      Apply it to both pages' group headings.
      **(b) Size** — with 004.1's count, either `controls.md` stands with headroom, or it splits by
      subject into `api/ui/controls/` (IA rule 3), or it stands and the IA records why (§9). If it
      splits: every anchored heading changes **level, not wording**, the ~30 inbound links are
      re-pointed in this same task (longest pattern first), and the new leaves get their own
      `api/README.md` rows so nothing falls to three clicks.
      **(c) The standard's files** — `information-architecture.md` §3's tree and §4's `api/ui` reading
      order learn that `controls` and `lists` exist; §7's grep list gains what `040` retired
      (`entry:text()`) under D-013's rule, falsified as reading zero on a healthy tree.
      **(d) The inherited hit** — `examples.md:106`'s `:offset(`, verified against `src/` first: the
      spelling is deleted if the anchor is genuinely gone, and §7 is corrected if it is not.
      Then the **full §12 sweep** and the close.
      **Reports**: D-014 and the reasoning that fixed it · the size decision with the number behind it ·
      links and anchors over every touched page **and every page linking into them**, count and zero
      broken, falsified in both directions · `wc -l` · headings · the §7 greps at zero, the
      `examples.md` hit included · every `hafen.*` name found in `src/` · columns measured with
      `perl -CSD`, table rows excluded, diffed against `HEAD` to separate introduced from inherited ·
      `STATE.md` figures **re-derived from the tree**, not carried forward.
      <!-- extra context: `specs/docs/002-map-database-review/` — 002.1 split `map.md` into a
           directory and re-pointed 22 links; it is the worked example for (b) -->
