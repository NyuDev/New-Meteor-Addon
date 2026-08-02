@echo off
REM ---------------------------------------------------------------------------
REM New addon build helper.
REM   * Pins Gradle to PrismLauncher's bundled JDK 21 (system JDK may be newer).
REM   * Applies the AF_UNIX selector fix this PC's JDK needs (otherwise Gradle's
REM     forked daemon dies with "SocketException: Invalid argument: connect").
REM Usage:  build.cmd build      |  build.cmd runClient  |  build.cmd <task>
REM
REM To build ALL versions:  build.cmd chiseledBuild
REM To build one version:   build.cmd "build[1.21.11]"
REM ---------------------------------------------------------------------------
setlocal
REM Auto-detect PrismLauncher's bundled JDK 21 via %APPDATA% (no hard-coded user path).
REM Override by setting JAVA_HOME yourself before calling this script.
if not defined JAVA_HOME set "JAVA_HOME=%APPDATA%\PrismLauncher\java\java-runtime-delta"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Temp"
if not exist "C:\Temp" mkdir "C:\Temp"
call "%~dp0gradlew.bat" %*
endlocal
