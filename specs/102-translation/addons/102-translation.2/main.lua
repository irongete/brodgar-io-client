-- 102.2 -- the client's own text sites. Self-checking suite.
--
-- WHAT THIS SHIPS. Every surface a catalogue names now DECLARES its own key at the render: a window
-- caption under `window.title`, a section heading under `heading`, a widget's tooltip under `tooltip`, a
-- petal under `menu`, a chat line under `chat` (or the key of its own kind), a speech bubble under
-- `world.speech` and a floating name under `world.nick`. A text entry declares `textentry`, which is the
-- one key the display seam refuses outright -- so what the user types is not matched even by an entry
-- written under "*".
--
-- HOW A SURFACE IS PROVED. A catalogue lands at the render and nowhere above it, so nothing in Lua can
-- SEE a translation; what it can see is what MISSED. A pair reaching :miss() under the right key is
-- therefore the whole proof that the site declared that key, and a pair GONE under a catalogue naming it
-- is the proof the lookup ran there. The two surfaces this suite can drive on its own -- a window it
-- builds and a line it logs -- are proved both ways. The four a program can neither hover, right-click,
-- open nor speak are DRIVEN BY THE MAINTAINER and polled for a bounded window: what they produced is
-- fully observable even though the suite could not cause it.
--
-- WHAT THE FIELD PROVES. It is filled under a catalogue that names nothing, and the assertion is that its
-- text reaches NO surface at all -- not merely that it went unmatched. A field rendering under the
-- fallback key would have recorded a `default` pair, and an entry under "*" would then have rewritten a
-- word as it was typed.

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

local WIN   = "Brodgar-title"     -- a window caption, drawn at window.title
local SYS   = "Brodgar-line"      -- ...a line in the System log, drawn at chat.system
local TIP   = "Brodgar-tip"       -- ...a widget's tooltip, drawn at tooltip
local TYPED = "Brodgar-typed"     -- ...and what goes into a field, which is drawn at NONE of them
local ES    = "-es"               -- what a catalogue says to draw instead

local POLL, WINDOW = 1, 60        -- the driven phase: one sweep a second, for a minute

-- The first miss recorded at `surface`, or nil. A miss is an object, so the search is a predicate.
local function firstAt(surface)
  return hafen.locale():miss():find(function(m) return m:surface() == surface end)
end

-- Is (surface, text) in this catalogue's miss set?
local function missed(surface, text)
  return hafen.locale():miss():find(function(m)
    return (m:surface() == surface) and (m:text() == text)
  end) ~= nil
end

