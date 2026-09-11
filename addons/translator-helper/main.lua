-- Translator helper -- displays the client in the language you pick, collects every string that language
-- does not name yet, and takes each translation from a text entry. The catalogue is installed the whole
-- time, so a translation is on screen the moment it is saved. What it builds is one catalogue document per
-- language, in the very shape hafen.locale():load(doc) takes -- the file a translation addon ships as
-- <language>.json -- kept in savedata/account/translator-helper.json under the language's code.

local LANGS = {                          -- the option's choices, in this order
  { name = "English", code = nil  },     -- the client's own words: nothing installed, nothing collected
  { name = "Spanish", code = "es" },
  { name = "Russian", code = "ru" },
  { name = "Chinese", code = "zh" },
}
local VIEWS    = { "Pending", "Translated", "Ignored", "All" }
local MAX_ROWS = 500                     -- the listbox takes 4096; past this the status line says to narrow the filter
local RESET_AT = 384                     -- re-install before the 512-pair miss set fills and stops recording
local WIDTH    = 600                     -- the panel's width, pinned, so a long string clips instead of widening it
local FREE     = { "chat", "chat.mine", "chat.private", "chat.party", "world.nick", "world.speech" }  -- player text

-- The window is drawn by the same client it watches, so its own captions reach the catalogue like any
-- other label. The fixed ones are simply never collected; the rows, the source line and the status line
-- change all the time and would fill the miss set with themselves, so the installed catalogue names them
-- by their shape -- as themselves, and never in the file.
local MINE = {
  default = { "Language", "Show", "Filter", "Match", "Pick a row", "collecting",
              "Pending", "Translated", "Ignored", "All",
              "English", "Spanish", "Russian", "Chinese",
              "Language the client displays, and the target of the list",
              ":translator opens the window",
              "The toggle key is in Options > Game > Keybindings > Translator helper" },
  button = { "Pattern", "Save", "Delete", "Ignore", "Restore" },
  ["window.title"] = { "Translator helper" },
}
local MINE_PATTERNS = {
  { surface = "default", match = "\\[(\\*|[a-z]+(?:\\.[a-z]+)*)\\] (?s)(.*)", text = "[%1$s] %2$s" },
  { surface = "default", match = "(english|es|ru|zh) -- (?s)(.*)", text = "%1$s -- %2$s" },
}

local opts  = hafen.client():options():addon()
local names = {}
for i, l in ipairs(LANGS) do names[i] = l.name end
local language = opts:choice("language"):choices(names):default("English"):add()

-- The list is not saved: only a translation is. What the client has drawn since the addon loaded is
-- collected again as you play, and a string put aside is put aside for this session.
local strings  = {}                              -- every (surface, text) seen this session: strings[surface][text] = true
local ignored  = {}                              -- the ones not to translate, the same shape
local settings = hafen.store():get("settings")   -- where the window stands, and whether it was open

local current                            -- the code in force, nil while English is displayed
local win, box, entry, source, status, viewBox, filterBox
local matchLine, matchBox, patternBtn, editLine, deleteBtn, ignoreBtn
local selected                           -- {surface=, text=} the string the user picked, or {surface=, pattern=} a pattern
local shownRows, rowKeys = {}, {}        -- the rows on screen, and each row's (surface, text)
local lastMisses = -1                    -- the miss count the last harvest read, so an unchanged set is not walked

-- ---------------------------------------------------------------- the documents

local function codeOf(name)
  for _, l in ipairs(LANGS) do
    if l.name == name then return l.code end
  end
  return nil
end

local function docOf(code)               -- the language's live document, in the store
  local d = hafen.store():get(code)
  if not d.text then d.text = {} end
  return d
end

