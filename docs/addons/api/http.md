# hafen.http: external HTTP requests

Fetch data from a URL outside the game. It is **protected twice over**: the `http.get` / `http.post`
[permission key](../guides/permissions.md) says whether your addon may use the network at all, and the
manifest's `network` block says which hosts — the declaration *is* the allowlist. Pair it with
[`hafen.json`](json.md) to read a JSON API.

```lua
hafen.http():get("https://api.example.com/prices")
  :on("done", function(res)
      if not res:ok() then hafen.log():write("request failed: " .. res:error()); return end
      if res:status() ~= 200 then hafen.log():write("HTTP " .. res:status()); return end
      local data = hafen.json():parse(res:body())
      hafen.log():write("iron = " .. tostring(data.iron))
    end)
  :send()
```

## Declaring network access

**Two lines, and they are one declaration.** `http.get` / `http.post` are
[permission keys](../guides/permissions.md) like any other, and the `network` block is **what those keys
take** — the key says *whether*, the hosts say *where*:

```json
{
  "id": "prices",
  "files": ["main.lua"],
  "permissions": ["http.get"],
  "network": {
    "hosts": ["api.example.com", "*.githubusercontent.com"]
  }
}
```

- **The user reads them as one line** when they enable your addon: *"fetch data from the servers it lists:
  api.example.com, \*.githubusercontent.com"*. That is the point of the key — before, a network addon
  raised no consent dialog at all and the hosts lived in a tooltip.
- **Hosts with no key is a load error** naming the key, so your addon does not run. **A key with no hosts**
  is refused at the call, naming the block to add. Neither can be forgotten quietly.
- **`hosts` is an exact, case-insensitive allowlist**, with a `*.domain` wildcard for sub-domains:
  `*.example.com` matches `a.example.com` and `a.b.example.com`, but **not** the apex `example.com`,
  which you list separately. A call to any other host is rejected **synchronously**, as a Lua error at
  call time, so wrap the call if the URL is dynamic.
- The **AddOns panel** still shows a `[net]` badge and the declared hosts in the addon's tooltip.

## Request

**A request is built bare and sent on purpose.** `hafen.http():request(url)` hands you one that has not
left, you configure it with chained setters, and `:send()` is what puts it on the wire. Every setter is
legal until `:send()` and none after — a rule with no timing in it.

```lua
hafen.http():request("https://api.example.com/report")
  :method("POST")
  :body({ char = hafen.session():current():character() })
  :header("Authorization", "Bearer " .. hafen.store():get("cfg").token)
  :timeout(5000)
  :on("done", function(res)
      if res:ok() and (res:status() == 200) then
        hafen.log():write(hafen.json():parse(res:body()).message)
      end
    end)
  :send()
```

`hafen.http():get(url)` and `hafen.http():post(url, body)` are the same thing with the method set, and
**neither takes a callback**: the handler has exactly one spelling. Passing one raises and says so.

Everything is **asynchronous**: `:send()` returns immediately and the handler runs on the UI thread a frame
or more later. There is no blocking form — a request on the UI thread would freeze the client.

## The request object

