-- 140.3 — bind is the one link. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local log = hafen.log()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log:write("[pass] " .. what)
  else
    fail = fail + 1
    log:write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log:write("[manual] " .. step .. " -- expect: " .. expect)
end

local function same(t, want)
  if type(t) ~= "table" or #t ~= #want then return false end
  for i = 1, #want do
    if t[i] ~= want[i] then return false end
  end
  return true
end

local opts = hafen.client():options():addon()

-- One option per kind, a second boolean for the replace, and the page's own.
local flag  = opts:boolean("flag"):default(false):add()
local other = opts:boolean("other"):default(false):add()
local count = opts:number("count"):range(2, 9):default(4):add()
local pick  = opts:choice("pick"):choices{"wood", "stone", "clay"}:default("wood"):add()
local word  = opts:text("word"):default("axe"):add()
local page  = opts:boolean("page-check"):default(false):add()

page:on("Changed", function(v) log:write("Changed " .. tostring(v)) end)

-- The suite's own page holds the bound check: what the [manual] line asks the maintainer to tick.
opts:panel(function(root)
  root:gap(4)
  hafen.ui():label():parent(root):text("140.3: tick the box, then read the chat")
  hafen.ui():check():parent(root):text("Bound to the option 'page-check'"):bind(page)
end)

local function run()
  pass, fail, manual = 0, 0, 0
  for _, o in ipairs({flag, other, count, pick, word}) do o:value(o:default()) end

  local win = hafen.ui():window():title("140.3"):position(200, 200)
  local col = hafen.ui():column():gap(4):parent(win):position(0, 0)
  local chk = hafen.ui():check():parent(col):text("flag")
  local sl  = hafen.ui():slider():parent(col):size(120)
  local dd  = hafen.ui():dropdown():parent(col):size(120)
  local rd  = hafen.ui():radio():parent(col)
  local en  = hafen.ui():entry():parent(col):size(120)

  local changed = 0
  chk:on("Changed", function() changed = changed + 1 end)

  chk:bind(flag)
  check(chk:bind() == flag and chk:value() == false,
        "check:bind(boolean) reads the option back and takes its value",
        tostring(chk:bind()) .. " holding " .. tostring(chk:value()))
  flag:value(true)
  check(chk:value() == true, "a write to the boolean moves the bound check", chk:value())

  sl:bind(count)
  local r = sl:range()
  local took = sl:value()
  count:value(7)
  check(r.min == 2 and r.max == 9 and took == 4 and sl:value() == 7,
        "a bound slider takes the number's range and value, and moves with a write",
        tostring(r.min) .. ".." .. tostring(r.max) .. " at " .. tostring(took) .. " then " .. tostring(sl:value()))

  dd:bind(pick)
  rd:bind(pick)
  local ddRows, rdRows = dd:rows(), rd:rows()
  local ddTook, rdTook = dd:value(), rd:value()
  pick:value("clay")
  check(same(ddRows, pick:info().choices) and ddTook == "wood" and dd:value() == "clay",
        "a bound dropdown takes the choice's rows and pick, and moves with a write",
        tostring(ddTook) .. " then " .. tostring(dd:value()))
  check(same(rdRows, pick:info().choices) and rdTook == "wood" and rd:value() == "clay",
        "a radio bound to the same option takes its rows, and moves with the same write",
        tostring(rdTook) .. " then " .. tostring(rd:value()))

  en:bind(word)
  local enTook = en:value()
  word:value("saw")
  check(enTook == "axe" and en:value() == "saw",
        "a bound entry takes the text and moves with a write",
        tostring(enTook) .. " then " .. tostring(en:value()))

  check(changed == 0, "the check's own Changed counts 0 across the writes", changed)

  chk:bind(nil)
  flag:value(false)
  check(chk:bind() == nil and chk:value() == true,
        "bind(nil) unbinds: a further write to the option moves nothing",
        tostring(chk:bind()) .. " holding " .. tostring(chk:value()))

  chk:bind(other)
  local retook = chk:value()
  flag:value(true)
  local afterFlag = chk:value()
  other:value(true)
  check(chk:bind() == other and retook == false and afterFlag == false and chk:value() == true,
        "a second bind replaces the first: the check follows the new option alone",
        tostring(chk:bind()) .. " " .. tostring(retook) .. "/" .. tostring(afterFlag) .. "/" .. tostring(chk:value()))

  refuses("slider:bind(boolean) is refused naming the check a boolean takes",
          function() sl:bind(flag) end, "hafen.ui():check()")
  local s = hafen.session():current()
  local borrowed = s and (s:ui():matchAll("window")[1] or s:ui():root())
  refuses("bind on a borrowed widget is refused naming the client",
          function() borrowed:bind(flag) end, "client")
  refuses("a choice listed twice is refused at :choices, naming the repeat",
          function() opts:choice("dup"):choices{"a", "b", "a"} end, "repeated")

  win:destroy()
  for _, o in ipairs({flag, other, count, pick, word}) do o:value(o:default()) end

  manualCheck("open Options ▸ AddOns ▸ '140.3 — bind is the one link' and tick the box",
              "a 'Changed true' line from the option in the chat (or 'Changed false': the new state)")
  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The only way in: a suite does not start itself. Deferred a step, because the console line runs under
-- the typed tree's monitor and a window is built into the layer.
hafen.console():on("t140", function() hafen.timer():after(0, run) end)
