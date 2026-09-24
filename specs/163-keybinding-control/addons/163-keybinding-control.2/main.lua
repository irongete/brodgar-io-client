-- 163.2 — a press on the key button. Self-checking suite.

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

local function run()
  if last then
    last.poll:cancel()
    if last.window:exists() then last.window:destroy() end
  end
  pass, fail, manual = 0, 0, 0

  local keybindings = hafen.client():options():keybindings()
  local noop = function() end
  keybindings:on("first", noop)
  local cancel_hotkey = keybindings:on("cancel", noop)
  keybindings:on("greyed", noop)
  local first_binding = keybindings:binding():get("first")
  local cancel_binding = keybindings:binding():get("cancel")
  local greyed_binding = keybindings:binding():get("greyed")

  local window = hafen.ui():window():title("163.2 key button"):position(40, 80)
  local column = hafen.ui():column():gap(4):parent(window)
  local function key_row(parent, caption, binding)
    local row = hafen.ui():row():gap(8):parent(parent)
    hafen.ui():label():parent(row):text(caption)
    return hafen.ui():keybinding():parent(row):bind(binding)
  end
  local first_button = key_row(column, "first", first_binding)
  local cancel_button = key_row(column, "cancel", cancel_binding)
  local greyed_column = hafen.ui():column():gap(4):parent(column)
  local greyed_button = key_row(greyed_column, "greyed", greyed_binding)
  greyed_column:enabled(false)
  window:pack()

  -- the keys: the first one differs from whatever an earlier run left on 'first'
  local step1_key = (first_binding:key() == "Shift+Ctrl+F12") and "Shift+Ctrl+F10" or "Shift+Ctrl+F12"
  local step2_key = "Shift+Ctrl+F11"
  local first_changed = {}
  first_button:on("Changed", function(key) first_changed[#first_changed + 1] = (key == nil) and "<nil>" or key end)
  local cancel_fired = 0
  cancel_button:on("Changed", function() cancel_fired = cancel_fired + 1 end)
  local cancel_start_key, cancel_start_assigned = cancel_binding:key(), cancel_binding:assigned()

  manualCheck("click the greyed key button 'greyed'", "nothing happens: it stays greyed and reads None")
  manualCheck("assign " .. step1_key .. " with the key button 'first': click it, then press the keys together",
              "it reads " .. step1_key)
  manualCheck("in Options > Game > Keybindings, section '163.2 — a press on the key button', give 'first' " .. step2_key,
              "before you do, that row reads " .. step1_key)
  manualCheck("click 'first' and press Escape", "it reads " .. step2_key .. " again")
  manualCheck("click 'first' and press Delete", "it reads None")
  manualCheck("click 'first' and press Backspace", "it still reads None")
  manualCheck("click the key button 'cancel' four times, a second apart", "each click shows ... for a moment, then None again")

  -- 'first': five steps, scored in the order the manual lines ask for them
  local function state()
    return ("changed={%s} key=%s assigned=%s value=%s"):format(table.concat(first_changed, ","),
      tostring(first_binding:key()), tostring(first_binding:assigned()), tostring(first_button:value()))
  end
  local steps = {
    { what = "the press assigned " .. step1_key .. ", fired Changed once with it, and a key button bound to 'first' afterwards shows it at once",
      score = function()
        local probe = hafen.ui():keybinding():bind(first_binding)
        local at_once = probe:text() == step1_key and probe:value() == step1_key
        probe:destroy()
        return #first_changed == 1 and first_changed[1] == step1_key and first_binding:key() == step1_key
          and first_binding:assigned() and first_button:value() == step1_key and at_once,
          state() .. " at once=" .. tostring(at_once)
      end },
    { what = "a key assigned in the panel showed on the button within a frame, and fired nothing", panel = true,
      score = function()
        return #first_changed == 0 and first_binding:key() == step2_key and first_button:value() == step2_key, state()
      end },
    { what = "Escape ended the capture, left the key and fired nothing",
      score = function()
        return #first_changed == 0 and first_binding:key() == step2_key and first_binding:assigned()
          and first_button:value() == step2_key, state()
      end },
    { what = "Delete unbound it and fired Changed once with nil",
      score = function()
        return #first_changed == 1 and first_changed[1] == "<nil>" and first_binding:key() == nil
          and first_binding:assigned() and first_button:value() == nil, state()
      end },
    { what = "Backspace put it back on its default and fired nothing",
      score = function()
        return #first_changed == 0 and first_binding:key() == nil and not first_binding:assigned()
          and first_button:value() == nil, state()
      end },
  }
  local step, was_capturing, settle, panel_polls = 1, false, nil, nil
  local function score_step()
    local ok, got = steps[step].score()
    check(ok, steps[step].what, got)
    first_changed = {}
    step, settle, panel_polls = step + 1, nil, nil
  end

  -- 'cancel': four captures, each ended by one cause; the first cause re-declares the hotkey, so the three
  -- captures after it prove the button answers again
  local causes = {
    { name = "hotkey ended", apply = function() cancel_hotkey:off() end,
      restore = function() cancel_hotkey = keybindings:on("cancel", noop) end },
    { name = "disabled", apply = function() cancel_button:enabled(false) end,
      restore = function() cancel_button:enabled(true) end },
    { name = "unbound", apply = function() cancel_button:bind(nil) end,
      restore = function() cancel_button:bind(cancel_binding) end },
    { name = "hidden", apply = function() cancel_button:visible(false) end,
      restore = function() cancel_button:visible(true) end },
  }
  local cause, acting, waited, ended = 1, false, 0, {}
  local cancel_what = "each capture ended by itself when its hotkey ended, when the button was disabled, unbound and hidden, the button answered again once its hotkey was declared again, and nothing was assigned"
  local function cancel_state()
    local parts = {}
    for index, entry in ipairs(causes) do parts[#parts + 1] = entry.name .. "=" .. tostring(ended[index]) end
    return table.concat(parts, " ") .. (" key=%s assigned=%s fired=%d"):format(tostring(cancel_binding:key()),
      tostring(cancel_binding:assigned()), cancel_fired)
  end
  local function score_cancel()
    check(ended[1] and ended[2] and ended[3] and ended[4] and cancel_binding:key() == cancel_start_key
            and cancel_binding:assigned() == cancel_start_assigned and cancel_fired == 0,
          cancel_what, cancel_state())
  end

  local greyed_captured, finished = false, false
  local started = os.time()
  local poll
  local function finish()
    finished = true
    poll:cancel()
    for index = step, #steps do check(false, steps[index].what, "not reached in 600 s") end
    if cause <= #causes then check(false, cancel_what, "not reached in 600 s: " .. cancel_state()) end
    check(not greyed_captured, "the greyed key button never began a capture", "it read ...")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
  poll = hafen.timer():every(0.05, function()
    if finished then return end
    if greyed_button:text() == "..." then greyed_captured = true end
    if step <= #steps then
      local capturing = first_button:text() == "..."
      if settle == nil and not capturing and (was_capturing or #first_changed > 0) then settle = 0 end
      was_capturing = capturing
      if settle ~= nil then
        settle = settle + 1
        if settle >= 2 then score_step() end        -- Changed lands just after the caption: score 0.1 s on
      elseif steps[step].panel and not capturing and first_binding:key() ~= step1_key then
        panel_polls = (panel_polls or 0) + 1
        if first_button:value() == first_binding:key() or panel_polls >= 2 then score_step() end
      end
    end
    if cause <= #causes then
      local capturing = cancel_button:text() == "..."
      if not acting then
        if capturing then
          causes[cause].apply()
          acting, waited = true, 0
        end
      else
        waited = waited + 1
        if (not capturing) or waited >= 10 then
          ended[cause] = not capturing
          causes[cause].restore()
          acting, cause = false, cause + 1
          if cause > #causes then score_cancel() end
        end
      end
    end
    if (step > #steps and cause > #causes) or (os.time() - started >= 600) then finish() end
  end)
  last = { window = window, poll = poll }
end

hafen.console():on("t163-2", function() hafen.timer():after(0, run) end)
