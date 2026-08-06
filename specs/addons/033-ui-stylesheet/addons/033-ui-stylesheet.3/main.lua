-- 033.3 — docs, the `theme` example, close. Self-checking suite; see specs/addons/TESTING.md.
--
-- What this task shipped and therefore what this asserts: the `data` asset (.json/.txt through hafen.asset,
-- the door a theme.json comes through), the sheet AS DATA end to end (file -> hafen.json():parse -> :load),
-- and the C1a contract the docs now publish -- a site key, `*`, an inert tree key, and the hard cut.
-- The two-addon fallback and the look of a restyled surface are the two things a program cannot see: they
-- are [manual] lines with the exact steps.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and drops its sheet before it finishes,
-- so a login that runs it leaves the client exactly stock.

local pass, fail, manual = 0, 0, 0

-- This addon's one stylesheet: a selector names a rule on it, and :install() applies what it says.
local sheet = hafen.ui():sheet()

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Build a sheet the way the `theme` addon does: the JSON carries a font DESCRIPTOR, Lua maps it to a handle.
local function sheetOf(doc)
  local rules = {}
  for key, props in pairs(doc.rules or {}) do
    local rule = {}
    if props.font then
      local f = props.font
      rule.font = f.size and hafen.font():get(f.face):derive():size(f.size) or hafen.font():get(f.face)
    end
    if props.color then rule.color = props.color end
    rules[key] = rule
  end
  return rules
end

local function run()
  -- 1. the `data` asset (033.3): the one loader, typed by extension, now covering the files a theme is made of.
  local a = hafen.asset():get("sheet.json")
  eq("a .json file loads as a data asset", a:type(), "data")
  eq("it reports the path it was loaded from", a:path(), "sheet.json")
  check(hafen.asset():get("sheet.json") == a, "a data asset is interned per path", "a different handle")
  check(type(a:text()) == "string" and #a:text() > 0, "it hands back the file's text", a:text())
  refuses("an unsupported extension is refused, listing the ones that load",
          function() hafen.asset():get("sheet.yaml") end, ".json/.txt (data)")

  -- 2. the sheet IS data: file -> hafen.json():parse -> sheet:load. A JSON array arrives as the sheet's own
  --    positional colour shape, so nothing between the file and the client converts a thing.
  local doc = hafen.json():parse(a:text())
  local rules = sheetOf(doc)
  check(doc.rules.chat.color[1] == 90, "a JSON colour array arrives 1-indexed, as the sheet's own shape",
        doc.rules.chat.color[1])
  check(pcall(function() sheet:load(rules):install() end), "a sheet built from a JSON file applies")

  -- 3. the C1a contract the docs publish: a site key, the `*` cascade, an inert tree key.
  check(pcall(function() sheet:rule("chat"):color(200, 210, 220) end), "a site key is accepted")
  check(pcall(function() sheet:rule("*"):color(200, 210, 220) end), "the `*` cascade is accepted")
  check(pcall(function() sheet:rule("@Inventory"):color(200, 210, 220) end),
        "a tree key is accepted and inert (C1b resolves it)")
  refuses("an unknown property is refused",
          function() sheet:load{ ["chat"] = { colour = { 1, 2, 3 } } }:install() end, "not a style property")
  refuses("a typo inside a TREE key's rule is refused too -- what is deferred is the key, not the rule",
          function() sheet:load{ ["@Inventory"] = { fnt = 1 } }:install() end, "not a style property")
  refuses("a malformed key errors exactly as hafen.ui():find(sel) does",
          function() sheet:load{ ["window["] = { color = { 1, 2, 3 } } }:install() end, "window[")

  -- 4. the hard cut, and what survived it.
  eq("the hard cut holds: hafen.font.setFont", hafen.font.setFont, nil)
  eq("the hard cut holds: hafen.font.reset", hafen.font.reset, nil)
  eq("the hard cut holds: hafen.font.scopes", hafen.font.scopes, nil)
  check(hafen.font():get("serif") ~= nil, "hafen.font():get(name) still names an engine font", nil)

  -- 5. drop it: this suite runs on every login and must leave the client stock.
  check(pcall(function() sheet:drop() end), "sheet:drop() drops this addon's sheet")

  manualCheck("enable the `theme` addon, then type ':theme on', ':theme dump', ':theme off'",
              "on = the client restyles live from theme.json (serif body, mono green chat, warm tooltips);"
              .. " dump = one line per rule; off = stock again. Disabling the addon also restores stock")
  manualCheck("with ':theme on', run  :lua hafen.ui():sheet():rule('chat'):color(255,80,80):sheet():install()"
              .. "  then  :lua hafen.ui():sheet():drop()",
              "chat turns RED (the last sheet applied wins), then falls back to the theme's GREEN"
              .. " -- not to stock; ':theme off' then leaves stock")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. That is also what
-- removed the whole class of login races between suites -- each one installs a client-wide sheet and bumps
-- Fonts.gen() while it runs, so two rounds overlapping reddened lines in the OTHER suite.
hafen.slash():register("t033-3", run)                                       -- the only way in
