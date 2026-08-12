-- 059.2 — the tree: a category is an entry that has children. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why. nil when it did, the message when it did not.
local function refusal(fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  if (not ok) and (err:find(wantMsg, 1, true) ~= nil) then return nil end
  return err
end

-- One verdict line over a group of refusals that make ONE claim; a failure names which case broke it.
local function refuses(what, cases)
  for _, c in ipairs(cases) do
    local bad = refusal(c[2], c[3])
    if bad then
      check(false, what, c[1] .. " -- " .. bad)
      return
    end
  end
  check(true, what)
end

local function carries(arr, obj)
  for _, v in ipairs(arr) do
    if v == obj then return true end
  end
  return false
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  pass, fail, manual = 0, 0, 0               -- the command is run again after every fix round
  local mg = hafen.menugrid()
  mg:remove("tools"):remove("tools/dig")     -- a re-run starts clean; removing what is not there is inert

  local icon  = hafen.asset():get("dig.png")
  local cat   = mg:add("tools"):name("Tools"):icon(icon)
  local child = mg:add("tools/dig"):name("Dig"):icon(icon)

  local wrote = child:parent(cat)
  check(wrote == child, "the setter writes and chains, handing the entry back", wrote)
  check(child:parent() == cat, ":parent() reads back the very category it was given", child:parent())
  local kids = cat:children()
  check((#kids == 1) and (kids[1] == child), "the category holds exactly that child", #kids)
  check(carries(mg:roots(), cat) and not carries(mg:roots(), child),
        "the root screen carries the category and not the child")
  child:parent(nil)
  check(carries(mg:roots(), child) and (child:parent() == nil),
        ":parent(nil) puts it back on the root screen", child:parent())

  local theirs                               -- a category of the client's own: the first root that is not ours
  for _, r in ipairs(mg:roots()) do
    if (theirs == nil) and (r:res():sub(1, 6) ~= "addon/") then
      theirs = r
    end
  end
  if theirs == nil then
    check(false, "the two kinds share one tree: a client category carries your entry",
          "no entry of the client's own on the root screen")
  else
    child:parent(theirs)
    check(carries(theirs:children(), child) and not carries(mg:roots(), child),
          "the two kinds share one tree: a client category carries your entry", child:parent())
    refuses("a write on the client's own entry is refused", {
      {theirs:res(), function() theirs:parent(nil) end, "is the client's own entry"},
    })
  end

  child:parent(cat)                          -- back under your own, for the cycle test and the manual line
  refuses("a cycle is refused rather than written", {
    {"itself",   function() cat:parent(cat) end,   "cannot hang under itself"},
    {"two-step", function() cat:parent(child) end, "already hangs under this one"},
  })
  check((cat:parent() == nil) and (child:parent() == cat),
        "and a refused cycle wrote nothing: the tree is what it was", cat:parent())
  refuses("a parent that is not a Pagina object is refused, saying which shape it was", {
    {"a number", function() child:parent(7) end,     "the parent is a Pagina object"},
    {"a key",    function() child:parent("Tools") end, "is a key, and a parent is"},
  })

  manualCheck("open the action menu and click the \"Tools\" button",
              "the \"Dig\" button on the screen it opens, and Back returning to the root screen")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t059-2", run)   -- the only way in: a suite does not start itself
