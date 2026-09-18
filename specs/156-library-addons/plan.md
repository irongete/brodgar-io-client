# 156 — Library addons: plan

> Written for an implementer who decides nothing. Every name, string and edit is stated here; where a
> code block is given it is the reference implementation, to be typed as written and adjusted only where
> the compiler demands (an import, a checked exception). Java is source/target **1.8**: no `var`, no
> `List.of`, no switch expressions; lambdas and `Supplier` are fine. The `docs/` pages are written as
> `DOCUMENTATION.md` says: tables carry the facts, present tense, no history, descriptive variable names.

## 0. Documentation: `DOCUMENTATION.md` is the standard, without exception — IMPORTANT

Every page and every row this feature writes follows `DOCUMENTATION.md` **§1–§11 to the letter**, in the
same voice and shape as the pages already in `docs/addons/`. Read it before touching a page, and again
before handing the task over. The drafts in §4 below are already written in that form: **type them as
drafted**; do not add, soften, explain or "improve" them. What that means, concretely:

| Rule (`DOCUMENTATION.md`) | In this feature |
|---|---|
| §3 Voice | Developer-first, direct, scannable. Second person, present tense, active. No narrative, no metaphors, no philosophy, no rhetorical questions, no filler ("simply", "just", "note that", "of course"). Limits are stated as facts with their reason. |
| §4 Structure | A reference page is: `#` title, one sentence, one runnable `lua` block (≤ 12 lines), `---`, tables of methods (Method · Returns · Permission · Description), rule tables (Rule · Detail), `See Also`. Nothing else. |
| §5 Headings | `#` once, in Title Case like its neighbours (`hafen.client: Keybindings`, `Data Types: The Widget Layer`, `Saved Data`). `##` sections and `###` sub-sections in sentence case. No punctuation that breaks an anchor. |
| §6 Examples | Runnable as written. **Descriptive variable names only** — `api`, `toast`, `handle`, `text`, `box`; never `s`, `w`, `p`, `g`, `ev`, `fn`, `cb`. Only symbols that exist in `src/`. |
| §7 Facts | Tables carry the facts; prose is one line of context. State what a read answers when the thing is missing (`nil`). No count statements ("the four verbs"). No benchmark numbers. |
| §8 No history | Present tense only. Never "was", "used to", "now supports", "new in 156". A design boundary is a present-tense fact. |
| §9 Mechanics | Relative links only. Under 300 lines a page. Blockquotes only for a critical warning or a permission gate. |
| §10 Prohibited | No `specs/` paths, no task numbers, no Java class or method names, no `src/` paths, no TODO or "coming soon" — in `docs/addons/**`, ever. |
| §11 Checklist | Before handing over: grep the page for single-letter variables; every method row has Returns and Permission; every link resolves; no literary prose; no obsolete names. |

A sentence that reads like an essay, a paragraph that explains why a rule is good, an example with `s`
or `w`, a "note that", a Java name — each is a defect the review sends back. The existing pages are the
model: open `api/client/keybindings.md` and `api/store/README.md` beside the page you write and match
them.

## 1. Approach

**One door, one walker, one order.**

- **One door.** Every cross-addon call enters the callee exactly as its own handler would:
  `AddonManager.enterLua(owner)` (the lock and the running-addon stack), `Sandbox.arm(owner.env)` (a fresh
  instruction budget), the `tickLuaNanos`/`catNanos` accounting. A new `AddonManager.callThrough` is
  `called()` with one difference: an error is **re-raised in the caller** instead of being logged.
- **One walker.** Every value that crosses between two addons goes through `Crossing.copy`: a function
  becomes a `Crossing.Wrapper` that enters *its* owner's door; a table becomes a fresh read-only table;
  a string, number, boolean or `nil` passes; userdata (any bridge handle) is refused. The same walker
  runs on arguments, on return values and on the export itself.
- **One order.** `AddonRegistry.plan(loading)` is a pure function from manifests to a load order plus the
  cycle errors. The loader only follows it. Being pure, `jshell` proves it with fixture manifests.

### 1.1 The vocabulary (spell these exactly)

