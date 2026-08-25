# hafen.console: the console command line

A **console command** is what the user types at the client's command line: `:` opens it, and everything
typed there — `:lua`, `:reload`, `:fs`, yours — is a console command the client's console dispatches.
This namespace is both directions onto it. `hafen.console()` **registers** a command, so the user can
drive your addon by typing at it; if you come from WoW that is the `SlashCmdList` pattern, and it is
**unprotected**. [`s:console():run(line)`](#run-a-line-protected) **says** a line, at one character's own
console, and that half is protected.

```lua
local cmd = hafen.console():on("greet", function(args)
  hafen.log():write("hello, " .. (args[1] or "world"))
end)
-- in the console:  :greet Alice
-- later:           cmd:off()
```

**Registering is the client's, saying a line is one character's.** A name is routed once for the whole
client, so a command you register answers from every character — but the console a line runs *in* belongs
to the character whose widget tree holds it, which is why the verb that says one takes an address. The
two halves are described in that order below.

## Subscribe

| Function | Returns | Description |
|---|---|---|
| `hafen.console():on(name, fn)` | a [subscription](event/README.md#subscribe) | route the console command `:name args…` to `fn(args)` |

`name` is a single word: no spaces, and not empty. `fn(args)` receives `args`, a 1-based table of the
whitespace-split words *after* the command name — `"quoted words"` group into one, and `\` escapes the
next character. The command name itself is not in the table.

A command is a subscription like every other `:on` in the API. What you get back is a `Sub`: `cmd:key()`
is the command name and `cmd:off()` stops it, after which typing `:name` reports that no addon handles
it. Ending it is idempotent, and it is done for you on reload or disable.

Subscribe any time; the file body is fine, and there is nothing to wait for. It is reload-safe: editing
your file and running `:reload` swaps the handler with no duplicate and no leaked command.

**When it fails.** The engine's own commands — `lua`, `addons` and `reload` — are reserved, and a name
an existing client command already owns is refused; both raise an error naming the command. If another
*addon* holds the name, the newest subscription wins and takes it over, and the console records the
reassignment — so two addons claiming `:sort` is a first-come, last-served race rather than an error.

**A command you subscribe to answers from every character.** The name is routed once, for the client
rather than for a login, so with several characters up it runs from whichever one's console you type it
into and goes on running as you tab between them. There is nothing to re-subscribe on a switch.

**Where it runs.** The console dispatches on the UI thread, the same thread as everything else your
addon does, so a command never races your own handlers. It runs under the same watchdog and error
isolation: an error in it is logged, not propagated.

## Read what you registered

`hafen.console()` **is** the
[collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of your addon's own
commands, so the section you subscribe through is the section you read back:

| Verb | Gives you |
|---|---|
| `hafen.console():list(filter)` | every command of yours, as an array of `Sub`s |
| `hafen.console():count(filter)` | how many |
| `hafen.console():find(filter)` | the first that matches, or `nil` |
| `hafen.console():get(name)` | the command registered under `name`, or `nil` |

The members are the very `Sub`s `:on` handed you, so `==` finds the one you are holding and `sub:key()`
is its name. A string `filter` is a substring match on that name; a function filter is called with each
`Sub`. Only your addon's commands are here — another addon's are its own, and the client's built-in
commands were never subscriptions.

`:get(name)` answers `nil` for a name you never registered: there is nothing for it to be a handle to,
since the registration *is* the subscription. All four verbs are **unprotected**.

```lua
for _, cmd in ipairs(hafen.console():list()) do
  hafen.log():write(":" .. cmd:key())
end

local greet = hafen.console():get("greet")
if greet then greet:off() end
```

## Run a line (protected)

### `s:console():run(line)`

Run `line` through **that character's** console, exactly as though the user had typed it there — the same
dispatcher, the same tree walk, the same command resolution. It hands the section back, so lines chain.
It needs the `console.run` [permission](../guides/permissions.md).

```lua
hafen.client():options():keybindings():on("logout", function()
  for _, s in ipairs(hafen.session():list()) do
    if s ~= hafen.session():current() then s:console():run("lo") end   -- drop the alts, keep this one
  end
end)
```

`line` is the command and the rest of the line, **without the opening colon**: the colon opens the console
line and the client never sees it as part of one. `run("reload")`, not `run(":reload")`.

**A line belongs to a character.** `:lo` logs out the character it is run at, `:gl` writes that
character's graphics settings, and `:act`, `:belt`, `:afk`, `:cam` and `:exportmap` each answer for the
character whose own window registered them — so `s:console():run("lo")` logs out the character `s` names,
drawn or not. That is the whole reason the verb hangs on a [session](session.md) rather than on `hafen`:
there is one command line per login, not one per client.

The client's own splitter is what splits the line, so `args` arrive exactly as they do for a typed
command: `"quoted words"` group into one, `\` escapes the next character, and `:lua` reads the literal
text back rather than the split words.

```lua
hafen.session():current():console():run('lua hafen.session():count()')
```

**A command that fails is not your error.** `run("zzz")` returns normally and writes `zzz: no such
command` where the console puts a refusal: that character's **System** log, and its own on-screen notice.
That is the console's own channel and the one the user reads, so nothing is raised for you to catch and
nothing appears behind your addon's name. Before that character's HUD is up there is nowhere for the log
half to go and the line is dropped; the notice half still shows.

> Once the HUD is up, those two are one place: the client logs its on-screen notices to the System channel
> as well, so **one failed command leaves two identical System lines**. Read the newest, and do not count
> lines to count failures.

`run("reload")` **queues** the reload rather than performing it — your addon is rebuilt at the next safe
point, so the call returns and the lines after it still run in the layer that made it.

A line runs **synchronously, on your own thread**, exactly as a typed one does, and a command body may run
a line of its own. A command that runs *itself* recurses until the instruction watchdog cuts it.

**When it fails.** Each raises, naming what is wrong: a missing or non-string `line`; one that is empty or
only whitespace; one that opens with `:`, which names the spelling without it; and a session the client
holds no console for — one that has ended, or one between widget trees. [`s:exists()`](session.md#read)
is the test for the last.

`hafen.console():run(...)` raises too, naming this verb: registering is the client's and saying a line is
one character's, so the door an author reaches for first says where the other half lives.

**Why it is protected.** The key is `console.run`, and it is the widest in the
[catalogue](../guides/permissions.md#the-catalogue): one key covers every command the client dispatches,
on any of your characters. `:lua` is among them, and `:lua` evaluates against the whole standard library
outside the sandbox your addon runs in — so an addon granted this can do what your own console can. The
line the user reads when they enable you says exactly that.

## See also

- [`hafen.session`](session.md) — the address a line is said at, and the rest of what hangs on one
- [`hafen.event`](event/README.md) — the `Sub` this hands back, and every other subscription in the API
- [`hafen.log`](log.md) — printing back to the console the command was typed into
- [`hafen.client():options():keybindings()`](client/keybindings.md) — a hotkey, the other way a user
  invokes an addon by hand
- [`hafen.ui`](ui/README.md) — a window, for anything the user does more than occasionally
