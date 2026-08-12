-- 061.6 — a text level survives the server rewriting it. Self-checking suite.
--
-- What the level does is fully automated here. What the SERVER does is not this suite's to cause: the only
-- updates that rewrite a text a level can stand on are a window's "cap", a label's "set" and a button's
-- "ch", and this server sends none of them at anything reachable — its windows carry their caption from
-- the moment they are placed, and what looks like a live number in the HUD is a bar or a widget the client
-- writes itself. So the suite arms the whole claim and scores over what the run reaches:
--
--   * it holds a level on every widget the server is ABLE to rewrite — the server-bound ones that say
--     something — and drops, reads and re-applies it once a second, which is how a rewrite underneath the
--     level would be seen whatever message carried it;
--   * if one lands, both halves are answered on the spot: the widget was still showing the level, and what
--     the client hands back is the server's new text rather than the one from before it;
--   * if none does, what it proves instead is the other side of the same seam — that everything else the
--     server DID send moved neither the level nor the stock under it, which is what a tap that fired too
--     widely, or restocked when nothing was written, would break.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local LEVEL = "061.6"
local WAIT = 10                  -- how long the level is held against whatever the server sends
local BEAT = 1                   -- how often the record is asked whether anything moved under it

local held                       -- every widget this run wrote on: {w =, win =, stock =, want =}
local watches                    -- the appear subscriptions (handle: :remove())
local msgs                       -- the message subscriptions (handle: :off())
local beat, deadline             -- the poll and the end of the run
local first                      -- the round trips are asserted once, on the first widget taken
local done                       -- this run has already reached its verdict
local saw                        -- every text message seen, by name and target type

-- A window's caption is :title(s) and everything else's is :text(s) — one level, two verbs, and the suite
-- holds both kinds at once.
local function reads(h)
  if h.win then
    return h.w:title()
  end
  return h.w:text()
end

local function writes(h, v)
  if h.win then
    h.w:title(v)
  else
    h.w:text(v)
  end
end

local function watch(w)
  if done or not w:id() then
    return                       -- no id: client-built, so no server update can ever name it
  end
  local h = {w = w, win = (w:role() == "window")}
  local ok, stock = pcall(reads, h)
  if not ok or (stock == nil) or (stock == LEVEL) then
    return                       -- nothing to say, or already ours
  end
  if not pcall(writes, h, LEVEL) then
    return                       -- a text entry holds its content rather than saying it; a picture button
  end                            --   has no caption at all. Both refuse, and neither is this suite's case
  h.stock = stock
  held[#held + 1] = h
  if not first then              -- the level itself, before anything server-side is asked of it
    first = true
    check(reads(h) == LEVEL, "one of the client's own widgets takes a text level", reads(h))
    writes(h, nil)
    check(reads(h) == stock, "...and dropping the level gives its own text back", reads(h))
    writes(h, LEVEL)             -- a second write REPLACES the level, so one nil is always enough
    writes(h, LEVEL .. " twice")
    writes(h, nil)
    check(reads(h) == stock, "...and a second write replaces the level rather than stacking on it", reads(h))
    writes(h, LEVEL)
  end
end

local function tally()
  local names = {}
  for k, n in pairs(saw) do
    names[#names + 1] = k .. " x" .. n
  end
  table.sort(names)
  return (#names == 0) and "no text message at all" or table.concat(names, ", ")
end

local function finish()
  if done then
    return
  end
  done = true
  local left = 0
  for _, h in ipairs(held) do     -- restored FIRST: nothing below may leave a level standing
    if h.w:exists() then
      pcall(writes, h, nil)
      if reads(h) == LEVEL then
        left = left + 1
      end
    end
  end
  check(left == 0, "every widget the suite wrote on reads its own text again", left .. " still ours")
  for _, s in ipairs(watches) do
    s:remove()
  end
  for _, s in ipairs(msgs) do
    s:off()
  end
  if beat then
    beat:cancel()
  end
  if deadline then
    deadline:cancel()
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- `shown` is what the widget was saying with the level on it, `stock` what the client handed back when the
-- level came off. The first is the whole claim; the second is what makes the restore honest.
local function verdict(h, shown, stock)
  check(shown == LEVEL, "the level is still there after the server rewrote the text", shown)
  if h.want then
    check(stock == h.want, "...and dropping it hands back the SERVER's value (" .. h.want .. ")", stock)
  else
    check(stock ~= h.stock, "...and dropping it hands back what the server wrote (\"" .. tostring(stock)
          .. "\"), not what the widget said before (\"" .. tostring(h.stock) .. "\")", stock)
  end
  finish()
end

-- Drop the level, read the stock half it was standing over, put it back. A rewrite underneath shows up as
-- a stock that is no longer what the widget said when the level went on — whatever message carried it.
local function poll(scoring)
  if done then
    return
  end
  local moved = 0
  for _, h in ipairs(held) do
    if h.w:exists() then
      local shown = reads(h)
      if pcall(writes, h, nil) then
        local stock = reads(h)
        if stock ~= h.stock then
          verdict(h, shown, stock)
          return
        end
        if shown ~= LEVEL then
          moved = moved + 1      -- the level came off and nothing put it back
        end
        pcall(writes, h, LEVEL)
      end
    end
  end
  if scoring then
    check(moved == 0, "nothing the server sent in " .. WAIT .. "s moved the level or the stock under it"
          .. " (saw: " .. tally() .. ")", moved .. " widget(s) lost the level")
  end
end

local function textMsg(ev)
  if done then
    return
  end
  local w = ev:target()
  local key = ev:msg() .. ":" .. w:type()
  saw[key] = (saw[key] or 0) + 1
  local want = ev:args()[1]
  if type(want) ~= "string" then
    return
  end
  for _, h in ipairs(held) do    -- the exact value, for the run lucky enough to be told it
    if h.w == w then
      h.want = want
      return
    end
  end
end

local function ending()
  if not first then
    check(false, "one of the client's own widgets takes a text level", "nothing server-bound says anything")
    check(false, "...and dropping the level gives its own text back", "the same")
    check(false, "...and a second write replaces the level rather than stacking on it", "the same")
  end
  poll(true)                     -- the last look, and the one that scores
  finish()
end

local function run()
  if held and not done then
    hafen.log():write("[fail] this suite is already waiting -- let it print its summary first")
    return
  end
  pass, fail, manual = 0, 0, 0
  held, watches, msgs, saw, first, done = {}, {}, {}, {}, false, false
  manualCheck("leave the client alone for " .. WAIT .. "s, or open anything you like",
              "no window caption and no label changes by itself -- this server sends no text update a"
              .. " level can stand on, so the re-apply cannot be driven from in game")
  -- An appear subscription fires for what is ALREADY in the tree as well, so this is both the scan and
  -- the watch for whatever is opened next. "*" rather than a list of classes: what a server rewrites is
  -- often a widget published by a resource, whose class name is its own.
  watches[#watches + 1] = hafen.ui():on("*", "appear", watch)
  -- The three a level can stand on, plus the two that write text where it cannot: what the run saw is
  -- half the verdict when it reaches nothing.
  for _, name in ipairs({"cap", "set", "ch", "col", "settext"}) do
    msgs[#msgs + 1] = hafen.event():message():on(name, textMsg)
  end
  beat = hafen.timer():every(BEAT, function() poll(false) end)
  deadline = hafen.timer():after(WAIT, ending)
end

hafen.slash():register("t061-6", run)   -- the only way in: a suite does not start itself
