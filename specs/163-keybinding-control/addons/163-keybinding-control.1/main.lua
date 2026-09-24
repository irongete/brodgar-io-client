-- 163.1 — the key button, built and bound. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's window and timer, taken down by the next run

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
  keybindings:on("first", noop)
  keybindings:on("second", noop)
  local dormant_hotkey = keybindings:on("dormant", noop)
  local first_binding = keybindings:binding():get("first")
  local second_binding = keybindings:binding():get("second")
  local dormant_binding = keybindings:binding():get("dormant")

  local window = hafen.ui():window():title("163.1 key button"):position(40, 80)
  local column = hafen.ui():column():gap(4):parent(window)
  local function key_row(caption)
    local row = hafen.ui():row():gap(8):parent(column)
    hafen.ui():label():parent(row):text(caption)
    return hafen.ui():keybinding():parent(row)
  end
  local first_button = key_row("first")
  local tipped_button = key_row("tipped")
  local bare_button = key_row("bare")
  local dormant_button = key_row("dormant")
  window:pack()
  tipped_button:bind(first_binding)
  dormant_button:bind(dormant_binding)
  dormant_hotkey:off()                       -- ended while bound: the button keeps its Binding

  -- 1. a key button built bare
  local bare_size = bare_button:size()
  check(bare_button:type() == "SetButton" and bare_button:role() == "button" and bare_size.w == 175
          and bare_button:text() == "None" and bare_button:value() == nil and bare_button:bind() == nil,
        "a bare key button is the client's: SetButton, button, 175 wide, reads None, holds nil, bound to nothing",
        ("type=%s role=%s w=%s text=%s value=%s bind=%s"):format(tostring(bare_button:type()),
          tostring(bare_button:role()), tostring(bare_size.w), tostring(bare_button:text()),
          tostring(bare_button:value()), tostring(bare_button:bind())))

  -- 2. :size(w)
  local sized_button, plain_button = hafen.ui():keybinding():size(120), hafen.ui():button()
  local sized, plain = sized_button:size(), plain_button:size()
  sized_button:destroy()
  plain_button:destroy()
  check(sized.w == 120 and sized.h == plain.h, "size(w) sets its width and keeps the height of the client's button art",
        ("w=%s h=%s button h=%s"):format(tostring(sized.w), tostring(sized.h), tostring(plain.h)))

  -- 3. an argument
  local argument_message = refusal(function() return hafen.ui():keybinding("first") end)
  check(says(argument_message, "takes no arguments"), "an argument to hafen.ui():keybinding() is refused",
        argument_message or "<no error>")

  -- 4. joined, replaced, unbound, and bound through an end
  first_button:bind(first_binding)
  local joined = first_button:bind() == first_binding
  local shown = first_button:text() == (first_binding:key() or "None") and first_button:value() == first_binding:key()
  first_button:bind(second_binding)
  local replaced = first_button:bind() == second_binding
  local caption = first_button:text()
  first_button:bind(nil)
  local unbound = first_button:bind() == nil and first_button:text() == caption
  first_button:bind(first_binding)
  local kept = dormant_button:bind() == dormant_binding
  check(joined and shown and replaced and unbound and kept,
        "bind(binding) joins it and reads back the same Binding, a second bind replaces it, bind(nil) keeps the key shown, and a hotkey ended while bound keeps its Binding",
        ("joined=%s shown=%s replaced=%s unbound=%s kept=%s"):format(tostring(joined), tostring(shown),
          tostring(replaced), tostring(unbound), tostring(kept)))

  -- 5. what is not a live hotkey of yours
  local late_name = "late" .. string.format("%d", os.time())
  local early_binding = keybindings:binding():get(late_name)     -- before on(): the registry id as written
  local late_hotkey = keybindings:on(late_name, noop)
  local cases = {
    { "client", keybindings:binding():get("inv"), "client's own" },
    { "other addon", keybindings:binding():get("addon/163-other-addon/toggle"), "another addon's" },
    { "taken before", early_binding, "taken before keybindings:on" },
    { "ended", dormant_binding, "has ended" },
  }
  local missed
  for _, case in ipairs(cases) do
    local message = refusal(first_button.bind, first_button, case[2])
    if (missed == nil) and not says(message, case[3]) then missed = case[1] .. ": " .. tostring(message) end
  end
  late_hotkey:off()
  local still = first_button:bind() == first_binding
  check(missed == nil and still,
        "a Binding that is not a live hotkey of yours is refused, naming what to do: the client's, another addon's, one taken before keybindings:on, one ended",
        missed or "a refused bind moved it")

  -- 6. an Option on the key button, a Binding on a check
  local options = hafen.client():options():addon()
  local flag_option = options:option():get("flag") or options:boolean("flag"):default(false):add()
  local option_message = refusal(first_button.bind, first_button, flag_option)
  local check_box = hafen.ui():check()
  local check_message = refusal(check_box.bind, check_box, first_binding)
  check_box:destroy()
  check(says(option_message, "keybindings:binding():get(name)") and says(check_message, "hafen.ui():keybinding()"),
        "an Option on the key button and a Binding on a check are refused, each naming the control that takes it",
        "option: " .. tostring(option_message) .. " / check: " .. tostring(check_message))

  -- 7. :value(v) and :text(s)
  local value_message = refusal(first_button.value, first_button, "F5")
  local text_message = refusal(first_button.text, first_button, "Go")
  check(says(value_message, "binding:key(key)") and says(value_message, "client.settings")
          and says(text_message, "hafen.ui():label()"),
        "value(v) and text(s) are refused, naming binding:key(key) under client.settings and a label",
        "value: " .. tostring(value_message) .. " / text: " .. tostring(text_message))

  -- 8. :tooltip(s)
  tipped_button:tooltip("Bound by the 163.1 suite")
  local cleared_button = hafen.ui():keybinding():tooltip("gone"):tooltip("")
  local tip, cleared = tipped_button:tooltip(), cleared_button:tooltip()
  cleared_button:destroy()
  check(tip == "Bound by the 163.1 suite" and cleared == nil, "tooltip(s) reads back, and an empty one clears it",
        "tip=" .. tostring(tip) .. " cleared=" .. tostring(cleared))

  -- the page holds the same control
  options:panel(function(root)
    local row = hafen.ui():row():gap(8):parent(root)
    hafen.ui():label():parent(row):text("first")
    hafen.ui():keybinding():parent(row):bind(first_binding)
  end)

  manualCheck("hover the key button 'first'", "the client's tip, three yellow lines: Escape, Backspace, Delete")
  manualCheck("hover the key button 'tipped'", "only the line: Bound by the 163.1 suite")
  manualCheck("open Options > AddOns > 163.1 — the key button, built and bound",
              "a row 'first' ending in a key button that reads what 'first' reads in the suite's window")
  manualCheck("click the key buttons 'bare' and 'dormant', once each", "both keep reading None")

  -- 9. a press that does nothing: the release seen, then half a second of captions
  local released = { bare = false, dormant = false }
  local captured = false
  bare_button:on("MouseUp", function(event) if event:button() == 1 then released.bare = true end end)
  dormant_button:on("MouseUp", function(event) if event:button() == 1 then released.dormant = true end end)
  local started, settle = os.time(), nil
  local poll
  poll = hafen.timer():every(0.05, function()
    if bare_button:text() == "..." or dormant_button:text() == "..." then captured = true end
    if released.bare and released.dormant and settle == nil then settle = 0 end
    if settle ~= nil then settle = settle + 1 end
    if (settle ~= nil and settle >= 10) or (os.time() - started >= 180) then
      poll:cancel()
      local both = released.bare and released.dormant
      check(both and not captured,
            "a press on a key button bound to nothing, or to a hotkey that has ended, began no capture",
            (not both) and ("not both clicked within 180 s (bare=" .. tostring(released.bare) .. " dormant="
              .. tostring(released.dormant) .. ")") or "one of them read ...")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end
  end)
  last = { window = window, poll = poll }
end

hafen.console():on("t163-1", function() hafen.timer():after(0, run) end)
