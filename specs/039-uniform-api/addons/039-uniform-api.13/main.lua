-- 039.13 -- hafen.quest(), hafen.wound(), hafen.craft(): the last three flat sections become entities.
-- Self-checking suite; see specs/testing/addon-suite.md. Run  :t039-13
--
-- The headline is the CRAFT decision: hafen.craft():current() is nil while no recipe is open, so the
-- `if c then` guard every crafting addon already writes goes on meaning what it meant. An inert
-- always-truthy entity would have turned each of those guards into one that passes and then reads
-- nothing, which is the silent failure this grammar exists to delete -- so the case with NO recipe open
-- is the one this suite asserts hardest, and it needs no setup at all.
--
-- It stands alone (D-085): every premise it rests on is asserted here, including the ones other suites
-- also make. It declares no permissions and writes nothing; the one gated verb is tested by its REFUSAL.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Count how many of a list of {label, thunk, wantedText} rows throw naming their replacement.
local function named(rows)
  local n, miss = 0, nil
  for _, r in ipairs(rows) do
    local ok, err = pcall(r[2])
    if (not ok) and (tostring(err):find(r[3], 1, true) ~= nil) then
      n = n + 1
    elseif not miss then
      miss = r[1] .. " -> " .. (ok and "<no error>" or tostring(err))
    end
  end
  return n, miss
end

-- Does a snapshot carry every REQUIRED key, no key outside required+optional, and nothing else?
local function shape(t, required, optional)
  if type(t) ~= "table" then return false, "not a table: " .. type(t) end
  local allowed = {}
  for _, k in ipairs(required) do allowed[k] = true end
  for _, k in ipairs(optional or {}) do allowed[k] = true end
  for k in pairs(t) do
    if not allowed[k] then return false, "unexpected field " .. tostring(k) end
  end
  for _, k in ipairs(required) do
    if t[k] == nil then return false, "missing " .. k end
  end
  return true, "ok"
end

local RETIRED = {
  { "hafen.quests",       function() return hafen.quests end,       "hafen.quest():list(f)" },
  { "hafen.wounds",       function() return hafen.wounds end,       "hafen.wound():list(f)" },
  { "hafen.wounds.has",   function() return hafen.wounds end,       "hafen.wound():find(needle)" },
  { "hafen.craft.current", function() return hafen.craft.current end, "hafen.craft():current()" },
  { "hafen.craft.make",   function() return hafen.craft.make end,   "hafen.craft():current():make(all)" },
}

