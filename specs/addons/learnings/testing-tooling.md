# Learnings — Headless testing & tooling

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Headless testing works** for the pure engine (event/timer logic): install the facade on a throw-
  away `Addon`, drive `AddonManager.tick(dt)` directly. Only `AddonRoot`/`OCache`/`MapView` wiring
  needs the live game. (See the throwaway `TickTest` pattern — same-package + reflection for privates.)
- **Windows/Git-Bash gotcha:** a `;`-separated `-cp` gets mangled by MSYS path conversion → prefix
  `java`/`javac` with `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`. Also `/tmp` breaks under
  `MSYS_NO_PATHCONV=1` — write throwaway `.java` to the **scratchpad** dir, not `/tmp`.
- **Headless persistence testing is strong and cheap** (extends the throwaway-in-scratchpad pattern): a
  same-package `StoreTest` builds a real `Addon` (package-private ctor) with a hand-built `store` table,
  sets `charScope` + `haven.savedatadir` via reflection, then drives `flush`/`loadScope` to simulate
  write → **relog** (fresh Addon + empty store) → read → increment → relog, asserting the counter survives.
  19/19 including array round-trip + write-skip (mtime unchanged) — proves the DoD before the in-game run.
- **Headless sandbox testing** (same throwaway-in-scratchpad pattern): `Sandbox.create()` needs no live
  game, so a same-package test asserts withheld surfaces are nil / whitelisted present, runs a bounded loop
  (no false-trip) and an infinite loop under a low `-Dhaven.addon.insncap` (aborts fast, catchable LuaError
  with "watchdog" in the message), for BOTH the addon env and `consoleGlobals()`. Also parse-compile the
  real `hello/main.lua` under `Sandbox.create()` to prove the harness stays compatible. 49/49 here.
- **Headless-test the enabled set against an isolated prefs node.** `Utils.prefs()` honors
  `-Dhaven.prefspec` (`Config.Variable.prop("haven.prefspec", "hafen")`) → `userNodeForPackage(Utils).node(spec)`.
  Run the test JVM with `-Dhaven.prefspec=addon-test-1f2` so `setEnabled`/`isEnabled` exercise the **real**
  `getprefsl`/`setprefsl` persistence path without touching the live `hafen` prefs, then `removeNode()` the
  test node afterwards. 14/14 (default-enabled, disable+enable round-trip, idempotency, persistence across
  a fresh read, null/empty no-op). The reload *sequence* itself (widgets/session) stays an in-game DoD.
- **Instrumenting the widget protocol is a strong diagnostic (kept in a learning, code reverted):** three
  `System.out.println` taps — widget **create** (`UI.NewWidget.run`), **place** (`AddWidget.run`, shows
  `child -> parent` — the most revealing for tree assembly), and **message** (`UiMessage.run`) — plus a marker
  at the code point under test, tagged `// addon-debug:` for one-grep removal, captured to a file over a few
  logins, pinpointed an ordering race in minutes. `ant run` forks the client with stdout inherited, so
  `ant run 2>&1 | tee x.log` captures it (note: PowerShell `tee` writes **UTF-16** — `tr -d '\000'` to read;
  ant prefixes forked stdout with `[java]`).
- **Headless-testing an L2 hook without constructing a live `UI` (2d).** A real `UI` needs a real `Audio.Root`
  (`new ActAudio.Root(null)` NPEs on `sys.mixer`), so don't build one. Test the pieces that don't need it: the
  marshalling converters (pure static), `LuaActionHook.invoke` with a real `Sandbox.create()` env + a `new
  Widget()` sender + `u=null` (a preventDefault/passthrough handler never dereferences `u`), the colon-call
  convention in isolation, and `onWdgmsg`'s no-hook fast path (returns true before the `ui`/`holdsLock` check).
  The `resend`/`send` → `rawWdgmsg` wiring (one line each) and the `holdsLock` gate are then the only in-game
  bits — same "headless proves the logic, in-game proves the wiring" split as prior slices. 33/33 here.
- **Headless-test `onMessage` end-to-end via reflection into the private dispatch map (2e-1).** `LuaMessageHook`
  needs only a throwaway `Addon` (package-private ctor + a `Sandbox.create()` env — no live UI, unlike 2d's
  `onWdgmsg` which needs `holdsLock`), so the FULL `onMessage` precedence path IS headless-testable: reflectively
  grab the private `static messageHooks` map, `put` a `CopyOnWriteArrayList` of hooks, call the public
  `onMessage`, assert (null=swallow / new array=rewrite / same-array-identity=fast path). Build handler fns with
  `env.load("return function(ev) … end","=t").call()` (the Java compile API works on a sandbox env — it strips
  only the Lua-facing `load`). 27/27; the fast path returning the *same* array instance (`==`) proves near-zero cost.
- **Headless-testing anything that builds a `KeyEvent`/`GlobKeyEvent` drags in `UI.<clinit>` (2e-2).** `KbdEvent`'s
  ctor calls `UI.modflags`, and `UI`'s static block runs `loadscale()`→`initscale()`→`Toolkit.instance()` — which
  **NoClassDefFoundErrors** without `lib/jglob.jar` on the classpath, then throws a **caught** `Unavailable`
  ("could find no working toolkit") under `-Djava.awt.headless=true` (an `Error` isn't caught by `initscale`'s
  `catch(Exception)`, but the toolkit `Unavailable` IS → falls back to default scale and proceeds). So the
  headless run needs `build/classes;luaj;lib/jglob.jar` (+ jogl/lwjgl so the providers load and fail *gracefully*
  headless) and `-Djava.awt.headless=true`; a benign "could not determine maximum scaling factor" warning prints,
  then the real events/`match`/`onGlobKey` all work. Use `forcode` keys (F5, Ctrl+F5) for deterministic match
  asserts (no AWT control-char mangling); `-Dhaven.prefspec=addon-test-2e2` isolates the `KeyBinding` prefs. 31/31.
- **`haven.Window` needs GL → the interesting `nativeWindowOf`/wrapper-hide branch is IN-GAME, but everything else
  is headless (3c).** A bare `new Widget(Coord)` builds headless (as 3b showed), so `nativeWindowOf(bareWidget)==self`
  and the whole hide-target/origVisible field logic test headless; the "finds the enclosing `Window`" branch and the
  live `getwidget`/`widgetid`-backed scan/`fireReplace` need a live UI. The **match logic is 100% headless** (pure,
  private-static → reflection): 36/36 including the `context="main"` container-inv rejection, caption, the `match`
  predicate in a real `Sandbox.create()` env, and the visible-grid vs hidden-default-wrapper origVisible capture.
  Same "headless proves the logic, in-game proves the UI wiring" split as every prior slice.
