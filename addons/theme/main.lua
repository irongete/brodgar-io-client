-- theme — an EXAMPLE addon (033-ui-stylesheet C1a, extended by 035-ui-chrome C2): a client theme that is
-- DATA, not code.
--
-- Everything this addon LOOKS like lives in theme.json, next to this file. Nothing below names a surface, a
-- font, a size, a colour or a pixel: it reads the file, maps the values JSON cannot hold to handles, and hands
-- the whole table to hafen.ui.skin{…}. To make a different theme you edit the JSON — that is the claim this
-- addon exists to prove, and it is why the loop over the rules never looks at a key.
--
-- Three ordinary doors, one line each:
--   hafen.asset("theme.json")   the file THIS addon ships — a "data" asset, read as UTF-8 (:text()), sandboxed
--                               like every asset (absolute paths and ".." are rejected, D-017)
--   hafen.json.parse(text)      the text → a Lua table. A JSON array is 1-indexed, so [190,210,190] arrives as
--                               the sheet's own positional colour shape and needs no conversion at all.
--   hafen.ui.skin{…}            the sheet: [selector] = {properties}, applied live and OWNED by this addon —
--                               :reload/disable drops it and the stock client comes back.
--
-- EXACTLY TWO values in a rule are things JSON cannot carry, and both for the same reason: they are HANDLES.
-- A font's `face` — a bare name ("serif") is one of the client's built-ins, anything with a dot
-- ("fonts/demo.ttf") is a FILE this addon ships — and a border's or background's `image`, always a file. Both
-- go through hafen.asset, the same door theme.json itself came through. Everything else in a rule — a colour
-- array, a 9-slice's four insets, a pad in pixels — is already the sheet's own shape and is copied untouched,
-- which is why the whole chrome half of this theme (the window frame, its title band, the panels) costs the
-- three lines of ruleOf() below and no more.
--
-- ...AND THE LAYOUT HALF COSTS NONE (036-ui-layout, feature E). A theme also says WHERE the client's windows
-- sit: `anchor = {to = "screen", at = "bottomright", offset = {-8, -8}}` on a selector that names a window.
-- There is nothing to map — an anchor's corner is a string, its offset is two numbers, and `pos`/`size` are the
-- same {x, y} arrays a colour and a slice already are — so the layout rules travel through ruleOf() verbatim
-- and this file gained not one line for them. A handle is the only thing an adapter is ever for.
--
-- SAVING A LAYOUT IS THIS ADDON'S OWN BUSINESS, and that is the point of the store half below. The engine ships
-- no profiles: a sheet is plain data and hafen.store is account-wide JSON, so "remember where I put my windows"
-- is a dozen lines here rather than a second store beside the one every addon already has.
--
--   ':theme save'    where each themed window is RIGHT NOW becomes a pinned `pos` for it, kept in
--                    hafen.store.layout (account scope) and re-applied on every ':theme on', on every character.
--   ':theme forget'  drop the pins and fall back to the file's own anchors.
--
-- An anchor HOLDS and a pin lets go: an anchored window is re-derived every tick, so dragging it snaps back,
-- while a saved `pos` is written once and the window is yours to drag until you save again. That is not a mode
-- this addon invents — it is what the two spellings of the one property mean (see the API docs' anchor section).
--
-- DORMANT until you ask for it: ':theme on' applies the sheet, ':theme off' drops it. A theme installed at login
-- would restyle the whole client on every login, which is not something an example addon should decide for you.
-- Assets are interned, so editing theme.json takes effect on ':reload' (which rebuilds the addon layer).
--
-- SAFE-tier: cosmetic and client-side, declares no permissions, sends nothing to the server.
-- See docs/addons/api/ui.md#the-stylesheet--restyling-the-client and docs/addons/api/asset.md.

local FILE = "theme.json"

local name          -- the theme's display name, from the JSON
local sheet         -- the built sheet: [selector] = { font = <handle>, color = {r,g,b}, bg/border/pad … }
local count = 0     -- how many rules it carries
local on = false    -- is our sheet currently installed?

-- A font descriptor's `face` → a font handle. One of the two places a string becomes something JSON cannot hold.
local function faceOf(face)
  if type(face) ~= "string" then
    error(FILE .. ": a font's `face` is a string — a built-in name (\"serif\") or a path to a font this addon"
      .. " ships (\"fonts/demo.ttf\"); got " .. type(face))
  end
  if face:find("%.") then
    return hafen.asset(face)        -- a FILE this addon ships — the same door theme.json came through
  end
  return hafen.font(face)           -- one of the client's built-ins: sans / serif / mono / fraktur
end

-- { face = "serif", size = 11, bold = true, … } → a handle. size/bold/italic/aa are :derive options, never
-- part of the load — so a theme names a face once and varies it per rule.
local function fontOf(f)
  local h = faceOf(f.face)
  local opts, any = {}, false
  for _, k in ipairs({ "size", "bold", "italic", "aa" }) do
    if f[k] ~= nil then opts[k], any = f[k], true end
  end
  if any then h = h:derive(opts) end
  return h
end

-- A rule as the JSON holds it → a rule the sheet accepts. The default is to copy: a colour array, a 9-slice's
-- four insets and a pad are already the sheet's own shapes, so they travel verbatim (which also means a typo in
-- the file is refused by the sheet, naming the property — the file is not pre-filtered into silence). What is
-- mapped is only where a HANDLE has to stand: the font's face, and an image path.
local function ruleOf(props)
  local rule = {}
  for k, v in pairs(props) do rule[k] = v end
  if props.font then rule.font = fontOf(props.font) end
  if props.bg and props.bg.image then rule.bg = { image = hafen.asset(props.bg.image) } end
  if props.border then rule.border = { image = hafen.asset(props.border.image), slice = props.border.slice } end
  return rule
