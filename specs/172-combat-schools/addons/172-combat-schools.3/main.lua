-- 172.3 — loading and saving a combat school, under fight.load and fight.save. Self-checking suite.
--
-- Run :t172 on a character in the world. For the length of the run it records every load, save and use the
-- client sends and cancels each one before it reaches the server, so the run changes nothing: it reads what
-- school:load() and school:save() send on the loaded school, and what they refuse.

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

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(text, needle)
  return (text ~= nil) and (text:find(needle, 1, true) ~= nil)
end

-- event:args() holds a nil for every nil the message carries, so # is no length: the highest index is.
local function highest(args)
  local top = 0
  for key in pairs(args) do
    if (type(key) == "number") and (key > top) then top = key end
  end
  return top
end

local sent = {}   -- every load, save and use sent during the run: { msg, widget, args, top, cancelled }
local subs = {}

local function listen()
  for _, msg in ipairs({ "load", "save", "use" }) do
    subs[#subs + 1] = hafen.event():action():on(msg, function(event)
      local args = event:args()
      local cancelled = pcall(function() event:preventDefault() end)
      sent[#sent + 1] = { msg = msg, widget = event:widget():type(), args = args, top = highest(args),
                          cancelled = cancelled }
    end)
  end
end

local function finish()
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- One record as `msg {a, b, nil, …}` for a got: line.
local function shown(record)
  if record == nil then return "nothing" end
  local parts = {}
  for index = 1, record.top do parts[#parts + 1] = tostring(record.args[index]) end
  return record.msg .. " {" .. table.concat(parts, ", ") .. "} from " .. tostring(record.widget)
end

-- Is `record` exactly `msg {wire}` from the schools tab?
local function exactly(record, msg, wire)
  return (record ~= nil) and (record.msg == msg) and (record.widget == "FightWnd") and (record.top == 1)
    and (record.args[1] == wire)
end

local function run()
  pass, fail, manual = 0, 0, 0
  sent, subs = {}, {}
  local session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t172 logged in")
    return finish()
  end
  local fight = session:fight()
  local schools = fight:school()
  local cur = schools:current()
  if cur == nil then
    check(false, "school():current() is a school", "nil -- the combat-schools tab has not built")
    return finish()
  end
  listen()
  local wire = cur:wire()

  -- 1. Load the loaded school: load {wire} and then use {wire}, and the mark stays where it was.
  if cur:empty() then
    check(true, "cur:load() (not tried: the current school is unused, so check 4 refuses it)")
  else
    local from = #sent
    local back = cur:load()
    check((back == cur) and (#sent == from + 2) and exactly(sent[from + 1], "load", wire)
          and exactly(sent[from + 2], "use", wire) and (schools:current() == cur),
          "cur:load() returns cur, sends exactly load {wire} then use {wire}, and current() is still cur",
          tostring(back) .. ": " .. shown(sent[from + 1]) .. "; " .. shown(sent[from + 2]) .. "; "
          .. (#sent - from) .. " sent")
  end

  -- 2. Save into it: save {wire, name?, layout…} and then use {wire}, the layout one place per card.
  local from = #sent
  local back = cur:save()
  local save, use = sent[from + 1], sent[from + 2]
  local wrong
  if (back ~= cur) or (#sent ~= from + 2) or (save == nil) or (save.msg ~= "save") or (save.widget ~= "FightWnd")
     or not exactly(use, "use", wire) then
    wrong = tostring(back) .. ": " .. shown(save) .. "; " .. shown(use) .. "; " .. (#sent - from) .. " sent"
  elseif save.args[1] ~= wire then
    wrong = "slot " .. tostring(save.args[1])
  elseif (not cur:empty()) and (save.args[2] ~= cur:name()) then
    wrong = "name " .. tostring(save.args[2]) .. ", cur:name() " .. tostring(cur:name())
  else
    local deck = fight:deck()
    local index = cur:empty() and 2 or 3
    local place, filled = 0, 0
    while (wrong == nil) and (index <= save.top) do
      place = place + 1
      local card = deck:get(place)
      if card == nil then
        wrong = "place " .. place .. " is past the deck's " .. deck:count()
      elseif save.args[index] == nil then
        if not card:empty() then wrong = "place " .. place .. " encoded empty, the card holds " .. tostring(card:res()) end
        index = index + 1
      else
        filled = filled + 1
        if card:empty() or (save.args[index + 1] ~= card:used()) then
          wrong = "place " .. place .. " encoded " .. tostring(save.args[index]) .. " x" .. tostring(save.args[index + 1])
            .. ", the card holds " .. tostring(card:res()) .. " x" .. tostring(card:used())
        end
        index = index + 2
      end
    end
    local cards = deck:count(function(card) return not card:empty() end)
    if (wrong == nil) and (filled ~= cards) then
      wrong = filled .. " filled places encoded, " .. cards .. " filled cards"
    end
  end
  check(wrong == nil, "cur:save() returns cur, sends save {wire, name?, one entry per deck place} then use {wire}",
        wrong)

  -- 3. Neither takes an argument.
  from = #sent
  local loadText = refusal(function() return cur:load(1) end)
  local saveText = refusal(function() return cur:save(1) end)
  check(says(loadText, "takes no arguments") and says(saveText, "takes no arguments") and (#sent == from),
        "cur:load(1) and cur:save(1) raise 'takes no arguments', and nothing is sent",
        tostring(loadText) .. " / " .. tostring(saveText))

  -- 4. An unused school is not loaded.
  local unused = schools:find(function(school) return school:empty() end)
  if unused == nil then
    check(true, "an unused school's :load() raises (not reached: no school is unused)")
  else
    from = #sent
    local text = refusal(function() return unused:load() end)
    check(says(text, "school:empty()") and (#sent == from),
          "an unused school's :load() raises naming school:empty(), and nothing is sent",
          tostring(text) .. "; " .. (#sent - from) .. " sent")
  end

  -- 5. Nothing reached the server.
  local escaped
  for _, record in ipairs(sent) do
    if not record.cancelled then escaped = escaped or shown(record) end
  end
  check((escaped == nil) and (#sent == (cur:empty() and 2 or 4)),
        "every send was intercepted and cancelled (" .. #sent .. " of them)", escaped or (#sent .. " sent"))

  -- 6. :current() is an address, and its refusal names the write.
  local text = refusal(function() return schools:current(1) end)
  check(says(text, "school:load()"), "school():current(1) raises, naming school:load()", text or "<no error>")

  finish()
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where the sends go out as any other's do.
hafen.console():on("t172", function()
  hafen.timer():after(0, function()
    local ok, err = pcall(run)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end)
end)
