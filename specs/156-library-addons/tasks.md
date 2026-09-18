# 156 — Library addons: tasks

> One task is one `/implement` session. Each task below lists its edits **in the order to make them**, the
> commands to run, the suite **in full** (type it as written), the verdict lines the maintainer will read
> back, and the docs it writes (the drafts are in `plan.md` §4 — copy them). Nothing here is a choice: where
> a name or a sentence is needed, `plan.md` §1.1 has it.

## Shared facts

- **IMPORTANT — the documentation standard is `DOCUMENTATION.md`, and nothing else.** Every page and row a
  task writes follows it to the letter and reads like the pages already in `docs/addons/`: tables carry
  the facts, second person, present tense, no narrative, no explanation of why a rule is good, no filler
  words, no history, no Java names, no `specs/` paths, descriptive variable names in every example. The
  drafts in `plan.md` §4 are written in that form: **type them as drafted**, and before handing over run
  `DOCUMENTATION.md` §11's checklist on every page touched (`plan.md` §0 has the table). A page that reads
  like literature is sent back.

- Every suite lives at `addons/156-library-addons.<X>/` (the folder name is the manifest `id`), is copied
  to `bin/addons/` by `ant bin`, and is run in-game with `:t156`. Only one suite is installed at a time.
- The suite helpers below open every `main.lua` (copy them verbatim):

```lua
local pass, fail, manual = 0, 0, 0
local function out(line) hafen.log():write(line) end
local function check(name, ok, got)
  if ok then pass = pass + 1; out("[pass] " .. name)
  else fail = fail + 1; out("[fail] " .. name .. " -- got: " .. tostring(got)) end
end
local function why(err)                       -- LuaJ writes "main.lua:12 msg" (a space) for a Java refusal
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end
local function refuses(name, needle, fn, ...)  -- the call must fail AND say why
  local ok, err = pcall(fn, ...)
  local msg = ok and "<no error>" or why(err)
  check(name, (not ok) and (string.find(msg, needle, 1, true) ~= nil), msg)
end
local function summary()
  out(string.format("[summary] %d pass, %d fail, %d manual", pass, fail, manual))
end
```

- Build: `rm -rf build/classes && ant hafen-client` (a true compile; `BUILD SUCCESSFUL`), then `ant bin`
  (the client runs `bin/hafen.jar`, not `build/classes`). A Java change needs a full client restart; the
  suite's Lua reloads with `:reload`.
- Syntax pre-check of a suite: `java -cp lib/brodgar/luaj-jse-3.0.1.jar lua.luac -p addons/156-library-addons.<X>/main.lua`
  (it leaves a `luac.out` behind; delete it).
- Checkers, run bare, each must exit 0: `python tools/docverbs.py` and `python tools/refusalverbs.py`.
  `docverbs` resolves documented verbs against their receiver's vocabulary: `addons.md` spells its receivers
  `addons:` and `addon:`; if the checker does not know them, add the two to its per-page variable table
  (the page → receiver map the checker keeps), naming the collection and the handle.
- What needs two addons — the load order, a dependency refusal, a call across two `Globals`, a call into a
  torn-down library — is proved in `jshell` during the task (`plan.md` §5), its transcript pasted into the
  task's report, and in-game by the maintainer with the first real library and consumer.

---

