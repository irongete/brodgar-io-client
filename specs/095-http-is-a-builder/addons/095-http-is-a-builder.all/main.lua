-- 095 -- http is a builder. The whole feature's four rows, under one command. The last of the sweep.
--
--   A-115  hafen.http():request(url) ... :send() -- construction and dispatch are two calls
--   A-116  the completion handler is :on("done", fn), not a positional argument
--   A-117  res becomes an object: :ok() :status() :body() :header(name) :error()
--   A-118  hafen.http() is the collection of this addon's live requests, with req:url() and :method()
--
-- WHY THE SHAPE WAS WRONG. CLAUDE.md: "a builder is constructed bare and configured by chained setters",
-- and HttpApi's own javadoc claimed compliance. It was not: hafen.http():get(url, cb) took the URL AND the
-- handler positionally, and the request was already scheduled -- it went out on the next tick -- before the
-- first setter ran. So every setter carried a lifetime rule no other builder in the API has: chain it in
-- the same statement or be refused. :send() removes the rule rather than restating it.
--
-- This suite DECLARES http.get and one host, because the only honest proof of a builder is to build one
-- and send it. Nothing is sent to a real service: example.com is the IANA reserved documentation domain,
-- and one GET of it is the whole of the network this suite touches.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local finished = false
local function finish()
  if finished then return end
  finished = true
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and (err:find(want, 1, true) ~= nil) then n = n + 1
    else why = why or (label .. " -> " .. err) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n) end
  return g
end

local function scored()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    local ok, r = pcall(fn)
    if ok and (r == nil) then return end
    total = total + 1
    if ok and r then n = n + 1 else why = why or (label .. " -> " .. tostring(r)) end
  end
  function g.done(what)
    check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n)
  end
  return g
end

local URL = "https://example.com/"

