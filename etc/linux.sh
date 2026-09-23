#!/bin/sh
# ----------------------------------------------------------------------
#  Haven & Hearth client launcher for Linux.
#
#  This file is a template. On build, `ant` copies it next to hafen.jar
#  and its dependency jars: into bin/ (the "bin" target, the development
#  sandbox) and into dist/ (the "dist" target, the install a release is
#  made of). Run ./linux.sh from a terminal to start the client. Any extra
#  arguments are passed through to the client: resources come from where
#  haven-config.properties beside this file says (the game's own server as
#  built; the launcher rewrites that line from its checkbox) unless you add
#  -U https://res.brodgar.io/, the brodgar.io resource cache (what
#  `ant run` uses).
# ----------------------------------------------------------------------
cd "$(dirname "$0")" || exit 1

exec java \
  --add-exports=java.base/java.lang=ALL-UNNAMED \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/sun.java2d=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.java2d.uiScale.enabled=false \
  -Djava.net.preferIPv6Addresses=system \
  -jar hafen.jar "$@"
