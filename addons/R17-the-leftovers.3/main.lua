-- R17.3 -- the action bar, the console and the client's own panels: the cases ab-16, co-12 and cl-20 say
-- no suite ever covered. It runs only from :tR17-3, sends nothing on the wire, and puts back everything
-- it touches: a slot it holds is released, an entry it adds is removed, a command it claims is ended.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level error
-- adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both: the class is
-- [^\n] rather than . so a traceback under the message cannot be eaten as a third prefix.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?[^\n]-%.lua:%d+:?[ \t]*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

-- Did fn refuse, and did it say why? Answers nil when it did, and what went wrong when it did not.
local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

local function allRefuse(name, cases, also, got)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, (bad == nil) and (also ~= false), bad or got)
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

local function needs(action)
  manual = manual + 1
  say("[manual] " .. action .. ", then type :tR17-3 -- expect: this line becomes a [pass]")
end

-- ---- ab-16: the bar ----------------------------------------------------------------------------------

local SLOT = 144          -- the last slot of the last page: a hold there is over nothing the player sees

-- Everything the server has in a slot, so a released hold can be shown to have handed it all back.
local function content(sl)
  return tostring(read(function() return sl:res() end)) .. "/"
         .. tostring(read(function() return sl:name() end)) .. "/"
         .. tostring(read(function() return sl:empty() end))
end

-- A hold is client-local and immediate, and it fires ActionbarChanged on both of its edges -- which is the
-- same event a server write to the bar fires, and the only one a suite may cause: an assignment or a clear
-- is kept by the server and would outlive the run.
local function holding(s, tag, edges, label, done)
  local sl = read(function() return s:actionbar():get(SLOT) end)
  local pag = read(function() return s:menugrid():add(tag) end)
  if (sl == nil) or (pag == nil) then
    ok(label, false, "no bar or no action menu yet on that character")
    done()
    return
  end
  local before, n0 = content(sl), edges()
  local held = read(function() return sl:hold(pag):hold() end)
  local resHeld = read(function() return sl:res() end)
  local emptyHeld = read(function() return sl:empty() end)
  -- ONE TICK BETWEEN THE EDGES, and it is not politeness. The change detection diffs the bar against a
  -- per-index cache when the tick drains the belt-set queue, so a take and a release inside one frame are
  -- one no-op rather than two edges: the slot the drain looks at is already back to what it was. The take
  -- is given its own tick, and so is the release.
  hafen.timer():after(0.4, function()
    local n1 = edges()
    read(function() return sl:hold(nil) end)
    hafen.timer():after(0.4, function()
      local after, n2 = content(sl), edges()
      read(function() return s:menugrid():remove(pag) end)
      ok(label,
         (held == pag) and (emptyHeld == false) and (resHeld == read(function() return pag:res() end))
           and (after == before) and ((n1 - n0) >= 1) and ((n2 - n1) >= 1),
         (n1 - n0) .. " taking edge, " .. (n2 - n1) .. " releasing edge, held as " .. tostring(resHeld)
           .. ", slot " .. before .. " -> " .. after)
      done()
    end)
  end)
end

-- ab-16: an entry an addon adds mints a remappable binding of its own, and :remove takes it back out of
-- the client's registry -- the key a custom entry claims, and the release of it.
local function entryKey(s, keys)
  local pag = read(function() return s:menugrid():add("r17key") end)
  if pag == nil then
    ok("a custom entry's own binding leaves the registry with the entry", false, "no action menu yet")
    return
  end
  local id = "scm/" .. tostring(read(function() return pag:res() end))
  local minted = read(function() return keys:binding():get(id):exists() end)
  read(function() return s:menugrid():remove(pag) end)
  ok("a custom entry's own binding leaves the registry with the entry",
     (read(function() return pag:exists() end) == false)
       and (read(function() return keys:binding():get(id):exists() end) == false),
     id .. (minted and " was minted" or " was not minted before the removal"))
end

-- ---- co-12: the console ------------------------------------------------------------------------------

local function console()
  -- The collision half: a name the CLIENT declares can never be claimed, whether or not a HUD is up --
  -- these three are the console's own static commands, declared before any session exists.
  allRefuse("a name the client or the engine owns is refused, and so is one that is not a word", {
    {"on gc",       function() return hafen.console():on("gc", function() end) end,
                                                          "already a client command"},
    {"on die",      function() return hafen.console():on("die", function() end) end,
                                                          "already a client command"},
    {"on threads",  function() return hafen.console():on("threads", function() end) end,
                                                          "already a client command"},
    {"on lua",      function() return hafen.console():on("lua", function() end) end,
                                                          "reserved engine command"},
    {"on reload",   function() return hafen.console():on("reload", function() end) end,
                                                          "reserved engine command"},
    {"on ''",       function() return hafen.console():on("", function() end) end,
                                                          "non-empty word with no spaces"},
    {"on 'two w'",  function() return hafen.console():on("two w", function() end) end,
                                                          "non-empty word with no spaces"},
  })
  -- The takeover half, within one addon: the second registration under a name IS the command, and the
  -- first ends the way :off() ends one. Then the ending itself: the name leaves the collection.
  local first = read(function() return hafen.console():on("r17probe", function() end) end)
  local second = read(function() return hafen.console():on("r17probe", function() end) end)
  local mine = read(function() return hafen.console():get("r17probe") end)
  local n = read(function() return hafen.console():count("r17probe") end)
  if second ~= nil then read(function() return second:off() end) end
  ok("a second claim on one name takes it and ends the first, and the ending clears the name",
     (first ~= nil) and (second ~= nil) and (mine == second) and (n == 1)
       and (tostring(first):find("off)", 1, true) ~= nil)
       and (read(function() return hafen.console():get("r17probe") end) == nil)
       and (read(function() return hafen.console():count("r17probe") end) == 0),
     tostring(first) .. " then " .. tostring(second) .. ", " .. tostring(n) .. " under the name")
