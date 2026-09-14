# Brodgar.io Client

[![Download the launcher](https://img.shields.io/github/v/release/irongete/brodgar-io-client-launcher?label=Download%20the%20launcher)](https://github.com/irongete/brodgar-io-client-launcher/releases/latest)

A [Haven & Hearth](https://www.havenandhearth.com/) client, forked from
[dolda2000/hafen-client](https://github.com/dolda2000/hafen-client).

- **AddOns** — a Lua addon system, documented in `docs/addons/`.
- **Multi-session** — log in several characters and control them at once.
- **Proximity voice chat** — voice chat with the players near you.

## Play

**[Download the launcher](https://github.com/irongete/brodgar-io-client-launcher/releases/latest)**, unzip it and
run `run.bat`: it installs the client and keeps it at the newest release, on the Release or the Beta channel.

**Log in with Steam**: with the Steam client running, the login screen's *Log in with Steam* button logs
in the Haven & Hearth account your Steam account is linked to (linking is on the game's website, under
*Account security*). The password box beside it is the native login, as ever.

## Build

A JDK 21 or later and `ant`: `ant` builds a runnable `bin/`, `ant run` starts it, `ant release` builds
`dist/`.

## Release

From a clean `master`, with `git`, `ant` and `gh` (`gh auth login`) on the PATH:

```powershell
.\release.ps1                 # highest vX.Y.Z tag with Z+1: v0.1.0 -> 0.1.1
.\release.ps1 0.2.0           # this version
.\release.ps1 0.2.0-beta.1    # a beta: a GitHub pre-release, for the launcher's Beta channel
```

[`release.ps1`](release.ps1) builds `dist/` with the addons in [`etc/release-addons`](etc/release-addons),
zips it, tags `v<version>`, pushes and creates the GitHub release. Notes: `-Notes notes.md` or
`-Message "..."`; without them, the commit subjects since the last tag. `-NoPublish` builds and tags only.

## License

LGPL-3, as upstream — see [COPYING](COPYING).
