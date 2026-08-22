# brodgar-io-client — Project Instructions

Customized Haven & Hearth ("Hafen") client, forked from `dolda2000/hafen-client`. Active work: a
World-of-Warcraft-style Lua (LuaJ) AddOn system in `src/io/brodgar/addon/`, on branch
**`feature/addons`**.

## The tree — the places there are, and no others

| Path | What it holds |
|---|---|
| `docs/addons/**` | **The contract.** What the API *is*, always current. The only model `/plan` and `/implement` need |
| `docs/client/**` | **The map of the upstream `haven` engine**: where each subsystem lives, what owns what, and the gotchas that cost time. Written only by the task that had to read that source anyway |
| `src/` | The `haven` engine (upstream) and `src/io/brodgar/**` (ours) |
| `addons/` | The two tools — `profiler` and `widgetstack` — and the one task suite in flight |
| `bin/addons/` | What the running client actually scans, beside the jar. A suite is copied here to be run, and gitignored |
| `specs/ROADMAP.md` | The maintainer's own long-term queue. `/plan` reads it; **no command writes it** |
| `specs/NNN-<feature>/` | `spec.md` · `plan.md` (its *Discarded alternatives* are the decision record) · `tasks.md`, plus the archived suites. Written once, then frozen |
| `tools/` | The checkers that hold `docs/` to `src/`: every documented verb resolved against its own **receiver's** vocabulary, and every verb a refusal offers as a replacement. Run them when either side moves — they exit non-zero, and they state their own blind spots |
| `DOCUMENTATION.md` | How a page under `docs/` is written |

**If a fact is true of the API today, it lives in `docs/` and `src/` — nowhere else.** `ls specs/`
is the index, and a second copy of a live fact is the copy that goes stale. While a feature is in
flight its `spec.md` and `tasks.md` carry what its own later tasks need, and freeze with it.

`docs/client/` is the one exception, and it earns it: it maps **upstream `haven` only**, where the
sole other copy is 100k lines of unannotated Java. **Nothing about `io.brodgar` is ever written
there** — that is already said by `docs/addons/` and by your own code, and the third copy is the
one that claims a class deleted two features ago. It is a **map, not an authority**: when it
disagrees with `src/`, `src/` wins and the task that found the discrepancy fixes the page.

A `NNN-` folder is **frozen**: never swept, never re-read unless a plan names it as prior art. It
tells you what was rejected and why — never what is in force. It also cites paths and numbers from
the day it was written, so **take the reason, never the pointer**: never follow a path out of a
frozen folder. If the reason does not stand on its own words, it is not prior art.

## Rules (obey always)

- **NEVER `git push`.** Everything stays local.
- **`/end` makes the only self-driven commit**, and it lands the whole task at once — code, docs,
  specs, addons — *after* the maintainer's verification. `/plan` and `/implement` commit nothing.
- **Everything in English**: the docs, the specs, the code and its comments.
- **A feature ships whole, and closes with nothing of its own left open.** A gap or a defect in the
  surface the feature itself ships is a **task of that feature**, never a note left somewhere for
  later. A defect or a trap in upstream `haven` is a **gotcha on its `docs/client/` page**. A page
  your own writing pushes over a ceiling is **split in the task that wrote it**. Everything else — a
  capability the client has not got, an idea past this feature's boundary — is **reported to the
  maintainer at the close**, and is theirs to queue.
- **`/archive` is a frozen backup — NEVER read it.**
- Core edits to `haven` stay minimal, centralized, and tagged `// addon:`.
- Read nothing outside what the running command lists, unless the maintainer names it.
- **No command looks for information in the history.** What is in force is in `docs/` and `src/`,
  and within a feature still in flight, in its own `spec.md` and `tasks.md`.
- These four files are written the way `docs/` is: present tense, no note of what a rule used to be.

## Build and verification

- `ant hafen-client` → `BUILD SUCCESSFUL`. `ant get-luaj` fetches dependencies, `ant bin` packages,
  `ant run` launches.
