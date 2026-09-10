# hafen.http: external HTTP requests

Fetch data from a URL outside the game. It is **protected twice over**: the `http.get` / `http.post`
[permission key](../guides/permissions.md) says whether your addon may use the network at all, and the
manifest's `network` block says which hosts. Both are one question the user answers when they enable you,
and their answer is what the client enforces. Pair it with [`hafen.json`](json.md) to read a JSON API.

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
  api.example.com, \*.githubusercontent.com"*. That one line is the whole decision they make about your
  network access, which is why the hosts are written beside the key rather than hidden in a tooltip.
- **The allowlist that gates a request is the one they approved**, not the one your manifest lists today. A
  host you add to the block afterwards is refused at the call, naming it, until the user approves it too —
  so widening the block in a shipped addon is a change they answer, not one that takes effect by itself.
- **Hosts with no key is a load error** naming the key, so your addon does not run. **A key with no hosts**
  is refused at the call, naming the block to add. Neither can be forgotten quietly.
- **An entry grants one origin — a scheme, a host and a port.** Write it as `[scheme://]host[:port]`. What
  you leave out you get by default, and the defaults are **https** on **443**: so `api.example.com` grants
  `https://api.example.com:443` and nothing else, and cleartext or another port has to be asked for by name
  (`http://box.example.com:8080`). A call to any other origin is rejected **synchronously**, as a Lua error
  at call time, so wrap the call if the URL is dynamic.
- **`hosts` is an exact, case-insensitive allowlist**, with a `*.domain` wildcard for sub-domains:
  `*.example.com` matches `a.example.com` and `a.b.example.com`, but **not** the apex `example.com`,
  which you list separately. **A wildcard covers one domain**: `*.com` is a load error, and so is any
  wildcard whose suffix is a bare top-level domain. The user approves this list by reading it.
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

**The URL is parsed strictly**, by RFC 3986: a space, an unescaped `{`, `}`, `|` or `^`, a malformed `%`
escape, an address with no scheme, or one whose scheme is not `http` or `https` raises at `:request(url)`,
naming the URL. The host that parse produces is the one the approved allowlist is asked about, so a URL the
client and the server would read differently never reaches the wire. Percent-encode whatever you interpolate
into a path or a query.

Everything is **asynchronous**: `:send()` returns immediately and the handler runs a frame or more later,
on the [step](threading.md) of the character whose login sent it, holding no widget tree — so it may
read and write any of them. There is no blocking form: a request waited on where your Lua runs would
freeze the client.

## The request object

