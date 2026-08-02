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
