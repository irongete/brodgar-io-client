-- 103.2 -- one page for the UI overlays. Self-checking suite.
--
-- WHAT THIS SHIPS. Docs only: the UI overlays now have a page of their own, and hafen.ui():overlay()
-- is documented there rather than in a section of the page about the windows you build. Nothing in
-- src changed, so what a suite can prove is that the page is TRUE -- which is the only thing about a
-- page that can go wrong without anyone noticing.
--
-- HOW IT IS PROVED. Every example the page prints is run here, in the order it prints them, and each
-- line of each one is asserted to do what the sentence beside it says. Then the claims the page makes
-- in prose rather than in a block: the draw order is attachment order and a re-added key goes to the
-- end, a replaced record ends, a removed one still answers :key(), a key that names nothing is inert,
-- and the four refusals. The banner from the first example is deliberately LEFT UP -- it is what the
-- [manual] line is read against -- and :reload takes it off again.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The keys of :list(), in the order it hands them back -- the page calls that order the draw order.
local function keys(coll)
  local out = {}
  for _, ov in ipairs(coll:list()) do out[#out + 1] = ov:key() end
  return table.concat(out, ",")
end

local function body()
  ---------------------------------------------------------------------------------------------
  -- The page's FIRST example, run as it is printed. The handle is kept because :draw(fn) hands
  -- the overlay back, which is itself one of the page's claims.
  ---------------------------------------------------------------------------------------------
  local paint = function(g, w, h)
    g:color(255, 200, 0)
    g:atext("hello", w / 2, 4, 0.5, 0)          -- centred along the top of the screen
  end
  local banner = hafen.ui():overlay():add("banner"):draw(paint)

  check((banner ~= nil) and (banner:key() == "banner") and (banner:exists() == true)
        and (banner:draw() == paint) and (hafen.ui():overlay():get("banner") == banner),
        "the page's first example installs a live painter under its key",
        tostring(banner) .. " exists=" .. tostring(banner and banner:exists()))

  ---------------------------------------------------------------------------------------------
  -- The page's SECOND example, line by line. A third key is attached beside it, because the draw
  -- order the page states cannot be told apart from insertion order with only two members.
  ---------------------------------------------------------------------------------------------
  local ovs = hafen.ui():overlay()
  local first = ovs:add("meters"):draw(function(g, w, h) g:frect(4, 4, 40, 6) end)

  check((ovs:get("meters") == ovs:get("meters")) and (ovs:get("meters") == first),
        "an added key is :get-able, and interned -- one object per key",
        tostring(ovs:get("meters")) .. " / " .. tostring(first))

  ovs:add("probe"):draw(function(g, w, h) end)
  local before = keys(ovs)
  ovs:add("meters")                             -- the same key again: still one member, now bare
  local after = keys(ovs)

  check(before:find("meters,probe", 1, true) and after:find("probe,meters", 1, true),
        ":list() is the draw order: attachment order, and a re-added key goes last",
        before .. "  ->  " .. after)

  local second = ovs:get("meters")
  check((ovs:count("meters") == 1) and (second:draw() == nil) and (second ~= first)
        and (first:exists() == false),
        "the same key again leaves one member, bare, and ends the one it replaced",
        ovs:count("meters") .. " member(s), :draw()=" .. tostring(second:draw())
          .. ", replaced still exists=" .. tostring(first:exists()))

  ovs:remove("meters")                          -- ...and gone: :get("meters") is nil
  ovs:remove("probe")

  check((ovs:get("meters") == nil) and (second:exists() == false) and (second:key() == "meters"),
        ":remove empties the key, and the removed one still answers :key()",
        tostring(ovs:get("meters")) .. " exists=" .. tostring(second:exists())
          .. " key=" .. tostring(second:key()))

  local back = ovs:remove("nothing-under-this-key")
  check((back == ovs) and (#ovs:list("nothing-under-this-key") == 0)
        and (ovs:count("nothing-under-this-key") == 0),
        ":remove of a key naming nothing is inert, and an unmatched filter lists empty",
        tostring(back == ovs) .. " / " .. tostring(ovs:list("nothing-under-this-key")))

  ---------------------------------------------------------------------------------------------
  -- The refusals the page states. Each must fail, and say why.
  ---------------------------------------------------------------------------------------------
  refuses("a key that is not a string is refused, saying what a key is for",
          function() return ovs:add(7) end, "the key must be a string")

  refuses("a painter that is not a function is refused, naming the signature",
          function() return banner:draw("hello") end, "expects a function")

  refuses("an argument to hafen.ui():overlay() is refused, naming the collection",
          function() return hafen.ui():overlay("banner") end, "takes no arguments")

  refuses("a name a painter does not answer is refused, listing the ones it does",
          function() return banner:paint() end, "has no verb 'paint'")

  check(tostring(banner) == 'Overlay("banner")', "tostring names the overlay by its key",
        tostring(banner))

  manualCheck("look at the top of the screen with the client in the world",
              "'hello' in yellow, centred along the top edge, drawn OVER the HUD"
                .. " (it stays up until :reload)")

  finish()
end

-- The synchronous run is one pcall, so a read that throws becomes one [fail] line and a summary
-- rather than a bare stack trace with no verdict under it.
local function run()
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    finish()
  end
end

hafen.slash():on("t103", run)   -- the only way in: a suite does not start itself