-- The WoundChanged latch (a PASSIVE subscription, not a start): the payload became Wound objects, and
-- the only sample worth asserting is a NON-EMPTY one -- the login fires the event with an empty list on
-- a healthy character, so latching the first arrival would prove that a table is a table.
local woundEvent, woundEmpty = nil, 0
hafen.event():on("WoundChanged", function(list)
  if (type(list) == "table") and (#list > 0) then
    woundEvent = woundEvent or list
  else
    woundEmpty = woundEmpty + 1
  end
end)

local function run()
  pass, fail, manual = 0, 0, 0   -- per RUN, not per session: this suite asks to be run again after
                                 -- opening a recipe, and a cumulative [summary] would not match the
                                 -- lines printed under it
  local quest, wound, craft = hafen.quest(), hafen.wound(), hafen.craft()

  -- 1. all three sections are per-addon singletons over a callable table, and an unknown verb throws
  local same = 0
  for _, s in ipairs({ "quest", "wound", "craft" }) do
    if (hafen[s]() == hafen[s]()) and (type(hafen[s]) == "table") and (type(hafen[s]()) == "userdata") then
      same = same + 1
    end
    if pcall(function() return hafen[s](1) end) then same = -99 end       -- an argument must be refused
  end
  check(same == 3, "each section is one callable table over one userdata object, and refuses arguments ("
        .. same .. "/3)")
  refuses("a section refuses an unknown verb", function() return craft:nosuchverb() end, "no verb")

  -- 2. every retired spelling throws naming its replacement
  local n, miss = named(RETIRED)
  check(n == #RETIRED, "every retired spelling throws naming its replacement (" .. n .. "/"
        .. #RETIRED .. ")", miss)

  -- 3. the log and the wound list ARE their section objects, and neither is a sequence
  local noSeq = 0
  for _, c in ipairs({ quest, wound }) do
    if not pcall(function() return #c end) then noSeq = noSeq + 1 end
    if not pcall(function() return c[1] end) then noSeq = noSeq + 1 end
    if not pcall(function() return c:get("me") end) then noSeq = noSeq + 1 end
  end
  check(noSeq == 6, "hafen.quest() and hafen.wound() ARE their collections and are not sequences: #, [1]"
        .. " and a string key are all refused (" .. noSeq .. "/6)")
  check((quest:count() == #quest:list()) and (wound:count() == #wound:list())
        and (quest:get(-1) == nil) and (wound:get(-1) == nil),
        ("both read without a client-side guard: %d quest(s), %d wound(s), and an unknown id is plain nil")
        :format(quest:count(), wound:count()),
        "a read threw or disagreed with :count()")

  -- 4. THE HEADLINE: with no recipe open, current() is NIL -- so `if c then` still guards
  local c = craft:current()
  if c == nil then
    check(true, "with no recipe open hafen.craft():current() is nil, so `if c then` is false and every"
          .. " existing craft guard still works")
    manualCheck("open any recipe (a crafting-menu entry), then run  :t039-13",
                "2 more [pass]: the open recipe reads its name and slots as a Craft object, and its gated"
                .. " :make() refuses this addon by naming the \"actions\" permission")
  else
    local ok, why = shape(c:info(), { "recipe", "inputs", "outputs", "qmod", "tools" })
    check(ok and (c == craft:current()) and c:exists() and (c:name() == c:info().recipe)
          and (#c:inputs() == #c:info().inputs) and (#c:outputs() == #c:info().outputs)
          and (#c:qualityInputs() == #c:info().qmod) and (#c:tools() == #c:info().tools),
          ("the open recipe '%s' is one interned Craft: %d input(s), %d output(s), %d tool(s)")
          :format(tostring(c:name()), #c:inputs(), #c:outputs(), #c:tools()), why)
    -- The gate: this suite declares nothing, so the refusal must name the PERMISSION. Nothing is crafted.
    refuses("the gated :make() refuses an addon that declared no permission",
            function() return c:make() end, "did not declare")
  end

  -- 5. the wound presence test: a miss is falsy, exactly as the old boolean was
  check((wound:find("no-such-wound-zzz") == nil) and (not wound:find("no-such-wound-zzz")),
        "a wound search that matches nothing is nil, so `if hafen.wound():find(x) then` reads false"
        .. " exactly as the old boolean did", wound:find("no-such-wound-zzz"))

  -- 6. a Quest is interned on its id, its status is one of the four words, and :info() loses nothing
  local q1 = quest:list()[1]
  if q1 then
    local ok, why = shape(q1:info(), { "id", "status", "mtime" }, { "title", "res" })
    local st = q1:status()
    check(ok and (quest:get(q1:id()) == q1) and q1:exists()
          and ((st == "pending") or (st == "done") or (st == "failed") or (st == "disabled"))
          and (q1:title() == q1:info().title) and (q1:modified() == q1:info().mtime),
          ("%d quest(s) in the log; '%s' is [%s], one interned object addressed by its own id")
          :format(quest:count(), tostring(q1:title() or q1:res()), tostring(st)), why)
    -- Only the SELECTED quest has objectives -- that is the game's limit, and it is a property here
    local sel, bare = quest:selected(), 0
    for _, q in ipairs(quest:list()) do
      if (not q:selected()) and (#q:conditions() == 0) then bare = bare + 1 end
    end
    check((bare == quest:count() - (sel and 1 or 0))
          and ((sel == nil) or (sel:selected() and (sel == quest:get(sel:id())))),
          ("only the selected quest carries objectives: %d of %d are bare, selected=%s")
          :format(bare, quest:count(), sel and ("'" .. tostring(sel:title()) .. "'") or "none"),
          "an unselected quest answered conditions, or :selected() disagreed with the collection")
    if sel then
      local c1 = sel:conditions()[1]
      if c1 then
        local cok, cwhy = shape(c1:info(), { "status" }, { "desc", "text" })
        check(cok and (c1:quest() == sel) and c1:exists()
              and (c1:description() == c1:info().desc)
              and (sel:conditions()[1] == c1),
              ("the selected quest has %d objective(s); the first is [%s] '%s', interned on its quest and"
               .. " its own text"):format(#sel:conditions(), tostring(c1:status()),
                                          tostring(c1:description())), cwhy)
      else
        check(false, "an objective is interned on its quest and its own text",
              "the selected quest publishes no objectives")
      end
    else
      manualCheck("open the Quest Log (the character sheet) and click a quest, then run  :t039-13",
                  "1 more [pass]: the selected quest's first objective reads its status and text and"
                  .. " points back at that same Quest object")
    end
  else
    manualCheck("the quest log is empty -- accept a quest, then run  :t039-13",
                "3 more [pass]: a Quest interned by id with one of the four status words, objectives on"
                .. " the selected quest only, and an objective pointing back at its quest")
  end

  -- 7. a Wound is interned on its id, and the tree's parent id is resolved to the Wound above it
  local w1 = wound:list()[1]
  if w1 then
    local ok, why = shape(w1:info(), { "id", "parentid", "level" }, { "name", "res", "severity" })
    local roots, kids = 0, 0
    for _, w in ipairs(wound:list()) do
      if w:parent() == nil then roots = roots + 1 else kids = kids + 1 end
      if (w:parent() ~= nil) and (w:parent():id() ~= w:info().parentid) then kids = -99 end
    end
    check(ok and (wound:get(w1:id()) == w1) and w1:exists() and (w1:level() == w1:info().level)
          and (roots + kids == wound:count()) and (roots >= 1),
          ("%d wound(s), %d root(s) and %d complication(s); '%s' is one interned object and w:parent()"
           .. " resolves the tree"):format(wound:count(), roots, kids,
                                           tostring(w1:name() or w1:res())), why)
    check(wound:find(w1:res() or w1:name()) ~= nil,
          "a wound search that matches hands back the Wound itself, not a boolean",
          "no match for " .. tostring(w1:res() or w1:name()))
  else
    manualCheck("you have no wounds -- take a hit (or run this on a wounded character), then :t039-13",
                "2 more [pass]: a Wound interned by id whose w:parent() resolves the tree it is drawn"
                .. " in, and a search that hands back the Wound rather than a boolean")
  end

  -- 8. WoundChanged carries Wound OBJECTS. The login fires it with an empty list on a healthy
  -- character, so only a non-empty sample can prove anything -- and an empty-only session says so.
  if woundEvent then
    local m = woundEvent[1]
    check((type(m) == "userdata") and (type(m:id()) == "number") and (wound:get(m:id()) == m),
          ("WoundChanged carried %d Wound object(s), the same objects the collection hands out")
          :format(#woundEvent), type(m))
  else
    check(woundEmpty > 0,
          ("WoundChanged fired %d time(s) this session, every payload an empty list -- which is what a"
           .. " character with no wounds sends, so there is no member to assert here"):format(woundEmpty),
          "WoundChanged has not fired at all -- are you in the world?")
  end

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-13", run)   -- the only way in: a suite does not start itself
