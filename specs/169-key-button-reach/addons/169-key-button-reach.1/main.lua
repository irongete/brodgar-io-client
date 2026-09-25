-- 169.1 — under client.settings a key button joins another addon's hotkey and the client's bindings.
-- Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's window and timer, taken down by the next run
local PRESSED_KEY = "Ctrl+Shift+J"           -- the key the maintainer presses; the suite puts the old one back
local PRESSED_READ = "Shift+Ctrl+J"          -- the same key as a binding reads it back: Shift, Ctrl, Alt

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

-- The message a refused call raised, its chunk prefix stripped; nil when the call did not raise.
local function refusal(fn, ...)
  local ok, err = pcall(fn, ...)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(message, text)
  return (message ~= nil) and (message:find(text, 1, true) ~= nil)
end

local function run()
  if last then
    last.poll:cancel()
    if last.window:exists() then last.window:destroy() end
  end
  pass, fail, manual = 0, 0, 0

  local keybindings = hafen.client():options():keybindings()
  local noop = function() end
  keybindings:on("own", noop)
  local dormant_hotkey = keybindings:on("dormant", noop)
  local own_binding = keybindings:binding():get("own")
  local dormant_binding = keybindings:binding():get("dormant")
  local own_prefix = "addon/169-key-button-reach.1/"

  local window = hafen.ui():window():title("169.1 key button reach"):position(40, 80)
  local column = hafen.ui():column():gap(4):parent(window)
  local function key_row(caption)
    local row = hafen.ui():row():gap(8):parent(column)
    hafen.ui():label():parent(row):text(caption)
    return hafen.ui():keybinding():parent(row)
  end
  local own_button = key_row("own")
  local client_button = key_row("inv")
  local other_button = key_row("other")
  local dormant_button = key_row("dormant")
  window:pack()

  -- 1. its own hotkey binds as before, and one that ends while bound keeps its Binding
  own_button:bind(own_binding)
  dormant_button:bind(dormant_binding)
  dormant_hotkey:off()
  check(own_button:bind() == own_binding and dormant_button:bind() == dormant_binding,
        "its own hotkey binds, and one ended while bound keeps its Binding",
        "own=" .. tostring(own_button:bind()) .. " dormant=" .. tostring(dormant_button:bind()))

  -- 2. one of the client's own bindings
  local inventory_binding = keybindings:binding():get("inv")
  local client_message = refusal(client_button.bind, client_button, inventory_binding)
  check(client_message == nil and client_button:bind() == inventory_binding
          and client_button:value() == inventory_binding:key(),
        "the client's binding inv binds, reads back the same Binding, and shows its key",
        client_message or ("bind=" .. tostring(client_button:bind()) .. " value=" .. tostring(client_button:value())
          .. " key=" .. tostring(inventory_binding:key())))

  -- 3. another addon's declared hotkey: the first one that binds
  local other_binding, tried = nil, 0
  for _, binding in ipairs(keybindings:binding():list("addon/")) do
    local id = binding:id()
    if other_binding == nil and id:sub(1, 6) == "addon/" and id:sub(1, #own_prefix) ~= own_prefix then
      tried = tried + 1
      if refusal(other_button.bind, other_button, binding) == nil then other_binding = binding end
    end
  end
  check(other_binding ~= nil and other_button:bind() == other_binding and other_button:value() == other_binding:key(),
        "another addon's declared hotkey binds (" .. (other_binding and other_binding:id() or "none")
          .. "), reads back the same Binding, and shows its key",
        (tried == 0) and "no other addon declares a hotkey here" or ("none of " .. tried .. " bound"))

  -- 4. what it still refuses under client.settings, leaving the earlier Binding in place
  local kept = other_button:bind()
  local nobody_message = refusal(other_button.bind, other_button, keybindings:binding():get("addon/169-nobody/toggle"))
  local nothing_message = refusal(other_button.bind, other_button, keybindings:binding():get("nothing-169"))
  check(says(nobody_message, "no addon has it declared") and says(nothing_message, "names no binding")
          and other_button:bind() == kept,
        "a hotkey no addon declares and an id that names nothing are refused, naming why, and change nothing",
        "nobody: " .. tostring(nobody_message) .. " / nothing: " .. tostring(nothing_message))

  manualCheck("click the key button 'dormant'", "it keeps showing its key, never ...")
  if other_binding == nil then
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    last = { window = window, poll = hafen.timer():after(0, noop) }
    return
  end
  manualCheck("press the key button 'other', then " .. PRESSED_KEY,
              "a [pass] line saying the key landed on " .. other_binding:id())

  -- 5. a press on another addon's hotkey is that hotkey's edit; the key it had is put back afterwards
  local saved_key = other_binding:assigned() and (other_binding:key() or "None") or nil
  local changed_key = false                  -- false until Changed fires
  other_button:on("Changed", function(key) changed_key = key end)
  local started = os.time()
  local poll
  poll = hafen.timer():every(0.1, function()
    if changed_key == false and os.time() - started < 180 then return end
    poll:cancel()
    local landed = other_binding:key()
    if changed_key ~= false then other_binding:key(saved_key) end
    check(changed_key == PRESSED_READ and landed == PRESSED_READ,
          "the press assigned " .. PRESSED_KEY .. " to " .. other_binding:id() .. ", and Changed said so",
          (changed_key == false) and "no press within 180 s" or ("changed=" .. tostring(changed_key)
            .. " binding=" .. tostring(landed)))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
  last = { window = window, poll = poll }
end

hafen.console():on("t169-1", function() hafen.timer():after(0, run) end)
