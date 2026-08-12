-- 059.3 — the click runs your Lua. Self-checking suite.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The name and the description carry a "$", which is markup in what the grid paints the tip with: the entry
-- has to draw it and read it back as written, and the manual line below is where the drawing is judged.
local NAME = "059.3 Click me ($1)"
local TIP  = "Costs $0 -- it runs your Lua and sends nothing"

local function run()
  pass, fail, manual = 0, 0, 0               -- the command is run again after every fix round
  local mg = hafen.menugrid()
  mg:remove("click"):remove("quiet")         -- a re-run starts clean; removing what is not there is inert

  local icon = hafen.asset():get("dig.png")
  local pag  = mg:add("click"):name(NAME):icon(icon):tooltip(TIP)
  local mute = mg:add("quiet"):name("059.3 No handler"):icon(icon)

  local trace, handed = "", nil
  local first = pag:on("use", function(p) trace = trace .. "1"; handed = p end)
  pag:on("use", function() trace = trace .. "2" end)
  eq(":on hands back a Sub on the one key an entry has", tostring(first), "Sub(use)")

  pag:use()
  eq("two handlers on one entry both ran, in registration order", trace, "12")
  check(handed == pag, "the handler is handed the very entry that fired", handed)

  first:off()
  trace = ""
  pag:use()
  eq("after sub:off() the other handler runs, and only it", trace, "2")

  local quiet, err = pcall(function() mute:use() end)
  check(quiet, "an entry nobody subscribed to does nothing on :use(), rather than raising", err)

  eq("pag:addon() names the addon that added the entry", pag:addon(), "059-menugrid-entries.3")
  eq("the name and the description read back exactly as written",
     pag:name() .. " | " .. pag:tooltip(), NAME .. " | " .. TIP)

  local theirs                               -- an entry of the client's own: the first root that is not ours
  for _, r in ipairs(mg:roots()) do
    if (theirs == nil) and (r:res():sub(1, 6) ~= "addon/") then
      theirs = r
    end
  end
  if theirs == nil then
    check(false, "the client's own entry answers :addon() with nil and refuses :on",
          "no entry of the client's own on the root screen")
  else
    eq("the client's own entry has no addon: :addon() is nil", theirs:addon(), nil)
    refuses("and it refuses a handler, naming whose entry it is", {
      {theirs:res(), function() theirs:on("use", function() end) end, "is the client's own entry"},
    })
  end

  refuses("an event a menu entry does not have is refused, naming the one it has", {
    {"clicked", function() pag:on("clicked", function() end) end, "it has: use"},
    {"a key that is not a string", function() pag:on(7, function() end) end, "expects (string, function)"},
  })

  pag:on("use", function() hafen.log():write("[click] 059.3: the menu button ran this line") end)
  manualCheck("open the action menu, rest the pointer on the \"" .. NAME .. "\" button, then left-click it",
              "the tooltip reading that name over \"" .. TIP .. "\", and one \"[click] 059.3\" line in the log")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t059-3", run)   -- the only way in: a suite does not start itself
