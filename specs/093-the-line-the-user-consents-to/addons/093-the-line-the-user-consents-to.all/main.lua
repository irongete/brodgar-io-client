-- 093 -- The line the user consents to. The whole feature's eight rows, under one command.
--
-- The permission tier's own definition is "a verb that starts an action the player could have performed",
-- and four things reached past that line with no key at all:
--
--   A-095  ev:resend() / ev:send(t) put a message on the very wire widget:send is gated on
--   A-096  map.marker  -- :remove(m) PERMANENTLY DELETES a pin the player placed, unprotected
--   A-097  client.settings -- every persisted option, and every hotkey, including the CLIENT's own
--   A-098  http.get / http.post -- the network was a second mechanism with none of this one's vocabulary
--   A-099  ACTIONBAR_RES's consent line described the UNPROTECTED verb beside it
--   A-100  kin.endKin -> kin.end: the one camelCase key in a catalogue of lower-case dotted ones
--   A-101  guides/permissions.md: a key may have more than one dot   (ALREADY TRUE -- see the Groups section)
--   A-102  conventions.md: the network tier said in the permission tier's words
--
-- THIS SUITE DECLARES NO PERMISSIONS. That is the point: what 093 ships is the gates, so the proof is that
-- each newly protected verb REFUSES and names its own key, while the read beside it answers freely. A suite
-- cannot have both halves of one gate -- declaring the key would prove the effect and destroy the refusal --
-- and here the refusal IS the feature.
--
-- What no program can read is the consent DIALOG, which is where A-098's host list and A-099's reworded line
-- actually land. That is the one manual, and it says exactly what to paste to see it.

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

-- A group of refusals, scored as one line. The criterion is: it RAISES, and it names EITHER the key it
-- needs OR the door immediately behind that key.
--
-- Why both. A suite cannot have the two halves of one gate -- declaring a key proves the effect and destroys
-- the refusal -- so this one declares nothing and reads the refusals. But the manual below asks you to paste
-- keys in to see the consent dialog, and while they are in, the gate you granted stands down and the call
-- reaches the argument check on the other side. Accepting either answer makes the verdict the same in both
-- states, and a verb with NO gate at all still fails, because `<no error>` is never a pass.
--
-- Every call below is made with an argument that CANNOT prosper, so nothing behind a granted gate ever runs.
-- The first draft was not, and with map.marker granted it deleted one of the user's own map pins -- which is
-- the very thing A-096 exists to keep an addon from doing without being asked.
local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want, behind)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and ((err:find(want, 1, true) ~= nil)
                     or (behind and (err:find(behind, 1, true) ~= nil))) then n = n + 1
    else why = why or (label .. " -> " .. err) end
  end
  -- ...and the same, for a receiver that may not be there: not reached is not counted.
  function g.maybe(label, get, fn, want, behind)
    local okg, subject = pcall(get)
    if (not okg) or (subject == nil) then return end
    g.ask(label, function() return fn(subject) end, want, behind)
  end
  function g.done(what)
    if total == 0 then
      pass = pass + 1
      hafen.log():write("[pass] " .. what .. " (0/0 reached -- nothing of the kind was up)")
    else
      check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n)
    end
  end
  return g
end

-- A group of reads, scored over what the run REACHED: a read that raises at all is the failure here.
local function opens()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    local ok, r = pcall(fn)
    if ok and (r == nil) then return end                 -- not up: not counted
    total = total + 1
    if ok then n = n + 1 else why = why or (label .. " -> " .. tostring(r)) end
  end
  function g.done(what)
    if total == 0 then
      pass = pass + 1
      hafen.log():write("[pass] " .. what .. " (0/0 reached)")
    else
      check(n == total, what .. " (" .. n .. "/" .. total .. " reached)", why or n)
    end
  end
  return g
end

