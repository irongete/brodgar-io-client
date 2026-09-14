# hafen.http: External HTTP Requests

Make asynchronous outbound HTTP/HTTPS GET and POST requests to external web services.

## Manifest Requirements

Outgoing network access requires declaring permissions and an explicit host allowlist in `manifest.json`:

```json
{
  "permissions": ["http.get", "http.post"],
  "network": {
    "hosts": [
      "api.example.com",
      "*.githubusercontent.com"
    ]
  }
}
```

* Defaults to HTTPS on port 443 unless an alternate scheme/port is specified (`http://custom.host:8080`).

---

## Quick Example

```lua
hafen.http():get("https://api.example.com/status")
  :header("Accept", "application/json")
  :timeout(10)
  :on("done", function(response)
    if not response:ok() then
      hafen.log():write("HTTP request failed: " .. (response:error() or "Unknown error"))
      return
    end

    if response:status() == 200 then
      local payload = hafen.json():decode(response:body())
      hafen.log():write("Server version: " .. tostring(payload.version))
    else
      hafen.log():write("Server returned HTTP error code: " .. response:status())
    end
  end)
  :send()
```

---

## Methods on `hafen.http()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:get(url)` | `string` | `HttpRequest` | `http.get` | Creates an HTTP GET request object. |
| `:post(url)` | `string` | `HttpRequest` | `http.post` | Creates an HTTP POST request object. |
| `:request(url, [method])` | `string, [string]` | `HttpRequest` | `http.get` \| `http.post` | Creates a generic HTTP request object. |

---

## Methods on `HttpRequest`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:header(name, value)` | `string, string` | `self` | Appends a request header. |
| `:body(content)` | `string` | `self` | Sets the request body payload (for POST requests). |
| `:timeout(seconds)` | `number` | `self` | Sets request timeout in seconds (default 15s). |
| `:on("done", callback)` | `string, function(response)` | `self` | Registers the response completion callback. |
| `:send()` | None | `self` | Dispatches the request asynchronously. |

---

## Methods on `HttpResponse`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:ok()` | None | `boolean` | `true` if the request completed without network or DNS errors. |
| `:status()` | None | `number` | HTTP response status code (e.g. `200`, `404`, `500`). |
| `:body()` | None | `string` | Raw response body text. |
| `:error()` | None | `string \| nil` | Error message if `:ok()` is `false`. |
| `:header(name)` | `string` | `string \| nil` | Retrieves a specific response header. |
