# Steam: the SDK bridge, the login and the Workshop

> What upstream ships for Steam, and what it needs from the process. Everything is in `src/haven/`;
> the SDK is `steamworks4j` (`com.codedisaster.steamworks`), in `lib/ext/steamworks/`, whose natives
> jar rides beside it.

## The bridge

| What | Where |
|---|---|
| The one instance | `Steam.get()` — lazily loads the natives and calls `SteamAPI.init()`; **null** when either fails, and `API.failed` latches so every later call answers null at once. Nothing touches the SDK before the first `get()` |
| What `init` needs | the Steam client running and logged in, and the process identified as the game: **app id 3051280**, from the `SteamAppId` environment variable (Steam sets it on a process it starts) or from `steam_appid.txt` in the **working directory**. Without either, `SteamAPI.init()` fails with `[S_API]` lines on stderr — noise, not an error |
| Natives | `Steam.SteamLibraryLoaderJogl` — JOGL's `TempJarCache`/`addNativeJarLibs` on `SteamAPI.class`, so the natives jar is found by name beside `steamworks4j.jar` (`steamworks4j-natives-windows-amd64.jar`, `64` appended on 64-bit Windows) |
| Callbacks | one daemon thread (`Steam.listen`) pumps `SteamAPI.runCallbacks()` every 100 ms; `Listener`/`Waiter` turn a callback id into a blocking `get()` |
| Identity and presence | `appid()`, `userid()`, `displayname()`, `setrp`/`setparty` (rich presence), `browse` (the overlay's browser), `overlaypos` |
| Cloud files | `readfile`/`writefile`, `checkstorage`/`enablestorage`; `SteamCache` is a `ResCache` over them, keyed `<appid>/<userid>/` — **unused** by the client's own paths |
| Workshop items | `Steam.UGItem` — `fid()`, `installed()`/`stale()`/`fetching()`, `download`, `path()` (where Steam put the content), `details()`; `UGItem.Update` is the builder an upload drives (`title`, `description`, `preview`, `contents(dir)`, `setprivate`/`setfriendsonly`/`setpublic`, `submit(message)`, `getprogress`) |

## The login

| What | Where |
|---|---|
| Which screen | `LoginScreen.authmech` (`haven.authmech`, default `native`): `native` builds `Credbox`, `steam` builds `Steambox`, anything else throws. The Steam launcher's `.hl` sets `steam` |
| The Steam credentials | `SteamCreds` — the constructor throws `IOException("Steam is not running")` when `Steam.get()` is null; `tryauth` takes a `Steam.webticket()` (`ISteamUser.GetAuthTicketForWebApi`, cancelled when the try-with-resources closes) and sends `steam ticket <hex>` to the auth server, which answers `ok <account>` or `no <reason>` |
| Who logs in | the Haven account the Steam account is **linked** to — linking is on the website, not in the client. `Session.User(acct).readname(displayname)`: the account name is the server's, the readable name the Steam persona |
| What is remembered | nothing: `SteamCreds.authname()` is null, so `Bootstrap` stores no `loginname` and no token (`Bootstrap.settoken` returns on a null user). The native path's "Remember me" has no Steam counterpart |
| `Steambox` | a label, the login button, and `steam_autologin`, a **static** flag: the first `Steambox` ever ticked logs in by itself, later ones wait for the click |
| The store | `SteamStore` — `steamsvc` from the services directory (`Config.Services`, `haven.svcdir`); `OptWnd` shows *Visit store* only when that service is named **and** `Steam.get()` answers. With no services directory it never shows |
| Mid-game initialisation | `Partyview.updsteam`/`destroy` call `Steam.get()` on every party change for rich presence — so a client with an app id and Steam open joins the Steam session the first time a party forms, whichever screen logged in |

## The Workshop

| What | Where |
|---|---|
| The launcher Steam runs | `WorkshopLauncher.main` — with no Steam (`Steam.get()` null) it is `haven.Client.main`; otherwise it downloads stale items (`Updater`), reads `workshop-client.properties` from each item's `path()`, and offers the `Chooser` — *Default client* plus one row per item — unless `launchlast` finds the remembered choice |
| An item's file | `workshop-client.properties`, at the item's root: `name`, `description` or `description-file`, `preview-image`, `visibility` (`private`/`friends`/`public`), `workshop-id`, then **one** of the two launch modes |
| Direct launch | `main-class` + `class-path` (`:`-separated, relative to the item; a jar's own manifest `Class-Path` is honoured): a `URLClassLoader` over a `ClassUnloader` that hides the launcher's own jar, `sysprop.<name>=<value>` lines set system properties, then `main(String[0])` **in the launcher's JVM** — on the AWT event thread from the chooser's button, on the launcher's own main thread when the choice was remembered. `launch` answers false and the JVM lives on as the client's — **and it never ends by itself**: a `main` that returns leaves it running (see the gotchas) |
| Chained launch | `launcher=<file>` — a `.hl` for loftar's `launcher.jar` beside the game, run as a **new JVM** (`findjvm()`, the `java.home` of the running one) |
| The remembered choice | `Utils.getpref("workshop/last-client")` and `workshop/skip-chooser` (the item ids the choice was made over — a subscription change voids it), written by `Chooser.save` **after** `launch` returns. A `main` that calls `System.exit` skips it |
| The upload tool | `haven.SteamWorkshop upload [-q] DIR [MESSAGE]` — reads `DIR/workshop-client.properties`, uploads the **whole directory** as the item's content (`update.contents(dir)`, tag `Client`), and needs `Steam.get()`: Steam running and the app id, `SteamAppId` in the environment being what its own error suggests. Without `workshop-id` it creates the item and prints the id to put in the file; with one it updates that item. It never writes the file itself |

**Gotchas that cost time.**
- **The app id is per working directory, not per jar.** `steam_appid.txt` is read from where the JVM was
  started, so a start from another folder (`ant run` from the source root) finds nothing and the login
  answers "Steam is not running" although Steam is; `SteamAppId` in the environment is what loftar
  recommends for exactly that case.
- **A failed `init` is final for the process.** `API.failed` is never reset: Steam started after the
  first `Steam.get()` needs a client restart.
- **A direct-launch item runs on the launcher's JVM and classpath.** Its flags are the launcher's
  (`--add-exports` for JOGL, whatever memory it has), not the item's; an item that wants its own JVM
  starts one from `main` or chains a `.hl`. The `Class-Path` of a jar on the item's `class-path` resolves
  relative to that jar, which is how one `hafen.jar` brings its `lib/` along.
- **A direct-launch `main` that starts a process and returns leaves the launcher's JVM alive, and Steam
  waiting.** The threads the launcher itself leaves (`Steam.listen`, the error reporter,
  `DeadlockWatchdog`) are daemons, but AWT's auto-shutdown never fires in that JVM — `AWT-Shutdown` waits
  in `AWTAutoShutdown.run` and `AWT-EventQueue-0` stays, `jstack` shows — so the JVM outlives the chooser,
  orphaned, its Steam API session open; Steam shows the game running and its Stop button waits on it
  forever. A `main` of that kind has to `System.exit` the JVM itself — a moment after returning, since
  `Chooser.save` (the remembered choice) runs only after `launch` returns.