- [x] **156.1 — `hafen.client():addons()`: the collection and the handle.** Criteria 1 and 2 of
      `spec.md`. Nothing exports yet: `:api()` exists and answers `nil`.

      **Edits, in order**
      1. `src/io/brodgar/addon/Addon.java` — beside `clientOpts, clientInterface, …` add `clientAddons` to the
         same declaration. After the `bindings` field add the four fields of `plan.md` §2.4 that 156.1 needs:
         `export` (volatile `LuaTable`, `null`), `apiViews`, `addonHandles`, `addonMeta`. (`crossWrappers` and
         the `CATS` change are 156.3's.)
      2. `src/io/brodgar/addon/AddonRegistry.java` — add `Discovered`, `discovered`, `discovered()`, `Status`,
         `status(id)`, `loaded(id)`, `isLoaded(a)` exactly as `plan.md` §2.2 writes them. Fill `discovered` in
         `loadAll`: right after `File[] subs = dir.listFiles(File::isDirectory);` and its null check, add a
         pre-pass over `subs` that, for every folder holding a `manifest.json`, `try`s `Manifest.load` and puts a
         `Discovered(id, sub, manifest, null)` or, on an exception, `Discovered(id, sub, null, Refusal.reason(e))`
         into a `TreeMap`, then assigns `discovered = Collections.unmodifiableMap(found)`. Today's loop below it
         stays as it is (it parses again; 156.2 folds the two into one pass). In the `subs == null` branch set
         `discovered = Collections.emptyMap()` before the `return`.
      3. `src/io/brodgar/addon/LuaAddon.java` — new, `plan.md` §2.6 whole, with one difference: the `api` verb
         stops after `if((lib == null) || (lib.export == null)) return LuaValue.NIL;` and returns `LuaValue.NIL`
         (the `Crossing` lines come in 156.3). Keep the `extra` table and the `export` verb out of this task:
         pass `null` as `LuaCollection.create`'s third argument; 156.3 adds them.
      4. `src/io/brodgar/addon/OptionsHandle.java` — the mount of `plan.md` §2.5, after the `stepping` verb.
      5. `docs/addons/api/types/client.md` — new, `plan.md` §4.2 typed as drafted; `docs/addons/api/types/README.md`
         — the two rows §4.2 names. `DOCUMENTATION.md` §1–§11 apply (`plan.md` §0).
      6. `docs/addons/api/client/addons.md` — new: the H1, the opening sentence and example, **The collection**,
         **The handle**, and the *See Also*, from `plan.md` §4.1, typed as drafted (`DOCUMENTATION.md` §1–§11
         apply, `plan.md` §0). Two omissions in this task, both filled by 156.3: leave the `addons:export(t)`
         row out (a page never names a verb that does not exist yet), and write the `:api()` row with its
         `nil` sentence but without the `[below](#reading-an-export)` link. The opening example calls
         `toast:api()`, which exists from this task and answers `nil`: keep it.
      7. Row edits of `plan.md` §4.4 tagged **1**: `api/conventions.md` (get-miss table), `api/client/README.md`
         (H1, sentence, row), `api/README.md` (row).
      8. `rm -rf build/classes && ant hafen-client && ant bin`; both checkers.

      **Its suite** — `addons/156-library-addons.1/manifest.json`:

```json
{ "id": "156-library-addons.1", "name": "156.1 suite", "version": "0.1.0", "author": "brodgar",
  "api_version": "1.0", "files": ["main.lua"],
  "description": "hafen.client():addons(): the collection and the handle" }
```

      `main.lua` (the shared helpers first, then):

```lua
local function run()
  pass, fail, manual = 0, 0, 0
  local addons = hafen.client():addons()
  check("the collection is one object per addon", addons == hafen.client():addons(), "two objects")
  local me = addons:get(ADDON.id)
  check("my own handle exists and answers my id", me:exists() == true and me:id() == ADDON.id, tostring(me:exists()) .. "/" .. tostring(me:id()))
  local info = me:info()
  check("my info reads loaded, no reason, and spells my manifest",
        info ~= nil and info.status == "loaded" and info.reason == nil and info.version == "0.1.0"
          and info.name == "156.1 suite" and info.author == "brodgar",
        info and (tostring(info.status) .. "/" .. tostring(info.reason) .. "/" .. tostring(info.version) .. "/" .. tostring(info.name)))
  local ghost = addons:get("156-no-such-addon")
  check("an unknown id is a handle that does not exist", ghost:exists() == false and ghost:id() == "156-no-such-addon", tostring(ghost:exists()))
  check("its info and api are nil", ghost:info() == nil and ghost:api() == nil, tostring(ghost:info()) .. "/" .. tostring(ghost:api()))
  check("the same handle twice, and tostring names it", ghost == addons:get("156-no-such-addon") and tostring(ghost) == "Addon(156-no-such-addon)", tostring(ghost))
  local listed = false
  for _, handle in ipairs(addons:list()) do if handle == me then listed = true end end
  check("list holds my handle by identity", listed and addons:count() >= 1, tostring(addons:count()))
  check("a string filter is a substring test on the id", #addons:list("156-library") >= 1 and addons:count("156-no-such") == 0, addons:count("156-no-such"))
  check("a function filter sees the handle", addons:find(function(handle) return handle:id() == ADDON.id end) == me, "not found")
  refuses("pairs on the collection names list()", ":list()", pairs, addons)
  refuses("# on the collection names list()", ":list()", function() return #addons end)
  refuses("get without an id names it", "id is required", function() return addons:get() end)
  refuses("get with a number refuses", "expected an addon id, got number", function() return addons:get(1) end)
  refuses("an unknown verb is refused naming the handle", "has no verb 'nope'", function() return me:nope() end)
  refuses("a surplus argument is refused", "takes no arguments", function() return me:info(1) end)
  check("nothing is exported yet: my api is nil", me:api() == nil, tostring(me:api()))
  summary()
end
hafen.console():on("t156", function() run() end)
```

      Expected: sixteen `[pass]` lines and `[summary] 16 pass, 0 fail, 0 manual`. No `[manual]`.

- [x] **156.2 — Dependencies mean what they say.** Criteria 3, 4, 5 and 6.

      **Edits, in order**
      1. `src/io/brodgar/addon/Manifest.java` — `Dependency`, `allDependencies()`, `deplist`, the two fields'
         type, the constructor, `load`, `internal`, `test`: `plan.md` §2.1.
      2. `src/io/brodgar/addon/AddonRegistry.java` — `LoadPlan`, `plan`, `cyclePath`, `walk`,
         `unmetDependency`, `hardDependants`, and `loadAll` rewritten in three phases: `plan.md` §2.2. The
         pre-pass 156.1 added becomes phase 1. Phase 3's `try` body is today's body from
         `Globals g = Sandbox.create();` on, unchanged apart from `sub` → `d.dir` and `sub.getName()` → `id`.
      3. `src/io/brodgar/addon/AddonManager.java` — the cascade in `autoDisable`: `plan.md` §2.3, last block.
      4. `AddonRegistry.AddonInfo` — three new `public final List<String> needs, optional, usedBy;` as the last
         constructor parameters; `describeAddons` fills them (`plan.md` §2.2, the paragraph after
         `unmetDependency`): `needs`/`optional` from the row's own manifest (`Dependency.toString()`),
         `usedBy` from every other parsed manifest, `" (optional)"` appended for an optional user, sorted by
         id; empty lists for a row without a manifest.
      5. `src/io/brodgar/addon/ui/AddonPanel.java` — `tip` and its one call site: `plan.md` §2.8.
      6. `AddonRegistry.STEPS` — the `exports` step directly after `Disable` (`plan.md` §2.2, last block). It
         is harmless before 156.3 and belongs with the teardown order this task establishes.
      7. `docs/addons/api/client/addons.md` — the **Dependencies and load order** section (`plan.md` §4.1),
         placed after **The handle**, typed as drafted; `DOCUMENTATION.md` §1–§11 apply (`plan.md` §0).
      8. Row edits of `plan.md` §4.4 tagged **2**: `manifest.md` (both tables), `panel.md` (two rows, one
         sentence), `runtime.md` (the cascade sentence), `guides/debugging.md`.
      9. `rm -rf build/classes && ant hafen-client && ant bin`; both checkers.
      10. The `jshell` proof of `plan.md` §5 (156.2): the parser's refusal, the order `[toast, farm, needy]`,
          both cycle sentences, `unmetDependency`'s two answers. Paste the transcript into the report.

      **Its suite** — `addons/156-library-addons.2/manifest.json`:

```json
{ "id": "156-library-addons.2", "name": "156.2 suite", "version": "0.1.0", "author": "brodgar",
  "api_version": "1.0", "files": ["main.lua"],
  "description": "dependencies mean what they say",
  "optional_dependencies": ["156-absent>=1.0.0"] }
```

      `main.lua` (the shared helpers first, then):

```lua
local function run()
  pass, fail, manual = 0, 0, 0
  local addons = hafen.client():addons()
  local me = addons:get(ADDON.id)
  local info = me:info()
  check("the loader took the optional minimum: I loaded, no reason", info ~= nil and info.status == "loaded" and info.reason == nil, info and (info.status .. "/" .. tostring(info.reason)))
  local absent = addons:get("156-absent")
  check("the absent optional dependency is a handle that does not exist", absent:exists() == false and absent:id() == "156-absent", tostring(absent:exists()))
  check("its api and info are nil", absent:api() == nil and absent:info() == nil, tostring(absent:api()) .. "/" .. tostring(absent:info()))
  check("the collection lists me and not it", addons:count(ADDON.id) == 1 and addons:count("156-absent") == 0, addons:count("156-absent"))
  manual = manual + 1
  out("[manual] hover this suite's row on the Installed tab -- expect: a tooltip line `Optional: 156-absent>=1.0.0`")
  summary()
end
hafen.console():on("t156", function() run() end)
```

      Expected: four `[pass]`, the `[manual]` line, `[summary] 4 pass, 0 fail, 1 manual`. The order, the
      five `needs …` sentences, the `>=` refusal, the cycle and the cascade are the `jshell` transcript's
      (the cascade by reading `autoDisable`: the loop is three lines).

- [x] **156.3 — The export door.** Criteria 7, 8, 9 and 10.

      **Edits, in order**
      1. `src/io/brodgar/addon/Addon.java` — `crossWrappers` (`plan.md` §2.4); `C_EXPORT = 5` and `"exports"`
         in `CATS`.
      2. `src/io/brodgar/addon/AddonManager.java` — `account` extracted from `called()`'s `finally` (the
         `finally` then reads `Sandbox.disarm(owner.env, budget); account(owner, cat, t0);`), and
         `callThrough`: `plan.md` §2.3.
      3. `src/io/brodgar/addon/Crossing.java` — new, `plan.md` §2.7 whole.
      4. `src/io/brodgar/addon/LuaAddon.java` — the `extra` table with the `export` verb, passed to
         `LuaCollection.create`; the `api` verb completed with `Crossing.minimumMet` and `Crossing.copy`
         (`plan.md` §2.6).
      5. `docs/addons/api/client/addons.md` — **Exporting**, **Reading an export**, **What a call does** and
         **What crosses, and how** (`plan.md` §4.1), typed as drafted; the `addons:export(t)` row in **The
         collection**; the `:api()` row's link. `DOCUMENTATION.md` §1–§11 apply (`plan.md` §0).
      6. Row edits of `plan.md` §4.4 tagged **3**: `runtime.md` (sandbox row, budgets row),
         `api/client/profiling/attribution.md` (two rows), `guides/permissions.md` (one sentence).
      7. `rm -rf build/classes && ant hafen-client && ant bin`; both checkers.
      8. The `jshell` proof of `plan.md` §5 (156.3): `5`, the read-only sentence, `nil` after the teardown,
         `liba.add: liba is disabled` from a kept wrapper. Paste the transcript into the report. The permission
         gate is verified by reading: `requirePermission` reads `current()`, pushed by `enterLua(owner)` inside
         `callThrough` — quote both lines in the report.

      **Its suite** — `addons/156-library-addons.3/manifest.json`:

