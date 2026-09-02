@echo off
rem ----------------------------------------------------------------------
rem  Haven & Hearth client launcher.
rem
rem  This file is a template. On build, `ant` copies it next to hafen.jar
rem  and its dependency jars: into bin/ (the "bin" target, the development
rem  sandbox) and into dist/ (the "release" target, the install handed to a
rem  player). Double-click run.bat, or run it from a terminal, to start the
rem  client. Any extra arguments are passed through to the client.
rem ----------------------------------------------------------------------
cd /d "%~dp0"
java ^
  --add-exports=java.base/java.lang=ALL-UNNAMED ^
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED ^
  --add-exports=java.desktop/sun.java2d=ALL-UNNAMED ^
  --enable-native-access=ALL-UNNAMED ^
  -Dsun.java2d.uiScale.enabled=false ^
  -Djava.net.preferIPv6Addresses=system ^
  -jar hafen.jar -U http://brodgar.io/res/ %*
if errorlevel 1 pause
