-- regex.lua -- a small matcher for the patterns a catalogue carries, so the list can tell which of its
-- strings a pattern answers. The CLIENT applies a pattern as Java does; this only decides what leaves the
-- list. Whole-string match, captures by number. It reads what a translation pattern is made of:
--   literals and escapes (\. \( \\ ...), \d \w \s and their negations, . (a newline only under (?s)),
--   classes [a-z] [^0-9] with escapes inside, groups ( ) and (?: ), alternation |, the quantifiers
--   * + ? {n} {n,} {n,m} and their lazy forms, the anchors ^ $, and the flags (?s) (?i).
-- Anything else -- a lookaround, a backreference, \p{..}, a possessive quantifier -- does not compile,
-- and a row such a pattern answers stays in the list until the client redraws it.
-- Bytes, not characters: a non-ASCII letter is two to four of them, so `.` alone or {n} count differently
-- from Java on such text; .* .+ and the classes do not.

regex = {}

local BUDGET = 5000                       -- node visits per match: a pathological pattern gives up, never stalls

local function parse(src)
  local pos, ncap = 1, 0
  local flags = { s = false, i = false }
  local parseAlt

  local function peek(n) return string.sub(src, pos, pos + (n or 1) - 1) end
  local function take() local c = string.sub(src, pos, pos); pos = pos + 1; return c end
  local function unsupported() error("unsupported") end

  local function escape()                 -- after a backslash: a literal, or a class
    local c = take()
    if c == "d" then return { kind = "set", test = function(ch) return string.find(ch, "%d") ~= nil end }
    elseif c == "D" then return { kind = "set", test = function(ch) return string.find(ch, "%d") == nil end }
    elseif c == "w" then return { kind = "set", test = function(ch) return string.find(ch, "[%w_]") ~= nil end }
    elseif c == "W" then return { kind = "set", test = function(ch) return string.find(ch, "[%w_]") == nil end }
    elseif c == "s" then return { kind = "set", test = function(ch) return string.find(ch, "%s") ~= nil end }
    elseif c == "S" then return { kind = "set", test = function(ch) return string.find(ch, "%s") == nil end }
    elseif c == "t" then return { kind = "lit", ch = "\t" }
    elseif c == "n" then return { kind = "lit", ch = "\n" }
    elseif c == "r" then return { kind = "lit", ch = "\r" }
    elseif c == "" or string.find(c, "%w") then unsupported()   -- \b \p \1 \Q and every other letter or digit
    end
    return { kind = "lit", ch = c }       -- an escaped punctuation mark is itself
  end

  local function class()                  -- after "["
    local negate = false
    if peek() == "^" then negate = true; take() end
    local items, first = {}, true
    while true do
      local c = take()
      if c == "" or c == "[" then unsupported() end          -- unclosed, nested, or [a&&[b]]
      if c == "]" and not first then break end
      local lo
      if c == "\\" then
        local e = escape()
        if e.kind == "set" then items[#items + 1] = { test = e.test } else lo = e.ch end
      else
        lo = c
      end
      if lo then
        if peek() == "-" and peek(2) ~= "-]" and pos < #src then   -- a range
          take()
          local hi = take()
          if hi == "\\" then
            local e = escape()
            if e.kind ~= "lit" then unsupported() end
            hi = e.ch
          end
          items[#items + 1] = { lo = lo, hi = hi }
        else
          items[#items + 1] = { lo = lo, hi = lo }
        end
      end
      first = false
    end
    return { kind = "set", test = function(ch, icase)
      local function hit(x)
        for _, it in ipairs(items) do
          if it.test then
            if it.test(x) then return true end
          elseif x >= it.lo and x <= it.hi then
            return true
          end
        end
        return false
      end
      local found = hit(ch) or (icase and (hit(string.lower(ch)) or hit(string.upper(ch))))
      if negate then return not found end
      return found
    end }
  end

  local function atom()
    local c = take()
    if c == "(" then
      local cap
      if peek(2) == "?:" then
        take(); take()
      elseif peek() == "?" then           -- (?s) (?i) (?si): a flag group, and nothing else
        take()
        local fl = ""
        while string.find(peek(), "^[a-z]") do fl = fl .. take() end
        if fl == "" or take() ~= ")" then unsupported() end
        for f in string.gmatch(fl, ".") do
          if f ~= "s" and f ~= "i" then unsupported() end
          flags[f] = true
        end
        return nil
      else
        ncap = ncap + 1
        cap = ncap
      end
      local alt = parseAlt()
      if take() ~= ")" then unsupported() end
      return { kind = "group", alt = alt, cap = cap }
    elseif c == "[" then return class()
    elseif c == "." then return { kind = "any" }
    elseif c == "^" then return { kind = "bol" }
    elseif c == "$" then return { kind = "eol" }
    elseif c == "\\" then return escape()
    elseif c == "*" or c == "+" or c == "?" or c == "{" or c == ")" or c == "" then unsupported()
    end
    return { kind = "lit", ch = c }
  end

  local function seq()
    local out = {}
    while true do
      local c = peek()
      if c == "" or c == "|" or c == ")" then break end
      local node = atom()
      if node then
        local min, max = 1, 1
        local q = peek()
        if q == "*" then min, max = 0, nil; take()
        elseif q == "+" then min, max = 1, nil; take()
        elseif q == "?" then min, max = 0, 1; take()
        elseif q == "{" then
          local a, comma, b, after = string.match(src, "^{(%d+)(,?)(%d*)}()", pos)
          if not a then unsupported() end
          min = tonumber(a)
          if comma == "" then max = min elseif b == "" then max = nil else max = tonumber(b) end
          pos = after
        end
        local lazy = false
        if q == "*" or q == "+" or q == "?" or q == "{" then
          if peek() == "?" then lazy = true; take() elseif peek() == "+" then unsupported() end
        end
        out[#out + 1] = { node = node, min = min, max = max, lazy = lazy }
      end
    end
    return out
  end

  parseAlt = function()
    local alts = { seq() }
    while peek() == "|" do
      take()
      alts[#alts + 1] = seq()
    end
    return alts
  end

  local ast = parseAlt()
  if pos <= #src then unsupported() end   -- a ")" with no "(" before it
  return { ast = ast, flags = flags, groups = ncap }
end

--- Compile a Java regular expression, or nil where it uses something this matcher does not read.
function regex.compile(src)
  local ok, prog = pcall(parse, src)
  if ok then return prog end
  return nil
end

--- Match the whole of s. Returns the captures by group number (an empty table for none), or nil.
function regex.matches(prog, s)
  local flags, n, steps = prog.flags, #s, 0
  local matchAlt, matchSeq

  local function matchNode(node, pos, caps, k)
    steps = steps + 1
    if steps > BUDGET then error("budget") end
    local kind = node.kind
    if kind == "lit" then
      local ch = string.sub(s, pos, pos)
      if ch == "" then return nil end
      if ch ~= node.ch and not (flags.i and string.lower(ch) == string.lower(node.ch)) then return nil end
      return k(pos + 1, caps)
    elseif kind == "set" then
      local ch = string.sub(s, pos, pos)
      if ch == "" or not node.test(ch, flags.i) then return nil end
      return k(pos + 1, caps)
    elseif kind == "any" then
      local ch = string.sub(s, pos, pos)
      if ch == "" or (ch == "\n" and not flags.s) then return nil end
      return k(pos + 1, caps)
    elseif kind == "bol" then
      if pos == 1 then return k(pos, caps) end
      return nil
    elseif kind == "eol" then
      if pos == n + 1 then return k(pos, caps) end
      return nil
    end
    return matchAlt(node.alt, pos, caps, function(p, c)   -- a group
      if node.cap then
        local c2 = {}
        for i, v in pairs(c) do c2[i] = v end
        c2[node.cap] = string.sub(s, pos, p - 1)
        c = c2
      end
      return k(p, c)
    end)
  end

  local function matchRep(item, count, pos, caps, k)
    local function more()
      if item.max ~= nil and count >= item.max then return nil end
      return matchNode(item.node, pos, caps, function(p, c)
        if p == pos and count >= item.min then return nil end   -- an empty pass past the minimum never ends
        return matchRep(item, count + 1, p, c, k)
      end)
    end
    if count < item.min then return more() end
    if item.lazy then return k(pos, caps) or more() end
    return more() or k(pos, caps)
  end

  matchSeq = function(sq, i, pos, caps, k)
    if i > #sq then return k(pos, caps) end
    return matchRep(sq[i], 0, pos, caps, function(p, c) return matchSeq(sq, i + 1, p, c, k) end)
  end

  matchAlt = function(alt, pos, caps, k)
    for _, sq in ipairs(alt) do
      local r = matchSeq(sq, 1, pos, caps, k)
      if r then return r end
    end
    return nil
  end

  local ok, r = pcall(matchAlt, prog.ast, 1, {}, function(p, c)
    if p == n + 1 then return c end
    return nil
  end)
  if not ok then return nil end            -- over budget: not a match the list will claim
  return r
end

--- The string as a regular expression that matches exactly itself: Java's special characters escaped.
function regex.quote(t)
  return (string.gsub(t, "[\\%^%$%.%|%?%*%+%(%)%[%]%{%}]", "\\%0"))
end
