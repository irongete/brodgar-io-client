# hafen.http — external HTTP requests

Fetch data from an external URL. `hafen.http` is **gated**: an addon may reach the network only if it
**declares a `network` block** in its `manifest.json`, and only the **hosts it lists** — the declaration
*is* the allowlist. Pair it with [`hafen.json`](json.md) to fetch and parse a JSON API.

> **v1 (N2a) ships `get` only.** `post` (with request bodies and redirect-follow) arrives in N2b.

| Member | Description |
|---|---|
| `hafen.http.get(url [, opts], cb)` | perform an HTTP GET; returns a request handle |

```lua
hafen.http.get("https://api.example.com/prices", function(res)
  if not res.ok then hafen.log("request failed: " .. res.error); return end
  if res.status ~= 200 then hafen.log("HTTP " .. res.status); return end
  local data = hafen.json.parse(res.body)      -- JSON string → Lua table
  hafen.log("iron = " .. tostring(data.iron))
end)
```

## Declaring network access (required)

Add a `network` block to your `manifest.json` whose `hosts` array lists every host you will call:

```json
{
  "id": "prices",
  "files": ["main.lua"],
  "network": {
    "hosts": ["api.example.com", "*.githubusercontent.com"]
  }
}
```

- **No `network` block ⇒ no network.** Any `hafen.http.*` call raises a Lua error telling you to add one.
- **`hosts` is an exact, case-insensitive allowlist** with a **`*.domain`** wildcard for sub-domains:
  `*.example.com` matches `a.example.com` and `a.b.example.com`, but **not** the apex `example.com`
  (list the apex separately). A call to any host not matched is **rejected synchronously** (a Lua error
  at call time — wrap it in `pcall` if the URL is dynamic).
- The **AddOns panel** shows a `[net]` badge and the declared hosts in the addon's tooltip, so the user
  sees which servers the addon talks to **before** enabling it.

## `get(url [, opts], cb)`

- **`url`** — string. Scheme must be `http` or `https`; the host must be in your allowlist.
- **`opts`** (optional table):
  - `headers` — a table of string→string request headers (e.g. `{ Authorization = "Bearer …" }`).
    Transport-owned headers (`Host`, `Content-Length`, `Connection`, `User-Agent`, …) are ignored.
  - `timeout` — milliseconds; default **10000**, capped at **60000**.
- **`cb`** (optional) — `function(res)`, called later on the UI thread with the [result table](#the-res-table).
  Omit it to fire-and-forget (transport errors are still logged).

Returns a **request handle** `{ :cancel() }`. `hafen.http` is **asynchronous** — the call returns
immediately and the callback runs a frame or more later, once the reply arrives.

```lua
local req = hafen.http.get("https://api.example.com/slow",
  { headers = { Authorization = "Bearer " .. hafen.store.cfg.token }, timeout = 5000 },
  function(res) ... end)

-- later, if you no longer want it:
req:cancel()      -- the callback will NOT fire
```

## The `res` table

| Field | When | Meaning |
|---|---|---|
| `res.ok` | always | `true` if an HTTP response was received (**any** status, incl. 4xx/5xx); `false` only on a **transport** failure. |
| `res.status` | `ok` | HTTP status code (`200`, `404`, `500`, …). |
| `res.body` | `ok` | response body as a string (decoded via the response charset, default UTF-8). |
| `res.headers` | `ok` | response headers, keys **lower-cased** (`res.headers["content-type"]`). |
| `res.error` | `not ok` | human-readable transport-error string. |

`res.ok` separates *"did we get a reply?"* from *"what code?"* — a `404` is `ok=true, status=404` (the
server answered); a DNS/connect/timeout failure is `ok=false, error=…`. Check `res.status` for HTTP-level
outcomes:

```lua
hafen.http.get(url, function(res)
  if not res.ok then hafen.log("transport error: " .. res.error); return end
  if res.status == 200 then
    local data = hafen.json.parse(res.body)
    ...
  elseif res.status == 404 then
    hafen.log("not found")
  end
end)
```

## Cancellation & lifecycle

- **`req:cancel()`** marks the request dead; its callback **never fires** (there is no "cancelled"
  callback).
- **`:reload` / disabling the addon / relogging** cancels every in-flight request the addon owns — an
  in-flight response is discarded and no callback runs. Nothing leaks across a reload.

## Security & limits

- **Private / loopback / link-local IPs are refused** even for an allowlisted host: if the host resolves
  to `127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `::1`, `fc00::/7`, or `fe80::/10`,
  the request fails with `ok=false, error="… blocked address …"`. This blocks LAN scanning / internal-
  service access from addon code.
- **TLS is verified** (the JDK default trust store); certificate verification is never disabled.
- **No redirects in v1** — a `3xx` is returned to you raw (`status` + the `location` header).
- **A generic `User-Agent`** (`brodgar-addon/1`) is sent; nothing identifies your character or account.
  **No cookies, no shared session** — each request is stateless; any token is your own (from your
  [`hafen.store`](store.md)).
- **Resource caps** (all `-D`-tunable): response size **8 MB** (`ok=false, error="response too large"`);
  timeout **10 s**, cap **60 s**; **6** concurrent requests per addon (excess queued; a hard per-addon
  cap of **64** pending raises at call); a shared pool of **8** threads across all addons.

## Notes

- Requests never run on the UI thread (they'd freeze the client), so the API is async-only — there is no
  blocking `get`. Do your work inside the callback.
- The callback runs under the same watchdog + error-isolation as every other addon callback: an error in
  it is logged, never propagated.
- See [`hafen.json`](json.md) to parse a JSON body, and [`hafen.store`](store.md) to persist tokens or
  cached results.
