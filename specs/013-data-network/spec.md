# 013-data-network — Spec

## What & why
Data & networking: `hafen.json` (parse/encode — one canonical serializer, D-036) and
`hafen.http` (async get/post — an addon's first way off the client, gated by a declared
per-addon `network` host allowlist, D-037). JSON is pure CPU and ungated; HTTP is
security-first: allowlist + private-IP block + caps, all Lua serialized on the UI thread.
Design: [design/19-data-and-network.md](../design/19-data-and-network.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.json.encode/parse`: round-trips mixed tables; integers stay clean (`5` not
      `5.0`); `null` → absent key / array hole; malformed input is pcall-able; strict encode
      rejects functions/userdata/cycles/non-finite; depth (256) + input (8 MB) caps.
- [x] The REPL echo, `hafen.store` persistence and `hafen.json.encode` all feed through ONE
      writer (`Json.write`, strict flag the only fork) and one JSON→Lua marshal
      (`LuaMarshal.jsonToLua`).
- [x] `hafen.http.get(url[,opts],cb)` — async: validate + gate synchronously (guiding error
      on missing `network` block / non-allowlisted host), blocking I/O on a shared daemon
      pool, completion queued and drained on the tick (`cb(res)`;
      `res={ok,status,body,headers,error}`); `:cancel()`/teardown means the callback never
      fires; per-addon in-flight limit (6) never blocks a pool thread.
- [x] Security: host allowlist (exact ci + `*.domain`), private/loopback/link-local IP block
      (v4 ranges + IPv6 ULA `fc00::/7` by hand), 8 MB / 10 s caps, hop-by-hop header
      hygiene, TLS verified, generic UA, no cookies; the AddOns panel shows a `[net]` badge
      + declared hosts; `netdemo` proves get / refused host / blocked LAN resolve.
- [x] `hafen.http.post(url, body[,opts],cb)` — string verbatim, table → strict JSON +
      auto `Content-Type` (unless set); manual redirect-follow ≤5 hops with per-hop
      allowlist + IP re-validation (a redirect can never escape the declared hosts);
      303/301/302 demote to GET dropping the body; 307/308 preserve.

## Out of scope
- DNS-rebinding pinning, generic `request{method=}` (PUT/DELETE/PATCH), non-default ports,
  streaming/download-to-file (design/19 §9); message-pack or other formats.

## Context files
- `design/19-data-and-network.md` — the N-series design + threat model
- `src/io/brodgar/addon/Json.java` (reader + the unified writer), `LuaMarshal.java`
  (`jsonToLua`), `LuaHttp.java` (worker + security), `LuaHttpRequest.java`,
  `HttpApi.java` (post-split home), `Manifest.java` (`network` block)
- `docs/addons/api/json.md`, `http.md` — shipped surface
- `addons/netdemo/` — the example (get/bad/lan/post/redirect/escape)
- `../004-saved-variables/` — the store this serializer also feeds
