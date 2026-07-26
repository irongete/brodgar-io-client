# N1 — `hafen.json` (parse / encode)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **27/27 headless logic
> checks** (encode↔parse round-trip; integer cleanliness `{"n":5}`→`5`; `null`→nil absent-key + array
> hole; malformed→pcall-able; the depth cap trips / a shallow doc passes under it; strict encode rejects
> userdata / non-finite / cycle; the forgiving writer still emits `"<cycle>"` / quoted userdata / `null`;
> empty containers; escape round-trip; booleans + nesting) + LuaJ parse of the extended `hello` harness.
> **In-game DoD pending.**
> **Design:** [specs/addons/19-data-and-network.md](../../../specs/addons/19-data-and-network.md) §2
> (the N-series spec; decisions **D-036** JSON-is-`hafen.json`, **D-013** one canonical serializer,
> **D-018** hostile-input caps), [specs/addons/api-reference.md](../../../specs/addons/api-reference.md)
> (`hafen.json`), [specs/addons/code-map.md](../../../specs/addons/code-map.md) (N-series seams).

`hafen.json` is the first slice of the **N-series** (data & networking). It is mostly *exposing machinery
that already exists*: the client has shipped a complete dependency-free JSON **reader**
([`Json.parse`](../../../src/io/brodgar/addon/Json.java)) since Phase 1 (it parses manifests + saved
variables) and a compact JSON **writer** (it backed the `:lua` REPL echo + `hafen.store` persistence).
Neither was reachable from Lua. N1 exposes both as `hafen.json.parse` / `hafen.json.encode`, and — the
one refactor the spec called for — **unifies the two writers into a single serializer** so there is no
drift (D-013). No network, no permission: JSON is pure CPU, ungated.

## The surface

```lua
local s = hafen.json.encode({ name = "ore", qty = 5, tags = { "raw", "metal" } })
-- '{"name":"ore","qty":5,"tags":["raw","metal"]}'
local t = hafen.json.parse(s)     -- objects → string-keyed tables, arrays → 1-based, null → nil
```

- **`parse(str)` → Lua value.** Wraps `Json.parse` (Java `Map`/`List`/`String`/`Double`/`Boolean`/`null`)
  and marshals the result to Lua. Malformed input raises a pcall-able `LuaError` (`"JSON: <msg> at offset
  <n>"`, straight from the reader).
