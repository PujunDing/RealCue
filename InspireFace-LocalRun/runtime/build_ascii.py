# -*- coding: utf-8 -*-
"""Build the demo from a pure-ASCII mirror to dodge AGP's non-ASCII path problem.

AGP 8.6.1 / aapt2 / Gradle test workers mangle the non-ASCII workspace path on
Windows: aapt2 cannot stat android.jar and unit tests all fail with
ClassNotFoundException. android.overridePathCheck=true gets past the guard but
does not fix the downstream tools. So we mirror sources + SDK + Gradle into a
pure-ASCII directory (see env_config.ASCII_MIRROR), build there, and copy the
APK back. If the repository is already on an ASCII path this script is not
needed: use runtime/run_gradle.py instead.
"""
import os
import sys
import time
import shutil
import subprocess

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(BASE)
DEMO_SRC = os.path.join(PROJECT, "android-demo")
SDK_SRC = os.path.join(BASE, "android")
AAR_SRC = os.path.join(BASE, "downloads", "inspireface-1.2.4.post1.aar")
GRADLE_SRC = os.path.join(BASE, "gradle", "gradle-8.7")

sys.path.insert(0, BASE)
import env_config as cfg  # noqa: E402  (machine-specific paths live here)

MIRROR = cfg.ASCII_MIRROR
M_DEMO = os.path.join(MIRROR, "android-demo")
M_SDK = os.path.join(MIRROR, "sdk")
M_AAR = os.path.join(MIRROR, "inspireface-1.2.4.post1.aar")
M_GRADLE = os.path.join(MIRROR, "gradle-8.7")
JAVA_HOME = cfg.JAVA_HOME
RO_DEP_CACHE = cfg.RO_DEP_CACHE
LOGS = cfg.LOGS

IGNORE = shutil.ignore_patterns(
    ".git", "build", ".gradle", ".idea", "*.iml",
    "local.properties", "sdk.local.properties", ".DS_Store")


def copy_dir(src, dst, label):
    if os.path.isdir(dst):
        print("[skip] %s mirror already exists" % label, flush=True)
        return
    print("[copy] %s -> %s" % (label, dst), flush=True)
    shutil.copytree(src, dst, ignore=IGNORE if label == "demo" else None)
    print("[ok] %s copied" % label, flush=True)


def main():
    os.makedirs(MIRROR, exist_ok=True)
    os.makedirs(LOGS, exist_ok=True)

    copy_dir(DEMO_SRC, M_DEMO, "demo")
    copy_dir(SDK_SRC, M_SDK, "sdk")
    copy_dir(GRADLE_SRC, M_GRADLE, "gradle")
    if not os.path.isfile(M_AAR):
        shutil.copy2(AAR_SRC, M_AAR)
        print("[ok] AAR copied", flush=True)

    # Pure-ASCII config files inside the mirror.
    with open(os.path.join(M_DEMO, "local.properties"), "w", encoding="ascii", newline="\n") as f:
        f.write("sdk.dir=%s\n" % M_SDK.replace("\\", "/"))
    with open(os.path.join(M_DEMO, "sdk.local.properties"), "w", encoding="ascii", newline="\n") as f:
        f.write("aarPath=../inspireface-1.2.4.post1.aar\n")

    gradle_bat = os.path.join(M_GRADLE, "bin", "gradle.bat")
    if not os.path.isfile(gradle_bat):
        print("ERROR: gradle.bat missing:", gradle_bat)
        return 2

    jdk = cfg.require_jdk()
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk
    env["ANDROID_HOME"] = M_SDK
    env["ANDROID_SDK_ROOT"] = M_SDK
    env["GRADLE_USER_HOME"] = cfg.GRADLE_USER_HOME
    if RO_DEP_CACHE:
        env["GRADLE_RO_DEP_CACHE"] = RO_DEP_CACHE
    env["TMP"] = cfg.TMP
    env["TEMP"] = cfg.TMP
    os.makedirs(env["GRADLE_USER_HOME"], exist_ok=True)
    os.makedirs(env["TMP"], exist_ok=True)
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")

    cmdline = ('"%s" --offline --no-daemon -p "%s" -PinspirefaceSdkSource=local '
               ':app:assembleDebug :app:testDebugUnitTest') % (gradle_bat, M_DEMO)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    log_path = os.path.join(LOGS, "build-ascii-%s.log" % stamp)
    print("CMD:", cmdline, flush=True)
    print("LOG:", log_path, flush=True)

    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        log.write("CMD: %s\n\n" % cmdline)
        proc = subprocess.Popen(cmdline, cwd=M_DEMO, env=env, shell=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            print(line, flush=True)
            log.write(line + "\n")
        code = proc.wait()

    print("\n=== exit code %d ===" % code, flush=True)

    # Copy the clean APK back into the project's expected output location.
    src_apk = os.path.join(M_DEMO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    dst_apk = os.path.join(DEMO_SRC, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if os.path.isfile(src_apk):
        os.makedirs(os.path.dirname(dst_apk), exist_ok=True)
        shutil.copy2(src_apk, dst_apk)
        print("=== APK copied back: %s (%d bytes) ===" % (dst_apk, os.path.getsize(dst_apk)), flush=True)

    # Summarize unit test results.
    results = os.path.join(M_DEMO, "app", "build", "test-results", "testDebugUnitTest")
    if os.path.isdir(results):
        import glob
        import xml.etree.ElementTree as ET
        total = fail = skip = 0
        for p in glob.glob(os.path.join(results, "TEST-*.xml")):
            r = ET.parse(p).getroot()
            total += int(r.get("tests", 0))
            fail += int(r.get("failures", 0)) + int(r.get("errors", 0))
            skip += int(r.get("skipped", 0))
        print("=== unit tests: %d total, %d failed, %d skipped ===" % (total, fail, skip), flush=True)

    print("=== mirror kept at %s (reusable, safe to delete) ===" % MIRROR, flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