| Where | Text |
|---|---|
| Collection name | `hafen.client():addons()` |
| Handle `tostring` | `Addon(<id>)` |
| Handle `__name` | `Addon` |
| Unknown verb on a handle | `addon has no verb '<x>' — an addon the client discovered: it answers :api() :exists() :id() and :info()` (the vocabulary is sorted; built by `Refusal.closedIndex("addon", methods, "an addon the client discovered")`) |
| Dot call on a handle | `addon:<verb>() — use a COLON call on an Addon object (hafen.client():addons():get(id))` |
| `:get` with a non-string | `hafen.client():addons():get(id): expected an addon id, got <typename>` |
| `export` arity | `Args.only(a, 1, "hafen.client():addons():export")` and `Args.required(a, 2, "hafen.client():addons():export", "t")` |
| `export` not a table | `hafen.client():addons():export(t): t must be a table, got <typename>` |
| `export` twice | `hafen.client():addons():export(t): already exported — an addon exports once, in its file body` |
| `export` bad value | `hafen.client():addons():export(t): '<path>' is a <Kind> — export functions and plain values (strings, numbers, booleans, tables of them)` |
| Write into an export view | `<id>'s export is read-only — it is your copy; a change belongs in the library, through a function it exports` |
| Write into a crossed table | `a table from <id> is read-only here — it is a copy taken as it crossed; answer with a return value` |
| `__metatable` of both | the string `read-only` |
| Wrapper label, export member | `<lib-id>.<path>` (`toast.show`, `toast.ui.open`) |
| Wrapper label, crossed function | `a function of <owner-id>` |
| Error inside | `<label>: <reason>` — `reason` is `Refusal.reason(e)` |
| Owner torn down | `<label>: <owner-id> is disabled` |
| Entry declined | `<label>: <owner-id> is busy on another thread` |
| Fatal inside | `<label>: <owner-id> failed fatally and is quarantined` |
| No running addon | `<label>: called outside any addon's code` |
| Handle as argument | `<label>: argument <n> is a <Kind> — a handle does not cross to another addon; hand it a function` |
| Handle inside an argument table | `<label>: argument <n>'s field '<path>' is a <Kind> — a handle does not cross to another addon; hand it a function` |
| Handle as a result | `<label>: the result is a <Kind> — a handle does not cross to another addon; hand back a value, a table or a function` |
| Handle inside a result table | `<label>: the result's field '<path>' is a <Kind> — a handle does not cross to another addon; hand back a value, a table or a function` |
| Manifest dependency form | `'<entry>' is not a dependency — write "<id>" or "<id>>=MAJOR.MINOR.PATCH" (1.2.0, with an optional pre-release such as 1.2.0-beta.1)` |
| Hard dependency missing | `needs <id>, which is not installed` |
| … disabled | `needs <id>, which is disabled` |
| … out of date | `needs <id>, which is out of date` |
| … manifest error | `needs <id>, which has a manifest error` |
| … failed (body threw, cycle, its own dependencies) | `needs <id>, which failed to load` |
| Minimum not met | `needs <id> >= <min>, <found> installed` |
| Dependency's version unparsable | `<id>'s version '<v>' is not a version (a version is MAJOR.MINOR.PATCH, such as 1.2.0, with an optional pre-release such as 1.2.0-beta.1)` |
| Hard cycle | `depends in a cycle: a -> b -> c -> a` (starting and ending at the member's own id) |
| Optional edge dropped (log only) | `optional dependency <dep> of <id> closes a cycle and is not ordered` |
| Cascade reason | `needs <id>` (the row reads `auto-disabled (needs <id>)`) |
| Tooltip lines | `Needs: <a>, <b>>=1.2.0` · `Optional: <c>` · `Used by: <x>, <y> (optional)` |
| `:info().status` words | `loaded` · `disabled` · `not loaded` · `error` · `outdated` · `auto-disabled` · `manifest error` |
| Profiling category | `exports` (`Addon.C_EXPORT = 5`) |

`<Kind>` is `Crossing.kind(v)`: `"collection"` for a `LuaCollection`, otherwise `tostring(v)` up to the first
`(` (`Timer(after 60s)` → `Timer`, `Options(video)` → `Options`, `Sub(GobAdded)` → `Sub`), and the LuaJ
`typename()` when that is empty. `<path>` joins string keys with `.` and writes a numeric key as `[n]`
(`items[3].icon`); any other key type is written with `tostring`.

## 2. Design, class by class

### 2.1 `Manifest` — `Dependency`

```java
/** One entry of dependencies/optional_dependencies: an id, and the minimum version it names or null. */
public static final class Dependency {
    public final String id, min;
    Dependency(String id, String min) { this.id = id; this.min = min; }
    public String toString() { return (min == null) ? id : (id + ">=" + min); }
}
public final List<Dependency> dependencies, optionalDependencies;

/** Both lists, hard first — what the load order reads. */
public List<Dependency> allDependencies() {
    List<Dependency> out = new ArrayList<Dependency>(dependencies);
    out.addAll(optionalDependencies);
    return out;
}

private static List<Dependency> deplist(Map<String, Object> m, String key) {
    List<Dependency> out = new ArrayList<Dependency>();
    for(String entry : strlist(m, key)) {
        int at = entry.indexOf(">=");
        String id = (at < 0) ? entry : entry.substring(0, at);
        String min = (at < 0) ? null : entry.substring(at + 2);
        boolean ok = !id.isEmpty() && (id.indexOf('/') < 0) && (id.indexOf('\\') < 0)
            && !id.contains(">") && !id.contains("=") && (id.trim().equals(id))
            && ((min == null) || io.brodgar.addon.registry.Semver.valid(min));
        if(!ok)
            throw new IllegalArgumentException("'" + entry + "' is not a dependency — write \"<id>\" or"
                + " \"<id>>=MAJOR.MINOR.PATCH\" (1.2.0, with an optional pre-release such as 1.2.0-beta.1)");
        out.add(new Dependency(id, min));
    }
    return out;
}
```

`load` calls `deplist(m, "dependencies")` and `deplist(m, "optional_dependencies")`; the constructor, `internal`
and `test` take `List<Dependency>` (`Collections.<Dependency>emptyList()`).

### 2.2 `AddonRegistry` — discovery, status, the plan, the three-phase load

```java
/** One folder the last load found: its manifest, or why it could not be read. */
static final class Discovered {
    final String id; final File dir; final Manifest manifest; final String manifestError;
    Discovered(String id, File dir, Manifest manifest, String manifestError) {
        this.id = id; this.dir = dir; this.manifest = manifest; this.manifestError = manifestError;
    }
}
private static volatile Map<String, Discovered> discovered = Collections.emptyMap();   // by id, sorted
/** Every folder with a manifest.json as of the last load, by id. */
static Map<String, Discovered> discovered() { return discovered; }

/** The load state of one id, as a word and the sentence behind it; null for an id the last load did not find. */
static final class Status {
    final String word, reason;
    Status(String word, String reason) { this.word = word; this.reason = reason; }
}
static Status status(String id) {
    Discovered d = discovered.get(id);
    if(d == null) return null;
    String warn = AddonManager.autoDisabledWarn.get(id);
    if(warn != null) return new Status("auto-disabled", warn);
    if(findLoaded(id) != null) return new Status("loaded", null);
    String err = loadErrors.get(id);
    if(err != null) return new Status("error", err);
    if(d.manifestError != null) return new Status("manifest error", d.manifestError);
    if(outdated.containsKey(id)) return new Status("outdated", ApiVersion.why(d.manifest.apiVersion));
    if(!isEnabled(id)) return new Status("disabled", null);
    return new Status("not loaded", null);
}
/** findLoaded, for the package: the loaded Addon of {@code id}, or null. */
static Addon loaded(String id) { return findLoaded(id); }
/** Whether {@code a} is the loaded addon of its id right now (a torn-down one is not). */
static boolean isLoaded(Addon a) { return (a != null) && (a.manifest != null) && (findLoaded(a.manifest.id) == a); }
/** The loaded addons whose HARD dependencies name {@code id}. */
static List<Addon> hardDependants(String id) {
    List<Addon> out = new ArrayList<Addon>();
    for(Addon a : addons)
        if(a.manifest != null)
            for(Manifest.Dependency d : a.manifest.dependencies)
                if(d.id.equals(id)) { out.add(a); break; }
    return out;
}
```

`liveStatus(id)` keeps its exact output and branch order (auto-disabled, loaded, error, outdated, disabled,
not loaded) and is left as it is; `status` is the second reader of the same maps, and the manifest-error
branch belongs to it alone (the panel reads that one off `AddonInfo.manifestError`).

**The plan** (pure; no client state):

```java
static final class LoadPlan {
    final List<String> order = new ArrayList<String>();
    final Map<String, String> errors = new LinkedHashMap<String, String>();    // hard-cycle members
    final List<String> notes = new ArrayList<String>();                        // dropped optional edges
}

/** The order the manifests in {@code loading} run in, and the members of every hard cycle. Pure. */
static LoadPlan plan(Map<String, Manifest> loading) {
    LoadPlan out = new LoadPlan();
    Map<String, java.util.TreeSet<String>> dependants = new java.util.HashMap<String, java.util.TreeSet<String>>();
    Map<String, Integer> indegree = new java.util.HashMap<String, Integer>();
    for(String id : loading.keySet()) { dependants.put(id, new java.util.TreeSet<String>()); indegree.put(id, 0); }
    for(Manifest m : loading.values())
        for(Manifest.Dependency d : m.allDependencies())
            if(loading.containsKey(d.id) && !d.id.equals(m.id) && dependants.get(d.id).add(m.id))
                indegree.put(m.id, indegree.get(m.id) + 1);
    java.util.TreeSet<String> ready = new java.util.TreeSet<String>();
    for(Map.Entry<String, Integer> e : indegree.entrySet()) if(e.getValue() == 0) ready.add(e.getKey());
    java.util.TreeSet<String> left = new java.util.TreeSet<String>(loading.keySet());
    while(!left.isEmpty()) {
        if(!ready.isEmpty()) {
            String id = ready.pollFirst();
            left.remove(id);
            out.order.add(id);
            for(String d : dependants.get(id)) { int n = indegree.get(d) - 1; indegree.put(d, n); if(n == 0) ready.add(d); }
            continue;
        }
        // Stalled: everything left waits on something left. First an OPTIONAL edge that closes a cycle is
        // dropped (smallest dependant id, its first such entry) — ordering it is impossible, refusing it is
        // not asked for.
        boolean dropped = false;
        for(String id : left) {
            for(Manifest.Dependency d : loading.get(id).optionalDependencies) {
                if(left.contains(d.id) && dependants.get(d.id).remove(id)) {
                    int n = indegree.get(id) - 1; indegree.put(id, n);
                    out.notes.add("optional dependency " + d.id + " of " + id + " closes a cycle and is not ordered");
                    if(n == 0) ready.add(id);
                    dropped = true; break;
                }
            }
            if(dropped) break;
        }
        if(dropped) continue;
        // Only hard edges remain among what is left, so a hard cycle exists: every id that walks back to
        // itself along hard edges is a member, each with its own path.
        List<String> members = new ArrayList<String>();
        for(String id : left) {
            List<String> path = cyclePath(id, left, loading);
            if(path != null) { members.add(id); out.errors.put(id, "depends in a cycle: " + String.join(" -> ", path)); }
        }
        if(members.isEmpty()) throw new IllegalStateException("stalled without a cycle: " + left);   // cannot happen
        for(String id : members) left.remove(id);
        for(String id : members)
            for(String d : dependants.get(id))
                if(left.contains(d)) { int n = indegree.get(d) - 1; indegree.put(d, n); if(n == 0) ready.add(d); }
    }
    return out;
}

/** start -> … -> start along HARD dependencies among {@code left}, or null when start is on no such cycle. */
private static List<String> cyclePath(String start, Set<String> left, Map<String, Manifest> loading) {
    java.util.ArrayDeque<String> path = new java.util.ArrayDeque<String>();
    return walk(start, start, left, loading, path, new java.util.HashSet<String>()) ? new ArrayList<String>(path) : null;
}
private static boolean walk(String at, String target, Set<String> left, Map<String, Manifest> loading,
                            java.util.ArrayDeque<String> path, Set<String> visiting) {
    path.addLast(at); visiting.add(at);
    for(Manifest.Dependency d : loading.get(at).dependencies) {
        if(!left.contains(d.id)) continue;
        if(d.id.equals(target)) { path.addLast(target); return true; }
        if(!visiting.contains(d.id) && walk(d.id, target, left, loading, path, visiting)) return true;
    }
    path.removeLast();
    return false;
}
```

**`loadAll`, in three phases.** Keep every existing log line and comment that still applies; the body of
phase 3's `try` is today's body from `Globals g = Sandbox.create();` to the `catch(Exception e)` unchanged,
with `sub` → `d.dir` and `sub.getName()` → `id`.

```java
static void loadAll() {
    loadGen++; reloadNeeded = false; autoDisabledWarn.clear(); loadErrors.clear(); outdated.clear();
    scanAddonDefaults();
    File dir = addonDir();
    log("addons dir: " + dir);
    File[] subs = dir.listFiles(File::isDirectory);
    Map<String, Discovered> found = new java.util.TreeMap<String, Discovered>();
    if(subs == null) { log("no addons/ directory"); discovered = Collections.unmodifiableMap(found); return; }
    Set<String> disabled = disabledSet();
    boolean loadOutdated = loadOutdated();
    Map<String, Consent> consented = consentedMap();
    // ---- 1. discover: every folder with a manifest, enabled or not, parsed or not
    for(File sub : subs) {
        if(!new File(sub, "manifest.json").isFile()) continue;
        String id = sub.getName();
        try {
            found.put(id, new Discovered(id, sub, Manifest.load(sub.toPath()), null));
        } catch(Exception e) {
            String why = Refusal.reason(e);
            found.put(id, new Discovered(id, sub, null, why));
            log("failed to load '" + id + "': " + why);
        }
    }
    discovered = Collections.unmodifiableMap(found);
    // ---- 2. who loads, and in what order
    Map<String, Manifest> loading = new java.util.TreeMap<String, Manifest>();
    for(Discovered d : found.values()) {
        if(d.manifest == null) continue;
        if(disabled.contains(d.id)) { log("skipping disabled addon '" + d.id + "'"); continue; }
        String why = ApiVersion.why(d.manifest.apiVersion);
        if(why != null) {
            if(!loadOutdated) { outdated.put(d.id, ApiVersion.label(d.manifest.apiVersion)); log("skipping out of date addon '" + d.id + "': " + why); continue; }
            log("loading out of date addon '" + d.id + "': " + why);
        }
        loading.put(d.id, d.manifest);
    }
    LoadPlan plan = plan(loading);
    for(String note : plan.notes) log(note);
    for(Map.Entry<String, String> e : plan.errors.entrySet()) { loadErrors.put(e.getKey(), e.getValue()); log("error in " + e.getKey() + ": " + e.getValue()); }
    // ---- 3. run, in order, each behind its hard dependencies
    for(String id : plan.order) {
        Discovered d = found.get(id);
        Manifest m = d.manifest;
        String unmet = unmetDependency(m, found, disabled);
        if(unmet != null) { loadErrors.put(id, unmet); log("error in " + id + ": " + unmet); continue; }
        try {
            Globals g = Sandbox.create();
            // … today's body …
        } catch(Exception e) {
            log("failed to load '" + id + "': " + Refusal.reason(e));
        }
    }
    log(addons.size() + " addon(s) loaded");
}

/** The first hard dependency of {@code m} that is not standing, as the error the dependant carries; null when all stand. */
static String unmetDependency(Manifest m, Map<String, Discovered> found, Set<String> disabled) {
    for(Manifest.Dependency dep : m.dependencies) {
        Discovered d = found.get(dep.id);
        if(d == null) return "needs " + dep.id + ", which is not installed";
        if(d.manifestError != null) return "needs " + dep.id + ", which has a manifest error";
        if(disabled.contains(dep.id)) return "needs " + dep.id + ", which is disabled";
        if(outdated.containsKey(dep.id)) return "needs " + dep.id + ", which is out of date";
        Addon loaded = findLoaded(dep.id);
        if((loaded == null) || loadErrors.containsKey(dep.id)) return "needs " + dep.id + ", which failed to load";
        if(dep.min != null) {
            String v = loaded.manifest.version;
            if(!io.brodgar.addon.registry.Semver.valid(v))
                return dep.id + "'s version '" + v + "' is not a version (a version is MAJOR.MINOR.PATCH, such as 1.2.0,"
                    + " with an optional pre-release such as 1.2.0-beta.1)";
            if(io.brodgar.addon.registry.Semver.compare(v, dep.min) < 0)
                return "needs " + dep.id + " >= " + dep.min + ", " + v + " installed";
        }
    }
    return null;
}
```

`describeAddons` builds three lists per row from the manifests it already parses: `needs` = `m.dependencies`
as strings (`Dependency.toString()`), `optional` = `m.optionalDependencies` as strings, `usedBy` = every
other parsed manifest whose `dependencies` name the id (its id) or whose `optionalDependencies` do (its id
+ `" (optional)"`), sorted by id. `AddonInfo` gains `public final List<String> needs, optional, usedBy;`
as the last three constructor parameters.

The teardown `STEPS` list gains, **directly after the `Disable` step**:

```java
// 156.3: what this addon exported goes; a copy another addon holds stays a table whose functions refuse
new Step("exports", a -> { a.export = null; a.apiViews.clear(); a.crossWrappers.clear(); }),
```

A dependant is torn down before its library — `reload()` walks `addons` in reverse, and `addons` is in
load order — so a dependant's `Disable` handler still reaches the library.

### 2.3 `AddonManager` — `callThrough` and the cascade

Extract the accounting of `called()`'s `finally` into one helper and call it from both:

```java
/** The one accounting every entry pays, on every path out — called()'s finally, and callThrough's. */
private static void account(Addon owner, int cat, long t0) {
    long d = System.nanoTime() - t0;
    owner.tickLuaNanos.add(d);   // soft per-tick CPU-budget accounting (D-018 layer 2)
    if(io.brodgar.prof.Prof.on) {
        owner.catNanos[cat].add(d);
        owner.catCalls[cat].increment();
        io.brodgar.prof.Overhead.hAddon++;   // 019.7: one probe hit, for the modelled addon-tier cost
    }
}

/**
 * Enter {@code owner}'s Lua for ANOTHER addon's call (156.3): the same door as callLua — the lock, the
 * running-addon stack, the instruction budget, the accounting — but an error is the CALLER's, re-raised
 * as "<label>: <reason>" instead of logged here, and a declined entry is a refusal rather than a NIL.
 */
static Varargs callThrough(Addon owner, int cat, LuaValue fn, LuaValue[] args, String label) {
    if(quiet())
        return LuaValue.NIL;
    String id = owner.manifest.id;
    if(!enterLua(owner))
        throw new LuaError(label + ": " + id + " is busy on another thread");
    try {
        long t0 = System.nanoTime();
        long budget = Sandbox.arm(owner.env);
        try {
            return fn.invoke((args.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(args));
        } catch(LuaError e) {
            throw new LuaError(label + ": " + Refusal.reason(e));
        } catch(RuntimeException e) {
            throw new LuaError(label + ": " + e);
        } catch(Throwable t) {
            if((t instanceof ThreadDeath) || Thread.currentThread().isInterrupted()) {
                if(t instanceof Error) throw (Error)t;
                throw new RuntimeException(t);
            }
            contain(owner, t);
            throw new LuaError(label + ": " + id + " failed fatally and is quarantined");
        } finally {
            Sandbox.disarm(owner.env, budget);   // released where it was claimed, on every path out
            account(owner, cat, t0);
        }
    } finally {
        leaveLua(owner);
    }
}
```

`autoDisable(a, reason)` gains, after `addons.remove(a);`:

```java
// 156.2: a library's loaded HARD dependants go with it, in the same sweep, each naming it
if(a.manifest != null)
    for(Addon d : AddonRegistry.hardDependants(a.manifest.id))
        if(addons.contains(d))
            autoDisable(d, "needs " + a.manifest.id);
```

### 2.4 `Addon` — fields and the category

Beside `clientOpts…`: `LuaValue clientAddons;`. New fields, documented in the style of their neighbours:

```java
/** What this addon exported, read once at export(t): a validated deep copy nobody writes; null until then. */
volatile LuaTable export;
/** This addon's copies of other addons' exports, by the library's id — minted once, held for this addon's life. */
final Map<String, LuaValue> apiViews = new java.util.HashMap<String, LuaValue>();
/** The wrappers this addon holds over other addons' functions, by the raw function: the same function, the same wrapper. */
final Interned<LuaValue, LuaValue> crossWrappers = Interned.identity();
/** This addon's Addon handles, by id (156.1). */
final Interned<String, LuaValue> addonHandles = Interned.keyed();
/** The metatable of those handles, built once per owner (its methods close over the owner). */
LuaValue addonMeta;
```

`C_WIDGET = 4` becomes `C_WIDGET = 4, C_EXPORT = 5;` and `CATS` becomes
`{"events", "timers", "draw", "hooks", "widgets", "exports"}`. Nothing else changes: `catNanos`, `profCat`,
`ProfHandle` and `Subs` all size and iterate from `CATS`.

### 2.5 `OptionsHandle.install` — the mount

After the `stepping` verb:

```java
// hafen.client():addons() — every addon the client discovered, and the export door (156). One collection
// per owner, like options(): the handles it mints are interned per owner.
client.set("addons", new VarArgFunction() {
    public Varargs invoke(Varargs a) {
        Section.self(a.arg1(), "client", "addons");
        if(a.narg() > 1)
            throw new LuaError("hafen.client():addons() takes no arguments — it is the collection of every addon"
                + " the client discovered; :get(id) addresses one");
        if(owner.clientAddons == null)
            owner.clientAddons = LuaAddon.collection(owner);
        return owner.clientAddons;
    }
});
```

### 2.6 `LuaAddon` — the collection and the handle (new file)

```java
package io.brodgar.addon;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.ArrayList;
import java.util.List;

/** hafen.client():addons(): every addon the client discovered, and the Addon handle :get(id) mints (156.1). */
final class LuaAddon {
    static final String COLL = "hafen.client():addons()";
    final String id;
    private LuaAddon(String id) { this.id = id; }
    public String toString() { return "Addon(" + id + ")"; }

    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("export", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaCollection.receiver(me, COLL, "export");
                Args.only(a, 1, COLL + ":export");
                LuaValue t = Args.required(a, 2, COLL + ":export", "t");
                if(!t.istable())
                    throw new LuaError(COLL + ":export(t): t must be a table, got " + t.typename());
                if(owner.export != null)
                    throw new LuaError(COLL + ":export(t): already exported — an addon exports once, in its file body");
                owner.export = Crossing.snapshot(t.checktable(), COLL + ":export(t)");
                return me;
            }
        });
        return LuaCollection.create(COLL, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String id : AddonRegistry.discovered().keySet()) out.add(of(owner, id));
                return out;
            }
            public boolean named() { return true; }
            public String needle(LuaValue member) { LuaAddon h = resolve(member); return (h == null) ? null : h.id; }
            public boolean addressable() { return true; }
            public String keyName() { return "id"; }
            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError(COLL + ":get(id): expected an addon id, got " + key.typename());
                return of(owner, key.tojstring());
            }
            public LuaCollection.Missing missing() { return LuaCollection.Missing.MINT; }
        }, extra);
    }

    static LuaValue of(final Addon owner, final String id) {
        return owner.addonHandles.of(id, () -> LuaValue.userdataOf(new LuaAddon(id), meta(owner)));
    }
    static LuaAddon resolve(LuaValue v) {
        if((v == null) || !v.isuserdata()) return null;
        Object o = v.touserdata();
        return (o instanceof LuaAddon) ? (LuaAddon)o : null;
    }
    private static LuaAddon handle(LuaValue self, String method) {
        LuaAddon h = resolve(self);
        if(h == null)
            throw new LuaError("addon:" + method + "() — use a COLON call on an Addon object (" + COLL + ":get(id))");
        return h;
    }

    private static LuaValue meta(final Addon owner) {
        if(owner.addonMeta != null) return owner.addonMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("addon", methods(owner), "an addon the client discovered"));
        mt.set("__name", LuaValue.valueOf("Addon"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAddon h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Addon(?)" : h.toString());
            }
        });
        owner.addonMeta = mt;
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("id", new VarArgFunction() { public Varargs invoke(Varargs a) {
            return LuaValue.valueOf(handle(Args.only(a, 0, "addon:id"), "id").id); } });
        m.set("exists", new VarArgFunction() { public Varargs invoke(Varargs a) {
            String id = handle(Args.only(a, 0, "addon:exists"), "exists").id;
            return LuaValue.valueOf(AddonRegistry.discovered().containsKey(id)); } });
        m.set("info", new VarArgFunction() { public Varargs invoke(Varargs a) {
            String id = handle(Args.only(a, 0, "addon:info"), "info").id;
            AddonRegistry.Discovered d = AddonRegistry.discovered().get(id);
            AddonRegistry.Status s = AddonRegistry.status(id);
            if((d == null) || (s == null)) return LuaValue.NIL;
            LuaTable t = new LuaTable();
            t.set("id", LuaValue.valueOf(id));
            Manifest mf = d.manifest;
            t.set("name", LuaValue.valueOf((mf != null) ? mf.name : id));
            if((mf != null) && (mf.version != null)) t.set("version", LuaValue.valueOf(mf.version));
            if((mf != null) && (mf.author != null)) t.set("author", LuaValue.valueOf(mf.author));
            if((mf != null) && (mf.description != null)) t.set("description", LuaValue.valueOf(mf.description));
            t.set("status", LuaValue.valueOf(s.word));
            if(s.reason != null) t.set("reason", LuaValue.valueOf(s.reason));
            return t; } });
        m.set("api", new VarArgFunction() { public Varargs invoke(Varargs a) {
            String id = handle(Args.only(a, 0, "addon:api"), "api").id;
            Addon lib = AddonRegistry.loaded(id);
            if((lib == null) || (lib.export == null)) return LuaValue.NIL;
            if(!Crossing.minimumMet(owner, lib)) return LuaValue.NIL;
            LuaValue view = owner.apiViews.get(id);
            if(view == null) {
                view = Crossing.copy(lib.export, lib, owner, id, Crossing.Direction.EXPORT, 0);
                owner.apiViews.put(id, view);
            }
            return view; } });
        return m;
    }
}
```

In 156.1 the `api` verb ends at the `minimumMet` line's predecessor: `lib.export` is always `null` (the field
exists from 156.1, nothing writes it until 156.3), so the verb answers `nil`; the `Crossing` calls are
added in 156.3.

### 2.7 `Crossing` — the walker, the wrapper, the metatables (new file, 156.3)

```java
package io.brodgar.addon;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.IdentityHashMap;

