-- 127.5 -- the host the allowlist is asked about is a strictly parsed one. Suite: run with :t127.
--
-- hafen.http():request(url) parses the url and keeps its host, and that host is the one the gate measures
-- against the consent record. So a lenient parse there is a lenient parse of the whole gate: a url whose
-- authority the client and the server read differently is a url whose approved host was a guess. The parse
-- is RFC 3986, and a space, an unescaped brace, a malformed percent escape and their kind are refused where
-- they were written rather than carried to a pool thread with nobody left to tell.
--
-- Every check below lands on :request(url), which is where the parse is and which reaches no network: a url
-- refused there never reaches :send(), and the two that do build are never sent. This suite therefore
-- declares no permission and no host, and nothing leaves the machine.
--
-- The two parse sites inside the transport -- the entry, and a redirect's relative Location resolved against
-- the current url -- are the same change behind this one. A relative Location is something no offline suite
-- can make a server send, so they are verified by reading them.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function strip(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check twice over: the call must fail, and the message must name the url it failed on --
-- a refusal that reads "malformed url" and nothing else leaves the author guessing which of their urls it is.
local function refusesNaming(what, url)
  local ok, err = pcall(function() return hafen.http():request(url) end)
  local msg = ok and "<no error>" or strip(err)
  check((not ok) and (msg:find(url, 1, true) ~= nil), what .. " is refused at the call, naming the url", msg)
end

-- Every one of these was accepted before, and the host handed on was whatever the lenient parse guessed.
local lenient = {
  { "a space in the path",         "https://api.example.com/a b" },
  { "an unescaped brace",          "https://api.example.com/{id}" },
  { "a malformed percent escape",  "https://api.example.com/%zz" },
  { "an unescaped pipe",           "https://api.example.com/a|b" },
  { "an unescaped caret",          "https://api.example.com/a^b" },
}

local function run()
  for _, c in ipairs(lenient) do
    refusesNaming(c[1], c[2])
  end

  -- The other direction, and it is the half that separates a stricter parse from a broken one: an ordinary
  -- url -- query and fragment included, both of which RFC 3986 has -- still builds, and the request carries
  -- it back verbatim.
  local ordinary = "https://api.example.com/prices?q=1#top"
  local ok, req = pcall(function() return hafen.http():request(ordinary) end)
  check(ok and (req:url() == ordinary), "an ordinary https url still builds, and the request carries it",
        ok and req:url() or strip(req))

  -- The scheme is checked at the same door, on what the same parse produced: this is what keeps a url off
  -- the local disk, and it answers with the rule rather than with a Java error from somewhere downstream.
  local fok, ferr = pcall(function() return hafen.http():request("file:///etc/passwd") end)
  local fmsg = fok and "<no error>" or strip(ferr)
  check(not fok, "a file: url is refused at the call", fmsg)
  check((not fok) and (fmsg:find("http or https", 1, true) ~= nil),
        "...saying the scheme must be http or https", fmsg)

  -- Nothing here was sent, so nothing here holds a slot -- and nothing resolved a name or opened a socket.
  check(hafen.http():count() == 0, "this suite leaves nothing in flight", hafen.http():count())

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t127", run)   -- the only way in: a suite does not start itself
