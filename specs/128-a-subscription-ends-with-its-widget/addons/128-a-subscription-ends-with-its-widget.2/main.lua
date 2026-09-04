-- 128.2 -- the disposal seam retires what a selector matched. Self-checking suite: run with :t128
-- while a character is in the world.
--
-- NON-REGRESSION, and it says so out loud. No verb reports what a selector subscription has matched, and a
-- widget that died as a descendant of a destroyed window announced nothing before this task and announces
-- nothing after -- so the record this task stops keeping has no behavioural difference for a suite to
-- assert. What a suite CAN assert is the surface the retirement runs beside and could eat, and that is what
-- every line below is:
--
--   * the "Added" a widget entering the client's tree still makes, for the widget and for a child under it;
--   * the "Removed" a widget LEAVING still makes -- the destroyed widget's own, and the one its child makes
--     as it dies as a DESCENDANT, which is the announcement the new retirement runs one drain behind;
--   * that neither is doubled, and that nothing arrives afterwards: a retirement that announced would mint
--     one "Removed" per widget inside every closing window;
--   * that the machinery is unharmed -- a widget built after the deaths is matched, announced and retired
--     like the first, and every sub still ends.
--
-- The manual line is the client's own window, which is the only widget here nothing in Lua can close.

local ID   = "128-a-subscription-ends-with-its-widget.2"
local SEL  = "[name=" .. ID .. "/probe]"    -- the one refiner an addon owns: this suite's widget, no other's
local KID  = "[name=" .. ID .. "/kid]"
local WAIT = 0.4                            -- a bounded wait: the step has to have run
local OPEN = 25.0                           -- how long the container half has

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

local st = {}

local function report()
  if st.winSub then st.winSub:off() end          -- the last subscription standing, ended where it is read
  hafen.log():write("[count] a client window's own Removed fired " .. st.win .. " time(s): "
                    .. ((#st.titles > 0) and table.concat(st.titles, ", ") or "<none>"))
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t128 in the world")
    return report()
  end

  st.addP, st.addK, st.remP, st.remK, st.win, st.titles = 0, 0, 0, 0, 0, {}
  st.subs = {}
  local function keep(sub) st.subs[#st.subs + 1] = sub end

  keep(s:ui():on(SEL, "Added",   function() st.addP = st.addP + 1 end))
  keep(s:ui():on(SEL, "Removed", function() st.remP = st.remP + 1 end))
  keep(s:ui():on(KID, "Added",   function() st.addK = st.addK + 1 end))
  keep(s:ui():on(KID, "Removed", function() st.remK = st.remK + 1 end))
  -- The manual half: every window of the client's own tree that actually LEAVES it. NOT in st.subs -- this
  -- one has to outlive the checks below by the whole of OPEN, and they end every subscription they made.
  st.winSub = s:ui():on("window", "Removed", function(w)
    st.win = st.win + 1
    local ok, t = pcall(function() return w:title() end)
    st.titles[#st.titles + 1] = (ok and t) and t or "?"
  end)

  -- A container, and not the character's own inventory: that one is a Hidewnd, HIDDEN by its toggle and
  -- never removed from the tree, so it announces nothing here and never did.
  manualCheck("open a container (a cupboard, a chest) and close it again, twice, within "
              .. OPEN .. "s of running :t128",
              "the [count] line below reads 2 time(s), one per close")

  -- The pair the retirement runs beside: a widget in the client's tree, and a child under it that will die
  -- as a DESCENDANT -- rdispose recurses dispose() alone, so it never runs remove() itself.
  st.probe = hafen.ui():widget():parent(s:ui():root()):name("probe"):size(4, 4):position(1, 1)
  st.kid   = hafen.ui():widget():parent(st.probe):name("kid"):size(2, 2):position(0, 0)

  hafen.timer():after(WAIT, function()
    check(st.addP == 1, "a widget entering the client's tree is announced, once", st.addP)
    check(st.addK == 1, "...and so is the child that entered under it", st.addK)

    st.probe:destroy()
    hafen.timer():after(WAIT, function()
      check(st.remP == 1, "the destroyed widget still reported Removed, exactly once", st.remP)
      check(st.remK == 1, "...and so did the child that died as its descendant", st.remK)

      -- The machinery is unharmed: the same selector still matches, announces and retires a fresh widget.
      st.fresh = hafen.ui():widget():parent(s:ui():root()):name("probe"):size(4, 4):position(2, 2)
      hafen.timer():after(WAIT, function()
        check(st.addP == 2, "a widget built after the deaths is matched and announced too", st.addP)
        st.fresh:destroy()
        hafen.timer():after(WAIT, function()
          check(st.remP == 2, "...and its own Removed still lands", st.remP)
          check(st.remK == 1, "nothing was announced for the retired child, then or since", st.remK)

          local ok, err = pcall(function()
            for i = 1, #st.subs do st.subs[i]:off() end
          end)
          local ok2 = pcall(function()
            for i = 1, #st.subs do st.subs[i]:off() end
          end)
          check(ok and ok2, "sub:off() on every subscription made here raises nothing, and is idempotent",
                (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
        end)
      end)
    end)
  end)

  hafen.timer():after(OPEN, report)   -- the container half's own window, long after the checks above
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t128 scores its own run, not both
  st = {}
  hafen.timer():after(0, start)       -- the step is where a widget of another tree may be written
end

hafen.console():on("t128", run)   -- the only way in: a suite does not start itself