end

-- Read theme.json and build the sheet. Note what is NOT here: no key is inspected and no property is named
-- except the two that carry a handle, so a theme may style any surface the client's grammar accepts, with any
-- property this client ships — the file decides, not this file.
local function build()
  local doc = hafen.json.parse(hafen.asset(FILE):text())
  local rules, n = {}, 0
  for key, props in pairs(doc.rules or {}) do
    rules[key], n = ruleOf(props), n + 1
  end
  return doc.name or "(unnamed)", rules, n
end

-- ---- the layout half: the file places the windows, and the STORE pins them ---------------------------
--
-- Everything below is the profile system the engine deliberately does not ship. It is short because a sheet is
-- plain data: a saved layout is { [selector] = {x, y} } and applying it is one more rule on top of the file's.

-- Does this rule say where its widget goes? (`pos` and `anchor` are one property in two spellings, so a rule
-- carrying either is one this addon can pin -- and a rule saying BOTH is an error the sheet refuses.)
local function places(rule)
  return (rule.pos ~= nil) or (rule.anchor ~= nil)
end

-- The saved layout: { [selector] = {x = , y = } }, account scope, so it is ready in OnLoad and shared by every
-- character. The table object is stable for this addon's whole life (a restore refills it in place), so it is
-- written THROUGH rather than replaced.
local function pins()
  local t = hafen.store.layout
  if t == nil then                  -- declared in manifest.json; this is belt and braces for a hand-edited one
    t = {}
    hafen.store.layout = t
  end
  return t
end

-- The sheet as it is actually installed: the file's rules, with a pinned position REPLACING the file's own
-- placement for any selector the user has saved. The pin is written as `pos` because that is what it is -- an
-- absolute point they chose by dragging -- and it takes the anchor's slot rather than sitting beside it.
local function effective()
  local saved = pins()
  if next(saved) == nil then return sheet end
  local out = {}
  for key, rule in pairs(sheet) do out[key] = rule end
  for key, p in pairs(saved) do
    local rule = {}
    for k, v in pairs(out[key] or {}) do rule[k] = v end
    rule.anchor, rule.pos = nil, { x = p.x, y = p.y }
    out[key] = rule
  end
  return out
end

local function apply(want)
  if want then
    hafen.ui.skin(effective())
  else
    hafen.ui.skin(nil)              -- every surface it styled falls back — to another addon's sheet, else stock
  end
  on = want
  hafen.log(("theme: '%s' (%d rules) is now %s"):format(name, count, on and "ON" or "OFF"))
end

