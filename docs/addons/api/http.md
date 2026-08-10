# hafen.http: external HTTP requests

Fetch data from a URL outside the game. `hafen.http()` is **protected by your manifest**: an addon reaches the
network only if it declares a `network` block, and only the hosts that block lists — the declaration
*is* the allowlist. Pair it with [`hafen.json`](json.md) to read a JSON API.

```lua
hafen.http():get("https://api.example.com/prices", function(res)
  if not res.ok then hafen.log():write("request failed: " .. res.error); return end
  if res.status ~= 200 then hafen.log():write("HTTP " .. res.status); return end
  local data = hafen.json():parse(res.body)
  hafen.log():write("iron = " .. tostring(data.iron))
end)
```

## Declaring network access

Add a `network` block to `manifest.json` whose `hosts` array lists every host you will call:

```json
{
  "id": "prices",
  "files": ["main.lua"],
  "network": {
    "hosts": ["api.example.com", "*.githubusercontent.com"]
  }
}
```

- **No `network` block means no network.** Any `hafen.http()` call raises a Lua error telling you to add
  one.
- **`hosts` is an exact, case-insensitive allowlist**, with a `*.domain` wildcard for sub-domains:
  `*.example.com` matches `a.example.com` and `a.b.example.com`, but **not** the apex `example.com`,
  which you list separately. A call to any other host is rejected **synchronously**, as a Lua error at
  call time, so wrap the call if the URL is dynamic.
- The **AddOns panel** shows a `[net]` badge and the declared hosts in the addon's tooltip, so the user
  sees which servers it talks to before enabling it.

## Request

Both verbs are **asynchronous**: the call returns immediately, and the callback runs on the UI thread a
frame or more later. There is no blocking form — a request on the UI thread would freeze the client —
so do your work inside the callback.

Each returns the **request object**, which you configure with chained setters. A request goes out on
the next tick, not inside the call that created it, so everything you chain onto it is applied before
it leaves; a request you cancel in the same call is never sent at all. Once it has gone, a setter
raises rather than pretending to change what is already on the wire.

### `hafen.http():get(url, cb)`

| Argument | Type | Meaning |
|---|---|---|
| `url` | string | scheme must be `http` or `https`, and the host must be in your allowlist |
| `cb` | function | `function(res)`, called with the [result table](#the-res-table). Omit it to fire and forget; a transport error is still logged |

```lua
local req = hafen.http():get("https://api.example.com/slow",
                             function(res) hafen.log():write(res.status) end)
req:header("Authorization", "Bearer " .. hafen.store():get("cfg").token):timeout(5000)

req:cancel()      -- the callback will NOT fire
```

### `hafen.http():post(url, body, cb)`

`get` plus a request **body**; `cb` behaves exactly as above.

`body` is either a **string**, sent verbatim, or a **table**, encoded to JSON with the same serializer
as [`hafen.json():encode`](json.md) and sent as `Content-Type: application/json` unless you set your own
with `:header`. A table that cannot be serialized — a function or userdata value, a cycle — raises a
Lua error at call time. `nil` sends an empty POST.

```lua
-- POST a Lua table as JSON, read JSON back
hafen.http():post("https://api.example.com/report",
  { char = hafen.player():name(), lp = hafen.char():lp() },
  function(res)
    if res.ok and res.status == 200 then
      local reply = hafen.json():parse(res.body)
      hafen.log():write(reply.message)
    end
  end):header("Authorization", "Bearer " .. hafen.store():get("cfg").token)

-- or a raw string body with your own content type
hafen.http():post("https://api.example.com/ingest", "a,b,c\n1,2,3")
  :header("Content-Type", "text/csv")
```

## The request object

| Method | Returns | Description |
|---|---|---|
| `req:header(name)` | string \| nil | the value this request carries for `name`, matched case-insensitively |
| `req:header(name, value)` | the request | set a request header; setting it again replaces it, whatever the spelling |
| `req:timeout()` | number | the milliseconds this request will wait |
| `req:timeout(ms)` | the request | set the timeout; **10000** by default, capped at **60000** |
| `req:cancel()` | nothing | stop it; the callback never fires |

Transport-owned headers (`Host`, `Content-Length`, `Connection`, `User-Agent`, …) are ignored. A
setter refuses an explicit `nil`: the read is the same name with no argument, so `req:timeout(t)` with
a `t` you forgot to set would otherwise read the timeout and change nothing.

## The res table

| Field | When | Meaning |
|---|---|---|
| `res.ok` | always | `true` if an HTTP response arrived, whatever its status, including 4xx and 5xx; `false` only on a **transport** failure |
| `res.status` | `ok` | HTTP status code |
| `res.body` | `ok` | response body as a string, decoded with the response charset, UTF-8 by default |
| `res.headers` | `ok` | response headers, keys **lower-cased**, so `res.headers["content-type"]` |
| `res.error` | not `ok` | human-readable transport-error string |

`res.ok` separates *did we get a reply* from *what did it say*: a `404` is `ok = true, status = 404`,
because the server answered, while a DNS, connect or timeout failure is `ok = false` with `res.error`
set. So check `ok` first and branch on `status` after.

## Cancellation and lifecycle

`req:cancel()` marks the request dead and its callback **never fires** — there is no "cancelled"
callback. Reloading or disabling the addon, and relogging, cancel every request the addon has in
flight: an in-flight response is discarded and no callback runs, so nothing leaks across a reload.

The callback runs under the same watchdog and error isolation as every other addon callback: an error
inside it is logged, never propagated.

## Security and limits

> **A request can never leave the allowlist you declared.** Every redirect hop is re-checked against
> it, and against the address rules below.

- **Private, loopback and link-local addresses are refused**, even for an allowlisted host: if it
  resolves into `127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `::1`, `fc00::/7` or
  `fe80::/10`, the request fails with `ok = false` and a blocked-address error. That closes LAN
  scanning and internal-service access from addon code.
- **TLS is verified** against the JDK trust store, and certificate verification is never disabled.
- **Redirects are followed**, up to **5** hops. A `3xx` whose `Location` points at a host you did not
  declare, or at a private address, aborts with `ok = false`. A `303`, and a `301` or `302` on a POST,
  is followed as a `GET` with the body dropped, per HTTP convention.
- **A generic `User-Agent`, `brodgar-addon/1`, is sent**, and nothing identifies your character or your
  account. There are no cookies and no shared session: every request stands alone, and any token is one
  you keep yourself, in [`hafen.store`](store.md).
- **Resource caps**: response size **8 MB**, above which the request fails with a too-large error;
  timeout **10 s**, raisable to **60 s**; **6** requests in flight per addon, with the excess queued and
  a hard cap of **64** pending, past which the call raises; and a pool of **8** threads shared by all
  addons.

## See also

- [`hafen.json`](json.md) — parsing a response body, and encoding a request one
- [`hafen.store`](store.md) — persisting tokens and cached results
- [permissions](../guides/permissions.md) — the other protected tier
