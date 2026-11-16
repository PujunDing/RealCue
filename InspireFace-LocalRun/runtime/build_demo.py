# -*- coding: utf-8 -*-
"""Build the InspireFace Android demo with the self-contained toolchain.

Uses the project-local SDK, Gradle 8.7, writable Gradle state and temporary
directory on D:. Existing C: dependencies are consumed through Gradle's
read-only dependency cache, so the build does not add new data to C:.
"""
import os
import sys
import time
import subprocess

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(BASE)
DEMO = os.path.join(PROJECT, "android-demo")
SDK = os.path.join(BASE, "android")
GRADLE_BAT = os.path.join(BASE, "gradle", "gradle-8.7", "bin", "gradle.bat")
JAVA_HOME = r"C:\Users\pjd02\.jdks\jbr-17.0.14"
RO_DEP_CACHE = r"C:\Users\pjd02\.gradle\caches"
LOGS = os.path.join(BASE, "logs")

TASKS = [":app:assembleDebug", ":app:testDebugUnitTest"]


def main():
    os.makedirs(LOGS, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    log_path = os.path.join(LOGS, "build-%s.log" % stamp)

    if not os.path.isfile(GRADLE_BAT):
        print("ERROR: Gradle launcher missing:", GRADLE_BAT)
        print("Run setup_env.py first.")
        return 2
    if not os.path.isfile(os.path.join(SDK, "platforms", "android-35", "android.jar")):
        print("ERROR: self-contained SDK missing android-35")
        return 2

    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["ANDROID_HOME"] = SDK
    env["ANDROID_SDK_ROOT"] = SDK
    env["GRADLE_USER_HOME"] = os.path.join(BASE, "gradle-home")
    env["GRADLE_RO_DEP_CACHE"] = RO_DEP_CACHE
    env["TMP"] = os.path.join(BASE, "tmp")
    env["TEMP"] = os.path.join(BASE, "tmp")
    os.makedirs(env["GRADLE_USER_HOME"], exist_ok=True)
    os.makedirs(env["TMP"], exist_ok=True)
    env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")

    # shell=True lets cmd.exe launch the .bat reliably (Windows cannot
    # CreateProcess a batch file directly); quoting keeps Chinese/spaced paths safe.
    cmdline = '"%s" --offline --no-daemon -p "%s" -PinspirefaceSdkSource=local %s' % (
        GRADLE_BAT, DEMO, " ".join(TASKS))
    print("CMD:", cmdline)
    print("LOG:", log_path, flush=True)

    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        log.write("CMD: %s\n\n" % cmdline)
        proc = subprocess.Popen(cmdline, cwd=DEMO, env=env, shell=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            print(line, flush=True)
            log.write(line + "\n")
        code = proc.wait()

    print("\n=== exit code %d ===" % code)
    print("=== log: %s ===" % log_path)
    apk = os.path.join(DEMO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    print("=== APK: %s (%s) ===" % (apk, os.path.getsize(apk) if os.path.isfile(apk) else "not produced"))
    return code


if __name__ == "__main__":
    sys.exit(main())
