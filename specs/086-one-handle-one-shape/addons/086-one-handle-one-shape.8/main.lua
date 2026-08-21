-- 086.8 -- the last colour reader: sheet:stock() hands back KEYED colours, so the stylesheet's
-- two readers of one property agree. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why -- here, naming both spellings.
local function refuses(what, fn, ...)
  local want = {...}
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- ---- reading a stylesheet document -----------------------------------------------------------

-- A colour is keyed when all four components answer by name, and positional when [1] answers at all.
local function keyed(c)
  return (type(c) == "table") and (type(c.r) == "number") and (type(c.g) == "number")
     and (type(c.b) == "number") and (type(c.a) == "number")
end

local function shapeOf(c)
  if type(c) ~= "table" then return tostring(c) end
  local named = (c.r ~= nil) and "r,g,b" or "no r"
  return "{" .. named .. (c[1] ~= nil and " AND [1],[2],[3]" or "") .. ", a=" .. tostring(c.a) .. "}"
end

-- Every colour a document carries, found by the grammar's own two places: the value of a `color`
-- property, and each member of a `palette`. Walked by KEY rather than by shape, because a padding
-- is four numbers as well and a screen offset is two.
local function colours(v, key, path, out)
  if type(v) ~= "table" then return out end
  if (key == "color") and (v.palette == nil) and (v.generate == nil) then
    out[#out + 1] = {c = v, path = path}
    return out
  end
  if key == "palette" then
    for i = 1, #v do
      out[#out + 1] = {c = v[i], path = path .. "[" .. i .. "]"}
    end
    return out
  end
  for k, sub in pairs(v) do
    colours(sub, k, path .. "." .. tostring(k), out)
  end
  return out
end

local function run()
  local sheet = hafen.ui():sheet()
  local stock = sheet:stock()

  -- The catalogue's own colours: keyed, all of them, and positional, none of them. Only the pair
  -- proves the shape MOVED rather than widened.
  local found = {}
  for site, rule in pairs(stock) do
    local was = #found
    colours(rule, nil, "", found)
    for i = was + 1, #found do found[i].site = site end
  end
  local nk, np, bad = 0, 0, nil
  for i = 1, #found do
    local f = found[i]
    if keyed(f.c) then nk = nk + 1 else bad = bad or (f.site .. f.path .. " " .. shapeOf(f.c)) end
    if (type(f.c) == "table") and (f.c[1] ~= nil) then
      np = np + 1
      bad = bad or (f.site .. f.path .. " " .. shapeOf(f.c))
    end
  end
  check((#found > 0) and (nk == #found),
        ("sheet:stock() answers .r .g .b .a on every colour (%d/%d)"):format(nk, #found),
        bad or "no colour anywhere in the catalogue")
  check((#found > 0) and (np == 0),
        ("...and [1] on none of them (%d/%d positional)"):format(np, #found),
        bad or "no colour anywhere in the catalogue")

  -- The other reader of the same property, on a widget this suite owns: the same four numbers under
  -- the same four keys, through w:rule() and through w:style() alike.
  local ex = found[1] and found[1].c
  local w = hafen.ui():widget()
  local same, got = false, "no colour in the catalogue to write"
  if ex then
    w:rule():color(ex)
    local st = w:style() or {}
    local rd = w:rule():color()
    same = keyed(st.color) and keyed(rd) and (st.color[1] == nil)
       and (st.color.r == ex.r) and (st.color.g == ex.g) and (st.color.b == ex.b)
       and (st.color.a == ex.a) and (rd.r == ex.r) and (rd.a == ex.a)
    got = "stock " .. shapeOf(ex) .. " / w:style() " .. shapeOf(st.color)
       .. " / w:rule() " .. shapeOf(rd)
  end
  check(same, "w:style() and w:rule() carry the stock colour under the same four keys", got)
  w:destroy()

  -- The round trip that must not break -- and a colour read back out of the installed sheet.
  local ok, err = pcall(function() sheet:load(stock):install() end)
  check(ok, "sheet:load(sheet:stock()):install() completes", err)
  local site = found[1] and found[1].site
  local back, bk, nsite = {}, 0, 0
  if site then colours(sheet:rule(site):info(), nil, "", back) end
  for i = 1, #found do
    if found[i].site == site then nsite = nsite + 1 end
  end
  for i = 1, #back do
    if keyed(back[i].c) and (back[i].c[1] == nil) then bk = bk + 1 end
  end
  check((#back > 0) and (bk == #back) and (#back == nsite),
        ("a colour read back out of the loaded sheet still answers .r (%d/%d on \"%s\")")
          :format(bk, #back, tostring(site)),
        ("%d keyed of %d read back, against %d in the catalogue"):format(bk, #back, nsite))

  -- Changing the reader did not disturb the two keys whose colour the client WALKS.
  refuses("\"chat.urgent\" still refuses a single colour, naming both spellings",
          function() sheet:rule("chat.urgent"):color{200, 210, 220} end, "palette", "generate")

  manualCheck("look at the client now -- this suite has the round-tripped stock sheet installed"
              .. " (`:reload` or disabling this addon drops it)",
              "window chrome, chat and tooltips exactly the colours they were")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t086-8", run)