/** What crosses between two addons, and how: functions as wrappers, tables as read-only copies, handles not at all (156.3). */
final class Crossing {
    private Crossing() {}
    enum Direction { EXPORT, ARGUMENT, RESULT }

    /** export(t)'s snapshot: a deep copy with raw functions and plain tables; a bad value refuses with its path. */
    static LuaTable snapshot(LuaTable t, String verb) {
        return (LuaTable)snap(t, verb, "", new IdentityHashMap<LuaValue, LuaValue>());
    }
    private static LuaValue snap(LuaValue v, String verb, String path, IdentityHashMap<LuaValue, LuaValue> seen) {
        switch(v.type()) {
        case LuaValue.TNIL: case LuaValue.TBOOLEAN: case LuaValue.TNUMBER: case LuaValue.TSTRING: case LuaValue.TFUNCTION:
            return v;
        case LuaValue.TTABLE: {
            LuaValue done = seen.get(v);
            if(done != null) return done;
            LuaTable out = new LuaTable();
            seen.put(v, out);
            LuaValue k = LuaValue.NIL;
            while(true) {
                Varargs n = v.next(k);
                k = n.arg1();
                if(k.isnil()) break;
                String at = join(path, k);
                out.rawset(snap(k, verb, at, seen), snap(n.arg(2), verb, at, seen));
            }
            return out;
        }
        default:
            throw new LuaError(verb + ": '" + path + "' is a " + kind(v) + " — export functions and plain values"
                + " (strings, numbers, booleans, tables of them)");
        }
    }

