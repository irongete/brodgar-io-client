-- Themes -- a whole client look, loaded from a file rather than written in Lua.
--
-- Every value a stylesheet rule takes has a spelling a file can carry: a colour is a table of numbers, a
-- face is named rather than handed over, a picture is named by its resource or by a path inside this
-- folder. So a theme needs no Lua at all -- it is a JSON document, and this addon is the two lines that
-- read one and install it:
--
--     local doc = hafen.json():parse(hafen.asset():get("themes/x.json"):text())
--     hafen.ui():sheet():load(doc.rules):install()
--
-- What is left over is bookkeeping: which files there are, which one is on, and remembering that across
-- a restart. The sheet itself is owned by this addon, so `:reload`, disabling it or `:theme off` puts the
-- client's own look back to the pixel.

-- ------------------------------------------------------------------ where the themes are
--
-- hafen.asset() loads a file by path and interns it; it does not enumerate a folder, and there is no verb
-- anywhere in the API that reads a directory -- an addon reads its own files, it does not browse them. So
-- the folder carries its own index, and adding a theme is two edits: drop the .json in, name it in
-- index.json. That is also what makes the order the list comes out in the author's rather than the
-- filesystem's.
local FOLDER = "themes/"
local INDEX  = FOLDER .. "index.json"

-- The word that gives the look back instead of naming a theme. A file that would claim it is skipped: the
-- release has to stay reachable, or a broken theme could only be undone by editing files.
local RELEASE = "off"

-- ------------------------------------------------------------------ state

local themes  = {}    -- name -> { name, file, title, description, rules }
local order   = {}    -- the names, in the index's own order
local active  = nil   -- the name installed by this addon, or nil for the client's own look
local settings = hafen.store():get("settings")   -- account-wide; filled before this file runs

-- ------------------------------------------------------------------ reading the folder

local function read(path)
  local ok, res = pcall(function() return hafen.json():parse(hafen.asset():get(path):text()) end)
  if not ok then return nil, tostring(res) end
  if type(res) ~= "table" then return nil, "the document is not an object" end
  return res
end

-- The name a theme is typed by is its FILE, minus the extension -- so what the user types is what is on
-- disk, and two themes cannot collide over a `name` field either of them is free to write.
local function nameOf(file)
  return (string.gsub(string.lower(file), "%.json$", ""))
end

local function load()
  themes, order = {}, {}
  local index, err = read(INDEX)
  if index == nil then
    hafen.log():write("themes: cannot read " .. INDEX .. " -- " .. err)
    return
  end
  local files = index.themes
  if type(files) ~= "table" then
    hafen.log():write("themes: " .. INDEX .. " names no themes -- it is"
      .. ' { "themes": ["default.json"] }, an array of the files in this folder')
    return
  end
  for i = 1, #files do
    local file = files[i]
    local name = (type(file) == "string") and nameOf(file) or nil
    if name == nil then
      hafen.log():write("themes: " .. INDEX .. "[" .. i .. "] is not a file name")
    elseif name == RELEASE then
      hafen.log():write('themes: "' .. file .. '" is skipped -- ":theme ' .. RELEASE
        .. '" is how the client\'s own look is given back, so a theme cannot be called that')
    elseif themes[name] ~= nil then
      hafen.log():write('themes: "' .. file .. '" is skipped -- "' .. name .. '" is already loaded')
    else
      local doc, derr = read(FOLDER .. file)
      if doc == nil then
        hafen.log():write('themes: "' .. file .. '" did not load -- ' .. derr)
      elseif type(doc.rules) ~= "table" then
        hafen.log():write('themes: "' .. file .. '" carries no "rules" object -- a theme is'
          .. ' { "name": …, "rules": { "<selector>": { <property>: <value> } } }')
      else
        themes[name] = {
          name        = name,
          file        = file,
          title       = (type(doc.name) == "string") and doc.name or name,
          description = (type(doc.description) == "string") and doc.description or nil,
          rules       = doc.rules,
        }
        order[#order + 1] = name
      end
    end
  end
end

-- ------------------------------------------------------------------ installing one
--
-- An addon owns exactly one sheet, so `:load` replaces what it said and `:install()` is the moment it
-- becomes what the client looks like. A rule this theme does not name falls back on the spot, which is why
-- switching themes needs no undo step: the second install is the whole of it.

local function install(name)
  local t = themes[name]
  if t == nil then return false, 'no theme called "' .. name .. '"' end
  local ok, err = pcall(function() hafen.ui():sheet():load(t.rules):install() end)
  if not ok then
    -- A refused rule leaves the sheet holding half a theme, and half a theme is not a look anyone asked
    -- for. Give it back rather than leaving the client wearing it.
    pcall(function() hafen.ui():sheet():release() end)
    active = nil
    return false, tostring(err)
  end
  active = name
  return true
end

local function release()
  hafen.ui():sheet():release()
  active = nil
end

local function remember(name)
  settings.theme = name        -- nil is a valid answer: the user asked for the client's own look
  hafen.store():flush()
end

-- ------------------------------------------------------------------ the command

local function list()
  if #order == 0 then
    hafen.log():write("themes: no theme loaded -- see " .. INDEX)
    return
  end
  hafen.log():write("themes: " .. #order .. " in " .. FOLDER)
  for i = 1, #order do
    local t = themes[order[i]]
    hafen.log():write(("  %s %-14s %s"):format((active == t.name) and "*" or " ", t.name,
                                               t.description or t.title))
  end
  hafen.log():write("  :theme <name> installs one, :theme " .. RELEASE
    .. " gives the client's own look back")
end

hafen.console():on("theme", function(args)
  local want = args[1]
  if want == nil then
    list()
  elseif string.lower(want) == RELEASE then
    release()
    remember(nil)
    hafen.log():write("themes: released -- the client's own look, to the pixel")
  else
    local name = string.lower(want)
    local ok, err = install(name)
    if ok then
      remember(name)
      hafen.log():write('themes: "' .. themes[name].title .. '" installed')
    else
      hafen.log():write("themes: " .. err)
      if themes[name] == nil then list() end
    end
  end
end)

-- ------------------------------------------------------------------ start
--
-- The files are read once every `Load` -- which is every `:reload` too, so editing a theme and reloading
-- is the whole edit loop. The remembered theme is re-installed then: a sheet lives as long as the addon
-- does, so it has to be said again after a reload.

hafen.event():on("Load", function()
  load()
  local want = settings.theme
  if type(want) ~= "string" then return end
  local ok, err = install(want)
  if not ok then
    hafen.log():write('themes: "' .. want .. '" was remembered but ' .. err)
  end
end)
