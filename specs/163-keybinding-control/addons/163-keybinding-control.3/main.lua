-- 163.3 — the client's key button. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's timer, taken down by the next run

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function refusal(fn, ...)
  local ok, err = pcall(fn, ...)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(message, text)
  return (message ~= nil) and (message:find(text, 1, true) ~= nil)
end

local WHAT = {
  "the Inventory row's key button reads its key as binding:key() spells it",
  "every key button the panel built reads the key it shows",
  "value(v) on the client's key button is refused naming binding:key(key), before any permission is asked",
  "bind(binding) on the client's key button is refused naming hafen.ui():keybinding()",
}

local function run()
  if last then last.poll:cancel() end
  pass, fail, manual = 0, 0, 0
  local inventory_binding = hafen.client():options():keybindings():binding():get("inv")
  manualCheck("open Options > Game > Keybindings", "its rows, each ending in a key button; nothing else to do")

  local started = os.time()
  local poll
  poll = hafen.timer():every(0.25, function()
    local session = hafen.session():current()
    local options_window = session and session:ui():match("window[title=Options]")
    local row_button
    if options_window then
      for _, label in ipairs(options_window:matchAll("@Label[text=Inventory]")) do
        local siblings = label:parent():children():list()
        for index, sibling in ipairs(siblings) do
          local after = siblings[index + 1]
          if sibling == label and after and after:type() == "SetButton" then row_button = after end
        end
      end
    end
    if row_button == nil and os.time() - started < 180 then return end
    poll:cancel()
    if row_button == nil then
      for _, what in ipairs(WHAT) do check(false, what, "the Keybindings panel was not open within 180 s") end
    else
      check(row_button:value() == inventory_binding:key(), WHAT[1],
            "value=" .. tostring(row_button:value()) .. " binding=" .. tostring(inventory_binding:key()))
      local count, odd = 0, nil
      for _, key_button in ipairs(options_window:matchAll("@SetButton")) do
        local text, value = key_button:text(), key_button:value()
        if text ~= "..." then
          count = count + 1
          if not ((text == "None" and value == nil) or value == text) and odd == nil then
            odd = "text=" .. tostring(text) .. " value=" .. tostring(value)
          end
        end
      end
      check(count >= 2 and odd == nil, WHAT[2], odd or ("only " .. count .. " key buttons"))
      local value_message = refusal(row_button.value, row_button, "F5")
      check(says(value_message, "binding:key(key)"), WHAT[3], value_message or "<no error>")
      local bind_message = refusal(row_button.bind, row_button, inventory_binding)
      check(says(bind_message, "hafen.ui():keybinding()"), WHAT[4], bind_message or "<no error>")
    end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
  last = { poll = poll }
end

hafen.console():on("t163-3", function() hafen.timer():after(0, run) end)