| Method | Returns | Description |
|---|---|---|
| `req:url()` | string | the address it was built for |
| `req:method()` / `req:method(name)` | string / the request | `"GET"` or `"POST"` |
| `req:body()` / `req:body(v)` | string \| nil / the request | a **string** sent verbatim, or a **table** encoded as JSON |
| `req:header(name)` | string \| nil | the value this request carries for `name`, matched case-insensitively |
| `req:header(name, value)` | the request | set a request header; setting it again replaces it, whatever the spelling |
| `req:timeout()` / `req:timeout(ms)` | number / the request | the milliseconds it will wait; **10000** by default, capped at **60000** |
| `req:on("done", fn)` | [`Sub`](event/README.md#the-sub) | the handler, called once with the [result](#the-result-object) |
| `req:send()` | the request | **put it on the wire.** Every setter above is refused from here on |
| `req:cancel()` | the request | stop it; the handler never fires |

A table body is encoded with the same serializer as [`hafen.json():encode`](json.md) and sent as
`Content-Type: application/json` unless you set your own with `:header`. One that cannot be
serialized — a function, userdata, a cycle — raises at the call.

Transport-owned headers (`Host`, `Content-Length`, `Connection`, `User-Agent`, …) are ignored. A setter
refuses an explicit `nil`: the read is the same name with no argument, so `req:timeout(t)` with a `t` you
forgot to set would otherwise read the timeout and change nothing.

The request is a [handle in the API's one shape](conventions.md#snapshots-vs-handles): a name it does not
answer raises naming the vocabulary, nothing can be written onto it, and `tostring(req)` names the method,
the URL and where it is — `Request(GET https://api.example.com/prices, sent)`.

> **The [permission](../guides/permissions.md) is checked by `:send()`**, not by `:request(url)`: nothing
> leaves the client until then, and which of `http.get` / `http.post` you need is not settled until the
> method is. The URL's *syntax* is checked where you wrote it.

## What is in flight

`hafen.http()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many)
of **your own** requests that have been sent and have not come back — the set the caps below are about.

| Call | Returns |
|---|---|
| `hafen.http():list(filter)` | every live request of yours |
| `hafen.http():count(filter)` | how many, which is what the pending cap counts |
| `hafen.http():find(filter)` | the first whose URL matches |
| `hafen.http():request(url)` / `:get(url)` / `:post(url, body)` | a new one, unsent |

A string filter is a substring test over `req:url()`. A request has **no key** — it *is* the handle
`:request(url)` gave you — so `:find(url)` is the search, and `:get(url)` here is the convenience above
rather than the collection's addressing.

```lua
if hafen.http():count() > 4 then return end            -- see the cap coming
for _, r in ipairs(hafen.http():list("example.com")) do r:cancel() end
```

## The result object

| Method | Returns | Description |
|---|---|---|
| `res:ok()` | boolean | `true` if an HTTP response arrived, whatever its status, including 4xx and 5xx; `false` only on a **transport** failure |
| `res:status()` | number \| nil | HTTP status code |
| `res:body()` | string \| nil | response body as a string, decoded with the response charset, UTF-8 by default |
| `res:header(name)` | string \| nil | one response header, matched **case-insensitively** |
| `res:error()` | string \| nil | human-readable transport-error string, `nil` when `:ok()` |

`res:ok()` separates *did we get a reply* from *what did it say*: a `404` is `ok` with `status` 404,
because the server answered, while a DNS, connect or timeout failure is not `ok` and `res:error()` is set.
So check `:ok()` first and branch on `:status()` after.

> **It is an object, like every other payload the API hands a handler**, so every field of it is a verb.
> Reach for the dot here and you get the **method**, never the value — a trap this API has everywhere,
> because `if gob.name then` is always true. `res:header("content-type")` also does the header matching
> for you: the name is matched case-insensitively, so there is nothing to remember about casing.

## Cancellation and lifecycle

`req:cancel()` marks the request dead and its handler **never fires** — there is no "cancelled" event.
A request you build and never `:send()` costs nothing and holds no slot. Reloading or disabling the addon,
and relogging, cancel every request the addon has in flight: an in-flight response is discarded and no
handler runs, so nothing leaks across a reload.

The handler runs under the same watchdog and error isolation as every other addon callback: an error
inside it is logged, never propagated.

## Security and limits

> **A request can never leave the allowlist you declared.** Every redirect hop is re-checked against
> it, and against the address rules below.

- **Private, loopback and link-local addresses are refused**, even for an allowlisted host: if it
  resolves into `127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `::1`, `fc00::/7` or
  `fe80::/10`, the request fails, `res:ok()` false, with a blocked-address error. That closes LAN
  scanning and internal-service access from addon code.
- **TLS is verified** against the JDK trust store, and certificate verification is never disabled.
- **Redirects are followed**, up to **5** hops. A `3xx` whose `Location` points at a host you did not
  declare, or at a private address, aborts with `res:ok()` false. A `303`, and a `301` or `302` on a POST,
  is followed as a `GET` with the body dropped, per HTTP convention.
- **A generic `User-Agent`, `brodgar-addon/1`, is sent**, and nothing identifies your character or your
  account. There are no cookies and no shared session: every request stands alone, and any token is one
  you keep yourself, in [`hafen.store`](store.md).
- **Resource caps**: response size **8 MB**, above which the request fails with a too-large error;
  timeout **10 s**, raisable to **60 s**; **6** requests in flight per addon, with the excess queued and
  a hard cap of **64** pending, past which `:send()` raises — `hafen.http():count()` sees it coming; and a pool of **8** threads shared by all
  addons.

## See also

- [`hafen.json`](json.md) — parsing a response body, and encoding a request one
- [`hafen.store`](store.md) — persisting tokens and cached results
- [permissions](../guides/permissions.md) — the other protected tier