-- ':theme save' -- read every laid-out window's CURRENT position back through the same API that placed it, and
-- keep it. Nothing here knows what the file said: where the window is now is the whole truth, whether it got
-- there from an anchor, from a previous pin, or from the user dragging it afterwards.
local function saveLayout()
  local saved, n = pins(), 0
  for key, rule in pairs(sheet) do
    if places(rule) then
      local w = hafen.ui(key)
      if w ~= nil then
        local p = w:pos()
        saved[key] = { x = p.x, y = p.y }
        n = n + 1
      end
    end
  end
  hafen.store.flush()
  if on then apply(true) end        -- re-install so the pins take over from the anchors immediately
  hafen.log(("theme: saved the layout of %d window%s (account-wide). They are pinned now, so you can drag them"
    .. " -- ':theme save' again to keep where you put them, ':theme forget' to go back to %s's own anchors.")
    :format(n, (n == 1) and "" or "s", FILE))
end

-- How ':theme dump' says where a rule puts its widget. Both spellings of the one property, and both spellings of
-- a coordinate: the file writes [40, 200] and a pin writes {x = , y = }, exactly as the sheet accepts either.
local function whereOf(rule)
  local p = rule.pos
  if p ~= nil then
    return ("pinned %s,%s"):format(tostring(p.x or p[1]), tostring(p.y or p[2]))
  end
  local a = rule.anchor
  if a ~= nil then
    local o = a.offset
    return ("%s of %s%s"):format(tostring(a.at or "topleft"), tostring(a.to or "screen"),
      o and ((" %+d,%+d"):format(o.x or o[1], o.y or o[2])) or "")
  end
  return "-"
end

local function forgetLayout()
  local saved = pins()
  for key in pairs(saved) do saved[key] = nil end
  hafen.store.flush()
  if on then apply(true) end
  hafen.log("theme: forgot the saved layout -- the windows go back where " .. FILE .. " anchors them")
end

hafen.events.on("OnLoad", function()
  on = false                        -- a reload rebuilt the env and tore the sheet down with it (owned resource)
  local ok, err = pcall(function() name, sheet, count = build() end)
  if not ok then
    sheet, count = nil, 0
    hafen.log("theme: could not load " .. FILE .. " -- " .. tostring(err))
    return
  end
  local n = 0
  for _ in pairs(pins()) do n = n + 1 end
  hafen.log(("theme: '%s' loaded from %s -- %d rules%s, nothing applied yet. ':theme on' to wear it,"
    .. " ':theme off' to take it off, ':theme dump' to list what it styles, ':theme save' / ':theme forget'"
    .. " for where its windows sit."):format(name, FILE, count,
      (n > 0) and (" + " .. n .. " saved window position" .. ((n == 1) and "" or "s")) or ""))
end)

hafen.slash.register("theme", function(args)
  if not sheet then hafen.log("theme: nothing loaded (see the error at login)"); return end
  local sub = (args and args[1]) or ""
  if sub == "on" then
    apply(true)
  elseif sub == "off" then
    apply(false)
  elseif sub == "save" then
    saveLayout()
  elseif sub == "forget" then
    forgetLayout()
  elseif sub == "dump" then
    hafen.log(("theme: '%s' from %s"):format(name, FILE))
    for key, rule in pairs(effective()) do
      hafen.log(("  [\"%s\"] font=%s color=%s bg=%s border=%s pad=%s where=%s"):format(key,
        rule.font and (rule.font:family() .. "/" .. tostring(rule.font:size() or "stock")) or "-",
        rule.color and ("{" .. table.concat(rule.color, ",") .. "}") or "-",
        rule.bg and (rule.bg.image and rule.bg.image:path()
                     or ("{" .. table.concat(rule.bg.color, ",") .. "}")) or "-",
        rule.border and (rule.border.image:path()
                         .. " {" .. table.concat(rule.border.slice, ",") .. "}") or "-",
        rule.pad or "-", whereOf(rule)))
    end
  else
    hafen.log(("theme: '%s', %d rules, currently %s -- ':theme on' / ':theme off' / ':theme dump' /"
      .. " ':theme save' / ':theme forget'"):format(name, count, on and "ON" or "OFF"))
  end
end)
