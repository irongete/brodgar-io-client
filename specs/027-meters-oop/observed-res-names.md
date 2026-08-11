# Observed HUD meter res names (027.1, read off a live client)

These are **server-published** (`IMeter.bg`), not client constants — never hard-code them in Java.
`meter:res()` is how an addon author re-derives this list on any server; this file is what 027.3's
docs quote as *observed on this server today*.

| # | `:res()` | what it is | when present |
|---|---|---|---|
| 1 | `gfx/hud/meter/hp`    | health   | always |
| 2 | `gfx/hud/meter/stam`  | stamina  | always |
| 3 | `gfx/hud/meter/nrj`   | energy   | always |
| 4 | `gfx/hud/meter/häst`  | the horse's own bar | while mounted |
| 5 | `gfx/hud/meter/mount` | the mount bar       | while mounted |

The old positional `VITAL_KEYS = {hp, stamina, energy}` mapping saw rows 1–3 and **dropped 4–5**;
they appear and disappear mid-session, which is also what makes `MeterAdded`/`MeterRemoved` (027.2)
worth having.

**Note for 027.3's docs:** row 4 is non-ASCII (`häst`, Swedish for horse — the console renders it
mangled under the Windows code page). So the documented advice for a needle is to use an **ASCII
substring** (`"st"`, `"mount"`, `"hp"`) or to iterate `hafen.meter()` and compare `:res()` yourself,
rather than typing the accented name into a Lua string literal.