-- Every surface `text` reached, whatever the key -- what proves a string reached NOTHING.
local function surfacesOf(text)
  local out = {}
  for _, m in ipairs(hafen.locale():miss():list()) do
    if m:text() == text then out[#out + 1] = m:surface() end
  end
  return table.concat(out, ",")
end

local made = {}
local function keep(w) made[#made + 1] = w; return w end

local function finish()
  pcall(function() hafen.locale():release() end)
  for _, w in ipairs(made) do pcall(function() w:destroy() end) end
  made = {}
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- A phase runs on a timer, so a throw inside one is logged and isolated by the addon layer and the run
-- simply stops -- leaving the catalogue installed and the window on screen. Every phase is therefore its
-- own pcall: whatever it was, it becomes one [fail] line and the suite still tidies up after itself.
local function phase(fn, onfail)
  return function()
    local ok, err = pcall(fn)
    if not ok then
      if onfail then pcall(onfail) end
      check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      finish()
    end
  end
end

local function run()
  local loc, s = hafen.locale(), hafen.session():current()
  if s == nil then
    check(false, "a character is logged in", "no current session -- run this in the world")
    return finish()
  end

  -------------------------------------------------------------------------------------------------
  -- One window, one button carrying a tooltip, one field holding a word. An empty catalogue names
  -- nothing and records everything, so what each of them draws lands in :miss() under its own key.
  -------------------------------------------------------------------------------------------------
  loc:load({}):install()
  local win = keep(hafen.ui():window():title(WIN):size(220, 90):position(120, 120))
  keep(hafen.ui():button():parent(win):position(10, 10):text("Brodgar"):tooltip(TIP))
  local entry = keep(hafen.ui():entry():parent(win):position(10, 45):size(180, 20):value(TYPED))
  hafen.log():write(SYS)          -- ...a System line, which is a chat line of the `chat.system` kind

  -- A frame has to run: a window draws its caption from its deco and a field its text from its own
  -- draw, neither of which has happened on the statement that built it.
  hafen.timer():after(1.5, phase(function()
    check(missed("window.title", WIN),
          "the window caption is recorded under window.title", surfacesOf(WIN))

    local sys = hafen.locale():miss():find(function(m)
      return (m:surface() == "chat.system") and (m:text():sub(-#SYS) == SYS)
    end)
    check(sys ~= nil, "a System line is recorded under chat.system, its own kind's key",
          "the line reached " .. (surfacesOf(SYS) ~= "" and surfacesOf(SYS) or "no surface"))

    check(surfacesOf(TYPED) == "",
          "what the user types reaches no surface at all, so no catalogue can match it",
          surfacesOf(TYPED))

    ---------------------------------------------------------------------------------------------
    -- ...and now a catalogue that NAMES them. The pair going away is the lookup running at the
    -- render, under the very key the site declared.
    ---------------------------------------------------------------------------------------------
    local sysline = (sys ~= nil) and sys:text() or (SYS .. "?")
    loc:load({ text = { ["window.title"] = { [WIN] = WIN .. ES },
                        ["chat.system"]  = { [sysline] = sysline .. ES },
                        ["*"]            = { [TYPED] = TYPED .. ES } } }):install()
    hafen.log():write(SYS)

    hafen.timer():after(1.5, phase(function()
      check(not missed("window.title", WIN),
            "an entry under window.title answers the caption", "still recorded as a miss")
      -- ...and the surface has to be PROVED live in this round, or the absence above says nothing: the
      -- suite's own verdict lines are System lines the catalogue does not name, so they are the witness.
      check((firstAt("chat.system") ~= nil) and not missed("chat.system", sysline),
            "an entry under chat.system answers the line, while the surface goes on recording",
            (firstAt("chat.system") == nil) and "no chat.system pair at all -- the chat drew nothing"
              or "the line is still recorded as a miss")

      -- Criterion 6, at the two surfaces a translation is in force at right now -- and the field again,
      -- now that an entry under "*" names the very word it holds.
      -- `[title=]` is a WINDOW's own caption, so it belongs on a step whose role is window.
      local t = win:match("window[title=" .. WIN .. "]")
      local e = win:match("[text=" .. TYPED .. "]")
      check((win:title() == WIN) and (entry:value() == TYPED) and (t ~= nil) and (e ~= nil)
              and (surfacesOf(TYPED) == ""),
            "the model is not translated: :title(), :value() and the [title=]/[text=] selectors answer"
              .. " English, and a \"*\" entry still reaches no field",
            tostring(win:title()) .. "/" .. tostring(entry:value())
              .. "/" .. tostring(t ~= nil) .. "/" .. tostring(e ~= nil) .. "/" .. surfacesOf(TYPED))

      -----------------------------------------------------------------------------------------
      -- The four surfaces a program cannot hover, right-click, open or speak. The catalogue
      -- names the caption ALONE, so every other surface goes on recording -- and the caption
      -- stays visibly translated for the eye check below.
      -----------------------------------------------------------------------------------------
      loc:load({ text = { ["window.title"] = { [WIN] = WIN .. ES } } }):install()
      manualCheck("within " .. WINDOW .. "s: hover the button in the suite's own window until its tip shows;"
                  .. " right-click the ground and leave the petals up a second; open the Character Sheet;"
                  .. " say a word in area chat",
                  "four scored lines below, one per surface, as each is reached")
      manualCheck("read the screen while that runs",
                  "the suite's window captioned \"" .. WIN .. ES .. "\", the field under it still \""
                  .. TYPED .. "\", and any floating character name unchanged")

      local hits, petals, left = {}, nil, WINDOW
      local poll
      poll = hafen.timer():every(POLL, phase(function()
        -- The tooltip is the suite's OWN, so it is looked for by name; the other three are the client's
        -- own words and nothing here can predict them, so any pair at the key counts.
        if (hits.tooltip == nil) and missed("tooltip", TIP) then hits.tooltip = TIP end
        for _, key in ipairs({"menu", "heading", "world.speech"}) do
          if hits[key] == nil then
            local m = firstAt(key)
            if m ~= nil then hits[key] = m:text() end
          end
        end
        if (hits.menu ~= nil) and (petals == nil) then
          local names = {}
          for _, p in ipairs(s:flowermenu():list()) do names[#names + 1] = p:label() end
          if #names > 0 then petals = names end
        end
        left = left - POLL
        if (left > 0) and not (hits.tooltip and hits.menu and hits.heading and hits["world.speech"]) then
          return
        end
        poll:cancel()

        check(hits.tooltip == TIP, "a widget's tooltip is recorded under tooltip",
              hits.tooltip or ("no pair for \"" .. TIP .. "\" in " .. WINDOW .. "s"))
        local named = false
        for _, n in ipairs(petals or {}) do named = named or (n == hits.menu) end
        check((hits.menu ~= nil) and named,
              "a petal caption is recorded under menu, and flowermenu():list() names that same English",
              (hits.menu == nil) and ("no menu pair in " .. WINDOW .. "s")
                or (hits.menu .. " not among " .. table.concat(petals or {}, ",")))
        check(hits.heading ~= nil, "a section heading is recorded under heading",
              "no heading pair in " .. WINDOW .. "s")
        check(hits["world.speech"] ~= nil, "a speech bubble is recorded under world.speech",
              "no world.speech pair in " .. WINDOW .. "s")

        -- Saying a word in area chat means typing it into the chat's quick line first, which is drawn at
        -- `chat` like every other line there -- but is what the PLAYER is typing, so it is offered to no
        -- catalogue at all. The prompt it carries is what a recorded one would be recognised by.
        local typed = hafen.locale():miss():find(function(m)
          return (m:surface():sub(1, 4) == "chat") and (m:text():find("> ", 1, true) ~= nil)
        end)
        check(typed == nil,
              "the chat quick line is offered to no catalogue: nothing the player typed was recorded",
              (typed ~= nil) and (typed:surface() .. " / " .. typed:text()) or "")
        finish()
      end, function() poll:cancel() end))
    end))
  end))
end

hafen.slash():on("t102", run)   -- the only way in: a suite does not start itself
