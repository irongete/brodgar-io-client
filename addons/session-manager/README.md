# Session Manager

One row per login the client holds. Each row is the character's name — the account before that
character is in the world — and the row for the session on screen is marked `*`.

| You do | It does |
|---|---|
| press a row's name | hands the screen to that character |
| press a row's `X` | logs that character out |
| press the `next` hotkey | goes to the next login, and round |
| type `:sessions` | opens the window, or closes it |

`:session add` is still how a second account is logged in — this manages the logins that exist.

*Suggested key: `Ctrl+Tab` — assign it in Options ▸ Keybindings ▸ Session Manager.*

An addon hotkey starts unbound, because the client gives one key to one action and an addon that
claimed a key already in use would lose it and leave you with a hotkey that never fires. So the key
above is a suggestion, and yours is the assignment.

## What it asks for

`session.close`, the permission behind the `X` — *log out any of your characters*. The client asks
you to approve it the first time you enable the addon; nothing else here leaves your client.

## Where it keeps its place

Where you drag the window is saved for the **account**, not for the character on screen, so the
switcher stands in the same place whichever character you are looking at and after a restart. The
window itself lives above every session, so moving the screen does not move it, rebuild it, or make
it blink.
