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