- **Headless-testing a method that persists: subclass the collaborator with a no-op sink, don't fight the static.**
  `radarSetIn` calls `conf.dsave()` on change; `ResCache.global` is `static final` (can't null it) and the real
  `save()` would NPE on the synthetic sets' null `from`. Cleanest fix: `new GobIcon.Settings(null,name){ public void
  save(){} }` — `dsave()`→(defer)→`dsave0()`→`save()` dispatches to the override → **zero disk I/O, no NPE**. Also:
  factor the testable core (`radarSetIn(Settings,…)`) to take the collaborator directly so it needs no live `gui()`,
  and keep it **package-private** so a throwaway `io.brodgar.addon`-package test class can reach it (jshell's unnamed
  package can't see package-private members — a compiled same-package `main` can). Full classpath for such a run =
  `build/classes` + **all** `lib/**/*.jar` (`Resource`'s `<clinit>` pulls in `lib/jglob.jar`).
- **Some read surfaces have almost no logic — the headless test then verifies null-safety + wiring, and the
  live value is an in-game check (A7).** A7 speed is `Speedget.cur`/`max` (two public ints) located via the
  Locator — the read itself is trivial, so the 8 headless checks target what CAN fail: the locator returning
  `null` with no HUD (no NPE), the facade table + functions installed, each returning `nil` with no HUD. The
  actual 0..3 value needs a live `Speedget`, verified in-game — same as every widget-tree read ultimately is.
  Don't manufacture a fake change-detection helper just to inflate the check count; test the real failure modes
  (null-safety, wiring) and lean on the in-game DoD for the live value.
- **A widget's `.class` literal / `isInstance`/`cast` do NOT trigger its static init, but reading a static
  FIELD does — and that can pull in resource loading unavailable headless (A7).** `gui().children(Speedget.class)`
  and the `cl.isInstance(n)`/`cl.cast(n)` inside it never run `Speedget`'s static block (JLS 12.4.1: class
  literals don't initialise), so the Locator is headless-safe. But `speedName()` reads `Speedget.tips`, a
  `static final String[]` populated by `Resource.local().loadwait(...)` at class-init — the first access forces
  the block and needs the client resource loader (`dolda/jglob/Loader`, absent under bare `jshell`). Practical
  rule: locating a widget by `.class` is always headless-testable; reading its static resource-backed tables is
  an **in-game** check. Keep the bounds-check that guards such a field read cheap and correct (verified in-game).

- **A8 — headless can't build a `haven` widget that renders text (extends the A7 note).** A7 found the Locator
  itself is headless-safe. A8 needed to test the *snapshot* of a `Makewindow.Spec` (an inner class), but merely
  referencing `haven.Makewindow` runs its `<clinit>`, which calls `Text.render("Quality:")` → the `ui/fraktur`
  font resource, absent under bare `jshell` (there is **no `res/` dir on disk** — resources come from a jar /
  the server at runtime), so it throws `NoSuchResourceException` before any test code runs. So: **pure data
  helpers** (here `craftRes`/`craftReses`, which take a stub `Indir<Resource>`) are headless-testable — fabricate
  a `Resource` via `Unsafe.allocateInstance` + reflect-set its `name` (needs `lib/jglob.jar` on the classpath,
  or `Resource.<clinit>` fails on `dolda/jglob/Loader`); but any helper that must be fed a **real widget-built
  object** is a documented **in-game / resource skip** (like 3a's Window-caption skip). Split the production code
  so the data-shaping is a plain-object function (A8's `craftSpec(Spec)` instead of `craftSpec(SpecWidget)`) to
  maximise what's testable without a live UI.
- **A9-1 — extract the change-detection DECISION into a pure function, and it becomes headless-testable
  (extends the A8 split to stateful adapters).** A per-item event adapter (`QuestAdded`/`QuestDone`) has two
  parts: *reading the widget* (needs a live UI) and *deciding what changed* (pure). Keeping them fused (the A6
  `KinAdapter` style — read + diff + fire in `refresh()`) leaves the interesting logic — "new **active** quest →
  Added, active→finished → Done, already-finished-at-login → **silent**, removed → prune" — untestable without a
  live `QuestWnd`. Splitting the decision into `questDiff(cache, fresh)` (both `Map<Integer,Integer>` of
  id→status **in**, `{event,id}` pairs **out**, cache mutated) let 13 of 26 checks nail the exact semantics
  headlessly; `refresh()` shrinks to read-the-lists → call `questDiff` → map id→snapshot → `fire`. Same spirit
  as A8's `craftSpec(Spec)`, but for a **stateful** cache diff rather than a stateless snapshot. When an adapter
  fires per-item deltas, make the delta computation a pure map→events function.
- **A9-1 — `public static final int` constants are inlined (JLS 13.1), so a status-mapping helper is
  headless-safe even when its enclosing class's `<clinit>` renders text.** This is the A7/A8 "touching a
  resource-backed class blows up headless" note, but turned to advantage: `QuestWnd.Quest` has a font-rendering
  static block (`catf.render("Quest completed").tex()`), yet `questStatus`/`questActive` reference only
  `Quest.QST_PEND`/`DONE`/`FAIL`/`DISABLED` — compile-time constants folded into the caller's bytecode as
  literals, so invoking them **never loads `Quest`**. The A8 `craftSpec` skip was forced because it needed a
  built `Spec` *object*; here the status codes are constants, so the mapping is fully testable. Rule of thumb:
  a helper that reads only a class's `static final` primitive/String constants doesn't trigger that class's init;
  one that reads a non-constant static field, or takes an instance, does.
- **A10 — headless-testable surface shrinks when a read has no pure transform + its class won't `<clinit>`
  headless.** A read-only, event-less subsystem has no change-detection function (the bulk of A6/A9's headless
  checks) — only null-safety + any pure helper. Here even the pure helper (`deckKey`) is untestable headless
  because touching `FightWnd` at all triggers `Text.<clinit>` → `/res/ui/fraktur.res` (the A8 skip). So the honest
  headless count is small (**4**: the facade wiring + null-guards, which DON'T reference the un-initable class on
  the no-session path); don't pad it — state the resource skip explicitly and lean on in-game + source inspection
  for the literal-backed mapping. The value of the headless pass here is proving the guards return empty/nil
  without an NPE, which it does.
- **4a — headless test isolation: `-Dhaven.prefspec` for prefs, `lib/jglob.jar` on the CP for `OCache.<clinit>`.**
  Testing the gate touches real client prefs → set `System.setProperty("haven.prefspec", "…test…")` FIRST (before
  the first `Utils.prefs()`), then `prefs().remove(key)` for a clean default — real prefs untouched (the 1f-2
  trick). And `moveClickCoord` references `OCache.posres`, whose `<clinit>` scans annotated classes via
  `dolda.jglob.Loader` → add **`lib/jglob.jar`** to the jshell classpath or the class won't initialize (a subtler
  cousin of the A8/A10 font-resource `<clinit>` skip — here the fix is a jar, not a skip). `--execution local`
  ignores jshell's `-R-D…`; set system props inside the script instead.
- **4b — headless-test the PURE policy + the pref round-trip, not the disk I/O (the A9-1 split, again).**
  `scanAddonDefaults()` does the disk/prefs I/O (read manifests → build a `Map<id,declaresActions>`, read/write the
  seen + disabled prefs); the DECISION is the pure `applyActionsDefaults(seen, disabled, declares)` (in-place set
  mutation, returns the write-id set) — so 7 checks nail the exact policy (read-ignored / new→disabled+seen /
  user-enabled-not-re-disabled / additions-only / `writeIds`=all) with no game or disk. The master switch is
  testable against an isolated `-Dhaven.prefspec` node (the 1f-2/4a trick): `setActionsEnabled`/`actionsEnabled`
  round-trip + idempotency, reading the private `reloadNeeded` via reflection (the public `reloadNeeded()` getter
  also works). `AddonManager.<clinit>` is headless-safe with `build/classes` + all `lib/**/*.jar` on the CP (its
  static block only registers `Console.setscmd` handlers; `CFG_ACTIONS`'s `Config.Variable.propb` is lazy). 12/12.
- **4c — encoding: this project compiles source as UTF-8, so non-ASCII string literals are fine.** `build.xml`'s
  `<javac>` sets no `encoding`, so it uses the JDK default charset — which is **UTF-8** on Java 18+ (JEP 400),
  and this client runs on Java 23. Proof it's relied on already: the whole `io.brodgar` tree has non-ASCII bytes
  (AddonManager alone ~1.2 KB), and `AddonPanel`'s 4b tooltip literal already renders an em-dash (`—`) in-game
  (4b verified). So curly quotes `“ ”` / `—` in a *rendered* literal are safe (`Text.std` covers General
  Punctuation) — no need to ASCII-fold. (Still worth a glance if a future contributor builds with an older JDK.)
- **4d (tooling) — jshell hangs waiting on stdin after running a `.jsh` file; redirect `</dev/null`.** A script-file
  run drops into the interactive REPL at EOF and blocks on stdin (a 2-min timeout). Piping `</dev/null` makes it exit
  cleanly. `--execution local` is NOT a fix — it breaks classpath class-resolution (`Could not resolve class …`); keep
  the default forked VM. Headless facade tests need `lib/jglob.jar` on the `-cp` too (`OCache.<clinit>` →
  `dolda.jglob.Loader`), else `moveClickCoord` and friends throw `NoClassDefFoundError` before touching `posres`.
- **4e — VERIFY the sandbox's Lua surface by RUNNING in a real `Sandbox.create()`, don't trust the whitelist doc.**
  `Sandbox.java`'s class-doc lists `unpack` among the whitelisted base fns, but LuaJ 3.0.1 is **Lua 5.2** — there is
  **no global `unpack`**, only `table.unpack` (the `walker` menu demo first used `unpack(path)` and hit `attempt to
  call nil`). Fixed by `table.unpack`. Lesson: an addon's stdlib assumptions must be checked against the *actual*
  sandboxed `Globals` (a 5-line runtime test loading a chunk that calls the fn), because the sandbox is a curated,
  5.2-flavoured subset — not the 5.1 globals many Lua snippets assume. (Stale-doc note: the `unpack` mention in
  `Sandbox`'s Javadoc is aspirational.)
- **A UI slice like U1 is thin on headless coverage by nature** — `onDrop` needs the engine's live drop dispatch, a
  bound `GOut`, and a real menu-grid drag; the pure pieces (`modsTable` bit mapping, the descriptor's resource/id-only
  gate) are trivial. So it ships on compile + LuaJ parse + an in-game DoD (drag an action → icon renders + res logs;
  Shift+click → shift=true), like the other draw/input slices (2a/2b/R1) whose real proof is on-screen.
- **W2 — hit-test logic is headless-testable with a hand-built `Widget` tree (no GL).** A bare `new Widget(sz)` +
  `parent.add(child, coord)` links the `child`/`next`/`prev`/`lchild` pointers with no `ui` and no GL, and
  `visible()`/`checkhit`/`xlate` are pure — so `AddonManager.hitTest` (a private static) can be exercised via
  reflection over an anonymous `Widget` subclass that overrides `checkhit`/`xlate` (12 checks: topmost-first,
  deepest-descent, parent/root fall-back, invisible-skip, `checkhit`-fall-through, a scrolled subtree, subtree-miss).
  This is a rare case where an interactive-looking UI primitive still has real headless coverage — the *dispatch* math
  is pure even though the *hover polling* is in-game-only.
- **F1 — a headless font test needs `-Djava.awt.headless=true`, or `Text.Foundry`'s AWT init hangs forever.**
  Constructing any `Text.Foundry` calls `TexI.mkbuf(...).getGraphics()` → the AWT toolkit; on a headful-defaulting
  JVM with no display that stalls indefinitely (a 2-min timeout, not an error). `jshell -R-Djava.awt.headless=true`
  builds foundries in ~0.5 s. Also: never touch `Text.std` in a headless test — `Text.<clinit>` loads the
  `ui/fraktur` resource (absent headless, the A8/A10 "resource skip"). Test `Fonts.foundry(scope, stock)` with a
  hand-built stock `Foundry` and size-less overrides (a `size` would call `UI.scale` → `UI.<clinit>`), and assert on
  `foundry.font.getFamily()` — that covers resolution/cascade/last-wins/teardown without the engine.
- **F2 — no headless render test: `Text`/`RichText` `<clinit>` loads `ui/fraktur`, absent headless.** Any test
  touching a `Text.Foundry`/`RichText.Foundry`/`Text.std` triggers `Text.<clinit>` →
  `Resource.local().loadwait("ui/fraktur")`, which throws headless (no `res/ui/fraktur.res` in the tree) — the same
  skip A8/A10/R2a documented. So the F2 draw path is compile + LuaJ-parse + **in-game** verified, not headless. (F1
  tested `haven.Fonts` because that class avoids `Text.std` — F2's `FontHandle.rich`/`stockRich` cannot.)
- **Some `haven` classes DO initialize headlessly — test before assuming a resource skip.** F2–F3c all
  recorded "headless resource skip" for their render paths, but with `lib/ext/hafen-res.jar` on the classpath
  `Text`/`RichText`/`ChatUI`/`Widget`/`MenuGrid`/`FlowerMenu` all `<clinit>` fine (only the GL `Tex` upload
  fails), which allowed 24 site-twin + 8 real **render** checks headlessly — far stronger evidence than provider
  arithmetic alone. Try the classpath first; it is cheap.
- **(F5) When a maintainer reports "only X changed", prove the mechanism headlessly before touching it.** A 13-check
  group driving a real `Label` through `restyle()` inside/outside the frame settled it in one run — including the
  hard case (a label created AFTER the override) that the `Spec.stamp` exists for. Cheaper than another in-game round
  trip, and it stays in the suite.

- **(018.4) Addon Lua has a real compile check — use the bundled LuaJ jar.**
  `java -cp lib/brodgar/luaj-jse-3.0.1.jar luac -p addons/<id>/main.lua` parses the chunk with the *same*
  compiler the client runs and exits non-zero with the line number on a syntax error (verify the check
  itself once with a deliberately broken file — a silent exit 0 proves nothing). It writes `luac.out` in the
  cwd, so `rm -f luac.out` after. This catches the whole class of typos that would otherwise cost a client
  restart, and it is the only pre-check available for a Lua-only task where `ant hafen-client` compiles
  nothing new.
- **(019.7) A `git worktree` of an older commit will not build: some jars are not in git.** Building a
  pre-feature client to A/B against needs `lib/brodgar/brodgar-voice-all.jar` copied in by hand — it is
  untracked, so the worktree gets only what `ant get-luaj` fetches, and the build dies with a wall of
  "cannot find symbol: class VoiceListener" from `Voice.java`/`OptWnd.java`. `git status` looking clean says
  nothing about it. `git worktree add <scratchpad>/<name> <commit>` + copy the jar + `ant hafen-client`, then
  `git worktree remove` when done so no stale entry is left in the repo.
- **A whole Lua-facing NAMESPACE can be pre-checked headlessly, and a null `GameUI` is the useful
  fixture** (020.1, extends the throwaway-in-scratchpad pattern above). `JsePlatform.standardGlobals()` +
  `new Addon(Manifest.internal("x"), Paths.get("."), env)` is enough to install one namespace's factory
  (`LuaKin.factory(owner)`) and drive it from real Lua source — no client, no HUD. `Manifest.internal`
  grants ALL permissions, so the **gated** verbs run too and their `requireActions` path is exercised.
  With no `GameUI` every widget lookup returns `null`, which is not a limitation but the point: it is
  exactly the "not in the world yet" branch of every method, so one run verifies arity dispatch,
  interning (`==` and as a table key), userdata immutability, the empty-collection path, and the exact
  TEXT of every guiding error — the things that are tedious to re-check in-game. What it cannot see is
  anything needing live data (freshness after a real change, the write actually reaching the server), so
  the in-game list shrinks to those. Put the harness class in the same package under the scratchpad and
  `javac -cp build/classes;<luaj jar>` it: package-private constructors then work with no reflection.
- **The console blips on its own: never A/B audio with a `:lua` line that returns a value.** `eval` prints the
  result with [`UI.msg(String)`](src/haven/UI.java:869) → [`InfoMessage`](src/haven/UI.java:823), whose `defsfx`
  is `sfx/msg`. So `:lua sound:play():stop()` — which returns the Sound — plays the *console's* blip even when
  the addon's clip was correctly cancelled, and it reads exactly like the bug you are hunting. It cost a full
  verification round on 024.2. Use the statement form (`:lua local s = sound:play():stop()`), which returns
  nothing and prints nothing; or check the terminal, where the trace, not the ear, is authoritative.
- **When a verification contradicts the code, instrument before re-reading the code.** Four temporary
  `System.out.println` lines (play / stop / silence / resolve, each with `Thread.currentThread().getName()`)
  answered in ONE round what two passes of re-reasoning had got wrong — and the answer was that the engine was
  right and the *test* was lying. Print the thread name: for anything deferred, the ordering IS the diagnosis.
- **(028.1) The `ui/fraktur` `<clinit>` wall (A8/A10) is a CLASSPATH problem, not a hard skip — add the res
  jars.** `haven.Text.<clinit>` does `Resource.loadwait("ui/fraktur")`, which is why touching anything that
  renders text was written off as headless-untestable. It resolves fine once the resource jars are on the
  classpath: `bin/builtin-res.jar` + `bin/hafen-res.jar` (or `lib/ext/*.jar` before `ant bin` has copied them).
  Full recipe for a same-package scratch `main`: `build/classes` + **all** of `lib/*.jar lib/brodgar/*.jar
  lib/ext/*.jar bin/*res*.jar`, run with `-Djava.awt.headless=true`. The client still prints
  `haven.iosys.Unavailable: could find no working toolkit` and a `GLException` trace while it hunts for a
  toolkit — both are **caught**, and execution continues; do not read them as the test failing. That turned
  028.1's font half from "verify in-game" into 53 headless asserts.
- **(028.1) Drive the Lua contract through a real `Sandbox.create()` env, not just the Java entry points.**
  A second scratch class that installs the namespaces into a `LuaTable`, sets it as `hafen` in the sandbox
  globals and runs one `g.load(src).call()` chunk is what actually proves `==` interning from Lua,
  `seen[asset]` as a table key, `pcall` over a callable table, and that a cut surface reads `nil`. Those are
  the assertions the harness addon would otherwise be the first thing to run — and it costs one file.
  MSYS note: a Git-Bash `/c/...` path in a `-cp` string silently yields `ClassNotFoundException`; use `C:/...`.
- **(029.1) A real `haven.UI` can be constructed headlessly, which makes the whole widget tree assertable.**
  Everything W-series — interning, `==`, liveness via `hasparent(ui.root)`, staleness, the no-pin rule — needs
  a `ui.root` to hang widgets off, and that used to read as "verify in-game". It is two workarounds: put
  `bin/builtin-res.jar` on the classpath (`Text.<clinit>` does a `loadwait("ui/fraktur")` and dies without it)
  and hand `new UI(...)` an `Audio.Root` allocated via `Unsafe.allocateInstance` with a bare `Mixer` reflected
  into its final `mixer` field — the real constructor opens a sink line. Full recipe + anchors in
  `specs/codebase/boot-and-loop.md`. 22 asserts, including a GC-based no-pin proof, for one scratch file.
- **(029.2) A bare `new Widget(Coord)` is a fine stand-in for the window chrome `haven.Window` cannot be
  headlessly.** `Window` needs GL (3c already knew), but nothing about the owned/borrowed logic cares *which*
  class the chrome is — only that the addon's `AddonWidget` sits inside it with its `root` pointing back. So
  `chrome.add(content, Coord.z); content.root(chrome)` reproduces the whole `hafen.ui.window{}` shape headlessly,
  and `:pos(x,y)`/`:size(w,h)`/`pack()` are assertable on it. 49/49 on top of 029.1's off-screen `UI` recipe,
  including the relog guard (build a SECOND `UI`, point `AddonManager.ui` at it, assert the restore is skipped).
  `LuaValue.method(name, a, b)` is the 3-arg colon call — no need to hand-build a `Varargs` for a two-arg verb.
- **(030.4) A `hello` harness function can be dry-run against a STUB `hafen` bridge, with no client and no engine.**
  `jshell --class-path lib/brodgar/luaj-jse-3.0.1.jar` + `JsePlatform.standardGlobals()`: `g.loadfile(path)` alone
  is a syntax check for every addon (it compiles without executing), and `sed`-ing the one function out of
  `main.lua` in front of a 40-line fake `hafen.ui` (a callable table over a 5-widget array) runs it for real. That
  catches the failure mode this kind of code actually has — a `string.format` whose argument count drifted from its
  placeholders, which throws at the *end* of a long log line and would otherwise surface as a broken login. Every
  branch of a 100-line contract check verified in seconds, before the maintainer ever logs in.
- **(030.4) In a GitHub anchor slug an em dash DROPS ENTIRELY — ` — ` becomes `--`, not `---`.** The link/anchor
  checker over `docs/addons/` reported 36 false positives until the slugger stopped mapping `—`/`–` to `-`:
  spaces become dashes, every punctuation character (dash included) is deleted. So `## Selectors — naming a
  widget` is `#selectors--naming-a-widget`, and the double dash that looks like a typo in the link is the
  correct spelling. Same rule explains `#the-built-ins--hafenfontname` from `` ## The built-ins — `hafen.font(name)` ``.
- **(031.3) Stub the engine's RULES, not just its reads, and the dry run finds state bugs — including a Lua
  one-liner.** 030.4's stub answered lookups; 031.3's had to *behave*: a widget table with `hide`/`show` over an
  ownership map (one window one owner, `:show()` drops the record) plus a `hafen.ui.replace` whose `:remove()`
  applies the real restore rule. The function under test is sliced out of the live `main.lua` by string search
  (`local swallowedWnd` → the next `hafen.events.on("OnEnterWorld"`) and `load(chunk, name, "t", env)`-ed against
  it, so it can never drift from what ships. Five scenarios (normal HUD · window open · owned by another addon ·
  replace finds nothing · pre-HUD) / 18 assertions ran in a second under `java -cp lib/brodgar/luaj-jse-3.0.1.jar
  lua`, and caught a real defect: **`local x = (cond ~= nil) and w:visible() or nil` collapses a `false` to
  `nil`** — the classic Lua and/or trap, and it silently turned an *expected-false* assertion into `visible=nil`
  in the login log. Any harness line that reports a boolean has to use an `if`, not `and`/`or`.
- **(032.1) `haven.Window` DOES construct headlessly — put `bin/hafen-res.jar` on the classpath.** This corrects the
  029.2 note above ("`Window` needs GL"): the blocker was never GL, it was `Window.<clinit>` doing
  `Resource.loadtex("gfx/hud/wnd/lg/bg")`, which `bin/builtin-res.jar` alone cannot satisfy. `loadtex` hands back a
  lazy `Tex` — GL is only touched when something *draws* — so with both res jars on the classpath a real
  `GameUI.Hidewnd(Coord.z, "Inventory")` builds off-screen and behaves exactly as in-game. That upgrades every
  headless widget test from "a bare `Widget` stands in for the chrome" to the actual chrome, and it is what let
  032.1 verify the selector, the enclosing-window hop, the toggle seam and the D-070 restore rule before the
  maintainer logged in: 46 assertions, one scratch file, `new UI(...)` + `new AddonWidget(owner, sz, new LuaTable())`
  as the view (its ctor is package-private, so the probe declares `package io.brodgar.addon`).
  Classpath: `build/classes;bin/hafen-res.jar;bin/builtin-res.jar;lib/brodgar/luaj-jse-3.0.1.jar;lib/jglob.jar` plus
  `build/{jogl-all,gluegen-rt,lwjgl-fat,lwjgl-awt}.jar` for `UI.initscale` (its `Unavailable` traces are caught).
- **(032.1) A headless pass is not the measurement.** The same probe "confirmed" `inventory[title=Inventory]` on a
  hand-built two-window tree; the thing that actually settled it was `widgetstack`'s inspector on a live 600-widget
  HUD, which additionally showed `inventory` = 4 matches vs `@Inventory` = 3 (the `Equipory` classifies as
  `inventory` too) and `[title=Inventory]` = 32 (every widget *inside* the wrapper answers the refiner). Build the
  probe to shrink the risk, then still take the measurement.
- **(032.2) Load the SHIPPING addon file whole against the rule stub when you can — slicing is the fallback.**
  031.3 sliced `readToggle` out of `hello/main.lua` because that file needs the entire API to load; `bags` is
  small enough that `loadfile("addons/bags/main.lua")()` runs the real thing, so the dry run exercises the
  addon's *actual* control flow (its hotkey closure, its `onClose`, its refusal branch) instead of a transcription
  of it. Feed it `hafen` as a plain **global** rather than an env table: LuaJ 3.0.1 is Lua 5.2, so there is no
  `setfenv`/`loadstring` (the 5.1 idiom every snippet reaches for), and scenarios run sequentially anyway.
- **(032.2) A rule stub catches REPORTING bugs, not just state bugs — and those are the ones in-game testing
  glosses over.** 11 scenarios / 42 checks over the ported `bags` + `readToggle`; the one failure was a log line:
  a replacement refused by another owner fell through to `if not grid then log("no inventory in the tree yet")`,
  because "nothing was replaced" and "nothing was there" are the same absence. The fix is to gate the fallback on
  what actually *happened* (`seen`, set when the subscription matched at all), not on the absence of a result.
  Nothing would have crashed in-game; the addon would simply have lied about a window sitting in plain sight.
- **(032.3) Assert a relationship from BOTH ends — that is what turns a documented rule into a checked one.**
  `hello`'s login check installs a view through the inventory **grid** and then reads it back through the
  **enclosing window** (`grid:replace() == view` *and* `wnd:replace() == view`): one record, reached from either
  side of the enclosing-window hop, proved from Lua instead of described in a comment. It keeps the headless stub
  honest too — a stub that mints a fresh table per read passes every `== nil` assertion and fails only this one.
  5 scenarios / 24 checks on `readToggle` sliced out of the shipping `main.lua`, in a second.
- **(032.3) The docs link/anchor checker is re-derived every close, and that is fine — the SLUG RULE is the part
  worth keeping.** ~60 lines of Python over `docs/addons/` (fenced blocks skipped, relative paths resolved,
  fragments matched against slugged headings) reports 0 broken over **557** links; rebuilding it costs minutes,
  where getting 030.4's em-dash rule wrong costs 36 false positives.
- **(033.3) Drive the SHIPPING suite headlessly through the slash command it registers — no slicing, no stub.**
  031.3/032.3 sliced a function out of `hello/main.lua` because that file needs the whole API; a per-task suite
  (`TESTING.md`) is small enough that the *real* namespaces load: `Sandbox.create()` + `new Addon(Manifest
  .internal(id), dir, env)` + `AddonManager.installHafen(g, owner)`, then `g.load(src).call()`. The suite's `run`
  is a local, but it hands itself to `hafen.slash.register("t033-3", run)` — so a same-package probe reads
  `owner.slashCommands` and calls `c.fn` directly. `AddonManager.fireTo(owner, "OnLoad")` supplies the login
  event an addon that builds state in `OnLoad` needs (without it the `theme` example reported "nothing loaded"
  three times and nothing else — a silent pass that looked like a bug in the addon). `AddonManager.log` prints
  to stdout with the `[id]` prefix and tolerates a null `ui`, so the pasted-back format is byte-identical to
  the in-game one: **the pre-check output IS the deliverable's output**. 18/18 before the maintainer logged in.
- **(033.3) There is no Python on this machine — the docs link/anchor checker was re-derived in JAVA, and
  `java Foo.java` runs a single file with no compile step.** 032.3's note ("~60 lines of Python, rebuilt every
  close") assumed an interpreter that is only the Microsoft Store stub here (`python3` prints an install
  message; `python -c` exits 0 having done **nothing**, which is the dangerous half — it looks like it worked).
  The keeper is still the **slug rule**, and it is GitHub's, not the 030.4 shorthand: lowercase, **delete every
  character that is not `[a-z0-9 _-]`** (so an ASCII hyphen SURVIVES and an em dash vanishes), then spaces →
  hyphens; duplicate headings get `-1`, `-2`. Skip fenced blocks in both the link scan and the heading scan.
  **Always plant a bad link and a bad anchor and re-run** — a checker that reports 0/600 without ever having
  been seen to fail proves nothing (the `luac -p` lesson, again).
- **(034.1) A suite that builds windows cannot be dry-run — so pre-check the ENGINE surface instead, and a
  `Label` is the headless widget that classifies.** 033.3's trick (load the shipping suite under
  `Sandbox.create()` and call the slash command it registers) stops working the moment a suite calls
  `hafen.ui.window{}`: `haven.Window` needs GL. What still works is a same-package probe that drives the real
  namespaces (`installHafen` on TWO addons, `hafen.ui.skin{…}` from real Lua) over a hand-built tree of
  `new Widget(Coord)` subclasses — nested `public static class PA extends Widget` gives `@PA` a stable
  `typeName`. Bare widgets classify as nothing, so a rank-ordering test looks impossible; **`new haven.Label("hi")`
  builds headless** (needs `bin/builtin-res.jar;bin/hafen-res.jar` on the classpath — `Text.<clinit>` loads
  `ui/fraktur` — plus `lib/jglob.jar` and `-Djava.awt.headless=true`), and it answers role `label`, which makes
  `label@Label` (3) vs `@Label` (2) a real specificity check with no client. 22/22 before the maintainer logged
  in; the shipping suite is then still worth *loading* headlessly, which proves it parses and registers.
- **(034.1) `python - <<EOF` HANGS on this box** — the Store stub reads stdin and never returns (a 2-minute tool
  timeout), which is worse than the known `python -c` no-op. There is no Python here: use `perl -0pi -e`, a
  throwaway Java file, or just the editor.
- **(034.2) The DRAW seam is headless-testable even though drawing is not — call the frame yourself.** 034.1
  concluded a suite that builds windows cannot be dry-run; the engine surface beneath it still can, and this time
  that surface *is* the feature. A same-package probe builds a tree of `new Widget(Coord)` + `new haven.Label`,
  installs sheets from **real Lua** through `installHafen` on two addons, then does exactly what
  `Widget.draw`'s child loop does — `try(Fonts.Frame f = Fonts.frame(w)) { Fonts.foundry(scope, stock) }` — and
  asserts on the returned foundry: its family, its `defcol`, its `fixcol` (package-private → one reflective
  getter), and its **identity** (`== stock` is "this client is byte-for-byte stock"; `== the previous one` is
  "nothing re-derived"). 28/28 before the maintainer logged in, covering composition, specificity, teardown and
  two addons — none of which needs a pixel.
- **(034.2) Plant the bug you are most afraid of, not a generic one — the two falsifications disagreed, which is
  the point.** Removing the style interning failed exactly ONE check ("a widget created after the rule still
  resolves it"); making it mint a Spec per call failed FOUR, the stamp-stability pair among them. Had only the
  first been tried, the greenness of the stamp checks would have looked like coverage it did not have. A
  falsification tells you which mechanism guards which claim, so run one per mechanism, not one per file.
- **(034.3) `haven.UI` does NOT build headless — the 032.1 note needs its other half.** 032.1 recorded that a real
  `UI` constructs off-screen once `bin/hafen-res.jar` is on the classpath; what it did not record is that the
  constructor also does `new ActAudio.Root(audio)`, which **NPEs on `sys.mixer`** when `audio` is null. So a probe
  that needs a live tree needs a real `Audio.Root` (which opens a device) — out of reach for a throwaway. The
  consequence is worth knowing before you plan the pre-check: anything behind `LuaWidget.live()` (every WRITE verb
  on a widget) is **in-game only**, because `live()` answers "unresolvable" while `AddonManager.ui` is null. What
  is still reachable without a UI: the method table itself (a hard cut reads `nil`, the new verb is a function),
  every refusal (parse the argument BEFORE looking at the widget and a bad call errors either way — worth doing
  for that reason alone), and the whole engine layer beneath, driven directly.
- **(035.1) A real `haven.Window` DOES build headless — leave it PARENTLESS.** 034.1 recorded that a suite
  building windows cannot be dry-run, and 034.3 that `haven.UI` needs a real `Audio.Root`; neither means
  `Window` itself is out of reach. `new Window(Coord, cap, lg)` constructs fine (its `<clinit>` eagerly
  `loadsimg`s the chrome, so `bin/hafen-res.jar` must be on the classpath) and the whole deco path —
  `chdeco`, `contarea`, `iresize`, `visible()`, `show`/`hide` — works on it. What does NOT work is **adding it
  to anything**: `Window.added()` does `parent.setfocus(this)`, which NPEs unless the parent has a parent of
  its own. A parentless window is enough for every selector (a selector matches a widget, not a position) and
  for driving a per-window tick decision directly. 63/63 before the maintainer logged in.
- **(035.1) A falsification can prove a check is HOLLOW, which is worth more than proving it green.** Three
  planted bugs, each expected to redden a different claim: dropping `combine`'s new properties → 2 red (the
  per-property fold), guarding the restore on `visible()` → 1 red (a hidden window kept its skin). The third —
  removing the identity fast path for a chrome-only rule — came back **0 red**, because the check was asserting
  on a scope the rule never cascaded through. The bug was in the test, and only the falsification could have
  said so; a green run reports the same thing whether it is guarding something or nothing.
- **(035.1) `git-bash` `perl -0pi -e` is the reliable in-place editor for a throwaway probe** (no Python on this
  box, per 034.1). For a Java source file, remember the probe's own compile is the syntax check — a botched
  substitution shows up as a `javac` error immediately, so rebuild after every edit rather than batching them.
- **(035.2) A login is ONE client, so the round is a sequence of SLOTS — a suite's writes are visible to every
  other suite.** 035.2 started at +3.5 s, on top of 035.1's +3 s slot, and produced three `[fail]` lines of
  which **none was in the task under test**: 035.1 counted this suite's still-skinned probe window in its
  `#hafen.ui.all("@SkinDeco") == 0` restore check, and 034.2's text-cache probe saw a **second key** for its own
  string because this suite was bumping `Fonts.gen()` a dozen times while it ran. The convention that already
  existed in comments (+3 · +6 · +9, "after the other suites' round") is load-bearing and now carries +12. Rule:
  **a suite that installs a sheet, keeps a styled widget alive, or bumps a global generation owns its slot
  alone** — and when a suite reddens, read the *addon id on the line*, then ask what else was running.
- **(035.2) A staged suite must WAIT FIRST — a step that runs inline with the write it checks reads the previous
  frame.** The sequencer was `f(); timer.after(0.35, next)`, so `step(1)` executed in the same tick as the
  `run()` that made the write, and only steps 2..n ever got their tick. The failure is maximally confusing: the
  *first* stage fails and every later one passes, which reads like a warm-up problem rather than an off-by-one.
  Write it as `timer.after(0.35, function() f(); next() end)` so the delay is a property of the step, not of the
  gap between steps. Headless pre-checking cannot catch this — a probe calls `SkinDeco.check(wnd)` itself.

- **(035.3) A suite that starts itself needs a schedule, and the schedule is the bug.** Per-task suites used to
  auto-run on `OnEnterWorld` + a timer, so each needed a *slot* (`+3`, `+6`, `+9`, `+12`…) to stay out of the
  others' way: every suite installs a client-wide sheet and bumps `Fonts.gen()` while it runs, and 034.2's last
  check counts text-cache keys for one string — key `(string, font, Fonts.gen())` — so any other suite skinning
  something mid-round makes it read 2 where it expects 1. 034.2 staged 3.4 s from `+6` and 034.3 started at
  `+9`: a **0.4 s overlap**, i.e. a race that passed for two whole features and then reddened a line in a suite
  nobody had touched. Two `/implement` rounds were spent moving constants. The fix was to delete the category:
  **suites now run only on their slash command** (`:t<NNN>-<X>`), the regression is typing them one at a time,
  and the single ordering rule left ("let a staged suite finish") belongs to the operator. Generalisation worth
  keeping: *when a test harness needs a schedule to avoid itself, remove the auto-start, not the overlap.*
- **(035.3) A red line in a suite that is NOT the task under test is diagnosed by running that suite ALONE.**
  `:t034-2` on its own was green, which distinguished "the schedule collided" from "035.3 broke the mechanism"
  in one command — cheaper and more conclusive than any amount of reasoning about the change. Do that before
  touching either the suite or the code.
- **(035.3) A same-package Java probe can drive the real sheet parser with NO Lua source file.** Build a
  throwaway `Addon` (`new Addon(Manifest.internal(id), Paths.get("."), Sandbox.create())`), hand-build the sheet
  as a `LuaTable` and call `Sheet.skin(addon, table)` — the real `Selector.parse`, `siteOf` and `propsOf` all
  run, so key classification, the `*` cascade, per-property folding, two-addon last-wins and teardown are all
  headless. A `LuaImage` needs no GL either: `new LuaImage(owner, name, new TexI(new BufferedImage(w,h,…)))`
  and wrap it as `LuaValue.userdataOf(img)` under the `LuaImage.KEY` field, which is exactly what
  `Chrome.parseBorder` resolves. 36/36 for 035.3 this way. `haven.Frame`/`ISBox`/`Window.wbox` all `<clinit>`
  fine with `lib/ext/hafen-res.jar` on the classpath (the F3 note), so the probe can measure real stock boxes.
- **(035.3) Expose the RULE as a named method rather than an inline branch, and the probe can assert it without
  a `GOut`.** `SkinBox.drawbg` needed a real graphics context to test directly; splitting the "where does the
  background stop" decision into `bgul(tl)`/`bgsz(sz)` made both branches assertable as plain `Coord`s, cost no
  allocation (the `Coord` arithmetic was already there), and named the rule in the source. Falsifying the two
  branches turned 4 checks red, which is what proved they were load-bearing.
- **(035.4) Slice the ADAPTER, not the round — a suite that builds windows can still be half dry-run.**
  034.1 recorded that a suite calling `hafen.ui.window{}` cannot be loaded and driven headlessly, and left it
  there. What still works is slicing the *pure* part out of the SHIPPING `main.lua` by its own banner comments
  (`indexOf("-- ---- the file → a sheet")` … `indexOf("-- ---- the run")`), concatenating a driver onto it and
  running that under `Sandbox.create()` + `installHafen` — so the adapter under test is the one that ships, not
  a re-typed copy, and a marker rename fails loudly instead of silently testing nothing. The engine half is then
  driven separately over a real parentless `haven.Window` + `SkinDeco.check`, and the two together cover
  everything except frame timing: 18/18 for 035.4. Falsifying both halves bit differently — removing the border
  mapping **crashed** the Lua half (a path string has no `:type()`), while making `SkinDeco` ignore `pad` turned
  exactly the 2 geometry checks red.
- **(035.4) A per-frame cost comparison must stand up its OWN scene.** Measuring "themed vs stock" against
  whatever windows the maintainer happens to have open makes the two halves incomparable, and on a bare HUD it
  can dress **zero** windows and measure nothing while still printing a pass. The cost round opens four windows
  of its own, samples stock, applies the sheet, samples again — the same scene twice — and prints both medians
  inside the verdict line so the number travels with the claim. It came back at the noise floor (1.574 vs 1.574
  ms; 1.600 vs 1.782 on a second run, the themed client *cheaper*), which is why the load-bearing assertion is
  the categorical one beside it: this addon's own `addons()` row must read `calls.draw + calls.widgets == 0`
  while the chrome paints. The sampling timer is charged to `timers` and never to `draw`, which is what keeps
  that check clean — a measurement that ran from an `OnUpdate` would have been charged to the thing it measures.

- **(036.1) A same-package probe can fabricate the `UI` the code under test only *reads*.** A real `haven.UI`
  needs an `Audio.Root`, but `stillMovable`/`live()`/`widgetid` touch exactly three fields: `Unsafe
  .allocateInstance(UI.class)`, reflectively set the private final `widgets`/`rwidgets` maps (a
  `setAccessible(true)` `Field.set` still works on non-static finals), and point `root` at an
  `Unsafe`-allocated `RootWidget`. Then wire the tree by **assigning the public `Widget.parent` field** instead
  of calling `add()` — that skips `added()`, which is what makes a `haven.Window` un-buildable headlessly
  (035.1) — and `hasparent(u.root)` is satisfied. With that, the probe drives the **real Lua verbs**
  (`LuaWidget.of(owner, w)` then `self.get("pos").invoke(...)`) rather than the Java behind them: 49/49 for
  036.1, including the whole record/restore/teardown/two-addon matrix that the in-game suite can only sample.
- **(036.1) A negative hit-test assertion must leave the widget's own rectangle.** The suite picked its target
  dynamically and moved it 24/18 px, then asserted `hafen.ui.at(old centre) ~= w` — which failed in-game on a
  widget wider than the move, because its old centre was still inside it. The fix is to probe the old
  **top-left + 2** (a corner any move of ≥ 3 px leaves) and to have the picker require `at()` to resolve to the
  candidate at *both* probe points before accepting it. Generalisation: when a check is "it is no longer here",
  derive "here" from a point the move provably vacates, and make the self-validating pick prove that too — a
  dynamically chosen target can otherwise redden on someone else's HUD for a reason that is not the feature.

- **(036.2) The fabricated `UI` is enough to run the SHIPPING suite end to end — not just the engine under it.**
  036.1's probe drove the real Lua verbs over `Unsafe`-allocated `UI`/`RootWidget`; 036.2's went one step
  further and `call()`ed the slash handler the shipping `main.lua` registers, against two real `haven.Window`s
  attached by assigning `parent`. The suite's own self-validating pickers (a uniquely-titled window; one the
  client lets you resize) find those windows exactly as they find HUD windows, so **19/19 of the in-game
  verdict lines were green before the client was started**, and the in-game run confirmed the same 19. Two
  fields make the difference: set `w.ui` as well as `w.parent` (`:info()` calls `wdgid()`), and add the suite's
  `Addon` to `AddonManager.addons` or nothing that scans the live owners can see its records. Generalise: a
  suite whose targets are *picked* rather than *named* is the one kind that can be dry-run against a fabricated
  world — which is a second reason to write the picker.
- **(036.2) Falsify each seam separately and they redden differently, which is the point.** Removing the
  hand-named level → 2 red, removing the re-fold after teardown → 1, removing the placement seam → 2. Three
  distinct signatures over 44 checks: each seam is load-bearing for a *different* claim, and a single shared
  count would have proved only that something was wired.

- **(036.3) A suite that builds windows CAN be dry-run — 034.1's limit is two missing fields wide.** That note
  ("`haven.Window` needs GL") was really about `Window.added()`, which focuses into the root: over the fabricated
  UI it NPEs. Two additions make the whole shipping suite run end to end, `hafen.ui.window{}` and all — allocate
  the root as a **subclass of `RootWidget` that overrides `setfocus` to nothing** (Unsafe-allocated, so the ctor
  never runs), and reflect-set `UI.grabs` to an empty `CopyOnWriteArrayList` (`:destroy()` walks it). 21/21 of
  036.3's in-game verdict lines were green before the client started, and the in-game run printed the same 21.
  `initanim()` sets `animst = "show"` synchronously, so a freshly added window is `visible()` with no ticks.
- **(036.3) Hand-wiring a widget tree must maintain `lchild`, not just `child`/`next`.** Draw walks
  `child→next`, **hit-testing walks `lchild→prev`** — so a probe that only sets `child` has a tree every read
  sees and `hafen.ui.at()` finds nothing in, and worse, a real `Widget.add()` into that parent takes `link()`'s
  `lchild == null` branch and **overwrites `child`**, silently unlinking everything attached by hand. Mirror
  `link()`: append at the end and set `lchild`.
- **(036.3) The dry run caught a bug in the SUITE that would have reddened in-game — a window's corner is not
  hit-testable.** The reachability check probed the window's top-left + 2; `Window.checkhit` delegates to
  `DefaultDeco.checkhit`, which owns the caption strip and the content area and **not** the transparent pixels
  between them. Probing the addon's own content child (found by `:type() == "AddonWidget"`) is the honest way to
  ask "can the client's dispatch still reach this window", and it works wherever the clamp put it.
- **(036.3) Falsify with a file copy, never `git checkout` — the revert wiped the whole task's work.** Four
  falsifications on one uncommitted file: `cp $F $SCRATCH/f.good` first, restore with `cp`, and remember that a
  multi-line `perl -0pi -e` pattern needs `\r?\n` on this box (the sources are CRLF) or it silently matches
  nothing — which reads exactly like a falsification that did not bite. The four bit 3 / 1 / 22 / 2 red: the
  clamp, the synchronous dependents, the parent-frame conversion, the per-tick re-derive.

- **(036.4) A callback-count check must OWN its scene.** 036.4's cost round asserts *"this addon ran 0 draw/
  widget callbacks while the client painted"* — and its first in-game run reported **2**, the only red in the
  task. Both were honest: the round before it stands up two probe windows whose entire job is to `g:text` every
  frame (the text-cache half), and `costRound` had not destroyed them. A counter cannot tell one of your own
  drawing widgets from an engine path calling you back, so a "nothing of ours ran" assertion has to begin by
  clearing the screen of everything of yours that is *supposed* to run. Generalise: a categorical zero is only
  as good as the scene it is measured in, and the scene must be built by the check, not inherited from the step
  before it. (035.4's version of this rule was about the *comparison* — stand up your own windows rather than
  measuring whatever is open; this is the same rule one step further: tear down the previous round's too.)
- **(036.4) The per-addon text cache measures "did this reach the DRAW?" with nothing armed.**
  `profiling():textcache()` is pull-only **and per addon** ("the top level is the CALLER's own cache"), so no
  other addon can perturb it — which makes 034.2's one-string-two-windows trick the whole cost proof for a
  property that is a *write*: with a layout-only sheet naming one window the two still share **ONE** key, and
  the same rule with a `font` in it takes **two**. Note the number to assert is one, not zero: `Sheet
  .rulesChanged` always ends in `Fonts.treeActive(…)`, which bumps `gen` unconditionally, so **any** sheet
  change re-keys everything once. What the layout-only case buys is that no per-widget *frame* opens, and the
  count then stops moving — assert both, plus the font variant, or "still one key" cannot fail.
- **(036.4) The fabricated-UI probe drives an EXAMPLE addon as happily as a suite** — load `addons/theme`,
  `AddonManager.fireTo(a, "OnLoad")` (the registry does that, `Addon.run()` does not), then call its
  `slashCommands` entries. Two conveniences make it painless: `a.env.load("return " + src).call()` reads any
  state back out of the addon's own sandbox, and **`-Dhaven.savedatadir=<scratch>` isolates `hafen.store`**, so
  a store round trip can be asserted without writing into the repo. 10/10 for `theme`'s layout + save/pin/forget
  matrix before the client started.
- **(036.4) There IS Python on this box now (3.14).** 033.3 recorded "no Python here" and re-derived the docs
  link/anchor checker in Java; it is a 40-line Python script again (GitHub's slug rule: lowercase, drop
  everything but word chars/space/hyphen, spaces → hyphens — so an em dash leaves **two** hyphens). Still
  self-verify it against planted breaks each time: 693 links, 0 broken, 3 planted, 3 caught.
- **A falsification only bites if the FIXTURE forces the two behaviours apart (037.1).** The probe for
  "a write reaches ALL of a resource's settings" was built on a deliberately *mixed* pair (one shown, one
  not) because that same pair proves the ANY-read beside it — and the partial-write falsification then
  **passed**, because touching whichever setting happened to be first still reached the asserted end state.
  The fix is not a better assertion but a better premise: **normalise the fixture so both agree before the
  write**, and assert both directions (all-true → write false, all-false → write true); a partial write then
  leaves a disagreement whatever the iteration order, and the falsification bit 2 red deterministically. Read
  generally: when a check says *"every X took the value"*, start from a state where **no** single X can
  satisfy it alone — otherwise the test is measuring `HashMap` order.
- **A debounced persist lands on ANOTHER thread — settle before counting it (037.1).** `GobIcon.Settings
  .dsave()` is `Defer.later(this::dsave0)`, so the no-op `save()` sink (the subclass trick above) increments
  *after* the call returns. A `saves == 1` assertion straight after the write is a race that passes on a warm
  JVM and reddens on a cold one; and the twin assertion — *a write that changes nothing does NOT persist* —
  needs the same settle or it passes for the wrong reason (nothing has had time to fire yet). One
  `Thread.sleep(250)` helper before each count, `>= 1` rather than `== 1` where the debounce may coalesce.

- **(037.2) A whole on-disk subsystem is headless-testable if you give it an in-memory `ResCache`.**
  `MapFile` takes its store as a constructor argument (`new MapFile(cache, "")`), so a 10-line `ResCache`
  over a `HashMap<String, byte[]>` — `store()` returns a `ByteArrayOutputStream` whose `close()` files the
  bytes — gives you the **real** engine: real `Grid.save`/`Grid.load`, the real `BackCache`es, the real
  `Defer` load, the real read/write lock. Build the fixture through the engine's own doors (`grid.save(file)`,
  `gridinfo.put`, `segments.put` under the write lock); only `Segment.map` (private, coord → grid id) needs a
  reflected `put`. That turns "verify the load model in-game" into an assertion: the FIRST `gridDataIn`
  answers null, and a poll a few ms later answers — the exact behaviour the API promises.
- **(037.2) `MapApi.mapfile()`/`sessloc()` resolve through `gui()`, and a fabricated `GameUI` is enough.**
  Extending 036.1's recipe one class further: `Unsafe.allocateInstance` a `UI`, a `RootWidget`, a `GameUI`
  **and a `MiniMap`**, reflect-set `MiniMap.file` (public **final** — a `setAccessible` `Field.set` still
  works on a non-static final) and `UI.root`, assign `gui.parent = root; root.child = root.lchild = gui`,
  point the static `AddonManager.ui` at it. `AddonManager.gui()`'s fallback scan finds the `GameUI` by
  `instanceof`, and `MapApi.mapfile()` takes the `g.mmap.file` branch because the Unsafe-allocated `GameUI`
  has a null `mapfile`. With `mm.sessloc = new MiniMap.Location(seg, tc)` set by hand, the *entire* Lua
  surface runs headlessly — 55/55 for 037.2, including the anchor round trip. Neither `MiniMap` nor
  `GameUI` needs its constructor, so neither needs GL.
- **(037.2) A round trip through `floor()` cannot prove where inside the cell you are.** The anchor
  falsification — pointing `marker:anchor()` at the tile's **corner** instead of its centre — left the
  round-trip check ("the anchor lands back on the tile the marker reports") **green**, because
  `math.floor(x / tilesz)` erases the half-tile either way. Exactly one check reddened: the explicit one
  asserting the anchor's own numbers. Generalisation of 035.1's hollowness lesson: *a check that quantises
  its input cannot guard anything finer than the quantum* — so where sub-cell placement matters, assert the
  raw value beside the round trip, not instead of it.

- **(037.3) A resource that exists only for the test: `Unsafe`-allocate the `Resource` AND its layer.** The
  overlay resources (`ols/*`) come from the server, so no res jar has one — but nothing in the read path
  needs a real resource. `Unsafe.allocateInstance(Resource.class)` + reflect-set `name` and the **protected
  `layers`** collection, put an `Unsafe`-allocated `MCache.ResOverlay` in it with its public final `tags`
  reflect-set, and `res.flayer(ResOverlay.class).tags()` answers. Point a `Resource.Saved` at it by
  reflect-setting its private transient `loaded` and `get()` never touches a pool. Two variants come free
  and are worth building: a resource with an **empty layers list** exercises the `NoSuchLayerException`
  branch, and a `Saved` subclass whose `get(int)` throws `haven.Loading` exercises the load model.
- **(037.3) A disk round trip rebuilds what you fabricated — patch AFTER the load, not before.**
  `Grid.save`/`load` serialise an overlay as (resource name, version), so `loadols` mints a **fresh**
  `Resource.Saved` against the real remote pool and the fabricated `loaded` is gone. Load the grid through
  the engine first, then reflect-set `loaded` on the overlays of the grid you got back — which is exactly
  the state a live client reaches once its pool has answered. Generalise: when a fixture crosses a
  serialiser, fabricate on the far side of it.
- **(037.3) Falsify the ARITHMETIC of an ownership record, not just its presence.** Five planted bugs over
  43 checks bit 2/1/1/1/2 and each named a different mechanism: dropping the union (`break` after the first
  matching overlay) **2**, a non-idempotent take **1**, no relog guard in the release **1**, the recorded
  side ignoring its stock value **1**, `Loading` read as "no tags" **2**. The idempotence one is the keeper:
  it is invisible unless the fixture has **another holder** — the probe calls `mv.enol("cplot")` first to
  stand in for the user's own checkbox, and only then does "two takes, one release" differ from "one take,
  one release". A ref count with a single owner cannot fail the way a ref count fails.
- **(037.4) To make the engine RENDER headlessly, inject the resource into the pool's own cache.** 037.3
  fabricated a resource and pointed one `Resource.Saved.loaded` at it, which dies at the next disk round trip.
  The version that survives everything: `Resource.Pool.load(name, ver, prio)` checks its private
  `Map<String,Resource> cache` **first** and returns `cur.indir()` when `cur.ver == ver`, so reflect-put a
  fabricated `Resource` into `Resource.remote()`'s `cache` under the tileset's name and *every* `Saved` for
  that name resolves instantly — including the fresh ones a `Grid.load` mints. Build the `Resource` with its
  own private `(Pool, String, int)` constructor (reflection; `name`/`ver` are final and `Unsafe` is not needed
  for them), then reflect-set the protected `layers` list. `DataGrid.render` needs only a `Resource.Image` with
  `img` set (`Unsafe`-allocate it; the final `z`/`id`/`info` are never read); `MapSource.drawmap` — the level-0
  path — additionally needs a `Tileset` layer, and there only `getres()` and `tfac()` are reached, so an
  `Unsafe`-allocated `Tileset` with the synthetic `this$0` on `Resource.Layer` reflect-set and a `tfac`
  returning a factory that answers **null** walks the whole render (`drawmap`'s transition pass wraps its
  `tiler(t)` call in `catch(RuntimeException)`). The cache holds values weakly — pin the resources in a static
  list or they vanish mid-run. Result: real `TexI`s, real 100×100 bitmaps, no network and no GL.
- **(037.4) A teardown check must name a resource, not count a list something else empties.** The planted bug
  "`MapImages.teardown` does nothing" came back **GREEN** twice, because the check was `owner.images.isEmpty()`
  and `AssetApi.teardownAssets` empties that list whether or not the map-image cache was cleared — the two
  teardowns overlap, and the assertion sat entirely inside the overlap. Standing up one known-live handle first
  and then asserting *that* handle is disposed **and** that re-reading its key answers nil made it bite. The
  rule: when two teardown paths both touch a resource, assert through the path under test — a count that the
  other path also drives to zero is not evidence about yours.
- **A whole ADDON dry-runs under the LuaJ CLI against a Lua-only stub `hafen` — no Java probe needed (037.5).**
  `java -cp lib/brodgar/luaj-jse-3.0.1.jar lua driver.lua <repo>` (with `MSYS_NO_PATHCONV=1
  MSYS2_ARG_CONV_EXCL='*'`) runs a driver that builds a fake `hafen` in *Lua*, `dofile`s the shipping
  `main.lua`, and then calls the slash handler the addon registered. Two pieces make it work: the stub's
  `hafen.timer.after/every` just **queue** the callback, so a `drain()` loop that re-runs the queue until it
  stops growing executes every staged round synchronously; and `hafen.ui.window{}` hands back a table that
  keeps `opts`, so the driver can invoke `opts.onDraw(g, w, h)` with a `g` that **records** its calls and
  assert the *pixels* an addon would have drawn. That is what pre-checked 037.5's pin arithmetic at level 0
  and level 2 — including a negative segment coord, where `math.floor(sc/step)*step` is the only spelling that
  aligns the way the engine's own `gc & ((1<<lvl)-1)` test demands. 28/28 across both shipping addons, every
  branch (no panel / profiling off / a slow render) and four falsifications. Cheaper than a same-package Java
  probe and it drives the *shipping* file, so it catches syntax, control flow and arithmetic before the client
  starts; what it cannot see is anything behind the real bridge (interning, the load model's real timing).
- **`hafen.log` is ASCII-only in practice: the console mangles anything else (037.5).** A runtime log string
  containing an em-dash printed as `atlas: zoom 2 � one pixel is 4 tiles` on the terminal — the *comments* in
  the same file are fine, and the suites had never shown it because their verdict lines were already plain
  `--`. Keep `—`, `·`, `…` and friends out of every string that reaches `hafen.log`; they are free in code
  comments, docs and manifests. Green verdicts do not cover the text beside them.

- **(038.1) LuaJ counts a NUMBER as a string, so a `key` guard needs `isstring() && !isnumber()`.** The
  probe planted `gob:overlay(7, {…})` expecting a refusal and got a silent coercion: `LuaValue.isstring()`
  is true for numbers (they coerce), so a "keys are names" contract accepted `7` and stringified it. The
  in-game suite would never have found it — a suite tests the API as documented, and nobody documents
  passing a number. Any argument check that means *literally a string* has to say `!v.isnumber()` too.
- **(038.1) The fabricated world extends all the way down to a real `OCache`, which makes an
  id-resolving API fully headless.** 036.1's recipe stopped at a fabricated `UI`; four more `Unsafe`
  allocations reach the object cache — `MapView`(→`AddonManager.view`) → its public `ui` → `UI.sess` →
  reflect-set `Session.glob` → reflect-set `Glob.oc = new OCache(glob)` (the **constructor** matters:
  `allocateInstance` skips the field initialisers, so a hand-allocated `OCache` has a null `objs`), then
  reflect the private `objs` MultiMap and `put` real `new Gob(glob, Coord2d.z, id)`s into it. After that
  `AddonManager.getgob` answers and every `hafen.*` verb keyed on a gob id runs. The game's own overlays
  are fabricated the same way: `new Sprite(null, res){}` (Sprite is abstract with **no abstract methods**)
  over an `Unsafe`-allocated `Resource` with `name` reflect-set, hung straight on the public `gob.ols` —
  bypassing `addol`, which is what you want, since `init()` needs the render tree.
- **(038.1) Two `Addon`s + one gob is the only way to prove a PER-ADDON partition, and no suite can do it
  alone.** A suite is one addon, so "two addons' `"tag"` on one gob do not collide" is unassertable from
  inside it; the probe installs `installHafen` on two and drives both, and falsifying the partition
  (making the read return every addon's keys) reddened **6** checks. What the in-game side *can* do is
  prove it from OUTSIDE: `:lua` runs as the console's own `Addon`, so a `[manual]` line reading
  `:lua hafen.player():gob():overlay("tag")` → `nil` while the suite holds that very key is a genuine
  one-line cross-addon proof. A `[manual]` that is a COMMAND rather than a procedure (037.4's lesson)
  can reach places the suite cannot.
- **(038.1) A census that only counts is a report; make it RECONCILE and it becomes a check.** The first
  version asserted `duplicates == 0` over the live world and came back red with the real number (13 of 33
  gobs carrying two overlays of one resource) — which is exactly what a measurement is for, but it left
  the suite with a permanently-red line. The fix was not to lower the bar: the design absorbed the
  measurement (the collapse is published as `ov:count()`), and the check became an **invariant** —
  `sum(ov:count())` must equal the raw overlay list on every sampled gob — plus a second line asserting
  the collapse is real *here*, or the reconciliation proved nothing stronger than `1 == 1`. A census
  belongs in a suite only once it has an invariant attached to it.
- **(038.2) Subclass the fabricated `MapView` and COUNT the scene seam — that is what turns "destroyed, not left
  floating" into an assertion.** 038.1's recipe reaches a real `OCache`, but a real `addClientGob` cannot run
  headlessly: `basic.add(gob.placed)` builds a `Gob.Placed.Placement`, which calls `placer()`/`getmapstate()` and
  therefore needs an `MCache`. Since `MapView.addClientGob`/`removeClientGob` are public and non-final, an
  `Unsafe`-allocated **subclass** overriding both with `added++`/`removed++` (and a private no-arg ctor purely to
  satisfy javac; `allocateInstance` never calls it) makes the whole world-entity lifecycle runnable *and*
  observable — every attach is one add, and replace / remove / gob-death / teardown must each be exactly one
  remove. 60/60 for 038.2.
- **(038.2) A stub `Moving` on the TARGET is what makes anchor arithmetic readable headlessly.** `overlay:pos()`
  came back `0,0` on the first run — not a bug: `FollowMoving.getc()` calls `target.getc()`, which falls through
  to `placer()` and the absent `MCache`, and the production code's `catch(RuntimeException)` correctly falls back
  to the entity's own `rc`. Hanging `new Moving(g){ getc() -> (5,6,0) }` on the target gob makes the offset
  observable as `(5+dx, 6+dy)`. Generally: when a headless read answers the *fallback* value, fabricate the thing
  the real path reads rather than weakening the check.
- **(038.2) Drive the lifecycle through the REAL seam, or the wiring is untested.** The gob-death check first
  called `LuaGobOverlay.gobGone(g)` directly, and deleting the call site in `AddonManager.tick` falsified **0**.
  Reflecting the private `registerOcache(ui)`, then `oc.remove(g)` + `AddonManager.tick(0.05)`, made the same
  falsification bite **4** — and it also proved the ordering claim (the overlay is gone before `GobRemoved`
  reaches Lua). A probe that calls the method proves the method; only the seam proves the seam.
- **(038.2) A refusal assertion must grep the sentence the refusal is ABOUT.** The three `follow=` checks matched
  on `"gob:overlay"`, and silently ignoring `follow=` still falsified only 1 — because the *next* validation
  (`x`/`y` missing) also names `gob:overlay` in its guidance. Matching `"'follow'/'offset' are GONE"` instead,
  with one extra check that the message also names the replacement, took the falsification to **5**. When two
  error paths share a keyword, the check is measuring the keyword.
- **(038.2) A 420-byte hand-built `.glb` is enough to test the mesh path end to end.** `hello`'s `tank.glb` is
  2 MB and a suite must stand alone, so `tri.glb` is generated in ~15 lines of Python: `asset.version 2.0`, one
  scene → one node → one mesh → one primitive with a `POSITION` accessor over three `VEC3`s, no indices and no
  material (`Gltf.build` requires only `meshes` + `accessors`, and `bakePrim` only `POSITION` with `mode 4`).
  The probe parses it as its first check, so the fixture validates itself before anything rests on it.
- **(038.3) `Gob.ctick` only TICKS an overlay that has render slots — give it an empty list and the expiry path
  runs headlessly.** The third core seam (a sprite that ends by itself) was untestable at first: with no render
  tree `ol.slots` stays null, so `ctick` keeps retrying `init()` and never reaches `ol.tick(dt)`. Reflect-setting
  the private `Gob.Overlay.slots` to an empty `ArrayList` — which is what the real one looks like on a gob with
  no slots — takes the tick branch, and `remove0()`'s `multirem` over an empty list is a no-op. Falsifying that
  seam then bit exactly 1. Generally: when a headless run skips a branch, look for the *guard* that a live scene
  would have satisfied and satisfy it emptily, rather than calling the guarded code directly.
- **(038.3) A falsification that HANGS is a result, not a failed run.** Unbounding the event drain
  (`for(;;)` instead of the queue's length at entry) made the probe JVM spin forever — no red line, no summary,
  the harness timed out. That *is* the proof the bound is load-bearing (D-106), and it is worth writing down as
  its own outcome: budget a timeout for each falsification and treat "never finished" as a distinct verdict
  beside "N red". It also cost the restore step of that round's script, so restore from the backup FIRST when a
  falsification loop is killed.
- **(038.3) A stub that always succeeds cannot catch a missing fixture.** The suite dry-ran 9/9 green under the
  LuaJ driver and then went red in-game on `hafen.asset("icon.png")`: the driver's stub `asset()` answers a table
  for any path, while the real one answers nil for a file the addon does not ship (a suite stands alone, D-085,
  so it needs its OWN copy — 038.2's lived in 038.2's folder). Generalisation: a stub proves the *logic* that
  consumes a resource, never that the resource EXISTS; for each `hafen.asset`/file a suite names, check the
  folder, or have the driver stub answer nil for anything not actually on disk.
- **(038.4) A suite cannot watch its own teardown, so the check SPANS TWO RUNS through the store.** `:reload`
  removing every overlay an addon attached is the feature's own teardown claim, and D-104 says teardown fires
  nothing — the only addon that could hear it is the one going away. The shape that works: run 1 attaches one
  record per space, parks `{gob, keys, natives}` in the suite's **own account-scoped** `saved_variables`, and
  prints a `[manual]` that is two keystrokes (`:reload`, then the same command); run 2 reads the marker back,
  prints the verdict and clears it. Account scope matters — a per-character table is filled just before
  `OnEnterWorld` and a `:reload` is not a relog. This is the one legitimate exception to "a suite stands alone
  in one command" (D-085), and it is worth stating in the suite's own header so the next reader does not
  "fix" it.
- **(038.4) A cross-reload count of something the SERVER owns measures the world, not your teardown.** The
  first version of "…and left the game's own overlays untouched" compared the native count before the reload
  with the count after. Most of the game's overlays are transient sprites on their own schedule, so that check
  reddens for reasons that have nothing to do with the code. What actually pins the claim is the **refusal**:
  a native key is not ours to remove or attach onto, so no sweep of ours can take one — duplicated into this
  suite from 038.1's per D-085, and asserted where it cannot drift. Generally: when a claim is "we did not
  touch X" and X moves by itself, assert the *mechanism that makes touching impossible*, not a count of X.
- **(038.4) "It still exists" is not the test for "it came back" — the EVENT is.** The despawn round counted a
  parked gob as returned when `g:exists()`, which is trivially true of one that never left, so the line
  "3 of them came back, and a returning object is BARE" passed a run in which nothing despawned at all. Keying
  on the `GobOverlayRemoved` the despawn itself fired (`dropped[id]`) splits the three real states —
  still gone / despawned and streamed back / never left — and only the second carries the claim. A pass that
  cannot distinguish "the thing happened and was correct" from "the thing never happened" is not a check.
- **(038.4) When a round both listens for an event and CAUSES it, announce the deliberate one and swallow it.**
  The same round removes its parked overlays by hand, which fires the identical `GobOverlayRemoved` — and
  because the events arrive a frame later, *after* the round has reset its bookkeeping, its own cleanup would
  have been read as a despawn on the next run. One counter per gob id (`expect[id]`, incremented at the call
  and decremented in the handler) separates "I did this" from "this happened to me". The dry run caught it by
  re-parking and immediately asking `gone`, which must report nothing — worth adding wherever a test both
  drives and observes one mechanism.
- **(038.4) Counters reset INLINE are reset in the wrong frame when the events are queued.** The churn round
  zeroed its add/remove counters and then ran 40 cycles — but everything before it had queued events of its
  own, which land on the tick, so the docs round's burst arrived on top and the count read 85 where 80 was
  expected. The reset has to happen from a *later* frame than the last thing that queued. This is the general
  hazard of any tick-queued event: "clear, act, assert" only works when the clear is separated from the
  previous act by a frame, and a headless driver that drains synchronously reproduces it exactly.
- **(038.4) The docs' own snippets are a test — transcribe them VERBATIM and run them.** The suite copies each
  example out of `gob.md#overlays` and `guides/custom-ui.md` and executes it, and that is what found the round's
  only real defect: the page claimed `gob:info().overlays` "is still the raw list", but like every other
  `GobInfo` field it is **absent, not empty**, when the gob carries none (`types.md` already said "optional").
  The fix was to both correct the page and *upgrade the check* — a type test on a gob that may carry nothing
  proves nothing, so it became 038.1's reconciliation invariant (`#info.overlays == sum(ov:count())`) taken on
  a gob known to carry natives. A documented example that does not execute is worse than no example.
- **(038.4) A stub cannot report a field the engine never sets.** The Lua-only driver modelled
  `info().overlays` as always-present and went 19/19 green; the in-game run found the absent field in one line.
  This is 038.3's "a stub that always succeeds cannot catch a missing fixture" in its other form — there the
  stub invented a *file*, here it invented a *field*. When a stub stands in for an engine read, make it as
  stingy as the engine: omit what the engine omits, and the check that depends on it will fail honestly.
- **(039.1) A headless dry run cannot assert a value the SESSION owns — assert that the call is routed.** The
  suite's first `hafen.time():clock()` check demanded a number and went red headlessly, where there is no
  `Glob` and the honest answer is nil. Rewriting it as *every reader answers through the section object
  (6/6)*, printing the clock in the pass text, made it true in both places and actually stronger: what the
  task ships is the routing, not the clock, and the maintainer still sees the real number
  (`clock = 1.0255606E8` in-game vs `nil` headless). Rule: *when a check reads red headlessly and green
  in-game, ask whether it is asserting the task's claim or the session's existence.*
- **(039.1) A same-package probe runs the SHIPPING suite by pulling the handler off the addon.** Load
  `addons/<id>/main.lua` into a `Sandbox.create()` env with `installHafen`, then walk
  `Addon.slashCommands` for the registered name and `fn.call(new LuaTable())` — no console, no UI, no
  dispatcher. With it, all of a suite's verdict lines are known before the client starts. It needs an owner
  whose manifest declares **nothing** (`Manifest.internal` is the trusted REPL owner: it declares `actions`
  and allow-all network, so every gate passes and no refusal can be asserted) — hence `Manifest.test(id)`.
- **(039.2) `luac -p` exits 0 on a syntax error — check the OUTPUT, not the status.** The 030.4 note says to
  verify the checker against a deliberately broken file, and doing so is what caught this: a sweep over 35
  addon files reported "0 failed" while `luac` was printing a `LuaError` stack for a planted `x = = 1` and
  still returning 0. The sweep has to be `out=$(java -cp … luac -p "$f" 2>&1); [ -n "$out" ] && fail`. Same
  lesson as always, one tool along: a checker that has never been seen to fail proves nothing.
- **(039.2) The map DATABASE fabricates without a session, and that is what makes the RECORDED path
  testable alone.** `new MapFile(new MemCache(), "probe")` over a 10-line in-memory `ResCache`, then
  `file.new Segment(id)` with its private `map` reflect-filled (`sc → grid id`) and `file.gridinfo.put` /
  `file.segments.put` under the **write lock** (`BackCache.put` calls `checklock`). Add an `Unsafe`-allocated
  `GameUI` + `MiniMap` with `file` reflect-set and `mm.sessloc = new MiniMap.Location(seg, Coord.of(0,0))`,
  and an `Unsafe`-allocated `MapView` whose `parent` is that `GameUI` so `AddonManager.gui()`'s fast path
  answers. With **no `MCache` at all**, every live lookup misses and the recorded half of a Position is the
  only thing under test — which is the one path an in-game round cannot isolate, because in-game the ground
  under the player is always streamed.
- **(039.2) A falsification that ABORTS the probe is still a falsification, but say so.** Removing the
  Position branch from `Json.write` turned the strict `encode` into a throw, which one check caught and the
  *next* one (an unguarded `lua(...)` whose result is read) propagated out of `main` — 1 red and no summary
  line. Report it as "1 + abort" rather than "1 red": the run stopped early, so the checks after it were
  never evidence either way.
- **(039.3) 038.4's "counters reset inline" trap has a second half: the STATE has to be cleared a frame early
  too.** A round that counts add/remove events must not only zero its counters from a later frame — it must
  also leave the gob bare *before* that frame, because an `:add` on a key that is still live is a REPLACE and
  fires a removal nobody counted. Clearing the key inside the timer alongside the reset would have read 2 adds
  against 3 removals; clearing it before the timer and resetting inside makes both 2. The rule generalises:
  *when events are queued, everything the count is supposed to ignore has to have happened in an earlier
  frame — the counters and the world both.*
- **(039.3) The incremental build hides a FIELD becoming a METHOD, which is exactly what de-tabling does.**
  Turning `Attach.off` into `screenOffset()` left `UiApi` reading the field; `ant hafen-client` said BUILD
  SUCCESSFUL because `UiApi.java` had not changed, and only `rm -rf build/classes` found it. A task that
  reshapes a record shared with another file should assume this is waiting, not hope it is not.
- **(039.4) A `[manual]` must be a STATE the maintainer can be in, not just an action they can take.** The
  suite hunted for a grid that is streamed but not yet written down and, failing to find one, printed
  *"walk into ground you have never explored and run it again"*. The maintainer did, twice, and it printed
  the same line: `MapFile.update`'s `inout` records the **3×3 around the player every time their grid or its
  seq changes**, so that state lasts a fraction of a second between a grid arriving from the server and the
  next save tick. No typed command can stand in it. 037.4's lesson was that a manual line must be *runnable*;
  this is the other half — it must also be *reachable*. The fix was to stop chasing the state and assert the
  invariant underneath it (*every streamed grid is `:live()`, and its recorded reads answer exactly when
  `:exists()` says so*), which is checkable over every streamed grid at once and bites if the two halves were
  wired to each other. Generally: *before writing a `[manual]`, ask how long the world stays in the state it
  asks for.*
- **(039.4) A check that contradicts the grammar passes the probe and fails the client, because the probe
  shares the mistake.** The suite asserted `grid:overlay() == grid:overlay()`. Spec §2.3 is explicit that a
  collection owned by an **entity** is a *view* — re-derived per call, holding nothing, so it cannot outlive
  the entity — while the section-level ones are singletons; identity belongs to the **members**. The headless
  probe never caught it because the probe asserted the same wrong thing, and the rule turned out to be absent
  from `docs/` altogether, which is why it was possible to get backwards twice. Two takeaways: *when a check
  and its pre-check are written by the same hand in the same hour, the pre-check confirms the author, not the
  code* — and *a grammar rule that only exists in `specs/` will be re-invented wrongly by whoever writes
  against `docs/`*.
- **(039.4) 037.2's quantisation lesson bites the FALSIFICATION as readily as the check.** Planting "a
  marker's position lands on the tile CORNER instead of its centre" left the probe green: the check derives
  the tile with `math.floor(x / 11)`, and a corner is inside the same tile as its centre, so the plant was
  invisible to a tile-level claim by construction. What bit was a plant a *whole tile* off, plus driving the
  other-segment path (which the in-segment fixture never reaches). *A falsification has to be wrong at the
  granularity the check measures; one finer than the quantum proves the check is coarse, not that it is
  broken.*
- **(039.4) The map database fabricates one step further: a segment's private coord→id map.** 039.2's recipe
  plus `MapFile.Segment`'s private `(MapFile, long)` constructor, `file.segments.put`/`knownsegs.add` under
  the **write** lock, `Grid.save(file)` into the in-memory `ResCache`, `file.gridinfo.put(...)`, and the
  segment's private `map` field reflect-filled with `sc → grid id` — which is what makes `seg:gridid(sc)`,
  and therefore the durable half of every Position, answer without a disk read. `new Resource.Saved(pool,
  name, ver)` builds a `TileInfo` with no network. Note the load model reaches the probe too: a cold
  `grid:modified()` is **nil** and needs a poll loop, which is the caller's frame in miniature.
- **(039.5) The fabricated UI reaches the HUD: a suite needing `hafen.ui():inventory()` is dry-runnable.**
  036.3's recipe plus two `Unsafe`-allocated widgets hand-wired under the root — a `GameUI` (which
  `AddonManager.gui()` finds by walking the tree, no session needed) with a `maininv` `Inventory`, both
  `bind`ed into `UI.widgets`/`rwidgets` so `:id()` and `:node(id)` round-trip. `allocateInstance` skips field
  initialisers, so a hand-allocated widget reads `visible == false` — which conveniently keeps the fake HUD
  out of the hit-test while the real `root.add(new Window(...))` targets stay in it. With that, the shipping
  suite ran end to end at 30/0/0 before the client started, and the in-game round confirmed the same 30.
- **(039.5) A hit-test check must use a BARE widget, not a window — a window's corner is not hit-testable.**
  The first draft probed `probe:children()[1]:rootPos() + 4` on an owned `hafen.ui.window{}` and came back 2
  red: `DefaultDeco.checkhit` owns the caption strip and the content area, not the transparent pixels between,
  and the first child's `rootPos()` is the window's own corner rather than the content's. `hafen.ui.widget{}`
  is its own rectangle to the edge, so the negative probe can be the old **top-left + 2** exactly as 036.1's
  lesson demands, instead of falling back to a centre the move has to be proved to vacate.
- **(039.5) A picker that hunts for a NATIVE window must exclude the ones this addon owns.** 036.2's `named()`
  helper takes the first uniquely-titled `window`, and the suite creates its own probe window before calling
  it — so it can pick *itself*, at which point `:position(x, y)` takes the owned branch (a direct `move`, no
  recorded level) and `:position(nil)` is a silent no-op that reddens the restore check for a reason that has
  nothing to do with the feature. `not (w:info() or {}).owned` is the whole fix. Generally: *a self-validating
  picker must exclude the suite's own furniture, because a suite is part of the client it is measuring.*
- **(039.6) A "does not happen this frame" claim needs a build site the ARMING has already passed.** The
  suite's own slash command is dispatched *before* `ui.tick()`, so a surface built there is armed and painted
  in the same frame either way — the check passes with the mechanism removed and proves nothing. The
  discriminating site is a widget's own `onTick`, which runs after `AddonManager.tick` has already armed
  everything built this frame: without the guard the new surface is reached by that same frame's draw pass
  (the draw traversal restarts from `child`, so it always reaches a new tail), with it the first paint lands
  one frame later. A clock widget incrementing a counter in `onTick` and doing the building is the whole
  fixture, and `firstPaint > builtAt` is the assertion. Generally: *a timing guarantee can only be measured
  from a moment the guarantee is about; measure it from the wrong phase and you measure the call model.*
- **(039.6) A staged round needs a DEADLINE or a red line silently becomes a missing block.** The paint round
  reports from inside a timer that only fires once the clock has ticked; if the surface never draws, nothing
  reports and the maintainer pastes back a block with no `[summary]` — which reads like a crash rather than a
  failure. A second timer calling the same guarded `report()` closes the block with a real `[fail]` whatever
  happens, and it is what let the headless dry run print `20 pass, 1 fail` (the one fail being the round that
  genuinely cannot run without a draw pass) instead of hanging with 20 lines and no verdict.
- **(039.6) The fabricated `GameUI` needs `meters` before anything is removed from under it.**
  `GameUI.cdestroy` ends in `meters.remove(w)` unconditionally, so an `Unsafe`-allocated HUD NPEs the moment
  a probe destroys a widget it re-homed there — a fabrication artefact that reads exactly like a teardown
  bug. One reflect-set `LinkedList` fixes it; the general rule is that `Unsafe.allocateInstance` skips field
  initialisers, so every collection the code under test touches *unconditionally* has to be fabricated too,
  not just the ones on the path being asserted.
- **(039.7) A fabricated `RootWidget` needs the RES JARS, and the failure names the wrong thing.**
  `Unsafe.allocateInstance(Root.class)` on a `RootWidget` subclass runs `ConsoleHost.<clinit>` &rarr;
  `Text.<clinit>` &rarr; `Resource.local().loadwait("ui/fraktur")`, which dies with
  `NoSuchResourceException ... from local res source (res)` — a message that points at the resource system
  and not at the classpath. It is 028.1's wall exactly, and the fix is 028.1's: add `bin/*res*.jar`
  (`builtin-res` + `hafen-res`) to `-cp`. The trap is that the trace fingers whatever class you allocated
  last (here a `GameUI`), so the instinct is to delete that line — which "works", moves the same crash one
  allocation earlier, and costs a round. Full recipe for a 036.1-style probe: `build/classes` + all of
  `lib/*.jar lib/brodgar/*.jar lib/ext/*.jar bin/*res*.jar`, `-Djava.awt.headless=true`, and the toolkit's
  `Unavailable` + `GLException` traces are caught noise.
- **(039.7) A table-driven setter/read round must read each property AS it writes it.** The suite wrote all
  eight rule setters in one loop and read them all back in a second, which reddened `position` for a reason
  that was not a defect: `anchor` writes the same slot, so by the second loop the earlier property was
  legitimately `nil`. The probe passed the same assertions because it interleaved write and read per
  property. Rule: one pass, `write(); read()` per row — a second sweep silently asserts *the end state*
  rather than *each write*, and any pair that shares a slot turns that into a false red (or, worse, a false
  green if the shared write happens to satisfy the check).
- **(039.7) Plant the defect against BOTH harnesses; one the probe catches alone names a MISSING suite
  check.** Falsifying "sheet:load replaces the document whole" (made it merge) reddened two probe lines and
  left the shipping suite fully green — which is not a probe win but a hole in the suite, since the in-game
  round is what the maintainer runs. The fix was one assertion in the suite (a rule the previous document
  named says nothing after a load that does not name it), and the same plant then bit both. Generally: a
  falsification round is also a coverage diff between the two harnesses.
- **(039.8) A mechanical port sweep rewrites the very spelling a REFUSAL test pins, and the check then
  asks the new form to throw.** Both of this task's regex sweeps (`hafen.asset(` → `:get(`, `:derive{…}` →
  `:derive():size(…)`) ran over `addons/**` including the suite being written, silently converting
  `refuses(..., function() return hafen.asset(ICON) end, ...)` into a call to the *live* door. The suite
  still parses, still runs, and reports `got: <no error>` — which reads like an engine defect. Rule: run a
  corpus sweep, then re-read every `refuses(`/`pcall(` line it touched, because those are exactly the sites
  whose OLD spelling is the point. (Same shape as 039.5's `pcall(hafen.ui.all, s)` finding, one level up.)
- **(039.8) A retired row's message is asserted as TEXT, so changing a signature is a two-place edit.**
  Widening the constructors to `:add(thing, p)` changed the `Retired` message; `add(imageAsset)` is not a
  substring of `add(imageAsset, p)`, so every suite pinning the old text reddens — with the engine
  perfectly correct and the pasted line showing the *right* replacement being named. Grep the wanted
  substring across `addons/` whenever a retired message changes; the coupling is invisible from the Java.
- **(039.8) A world-entity handle is fully headless-testable without a MapView.** `new LuaGhost(owner,
  null, res, rc, a)` constructs directly, and every verb guards on `gob != null`, so pulling the private
  `ghostHandle` off `RenderApi` by reflection exercises the whole read/write vocabulary — position, rotate,
  scale, alpha, tint, visible, clickable, onClick, exists, plus the nil discipline — with no client at all.
  What is left for the in-game round is only the scene: the real `addClientGob`, the billboard re-mill and
  the Position round trip. 99/99 that way, and it still missed D-127, because the defect lived in the one
  call the probe could not make.
- **(039.9) A gate check that matches only the VERB NAME passes with the gate removed.** The suite asserted
  the five gated writes by pcall-ing each and looking for the verb in the message — and the falsification
  that deleted `requireActions` from `kin:group` left it **green**, because the very next line raises
  *"kin:group(): no Kin window (not in the world yet)"*, which contains the verb too. Every gated verb has a
  second, unrelated refusal that names itself; the permission clause is the only text that distinguishes
  them, so the wanted substring is `"<verb>: this addon did not declare"` rather than `"<verb>"`. The probe
  caught it and the suite did not, which is 039.7's rule again: *a falsification round is a coverage diff
  between the two harnesses*, and the harness the maintainer actually runs is the one that has to bite.
  Generalisation: when a refusal is one of several a call can raise, pin the CLAUSE that makes it that
  refusal, not the name every one of them repeats.
- **(039.9) A per-task suite can be dry-run against no client at all when the feature is a SHAPE.** The six
  collections read the HUD, so headlessly every one is empty — and that is enough to assert the whole
  grammar: the section singleton, the collection verbs, the retired rows *and their message text*, the
  interning that does not need a client (`kin:get(7)`, the fixed 144 slots, a Sound name), the refusals, and
  the gates (the owner is `Manifest.test`, which declares nothing, where `Manifest.internal` passes every
  gate — 039.1). 70 probe asserts plus the shipping suite end to end, whose only two reds were the rounds
  that genuinely need a live HUD. Write those two so the `got:` says *why* ("the catalogue is empty", "no
  HUD meter -- are you in the world?"), and the headless run reads as a precondition rather than a defect.
- **(039.10) A parked cross-reload check reads a FILE, and a file outlives the run that wrote it — so the
  value has to carry a STAMP.** 038.4 recorded that a cross-reload *count* of something the server owns
  measures the world; the same trap has a quieter shape when the thing crossing the reload is your own saved
  variable. The store round trip (`:t039-10` writes, `:reload`, `:t039-10 kept` reads) went green during a
  falsification round where the write could not possibly have reached disk, because a previous run's
  `savedata/account/<id>.json` was still there and said exactly what the check wanted to hear. Writing
  `t.stamp = os.time()` and asserting the age is under ten minutes makes the check discriminate the run from
  the residue, with no human in the loop. Rule: *a check that reads persistent state must assert WHEN it was
  written, not only what it says* — and delete the file between falsification rounds, or the plant is testing
  the disk.
- **(039.10) The client CANONICALISES a keybinding's spelling, so a write/read round trip compares tokens.**
  `kb:key("x", "Ctrl+Shift+Y")` reads back `"Shift+Ctrl+Y"`: `KeyMatch.name()` emits the modifiers in its own
  order, not the caller's. A `got == want` assertion therefore reddens on a perfectly correct remap. Split
  both on `+` and compare as sets. Generalisation of 037.2's lesson in the other direction: *when the engine
  normalises what you hand it, the round trip is about the VALUE, and asserting the string asserts the
  engine's formatting.*
- **(039.10) Asserting a SHAPE across every section in one loop is what finds the section nobody looked at.**
  The probe ran four claims (`==` identity, callable-table-over-userdata, arguments refused, unknown verb
  throws) over all five of the task's sections rather than hand-writing them per section, and the fourth
  caught `hafen.player()` reading `nil` for `:nosuchverb()` where every other section throws — the third
  occurrence of the same defect in this feature (039.2 `LuaGob`, 039.3 `LuaOverlay`), each found only because
  something asserted it. A per-section checklist would have had four entries for the four sections somebody
  thought about. Rule: *when a task ships N of one shape, assert the shape N times in a loop; the one you
  would have skipped is the one that is wrong.*
- **(039.11) A payload check that latches the FIRST event latches an EMPTY one, and passes having proved
  nothing.** The suite recorded the first `StudyChanged` at load and asserted the payload was an array of
  objects. It went green in-game reading `table/empty`: the login fires the event while the study window is
  still building, so the array that arrived had no members and the check could only ever observe that a
  table is a table. That is 028.3's *a harness assertion whose precondition is not guaranteed reports a fake
  pass* in the shape a **latch** gives it — the precondition is not "am I in the world" but "does this
  particular payload carry anything", and the first one systematically does not. The fix is one word:
  latch the first **non-empty** payload, assert the member is userdata answering a read, and count the empty
  ones so an empty-only session says so in `got:` instead of passing. The probe could not have caught it —
  no event fires headlessly — so this is the in-game round earning its keep on a check the dry run had no
  opinion about. Rule: *when a check latches one sample of a stream, state what makes a sample admissible,
  or the first arrival will be the least informative one.*
- **(039.11) The falsification plant has to sit where the value is COMPUTED, not where it is passed.** The
  first attempt at "does the collection get minted per call" edited the argument of the install-time
  `chr.set("attr", collection("attr", attrs))` — which is evaluated once whatever it says, so both harnesses
  stayed green and the plant proved only that I had planted nothing. Moving the plant into the accessor
  itself (mint inside `invoke`) reddened the probe twice and the suite once. Rule: *a plant that leaves both
  harnesses green is a plant to re-read before it is a check to distrust.*
- **(039.12) A suite that INVITES a second run needs its counters reset per run, and only the second run
  shows it.** This suite's two headline rounds need a state the maintainer has to enter, so both `[manual]`
  lines say *run the same command again* — and the second block came back `[summary] 25 pass` over 13 printed
  `[pass]` lines, because `pass`/`fail`/`manual` were file-level locals initialised once at load. Every past
  suite hid this by being run once per login. The skeleton in `TESTING.md` declares them at the top, which is
  right, but the reset belongs at the top of `run()`. Rule: *a summary is a summary OF THE BLOCK under it —
  if a command can be run twice in one session, zero the counters where the run begins, not where they are
  declared.*
- **(039.12) Check whether the game already puts you in the `[manual]`'s state before writing the line.**
  039.12 wrote *party up with another character* as a `[manual]`, assuming a solo player has no party. The
  server sends a one-member party list, so `hafen.party():count()` is 1 while alone: the round ran, printed
  two `[pass]` and the manual line never fired. No harm done — the fallback branch is what made that possible
  — but the line was written for a state nobody has to enter, which is 039.4's *a `[manual]` must be a state
  the maintainer can be in* from the other side. Rule: *before asking a human for a precondition, read the
  subsystem and check it is not already satisfied; a manual line you never see is a manual line you did not
  need to write.*
- **(039.13) A plant that makes the value's own `toString` throw kills the harness while it is PRINTING the
  red line.** Falsifying "`:current()` is nil with no recipe open" by minting an entity over a null window
  made three checks fail correctly — and the first one aborted the whole probe with an NPE out of
  `LuaCraft.toString()`, because the failure branch interpolates the value into its message. 039.2's abort
  lesson in its quietest shape: the checks behind it were never evidence either way, and the run printed a
  stack trace rather than a verdict. The fix is one helper — stringify the `got` inside a `try`, reporting
  `<toString threw: …>` — after which the same plant reddened 3 checks and the summary still printed. Rule:
  *a harness must survive the defect it is looking for, and the place it most often does not is the code
  that formats the failure.*
- **(039.13) Verify the plant APPLIED before believing a green falsification round.** A `perl -0pi -e`
  substitution inside a `<<'EOF'` heredoc lost a backslash, so the pattern never matched, the plant was
  never made, and the round reported "0 failed" — which reads exactly like a check that cannot discriminate.
  Re-running it with a `grep` on the planted line first showed the edit was absent and the check bit
  immediately. Generalisation of 039.11's *a plant that leaves both harnesses green is a plant to re-read*:
  the first thing to re-read is not the check, it is whether the file on disk actually changed.
- **(039.13) A falsification that bites ZERO is worth reporting when the branch is genuinely out of reach.**
  Planting `w:parent()` ignoring the `-1` root marker left the probe green, and correctly: with no client
  every wound is on its departed branch, where the guard the plant removed is never reached. That is not a
  weak check but an honest boundary — the tree half is the in-game round's to prove, and the in-game round
  did (`1 root(s), 0 complication(s)`, with `w:parent():id()` cross-checked against `w:info().parentid`).
  Rule: *say which plants bit and which could not, and why; a falsification tally with no zeros in it is
  usually a tally that only counted the reachable plants.*
- **(039.14) A sum that only COUNTS is satisfied by an under-report — make it reconcile or it goes green over
  a broken read.** The equipment check asserted `filled >= #eq` (worn items fill at least as many slots as
  there are items), which is true whether or not every item names its place — so the first in-game round
  printed *"18 worn item(s) fill 18 slot(s)"* and passed, while one of those items was reporting **zero**
  slots and therefore reading as *not worn*. The arithmetic was the only evidence (16×1 + a two-slot item
  = 18 needs one item at 0), and it was in the `[pass]` line's own text rather than in the verdict. The fix
  is the check the census rule (038.1) already demands one level up: assert the invariant — *every* worn item
  names ≥ 1 slot, with the offender in `got:` — after which the plant reddens both harnesses instead of only
  the probe. Generally: *a `>=` over a set is a smoke test; the assertion is that no member is missing.*
- **(039.14) The fabricated HUD extends to real item containers, and the trap is `cdestroy`, not the build.**
  039.5's recipe plus `new Inventory(Coord)` / an `Unsafe`-allocated `Equipory` (its ctor builds an `Avaview`)
  hand-wired under the fake `GameUI` makes the whole item surface runnable: bind a real `new GItem(indir)`
  into `UI.widgets`, then place it with the container's own **`addchild(item, cell)`** — not `add()`. Both
  containers keep a private `wmap` that `addchild` fills and `cdestroy` reads back unguarded
  (`ui.destroy(wmap.remove(i))` → NPE on a null), so an item placed the short way kills the run at the exact
  moment the probe destroys it, which is the moment the staleness test needs. Wiring a `GameUI`/`Equipory`
  under a parent must also skip `added()` (assign `parent`/`ui` and call the public `link()`): `GameUI.added`
  resizes and dereferences a `chat` that a fabrication has not got.
- **(039.14) A resource's own published class can be FABRICATED for a probe — put a stand-in on the
  classpath under the real package name.** `item:quality()` reads a class named
  `haven.res.ui.tt.q.qbuff.QBuff` reflectively, so a 12-line stand-in with the same package, name and
  `public double q` (plus its `Quality` subclass) compiled into the probe's own output directory makes the
  whole path testable with no resource loaded at all — and the preference rule (the plain quality wins over
  another buff row) becomes an ordinary assertion. Reflect-set the private `GItem.info` list to hand them
  over; `info()` only rebuilds when `rawinfo != null`, so a planted list survives.
- **(039.15) A NONDETERMINISTIC defect cannot be pinned by the in-game suite — that suite can only ever say "it did
  not throw today", so the deterministic half is a probe that BUILDS the state.** The gob string-filter bug needed
  a gob whose resource had not resolved to be in view at that instant; the shipping suite reproduces it or does
  not, by luck. `LuaCollection.keeps` is package-visible and **static**, so a 90-line probe reaches it reflectively
  with no client, no `Addon` and no sandbox, and passes the states directly: a named member with a needle, one
  without, and a nameless kind. 8/8, and falsifying it by restoring the old conflation from a file copy reddened
  two lines — one reproducing the maintainer's pasted error text verbatim, which is the strongest evidence a fix
  can carry. **Reach for the static helper, not the collection**: constructing the collection needs the whole
  bridge, while the decision under test needs six arguments.
- **(039.15) `LuaValue.isstring()` is TRUE for a number, so a "wrong type" test written with `valueOf(7)` proves
  nothing.** The filter's type refusal looked untested-and-passing until the probe reddened on it: `7` takes the
  string branch (Lua's own coercion, which LuaJ models faithfully) and quietly matches nothing. Use `LuaValue.TRUE`
  or a table for the wrong-type case. The general form is 001.1's again — *a check that passes for the wrong reason
  is worse than a missing one* — and it is the second time this file records `isstring()` as the trap.
- **(039.15) The docs audit is a 200-line Python script and it is worth keeping whole, because four of its checks
  disagree with the obvious one-liner.** Python 3.14 is on this box (033.3's "re-derive it in Java" is retired).
  The traps, all of which bit: `os.path.normpath` strips a leading `./` so a graph keyed on walk paths silently
  matches nothing — use `abspath` everywhere, or reachability reports every page unreachable AND every anchor
  check is skipped, which reads as a clean run; the heading check greps the SLUG for `--` rather than the source
  for an em dash (001.5's character class, mechanised); the wrap check measures characters with `s/\r?\n?$//`,
  never `chomp` and never `awk length` (002.2/003.1); and links inside inline backticks must be blanked before
  the link regex, or documentation ABOUT a link counts as one. Falsify both directions every run: plant a bad
  path AND a bad anchor, confirm both are caught, confirm the healthy tree reports zero.
- **(039.16) A falsification plant can be PRESENT and INERT, and that reads exactly like a check that cannot
  discriminate.** Falsifying "every section hands back the SAME object every call" meant making one section mint a
  fresh one; the plant was `sectionObject(name, verbs)` where that helper is `setmetatable(verbs, mt)` — which
  **returns the table it was given**, so both calls handed back the identical table and the check stayed green. The
  first reading was "the singleton check is broken"; the plant was. 039.13 recorded *grep the file and confirm the
  plant is there* — this is one step past it: the plant was there, and did not produce the state it claimed to.
  **The fix is to assert the plant, not the plant's source**: a plant meant to produce two distinct objects is
  checked by printing whether they are distinct, before concluding anything about the check under it. Lua-specific
  trap in the same shape: `setmetatable` and `table.sort` mutate and return their argument, so any stub built on
  them recycles state a fresh-object plant needs to break.
- **(040.12) 040.9's "measure the tree baseline on the same side of a lazy row-build tick" gotcha recurs on
  EVERY new model-backed suite that builds its own `[manual]`-line demo window, and a narrative mention in
  `STATE.md` was not enough to stop it recurring — this is the first time it is a searchable learning.** The
  suite called `phase1()` (which measures `base = treeCount()`) synchronously at the end of `run()`, right
  after building the demo's own `hafen.ui():table()` — but `SListBox.update()` (inside a `TableBox`'s
  `MainList`) builds row widgets lazily, on the FIRST tick, so `base` was 9 widgets short of what that SAME
  table looked like a few ticks later at the final count, reading as a leak that never was one. Fix: defer
  `run()`'s call into `phase1` by one tick (`hafen.timer():after(0.5, phase1)`), exactly 040.9's own fix,
  applied a second time. **The durable form of the rule**: any suite with a lazily-built row/cell tree AND its
  own always-on demo window must take the demo's lazy build into account before touching `treeCount()` at
  all, not just avoid asserting on the demo's OWN row widgets before they exist.
- **(040.12) A helper that walks an `SListBox`'s children for "the row widgets" must filter its own auto
  `Scrollbar` — 040.9's `rowWidgets()` already does this, and porting the SAME shape to a new consumer without
  copying the filter reintroduces the exact miscount.** `TableBox.MainList extends SListBox`, so it carries
  the auto-added `Scrollbar` (`SListBox`'s `autoscroll()` defaults `true`) as a sibling of every `Row` widget;
  a `rowWidgets()` ported to walk `MainList:children()` without excluding `type() == "Scrollbar"` counted 3
  rows as 4. Grep `rowWidgets` before writing a new one over any `SListBox`-shaped container.

- **(041.1) A Lua stub must raise with `error(msg, 0)`, or the suite's own refusal helper eats the message.**
  The headless driver modelled the new bus in Lua and six refusal checks went red for a reason that had
  nothing to do with the suite: LuaJ's default `error(msg)` prefixes the chunk position **and appends a
  traceback**, and every suite's `refuses()` strips a leading `^.-%.lua:%d+:%s*` — in Lua patterns `.` matches
  newlines, so the strip ate the message *and* the first traceback line and compared against `"in function
  'on'"`. A `LuaError` thrown from Java arrives clean, so this is a stub artefact that reads exactly like a
  product defect. Raise from a stub with level 0 whenever the string is what the check pins. Generalisation:
  when a dry run reddens a line about a *message*, suspect the stub's error shape before the assertion.
- **(041.6) "Every prior task's suite went green" is not proof a design line was actually built — a symbol
  check against `src/` while writing docs found what five green suites did not.** `spec.md`/`EXAMPLES.md`
  called for `GobOverlayAdded`/`GobOverlayRemoved` and the three `*Clicked` bus events to become `LuaEvent`
  colon-verb objects, same as everything else the feature touched; no single task's checklist (`041.1`
  through `041.5`) actually assigned that conversion, so `AddonManager.overlayPayload`/
  `RenderApi.onGhostClick` still build a plain `LuaTable` today and every suite that passed never asserted
  the shape because none of them touched these five keys. The gap was found only because the docs task's
  own §12 checklist item 5 ("every `hafen.*` name on a page found in `src/`") is a grep against the engine,
  not against another doc or another suite's green line. Generalisation: a feature split across many small
  tasks needs the full acceptance-criteria list re-checked against `src/` at the close, because "assigned
  to a task" and "in the spec" can silently drift apart across five hand-offs, and a docs task's symbol
  check is one of the few places that would ever notice.

- **(041.7) A retired-verb probe must ride an ALREADY-PARENTED widget, never a fresh one built just to
  probe it -- a bare builder attaches under `ui.root` immediately (`UiApi.attach`), before any
  `:parent(...)` call.** A completeness-sweep suite that asserts sixteen retired `w:onXxx(fn)` spellings
  is tempted to build a throwaway `hafen.ui():button()`/`:widget()`/etc. per probe, since the call is
  expected to throw before doing anything else -- but the throw happens on the DOT-READ of the retired
  name, one step *after* construction already attached the widget to the tree, so a probe widget never
  handed to `:parent(scratchWindow)` survives the scratch window's `:destroy()` and reads as a leaked
  control in a teardown tree-count check. Caught before the first in-game run by tracing `UiApi.attach`'s
  `u.root.add(rootw)` line, not by running it. Fix: reuse handles the suite already built and parented for
  an earlier part of its own sweep (`handles.btn`, the shared `own` widget, ...) for every such probe.
- **(041.7) TESTING.md's "the regression is never run" rule extends to a task's OWN `[manual]` lines, not
  only to what other suites assert.** A close-task suite asked the maintainer, as one of its two manual
  steps, to "run the full regression list one command at a time" -- which is exactly the thing D-085/the
  2026-08-06 amendment (041.2) says never to ask for: *"a task ... never reports 'also run :tNNN-X' as
  part of its own proof."* The maintainer caught it in the same round it shipped, referencing the rule and
  recent commits directly. The line was written because `tasks.md`'s own 041.7 entry (drafted the same day
  the amendment landed) still named it as the task's `[manual]` line -- a spec artefact and the policy it
  predates can disagree, and only the artefact gets read by `/implement` unless the maintainer flags the
  drift. Fix: delete the line; a close task's own suite (the matrix + retired sweep + the two composite
  payloads) is what proves completeness, and nothing outside `:t<NNN>-<X>` belongs in its own `[manual]`
  list, no exception for "this is the feature's last task."

- **(042.1) A `[manual]` line for "a HUD meter appears/disappears mid-session" must name mounting a
  horse, never a crafting/digging progress bar.** The suite's first draft asked the maintainer to "start
  and complete a craft" to exercise `MeterAdded`/`MeterRemoved`; nothing happened, because crafting
  progress in this codebase never occupies `GameUI`'s `place == "meter"` HUD slot — the only server
  message that lands there is `im` (`IMeter`'s `@RName`), and the only in-game trigger that adds to it
  mid-session is mounting (`gfx/hud/meter/häst` + `.../mount`; `learnings/widget-tree-reads.md` (027.1)
  already recorded this from a different feature, but it wasn't consulted while writing this task's test
  instructions because 042.1's own "Context files" didn't name it). Generalisation: before writing a
  `[manual]` line that asks the maintainer to trigger a specific widget-tree event, grep this file's own
  learnings for the widget/uimsg involved — a fresh-context task can rediscover a fact a sibling feature
  already paid for, and re-deriving it wrong costs a verification round.

- **(042.8) The (036.1) fabricated-collaborator recipe needs no live `UI` at all when the code under test
  only READS `AddonManager.ui` and branches on null.** `dispatchReplacedRemoved`'s death test
  (`stillHidable`, inside `endReplacement`) checks `u == null` first and short-circuits false — which is
  exactly the branch a server-destroyed window takes in-game anyway (M1 fires after the id is already
  unbound), so leaving `AddonManager.ui` null in the probe exercises the real path, not a stand-in for it.
  Two throwaway `Addon`s (`Manifest.internal`), a bare `new Widget(Coord)` for the "native window", a
  package-private `new AddonWidget(owner, sz)` for the stand-in, and a hand-built `LuaWidget.Hidden`
  (package-private ctor, same package) were enough for 12 checks: the record ends and the view is killed
  when its OWN window reaches the seam, twice-offered is a no-op (not a double-kill), a view-less record
  survives, an unrelated widget's removal never touches a different substitution, the `:lua` REPL owner
  (`consoleOwner`) is covered identically, and `anyHidden` recomputes correctly both ways. The one thing
  this cannot reach — confirmed separately, in-game — is the visible consequence of NOT going through
  `replace(nil)`: see `widget-replacement.md`'s 042.8 entry.

- **(042.11) TESTING.md's own copy-paste skeleton was stale against the CURRENT API, and a suite that
  copies it verbatim fails SILENTLY — "no such command" in-game, with the real reason (a `LuaError` from a
  retired dotted spelling) only ever reaching the terminal `AddonRegistry.loadAll` logs to, not the console
  the maintainer was watching.** The skeleton predates 039-uniform-api and used `hafen.log(msg)` /
  `hafen.slash.register(name, fn)` — both retired, both throwing (`Retired.NAMES`) naming their colon-call
  replacements (`hafen.log():write(msg)`, `hafen.slash():register(name, fn)`). Because
  `hafen.slash.register(...)` was the file's last top-level statement, the throw happened during
  `Addon.run()`'s file body execution, which is caught and stored as `addon.error` — so the addon never
  registered its command and never surfaced *why* in-game. **Verify a NEW suite loads cleanly, not just
  that it parses**: `luac -p` (018.4) only proves syntax, and this bug was syntactically valid Lua. The
  033.3 recipe (a same-package probe: `Manifest.load(dir)` on the REAL manifest, a throwaway `Addon` +
  `Sandbox.create()`, `AddonManager.installHafen`, then `owner.run()`) catches it in one run: check
  `owner.error == null` AND `owner.slashCommands` is non-empty before ever asking the maintainer to
  `:reload`. TESTING.md's skeleton was fixed the same task (both calls updated to the current colon form) —
  if a future skeleton drifts from the API again, this is the check that catches it before a verification
  round is spent on "no such command."

- **(043.1) A suite that must SHIP an asset can hand-write it — a one-triangle `.gltf` is ~900 bytes where
  the shipped `.glb` is 2 MB.** A per-task suite is archived into `specs/` and its assets are addon-relative
  and **sandboxed**, so it cannot borrow `hello`'s or `planner`'s — copying `tank.glb` would have put a 2 MB
  blob in the repo per task that touches the object collection. `Gltf.parse` accepts a `.gltf` whose buffer is
  a `data:…;base64` URI (`resolveBuffers`), so the whole model is one JSON file: `scenes`/`nodes`/`meshes`/
  `accessors`/`bufferViews`/`buffers`, POSITION only (NORMALs are computed when absent, indices optional).
  Verify it through the real parser before shipping — a 6-line throwaway `main` calling
  `Gltf.parse(bytes, name, null)` prints `prims/nvert/ntri`; note it needs the **luaj jar** on the classpath
  even though the class is pure, because its error path builds a `LuaError`.
- **(043.1) A hand-made fixture has to be SEEABLE, or the `[manual]` line it exists for proves nothing.** The
  first `tri.gltf` was a triangle lying flat in glTF's XZ plane with no material: after the basis conversion it
  hugged the ground, and with no `material` the parser defaults `doubleSided` to **false**, so it was
  back-face culled and invisible from the game camera. The maintainer reported seeing two of the three things
  the manual line names — which reads exactly like "the object collection is broken" and is not. Rules that
  came out of it: stand a fixture **upright** (glTF is Y-up and 1 metre = 1 tile via `Gltf.MODEL_UNIT = 11`,
  so a `Y=0..1` triangle is one tile tall with its base on the ground), give it a material with
  `"doubleSided": true` so no winding can hide it, and a `baseColorFactor` that is not the terrain's colour.
  Generalisation: when a check's whole job is "a human confirms it looks the same", the thing being looked at
  must be unmistakable — a fixture that can be *missed* turns a green feature into a verification round.

- **(043.2) An in-game round that reports on a DESPAWN must classify by the event it recorded, not by a
  snapshot at report time — walking back re-sends the same object under the SAME id.** The despawn round asked
  `hafen.world():gob():get(id):exists()` when the maintainer ran `:t043-2 gone`, having walked ~100 tiles out
  and back. All three parked gobs read as *still loaded* — the server re-sent those very objects under their
  original ids on the return trip — so entities the feature had correctly destroyed were classified as "never
  left", and the round reported the opposite of what the maintainer could see with his own eyes (icons gone,
  free cabin still standing). The fix is not a better tolerance but a better source of truth: the suite already
  had a `GobRemoved` handler recording `ent:exists()` **as the removal happened**, so `atRemoval[id] ~= nil`
  *is* the despawn, and the recorded `false` is also the proof the end ran before the event reached Lua. Rule:
  *for anything a walk makes happen, the only durable evidence is what you recorded while it happened — a gob
  id is not a lifetime.*
- **(043.2) A check whose failure list is passed only as the `got` argument is a check that CANNOT fail.** The
  same round built a `wrong` list of mismatches and then wrote
  `check(alive == #parked, "…all icons are still riding them", table.concat(wrong, "; "))` — the condition was
  trivially true and `wrong` only ever *prints* on failure, so the line printed `[pass] all 3 icons are still
  riding them` without ever having asserted it. Worse than a missing check: it read green over the very
  mismatch it had computed. Rule: *whatever you compute a discrepancy list for, `#list == 0` IS the condition —
  if a list appears only in the `got` slot, the assertion beside it is asserting something else.*
- **(043.3) The 033.3 probe recipe run with `Manifest.test(id)` executes NO FILES — `error == null` and zero
  slash commands, which reads exactly like "the suite failed to register" and is really "the probe never ran
  it".** `Manifest.test` ([Manifest.java:121](../../../src/io/brodgar/addon/Manifest.java:121)) is a *synthetic*
  manifest declaring nothing — including an empty `files` list — so `Addon.run()` loops over nothing and returns
  cleanly. 039.1's note recommends it over `Manifest.internal` for the right reason (internal declares `actions`
  + allow-all network, so no refusal can be asserted), but for a **suite** the honest answer is neither: load the
  real `Manifest.load(dir)`, because a per-task suite already declares no permissions by protocol (TESTING.md),
  so the real manifest IS the "declares nothing" shape. Check `owner.slashCommands.size()` explicitly and assert
  it — a probe that only checks `owner.error == null` passes this bug.
- **(043.3) `java -cp` will not find a class under the scratchpad from a Git Bash shell; `javac -d` there is
  fine.** Compiling the probe into
  `%LOCALAPPDATA%\Temp\claude\…\scratchpad` worked, but `java -cp "build/classes;…;$SCRATCH"` answered
  `ClassNotFoundException` for a `.class` that demonstrably existed at that path (MSYS argument mangling on the
  absolute `C:/…` element of a `;`-separated list). Copying the compiled package into a throwaway repo-local dir
  (`probe-tmp/`, deleted after) and putting *that* on the classpath ran first time. Keep probe **classes**
  repo-local and transient; the scratchpad is fine for sources and outputs.
- **(043.4) A `:list(filter)` refusal asserted on an EMPTY collection CANNOT fail — the filter is validated per
  member, so with no members nothing raises.** `LuaCollection`'s `list`/`count`/`find` call `keeps(filter, …)`
  *inside* the member loop, which is right (the needle is per member) but means a bad filter on an empty set is
  silently accepted. A headless probe driving `hafen.vr():list(true)` with nothing standing reported
  `filterRefused=<no error>` — which reads exactly like "the refusal is missing" and is really "the loop that
  raises never ran"; in-game, with four entities standing, the same call refuses correctly. Two rules. *Assert a
  per-member refusal with at least one member present* — in a suite that means placing before refusing, not
  after clearing. And *when a probe reports `<no error>` for a refusal you have read the code for, check whether
  the raising path was reachable at all before changing the code*: this is the empty-set twin of 043.2's "a check
  whose failure list is only in the `got` slot cannot fail".
- **(043.5) A section rename adds two names to the docs' retired-name guard, and that list lives in the OTHER
  area.** `hafen.ghost` and `hafen.render` are retired spellings as of 043, so `docs/`'s §7 grep list
  (`specs/docs/design/style-guide.md`) should carry them — but that file is area `docs`'s, and an `addons` task
  does not edit it. Both read **zero** over `docs/` at 043.5's close, checked by hand alongside the §7 list
  proper, so the tree is clean; what is missing is only the standing guard against a reintroduction. Rule: *when
  a rename retires a name that `docs/` used to spell, run the grep yourself and report the two counts — the
  owning area's list is a filing, not a blocker, and an unrun grep is the part that actually costs.*
- **(044.2) A screen-rectangle probe must be captured BEFORE the widget stands — standing re-homes it, so its
  own `:position()` becomes surface-local.** The suite's "a title-bar drag is inert" check is the flat UI's own
  hit test read twice (`> 0` points before, exactly `0` after), and the second read has to use the rectangle
  taken from the first: after `hafen.vr():widget():add`, `w:position()` answers `(0, 0)` in the surface, so
  re-deriving the points there probes the screen corner and "finds nothing" for the wrong reason — a green line
  that proves nothing. Same shape as 044.1's `px, py`. And accept a hit on any **descendant**: a `Window`'s
  content area resolves to the addon's own `AddonWidget` child, not to the window, so the probe walks
  `hit:parent()` up rather than comparing identity (036.3 reached the same place from the other side, by
  probing the content child directly). Finally, `LuaWidget.hitTest` honours `Widget.checkhit` at the leaf, so a
  transparent window corner is a legitimate miss — assert *at least one* point reachable before, never all of
  them.
- **(044.3) A counter that answers the same number for "correct", "correct another way" and "broken" is not an
  assertion — and it took two green runs to see it.** After finding that a standing window could freeze on its
  fade's first frame, the obvious guard looked like "a window must upload more than once while it fades in". It
  is wrong three ways over: a surface skipped while its content is `pending` can outlast the whole 0.1 s fade and
  upload **once**, already finished (a fresh client: 5 uploads for 5 surfaces); the same code after a `:reload`
  arms sooner, rides the fade and uploads **nine or more**; and a **frozen** surface also reads once. The check
  went red on a client whose windows were visibly perfect, which is the only reason it was re-examined rather than
  believed. Withdrawn, and what replaced it is the honest floor — *every standing surface uploaded at least once*
  — with the freeze left where it actually lives, on a `[manual]` line. **Rule: before writing an assertion over a
  counter, name the value it takes in the BROKEN case and in every correct case; if any two coincide, the counter
  cannot see the bug and the check will only ever mislead.** The corollary for `[manual]`: a line is not a
  fallback for laziness — sometimes it is the only instrument that distinguishes the cases.
- **(044.3) Assert what changed, not the literal a widget was built from — a `Window`'s `:size()` is its OUTER
  size.** A check compared `cam:size().x == 180` against `hafen.ui():window():size(180, 110)` and read 229: the
  builder sets the content size and the read answers content plus chrome. The fix was not a new number but a
  different claim — record the size at stand time and assert it is *unchanged* across the mode swap, which is what
  the check meant ("the window still answers its reads") and which no future chrome change can falsify.
- **(044.3) A widget put back on the flat UI returns ON TOP, so a reachability count can only grow.** The
  going-back check asserted `after == before` over nine hit-test probes and read `3 -> 5`: a re-homed widget is
  added at the **end** of its parent's chain, so it comes back above whatever was covering it. `>=` (plus
  `before > 0`, so the probe is known to have been real) is the claim that is actually true. Generally: *a check
  over z-ordered hit-testing states a floor, never an equality, unless the test owns every widget on screen.*

- **(044.4) A suite that synthesises input must leave NO press outstanding — a held one can maim the client.**
  Round 3 of `:t044-4` held a synthetic `MouseDown` on a button across a 0.8 s timer to prove a panel repaints
  while a gesture lasts. `haven`'s `Button` grabs the mouse on its press, so one real click by the maintainer
  inside that window orphaned the grab for the rest of the session (`ui-widgets.md`, same task) — every click
  anywhere then depressed the button, which reads exactly like a routing bug in the feature under test and cost
  a verification round to chase. Pair every press with its release **in the same statement**; if a claim seems to
  need a press held across frames, assert it some other way.
- **(044.4) An explicit `nil` is a REFUSAL, so a helper with an optional trailing argument must omit it, not
  forward it.** `poke(e, x, y, key, arg)` called `hafen.vr():pointer(key, sx, sy, arg)` unconditionally; for
  `MouseMove` there is no `arg`, and Lua passes the explicit nil, which the verb rejects by design (arity is the
  verb — §2 of `conventions.md`). Branch on `arg == nil` and make the shorter call. The refusal was correct and
  the suite was wrong, which is the good failure of that convention.
- **(044.4) `hafen.ui():at()` is a measuring instrument, not just an assertion.** Sweeping it over a widget's
  rectangle locates a child of the client's own chrome to the pixel (`ui-widgets.md`), ~2×size calls for two 1-D
  passes. Cheap enough to run once in a suite's setup, and it means a test aims at what is actually there rather
  than at a constant that is right on one client's DPI and wrong on another's.
- **(044.5) A "measure the child's box on the flat UI" helper must not sweep ONE column.** 044.4's `childRect`
  found a child by sweeping `hafen.ui():at()` down the window's middle column, then across the row it found.
  That works only for children the middle column happens to cross: a dropdown's drop arrow is right-aligned
  inside its box, and a picture button is only as wide as its picture, so both came back `nil` and the suite
  reported an honest but useless "not measurable". The fix is three sweeps — try ~9 columns at deciles until one
  hits, take that row's x range, then the y range down the middle of THAT — plus an optional **known row** for a
  child no column is guaranteed to cross (the arrow's row is its dropdown's own middle, because it is centred in
  it). Cheap (~2·sz calls) and exact on any chrome.
- **(044.5) A headless probe cannot reach any Widget verb: the handle needs a live UI.** `LuaWidget.live()`
  returns null when `AddonManager.ui` is null, so every read answers nil and every write is the D-112 chaining
  no-op — and the builders refuse outright ("no UI is up yet"). What a probe CAN still prove about a new verb is
  everything decided before the handle is resolved: `Args.required`/`Args.passed` refusals, a section-level
  verb's own argument checks, and that a section read answers `nil` rather than throwing with no UI. Anything
  past that is an in-game check, so put the refusal checks in the suite too.
- **(044.6) Assert the CLAIM, not the implementation detail that happens to be observable.** A check read the
  standing window's `c` and demanded `0,0` — the surface's pin, which is internal, restored every tick and
  therefore readable in a bad frame. It went red once on a value nothing in the feature promises. Rewritten to
  assert what the task actually claims (*the place it stands at is NOT the place the user arranged it at, which
  is why that place is recorded*), it is both stronger and unable to flap. The general shape: if a check can
  fail without anything the task promises being broken, it is testing the wrong noun.
- **(044.6) Put the diagnostic numbers on the PASS line when a later run has to compare across a `:reload`.**
  `check()` only prints its `got` argument on failure, so a value the maintainer must compare between two runs
  has to live in the claim string itself. Formatting the window's two positions into check 5's text turned "the
  put-back after a reload is wrong somehow" into one pasted line per run and a direct comparison — no extra
  command, no state carried across the reload (which a suite cannot do anyway: its Lua state dies with it).
- **(044.7) A check that measures against a SCREEN EDGE picks by margin, never first-past-the-post — the camera
  sways while the character stands still.** 044.7's suite stands eight probes in a ring, asks each through
  `widget:screen(x, y)` where its corners are drawn, and puts the panel at one that is off screen — then measures
  for 1.5 s that a culled panel draws 0 times. The first version took the **first** probe that was outside at all:
  it landed 6 px past the bottom edge (`2385,1375..2461,1416` on a `3440x1369` screen), the camera drifted it back
  into view halfway through, and the run reported `draws +138 uploads +138` over 267 frames — a red line for a
  feature that was working. Fixes, all three needed: pick the probe **furthest** out, not the first; require that
  margin to clear `max(100 px, 6% of the screen)` and say so in a guard line when nothing does ("zoom in and run
  again"); and **re-measure at the end of the window**, folding "it was still off screen when this closed" into
  the assertion so it can neither pass nor fail for the wrong reason. Rule: *any threshold a live camera can walk
  across is measured at both ends of the window and chosen with room to spare.*
- **(044.7) A probe measuring where something WILL be drawn must be the same kind and size as the thing.** The
  same suite's probes started as bare `hafen.ui():widget()` at half the panel's size, so the rectangle they
  measured was not the rectangle the panel would occupy. They are now built by the identical expression. The
  related in-game finding: `hafen.ui():widget()` is a bare content rectangle with **no chrome**, so a panel built
  from one shows nothing but what its own `Draw` handler paints — a `[manual]` line asking a human whether a
  panel "looks right" wants `hafen.ui():window():title(...)`, which brings the title bar, border and background
  (the 043.1 "a fixture that can be missed turns a green feature into a verification round" rule, again).
- **(044.7) A `[manual]` line can assert something the code does not do, and nothing catches it.** 044.7's line
  told the maintainer that walking far enough would despawn the panel's gob and take it down — but the panel it
  leaves standing is anchored to a **point**, and a free entity has no gob to lose. Nothing in the suite could
  fail on that: prose is not an assertion. Rule: *read every `[manual]` line back against the code path it
  describes, exactly as if it were a check — it is the one line in a suite with no compiler and no runtime.*
- **(044.8) An instrument that samples at "the last event" measures whatever fired LAST, which in a teardown
  is the wrong thing — name the moment you want, not the latest one.** Chasing a container record that read 0,
  a debug line recorded the two candidate reads on every `ItemAdded`/`ItemRemoved`. The close fires 37
  removals, so the line reported the state of an *emptied* container and answered nothing: `window=0` looked
  like proof that the read was broken when it only meant "after the last removal". Re-sampled on **adds only**
  it read `window=37 container=37` and settled the question in one round. Same class of error as 043.2's
  "classify by the event you recorded, not by a snapshot at report time".
- **(044.8) A `[dbg]` line is code and can crash the handler it is in — `tostring()` every field and dry-run
  the format string before shipping it.** The first attempt interpolated `box:type()` with `%s`; on a stale
  widget that read is `nil`, so the whole `Destroy` handler died with `bad argument: string expected, got nil`
  and the round produced no data at all. (The crash was itself the finding — the container is stale by then —
  but that was luck.) Both format strings were then run through the LuaJ CLI
  (`java -cp lib/brodgar/luaj-jse-3.0.1.jar lua fmt.lua`) with worst-case values before the next round; that
  costs a minute and buys back a whole in-game cycle.
- **(045.3) The 300-line ceiling is breached by CORRECTING a sentence, not by adding a section — on a page at the
  ceiling, correct in place at equal line count.** `conventions.md` sat at exactly 300. Replacing two wrapped lines
  of a callout with a three-line correction put it at **301**, and nothing about the edit looked like growth: the
  diff was one sentence for another. The §12 size check is the only thing that catches this, so run it on every
  page touched and not just the ones a section was added to. The fix is not to split the page — it is to write the
  correction to the same line count (a 2-for-2 swap), which took one attempt once the constraint was known. Same
  class as the wrap check: **the unit that matters is the wrapped line, and prose edits move it invisibly.**
- **(047.1) For a surface that GRABS input, a suite runs in two halves of one command: `:tNNN-X` arms, and
  `:tNNN-X done` closes.** An open `FlowerMenu` grabs mouse **and** keyboard, so nothing can be typed while
  one is up and no timer-based summary can know when the maintainer has finished. Splitting on the argument
  (043.2's `:t043-2 gone` shape) gives the arming half the synchronous assertions plus the `[manual]` steps,
  lets the armed handlers print their own `[pass]` lines live as each gesture happens, and puts the
  cross-round invariants (`opens == closes`, none overlapping, one close with a label and one without) plus
  the single `[summary]` in the second half. Keep the counters at file scope so they survive between the two,
  and reset them in the arming half so a re-run starts clean — and `:off()` the previous subscriptions there
  too, or a second arming double-counts every event.
- **(047.1) The 033.3 probe recipe extends to driving `haven` WIDGET seams directly, if the res jars go on
  the classpath.** Constructing a real `FlowerMenu` headlessly needs
  `-cp "probe-tmp;lib/brodgar/luaj-jse-3.0.1.jar;lib/jglob.jar;bin/builtin-res.jar;bin/hafen-res.jar"` —
  `jglob` for `Resource.<clinit>`, and the two res jars because `Text.<clinit>` load-waits on `ui/fraktur`.
  The GL provider then prints a `NoClassDefFoundError: com/jogamp/opengl/GLException` stack that is **not
  fatal** (`haven.iosys.Providers` logs it and moves on), so read past it to the probe's own output. To make
  the addon-side subscription fire, reflect the throwaway `Addon` into `AddonManager.addons` — `installHafen`
  alone does not register it, so `hasSub` finds nobody and every `fire*` is silently a no-op. That bought the
  whole event-pairing proof (3 opened / 3 closed, the right label on each, repeat calls inert) before the
  client was started, on a feature whose in-game half needs a human holding a mouse.
- **(047.2) A read-only suite CAN prove a gate properly — assert the refusal from inside the event that says the
  target is really there.** `TESTING.md` forbids a suite declaring `permissions`, so a gated verb is proven by its
  refusal; but a refusal asserted with nothing open is weak evidence, because it could equally have been "there was
  nothing to pick". Arming a `FlowerMenuOpened` handler and re-asserting the same four refusals **with a real ring on
  screen** — `:select(1)` against a petal that genuinely sits at position 1, `:select("<its own first caption>")`
  against a caption that genuinely matches, `:cancel()` with something there to cancel — leaves the permission as the
  only explanation. That is also the half a headless probe cannot reach, so it is exactly what the in-game round is
  worth spending. Generalises to any gated verb whose subsystem emits an "it exists now" event.
- **(047.2) Do NOT reach for an example addon (`walker`, `planner`, …) to drive a suite's verification.** D-085 is
  usually read as "do not depend on another *suite*", but leaning on an example addon breaks the same promise in a
  worse way: it makes the maintainer enable a write addon and clear a consent dialog before a single line of THIS
  task can be checked. The example addon is documentation and ships with the task; it is never part of the ask. If
  the suite genuinely cannot reach a behaviour alone, that behaviour is a `[manual]` line the maintainer performs
  with their own mouse — not a second addon to install.
- **(047.2) Splitting the target lookup off a verb makes its whole resolution headless-testable.** `select` =
  `selectOn(required(verb), key)`: `required` is the one part that needs a live `UI`, so `selectOn(fm, key)` takes a
  hand-built `FlowerMenu` and the entire index/caption/refusal matrix runs under the 033.3 classpath with a
  subclass whose `choose(Petal)` just records — 10 checks (position, caption, the `"3"` trap, both range ends,
  fractional, wrong type) before the client is ever started, and no `wdgmsg` on the wire. Same "pure builder + thin
  sender" split as 4d's `clickGobArgs`, applied to a resolver instead of an encoder.
- **(047.3) The fabricated-UI probe reaches all the way to a `haven` widget seam — drive the LUA verb, not the
  Java under it.** 036.1/036.3's recipe (Unsafe-allocate `UI` + a `RootWidget`, hand-link `child`/`lchild`,
  point `AddonManager.ui` at it) composes with 047.1's (real `FlowerMenu` objects, res jars on the classpath):
  hang the menu under the fabricated root, set `fm.ui`, call the engine seam (`FlowerMenuApi.opened`) and then
  read the answer back through `hafen.get("flowermenu").call().method("gob")` — the same call Lua makes. That
  covered the whole attribution matrix headlessly (claim, re-read identity, a second announce, the fade, a
  moved press point, consume-once, a ground press) plus both grammar refusals: **14/14 before the client
  started**, on a feature whose in-game half needs a human holding a mouse.
- **(047.3) A suite whose gestures are ARMED one at a time can be half dry-run — arm the NEGATIVE one.** The
  shipping suite's tree gesture needs a real gob (`:name()` reads the resource, which needs a live `OCache`),
  but its *negative* gestures assert `:gob() == nil` with a ring up — and a ring with no token behind it is
  exactly what the probe can build. Calling the registered slash command for the arming half and then firing a
  token-less menu ran 7 of the suite's own verdict lines green headlessly, including the branch that matters
  most. Generalises: when a suite is split into armed steps, at least the steps whose claim is an ABSENCE are
  usually reachable without the live game.
- **(047.3) Two falsifications, two distinct single-red signatures — that is the report worth writing down.**
  Dropping the `lcc` equality from the token reddened *only* "a ring that opens at a different point answers
  nil"; clearing the attribution at the close reddened *only* "while the ring fades it still names it". Neither
  bug touched the other's check, which is what proves the two mechanisms guard two different claims rather
  than being one mechanism counted twice (the 034.2/035.1 rule: one falsification per mechanism).

- **(048.1) A protected verb's whole refusal surface is HEADLESS — the 033.3 probe runs the suite nearly
  green before the client is started.** `requireActions` needs neither a `UI` nor a session, and it runs
  before everything that does (D-213), so every gate assertion, every `Retired` throw and every closed-vocabulary
  refusal answers under `Sandbox.create()` + `installHafen` + `Manifest.load(dir)` alone. 048.1's suite reported
  **11 pass / 2 fail** headlessly, and the two fails were precisely the checks that need a world (the player-Gob
  premise and the refusal asserted on the live player Gob) — so the in-game round had one prediction to confirm
  rather than thirteen results to read. Rule: *for a task whose proof is refusals, treat a non-green headless run
  as a bug in the suite, and write down which lines are expected to be red and why.*
- **(048.1) A never-nil handle gives a read-only suite a receiver with no world.**
  `hafen.world():gob():get(-1)` mints a `LuaGob` for an id nothing ever published (`:get(id)` is documented as
  never nil, `gob:exists()` being the liveness test), so `hafen.world():gob():get(-1):click(3)` is a real colon
  call on a real entity that reaches the gate — proving *gate before live-object lookup* both headlessly and
  in-game, where asserting it on the player's own gob could not distinguish the two orders. Generalises to any
  entity whose `:get` interns rather than searches.

- **(048.2) A suite cannot SEND a protected verb, but it can WATCH one — and that turns the last `[manual]`
  eyeball into `[pass]` lines.** `hafen.event():action():on(msg, fn)` fires at the `UI.wdgmsg` choke point with
  the arguments **fully resolved**, and subscribing is an observe surface, so a suite declaring no permissions
  may arm it. The maintainer fires the `:lua` one-liner (the console owner declares everything) and the suite
  asserts what went out. 048.2 keyed the record by `#ev:args()` — 1 / 3 / 8 distinguish the item, ground and gob
  forms of `itemact` — so `:t048-2 sent` could prove the gob form carried `Gob.GobClick.clickargs` verbatim and
  aimed at the object's own position, which is the feature's whole new capability and was otherwise a human
  judging a screen. Arming the recorder at **file scope** is not "a suite starting itself" (TESTING.md): it
  prints nothing, writes nothing and races nothing — the 043.2 `GobRemoved` precedent, and a send cannot be
  observed after the fact.
- **(048.2) A `[manual]` line aimed by RESOURCE FILTER can miss, and the miss reads exactly like the feature
  failing.** `hafen.world():gob():nearest("terobjs/plants")` answered nil with no plant nearby, so
  `hand:use(nil)` correctly refused with *target is required* — and that is what came back in the verification
  log for the one line proving the task's headline capability. Nothing was wrong. Round two aimed with
  `nearest(function(o) return not o:isPlayer() end)`, which cannot come back empty in a populated world, and
  moved the real claim into an assertion (above) so the aim no longer has to be lucky. This is 043.1's *a
  fixture that can be missed turns a green feature into a verification round*, one step out from a shipped asset
  to the live world: **a manual line must not depend on a fixture the world may not have.**
- **(048.3) When the gate runs FIRST, a protected surface is headless-testable WHOLE — and an entity that was
  never live is a free stale one.** D-213's ordering (`requireActions` before the argument check and before the
  live lookup) means a refusal needs no session at all, so the 033.3 probe can reach every verb of an entity the
  game normally supplies: `new haven.GItem(null)` constructs with no `UI`, `LuaItem.of(owner, it)` interns it,
  and the entity's whole metatable is callable. Run it under **two owners in the one probe** —
  `Manifest.test(id)` declares nothing (the shipped suite's own shape) and `Manifest.internal(id)` declares
  everything (what the `:lua` console runs as) — and both sides of every gate are proved before the maintainer
  logs in: the undeclared owner gets the permission error naming the verb, the declared one falls through to the
  *next* refusal. And because a `GItem` with no `ui` can never satisfy `live()`, that second run is also the
  **stale** case, which the shipped suite can only reach by holding a handle across a `[manual]` move. 048.3
  pre-checked all four verbs, the argument refusals and the retired-row throw this way; the in-game run added
  nothing but the reads.
- **(048.3) `ant hafen-client` does not install a suite — `ant bin` does.** The build check compiles into
  `build/classes`; `bin/addons/` (the directory the running client actually scans, TESTING.md) is populated by
  the `bin` target's `<copy todir="bin/addons">`. Hand a task over without it and the maintainer restarts into a
  client that has the new engine and none of the new suite — a bare "no such command" with the same symptom as
  042.11's file-scope throw and a different cause. Also: compile a throwaway probe **into `build/classes`** and
  delete the class afterwards; a `-cp` naming a scratchpad directory alongside `build/classes` is exactly the
  `;`-separated path MSYS mangles (see the Windows/Git-Bash entry above), and the failure reads as
  `ClassNotFoundException` on a class that is plainly on disk.
- **(048.4) A wire recorder must IDENTIFY its own sends, not read "the last two" — when the message is one a
  PLAYER also sends.** 048.2's recorder trick generalises, but its indexing does not. `"place"` and `"sel"` are
  exactly what building and dragging a tile-area tool put on the wire, so the maintainer following a `[manual]`
  line that said *start building something so an object is on your cursor* placed it for real, and the last two
  recorded `"place"` messages were **theirs** (angle `-16384`, dx `-2048`). The suite asserted this task's claims
  against a stranger's send and reported it as a broken angle encoding — a red line naming the one thing that
  was provably correct (the pure builders had been checked headlessly: `0 → 0`, `π/2 → 16384`, dx exactly 1024).
  `"itemact"` had hidden this: 048.2 keyed by `#ev:args()`, and a player *can* fire itemact, but the run happened
  to be clean. The fix is two lines and makes the buffer self-describing: **the main run CLEARS the recorder, and
  the `sent` run asserts there are EXACTLY N** — a stray hand gesture then reports itself by count
  (*"3 recorded — a place you did BY HAND is in there too"*) instead of silently becoming the evidence. And the
  fixture went with it: the firing line now says *with NOTHING on your cursor*, because with an empty cursor the
  server ignores `"place"` and the wire is what is being asserted anyway; the visual "watch it land" moved to its
  own `[manual]` line **after** `sent` is green. That is 048.2's *a manual line must not depend on a fixture the
  world may not have*, one turn further: **a manual line must not ask for a fixture whose setup pollutes the
  evidence.** Rule: before recording a `wdgmsg`, ask whether a human at the keyboard can send the same name — if
  yes, bound the window (clear + exact count) rather than indexing from the end.
- **(048.4) `:lua local p = …; f(p)` echoes NOTHING, so never write a `lua= …` expectation for a chunk that
  starts with a statement.** The console evaluates the line and prints `lua= <value>` only when there is a value;
  a chunk beginning with `local` is a statement, not an expression, so a perfectly successful two-call firing
  prints nothing at all. 048.4's `[manual]` line claimed `each answers lua= "hafen.world()"` (true of the verb —
  it does hand the section back so writes chain) and the maintainer correctly saw silence. Either write the
  expectation as *no error, and nothing echoed*, or make the line an expression (`:lua tostring(f(p))`) if the
  return value is the thing under test.
- **(048.5) With no session, a gated verb's error tells you the gate's ORDER — run the probe under two owners
  and read which message comes back.** 048.3 ran its probe under `Manifest.test` (declares nothing) and
  `Manifest.internal` (declares everything); the sharper use is that headlessly the *live lookup can never
  succeed* — there is no `GameUI`, so `LuaPagina.live(res)` is null for every resource name. That turns the
  declared owner's run into the ordering proof: if it comes back *"is not in the menu"* the gate has already
  run and passed, and if it came back *"did not declare"* the gate would be sitting behind the lookup. Both
  halves of D-213 from one throwaway, no client. The entity handle costs nothing to mint either — `LuaPagina
  .of(owner, "paginae/act/dig")` is package-private, so a same-package probe fabricates a handle for a
  resource the catalogue has never heard of and reaches the verb with no world at all.
- **(048.5) Drive the shipping suite headlessly for what it does with NOTHING there, not only for what it
  proves.** The 033.3 probe normally answers *does it load and register*; calling the registered `run()` on an
  empty catalogue answers a second question worth as much — *does it degrade into `[fail]` lines, or does it
  throw halfway and print no `[summary]` at all*. Five of 048.5's ten checks are structurally unreachable
  without a live action menu, and confirming the other five stay green while those five report themselves is
  what makes the pasted-back block readable in one pass. It also catches the Lua trap that shape invites: a
  `got` expression like `(x == nil) and "none" or x:name()` is only safe because `and`/`or` short-circuit —
  write it as a plain argument and the nil deref fires before `check()` is ever called.

- **(048.6) Put a RECORDER between the fabricated widget and the fabricated root, and the wire itself becomes
  headlessly assertable.** 036.1's recipe (`Unsafe.allocateInstance(UI.class)` + reflective `widgets`/
  `rwidgets` + an `Unsafe`-allocated `RootWidget`, tree wired by assigning `Widget.parent`) reaches every
  read and every refusal, but a *successful* send still dies at the top: `wdgmsg` walks UP, and the fabricated
  root's `ui` is null. One anonymous `new Widget(sz) { public void wdgmsg(Widget sender, String msg, Object...
  a) { record… } }` parented to the root, with the widget under test parented to IT, turns that last NPE into
  the most valuable line the probe prints — `from #77 "click" [(3,4) <Coord>, 1 <Integer>, s <String>]`, i.e.
  the sender id, the message name and the marshalled Java types, which is precisely what the in-game suite can
  only observe through the action stream. 14/14 headless before the maintainer logged in, including the good
  send. Also: run the probe under **two** manifests (`Manifest.test` declares nothing, `Manifest.internal`
  declares everything) — with the gate first (D-213) the undeclared run proves the ORDER and the declared run
  proves the messages, and neither can be seen from the other.
- **(048.7) A retirement message is a CLAIM about the API, so assert it rather than trusting it.** A `Retired`
  row is prose that tells a stranded caller where to go, and nothing in the engine checks that the destination
  exists — a typo, or a verb renamed after the row was written, produces a message that sends a porting author to
  a door that is not there, and every test still passes because the throw *did* happen. 048.7's suite reads back
  every replacement its section message names, off the very handles the message spells
  (`hafen.player().move`, `hafen.world().place`, `hafen.flowermenu().select`, …, plus `gob:click` on
  `hafen.world():gob():get(-1)`, which interns rather than searches and so is a receiver with no world behind it),
  and fails naming any that is not a function. Eight doors, one `[pass]` line. Worth doing wherever a message
  names an API rather than describing one — which is every row in that table.
- **(048.7) A deletion-only task has no `[manual]` line, and that is a property worth checking for.** Everything
  048.7 shipped is an absence — a section gone, two verbs gone, a helper moved — and an absence is exactly what a
  program can assert: the throw, its message, and the continued presence of the neighbours. The suite came back
  10/10 headless and 10/10 in-game with zero human steps. If a task that only deletes still wants a `[manual]`
  line, the line is usually asking a human to confirm something the API can be asked directly.
