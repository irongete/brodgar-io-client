# hafen.http: External HTTP Requests

Fetch data from a URL outside the game, protected twice. The `http.get` / `http.post` [permission key](../guides/permissions.md) says whether your addon may use the network. The manifest's `network` block says which hosts. Pair it with [`hafen.json`](json.md) to read a JSON API.

```lua
hafen.http():get("https://api.example.com/prices")
  :on("Done", function(result)
      if not result:ok() then hafen.log():write("request failed: " .. result:error()); return end
      if result:status() ~= 200 then hafen.log():write("HTTP " .. result:status()); return end
      local prices = hafen.json():parse(result:body())
      hafen.log():write("iron = " .. tostring(prices.iron))
    end)
  :send()
```

---

## Declaring network access

The key says whether, the hosts say where. The user reads them as one line when they enable your addon: *"fetch data from the servers it lists: api.example.com, \*.githubusercontent.com"*.

```json
{
  "id": "prices",
  "api_version": "1.1",
  "files": ["main.lua"],
  "permissions": ["http.get"],
  "network": {
    "hosts": ["api.example.com", "*.githubusercontent.com"]
  }
}
```

| Rule | Detail |
|---|---|
| The approved allowlist gates a request | Not the one your manifest lists today: a host added afterwards is refused at the call, naming it, until the user approves it too. |
| Hosts with no key | A load error naming the key. Your addon does not run. A key with no hosts is refused at the call, naming the block. |
| An entry grants one origin | `[scheme://]host[:port]`, defaulting to https on 443: `api.example.com` grants `https://api.example.com:443` and nothing else. Cleartext or another port is asked for by name (`http://box.example.com:8080`). A call to any other origin is rejected synchronously, as a Lua error at call time. |
| Exact, case-insensitive, with a `*.domain` wildcard | `*.example.com` matches `a.example.com` and `a.b.example.com`, not the apex `example.com`, listed separately. A wildcard covers one domain: `*.com`, or any wildcard whose suffix is a bare top-level domain, is a load error. |
| The AddOns panel | Shows a `[net]` badge and the declared hosts in the addon's tooltip. |

## Request

A request is built bare with `hafen.http():request(url)`, configured with chained setters, and put on the wire by `:send()`. Every setter is legal until `:send()` and none after.

