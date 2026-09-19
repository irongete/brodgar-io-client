# brodgar.io client

[![Download the launcher](https://img.shields.io/github/v/release/irongete/brodgar-io-client-launcher?label=Download%20the%20launcher)](https://github.com/irongete/brodgar-io-client-launcher/releases/latest)

A [Haven & Hearth](https://www.havenandhearth.com/) client, forked from
[dolda2000/hafen-client](https://github.com/dolda2000/hafen-client), documented
[here](https://irongete.github.io/brodgar-io-client/).

- **AddOns** — a Lua addon system.
- **Multi-session** — log in several characters and control them at once.
- **Proximity voice chat** — voice chat with the players near you.

## Play

**[Download the launcher](https://github.com/irongete/brodgar-io-client-launcher/releases/latest)**, unzip it and
run `run.bat`: it installs the client and keeps it at the newest release, on the Release or the Beta channel.

**Log in with Steam**: with the Steam client running, the login screen's *Log in with Steam* button logs
in the Haven & Hearth account your Steam account is linked to (linking is on the game's website, under
*Account security*). The password box beside it is the native login, as ever.

## Build

A JDK 21 or later and `ant`: `ant` builds `bin/`, the development sandbox (the client plus every addon of
the sibling `brodgar-io-client-addons` checkout), `ant run` starts it, and `ant dist` builds `dist/`, a
playable install with only the addons [`etc/release-addons`](etc/release-addons) names. Every build is
version `dev` unless `-Dversion=` says otherwise; the launcher shows the installed version in its title.

## Publish

A **release** is a number, `v6`: a plain GitHub release, which every launcher installs. A **beta** is
`v6.1-beta`, `v6.2-beta`, … — the betas since release 6: GitHub pre-releases, which only a launcher on its
Beta channel installs. So `v5 < v5.1-beta < v5.2-beta < v6`: the Release channel counts 5, 6, 7, and the Beta
channel sees the betas in between. The script counts from the newest version on GitHub, whichever channel it
is on. From a clean `master`, with `git`, `ant` and `gh` (`gh auth login`) on the PATH:

```powershell
.\publish.ps1 -Beta         # the next beta:     v5 -> v5.1-beta, v5.1-beta -> v5.2-beta
.\publish.ps1 -Release      # the next release:  v5.3-beta -> v6, v5 -> v6
```

[`publish.ps1`](publish.ps1) prints the newest version on GitHub, the one it is about to publish and — for a
release after a beta — whether `master` still holds that beta's code, and asks (`-Yes` skips the question);
then it builds `dist/`, zips it, tags `v<version>`, pushes and creates the GitHub release. Notes: `-Notes
notes.md` or `-Message "..."`; without them, the commit subjects since the previous version. `-NoPublish`
builds and tags only. Nothing published counts as release 0: the first beta is `v0.1-beta`, the first
release `v1`. `-Version 6` names the number instead of counting it, for the rare day the count is not what
you mean.

CI does the same, with the same script ([`.github/workflows/publish.yml`](.github/workflows/publish.yml)):
**a push to `master` publishes the next beta**, and the workflow's **Run workflow** button (Actions ▸
Publish) publishes the next release from `master` — the button takes a `version` for the rare day one has
to be named. Every run passes the three checkers in `tools/` first, and a commit that already carries a tag
(published by hand) is left alone. A push that is not to become a beta says `[skip ci]` in its commit
message.

## License

LGPL-3, as upstream — see [COPYING](COPYING).