local function catalogue(code)           -- what is installed: the document, plus what is not to be collected
  local d = docOf(code)
  local text = {}
  for s, entries in pairs(d.text) do
    local m = {}
    for en, tr in pairs(entries) do m[en] = tr end
    text[s] = m
  end
  for s, set in pairs(ignored) do        -- an ignored string is said as itself: drawn unchanged, and never a miss
    if not text[s] then text[s] = {} end
    for en in pairs(set) do
      if text[s][en] == nil then text[s][en] = en end
    end
  end
  local pattern = {}
  for i, p in ipairs(d.pattern or {}) do pattern[i] = p end
  for _, s in ipairs(FREE) do            -- what a player wrote is nobody's to translate
    pattern[#pattern + 1] = { surface = s, match = "(?s)(.*)", text = "%1$s" }
  end
  for _, p in ipairs(MINE_PATTERNS) do   -- and neither is what this window draws
    pattern[#pattern + 1] = p
  end
  return { text = text, pattern = pattern }
end

local function shaped(s, t)              -- a row, the source line or the status line, by shape
  return s == "default" and (string.find(t, "^%[[%a%.%*]+%] ") ~= nil
    or string.find(t, "^%a+ %-%- ") ~= nil)
end

local function mine(s, t)                -- a string the window itself draws: never collected
  local fixed = MINE[s]
  if fixed then
    for _, m in ipairs(fixed) do
      if m == t then return true end
    end
  end
  return shaped(s, t)
end

local function purge(set, pred)          -- what an earlier round let into a document
  for s, bucket in pairs(set) do
    for t in pairs(bucket) do
      if pred(s, t) then bucket[t] = nil end
    end
  end
end

local function flagged(set, s, t)
  return set[s] ~= nil and set[s][t] ~= nil
end

local function flag(set, s, t, on)
  if on then
    if not set[s] then set[s] = {} end
    set[s][t] = true
  elseif set[s] then
    set[s][t] = nil
  end
end

local function translation(s, t)         -- the exact entry that answers the string: the surface's, then "*"'s
  if not current then return nil end
  local d = docOf(current)
  local m = d.text[s]
  if m and m[t] then return m[t] end
  m = d.text["*"]
  return m and m[t] or nil
end

local compiled = {}                      -- match source -> the compiled pattern, or false where regex.lua reads none

local function patternFor(s, t)          -- the first pattern of the language that answers the string, or nil
  if not current then return nil end
  for i, p in ipairs(docOf(current).pattern or {}) do
    if p.surface == s or p.surface == "*" then
      local prog = compiled[p.match]
      if prog == nil then
        prog = regex.compile(p.match) or false
        compiled[p.match] = prog
      end
      if prog and regex.matches(prog, t) then return i end
    end
  end
  return nil
end

local refusal                            -- what the last :load(doc) said no to, for the status line

local function reload()                  -- an installed catalogue says the new document at once
  if not current then return true end
  local ok, err = pcall(function() hafen.locale():load(catalogue(current)) end)
  if not ok then
    refusal = string.gsub(tostring(err), "\nstack traceback:.*$", "")
    for _ = 1, 3 do                      -- the "@main.lua:162 " a bridge refusal is prefixed with
      refusal = string.gsub(refusal, "^@?.-%.lua:%d+:?%s*", "")
    end
    hafen.log():write("the " .. current .. " catalogue was refused: " .. refusal)
  end
  return ok
end

-- ---------------------------------------------------------------- the rows

local function oneline(t)
  return (string.gsub(t, "[\r\n]+", " "))
end

local function clip(s, n)                -- at most n bytes, never cutting a UTF-8 character in two
  if #s <= n then return s end
  local cut = n
  while cut > 1 do
    local b = string.byte(s, cut + 1)
    if b == nil or b < 0x80 or b >= 0xC0 then break end
    cut = cut - 1
  end
  return string.sub(s, 1, cut) .. "..."
end

local function rowOf(s, t)
  return "[" .. s .. "] " .. oneline(t)
end

local function patternRow(p)             -- a pattern's own row: its match between slashes
  return "[" .. p.surface .. "] /" .. oneline(p.match) .. "/"
end

local function known()                   -- what the list is over: the strings seen this session, and every
  local all = {}                         -- string the language's document names, seen or not
  for s, set in pairs(strings) do
    all[s] = {}
    for t in pairs(set) do all[s][t] = true end
  end
  if current then
    for s, entries in pairs(docOf(current).text) do
      if not all[s] then all[s] = {} end
      for t in pairs(entries) do all[s][t] = true end
    end
  end
  return all
end

local function collect()                 -- the rows the view and the filter keep, sorted by surface then text
  local view   = viewBox and viewBox:value() or "Pending"
  local needle = string.lower(filterBox and filterBox:value() or "")
  local all = known()
  local surfaces = {}
  for s in pairs(all) do surfaces[#surfaces + 1] = s end
  table.sort(surfaces)
  local rows, keys, total = {}, {}, 0
  local counts = { Pending = 0, Translated = 0, Ignored = 0, Patterns = 0 }
  local function keep(row, key)
    if needle == "" or string.find(string.lower(row), needle, 1, true) then
      total = total + 1
      if total <= MAX_ROWS then
        rows[#rows + 1] = row
        keys[row] = key
      end
    end
  end
  if current then                        -- the patterns first, in the order they answer in
    local pats = docOf(current).pattern or {}
    counts.Patterns = #pats
    if view == "All" or view == "Translated" then
      for i, p in ipairs(pats) do keep(patternRow(p), { surface = p.surface, pattern = i }) end
    end
  end
  for _, s in ipairs(surfaces) do
    local texts = {}
    for t in pairs(all[s]) do texts[#texts + 1] = t end
    table.sort(texts)
    for _, t in ipairs(texts) do
      local state = flagged(ignored, s, t) and "Ignored"
        or ((translation(s, t) or patternFor(s, t)) and "Translated" or "Pending")
      counts[state] = counts[state] + 1
      if view == "All" or view == state then keep(rowOf(s, t), { surface = s, text = t }) end
    end
  end
  return rows, keys, total, counts
end

-- The edit line shows what the catalogue says for the picked row: an exact entry (Match empty, the entry
-- holding the translation), a pattern (Match holding its match, the entry its text), or nothing yet (Match
-- empty, the entry holding the English). Save writes what the line shows; Delete removes it.
local function show(k)
  selected = k
  if not k then
    source:text("Pick a row")
    matchBox:value("")
    entry:value("")
    ignoreBtn:text("Ignore"):enabled(false)
    deleteBtn:enabled(false)
    patternBtn:enabled(false)
    return
  end
  if not k.text then                     -- a pattern's own row
    local p = docOf(current).pattern[k.pattern]
    source:text(clip(patternRow(p), 90))
    matchBox:value(p.match)
    entry:value(p.text)
    ignoreBtn:text("Ignore"):enabled(false)
    deleteBtn:enabled(true)
    patternBtn:enabled(false)
    return
  end
  local tr = translation(k.surface, k.text)
  local pi = (not tr) and patternFor(k.surface, k.text) or nil
  k.pattern = pi                         -- the pattern that answers this string, if one does: Save edits it
  source:text(clip(rowOf(k.surface, k.text), 90))   -- the row's own shape, which the catalogue names as itself
  if pi then
    local p = docOf(current).pattern[pi]
    matchBox:value(p.match)
    entry:value(p.text)
  else
    matchBox:value("")
    entry:value(tr or k.text)
  end
  ignoreBtn:text(flagged(ignored, k.surface, k.text) and "Restore" or "Ignore"):enabled(true)
  deleteBtn:enabled(tr ~= nil or pi ~= nil)          -- there is something to delete
  patternBtn:enabled(true)
end

local function refresh(note)             -- note: one line for the status, instead of the counts, this once
  if not (win and win:exists()) then return end
  local rows, keys, total, counts = collect()
  local same = (#rows == #shownRows)
  if same then
    for i = 1, #rows do
      if rows[i] ~= shownRows[i] then same = false; break end
    end
  end
  if not same then
    local pick = box:value()
    box:rows(rows)                                   -- replaces the set, and clears the pick with it
    shownRows, rowKeys = rows, keys
    if pick and keys[pick] then                      -- ...so it is put back where the row still stands
      box:value(pick)
    elseif pick then
      show(nil)
    end
  end
  editLine:enabled(current ~= nil)
  matchLine:enabled(current ~= nil)
  local line = (current or "english") .. " -- " .. counts.Pending .. " pending, " .. counts.Translated
    .. " translated, " .. counts.Patterns .. " patterns, " .. counts.Ignored .. " ignored"
  if total > MAX_ROWS then
    line = line .. " -- showing " .. MAX_ROWS .. " of " .. total .. ": narrow the filter"
  end
  if note then line = (current or "english") .. " -- " .. clip(oneline(note), 110) end   -- the same shape
  status:text(line)
end

local function pick(row)                 -- select a row as the user would, and show it
  if row and rowKeys[row] then
    box:value(row)
    show(rowKeys[row])
  else
    show(nil)
  end
end

local function indexOf(row)
  for i, r in ipairs(shownRows) do
    if r == row then return i end
  end
  return nil
end

local function reselect(row, idx)        -- the row itself if it still stands, else the one that took its slot
  if rowKeys[row] then return pick(row) end
  local nxt = idx and shownRows[idx] or nil
  pick(nxt or shownRows[#shownRows])
end

-- ---------------------------------------------------------------- the edits

local function rowOfSelected()           -- the picked row as the list spells it now, and where it stands
  local row
  if selected.text then
    row = rowOf(selected.surface, selected.text)
  else
    row = patternRow(docOf(current).pattern[selected.pattern])
  end
  return row, indexOf(row)
end

local function settle(row, idx, note)    -- after an edit: the rows, the pick, and the disk
  refresh(note)
  reselect(row, idx)
  hafen.store():flush()
end

local function save(text)
  if not (selected and current) or text == "" then return end   -- nothing to say is nothing to save
  local d = docOf(current)
  local s, match = selected.surface, matchBox:value()
  local row, idx = rowOfSelected()
  if match ~= "" then                    -- a pattern: the one the row shows, or a new one after the last
    local p = { surface = s, match = match, text = text }
    local i, before = selected.pattern, nil
    if not d.pattern then d.pattern = {} end
    if i then before = d.pattern[i]; d.pattern[i] = p else d.pattern[#d.pattern + 1] = p; i = #d.pattern end
    if not reload() then                 -- refused: the document goes back as it was, and the status says why
      if before then d.pattern[i] = before else table.remove(d.pattern, i) end
      if #d.pattern == 0 then d.pattern = nil end
      return refresh(refusal)
    end
    if selected.text then row = rowOf(s, selected.text) else row = patternRow(p) end
  else
    if not selected.text then return end -- a pattern's row with its match wiped: nothing to write
    if not d.text[s] then d.text[s] = {} end
    d.text[s][selected.text] = text
    reload()
  end
  settle(row, idx)
end

local function delete()                  -- what answers the row goes; a string stays in the list
  if not (selected and current) then return end
  local d = docOf(current)
  local s = selected.surface
  local row, idx = rowOfSelected()
  if selected.pattern then
    table.remove(d.pattern, selected.pattern)
    if #d.pattern == 0 then d.pattern = nil end
  elseif d.text[s] and d.text[s][selected.text] then
    d.text[s][selected.text] = nil
  elseif d.text["*"] and d.text["*"][selected.text] then
    d.text["*"][selected.text] = nil
  else
    return
  end
  reload()
  settle(row, idx)
end

local function ignore()
  if not (selected and selected.text) then return end
  local s, t = selected.surface, selected.text
  local row, idx = rowOfSelected()
  flag(ignored, s, t, not flagged(ignored, s, t))
  reload()
  settle(row, idx)
end

local function pattern()                 -- start a pattern from the picked string: its English, escaped
  if not (selected and selected.text) then return end
  matchBox:value(regex.quote(selected.text))
end

-- ---------------------------------------------------------------- the language

local function apply(name)               -- runs where the option was written, so the window waits for the step
  current = codeOf(name)
  lastMisses = -1
  local loc = hafen.locale()
  if current then
    local ok, err = pcall(function() loc:load(catalogue(current)):install() end)
    if not ok then hafen.log():write("the " .. current .. " catalogue was refused: " .. tostring(err)) end
  else
    loc:release()
  end
  hafen.timer():after(0, refresh)
end

local function harvest()                 -- every second: what missed goes into the list
  if win and win:exists() and win:visible() then
    local p = win:position()
    settings.x, settings.y = p.x, p.y
  end
  if not current then return end
  local loc  = hafen.locale()
  local info = loc:info()
  if not info.installed or info.misses == lastMisses then return end
  local added = 0
  for _, m in ipairs(loc:miss():list()) do
    local s, t = m:surface(), m:text()
    if not flagged(strings, s, t) and not mine(s, t) then
      flag(strings, s, t, true)
      added = added + 1
    end
  end
  lastMisses = info.misses
  if info.misses >= RESET_AT then                    -- a fresh round, before the set fills and goes quiet
    loc:install()
    lastMisses = 0
  end
  if added > 0 then refresh() end
end

-- ---------------------------------------------------------------- the window

local function build()
  win = hafen.ui():window():title("Translator helper"):position(settings.x or 80, settings.y or 80)
  local panel = hafen.ui():column():gap(4):parent(win):position(0, 0):size(WIDTH)

  local top = hafen.ui():row():gap(6):parent(panel)
  hafen.ui():label():parent(top):text("Language")
  hafen.ui():dropdown():parent(top):size(100):bind(language)
  hafen.ui():label():parent(top):text("Show")
  viewBox = hafen.ui():dropdown():parent(top):size(100):rows(VIEWS):value("Pending")
  hafen.ui():label():parent(top):text("Filter")
  filterBox = hafen.ui():entry():parent(top):size(180)

  box    = hafen.ui():listbox():parent(panel):size(WIDTH, 260)
  source = hafen.ui():label():parent(panel):text("Pick a row")

  matchLine = hafen.ui():row():gap(6):parent(panel)
  hafen.ui():label():parent(matchLine):text("Match")
  matchBox = hafen.ui():entry():parent(matchLine):size(WIDTH - 40 - 70 - 2 * 6)
  patternBtn = hafen.ui():button():parent(matchLine):size(70):text("Pattern"):enabled(false)

  editLine = hafen.ui():row():gap(6):parent(panel)
  entry = hafen.ui():entry():parent(editLine):size(WIDTH - 3 * 70 - 3 * 6)
  local saveBtn = hafen.ui():button():parent(editLine):size(70):text("Save")
  deleteBtn = hafen.ui():button():parent(editLine):size(70):text("Delete"):enabled(false)
  ignoreBtn = hafen.ui():button():parent(editLine):size(70):text("Ignore"):enabled(false)

  status = hafen.ui():label():parent(panel):text("collecting")
  win:pack()

  viewBox:on("Changed", function() refresh() end)
  filterBox:on("Changed", function() refresh() end)
  box:on("Changed", function(row) show(rowKeys[row]) end)
  entry:on("Submitted", save)
  matchBox:on("Submitted", function() save(entry:value()) end)   -- Enter in either field saves the line
  saveBtn:on("Pressed", function() save(entry:value()) end)
  patternBtn:on("Pressed", pattern)
  deleteBtn:on("Pressed", delete)
  ignoreBtn:on("Pressed", ignore)
  win:on("Close", function()                         -- the X destroys the window: build it again next time
    win = nil
    settings.open = false
  end)
  refresh()
end

local function toggle()                  -- a command or a hotkey holds a tree of the client's: the window waits for the step
  hafen.timer():after(0, function()
    if not (win and win:exists()) then
      build()
      settings.open = true
      return
    end
    local open = not win:visible()
    win:visible(open)
    settings.open = open
    if open then refresh() end
  end)
end

opts:panel(function(root)
  root:gap(4)
  hafen.ui():label():parent(root):text("Language the client displays, and the target of the list")
  hafen.ui():dropdown():parent(root):size(100):bind(language)
  hafen.ui():label():parent(root):text(":translator opens the window")
  hafen.ui():label():parent(root):text("The toggle key is in Options > Game > Keybindings > Translator helper")
end)

language:on("Changed", apply)
hafen.console():on("translator", toggle)
hafen.client():options():keybindings():on("toggle", toggle)
hafen.timer():every(1, harvest)

for _, l in ipairs(LANGS) do             -- a "translation" of a row of the list is nobody's: by shape only,
  if l.code then purge(docOf(l.code).text, shaped) end   -- a caption the game shares with the window stays
end

apply(language:value())
if settings.open then build() end
