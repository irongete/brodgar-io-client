-- 048.5 — pag:use() becomes protected, and hafen.act():menu is deleted. Self-checking suite; see
-- specs/testing/addon-suite.md.
--
-- Two halves of one claim. hafen.act():menu(path...) is GONE and nothing replaced it: 023-menugrid-oop already
-- absorbed the mechanism, so hafen.menugrid():get("Dig"):use() is the door and the pagina-PATH address space
-- goes with the old verb rather than being rehoused beside it. That only holds if the surviving door costs what
-- the deleted one did — and pag:use() shipped UNGATED, the one verb in hafen.* that committed a real server
-- action for free. So it gains the "actions" permission here.
--
-- That gate is a live behaviour change to a shipped verb, which makes the load-bearing assertion the one right
-- beside it: the READS on the very same entry, in the very same run, still answer for this very same undeclared
-- addon. A gate that landed on the section instead of on the write half would pass every refusal check below
-- and silently break every read-only addon that browses the action menu.
--
-- This suite declares no permissions (TESTING.md), so the verb is proven BY ITS REFUSAL and the firing demo is
-- a [manual] :lua line — the console owner declares every permission, so it needs no addon.

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

local function run()
  pass, fail, manual = 0, 0, 0

  -- The catalogue, read by an addon that declares nothing. Pick the first entry that has resolved far enough
  -- to have a DISPLAY name: the menu fills in sub-second as resources load, so a fixed key would redden on a
  -- cold login for a reason that is not this task, and a nameless entry cannot prove the :name() read.
  local cat = hafen.menugrid():list()
  local pag, nm
  for i = 1, #cat do
    local n = cat[i]:name()
    if (n ~= nil) and (n ~= "") then pag, nm = cat[i], n break end
  end
  check((pag ~= nil) and (hafen.menugrid():count() == #cat),
        ("the action menu answers this undeclared addon: :list() and :count() agree (%d entries)"):format(#cat),
        (pag == nil) and ("%d entries, none with a display name yet -- log in and re-run a second later")
                          :format(#cat)
                      or ("list=%d count=%d"):format(#cat, hafen.menugrid():count()))

  -- The verb is still there and is still a function -- this task gates it, it does not remove it.
  check((pag ~= nil) and (type(pag.use) == "function"),
        "pag:use is a function on an entry from that list",
        (pag == nil) and "no entry to read it off" or type(pag.use))

  -- THE CHANGE. It used to send. Now it refuses, and the refusal names the verb a caller has to declare for
  -- rather than a section, so the fix is readable from the one line.
  refuses("pag:use() refuses this undeclared addon, naming the verb",
          function() return pag:use() end, "pag:use: this addon did not declare")

  -- ...and the gate landed on the WRITE HALF ONLY. Same entry, same undeclared addon, same run: every read
  -- still answers. This is the claim that makes the change safe for the read-only addons that browse the menu.
  check((pag ~= nil) and (type(pag:res()) == "string") and (pag:name() == nm) and (pag:exists() == true),
        "...and the READS on that same entry still answer: :res(), :name(), :exists()",
        (pag == nil) and "no entry" or ("%s / %s / %s"):format(tostring(pag:res()), tostring(pag:name()),
                                                               tostring(pag:exists())))
  check((pag ~= nil) and (hafen.menugrid():get(pag:res()) == pag),
        "...and :get(resourceName) still addresses it, handing back the same interned object",
        (pag == nil) and "no entry" or tostring(hafen.menugrid():get(pag:res())))
  check(hafen.menugrid():get("nope/nope") == nil,
        "...and a miss is still plain nil, not a refusal",
        tostring(hafen.menugrid():get("nope/nope")))

  -- The old door is shut, under BOTH field reads (D-216): the colon call a shipped addon actually wrote, and
  -- the pre-039 dotted one. The two assert different sentences of the same message -- where the action is
  -- invoked now, and that the path address space was refused rather than rehoused.
  refuses("hafen.act():menu throws naming hafen.menugrid():get(name):use()",
          function() hafen.act():menu("lo", "cs") end, "hafen.menugrid():get(\"Dig\"):use()")
  refuses("the pre-039 dotted hafen.act.menu throws, and says there is no path-based door",
          function() return hafen.act.menu end, "There is no path-based door")

  -- ...and none was quietly added on the menu grid either. The collection has a CLOSED vocabulary, so the
  -- absence of a path door is itself a refusal that names the verb it does not have.
  refuses("hafen.menugrid() has no :use -- the path door was refused, not relocated",
          function() return hafen.menugrid().use end, "has no verb 'use'")

  -- The section EMPTIES over this feature (D-117); it does not disappear from under the verbs still on it.
  check(hafen.act():enabled() == false,
        "the rest of hafen.act() is still callable: :enabled() (false)",
        tostring(hafen.act():enabled()))

  -- Pinned to Dig on purpose: this fires for real on your character, so the line names ONE ordinary, harmless
  -- action rather than whatever entry the picker above happened to land on (which may be a category, or
  -- something you would rather not have armed).
  manualCheck(":lua hafen.menugrid():get(\"Dig\"):use()   -- if you do not have Dig, substitute any plain"
              .. " action you do have:  :lua for _, a in ipairs(hafen.menugrid():list()) do"
              .. " hafen.log():write(a:name() or a:res()) end",
              "the action arms exactly as left-clicking that menu button does, and the modifier keys you are"
              .. " physically HOLDING are the ones that apply -- pag:use() takes no mods because the client's"
              .. " own PagButton.use reads them live. The console owner declares every permission, so it gets"
              .. " past the gate this suite cannot")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t048-5", run)   -- the only way in: a suite does not start itself
