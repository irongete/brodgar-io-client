-- 088.1 -- :list() enumerates, and nothing else. Self-checking suite.
--
-- The severity of this rename is the SIDE EFFECT, not the message. hafen.ui():list() used to reach
-- Controls.list, which attaches the control to the addon layer before the next statement runs -- so a
-- reader who had learned four enumerating :list()s wrote the fifth and got an empty box drawn on screen.
-- A retirement fired from inside the builder would pass a message check and still leave that control up.
-- So the first two lines are one claim in two halves: the call raises naming :listbox(), AND the number
-- of widgets in the addon layer is the same on both sides of it.
--
-- Then the replacements answer for themselves: hafen.ui():listbox() builds a real one, hafen.vr():entity()
-- is a collection over every kind at once, and hafen.vr():click(...) still hands back the boolean the
-- caller needs. Each retired spelling is asked to raise and to NAME what replaced it.

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

-- A refusal is a check: the call must fail, and fail SAYING what to write instead.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, r = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- ---- the refusal that must fire BEFORE anything is built ---------------------------------------------
--
-- A probe of our own gives us the addon layer's root -- the very tree UiApi.attach adds a new surface to
-- -- and #root:children() is then the count a refused builder must not move. The probe is destroyed
-- again, so the suite leaves the layer exactly as it found it.

local function refusalSection()
  local probe = hafen.ui():widget():size(1, 1)
  local root = probe:parent()
  check(root ~= nil, "the addon layer is reachable, so the count below means something", root)

  local before = #root:children()
  refuses("hafen.ui():list() raises naming :listbox()",
          function() return hafen.ui():list() end, "hafen.ui():listbox()")
  local after = #root:children()

  check(after == before, "and it BUILDS NOTHING: the addon layer holds the same widgets across the refused"
        .. " call (" .. before .. ")", after)
  probe:destroy()
end

-- ---- the replacement builds a real control ------------------------------------------------------------

local function listboxSection()
  local box = hafen.ui():listbox():position(40, 40):size(160, 60):rowHeight(20):rows{"Alpha", "Beta"}
  local n, h = 0, nil
  if box ~= nil then
    h = box:rowHeight()
    if h == 20 then n = n + 1 end
    local rows = box:rows()
    if (type(rows) == "table") and (#rows == 2) and (rows[1] == "Alpha") then n = n + 1 end
    box:destroy()
  end
  check(n == 2, "hafen.ui():listbox() builds one that answers :rowHeight(20) and :rows{...} (" .. n .. "/2)",
        "rowHeight " .. tostring(h))

  refuses("hafen.ui():listbox(fn) still refuses an argument, naming the chained setters",
          function() return hafen.ui():listbox(function() end) end, "chained setters")
end

-- ---- the cross-kind collection ------------------------------------------------------------------------
--
-- hafen.vr() holds four kinds and its own visibility switch, so it cannot itself be the collection: the
-- cross-kind set is hafen.vr():entity(), and the question it exists to answer is "how many, in total".

local function perKindSum()
  local vr = hafen.vr()
  return vr:ghost():count() + vr:sprite():count() + vr:object():count() + vr:widget():count()
end

local function entitySection()
  local sum = perKindSum()
  local n = hafen.vr():entity():count()
  check(n == sum, "hafen.vr():entity():count() equals the sum of the four per-kind counts (" .. n .. ")", sum)

  local l = hafen.vr():entity():list()
  check((type(l) == "table") and (#l == n), "hafen.vr():entity():list() is a plain array of that many",
        type(l) .. ", #" .. tostring(l and #l))

  refuses("hafen.vr():list(filter) raises naming :entity()",
          function() return hafen.vr():list() end, ":entity()")
end

-- ---- the pointer verb ----------------------------------------------------------------------------------

local function clickSection()
  local r = hafen.vr():click("MouseMove", 4, 4, 1)
  check(type(r) == "boolean", "hafen.vr():click(key, x, y, a) answers a boolean", type(r) .. " " .. tostring(r))

  refuses("hafen.vr():pointer(...) raises naming :click(...)",
          function() return hafen.vr():pointer("MouseMove", 4, 4, 1) end, ":click(")
end

-- ---- what the collection does that the old array could not ---------------------------------------------
--
-- Counting and removing ACROSS the kinds is the whole of the reshape, and one standing thing proves both.
-- Standing anything needs the world, which only the player can be in, so this is reached inside a bounded
-- window and scored over what the run got to.

local function standingSection()
  local s = hafen.session():current()
  local p = s:player():gob():position()
  local base = hafen.vr():entity():count()
  local g = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)
  local n = 0
  if hafen.vr():entity():count() == (base + 1) then n = n + 1 end
  hafen.vr():entity():remove(g)
  if (hafen.vr():entity():count() == base) and (not g:exists()) then n = n + 1 end
  check(n == 2, "hafen.vr():entity() counts a ghost across the kinds and :remove(x) ends it (" .. n .. "/2)",
        n)
end

local function inWorld()
  local ok, p = pcall(function()
    return hafen.session():current():player():gob():position()
  end)
  return ok and (p ~= nil)
end

local function run()
  pass, fail, manual = 0, 0, 0

  section("refusal", refusalSection)
  section("listbox", listboxSection)
  section("entity", entitySection)
  section("click", clickSection)

  local tries = 0
  local function poll()
    if inWorld() or (tries >= 20) then
      if inWorld() then
        section("standing", standingSection)
      else
        check(false, "hafen.vr():entity() counts a ghost across the kinds and :remove(x) ends it (0/2)",
              "<never in the world: log a character in and run :t088-1 again>")
      end
      manualCheck("look at the screen now that the run has finished",
                  "nothing of this suite is left: no list box, no empty box at the client's default"
                  .. " build position, and no cabin standing in the world")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      return
    end
    tries = tries + 1
    hafen.timer():after(0.5, poll)
  end
  poll()
end

hafen.slash():on("t088-1", run)                -- the only way in: a suite does not start itself