local function run()
  ---------------------------------------------------------------------------------------------------
  -- A-115: BARE. Nothing is sent, so every setter is legal -- which is the rule :send() replaced the
  -- next-tick one with. The read arity of each setter proves the write landed.
  ---------------------------------------------------------------------------------------------------
  section("A-115", function()
    local g = scored()
    local req = hafen.http():request(URL)
    g.want("request(url) hands back a Request", function() return tostring(req):find("Request(", 1, true) ~= nil end)
    g.want("it has NOT been sent", function() return tostring(req):find("sent", 1, true) == nil end)
    g.want("every setter chains and returns the request", function()
      return (req:method("POST") == req) and (req:timeout(4000) == req)
        and (req:header("X-094", "v") == req) and (req:body("x") == req)
    end)
    g.want("and every one of them reads back", function()
      return (req:method() == "POST") and (req:timeout() == 4000)
        and (req:header("x-094") == "v")                 -- case-insensitive, like the wire
        and (req:body() == "x") and (req:url() == URL)
    end)
    g.want("a request never sent is in no collection", function()
      return hafen.http():find(URL) == nil
    end)
    req:cancel()
    g.done("a request is built bare, and every setter is legal until it goes")
  end)

  section("A-115 refusals", function()
    local sent = hafen.http():request(URL)
    sent:send()
    local r = refusals()
    r.ask("a setter after :send()", function() return sent:timeout(1000) end, "already been sent")
    r.ask("a second :send()", function() return sent:send() end, "already been sent")
    r.ask("request(nil)", function() return hafen.http():request() end, "url is required")
    r.ask("request(not a url)", function() return hafen.http():request("ftp://x/") end, "http")
    r.ask("method(garbage)", function() return hafen.http():request(URL):method("PATCH") end, "GET and POST")
    sent:cancel()
    r.done("a sent request refuses every setter, and a bad build refuses at the call")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-116: the handler is the API's ONE notification verb, and the positional form is gone loudly. It
  -- is a reshape, so Retired cannot key on it -- the refusal is written inside the verb instead.
  ---------------------------------------------------------------------------------------------------
  section("A-116", function()
    local g, r = scored(), refusals()
    g.want("on(\"done\", fn) hands back a Sub that ends", function()
      local req = hafen.http():request(URL)
      local sub = req:on("done", function() end)
      local ok = (sub ~= nil) and (sub:key() == "done")
      sub:off()
      req:cancel()
      return ok
    end)
    g.done("the handler is a subscription")
    r.ask("get(url, cb)", function()
      return hafen.http():get(URL, function() end)
    end, "takes no callback")
    r.ask("post(url, body, cb)", function()
      return hafen.http():post(URL, "x", function() end)
    end, "takes no callback")
    r.ask("on(\"nope\", fn)", function()
      return hafen.http():request(URL):on("nope", function() end)
    end, "has no event")
    r.done("the positional callback is refused, naming the whole new shape")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-118: the collection of what is in flight -- the one bounded set in the API you could not look at.
  ---------------------------------------------------------------------------------------------------
  section("A-118", function()
    local g = scored()
    local a = hafen.http():request(URL):send()
    g.want("hafen.http() is a collection", function()
      local h = hafen.http()
      local okc, n = pcall(function() return h:count() end)
      return okc and (type(n) == "number") and (#h:list() == n) and not pcall(function() return #h end)
    end)
    g.want("a sent request is IN it, and :find matches on the url", function()
      return (hafen.http():count() >= 1) and (hafen.http():find("example.com") ~= nil)
    end)
    g.want(":get is the CONVENIENCE here, not the collection's addressing", function()
      -- A request has no key, so :find(url) is the search; the section's own :get(url) builds one instead,
      -- which is the ":get" collision the audit's naming census already catalogues (C26).
      local b = hafen.http():get(URL)
      local ok = (b:url() == URL) and (b:method() == "GET") and (hafen.http():find(URL) ~= b)
      b:cancel()
      return ok
    end)
    g.want("cancelling takes it out", function()
      a:cancel()
      return hafen.http():find("example.com") == nil
    end)
    g.done("the requests in flight are a collection")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-117 + the whole round trip, LAST and asynchronous: one GET of example.com, the IANA reserved
  -- documentation domain, which is the only host this suite's manifest allows.
  ---------------------------------------------------------------------------------------------------
  section("A-117", function()
    local req = hafen.http():request(URL):timeout(8000)
    req:on("done", function(res)
      local g = scored()
      g.want("res:ok() answers", function() return type(res:ok()) == "boolean" end)
      if res:ok() then
        g.want("res:status() is a number", function() return type(res:status()) == "number" end)
        g.want("res:body() is a string", function() return type(res:body()) == "string" end)
        g.want("res:header(name) matches case-insensitively", function()
          local a, b = res:header("Content-Type"), res:header("content-type")
          return (a == b) and ((a == nil) or (type(a) == "string"))
        end)
        g.want("res:error() is nil when it completed", function() return res:error() == nil end)
      else
        g.want("res:error() says why, and the reads are nil", function()
          return (type(res:error()) == "string") and (res:status() == nil) and (res:body() == nil)
        end)
      end
      g.want("a field read hands back the METHOD, so res.ok is never a boolean", function()
        return type(res.ok) == "function"                -- the closed-type property, said out loud
      end)
      g.done("the result is an object" .. (res:ok() and "" or " (the exchange did not complete)"))
      finish()
    end)
    req:send()
    hafen.timer():after(10, function()
      if finished then return end
      check(false, "the result is an object", "no reply from " .. URL .. " within ten seconds")
      finish()
    end)
  end)

  manualCheck("check that nothing of yours went anywhere: the only host this suite's manifest allows is"
                .. " example.com, the IANA reserved documentation domain, and it sends one GET of it",
              "no other traffic. The AddOns panel row for this suite shows [net] with example.com as the"
                .. " whole allowlist, and the consent dialog said \"fetch data from the servers it lists:"
                .. " example.com\" when you enabled it")
end

hafen.slash():on("t095", run)                  -- the only way in: a suite does not start itself