```json
{ "id": "156-library-addons.3", "name": "156.3 suite", "version": "0.1.0", "author": "brodgar",
  "api_version": "1.0", "files": ["main.lua"],
  "description": "the export door: export, api, what crosses" }
```

      `main.lua` (the shared helpers first, then). The refused export comes **before** the good one: a
      refused `export` exports nothing, and the good one can happen only once.

```lua
local t = {
  add  = function(x, y) return x + y end,
  echo = function(x) return x end,
  call = function(callback) return callback() end,
  same = function(a, b) return a == b end,
  boom = function() error("kaboom") end,
  sub  = { k = "v" },
  n    = 1,
}
local exported = false
local function run()
  pass, fail, manual = 0, 0, 0
  local addons = hafen.client():addons()
  local me = addons:get(ADDON.id)
  local timer = hafen.timer():after(600, function() end)
  if not exported then
    refuses("a handle in the export is refused naming its key and kind", "'icon' is a Timer", function() return addons:export({ icon = timer }) end)
    refuses("export wants a table", "t must be a table, got string", function() return addons:export("x") end)
    check("export chains", addons:export(t) == addons, "not the collection")
    exported = true
  else
    pass = pass + 3; out("[pass] (export refusals and the export itself: proved on the first run)")
  end
  refuses("a second export refuses", "already exported", function() return addons:export(t) end)
  local api = me:api()
  check("api is my copy, the same table twice, its functions wrappers",
        api ~= t and api == me:api() and rawequal(api.add, t.add) == false and api.add == api.add, tostring(api))
  check("a call goes through the door: add(1, 2)", api.add(1, 2) == 3, api.add(1, 2))
  refuses("the copy is read-only", "export is read-only", function() api.x = 1 end)
  local keys = 0
  for _ in pairs(api) do keys = keys + 1 end
  t.later = 1
  check("pairs walks it, and a later write to t is not in it", keys == 7 and api.later == nil and api.n == 1, keys)
  check("a nested table is a copy, read-only too", api.sub ~= t.sub and api.sub.k == "v" and pcall(function() api.sub.k = "w" end) == false, tostring(api.sub))
  local arg = { a = 1 }
  local back = api.echo(arg)
  check("an argument table crosses as a copy, both ways", back ~= arg and back.a == 1 and getmetatable(back) == "read-only", tostring(back))
  refuses("a handle argument is refused naming position and kind", "argument 1 is a Timer", api.echo, timer)
  refuses("an error inside is prefixed with the label", ADDON.id .. ".boom: ", api.boom)
  local callback = function() return "x" end
  check("a callback crosses and runs; the same function is the same wrapper", api.call(callback) == "x" and api.same(callback, callback) == true, tostring(api.call(callback)))
  timer:cancel()
  summary()
end
hafen.console():on("t156", function() run() end)
```

      Expected on the first `:t156` after a `:reload`: fourteen `[pass]` lines and
      `[summary] 14 pass, 0 fail, 0 manual` (a second `:t156` in the same load prints the same count, with
      the first three folded into one line). `busy on another thread`, the teardown refusal and the
      two-`Globals` path are the `jshell` half. No `[manual]`.

