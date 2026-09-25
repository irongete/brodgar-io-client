-- 169.2 — without client.settings the refusals name the permission. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's window, taken down by the next run

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
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
  if last and last:exists() then last:destroy() end
  pass, fail, manual = 0, 0, 0

  local keybindings = hafen.client():options():keybindings()
  keybindings:on("own", function() end)
  local own_binding = keybindings:binding():get("own")

  local window = hafen.ui():window():title("169.2 refusals"):position(40, 80)
  local key_button = hafen.ui():keybinding():parent(window)
  window:pack()
  last = window

  -- 1. its own hotkey binds with no permission
  local own_message = refusal(key_button.bind, key_button, own_binding)
  check(own_message == nil and key_button:bind() == own_binding and key_button:value() == own_binding:key(),
        "its own hotkey binds with no permission and reads back",
        own_message or ("bind=" .. tostring(key_button:bind()) .. " value=" .. tostring(key_button:value())))

  -- 2. one of the client's own bindings
  local client_message = refusal(key_button.bind, key_button, keybindings:binding():get("inv"))
  check(says(client_message, "one of the client's own") and says(client_message, "client.settings"),
        "the client's binding inv is refused, naming it the client's own and client.settings",
        client_message or "<no error>")

  -- 3. another addon's hotkey: refused before any lookup, so no other addon needs to be loaded
  local other_message = refusal(key_button.bind, key_button, keybindings:binding():get("addon/169-nobody/toggle"))
  check(says(other_message, "another addon's") and says(other_message, "client.settings"),
        "another addon's hotkey is refused, naming it another addon's and client.settings",
        other_message or "<no error>")

  -- 4. a refused bind changes nothing
  check(key_button:bind() == own_binding, "the refused binds left its own hotkey on the button", key_button:bind())

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t169-2", function() hafen.timer():after(0, run) end)
