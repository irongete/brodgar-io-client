-- R08 -- the permission tier's open doors.
--
-- This addon declares NO permissions on purpose: every gate the block added or moved has to refuse it, and
-- a refusal is only a proof when the caller could not have been granted the key. The verbs that stayed open
-- are checked the other way round -- they must NOT refuse.
--
-- Run it with :tR08. It starts nothing by itself.

local L = hafen.log()

local out, pass, fail, man = {}, 0, 0, 0

local function ok(what)
  pass = pass + 1
  out[#out + 1] = "[pass] " .. what
end

local function bad(what, got)
  fail = fail + 1
  out[#out + 1] = "[fail] " .. what .. " -- got: " .. tostring(got)
end

local function manual(what)
  man = man + 1
  out[#out + 1] = "[manual] " .. what
end

local function check(what, cond, got)
  if cond then ok(what) else bad(what, got) end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the strip has
-- to allow both shapes or every message read starts with the chunk name.
local function why(e)
  e = tostring(e or "")
  return (e:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Call fn and answer: was it refused, and what did it say.
local function refused(fn)
  local done, err = pcall(fn)
  if done then return false, "<no error>" end
  return true, why(err)
end

local function says(text, needle)
  return text:find(needle, 1, true) ~= nil
end

local function flush()
  for _, line in ipairs(out) do L:write(line) end
  L:write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. man .. " manual")
end

-- ONE written line, carrying four things at once: an ordinary colon (which the forged-prefix rule must let
-- through), markup a stranger could have typed, and an embedded newline the sink has to keep inside the line.
local PROBE = "R08 probe: $col{ff0000}{x} one\ntwo"

-- The probe as the System log holds it -- searched for rather than taken as "the newest", since the client
-- writes lines of its own whenever it likes.
local function probeLine(s)
  local ch = s:chat():find(function(c) return c:kind() == "chat.system" end)
  if not ch then return nil end
  local n = ch:message():count()
  for i = n, math.max(1, n - 8), -1 do
    local m = ch:message():get(i)
    if m and m:raw() and says(m:raw(), "R08 probe") then return m end
  end
  return nil
end

-- ---- phase 3: the Kin window, scored over a bounded window rather than left as two manual lines -------
local function kinChecks(s)
  local wnd = s:ui():match("@BuddyWnd")
  if not wnd then return false end
  local entries = wnd:matchAll("@TextEntry")
  local hidden, any = nil, entries[1]
  for _, e in ipairs(entries) do
    if e:text() == nil then hidden = e end
  end
  check("a hidden field in the Kin window reads nil through widget:text()", hidden ~= nil,
        (any == nil) and "no text entry in the window" or "every entry answered with text")
  check("a widget inside the Kin window has no parent", (any ~= nil) and (any:parent() == nil),
        (any == nil) and "no text entry in the window" or "a parent came back")
  return true
end

local function phase3(s, left)
  if kinChecks(s) then return flush() end
  if left <= 0 then
    manual("open the Kin window while this runs -- expect: two [pass] lines here instead of this one")
    return flush()
  end
  hafen.timer():after(0.5, function() phase3(s, left - 0.5) end)
end

-- ---- phase 2: read the probe line back off the System log --------------------------------------------
local function phase2(s)
  local msg = probeLine(s)
  if not msg then
    bad("hafen.log():write accepts an ordinary colon", "the probe line never reached the System log")
    bad("hafen.log():write keeps a newline inside its own line", "no probe line")
    bad("msg:raw() hands back the line as it arrived", "no probe line")
    bad("msg:text() hands back the quoted line the chat drew", "no probe line")
    return phase3(s, 8)
  end
  local raw, txt = msg:raw() or "", msg:text() or ""
  ok("hafen.log():write accepts an ordinary colon")
  check("hafen.log():write keeps a newline inside its own line",
        says(raw, "one\\ntwo") and not says(raw, "\n"), raw)
  check("msg:raw() hands back the line as it arrived", says(raw, "$col{ff0000}{x}"), raw)
  check("msg:text() hands back the quoted line the chat drew",
        says(txt, "$$col${ff0000$}${x$}"), txt)
  phase3(s, 8)
end

-- ---- phase 1: the gates, none of which this addon may pass -------------------------------------------
local function phase1()
  local s = hafen.session():current()
  if not s then
    L:write("[fail] a character has to be in the world -- got: no session on screen")
    L:write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  local no, msg = refused(function() hafen.virtual():click("MouseDown", 0, 0) end)
  check("hafen.virtual():click is refused, naming \"virtual.click\"",
        no and says(msg, "virtual.click") and says(msg, "manifest.json"), msg)

  local hud = s:ui():match("@GameUI")
  no, msg = refused(function() hud:value(true) end)
  check("widget:value(v) on one of the client's own is refused, naming \"widget.value\"",
        no and says(msg, "widget.value"), (hud == nil) and "the HUD is not up" or msg)

  -- ...and the doors that stayed open have to STAY open.
  local cur = s:chat():selected()
  no, msg = refused(function() s:chat():selected(cur) end)
  check("s:chat():selected(ch) still selects without \"ui.focus\"", not no, msg)

  L:write(PROBE)
  hafen.timer():after(0.5, function() phase2(s) end)
end

-- The console handler runs under the typed tree's monitor, so everything above it is deferred by a tick.
hafen.console():on("tR08", function()
  hafen.timer():after(0, phase1)
end)
