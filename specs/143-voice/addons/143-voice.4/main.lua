-- 143.4 — a petal goes with its addon. Self-checking suite.
--
-- The step this task adds disarms a petal when its addon is torn down while the ring is still up, and it
-- changes nothing a live addon can see: a ring holds the mouse and the keyboard, so no player disables an
-- addon while one is up, and no verb tears one down. So the suite proves the ring round trip is intact —
-- right-click the nearest object, add a petal inside FlowerMenuAdded, read it back, pick it, and score on
-- one timer that fn ran with the petal and the ring closed carrying the label. The step itself is verified
-- by reading.

local WINDOW = 4   -- seconds: a ring lives about a second, plus the server's answer to the cancel

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local s = hafen.session():current()
  if not s then
    check(false, "a drawn session", "none")
    summary()
    return
  end
  local gobs = s:world():gob()
  local g = gobs:nearest("terobjs/trees") or gobs:nearest("terobjs/bushes") or gobs:nearest()
  if not g then
    check(false, "an object to right-click", "no gob near the character")
    summary()
    return
  end

  local st = { added = false, petal = nil, session = nil, ranPetal = nil, ranSession = nil,
               ran = 0, removed = nil }
  local sub, rsub

  sub = hafen.event():on("FlowerMenuAdded", function(petals, es)
    if st.added then return end   -- one ring is the whole proof
    st.added = true
    local menu = es:flowermenu()
    local p = menu:add("Suite petal", function(pp, ps)
      st.ran = st.ran + 1
      st.ranPetal, st.ranSession = pp, ps
    end)
    check(p ~= nil and p:native() == false, "add answers a Petal with native() false",
          p and tostring(p:native()) or "nil")
    eq("its label", p:label(), "Suite petal")
    local l = menu:list()
    check(l[#l] == p, "list() ends in it", tostring(l[#l]))
    eq("count() is one more than the payload's length", menu:count(), #petals + 1)
    st.petal, st.session = p, es
    p:select()
  end)
  rsub = hafen.event():on("FlowerMenuRemoved", function(label)
    if st.added and st.removed == nil then st.removed = label or false end
  end)

  s:world():click(g, 3)
  hafen.log():write("right-clicked " .. tostring(g:name()) .. "; scoring in " .. WINDOW .. " s")

  hafen.timer():after(WINDOW, function()
    check(st.added, "a ring was announced in the window", "no FlowerMenuAdded")
    eq("fn ran once", st.ran, 1)
    check(st.ranPetal ~= nil and st.ranPetal == st.petal, "fn ran with the petal (==)", tostring(st.ranPetal))
    check(st.ranSession ~= nil and st.ranSession == st.session, "fn ran with the session (==)",
          tostring(st.ranSession))
    eq("FlowerMenuRemoved carried the label", st.removed, "Suite petal")
    check(st.petal ~= nil and st.petal:native() == false, "the petal still reads native() false after the ring closed",
          st.petal and tostring(st.petal:native()) or "nil")
    eq("no ring is open after the pick", s:flowermenu():count(), 0)
    sub:off()
    rsub:off()
    summary()
  end)
end

-- The only way in: a suite does not start itself. Deferred onto the step, because the console line runs
-- under the typed tree's monitor and the right-click is a write of the session's.
hafen.console():on("t143", function() hafen.timer():after(0, run) end)
