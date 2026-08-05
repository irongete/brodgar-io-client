-- NetDemo — the hafen.http + hafen.json demo addon (N2a).
--
-- It DECLARES a "network" block in its manifest, so it may reach ONLY the hosts listed there
-- (api.github.com, localtest.me) — the declaration IS the allowlist (D-037). A network addon loads
-- normally (unlike a write/"actions" addon, which is disabled by default); the AddOns panel shows a
-- [net] badge and the exact hosts so you see which servers it talks to BEFORE enabling it.
--
-- hafen.http() is ASYNC by necessity (a blocking request can't run on the UI thread): every call returns
-- immediately with the REQUEST OBJECT and delivers the result later via cb(res) on the tick. The request
-- is configured by chained setters -- req:header(name, value), req:timeout(ms) -- and :cancel() stops it;
-- it goes out on the next tick, so everything you chain onto it is applied before it leaves. The res
-- table: res.ok (true = a reply arrived, ANY status incl. 4xx/5xx; false = a transport failure), and then
-- res.status / res.body / res.headers when ok, or res.error when not.
--
-- N2b adds POST + redirect-following: hafen.http():post(url, body, cb) sends a body (a string verbatim,
-- or a TABLE auto-encoded to application/json), and BOTH verbs now follow up to 5 redirects, re-checking the
-- allowlist + private-IP block on every hop -- so a 3xx can never escape the declared hosts.
--
-- Nothing hits the network at login. Each request is a deliberate ':netdemo <sub>' trigger:
--   :netdemo        (or :netdemo get)  GET api.github.com + hafen.json():parse the JSON reply
--   :netdemo post   POST a JSON table to httpbin.org and read the echoed body back (N2b)
--   :netdemo redirect  follow a 3xx to an allowlisted host -> ok (N2b)
--   :netdemo escape    a redirect pointing at a NON-allowlisted host is blocked mid-hop (N2b)
--   :netdemo bad    try a NON-allowlisted host -> rejected SYNCHRONOUSLY at call (pcall'd, logged)
--   :netdemo lan    GET localtest.me, which resolves to 127.0.0.1 -> refused by the private-IP block (ok=false)

hafen.log():write("netdemo loaded -- hafen.http (GET+POST) + hafen.json demo. Run  :netdemo  for the request demos.")

hafen.event():on("OnLoad", function()
  hafen.log():write("netdemo: allowlisted hosts = api.github.com, localtest.me (declared in manifest.json)")
end)

-- A small, stable public JSON API: the upstream hafen-client repo metadata.
local GH = "https://api.github.com/repos/dolda2000/hafen-client"

local function doGet()
  hafen.log():write("netdemo: GET " .. GH .. " ...")
  local req = hafen.http():get(GH, function(res)
    if not res.ok then
      hafen.log():write("netdemo: request FAILED -- " .. tostring(res.error))
      return
    end
    hafen.log():write(("netdemo: HTTP %d, %d bytes, content-type=%s")
      :format(res.status, #res.body, tostring(res.headers["content-type"])))
    if res.status ~= 200 then return end
    local ok, data = pcall(function() return hafen.json():parse(res.body) end)
    if not ok then
      hafen.log():write("netdemo: JSON parse error -- " .. tostring(data))
      return
    end
    -- data is a Lua table: pull a couple of fields out of the parsed JSON.
    hafen.log():write(("netdemo: repo=%s  stars=%s  forks=%s  lang=%s")
      :format(tostring(data.full_name), tostring(data.stargazers_count),
              tostring(data.forks_count), tostring(data.language)))
  end)
  -- R4: no options table. The request is configured by chained setters, and it has not gone out yet.
  req:header("Accept", "application/vnd.github+json"):timeout(8000)
  hafen.log():write(("netdemo: request configured -- Accept=%s timeout=%dms")
    :format(req:header("Accept"), req:timeout()))
end

-- A non-allowlisted host: the gate rejects it synchronously at call, so pcall catches the LuaError.
local function doBad()
  local ok, err = pcall(function() hafen.http():get("https://evil.example.org/steal", function() end) end)
  if ok then
    hafen.log():write("netdemo: UNEXPECTED -- the disallowed host was not rejected!")
  else
    hafen.log():write("netdemo: disallowed host correctly refused at call -- " .. tostring(err))
  end
end

-- An allowlisted host that resolves to a private IP (localtest.me -> 127.0.0.1): the request is ACCEPTED
-- at call (it's in the allowlist) but the resolved-IP block refuses it on the pool thread -> ok=false.
local function doLan()
  hafen.log():write("netdemo: GET http://localtest.me/  (resolves to 127.0.0.1 -- expect a blocked-address error)")
  hafen.http():get("http://localtest.me/", function(res)
    if res.ok then
      hafen.log():write(("netdemo: UNEXPECTED -- localtest.me returned HTTP %d (private IP should be blocked)"):format(res.status))
    else
      hafen.log():write("netdemo: private-IP host correctly refused -- " .. tostring(res.error))
    end
  end)
end

-- POST a Lua TABLE: hafen.http auto-encodes it to JSON (application/json) and sends it. httpbin.org/post
-- echoes the request back, including the JSON it parsed under res.json -- so we round-trip our own body.
local function doPost()
  local payload = { char = hafen.player():name() or "unknown", lp = 42, tags = { "a", "b" } }
  hafen.log():write("netdemo: POST https://httpbin.org/post  body=" .. hafen.json():encode(payload))
  hafen.http():post("https://httpbin.org/post", payload, function(res)
    if not res.ok then
      hafen.log():write("netdemo: POST FAILED -- " .. tostring(res.error))
      return
    end
    if res.status ~= 200 then
      hafen.log():write(("netdemo: POST HTTP %d"):format(res.status))
      return
    end
    local ok, data = pcall(function() return hafen.json():parse(res.body) end)
    if not ok then hafen.log():write("netdemo: POST reply parse error -- " .. tostring(data)); return end
    -- httpbin echoes what it received: data.json is our decoded body, data.headers the request headers.
    local echoed = data.json or {}
    hafen.log():write(("netdemo: POST echoed back char=%s lp=%s content-type=%s")
      :format(tostring(echoed.char), tostring(echoed.lp),
              tostring(data.headers and data.headers["Content-Type"])))
  end)
end

-- Follow a redirect to an ALLOWLISTED host: httpbin.org/redirect-to?url=https://httpbin.org/get sends a 302
-- whose Location is still httpbin.org (allowlisted) -> the hop is re-validated and followed -> ok.
local function doRedirect()
  local url = "https://httpbin.org/redirect-to?url=https%3A%2F%2Fhttpbin.org%2Fget&status_code=302"
  hafen.log():write("netdemo: GET " .. url .. "  (302 -> httpbin.org/get, an allowlisted hop -> expect ok)")
  hafen.http():get(url, function(res)
    if not res.ok then
      hafen.log():write("netdemo: redirect FAILED -- " .. tostring(res.error))
      return
    end
    hafen.log():write(("netdemo: redirect followed -> HTTP %d, %d bytes (landed on the allowlisted host)")
      :format(res.status, #res.body))
  end)
end

-- Follow a redirect that points OFF the allowlist: Location = https://example.com (NOT declared). The first
-- hop (httpbin.org) is allowed, but the redirect target's host is re-checked and refused -> ok=false.
local function doEscape()
  local url = "https://httpbin.org/redirect-to?url=https%3A%2F%2Fexample.com%2F&status_code=302"
  hafen.log():write("netdemo: GET " .. url .. "  (302 -> example.com, NOT allowlisted -> expect the hop to be blocked)")
  hafen.http():get(url, function(res)
    if res.ok then
      hafen.log():write(("netdemo: UNEXPECTED -- redirect to a non-allowlisted host was followed (HTTP %d)"):format(res.status))
    else
      hafen.log():write("netdemo: off-allowlist redirect correctly blocked mid-hop -- " .. tostring(res.error))
    end
  end)
end

hafen.slash():register("netdemo", function(args)
  local sub = args[1] or "get"
  if     sub == "get" or sub == "" then doGet()
  elseif sub == "post"             then doPost()
  elseif sub == "redirect"         then doRedirect()
  elseif sub == "escape"           then doEscape()
  elseif sub == "bad"              then doBad()
  elseif sub == "lan"              then doLan()
  else
    hafen.log():write(":netdemo sub-commands -> get | post | redirect | escape | bad | lan")
    hafen.log():write("   get = GET api.github.com + json.parse    post = POST a JSON table to httpbin.org (echo)")
    hafen.log():write("   redirect = follow a 3xx to an allowlisted host    escape = redirect off the allowlist (blocked)")
    hafen.log():write("   bad = non-allowlisted host (refused at call)    lan = private-IP host (refused by the IP block)")
  end
end)