| Method | Returns | Description |
|---|---|---|
| `req:url()` | string | the address it was built for |
| `req:method()` / `req:method(name)` | string / the request | `"GET"` or `"POST"` |
| `req:body()` / `req:body(v)` | string \| nil / the request | a **string** sent verbatim, or a **table** encoded as JSON |
| `req:header(name)` | string \| nil | the value this request carries for `name`, matched case-insensitively |
| `req:header(name, value)` | the request | set a request header; setting it again replaces it, whatever the spelling |
| `req:timeout()` / `req:timeout(ms)` | number / the request | the milliseconds it will wait; **10000** by default, and a whole number **1..60000** — anything else is refused, never clamped |
| `req:on("done", fn)` | [`Sub`](event/README.md#subscribe) | the handler, called once with the [result](#the-result-object); legal before `:send()` and after it |
| `req:send()` | the request | **put it on the wire.** Every setter above is refused from here on — `req:on` is not one of them, because a request in flight has not come back yet |
| `req:cancel()` | the request | stop it; the handler never fires |

A table body is encoded with the same serializer as [`hafen.json():encode`](json.md) and sent as
`Content-Type: application/json` unless you set your own with `:header`. One that cannot be
serialized — a function, userdata, a cycle, a table nested past that serializer's depth cap —
raises at the call.

Transport-owned headers (`Host`, `Content-Length`, `Connection`, `User-Agent`, …) are ignored, and so are
**`Accept-Encoding`** and **`Cookie`**: the first is how the client asks for an uncompressed body, which is
what the 8 MB cap is measured against, and the second is a session the client does not keep. A setter
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

**A header the server sent twice comes back as its lines, newline-separated.** Most repeated headers
mean one comma-separated list and are joined with `", "` — but `set-cookie` is the one header that
must not be, because a cookie's own `Expires` attribute contains a comma. So `res:header("set-cookie")`
answers every line, joined with `
`, and a header value can never contain one:

```lua
for line in (res:header("set-cookie") or ""):gmatch("[^
]+") do
  hafen.log():write(line)
end
```

## Cancellation and lifecycle

`req:cancel()` marks the request dead and its handler **never fires** — there is no "cancelled" event.
A request you build and never `:send()` costs nothing and holds no slot. Reloading or disabling the addon,
and relogging, cancel every request the addon has in flight: an in-flight response is discarded and no
handler runs, so nothing leaks across a reload.

`req:cancel()`, a reload and a disable all **close the exchange where it stands** rather than letting it
finish unheard, so no further hop goes out. A request whose login ends before the reply arrives is ended
the same way: the handler belongs to that character's tick, and there is no tick left to run it on.

**A request needs a login to run on.** `:send()` from a client holding none — at the login screen, or
before your first character reaches the world — is accepted and never started: it waits, holding a
slot, and the reload or disable that ends your addon cancels it, so its handler never fires. Send from
[`SessionEnteredWorld`](event/bus/lifecycle.md#sessions) or later, and check
[`hafen.session():list()`](session.md) if you might be earlier.

The handler runs under the same watchdog and error isolation as every other addon callback: an error
inside it is logged, never propagated.

## Security and limits

> **A request can never leave the hosts the user approved.** Every redirect hop is re-checked against
> them, and against the address rules below.

- **Private, loopback and link-local addresses are refused**, even for an allowlisted host: if it
  resolves into `127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `100.64.0.0/10`,
  `192.0.0.0/24`, `198.18.0.0/15`, `::1`, `fc00::/7` or `fe80::/10`, the request fails, `res:ok()` false,
  with a blocked-address error. That is the whole list, and it closes LAN scanning, carrier-NAT router
  interfaces and internal-service access from addon code.
- **The address that was checked is the address that is used.** A hop is resolved once, and a plain `http`
  connection is then made to that very address, so a name that answers public when it is checked and
  private a moment later reaches nothing. For `https` the binding is the **certificate**: whatever the name
  resolves to has to present a chain valid for the host you named, which an internal address cannot.
- **TLS is verified** against the JDK trust store, and certificate verification is never disabled.
- **Redirects are followed**, up to **5** hops. A `3xx` whose `Location` points at an origin outside that
  approved set, or at a private address, aborts with `res:ok()` false. A `303`, and a `301` or `302` on a
  POST, is followed as a `GET` with the body dropped, per HTTP convention.
- **Your headers stop at the host you addressed.** A redirect that changes host drops every header you set
  — the second host is one the user approved separately, and a bearer token for the first is not a bearer
  token for it. The client's own headers go on every hop.
- **A generic `User-Agent`, `brodgar-addon/1`, is sent**, and nothing identifies your character or your
  account. There are no cookies and no shared session: every request stands alone, and any token is one
  you keep yourself, in [`hafen.store`](store.md).
- **The timeout is one deadline over the whole exchange** — connect, redirects and body together — so five
  hops cannot spend it five times and a server sending one byte at a time cannot outlast it. It fails with
  a `timeout` error whenever it runs out.
- **A response is text.** `res:body()` decodes it with the response charset and there is no byte
  accessor beside it, so a request for an image or an archive comes back as the text that decoding
  made of it and is not what the server sent. Ask for what you can read: JSON, plain text, anything
  the server will encode for you.
- **Resource caps**: response size **8 MB**, above which the request fails with a too-large error;
  timeout **10 s** by default and settable anywhere in **1 ms..60 s**, refused outside it; **6** requests in
  flight per addon, with the excess queued and a hard cap of **64** pending, past which `:send()` raises —
  `hafen.http():count()` sees it coming; and a pool of **8** threads shared by all addons.

## See also

- [`hafen.json`](json.md) — parsing a response body, and encoding a request one
- [`hafen.store`](store.md) — persisting tokens and cached results
- [permissions](../guides/permissions.md) — the other protected tier
