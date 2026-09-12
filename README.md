# Brodgar.io Client

[![Latest release](https://img.shields.io/github/v/release/irongete/brodgar-io-client?include_prereleases)](https://github.com/irongete/brodgar-io-client/releases)

A [Haven & Hearth](https://www.havenandhearth.com/) client, forked from
[dolda2000/hafen-client](https://github.com/dolda2000/hafen-client).

- **AddOns** — a Lua addon system, documented in `docs/addons/`.
- **Multi-session** — log in several characters and control them at once.
- **Proximity voice chat** — voice chat with the players near you.

## Play

[Download the launcher](https://github.com/irongete/brodgar-io-client-launcher/releases/latest), unzip it
and run `run.bat`: it keeps the client at the newest release, on the Release or the Beta channel.

Without it: the zip from [the releases](https://github.com/irongete/brodgar-io-client/releases), unzipped,
and `run.bat` with a Java 17 or later.

## Build

A JDK 21 or later and `ant`: `ant` builds a runnable `bin/`, `ant run` starts it, `ant release` builds
`dist/`.

## License

LGPL-3, as upstream — see [COPYING](COPYING).