- An incremental build hides a symbol that moved between files — `rm -rf build/classes` first for a
  true compile check.
- Java compiles at source/target **1.8** (no `var`, no `Files.readString`, no switch expressions)
  and runs on Java 23.
- **A Java change needs an `ant` rebuild AND a full client restart.** Only Lua addon *files* reload
  live, with `:reload`.
- Pre-check logic headlessly where you can: `jshell`, or the in-game `:lua` REPL.
- Final verification is in-game, by the maintainer, through the task's own suite.

## The API grammar — invariant, every feature obeys it

- **One canonical way** per operation, no dual styles. Namespaced `hafen.*`. Nothing is released,
  so a replaced API is **hard-cut**: no deprecation alias, and every retired spelling throws naming
  its replacement. **A rename is free and a reshape is not**: `Retired` carries a *name*, so a
  changed argument, return or payload shape has nothing to key on — it needs a refusal written
  inside the verb and a line on the page, and neither of those is a row anything sweeps.
- A **section** is called and is a per-addon singleton; everything after it is a **colon verb**;
  **arity is the verb**; a set is a **collection**.
- **Reference-based accessors**: a read takes the thing it reads and hands back a **live interned
  object**, with `:info()` as its only snapshot. An explicit `nil` raises, except where a page
  documents a meaning for it.
- **One notification verb**: `X:on(key, fn)` → a `Sub`, ended with `sub:off()`. The address picks
  the door — hold the object, subscribe on it; otherwise on the bus.
- A **builder** is constructed bare and configured by chained setters. A place is a **Position**.
- **Protected tier**: a write verb sits behind a **per-verb permission key**, declared exactly or as
  a `<prefix>.*` group, with enable-time consent and no global switch. Everything else observes, or
  writes client-local only.

## Testing — one task, one self-checking suite

Every task ships its own addon at `addons/<NNN>-<feature>.<X>/` (task `033.2` of
`specs/033-ui-stylesheet/` → `addons/033-ui-stylesheet.2/`; the folder name IS the manifest `id`).
It asserts **through the very API the task just shipped** and prints one verdict line per check:

```text
[pass] the sheet accepts a positional colour
[fail] an unknown property is refused -- got: <no error>
[manual] open the chat and read a line -- expect: grey-blue text, font unchanged
[summary] 12 pass, 1 fail, 2 manual
```

The maintainer runs `:t<NNN>-<X>`, writes the observed result on each `[manual]` line, and pastes
the whole block back. Nothing needs interpreting — that round trip is the format's point.

- **A suite stands ALONE.** Its one command is the whole verification of that task. Where its proof
  rests on something an older suite also checks, it **duplicates the assertion** — assume no other
  suite is ever run. Needing a second command is a missing assertion, never a request to make.
- **Automate everything the API can read back.** A refusal is a check too: `pcall` the bad call and
  assert it failed *and* said why. `[manual]` is only for what a program cannot **observe** — a
  keypress, a judgement of how something looks, a server-side effect. Not for what it cannot cause:
  where a check needs a receiver only the server can produce, retry on a timer for a bounded window
  and score over what the run reached.
- **A suite never starts itself** (no login hook, no timer), never mutates persistent state, and
  stays around ≤ 15 output lines. More than that means it was two tasks.
- `/implement` copies it to `bin/addons/` to be run, and re-copies it after every fix round. `/end`
  archives it into `specs/NNN-<feature>/addons/` and deletes the copy.
- **An addon is a suite when its folder name reads `<NNN>-<feature>.<X>`, and only then.** The only
  other folders under `addons/` are the two tools, `profiler` and `widgetstack`: never archived,
  never deleted, never grown to carry a proof — fixed when a change breaks them, and that is all.
  **No third folder is added.** A surface is shown by its own page's example, never by a demo.

## The cycle

**`/plan <feature>`** (design, review, no commit) → **`/implement`** (one task; iterate with the
maintainer until it passes) → **`/end`** (verify, close, commit everything).

Each command states exactly what to read. Read nothing else.
