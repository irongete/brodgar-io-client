-- 059.1 — an entry of your own in the action menu, drawn with your own PNG. Self-checking suite.

local ID  = "addon/059-menugrid-entries.1/dig"
local TMP = "addon/059-menugrid-entries.1/tmp"

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
  mg:remove("dig"):remove("tmp")            -- a re-run starts clean; removing what is not there is inert

  local icon = hafen.asset():get("dig.png")
  local pag = mg:add("dig")
  eq("an entry names itself by the id its addon gave it", pag:res(), ID)
  check((mg:get(ID) == pag) and pag:exists(),
        ":get(res) is the very object :add handed back, and it is in the menu", mg:get(ID))
  check(carries(mg:list(), pag) and carries(mg:roots(), pag),
        "the catalogue and the root screen both carry it")
  check(pag:name("Auto-dig"):icon(icon) == pag,
        "the setters write and chain, each handing the entry back")
  eq("the display name reads back", pag:name(), "Auto-dig")
  check(pag:icon() == icon, ":icon() reads back the very handle it was given", pag:icon())
  eq("a custom entry sends nothing, so its action path is empty", #pag:path(), 0)

  local tmp = mg:add("tmp")
  mg:remove(tmp)
  check((not tmp:exists()) and (mg:get(TMP) == nil),
        "a removed entry is out of the menu: :exists() false and :get() nil", mg:get(TMP))

  refuses("a bad id is refused, saying which shape it was", {
    {"empty",     function() mg:add("") end,        "must be a non-empty string"},
    {"absolute",  function() mg:add("/abs") end,    "is absolute"},
    {"climbing",  function() mg:add("../out") end,  "climbs out of your addon"},
    {"a number",  function() mg:add(7) end,         "expected a string id"},
    {"nil",       function() mg:add(nil) end,       "must not be nil"},
  })
  refuses("a duplicate id is refused", {
    {"dig again", function() mg:add("dig") end,     "already has an entry with the id"},
  })
  refuses("an icon that is not an image handle is refused", {
    {"a path",    function() pag:icon("dig.png") end,             "is a path, and an icon is the"},
    {"a font",    function() pag:icon(hafen.font():get("serif")) end, "an icon is an image"},
  })

  local theirs
  for _, r in ipairs(mg:roots()) do
    if (theirs == nil) and (r:res():sub(1, 6) ~= "addon/") then
      theirs = r
    end
  end
  if theirs == nil then
    check(false, "a write on the client's own entry is refused", "no granted entry on the root screen")
  else
    refuses("a write on the client's own entry is refused", {
      {theirs:res(), function() theirs:name("x") end, "is the client's own entry"},
    })
  end

  manualCheck("open the action menu and hover the new button",
              "your own PNG on the ROOT screen (a pick on a dark square), tooltip \"Auto-dig\"")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t059-1", run)   -- the only way in: a suite does not start itself