```lua
hafen.http():request("https://api.example.com/report")
  :method("POST")
  :body({ char = hafen.session():current():character() })
  :header("Authorization", "Bearer " .. hafen.store():var("cfg").token)
  :timeout(5000)
  :on("Done", function(result)
      if result:ok() and (result:status() == 200) then
        hafen.log():write(hafen.json():parse(result:body()).message)
      end
    end)
  :send()
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.http():request(url)` | request | Unprotected | A new request, unsent. |
| `hafen.http():get(url)` / `hafen.http():post(url, body)` | request | Unprotected | The same with the method set. Neither takes a callback: passing one raises. |
| `request:url()` | `string` | Unprotected | The address it was built for. |
| `request:method()` / `request:method(name)` | `string` / the request | Unprotected | `"GET"` or `"POST"`. |
| `request:body()` / `request:body(value)` | `string \| nil` / the request | Unprotected | A string sent verbatim, or a table encoded as JSON. |
| `request:header(name)` | `string \| nil` | Unprotected | The value this request carries for `name`, matched case-insensitively. |
| `request:header(name, value)` | the request | Unprotected | Set a request header. Setting it again replaces it, whatever the spelling. |
| `request:timeout()` / `request:timeout(milliseconds)` | `number` / the request | Unprotected | The milliseconds it waits: `10000` by default, a whole number `1..60000`. Anything else is refused, never clamped. |
| `request:on("Done", fn)` | [`Sub`](event/README.md#subscribe) | Unprotected | The handler, called once with the [result](#the-result-object), whether or not the exchange completed. The one key a request has, and legal before and after `:send()`. |
| `request:send()` | the request | `http.get` / `http.post` | Put it on the wire. Every setter is refused from here on (`request:on` is not one). |
| `request:cancel()` | the request | Unprotected | Stop it. The handler never fires. |

| Rule | Detail |
|---|---|
| The URL is parsed strictly | By RFC 3986. A space, an unescaped `{`, `}`, `\|` or `^`, or a malformed `%` escape raises at `:request(url)` naming the URL. So does no scheme, or a scheme other than `http`/`https`. The parsed host is the one the allowlist is asked about. Percent-encode what you interpolate into a path or query. |
| Asynchronous | `:send()` returns at once. The handler runs a frame or more later on the [step](threading.md) of the character whose login sent it. It holds no widget tree, so it may read and write any. There is no blocking form. |
| A table body | Encoded as [`hafen.json():encode`](json.md) does and sent as `Content-Type: application/json` unless you set your own. One that cannot be serialised (a function, userdata, a cycle, past the depth cap) raises at the call. |
| Headers ignored | Transport-owned ones (`Host`, `Content-Length`, `Connection`, `User-Agent` and the rest). `Accept-Encoding`: the client asks for an uncompressed body, what the 8 MB cap is measured against. `Cookie`: a session the client does not keep. |
| An explicit `nil` is refused | The read is the same name with no argument, so `request:timeout(value)` with a forgotten `value` would otherwise read and change nothing. |
| A handle | A name it does not answer raises naming the vocabulary. Nothing can be written onto it ([handles](conventions.md#snapshots-vs-handles)). `tostring(request)` is `Request(GET https://api.example.com/prices, sent)`. |
| The permission is checked by `:send()` | Not by `:request(url)`: nothing leaves until then, and which of `http.get`/`http.post` you need is not settled until the method is. |

## What is in flight

`hafen.http()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of your own requests sent and not yet back, the set the caps below count.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.http():list(filter)` | request`[]` | Unprotected | Every live request of yours. |
| `hafen.http():count(filter)` | `number` | Unprotected | How many. What the pending cap counts. |
| `hafen.http():find(filter)` | request `\| nil` | Unprotected | The first whose URL matches. |

A string filter is a substring test over `request:url()`. A request has no key (it is the handle `:request(url)` gave you), so `:find(url)` is the search. `:get(url)` here is the constructor above.

```lua
if hafen.http():count() > 4 then return end            -- see the cap coming
for _, request in ipairs(hafen.http():list("example.com")) do request:cancel() end
```

## The result object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `result:ok()` | `boolean` | Unprotected | `true` if an HTTP response arrived, whatever its status (4xx and 5xx included). `false` only on a transport failure. |
| `result:status()` | `number \| nil` | Unprotected | HTTP status code. |
| `result:body()` | `string \| nil` | Unprotected | The body as a string, decoded with the response charset, UTF-8 by default. |
| `result:header(name)` | `string \| nil` | Unprotected | One response header, matched case-insensitively. |
| `result:error()` | `string \| nil` | Unprotected | The transport error. `nil` when `:ok()`. |

| Rule | Detail |
|---|---|
| Check `:ok()` first, branch on `:status()` after | A `404` is `ok` with `status` 404. A DNS, connect or timeout failure is not `ok` and `:error()` is set. |
| An object, not a table | Every field is a verb: `result.status` is the method, always true. |
| A header sent twice | Most repeated headers are one comma-separated list and are joined with `", "`. `set-cookie` is not (a cookie's `Expires` contains a comma), so `result:header("set-cookie")` answers every line joined with `"\n"`, and a header value never contains one. |

```lua
for line in (result:header("set-cookie") or ""):gmatch("[^\n]+") do
  hafen.log():write(line)
end
```

## Cancellation and lifecycle

| Rule | Detail |
|---|---|
| `request:cancel()` | The handler never fires. There is no "cancelled" event. The exchange is closed where it stands, so no further hop goes out. |
| Unsent | A request built and never `:send()`ed costs nothing and holds no slot. |
| Reload, disable, relogin | Cancel every request in flight: the response is discarded and no handler runs. A request whose login ends before the reply is ended the same way, since the handler belongs to that character's tick. |
| A request needs a login | `:send()` from a client holding none (the login screen, before your first character reaches the world) is accepted and never started. It waits holding a slot until the reload or disable cancels it. Send from [`SessionEnteredWorld`](event/bus/lifecycle.md#sessions) or later. Check [`hafen.session():list()`](session.md) if you might be earlier. |
| The handler | Runs under the same watchdog and error isolation as every callback: an error is logged, never propagated. |

## Security and limits

> **A request can never leave the hosts the user approved.** Every redirect hop is re-checked against them and against the address rules.

| Rule | Detail |
|---|---|
| Private, loopback and link-local addresses are refused | Even for an allowlisted host: `127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `100.64.0.0/10`, `192.0.0.0/24`, `198.18.0.0/15`, `::1`, `fc00::/7`, `fe80::/10`. The request fails, `result:ok()` false, with a blocked-address error. |
| The address checked is the address used | A hop is resolved once and a plain `http` connection made to that address, so a name answering public then private reaches nothing. For `https` the binding is the certificate: a valid chain for the host you named, which an internal address cannot present. |
| TLS | Verified against the JDK trust store, never disabled. |
| Redirects | Followed up to 5 hops. A `3xx` whose `Location` leaves the approved set or points at a private address aborts with `result:ok()` false. A `303`, and a `301` or `302` on a POST, is followed as a `GET` with the body dropped. |
| Your headers stop at the host you addressed | A redirect that changes host drops every header you set. The client's own headers go on every hop. |
| Identity | A generic `User-Agent`, `brodgar-addon/1`. Nothing identifies your character or account. No cookies, no shared session. Any token is one you keep in [`hafen.store`](store/README.md). |
| One deadline over the whole exchange | Connect, redirects and body together. Runs out with a `timeout` error. |
| A response is text | `result:body()` decodes with the response charset, and there is no byte accessor. An image or an archive comes back as the text decoding made of it. |
| Caps | Response size 8 MB (fails with a too-large error). Timeout 10 s by default, `1 ms..60 s`. 6 requests in flight per addon with the excess queued and a hard cap of 64 pending, past which `:send()` raises. A pool of 8 threads shared by all addons. |

---

## See Also

- [`hafen.websocket`](websocket.md) — a connection the server can push on, under the same key shape and allowlist.
- [`hafen.json`](json.md) — parsing a response body, and encoding a request one.
- [`hafen.store`](store/README.md) — persisting tokens and cached results.
- [Permissions](../guides/permissions.md) — the protected tier.