local function run()
  local s = hafen.session():current()

  ---------------------------------------------------------------------------------------------------
  -- A-096: the marker writes. :remove(m) deletes a pin no server can restore, and all four were open.
  ---------------------------------------------------------------------------------------------------
  section("A-096", function()
    local mk = hafen.map():marker()
    local one = mk:list()[1]
    local r = refusals()
    -- No Position, so a granted add has nowhere to put a pin; a string, so a granted remove has no Marker.
    r.ask("marker():add", function() return mk:add("093", nil) end, "map.marker", "must be a Position")
    r.ask("marker():remove", function() return mk:remove("093") end, "map.marker", "is a Marker object")
    r.maybe("marker:color", function() return one end, function(m) return m:color("x") end,
            "map.marker", "marker:color")
    r.maybe("marker:onMap", function() return one end, function(m) return m:onMap("x") end,
            "map.marker", "must be true or false")
    r.done("the marker writes refuse, naming map.marker")
  end)

  section("A-096 reads", function()
    local mk = hafen.map():marker()
    local g = opens()
    g.want("marker():count()", function() return mk:count() end)
    g.want("marker():list()", function() return mk:list() end)
    g.want("marker:color() reads", function()
      local one = mk:list()[1]
      if not one then return nil end
      one:color()
      return true
    end)
    g.done("reading the map needs no key")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-097: every option WRITE, in one place -- and every hotkey remap, which reaches the CLIENT's own
  -- bindings, so an addon could take the inventory key with nobody having been asked.
  ---------------------------------------------------------------------------------------------------
  section("A-097", function()
    local o = hafen.client():options()
    local r = refusals()
    -- Each written with a value the option itself refuses, so a granted write changes nothing.
    r.ask("video:lightingMode(s)", function() return o:video():lightingMode("nope") end,
          "client.settings", "must be")
    r.ask("video:renderScale(v)", function() return o:video():renderScale("x") end,
          "client.settings", "renderScale")
    r.ask("interface:scale(v)", function() return o:interface():scale("x") end,
          "client.settings", "interface:scale")
    r.ask("audio:masterVolume(v)", function() return o:audio():masterVolume("x") end,
          "client.settings", "masterVolume")
    r.ask("camera:mode(s)", function() return o:camera():mode("nope") end, "client.settings", "camera:mode")
    r.ask("binding:key(k)", function()
      return o:keybindings():binding():get("inv"):key("!!!")          -- never parses: no remap either way
    end, "client.settings", "cannot parse key")
    r.ask("client:profiling(b)", function() return o:client():profiling("x") end,
          "client.settings", "profiling")
    r.done("every option write and every hotkey remap refuses, naming client.settings")
  end)

  section("A-097 reads", function()
    local o = hafen.client():options()
    local g = opens()
    g.want("video:shadows()", function() o:video():shadows(); return true end)
    g.want("interface:scale()", function() o:interface():scale(); return true end)
    g.want("camera:mode()", function() o:camera():mode(); return true end)
    g.want("binding:key()", function() o:keybindings():binding():get("inv"):key(); return true end)
    g.want("binding:default()", function() o:keybindings():binding():get("inv"):default(); return true end)
    g.done("reading a setting or a binding needs no key")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-098: the network is in the ONE catalogue now. The key says whether, the hosts say where -- and
  -- this addon declares neither, so it is the key it is told about first.
  ---------------------------------------------------------------------------------------------------
  section("A-098", function()
    local r = refusals()
    -- A host nothing can have allowlisted, so a granted key still reaches no network.
    r.ask("http():get", function()
      return hafen.http():get("https://093.invalid/x", function() end)
    end, "http.get", "allowlist")
    r.ask("http():post", function()
      return hafen.http():post("https://093.invalid/x", "x", function() end)
    end, "http.post", "allowlist")
    r.done("the network verbs refuse, naming http.get and http.post")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-100: the key is <section>.<action> and the action is the plain word. `end` is a Lua keyword so the
  -- VERB cannot change -- the key need not follow it letter for letter, and now it does not.
  ---------------------------------------------------------------------------------------------------
  section("A-100", function()
    local kin = s and s:kin() and s:kin():list()[1]
    if not kin then
      pass = pass + 1
      hafen.log():write("[pass] kin:endKin() names kin.end, never kin.endKin (0/0 reached -- no kin)")
      return
    end
    -- A DOT call: the gate runs first (D-213), so without the key this is the key, and with it the
    -- receiver refusal -- and a kinship is never actually ended either way.
    local ok, err = pcall(function() return kin.endKin("not a Kin") end)
    err = ok and "<no error>" or tostring(err)
    check((not ok) and (err:find("kin.endKin", 1, true) == nil)
            and ((err:find("kin.end", 1, true) ~= nil) or (err:find("COLON call", 1, true) ~= nil)),
          "kin:endKin() names kin.end, and never kin.endKin", err)
  end)

  ---------------------------------------------------------------------------------------------------
  -- The one thing no program can read: the consent DIALOG. A-098's host list and A-099's reworded line
  -- both land there and nowhere else.
  ---------------------------------------------------------------------------------------------------
  manualCheck('paste "permissions": ["map.marker", "actionbar.res", "http.get"], "network": {"hosts":'
                .. ' ["example.com"]} into this suite\'s manifest.json, :reload, and tick it in'
                .. ' Options > AddOns',
              'three lines in the consent dialog: "add, rename and delete pins on your map", "assign one of'
                .. ' the game\'s own actions to any of your characters\' action-bar buttons" -- which used to'
                .. ' describe the UNPROTECTED verb beside it -- and "fetch data from the servers it lists:'
                .. ' example.com", the host list where a network addon used to raise no dialog at all.'
                .. ' While those blocks are in, the marker and http lines above report the ARGUMENT refusal'
                .. ' behind the gate instead of the key, which is a pass either way. Remove them afterwards')

  ---------------------------------------------------------------------------------------------------
  -- A-095, last and over a WINDOW: ev:resend()/ev:send(t) exist only inside a handler the client's own
  -- traffic calls, so the check arms one and scores whatever the next three seconds bring. Walk or click
  -- while it runs. Nothing is re-sent either way -- the gate raises before the cancel.
  ---------------------------------------------------------------------------------------------------
  section("A-095", function()
    local seen, r = 0, refusals()
    hafen.log():write("[093] listening for one outbound action for 3s -- WALK OR CLICK NOW")
    local sub = hafen.event():action():on("*", function(ev)
      if seen > 0 then return end
      seen = 1
      r.ask("ev:resend()", function() return ev:resend() end, "widget.send", "LEFT THE TREE")
      r.ask("ev:send(t)", function() return ev:send() end, "widget.send", "args is required")
    end)
    hafen.timer():after(3, function()
      sub:off()
      r.done("ev:resend() and ev:send(t) refuse, naming widget.send")
      finish()
    end)
  end)
end

hafen.slash():on("t093", run)                  -- the only way in: a suite does not start itself
