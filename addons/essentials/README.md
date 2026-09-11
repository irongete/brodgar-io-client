# Essentials

What the client does for a character the moment it enters the world. No window and no command: the rows
are in **Options ▸ AddOns ▸ Essentials**, and each one is read when a session enters the world.

| Row | Does |
|---|---|
| **Toggles at login** — Criminal Acts, Swimming | presses Adventure ▸ Toggle ▸ that toggle, unless its buff is already on the bar |
| **Open inventory on login** | opens the inventory window |
| **Set speed at login** | puts the character on Crawl, Walk, Run or Sprint; `None` leaves it as it is |

Everything is off until you turn it on. The toggles wait a few seconds for the buff bar to stream in
before they read it, and each row writes one line to the console saying what it did.

It needs `menugrid.use` (the toggles) and `speed.set` (the speed), so it starts disabled: tick it in the
AddOns panel, accept the two keys, and **Reload UI**. A `:reload` announces every character already in the
world again, and that is not a login — nothing runs for them.