- **`encode(value)` → compact JSON string.** Reuses the compact writer, run in **strict** mode: a
  function / userdata / thread / cycle / non-finite number raises a `LuaError` (the public entry point
  must only ever produce valid JSON — unlike the REPL echo's forgiving `"<cycle>"` / quoted-`tostring`).

## What changed (three files in `io.brodgar.addon`, zero `haven` core edit)

### 1. The writer moved into `Json` (D-013) — `Json.write(LuaValue[, strict])`

The compact writer (`json`/`jsontab`/`jsonstr`) lived as private methods on `AddonManager`. It moved into
[`Json.java`](../../../src/io/brodgar/addon/Json.java) — beside the reader it mirrors — as
`Json.write(LuaValue)` (forgiving) / `Json.write(LuaValue, boolean strict)`. Reader + writer now live
together; **one canonical serializer** feeds all callers, so they can never drift:

- **`:lua` REPL echo** (`AddonManager` console) → `Json.write(r)` (forgiving — a function result still
  echoes as a quoted `tostring`, a cycle as `"<cycle>"`, copy-friendly).
- **`hafen.store` persistence** (`scopeJson`) → `Json.write(wrap)` (forgiving; the store only ever holds
  data tables anyway).
- **`hafen.json.encode`** → `Json.write(v, true)` (**strict** — the two deliberate differences from the
  REPL echo: non-serializable value → `LuaError`, cycle → `LuaError`, and a non-finite number → `LuaError`
  rather than the REPL's `null`).

The `strict` flag is the only behavioural fork; the array-vs-object heuristic, integer cleanup, and string
escaping are identical (moved verbatim). `AddonManager`'s now-unused `LuaNumber`/`LuaString` imports were
dropped.

### 2. The JSON→Lua marshal is now canonical — `LuaMarshal.jsonToLua(Object)`

`AddonManager` had a private `jsonToLua` (used by the `hafen.store` restore) that converted a parsed-JSON
`Map`/`List`/scalar to Lua. It moved to [`LuaMarshal`](../../../src/io/brodgar/addon/LuaMarshal.java) (the
existing home of the shared Java↔Lua marshalling, D-013) so **both** `hafen.store` restore and
`hafen.json.parse` convert the one way. Two refinements while centralizing:

- **Integer preservation.** The old store marshal converted every number with `doubleValue()` (so `5`
  restored as `5.0`). `LuaMarshal.jsonToLua` returns an **`int`** for an integral value in Lua's int range
  (`{"n":5}` → `5`, matching the spec table). Harmless for the store (the writer normalizes integral
  doubles to `5` anyway) and correct for `hafen.json`.
- **`null` handling made explicit.** A JSON `null` → `NIL`; `set(key, NIL)` on a `LuaTable` yields an
  **absent key** (object) or a **hole** with the index still advancing (array) — the documented Lua-JSON
  caveat. `AddonManager.fillTable` (the in-place store restore that must preserve the addon's cached table
  reference) now just delegates each value to `LuaMarshal.jsonToLua`.

### 3. The bridge — `hafen.json` table (`AddonManager`)

Built beside `hafen.log`/`hafen.store` in the per-addon facade. `parse` checks the input-length cap first
(`Json.MAX_INPUT`), parses with the depth cap (`Json.DEFAULT_MAX_DEPTH`), and rethrows the reader's
`RuntimeException` as a `LuaError`; `encode` calls `Json.write(v, true)`.

### Hostile-input caps (D-018 spirit)

The reader is recursive (`object`→`value`→`object`…), so a deeply-nested document could `StackOverflow`,
and a huge string could exhaust memory. `Json` grew:

- a **max-depth** guard inside `object()`/`array()` (a `depth` counter vs `maxDepth`), used by
  `Json.parse(text, maxDepth)`. `Json.parse(text)` keeps a generous default (`256`), which also hardens
  the internal manifest/saved-var callers for free.
- an **input-length** cap (`Json.MAX_INPUT`, default 8 MB) enforced at the `hafen.json.parse` bridge
  before parsing.

Both are `-D`-tunable (`haven.addon.json.maxdepth` / `.maxlen`), like `haven.addon.insncap`.

## Threading / safety

Nothing new: `parse`/`encode` are synchronous, pure-CPU, and run on the calling (UI) thread under the
instruction watchdog like any other bridge call. No I/O, no `haven` widget state, no permission. (The
network half — `hafen.http`, which *is* async and gated — is N2a/N2b.)

## Harness (`hello` v0.45.0)

A new `OnLoad` self-check (beside the sandbox self-check) round-trips a mixed table
(`{iron=5, name="ore", frac=2.5, nums={10,20,30}, flag=true}`): `encode` → `parse` → assert the scalars,
the numeric array, the boolean, and the float survive; assert `parse('{"a":1,"b":null}').b == nil` (the
`null`→absent-key caveat); assert `tostring(parse('{"n":5}').n) == "5"` (integer cleanliness); and assert
`pcall(hafen.json.parse, "{bad}")` returns `false` (malformed is catchable). One log line reports all four
booleans + the encoded string, so one login re-verifies the serializer alongside every other feature.

## Deferred (→ N2a / N2b)

- `hafen.http.get` + the async/security substrate (thread pool, `HttpURLConnection`, result queue + tick
  drain, `res` table, `:cancel()` handle, the `network` manifest block + host allowlist + private-IP block
  + caps, panel host display) — **N2a**.
- `hafen.http.post` + rich headers + redirect-follow — **N2b**. Its JSON-body encoder will call this
  same `Json.write`.
