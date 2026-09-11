-- Essentials: what the client does for a character the moment it enters the world.
--
-- No window and no command. Every setting is on its page, Options ▸ AddOns ▸ Essentials, and each one is
-- read when a session enters the world: the toggles under Adventure ▸ Toggle, the inventory window, and the
-- movement speed. Nothing runs from the file body or from a timer of its own, and nothing runs for a
-- character already playing: a :reload announces SessionEnteredWorld again for every session in the world,
-- and that is not a login, so the sessions up when this file runs are skipped once.

local opts = hafen.client():options():addon()

local crime     = opts:boolean("crime"):default(false):add()
local swim      = opts:boolean("swim"):default(false):add()
local inventory = opts:boolean("inventory"):default(false):add()
local speed     = opts:choice("speed"):choices{"None", "Crawl", "Walk", "Run", "Sprint"}:default("None"):add()

-- THE PAGE. An option is a stored value and draws nothing; what shows it is a control built here, on the
-- column the client hands over each time Options > AddOns > Essentials is opened, and bound to the option
-- -- so ticking a box writes the option, and the box comes back ticked on the next visit, after a reload
-- and after a restart, because the value never left the client.
opts:panel(function(root)
  root:gap(4)
  hafen.ui():label():parent(root):text("Toggles at login")
  hafen.ui():check():parent(root):text("Criminal Acts")
    :tooltip("Turn Criminal Acts on when a character enters the world"):bind(crime)
  hafen.ui():check():parent(root):text("Swimming")
    :tooltip("Turn Swimming on when a character enters the world"):bind(swim)
  hafen.ui():check():parent(root):text("Open inventory on login")
    :tooltip("Open the inventory window when a character enters the world"):bind(inventory)
  hafen.ui():label():parent(root):text("Set speed at login")
  hafen.ui():dropdown():parent(root):size(120)
    :tooltip("The movement speed a character is put on when it enters the world; None leaves it as it is")
    :bind(speed)
end)

-- The toggles, each by its resource name: the entry in the action menu, and the buff the server keeps on
-- the bar while it is on — so a toggle already on is left on rather than pressed off.
local TOGGLES = {
  {option = crime, res = "paginae/act/crime", name = "Criminal Acts"},
  {option = swim,  res = "paginae/act/swim",  name = "Swimming"},
}
local SPEEDS = {Crawl = 1, Walk = 2, Run = 3, Sprint = 4}   -- the 1-based positions s:speed():get takes

local SETTLE   = 3     -- seconds after entering the world before the buff bar is read: the buffs stream in
local INTERVAL = 0.5   -- seconds between retries of what is not ready yet
local DEADLINE = 20    -- seconds after which what has not happened is given up on, with a line saying so

-- A job is {what, run}: run(elapsed) answers true once it is done, and false to be retried next tick.

local function toggledOn(s, t)                     -- the buff the server puts up while the toggle is on
  return s:buff():find(t.res) ~= nil or s:buff():find(t.name) ~= nil
end

local function toggleJob(s, t)
  return {what = t.name, run = function(elapsed)
    if elapsed < SETTLE then return false end
    local pag = s:menugrid():get(t.res)
    if not pag or not pag:name() then return false end      -- not in the menu yet, or still loading
    if toggledOn(s, t) then
      hafen.log():write(t.name .. " was already on")
    else
      pag:use()
      hafen.log():write(t.name .. " turned on")
    end
    return true
  end}
end

local function inventoryJob(s)
  return {what = "inventory", run = function()
    local inv = s:ui():inventory()
    if not inv then return false end
    inv:parent():visible(true)                              -- the window around the grid
    hafen.log():write("inventory opened")
    return true
  end}
end

local function speedJob(s, name)
  return {what = "speed", run = function()
    local sp = s:speed():get(SPEEDS[name])
    if not sp then return false end                         -- the selector has not streamed in yet
    if sp:available() then
      s:speed():set(sp)
      hafen.log():write("speed set to " .. name)
    else
      hafen.log():write(name .. " is locked, speed left as it is")
    end
    return true
  end}
end

-- Retry the jobs every INTERVAL until each has run, that login is over, or DEADLINE passes. A job that
-- raises is dropped with its message: one line, never a timer throwing on every tick.
local function run(s, jobs)
  local who, elapsed, timer = s:character(), 0
  timer = hafen.timer():every(INTERVAL, function()
    elapsed = elapsed + INTERVAL
    if s:character() ~= who then timer:cancel(); return end  -- logged out, or playing someone else now
    for i = #jobs, 1, -1 do
      local ok, done = pcall(jobs[i].run, elapsed)
      if not ok then hafen.log():write(jobs[i].what .. ": " .. tostring(done)) end
      if not ok or done then table.remove(jobs, i) end
    end
    if #jobs == 0 then
      timer:cancel()
    elseif elapsed >= DEADLINE then
      for _, job in ipairs(jobs) do
        hafen.log():write(job.what .. ": not ready after " .. DEADLINE .. "s, given up")
      end
      timer:cancel()
    end
  end)
end

local announced = {}          -- accounts whose next SessionEnteredWorld is a :reload's re-announcement
for _, s in ipairs(hafen.session():list()) do
  if s:character() then announced[s:user()] = true end
end

hafen.event():on("SessionEnteredWorld", function(s)
  if announced[s:user()] then announced[s:user()] = nil; return end
  local jobs = {}
  for _, t in ipairs(TOGGLES) do
    if t.option:value() then jobs[#jobs + 1] = toggleJob(s, t) end
  end
  if inventory:value() then jobs[#jobs + 1] = inventoryJob(s) end
  if speed:value() ~= "None" then jobs[#jobs + 1] = speedJob(s, speed:value()) end
  if #jobs > 0 then run(s, jobs) end
end)
