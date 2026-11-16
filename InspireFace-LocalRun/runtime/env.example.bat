@echo off
REM =====================================================================
REM  RealCue local build environment - EXAMPLE (Windows / cmd.exe)
REM ---------------------------------------------------------------------
REM  1. Copy this file to env.local.bat (gitignored).
REM  2. Edit the paths below to match YOUR machine.
REM  3. In the cmd.exe window used for builds:  call env.local.bat
REM     (PowerShell / Git Bash snippets are in ENVIRONMENT_SETUP.md)
REM  4. Verify with:  python runtime/env_config.py
REM
REM  Nothing here is required if auto-detection succeeds. env_config.py
REM  looks for JDK 17, the machine-wide SDK and the emulator automatically.
REM =====================================================================

REM --- Set these only when auto-detection fails -------------------------
REM JDK 17 ONLY. Android Studio's bundled JBR is usually JDK 21 and is
REM NOT compatible with AGP 8.6.1.
set "REALCUE_JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot"

REM Machine-wide Android SDK: supplies emulator.exe and accepted licenses.
REM This is different from the self-contained SDK under runtime/android.
set "REALCUE_GLOBAL_ANDROID_SDK=C:\Users\YOUR_USERNAME\AppData\Local\Android\Sdk"

REM Full path to emulator.exe when it is not under the SDK above.
REM set "REALCUE_EMULATOR=D:\Android\sdk\emulator\emulator.exe"

REM --- Optional ------------------------------------------------------
REM Extra folder holding large archives (e.g. x86_64-35_r09.zip).
REM set "REALCUE_EXTRA_DOWNLOADS=D:\paper\ifd-build\downloads"

REM Pure-ASCII mirror used by build_ascii.py for non-ASCII repo paths.
REM set "REALCUE_ASCII_MIRROR=D:\paper\ifd-build"

REM First build on a fresh machine: let Gradle download dependencies.
REM Unset it afterwards to keep every write on the D: drive.
REM set "REALCUE_GRADLE_ONLINE=1"

REM Resolve InspireFace from JitPack online instead of a local AAR.
REM set "REALCUE_SDK_SOURCE=jitpack"

echo RealCue environment loaded. Verify with: python runtime/env_config.py
