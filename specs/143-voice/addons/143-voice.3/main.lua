-- 143.3 — a petal of your own on the radial menu. Self-checking suite.
--
-- Right-clicks the nearest object through s:world():click(g, 3), and inside the FlowerMenuAdded handler
-- adds a petal, reads it back beside the server's, tries the two bad calls, and picks it. One timer scores
-- what the pick did: fn ran with the petal and the session, FlowerMenuRemoved carried the label, and :add
-- after the ring closed is refused naming FlowerMenuAdded.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
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
    eq("index() == count()", p:index(), menu:count())
    eq("wire() == count() - 1", p:wire(), menu:count() - 1)
    local l = menu:list()
    check(l[#l] == p, "list() ends in it", tostring(l[#l]))
    eq("count() is one more than the payload's length", menu:count(), #petals + 1)
    check(#petals > 0 and petals[1]:native() == true, "a server petal reads native() true",
          (#petals > 0) and tostring(petals[1]:native()) or "no server petal")
    eq("info().native is false", p:info().native, false)
    refuses("add(\"\", fn) is refused naming the label",
            function() menu:add("", function() end) end, "label")
    refuses("add(\"x\", 1) is refused naming a function",
            function() menu:add("x", 1) end, "function")
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
    refuses("add(\"late\", fn) after the ring closed is refused naming FlowerMenuAdded",
            function() s:flowermenu():add("late", function() end) end, "FlowerMenuAdded")
    sub:off()
    rsub:off()
    summary()
  end)
end

-- The only way in: a suite does not start itself. Deferred onto the step, because the console line runs
-- under the typed tree's monitor and the right-click is a write of the session's.
hafen.console():on("t143", function() hafen.timer():after(0, run) end)