    /** One value crossing from {@code from} to {@code to}. {@code label} names the call; {@code pos} the argument (0 = none). */
    static LuaValue copy(LuaValue v, Addon from, Addon to, String label, Direction dir, int pos) {
        return cross(v, from, to, label, dir, pos, "", new IdentityHashMap<LuaValue, LuaValue>(), meta(from, dir));
    }
    private static LuaValue cross(LuaValue v, Addon from, Addon to, String label, Direction dir, int pos, String path,
                                  IdentityHashMap<LuaValue, LuaValue> seen, LuaValue mt) {
        switch(v.type()) {
        case LuaValue.TNIL: case LuaValue.TBOOLEAN: case LuaValue.TNUMBER: case LuaValue.TSTRING:
            return v;
        case LuaValue.TFUNCTION: {
            if(v instanceof Wrapper) {
                Wrapper w = (Wrapper)v;
                return (w.owner == to) ? w.fn : v;              // home again: the raw function; elsewhere: as is
            }
            final LuaValue fn = v;
            final String wlabel = (dir == Direction.EXPORT) ? (label + "." + path) : ("a function of " + from.manifest.id);
            return to.crossWrappers.of(fn, () -> new Wrapper(from, fn, wlabel));
        }
        case LuaValue.TTABLE: {
            LuaValue done = seen.get(v);
            if(done != null) return done;
            LuaTable out = new LuaTable();
            seen.put(v, out);
            LuaValue k = LuaValue.NIL;
            while(true) {
                Varargs n = v.next(k);
                k = n.arg1();
                if(k.isnil()) break;
                String at = join(path, k);
                out.rawset(cross(k, from, to, label, dir, pos, at, seen, mt),
                           cross(n.arg(2), from, to, label, dir, pos, at, seen, mt));
            }
            out.setmetatable(mt);
            return out;
        }
        default:
            throw new LuaError(refusal(label, dir, pos, path, kind(v)));
        }
    }