- [x] **156.4 — Writing a library: the guide.** Criterion 11.

      **Edits, in order**
      1. `docs/addons/guides/libraries.md` — new, `plan.md` §4.3 whole, typed as drafted. A guide is one task
         start to finish, in tables and runnable blocks; no essay. `DOCUMENTATION.md` §1–§11 apply (`plan.md` §0).
      2. `docs/addons/guides/README.md` — the row §4.3 names.
      3. Row edits of `plan.md` §4.4 tagged **4**: `guides/saved-data.md` (one sentence). Add the
         `[Libraries](guides/libraries.md)` link to `manifest.md`'s *See Also* (`— a library is an addon others
         name in these lists.`) and to `api/client/addons.md`'s *See Also* if 156.1 left it out.
      4. Both checkers (docs-only task: no `ant` needed; `ant bin` still copies the suite).

      **Its suite** — `addons/156-library-addons.4/manifest.json`:

```json
{ "id": "156-library-addons.4", "name": "156.4 suite", "version": "0.1.0", "author": "brodgar",
  "api_version": "1.0", "files": ["main.lua"],
  "description": "the guide's library and consumer run as written" }
```

      `main.lua` (the shared helpers first, then). The library half is the guide's `toast.lua` verbatim; the
      consumer half is the guide's `notify`, its handle passed in so the same function runs with and
      without a library.

```lua
-- the guide's library, verbatim
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

