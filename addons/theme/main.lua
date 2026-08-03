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

local function apply(want)
  if want then
    hafen.ui.skin(sheet)
  else
    hafen.ui.skin(nil)              -- every surface it styled falls back — to another addon's sheet, else stock
  end
  on = want
  hafen.log(("theme: '%s' (%d rules) is now %s"):format(name, count, on and "ON" or "OFF"))
end

hafen.events.on("OnLoad", function()
  on = false                        -- a reload rebuilt the env and tore the sheet down with it (owned resource)
  local ok, err = pcall(function() name, sheet, count = build() end)
  if not ok then
    sheet, count = nil, 0
    hafen.log("theme: could not load " .. FILE .. " -- " .. tostring(err))
    return
  end
  hafen.log(("theme: '%s' loaded from %s -- %d rules, nothing applied yet. ':theme on' to wear it, ':theme off'"
    .. " to take it off, ':theme dump' to list what it styles."):format(name, FILE, count))
end)

hafen.slash.register("theme", function(args)
  if not sheet then hafen.log("theme: nothing loaded (see the error at login)"); return end
  local sub = (args and args[1]) or ""
  if sub == "on" then
    apply(true)
  elseif sub == "off" then
    apply(false)
  elseif sub == "dump" then
    hafen.log(("theme: '%s' from %s"):format(name, FILE))
    for key, rule in pairs(sheet) do
      hafen.log(("  [\"%s\"] font=%s color=%s bg=%s border=%s pad=%s"):format(key,
        rule.font and (rule.font:family() .. "/" .. tostring(rule.font:size() or "stock")) or "-",
        rule.color and ("{" .. table.concat(rule.color, ",") .. "}") or "-",
        rule.bg and (rule.bg.image and rule.bg.image:path()
                     or ("{" .. table.concat(rule.bg.color, ",") .. "}")) or "-",
        rule.border and (rule.border.image:path()
                         .. " {" .. table.concat(rule.border.slice, ",") .. "}") or "-",
        rule.pad or "-"))
    end
  else
    hafen.log(("theme: '%s', %d rules, currently %s -- ':theme on' / ':theme off' / ':theme dump'")
      :format(name, count, on and "ON" or "OFF"))
  end
end)