    private static String refusal(String label, Direction dir, int pos, String path, String kind) {
        String where = (dir == Direction.RESULT)
            ? (path.isEmpty() ? "the result" : ("the result's field '" + path + "'"))
            : (path.isEmpty() ? ("argument " + pos) : ("argument " + pos + "'s field '" + path + "'"));
        String hint = (dir == Direction.RESULT) ? "hand back a value, a table or a function" : "hand it a function";
        return label + ": " + where + " is a " + kind + " — a handle does not cross to another addon; " + hint;
    }

    /** The read-only metatable of a copy: one sentence for an export view, one for a crossed table. */
    private static LuaValue meta(final Addon from, Direction dir) {
        final String msg = (dir == Direction.EXPORT)
            ? (from.manifest.id + "'s export is read-only — it is your copy; a change belongs in the library, through a function it exports")
            : ("a table from " + from.manifest.id + " is read-only here — it is a copy taken as it crossed; answer with a return value");
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.NEWINDEX, new ThreeArgFunction() {
            public LuaValue call(LuaValue t, LuaValue k, LuaValue v) { throw new LuaError(msg); }
        });
        mt.set(LuaValue.METATABLE, LuaValue.valueOf("read-only"));
        return mt;
    }

    static String kind(LuaValue v) {
        Object o = v.isuserdata() ? v.touserdata() : null;
        if(o instanceof LuaCollection) return "collection";
        String s = v.tojstring();
        int at = s.indexOf('(');
        if(at > 0) s = s.substring(0, at);
        return s.isEmpty() ? v.typename() : s;
    }
    static String join(String path, LuaValue key) {
        String k = (key.type() == LuaValue.TNUMBER) ? ("[" + key.tojstring() + "]") : key.tojstring();
        if(path.isEmpty()) return k;
        return (key.type() == LuaValue.TNUMBER) ? (path + k) : (path + "." + k);
    }

    /** Whether {@code receiver}'s manifest minimum on {@code lib}, if it names one, is met by lib's version. */
    static boolean minimumMet(Addon receiver, Addon lib) {
        String id = lib.manifest.id, v = lib.manifest.version;
        for(Manifest.Dependency d : receiver.manifest.allDependencies()) {
            if(!d.id.equals(id) || (d.min == null)) continue;
            if(!io.brodgar.addon.registry.Semver.valid(v)) return false;
            return io.brodgar.addon.registry.Semver.compare(v, d.min) >= 0;
        }
        return true;
    }

    /** A function of {@code owner} as another addon holds it: every call enters owner's door. */
    static final class Wrapper extends VarArgFunction {
        final Addon owner; final LuaValue fn; final String label;
        Wrapper(Addon owner, LuaValue fn, String label) { this.owner = owner; this.fn = fn; this.label = label; }
        public Varargs invoke(Varargs args) {
            Addon caller = AddonManager.current();
            if(caller == null) throw new LuaError(label + ": called outside any addon's code");
            if(!AddonRegistry.isLoaded(owner)) throw new LuaError(label + ": " + owner.manifest.id + " is disabled");
            LuaValue[] in = new LuaValue[args.narg()];
            for(int i = 0; i < in.length; i++)
                in[i] = copy(args.arg(i + 1), caller, owner, label, Direction.ARGUMENT, i + 1);
            Varargs out = AddonManager.callThrough(owner, Addon.C_EXPORT, fn, in, label);
            LuaValue[] back = new LuaValue[out.narg()];
            for(int i = 0; i < back.length; i++)
                back[i] = copy(out.arg(i + 1), owner, caller, label, Direction.RESULT, i + 1);
            return (back.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(back);
        }
        public String tojstring() { return "function: " + label; }
    }
}
```

Notes for the implementer: `LuaValue.METATABLE` and `LuaValue.NEWINDEX` are LuaJ's `__metatable` and
`__newindex` keys; `ThreeArgFunction` is `org.luaj.vm2.lib.ThreeArgFunction`. `snapshot` validates *and*
copies, so a later write into the exporter's own `t` changes nothing. Self-crossing (receiver == owner)
follows the same rules — the suite relies on it: the receiver's own functions come back to it as wrappers
through `:api()`, and go home raw when passed back as arguments.

### 2.8 `AddonPanel.tip`

Signature becomes `tip(String lead, String description, String permissions, List<String> hosts, List<String>
needs, List<String> optional, List<String> usedBy)`; after the hosts block, three blocks of the same shape:
`"Needs: " + String.join(", ", needs)`, `"Optional: " + …`, `"Used by: " + …`, each only when non-empty,
each preceded by `\n\n` when the tip already has text. The one call site passes `ai.needs, ai.optional,
ai.usedBy`.

## 3. Threading and lifecycle, stated

- `export` runs inside the exporter's Lua (its lock); `:api()` inside the receiver's. The receiver reads the
  exporter's `export` field (volatile) and walks a snapshot nobody writes — a cross-lock read of immutable
  data.
- `Wrapper.invoke` runs on the caller's thread with the caller's lock held and takes the owner's through
  `enterLua`: re-entrant on the same thread, waiting when no tree monitor is held, refusing when one is.
- `Interned` mints allocate and read only; `crossWrappers` (weak both ways) lives on the *holder*.
- The loaded set changes only in `loadAll`/`reload` (everything) and `autoDisable` (end of tick). A view
  minted before an auto-disable stays in `apiViews`; `:api()` answers `nil` from then on because
  `AddonRegistry.loaded(id)` is `null`; a wrapper in it refuses `… is disabled`.

## 4. The pages

Every page below is written by the task named beside it; the text is the draft to type, in
`DOCUMENTATION.md`'s form (§0 above — **IMPORTANT**: same style as the rest of `docs/addons/`, tables and
facts, no literature, no Java names, no history). Where a page already exists, the edit is given as
*before → after*; the surrounding rows are the model for the new one.

### 4.1 `docs/addons/api/client/addons.md` (156.1 the first three sections, 156.2 *Dependencies and load order*, 156.3 the rest)

````markdown
# hafen.client: Addons and Libraries

`hafen.client():addons()` is every addon the client discovered, one handle per id. An addon that exports a table is a **library**: another addon reads it with `handle:api()` and calls into it through the client.

```lua
local toast = hafen.client():addons():get("toast")     -- a handle, whether or not toast is installed
local api = toast:api()                                -- its export, or nil
if api then api.show("Hello") else hafen.log():write("Hello") end
```

---

## The collection

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.client():addons()` | collection | Unprotected | Every addon the last load found in `addons/`, by id. One object per addon of yours. |
| `addons:list(filter)` | `Addon[]` | Unprotected | The handles, by id. A string filter is a substring test on the id; a function is a predicate over the handle. |
| `addons:count(filter)` | `number` | Unprotected | How many. |
| `addons:find(filter)` | `Addon \| nil` | Unprotected | The first that matches. |
| `addons:get(id)` | `Addon` | Unprotected | Always a handle, the same object per id. `:exists()` is the question a `nil` would answer. |
| `addons:export(t)` | the collection | Unprotected | Publish `t` as your export, once — [below](#exporting). |

`pairs`, `#` and `[n]` are refused naming `:list()`, as on every [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many). Everything here answers as of the last load: a folder that appears or goes mid-session is seen at the next reload, like the [AddOns manager](../../panel.md).

## The handle

| Method | Returns | Permission | Description |
|---|---|---|---|
| `addon:id()` | `string` | Unprotected | The id, as you addressed it. |
| `addon:exists()` | `boolean` | Unprotected | A folder with a `manifest.json` at the last load. |
| `addon:info()` | [`Addon`](../types/client.md#addon) `\| nil` | Unprotected | The snapshot: id, name, version, author, description, `status`, `reason`. `nil` for an id that does not exist. |
| `addon:api()` | `table \| nil` | Unprotected | Your copy of its export — [below](#reading-an-export). `nil` when it is not loaded, exported nothing, or is below the minimum you declared. |

| Rule | Detail |
|---|---|
| `status` | `loaded`, `disabled`, `not loaded`, `error`, `outdated`, `auto-disabled` or `manifest error`: the words the [Installed tab](../../panel.md#installed) shows. `reason` is the sentence behind an `error`, `outdated`, `auto-disabled` or `manifest error`, absent otherwise. |
| Interned | `addons:get("toast") == addons:get("toast")`, from the first call, whether or not the folder exists. `tostring` is `Addon(toast)`. |
| Closed | An unknown verb raises naming the verbs a handle answers. |

## Dependencies and load order

Your manifest's `dependencies` and `optional_dependencies` list ids, each as `<id>` or `<id>>=MAJOR.MINOR.PATCH` ([manifest](../../manifest.md#the-manifest)). They decide when your files run and what happens when a dependency is missing.

| Rule | Detail |
|---|---|
| Order | Every addon runs after the installed, loading addons its two lists name, ties by id. A dependency's `export` and `Load` precede your file body. |
| A hard dependency missing | Your addon is a load error, its row reading `error: needs toast, which is not installed` (`is disabled`, `is out of date`, `has a manifest error`, `failed to load`). Nothing of yours runs. |
| A minimum | `toast>=1.2.0` compares the library's manifest `version` as the hub orders versions. Not met on a hard dependency: `error: needs toast >= 1.2.0, 1.0.0 installed`. On an optional one: the library is, to you, absent — `:api()` is `nil`, `:exists()` and `:info()` still answer. A library whose `version` is not a version is below every minimum. |
| An optional dependency absent | Nothing happens. Ask `:api()` when you need it. |
| A cycle | Hard dependencies that close a cycle are a load error on each member: `depends in a cycle: a -> b -> a`. An optional dependency that would close one is not ordered, with a log line. |
| Mid-session | A library the [CPU budget](../../runtime.md#budgets-and-the-watchdog) stops takes its loaded hard dependants with it, each reading `auto-disabled (needs toast)`. An optional dependant keeps running and its `:api()` turns `nil`. |
| Tearing down | In reverse load order: a dependant's `Disable` handler still reaches its library. |

```lua
-- optional: ask at the moment of use, and nothing breaks without it
local toast = hafen.client():addons():get("toast")
local function notify(text)
  local api = toast:api()
  if api then api.show(text) else hafen.log():write(text) end
end
```

## Exporting

`hafen.client():addons():export(t)` reads `t` once, at the call, and keeps a copy. Call it in your file body, so every addon that names you in a dependency list finds it. A second call refuses `already exported`.

| `t` may hold | Rule |
|---|---|
| Functions | Each runs as yours when called — [below](#what-a-call-does). |
| Strings, numbers, booleans | Copied. A value that changes is a function that answers it: the copy never updates. |
| Tables of the same | Copied, recursively. |
| Anything else | Refused naming the key: `export: 'icon' is a Widget — export functions and plain values`. A handle of yours is not a value another addon can hold. |

```lua
local open = 0
local function show(text)
  open = open + 1
  local box = hafen.ui():widget():size(240, 28):position(400, 40 + open * 32)
  hafen.ui():label():text(text):parent(box):position(8, 6)
  hafen.timer():after(3, function() box:destroy(); open = open - 1 end)
  return open
end
hafen.client():addons():export({ show = show, count = function() return open end })
```

## Reading an export

`handle:api()` hands you **your own copy** of the library's export, the same table on every call while the library is loaded.

| Rule | Detail |
|---|---|
| Read-only | A write refuses: `toast's export is read-only — it is your copy; a change belongs in the library, through a function it exports`. `pairs` and `#` work; it is a table. |
| Functions are wrappers | `api.show == api.show`, and `rawequal(api.show, f)` is false for the library's own `f`. Every call enters the library. |
| `nil` | The library does not exist, is not loaded, exported nothing, or is below the minimum your manifest names. |
| After a teardown | The copy you hold stays a table; a function in it refuses `toast.show: toast is disabled`. |

## What a call does

A call through an export — and a callback the library calls back — enters the **owner's** door.

| Rule | Detail |
|---|---|
| Runs as its owner | The library's code runs under the library's [consent](../../guides/permissions.md), with the library's environment and store, whoever called it. A callback you hand a library runs under yours. |
| Budgets | The call is an entry into the owner's Lua: a fresh [instruction budget](../../runtime.md#budgets-and-the-watchdog). Its time is charged to the owner under `exports` and to the caller's own entry. |
| An error | Reaches the caller as `toast.show: <reason>`; the library keeps running. `pcall` catches it. |
| Busy | From a handler that holds a tree's monitor ([threading](../threading.md)), a library busy on another thread refuses: `toast.show: toast is busy on another thread`. |

### What crosses, and how

Arguments and return values follow one rule in both directions.

| Value | Crosses as |
|---|---|
| A string, number, boolean, `nil` | Itself. |
| A function | A wrapper entering its owner's door. The same function crosses as the same wrapper. |
| A table | A read-only copy, recursive, cycles kept; the write refusal names the addon it came from. A metatable does not cross. |
| A bridge handle (a widget, a timer, a session, a store table, a collection…) | Refused: `toast.show: argument 2 is a Widget — a handle does not cross to another addon; hand it a function`. |

```lua
-- lend a capability, not a handle: the library can only do what your functions do
local settings = hafen.store():var("settings")
api.bind({ get = function(key) return settings[key] end,
           set = function(key, value) settings[key] = value end })
```

---

## See Also

- [Libraries](../../guides/libraries.md) — writing one and using one, end to end.
- [The manifest](../../manifest.md) — the two dependency lists.
- [Attribution](profiling/attribution.md) — the `exports` category.
- [Data types](../types/client.md) — the `Addon` shape.
````

### 4.2 `docs/addons/api/types/client.md` (156.1)

````markdown
# Data Types: The Client

Shapes the client answers about itself: an addon it discovered.

## Addon

`addon:info()` on a [handle](../client/addons.md#the-handle).

| Field | Type | Meaning |
|---|---|---|
| `id` | `string` | The folder and manifest id. |
| `name` | `string` | The manifest `name`, else the id. |
| `version` | `string \| nil` | The manifest `version`. |
| `author` | `string \| nil` | The manifest `author`. |
| `description` | `string \| nil` | The manifest `description`. |
| `status` | `string` | `loaded`, `disabled`, `not loaded`, `error`, `outdated`, `auto-disabled` or `manifest error`. |
| `reason` | `string \| nil` | The sentence behind `error`, `outdated`, `auto-disabled` and `manifest error`. |
````

`types/README.md`: add the row `| [The client](client.md) | An addon the client discovered. |` to *The
pages* and `| \`Addon\` | [The client](client.md#addon) |` to *Every shape* (alphabetical, before `Attr`).

### 4.3 `docs/addons/guides/libraries.md` (156.4)

````markdown
# Libraries

An addon that exports a table is a library; another addon reads it with `hafen.client():addons():get(id):api()`. This page writes one, `toast`, and one addon that uses it. The verbs are in [addons and libraries](../api/client/addons.md).

## Writing one

A library is an ordinary addon: a folder, a manifest, files. What makes it a library is one call in its file body.

```json
{ "id": "toast", "name": "Toast", "version": "1.0.0", "author": "you", "api_version": "1.0", "files": ["toast.lua"],
  "description": "Notices at the top of the screen, for other addons to show" }
```

```lua
-- toast.lua
local open = 0

local function show(text)
  open = open + 1
  local box = hafen.ui():widget():size(240, 28):position(400, 40 + open * 32)
  hafen.ui():label():text(text):parent(box):position(8, 6)
  hafen.timer():after(3, function() box:destroy(); open = open - 1 end)
  return open
end

hafen.client():addons():export({
  show  = show,
  count = function() return open end,
})
```

| Rule | Why |
|---|---|
| Export in the file body | Every addon that names you in a dependency list runs after you, so your export is there before their first line. |
| Export functions, not state | The export is copied once, at the call; a number in it never changes for the reader. `count()` answers a value, `count = open` would not. |
| Return values, never handles | The box `show` builds is yours; it cannot cross. Answer an id, a table, a function. |
| Your code runs as you | Under your permissions, your store, your budget — whoever calls. A library that walks needs `player.move` in its own manifest, and the user consents to it when enabling the library. |
| A breaking change is a new id | Inside `toast`, only add. Rename or reshape a function and publish `toast2`; consumers move when they choose. |
| Data stays with its owner | Your store holds your own state and what is shared by nature (an index every consumer feeds). A consumer's settings go in the consumer's `hafen.store()` — lend it functions, not files. |

## Using one

```json
{ "id": "farm-helper", "version": "0.3.0", "api_version": "1.0", "files": ["main.lua"],
  "optional_dependencies": ["toast>=1.0.0"] }
```

```lua
-- main.lua
local toast = hafen.client():addons():get("toast")     -- a handle, installed or not

local function notify(text)
  local api = toast:api()                              -- the export, or nil
  if api then api.show(text) else hafen.log():write(text) end
end

hafen.console():on("hello", function(args)
  notify("Hello " .. (args[1] or ""))
end)
```

| Choice | Effect |
|---|---|
| `optional_dependencies` | `toast` runs before you when it is installed; nothing happens when it is not. Ask `:api()` when you need it. |
| `dependencies` | You do not run without it: `error: needs toast, which is not installed` on your row. Use it when a missing library means your addon means nothing. |
| `toast>=1.2.0` | The version you need. Below it a hard dependency is an error and an optional one answers `nil`. |
| A callback | A function you pass (`api.on("click", function() hafen.log():write("clicked") end)`, when a library offers one) runs as you, through the client, when the library calls it. |

## What happens

| Moment | Detail |
|---|---|
| Load | `toast` runs, exports; `farm-helper` runs after it. |
| `:hello Ada` | `farm-helper` calls `api.show`; the notice is built inside `toast`, with `toast`'s budget and consent. |
| `toast` removed | The next reload: `farm-helper` still loads, `:api()` is `nil`, `notify` writes to the log. |
| `toast` stopped by the watchdog | `farm-helper` keeps running; its `:api()` is `nil` from then on, a kept copy's functions refuse `toast.show: toast is disabled`. A hard dependant would stop with it. |

## See Also

- [Addons and libraries](../api/client/addons.md) — every verb, and what crosses.
- [The manifest](../manifest.md) — the two dependency lists.
- [Saved data](saved-data.md) — whose file a value belongs in.
- [Permissions](permissions.md) — whose consent a call runs under.
````

`guides/README.md`: add `| [Libraries](libraries.md) | Export functions for other addons, and use another addon's. |`
after the *Hotkeys, commands and settings* row.

### 4.4 Row edits on existing pages

| Page | Task | Before → after |
|---|---|---|
| `manifest.md` field table | 2 | `dependencies` row → `\| \`dependencies\` \| \`string[]\` \| Addon ids this addon needs, each \`<id>\` or \`<id>>=MAJOR.MINOR.PATCH\`. They run before it; a missing one is a load error naming it ([addons](api/client/addons.md#dependencies-and-load-order)). \|`; `optional_dependencies` row → `\| \`optional_dependencies\` \| \`string[]\` \| The same form. They run before it when installed; a missing one changes nothing. \|` |
| `manifest.md` rules table | 2 | *Nothing to import* row → `\| Nothing to import \| Each addon runs in an environment of its own. Another addon's export is read through [\`hafen.client():addons()\`](api/client/addons.md); its state is never yours. \|`; the *A manifest the client cannot read* row gains `A dependency entry that is not \`<id>\` or \`<id>>=MAJOR.MINOR.PATCH\`.` |
| `panel.md` Installed table | 2 | `error: …` row → `\| \`error: …\` \| Its manifest or its Lua failed, or a dependency is missing: \`error: needs toast, which is not installed\`. The message says how. \|`; `auto-disabled (…)` row → `… stopped it, or stopped a library it needs: \`auto-disabled (needs toast)\`. \|`; the paragraph above the table gains `The tooltip also lists \`Needs:\`, \`Optional:\` and \`Used by:\` when the manifests name them.` |
| `runtime.md` sandbox table | 3 | new row `\| Another addon's code \| Through [\`hafen.client():addons()\`](api/client/addons.md): its export, as a copy, and its functions through the client. \| \`require\`. \|` |
| `runtime.md` budgets table | 3 | *An entry inside an entry* row gains `A call into another addon's exported function, or a callback it calls back, is an entry into that addon.` |
| `runtime.md` budgets prose | 2 | after *An auto-disable lasts until the next load*: `A library's auto-disable takes its loaded hard dependants with it, each reading \`auto-disabled (needs <id>)\`.` |
| `api/conventions.md` get-miss table | 1 | the MINT row's list gains `hafen.client():addons()` |
| `api/client/README.md` | 1 | H1 → `# hafen.client: The Client`; first sentence gains `, and the addons it discovered`; table gains `\| \`hafen.client():addons()\` \| Every addon installed, its export, and yours. \| [Addons and libraries](addons.md) \|` |
| `api/README.md` | 1 | after the `hafen.client` row: `\| [Addons and libraries](client/addons.md) \| Every addon the client discovered; export a table, read another addon's. \|` |
| `api/client/profiling/attribution.md` | 3 | `calls` row list gains `exports`; the *Categories* row gains `\`exports\`: calls into this addon's export from other addons, and callbacks they handed it.` |
| `guides/debugging.md` status table | 2 | `error: …` row → `The message: bad JSON, a missing \`id\` or \`files\`, an id that is not the folder name, or \`needs <id>, which …\`, a [dependency](../api/client/addons.md#dependencies-and-load-order) missing.` |
| `guides/permissions.md` *A key names the action* | 3 | new closing sentence: `A library's exported function runs under the library's own keys, whoever called it; a callback you hand a library runs under yours ([addons](../api/client/addons.md#what-a-call-does)).` |
| `guides/saved-data.md` *Store data, not objects* | 4 | new closing sentence: `A value belongs in the file of the addon it describes: a [library](libraries.md) keeps its own state and lends functions, never its file.` |

## 5. The `jshell` proofs (run by `/implement`, transcripts into the task's report)

Both scripts run on `build/classes` with `lib/brodgar/*.jar` on the classpath, headless
(`jshell -R-Djava.awt.headless=true --class-path "build/classes;lib/brodgar/luaj-jse-3.0.1.jar;lib/brodgar/minimal-json-0.9.5.jar;lib/brodgar/sqlite-jdbc-3.53.4.0.jar;lib/jglob.jar;build/builtin-res.jar;build/hafen-res.jar"` — the memory `jshell-headless-widget-precheck` has the details; Git Bash mangles a relative first entry, so use absolute paths and `export MSYS_NO_PATHCONV=1`). Reflection into `io.brodgar.addon` is fine (`setAccessible(true)`).

**156.2 — the plan and the parser** (no client state touched):

```java
import io.brodgar.addon.*; import java.nio.file.*; import java.util.*;
Path root = Files.createTempDirectory("addons156");
void addon(String id, String json) throws Exception { Path d = root.resolve(id); Files.createDirectories(d); Files.write(d.resolve("manifest.json"), json.getBytes("UTF-8")); Files.write(d.resolve("main.lua"), new byte[0]); }
addon("toast", "{\"id\":\"toast\",\"api_version\":\"1.0\",\"version\":\"1.0.0\",\"files\":[\"main.lua\"]}");
addon("farm",  "{\"id\":\"farm\",\"api_version\":\"1.0\",\"files\":[\"main.lua\"],\"optional_dependencies\":[\"toast\"]}");
addon("a",     "{\"id\":\"a\",\"api_version\":\"1.0\",\"files\":[\"main.lua\"],\"dependencies\":[\"b\"]}");
addon("b",     "{\"id\":\"b\",\"api_version\":\"1.0\",\"files\":[\"main.lua\"],\"dependencies\":[\"a\"]}");
addon("needy", "{\"id\":\"needy\",\"api_version\":\"1.0\",\"files\":[\"main.lua\"],\"dependencies\":[\"toast>=1.2.0\"]}");
addon("bad",   "{\"id\":\"bad\",\"api_version\":\"1.0\",\"files\":[\"main.lua\"],\"dependencies\":[\"toast>=1.2\"]}");
Map<String, Manifest> loading = new TreeMap<>();
for(String id : new String[]{"toast","farm","a","b","needy"}) loading.put(id, Manifest.load(root.resolve(id)));
try { Manifest.load(root.resolve("bad")); } catch(Exception e) { System.out.println(e.getMessage()); }
var plan = AddonRegistry.class.getDeclaredMethod("plan", Map.class); plan.setAccessible(true);
Object p = plan.invoke(null, loading);
var order = p.getClass().getDeclaredField("order"); order.setAccessible(true);
var errors = p.getClass().getDeclaredField("errors"); errors.setAccessible(true);
System.out.println(order.get(p));
System.out.println(errors.get(p));
```

Expected output, in order: the parser's sentence for `toast>=1.2`; `[toast, farm, needy]` (`farm` sorts before
`toast` by name and still loads after it; `needy` after `toast`); `{a=depends in a cycle: a -> b -> a,
b=depends in a cycle: b -> a -> b}`. Then `unmetDependency` (reflection, three arguments) against a `found`
map built with the `Discovered` constructor: `needy` → `needs toast >= 1.2.0, 1.0.0 installed` once `toast`
is put in `AddonManager.addons` as a loaded `Addon`, and `needs toast, which is not installed` with `toast`
removed from `found`.

**156.3 — two owners, one wrapper** (two probe addons in bare sandboxes):

```java
import io.brodgar.addon.*; import org.luaj.vm2.*;
var mk = Addon.class.getDeclaredConstructor(Manifest.class, java.nio.file.Path.class, Globals.class); mk.setAccessible(true);
var test = Manifest.class.getDeclaredMethod("test", String.class); test.setAccessible(true);
Globals ga = Sandbox.create(), gb = Sandbox.create();
Addon A = (Addon)mk.newInstance(test.invoke(null, "liba"), java.nio.file.Paths.get("liba"), ga);
Addon B = (Addon)mk.newInstance(test.invoke(null, "userb"), java.nio.file.Paths.get("userb"), gb);
var install = AddonManager.class.getDeclaredMethod("installHafen", Globals.class, Addon.class); install.setAccessible(true);
install.invoke(null, ga, A); install.invoke(null, gb, B);
AddonManager.addons.add(A); AddonManager.addons.add(B); A.loaded = true; B.loaded = true;
var enter = AddonManager.class.getDeclaredMethod("enterLua", Addon.class); enter.setAccessible(true);
var leave = AddonManager.class.getDeclaredMethod("leaveLua", Addon.class); leave.setAccessible(true);
enter.invoke(null, A);
ga.load("hafen.client():addons():export({ add = function(x, y) return x + y end })").call();
leave.invoke(null, A);
enter.invoke(null, B);
System.out.println(gb.load("local api = hafen.client():addons():get('liba'):api(); return api.add(2, 3)").call());
System.out.println(gb.load("local api = hafen.client():addons():get('liba'):api(); local ok, err = pcall(function() api.x = 1 end); return err").call());
leave.invoke(null, B);
var td = AddonRegistry.class.getDeclaredMethod("teardown", Addon.class); td.setAccessible(true);
td.invoke(null, A); AddonManager.addons.remove(A);
enter.invoke(null, B);
System.out.println(gb.load("return tostring(hafen.client():addons():get('liba'):api())").call());
System.out.println(gb.load("local api = " + "nil; return 'kept wrapper: ' .. tostring(pcall(function() end))").call());
leave.invoke(null, B);
```

Expected: `5`; the read-only sentence naming `liba`; `nil` after the teardown. Keep a reference to the view
before the teardown (`local api = …` stored in a `gb` global) and call `api.add(1, 1)` after it to read
`liba.add: liba is disabled`. `AddonManager.addons` is `static final` and package-visible: if the
reflection into the package is refused, run `jshell` with `--add-opens` off (`build/classes` is on the
class path, not the module path, so plain reflection works). What this proves: two `Globals`, one wrapper,
the call succeeds, the copy is read-only, `:api()` is `nil` after the teardown, a kept wrapper refuses. The
permission gate is proved by reading: `requirePermission` reads `current()`, which `enterLua(owner)` pushed
inside `callThrough`.

## 6. Risks and gotchas

- **`callLua` swallows.** `called()` logs a `LuaError` and returns `NIL`; `callThrough` shares the enter,
  the arm and the accounting (`account`, byte-for-byte the old `finally`: the watchdog's threshold depends
  on `tickLuaNanos`) and differs only in the catch.
- **The gate keys on the running stack.** `requirePermission` asks `AddonManager.current()`, never a
  handle's owner. A function called outside its owner's door runs under the caller's consent — why every
  crossing function is a wrapper, and why handles do not cross.
- **`enterLua` declines under a tree monitor** (`LuaWidget.heldOther(null) != null` and the lock taken):
  for a cross-call that is the `busy` refusal, not a silent `NIL`.
- **LuaJ's `pairs` calls `checktable` before any metamethod** (why `guardIteration` wraps `pairs`), so the
  view is a real table. `__metatable` blocks `setmetatable`; `rawset` writes into the writer's own copy.
- **`Interned` mints run under the lock**: allocate and read, never call back into a cache. `crossWrappers`
  is weak both ways and lives on the holder; `apiViews` is a plain map, touched from its owner's Lua only.
- **`Semver.compare` raises** on a non-version: `unmetDependency` and `minimumMet` test `Semver.valid` first.
- **`loadAll` reads `listFiles` unsorted**; `plan` orders ties by id. `reload()` tears `addons` down in
  reverse: dependants first.
- **`Refusal.reason(e)`** is the message LuaJ built, `main.lua:12: kaboom` for `error("kaboom")`; the
  wrapper prefixes it, it does not strip it. A suite asserts with a plain `string.find(msg, needle, 1, true)`.
- **`Manifest.internal`/`test`** pass `none` for both lists; the type change touches them.
- **The `:lua` console** is an `Addon` on the running stack: it may read any `:api()`; its own
  `:get(<console id>):exists()` is `false` (no folder).
- **`describeAddons` parses every manifest per call**; compute `usedBy` inside that pass, never per frame.
- **A `LuaError` thrown from `Wrapper.invoke`** propagates through the caller's Lua as an ordinary error:
  `pcall` catches it, an uncaught one reaches the caller's `callLua` and is logged against the caller.
- **`LuaValue.next` on a table being iterated** is how `LuaCollection.methods` walks `extra`; the same idiom
  is used in `Crossing`, so no `Iterator` import is needed.
- **`docverbs`** (`tools/docverbs.py`) resolves every documented verb against its receiver's vocabulary:
  `addons.md`'s tables spell the receivers `addons:` and `addon:`; map them in the checker's per-page table
  if it refuses them (memory `checker-exit-code-not-the-pipe`: run each checker bare, read its own exit).

## 7. Discarded alternatives

- `require("toast")` or a shared global — one `Globals` per addon is the sandbox; a raw value carries
  identity and lifetime across the boundary.
- `:get(id)` answering `nil` on a miss — an id is a name you know before the folder exists; `:exists()`
  is the question, as for a gob or a binding.
- A lazy proxy over the library's table — LuaJ's `pairs` demands a real table; a proxy is shared state.
- Handing the raw exported table — the receiver could rewrite the library's state, and its functions
  would run under the receiver's consent.
- Bridge handles crossing "as their owner" — the gate keys on the running addon, so a borrowed handle is
  gated by whoever holds the stack; a closure carries the right owner.
- A live export (reads of the library's table after `export`) — an API is a fixed contract; state is a
  function that answers it.
- One shared view for every receiver — shared mutable state; a copy per receiver keeps a write local.
- Charging a cross-call to the caller alone — a slow library would never show on its own row.
- Cascading a panel checkbox — a checkbox applies at the next reload, which rebuilds everything; only an
  auto-disable happens mid-session.
- Refusing a cycle that only an optional edge closes — ordering it is impossible, refusing it is not
  asked for; the edge is dropped and logged.
- A second version syntax ("dotted integers") — `Semver` already orders the hub's versions.
- `Added`/`Removed` on `addons()` — the loaded set changes only at reload and auto-disable.
- A `library` manifest flag — a library is any addon that exports; a flag is a second source.
- A consumer-side version check only — a manifest minimum is refused before a broken call runs.
- Browse staging dependencies now — the hub's JSON carries no `dependencies`; a client half alone cannot
  be verified against it.