-- the guide's consumer, its handle a parameter
local function notify(handle, text)
  local api = handle:api()                              -- the export, or nil
  if api then return api.show(text) else hafen.log():write(text); return "logged" end
end

local function run()
  pass, fail, manual = 0, 0, 0
  hafen.timer():after(0, function()                     -- a widget is built off the console's tree monitor
    local toast = hafen.client():addons():get(ADDON.id)
    local absent = hafen.client():addons():get("156-absent")
    local n = notify(toast, "Hola")
    check("show answers the count of open notices", n == 1, tostring(n))
    check("count reads one while the notice stands", toast:api().count() == 1, toast:api().count())
    check("without the library, notify falls back to the log", notify(absent, "Hola (logged)") == "logged", "no fallback")
    manual = manual + 1
    out("[manual] look at the top of the screen -- expect: a `Hola` notice for three seconds")
    hafen.timer():after(3.5, function()
      check("count reads zero after the notice's life", toast:api().count() == 0, toast:api().count())
      summary()
    end)
  end)
end
hafen.console():on("t156", function() run() end)
```

      Expected: three `[pass]`, the `[manual]` line, then after three and a half seconds the fourth `[pass]`
      and `[summary] 4 pass, 0 fail, 1 manual`. The `Hola (logged)` line between them is the fallback's own
      output, tagged with the suite's id.

---

## At each `/end`

- The suite is archived into `specs/156-library-addons/addons/` and its copy under `bin/addons/` deleted
  (plain `mv`, then verify both sides: `git mv -k` skips an untracked folder).
- `tools/docverbs.py` and `tools/refusalverbs.py` exit 0, run bare.
- 156.4's close fast-forwards `feature/156-library-addons` into `master` and deletes the branch. The report
  at the close names, for the maintainer to queue: the hub's `dependencies` field and the Browse task it
  unlocks; the `ROADMAP.md` line of 051 to strike.
