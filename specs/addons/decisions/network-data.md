# Decisions — Data & network

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-036 — JSON is a `hafen.json` namespace (parse/encode), reusing the existing reader + writer ✅ (maintainer, 2026-07-26)
**Decision.** Expose JSON to addons as **`hafen.json.parse(str)`** (JSON string → Lua value) and
**`hafen.json.encode(value)`** (Lua value → compact JSON string) — **not** the maintainer's first-draft
`hafen.utils.parseJSON`; a `utils` grab-bag violates [D-020](architecture-api.md)/[D-013](architecture-api.md) (namespaced by
concern). It **reuses what already exists**: the dependency-free reader [`Json.parse`](src/io/brodgar/addon/Json.java:25)
(manifests + saved vars today) and the compact writer [`AddonManager.json`](src/io/brodgar/addon/AddonManager.java:8250)
(REPL echo + `hafen.store` today). The **one refactor** ([D-013](architecture-api.md)): move the writer into
[`Json`](src/io/brodgar/addon/Json.java) as `Json.write(LuaValue[, strict])` so reader + writer live together and
the REPL, `hafen.store`, `hafen.json.encode`, and the `hafen.http` JSON-body path all call **one** serializer (no drift).
**Consequences.**
- `parse`: JSON object → string-keyed table, array → 1-indexed table, number → Lua number (ints kept as ints);
  **`null` → `nil`** (documented caveat — a hole in an array; no `json.null` sentinel in v1, one canonical way).
  Malformed input → `LuaError` (rethrows `Json.err`'s offset); **input-length + max-depth caps** ([D-018](security-sandbox.md)
  spirit) guard the recursive parser against `StackOverflow`/OOM.
- `encode`: same array-vs-object heuristic + integer cleanup + cycle detection as the REPL writer; the public path is
  **strict** — a function/userdata/thread or a cycle throws a `LuaError` (vs the REPL's forgiving `tostring`/`"<cycle>"`).
- **Ungated** — pure CPU, no I/O, no server contact. **Zero `haven` core edit** (all `io.brodgar.addon`).
- Built as **N1** (the first N-series slice).
**Rationale.** The machinery is already written and battle-tested internally; this is mostly *exposing* it the one
canonical way. JSON is independent of the network (parse a `hafen.store` string offline), so it earns its own namespace.
**See.** [19-data-and-network.md](../design/19-data-and-network.md) §2, [D-016](filesystem-build.md) (the bundled JSON facility), [D-037](network-data.md).

### D-037 — External requests are `hafen.http` (async get/post), gated by a `network` allowlist ✅ (maintainer, 2026-07-26)
**Decision.** Addons reach the network through **`hafen.http.get(url[,opts],cb)`** and
**`hafen.http.post(url,body[,opts],cb)`** — **asynchronous, always**. A blocking request cannot run on the UI thread
(it would freeze the client, and the instruction watchdog counts **Lua** instructions, so a blocking **Java** call is
never interrupted), so `hafen.http` returns a **request handle** immediately and delivers the result via a
**callback marshalled to the tick** — the exact `GobAdded`/timer pattern (off-thread queue drained on tick, per-handler
error isolation, all Lua serialized on the UI thread). `HttpURLConnection` (Java 1.8-compatible, unlike
`java.net.http.HttpClient`). The callback gets **one `res` table**: `ok` (an HTTP reply arrived, any status),
`status`, `body`, `headers` (lower-cased), or `error` (transport failure) — separating "did we get a reply?" from
"what code?". GET + POST only in v1 (read + send — two operations, not a dual style, so [D-013](architecture-api.md) holds);
POST `body` may be a string (verbatim) or a table (auto-`Json.write` → `application/json`).
**Security (maintainer choices, 2026-07-26).**
- **Allowlist, declared in the manifest** — a **`network` block** whose **`hosts`** array is the allowlist (exact host,
  case-insensitive, `*.domain` sub-domain wildcard). Its **presence grants the permission** (scoped to those hosts);
  no block ⇒ every `hafen.http.*` throws a guiding `LuaError`, like `requireActions`
  ([AddonManager.java:311](src/io/brodgar/addon/AddonManager.java:311)). A disallowed host is rejected **synchronously**
  at call time. This mirrors the "actions" permission ([D-027](actions-permissions.md)/[D-028](actions-permissions.md)) but as its own
  block (it carries config), following the `saved_variables` precedent — **not** a `permissions[]` flag. The AddOns
  panel shows declared hosts before enable (transparency, the network analog of [D-025](actions-permissions.md)'s notice).
- **Private/loopback/link-local IPs blocked** by default (`127/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`,
  `::1`, `fc00::/7`, `fe80::/10`) — checked on the resolved IP, to stop LAN-scan / internal-SSRF from third-party code.
  No user-facing localhost override in v1.
- **Limits** ([D-018](security-sandbox.md) spirit, `-D`-tunable): response size 8 MB, timeout 10 s (cap 60 s), 6 in-flight
  per addon (excess queued), a shared bounded pool (8 threads). Generic `User-Agent`, no cookie jar, no auto identity;
  hop-by-hop headers stripped; TLS verified (JDK trust store).
- **Redirects:** none auto-followed in v1 (N2a returns the 3xx raw); N2b follows ≤5 hops **re-validating the allowlist +
  private-IP check per hop** (a redirect can never escape the allowlist).
**Consequences.**
- **Ownership/teardown:** the handle is bridge-owned in `Addon.requests` (like `subs`/`timers`/`ghosts`/`images`);
  `:reload`/disable/relog cancels in-flight requests and the result is discarded on drain (cancelled ⇒ callback never
  fires). The pool is one static `ExecutorService` on `AddonManager`.
- **`haven` core edits: none expected** (JDK HTTP + our JSON + the addon-package async plumbing). Any client-shutdown
  hook for the pool would be a single `// addon:` line — flagged at build time, not assumed.
- Built as **N2a** (GET + the whole async/security substrate) then **N2b** (POST + rich headers + redirect-follow).
**Rationale.** Network access from third-party code is powerful (exfiltration, phone-home, LAN/SSRF, DoS), so it is
opt-in, allowlisted, and visible — the project's security-by-design posture ([D-017](security-sandbox.md)) applied to I/O.
Async + tick-drain reuses proven infrastructure rather than inventing a threading model. Maintainer ratified the
allowlist model, the private-IP block, and GET+POST (2026-07-26).
**See.** [19-data-and-network.md](../design/19-data-and-network.md) §3–§5, [12-security-and-permissions.md](../design/12-security-and-permissions.md), [D-036](network-data.md), [D-027](actions-permissions.md)/[D-028](actions-permissions.md), [D-018](security-sandbox.md).
