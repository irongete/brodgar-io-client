-- 140.2 — an option is the model. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local opts = hafen.client():options():addon()

-- One of each kind, declared from the file body: a value, its default, its bounds or its choices.
local flag = opts:boolean("flag"):default(false):add()
local size = opts:number("size"):range(1, 20):default(8):add()
local mode = opts:choice("mode"):choices{"compact", "wide"}:default("compact"):add()
local name = opts:text("name"):default("stock"):add()

-- The keys of a table, sorted and joined, so a shape can be compared as one string.
local function keys(t)
  local ks = {}
  for k in pairs(t) do ks[#ks + 1] = tostring(k) end
  table.sort(ks)
  return table.concat(ks, ",")
end

local function run()
  pass, fail, manual = 0, 0, 0

  eq("opt:type() answers the four", flag:type() .. " " .. size:type() .. " " .. mode:type() .. " "
     .. name:type(), "boolean number choice text")

  -- A write reads back, and Changed fires once for the change and not for a rewrite of the same value.
  -- The value is put back afterwards: a suite leaves the store as it found it.
  local was = size:value()
  local other = (was == 8) and 9 or 8
  local fired = 0
  local sub = size:on("Changed", function(v) fired = fired + 1 end)
  size:value(other)
  eq("a stored write reads back after :value(v)", size:value(), other)
  eq("Changed fires once for a change", fired, 1)
  size:value(other)
  eq("Changed does not fire for a rewrite of the same value", fired, 1)
  sub:off()
  size:value(was)

  eq("info() on a boolean has exactly name, type, value, default", keys(flag:info()),
     "default,name,type,value")
  eq("info() on a number adds min and max", keys(size:info()), "default,max,min,name,type,value")
  local mi = mode:info()
  eq("info() on a choice adds choices", keys(mi) .. " / " .. table.concat(mi.choices, "+"),
     "choices,default,name,type,value / compact+wide")
  eq("opts:option():count() is four", opts:option():count(), 4)

  -- The four retired spellings: each refuses naming the control to build in the panel.
  refuses(":label(s) on a builder names the check's caption",
          function() opts:boolean("x"):label("x") end, "hafen.ui():check():text(s)")
  refuses(":tooltip(s) on a builder names the check's hover text",
          function() opts:boolean("x"):tooltip("x") end, "hafen.ui():check():tooltip(s)")
  refuses("opts:button(name) names hafen.ui():button() in the panel",
          function() opts:button("b") end, "hafen.ui():button()")
  refuses("opts:label(name) names hafen.ui():label() in the panel",
          function() opts:label("l") end, "hafen.ui():label()")
  refuses("opt:label() on an option is the same cut",
          function() return flag:label() end, "hafen.ui():check():text(s)")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t140", run)   -- the only way in: a suite does not start itself
