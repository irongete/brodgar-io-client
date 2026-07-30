# Learnings — Network & data (json, http)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **N1 — the JSON writer + JSON→Lua marshal already existed, just weren't shared or exposed.** The compact writer lived
  as private `AddonManager.json`/`jsontab`/`jsonstr`; a JSON→Lua converter lived as private `AddonManager.jsonToLua`.
  N1's real work was **consolidation** (D-013), not new code: move the writer into `Json.write(v[,strict])` beside the
  reader, move the marshal into `LuaMarshal.jsonToLua`, and point every caller (REPL echo, `hafen.store`, the new
  `hafen.json`) at the one copy. **Lesson:** before writing a "new" serializer, grep — the client had two, and the task
  was to *unify* them so `hafen.json` and `hafen.store` can never disagree.
- **`strict` is the only fork between the public encoder and the REPL echo.** The REPL echo must stay forgiving (a
  function result echoes as a quoted `tostring`, a cycle as `"<cycle>"`, `NaN`→`null`) so the console never throws mid-
  inspect; `hafen.json.encode` must be strict (those all throw) so its output is always valid JSON. One boolean flag on
  the shared writer covers both — no duplicated logic.
- **`Json.parse` always returns `Double`, so integer-ness is reconstructed at marshal time, not preserved.** The reader
  has no int type. `LuaMarshal.jsonToLua` narrows an **integral** value in Lua's int range back to a Lua int (`{"n":5}`
  → `5`), so it prints `5` and compares `== 5`. (Even without the narrowing the *encoded* form would be clean — the
  writer already normalizes integral doubles to `5` — but the narrowing makes the parsed value itself int-typed.)
- **Setting a table key to `NIL` is how JSON `null` becomes an absent key / array hole.** `LuaTable.set(k, NIL)` removes
  the key; for an array the position counter still advances, so `[1,null,3]` → `{[1]=1,[3]=3}` (a hole at 2). This is
  the standard Lua-JSON trade-off — documented, no `json.null` sentinel — and falls out for free from the marshal.
- **Hostile-input caps belong in the parser, not just the bridge.** The depth guard lives inside `Json.object()`/
  `array()` (a `depth` counter), so `Json.parse(text)` with the default 256 also protects the internal manifest/saved-var
  callers from a `StackOverflow`, not only `hafen.json`. The length cap is a cheap pre-check at the bridge. Both
  `-D`-tunable, mirroring `haven.addon.insncap`.
- **N2a — a blocking Java call from Lua is invisible to the watchdog, so network MUST be async.** The instruction
  watchdog counts **Lua** ops; a Lua→Java `HttpURLConnection` call blocks the UI thread with zero Lua instructions
  executing, so it would hang forever un-aborted. Hence `hafen.http` is async-only (no blocking `get`), reusing the
  exact `GobAdded` marshal: pool thread does I/O → enqueue → tick drains → armed+isolated callback. All addon Lua
  still runs serialized on the UI thread under the watchdog; the pool only ever touches JDK/`haven`-free code.
- **Per-addon concurrency limits must not block pool threads.** The naïve "acquire a per-addon `Semaphore(6)` in the
  worker" starves the shared 8-thread pool (blocked workers hold threads doing nothing, and one busy addon can wedge
  the rest). Instead a **UI-thread scheduler** (`maybeStartHttp`) counts running requests and only *submits* up to
  the limit, leaving the excess as un-started entries in `Addon.requests`; the drain re-runs it as slots free. The
  pool is always doing real work, never waiting.
- **`network` is a manifest BLOCK, not a `permissions[]` string — because the declaration carries config.** Like
  `saved_variables`, a capability-with-configuration (the host allowlist) gets its own object block. `usesActions()`
  is a bare flag; `hostAllowed(host)` needs the list. The block's presence (non-empty `hosts`) *is* the grant.
- **`InetAddress`'s own classifiers cover almost all the private ranges — except IPv6 ULA `fc00::/7`.**
  `isLoopbackAddress`/`isAnyLocalAddress`/`isLinkLocalAddress`/`isSiteLocalAddress`/`isMulticastAddress` between them
  cover `127/8`, `::1`, `0.0.0.0`/`::`, `169.254/16`+`fe80::/10`, `10/8`+`172.16/12`+`192.168/16`, and multicast —
  but **not** `fc00::/7`, which needs a manual `(b[0] & 0xfe) == 0xfc` check on the 16-byte address. Check **all**
  resolved addresses (`getAllByName`), not just the first, so a dual-record host can't smuggle a private A record.
- **`localtest.me` (and similar) resolve to `127.0.0.1` — a clean way to demo the private-IP block without a bad
  manifest.** Allowlisting a public *hostname* that DNS-resolves to loopback exercises the resolved-IP refusal path
  (accepted at the allowlist, refused on the resolved address) distinctly from the synchronous host-allowlist
  rejection. `netdemo :lan` uses it.
- **N2b — following redirects manually is the ONLY way to keep them inside the allowlist.** `HttpURLConnection`'s
  built-in `setInstanceFollowRedirects(true)` jumps silently, with **no per-hop callback** to re-check the target host —
  so it could carry a request from an allowlisted host to any redirect target (SSRF via an open redirect). The fix is
  `setInstanceFollowRedirects(false)` + a manual `while` hop-loop that runs the same `validateHop` (allowlist +
  private-IP) on **every** `Location` before connecting. Cap the hops (5) to stop redirect cycles, and resolve a
  relative `Location` with `new URL(base, loc)`. This is why the allowlist re-check is worth the loop.
- **A POST that follows a 301/302/303 must demote to GET + drop the body.** Per HTTP semantics (and every browser),
  a `303` is always a GET, and a `301`/`302` on a non-idempotent method (POST) becomes a GET too; only `307`/`308`
  preserve the method + body. Keep `method`/`body` as **mutable locals** in the hop-loop (seeded from the immutable
  request) so the demotion is local to the worker and the original handle is untouched.
- **`Content-Type` for a JSON body is set only if the addon didn't set its own** — a case-insensitive scan of the
  caller's headers (`hasContentType`), so `opts.headers = { ["Content-Type"] = "application/…" }` wins over the
  `application/json` default. The table→JSON convenience is the ergonomic default, never an override.