end

-- ---- cl-20: the client's own panels ------------------------------------------------------------------

local function panels(opts)
  -- The five panels of the Options window, each answering one read of the type its page documents.
  local got = {}
  local good = true
  local rows = {
    {"interface", function() return opts:interface():scale() end, "number"},
    {"video",     function() return opts:video():vsync() end,     "boolean"},
    {"audio",     function() return opts:audio():masterVolume() end, "number"},
    {"camera",    function() return opts:camera():mode() end,     "string"},
    {"client",    function() return opts:client():recall() end,   "boolean"},
  }
  for _, r in ipairs(rows) do
    local v = read(r[2])
    got[#got + 1] = r[1] .. "=" .. tostring(v)
    good = good and (type(v) == r[3])
    -- ...and the same panel is the same object every call, which is what makes a handle worth keeping.
    good = good and (read(function() return opts[r[1]](opts) end) == read(function() return opts[r[1]](opts) end))
  end
  ok("the five client panels each answer a read of the type their page names, and intern",
     good, table.concat(got, " "))
end

local function remapGate(keys, opts)
  local b = read(function() return keys:binding():get("inv") end)
  if b == nil then
    ok("a write to a client setting refuses without client.settings, and a read never does", false,
       "no binding registry")
    return
  end
  -- Every write here is refused before it lands: this suite declares no key, so the gate is what answers.
  -- The reads beside them are unprotected and answer, which is the half the refusal must not swallow.
  allRefuse("a write to a client setting refuses without client.settings, and a read never does", {
    {"binding:key",  function() return b:key("Ctrl+F9") end,        "client.settings"},
    {"binding:key(nil)", function() return b:key(nil) end,          "client.settings"},
    {"interface:scale", function() return opts:interface():scale(1.5) end, "client.settings"},
    {"video:vsync",  function() return opts:video():vsync(true) end, "client.settings"},
  }, (read(function() return b:id() end) == "inv")
       and (type(read(function() return b:assigned() end)) == "boolean"),
     "inv reads " .. tostring(read(function() return b:key() end)))
end

local function level(keys)
  -- down() is the LEVEL rather than the edge, and a hotkey of an addon's own starts unbound -- so it is
  -- false here, and its being a boolean at all is what the row says nothing asserts.
  local hot = read(function() return keys:on("r17level", function() end) end)
  local b = read(function() return keys:binding():get("r17level") end)
  local id = read(function() return b:id() end)
  local down = read(function() return b:down() end)
  local i = read(function() return b:info() end)
  if hot ~= nil then read(function() return hot:off() end) end
  ok("a hotkey of an addon's own is in the registry under its addon, unbound, and reads its key's level",
     (hot ~= nil) and (id == "addon/R17-the-leftovers.3/r17level") and (down == false)
       and (read(function() return b:exists() end) == true)
       and (read(function() return b:key() end) == nil)
       and (type(i) == "table") and (i.id == id) and (i.down == false),
     tostring(id) .. ", down " .. tostring(down))
end

-- ---- the run -----------------------------------------------------------------------------------------

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function run()
  local cur = read(function() return hafen.session():current() end)
  if cur == nil then
    fail = fail + 1
    say("[fail] no character is on screen -- log in and run :tR17-3 again")
    finish()
    return
  end
  local opts = hafen.client():options()
  local keys = opts:keybindings()

  -- Every ActionbarChanged, and which of them this run caused: the rest are the server's own, which is
  -- the edge the row names and the one a suite must not make for itself.
  local mine, theirs = 0, 0
  local watch = hafen.event():on("ActionbarChanged", function(sl)
    if read(function() return sl:index() end) == SLOT then mine = mine + 1 else theirs = theirs + 1 end
  end)
  local function edges() return mine end

  local alt
  for _, s in ipairs(read(function() return hafen.session():list() end) or {}) do
    if (s ~= cur) and (read(function() return s:character() end) ~= nil) then alt = alt or s end
  end

  local function afterBoth()
    watch:off()
    if theirs > 0 then
      pass = pass + 1
      say("[pass] the bar reports the server's own edges too -- " .. theirs .. " arrived unasked")
    end
    entryKey(cur, keys)
    console()
    panels(opts)
    remapGate(keys, opts)
    level(keys)
    finish()
  end

  local function background()
    if alt == nil then
      needs("log a second character into the world (the hold on a bar nobody is looking at)")
      afterBoth()
      return
    end
    holding(alt, "r17bg", edges, "a slot of a bar nobody is looking at holds and releases the same way",
            afterBoth)
  end

  holding(cur, "r17hold", edges, "a hold takes a slot, reads as the entry, fires both edges and gives the"
          .. " slot back", background)
end

hafen.console():on("tR17-3", function()
  pass, fail, manual = 0, 0, 0
  -- The typed command runs under the console tree's monitor, so the run is deferred to the step exactly as
  -- every suite that reads or writes a widget tree is.
  hafen.timer():after(0, run)
end)
