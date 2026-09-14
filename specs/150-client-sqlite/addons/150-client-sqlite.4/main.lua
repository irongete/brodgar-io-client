-- 150.4 — Remove forgets the addon. Self-checking suite.
--
-- Remove is the hub's, refused for a folder the hub did not install, so this suite cannot remove itself:
-- what it proves is that the rows Remove deletes EXIST and are this addon's — an option, a hotkey and a
-- remembered window, each declared and read back under the addon's own key. The deletion itself is proven
-- headless (two ids in every table and both lists, forget(a), b's rows intact) and by the maintainer's
-- [manual] step on an addon the hub installed.

local ID = "150-client-sqlite.4"
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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The option: a row under addon/<id>/opt/<name>, written on the write and read back.
local function checkOption()
  local opts = hafen.client():options():addon()
  local o = opts:text("mark"):default("none"):add()
  o:value("set by t150")
  eq("the option reads back what was written", o:value(), "set by t150")
  eq("the option is this addon's: opts:option():get finds it", opts:option():get("mark"), o)
  o:value(o:default())                    -- leave the row at its default
  eq("the option is back on its default", o:value(), "none")
end

-- The hotkey: a binding under addon/<id>/<name>, the id its assignment is remembered under.
local function checkHotkey()
  local keys = hafen.client():options():keybindings()
  local hot = keys:on("mark", function() end)
  local b = keys:binding():get("mark")
  eq("the hotkey exists once declared", b:exists(), true)
  eq("its id carries this addon's prefix", b:id(), "addon/" .. ID .. "/mark")
  eq("it starts unbound", b:key(), nil)
  hot:off()
end

-- The window: a placement row under this addon and its scope, captured as the first window goes and
-- put back on the second. Built on a timer: the console handler holds the typed tree.
local function checkWindow(done)
  hafen.timer():after(0, function()
    local ok, err = pcall(function()
      local w1 = hafen.ui():window():title("t150.4"):size(120, 40)
      w1:remember("mark")
      w1:position(77, 91)
      w1:destroy()
      local w2 = hafen.ui():window():title("t150.4"):size(120, 40)
      w2:remember("mark")
      local p = w2:position()
      check(p.x == 77 and p.y == 91, "a remembered window comes back where the first one stood (77, 91)",
            tostring(p.x) .. ", " .. tostring(p.y))
      w2:remember(nil)                    -- delete the row: a suite leaves nothing behind
      eq("remember(nil) forgets the name", w2:remember(), nil)
      w2:destroy()
    end)
    check(ok, "the window checks ran without an error", err)
    done()
  end)
end

local function run()
  local ok, err = pcall(checkOption)
  check(ok, "the option checks ran without an error", err)
  ok, err = pcall(checkHotkey)
  check(ok, "the hotkey checks ran without an error", err)
  checkWindow(function()
    manualCheck("install one addon from the hub, set one of its options, Remove it in the panel, :reload, then"
      .. " sqlite3 savedata/client.sqlite \"select count(*) from prefs where key like 'addon/<id>/%'\"",
      "0, and savedata/<id>/ still there")
    summary()
  end)
end

hafen.console():on("t150", run)   -- the only way in: a suite does not start itself
