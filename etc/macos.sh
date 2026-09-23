#!/bin/sh
# ----------------------------------------------------------------------
#  Haven & Hearth client launcher for macOS.
#
#  This file is a template. On build, `ant` copies it next to hafen.jar
#  and its dependency jars: into bin/ (the "bin" target, the development
#  sandbox) and into dist/ (the "dist" target, the install a release is
#  made of). Run ./macos.sh from a terminal to start the client. Any extra
#  arguments are passed through to the client: resources come from where
#  haven-config.properties beside this file says (the game's own server as
#  built; the launcher rewrites that line from its checkbox) unless you add
#  -U https://res.brodgar.io/, the brodgar.io resource cache (what
#  `ant run` uses).
#
#  The client is started on the Cocoa/CGL toolkit, as `ant run` does:
#  there is no arm64 JOGL native, and the LWJGL AWT canvas it would fall
#  through to draws on the AWT event thread, which AppKit kills the
#  process for. CGL ships in hafen-panama.jar, so it needs Java 22; the
#  client refuses a toolkit it has not got, so on an older Java none is
#  named.
# ----------------------------------------------------------------------
cd "$(dirname "$0")" || exit 1

toolkit=
major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
if [ "${major:-0}" -ge 22 ]; then
  toolkit=-Dhaven.toolkit=cgl
fi

exec java \
  --add-exports=java.base/java.lang=ALL-UNNAMED \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/sun.java2d=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.java2d.uiScale.enabled=false \
  -Djava.net.preferIPv6Addresses=system \
  $toolkit \
  -jar hafen.jar "$@"
