-- NetDemo — the hafen.http + hafen.json demo addon (N2a).
--
-- It DECLARES a "network" block in its manifest, so it may reach ONLY the hosts listed there
-- (api.github.com, localtest.me) — the declaration IS the allowlist (D-037). A network addon loads
-- normally (unlike a write/"actions" addon, which is disabled by default); the AddOns panel shows a
-- [net] badge and the exact hosts so you see which servers it talks to BEFORE enabling it.
--
-- hafen.http.* is ASYNC by necessity (a blocking request can't run on the UI thread): every call returns
-- immediately with a { :cancel() } handle and delivers the result later via cb(res) on the tick. The res
-- table: res.ok (true = a reply arrived, ANY status incl. 4xx/5xx; false = a transport failure), and then
-- res.status / res.body / res.headers when ok, or res.error when not.
--
-- Nothing hits the network at login. Each request is a deliberate ':netdemo <sub>' trigger:
--   :netdemo        (or :netdemo get)  GET api.github.com + hafen.json.parse the JSON reply
--   :netdemo bad    try a NON-allowlisted host -> rejected SYNCHRONOUSLY at call (pcall'd, logged)
--   :netdemo lan    GET localtest.me, which resolves to 127.0.0.1 -> refused by the private-IP block (ok=false)

hafen.log("netdemo loaded (v0.1.0) -- hafen.http + hafen.json demo. Run  :netdemo  for the request demos.")

hafen.events.on("OnLoad", function()
  hafen.log("netdemo: allowlisted hosts = api.github.com, localtest.me (declared in manifest.json)")
end)

-- A small, stable public JSON API: the upstream hafen-client repo metadata.
local GH = "https://api.github.com/repos/dolda2000/hafen-client"

local function doGet()
  hafen.log("netdemo: GET " .. GH .. " ...")
  hafen.http.get(GH, function(res)
    if not res.ok then
      hafen.log("netdemo: request FAILED -- " .. tostring(res.error))
      return
    end
    hafen.log(("netdemo: HTTP %d, %d bytes, content-type=%s")
      :format(res.status, #res.body, tostring(res.headers["content-type"])))
    if res.status ~= 200 then return end
    local ok, data = pcall(hafen.json.parse, res.body)
    if not ok then
      hafen.log("netdemo: JSON parse error -- " .. tostring(data))
      return
    end
    -- data is a Lua table: pull a couple of fields out of the parsed JSON.
    hafen.log(("netdemo: repo=%s  stars=%s  forks=%s  lang=%s")
      :format(tostring(data.full_name), tostring(data.stargazers_count),
              tostring(data.forks_count), tostring(data.language)))
  end)
end

-- A non-allowlisted host: the gate rejects it synchronously at call, so pcall catches the LuaError.
local function doBad()
  local ok, err = pcall(hafen.http.get, "https://evil.example.org/steal", function() end)
  if ok then
    hafen.log("netdemo: UNEXPECTED -- the disallowed host was not rejected!")
  else
    hafen.log("netdemo: disallowed host correctly refused at call -- " .. tostring(err))
  end
end

-- An allowlisted host that resolves to a private IP (localtest.me -> 127.0.0.1): the request is ACCEPTED
-- at call (it's in the allowlist) but the resolved-IP block refuses it on the pool thread -> ok=false.
local function doLan()
  hafen.log("netdemo: GET http://localtest.me/  (resolves to 127.0.0.1 -- expect a blocked-address error)")
  hafen.http.get("http://localtest.me/", function(res)
    if res.ok then
      hafen.log(("netdemo: UNEXPECTED -- localtest.me returned HTTP %d (private IP should be blocked)"):format(res.status))
    else
      hafen.log("netdemo: private-IP host correctly refused -- " .. tostring(res.error))
    end
  end)
end

hafen.slash.register("netdemo", function(args)
  local sub = args[1] or "get"
  if     sub == "get" or sub == "" then doGet()
  elseif sub == "bad"              then doBad()
  elseif sub == "lan"              then doLan()
  else
    hafen.log(":netdemo sub-commands -> get | bad | lan")
    hafen.log("   get = GET api.github.com + json.parse   bad = non-allowlisted host (refused at call)")
    hafen.log("   lan = allowlisted host resolving to 127.0.0.1 (refused by the private-IP block)")
  end
end)
