# -*- coding: utf-8 -*-
"""Install the built APK on any connected Android device and cold-start it.

Usage:
    python runtime/verify_on_device.py            # auto-pick first online device
    python runtime/verify_on_device.py <serial>   # target a specific device

Prerequisites:
  * Build the APK first:  python runtime/build_demo.py
  * Enable USB debugging on the phone and accept the host's RSA prompt,
    then check with:  adb devices
  * Or start the bundled phone AVD instead of a physical phone:
      python runtime/verify_on_emulator.py RealCue_API35

The SDK, adb, AVD and all temp files live under this project's runtime/
directory on the D: drive; this script writes no new data to the C: drive.
"""
import os
import sys
import time
import subprocess

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.abspath(__file__))          # .../runtime
PROJECT = os.path.dirname(BASE)                            # .../InspireFace-LocalRun
SDK = os.path.join(BASE, "android")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
APK = os.path.join(PROJECT, "android-demo", "app", "build", "outputs",
                   "apk", "debug", "app-debug.apk")
PKG = "com.example.inspireface_example"
ACTIVITY = ".HomeActivity"

# Keep adb / tool side effects off the C: drive.
ENV = dict(os.environ)
ENV["ANDROID_HOME"] = SDK
ENV["ANDROID_SDK_ROOT"] = SDK
ENV["ANDROID_EMULATOR_HOME"] = os.path.join(BASE, "avd")
ENV["ANDROID_AVD_HOME"] = os.path.join(BASE, "avd")
ENV["TMP"] = os.path.join(BASE, "tmp")
ENV["TEMP"] = os.path.join(BASE, "tmp")
ENV["LOCALAPPDATA"] = os.path.join(BASE, "tmp", "local-appdata")


def run(cmd, timeout=180):
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout, env=ENV)
        return r.returncode, (r.stdout + r.stderr).decode("utf-8", errors="replace").strip()
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT"
    except Exception as e:
        return -1, str(e)


def main():
    for d in (ENV["TMP"], ENV["LOCALAPPDATA"]):
        os.makedirs(d, exist_ok=True)

    if not os.path.isfile(ADB):
        print("ERROR: adb missing:", ADB)
        print("Run python runtime/setup_env.py first.")
        return 2
    if not os.path.isfile(APK):
        print("ERROR: APK missing:", APK)
        print("Build it first:  python runtime/build_demo.py")
        return 2

    code, out = run([ADB, "devices", "-l"], timeout=20)
    devices = [l.split("\t")[0] for l in out.splitlines()
               if "\tdevice" in l and not l.startswith("List")]
    print("=== adb devices ===")
    print(out)
    serial = sys.argv[1] if len(sys.argv) > 1 else (devices[0] if devices else None)
    if not serial:
        print("FAIL: no online adb device.")
        print("  - Connect a phone with USB debugging enabled and accept the RSA prompt, or")
        print("  - start the bundled phone AVD: python runtime/verify_on_emulator.py RealCue_API35")
        print("  - or copy the APK straight to the phone and install it manually:")
        print("      " + APK)
        return 3
    print("=== target: %s ===" % serial)

    code, out = run([ADB, "-s", serial, "install", "-r", APK], timeout=420)
    print("=== install (rc=%d) ===\n%s" % (code, out[-1200:]))
    if code != 0 or "Success" not in out:
        return 4

    code, out = run([ADB, "-s", serial, "shell", "am", "start", "-n",
                     PKG + "/" + ACTIVITY], timeout=60)
    print("=== am start (rc=%d) ===\n%s" % (code, out[-800:]))
    time.sleep(8)

    code, out = run([ADB, "-s", serial, "shell", "pidof", PKG], timeout=30)
    alive = code == 0 and out.strip().isdigit()
    print("=== pidof %s: rc=%d %s ===" % (PKG, code, out))

    code, lc = run([ADB, "-s", serial, "logcat", "-d", "-t", "800"], timeout=90)
    keys = ("AndroidRuntime", "FATAL EXCEPTION", "InspireFace", "FaceEngine",
            "GlobalLaunch", "CameraX", "C API", "native version")
    hits = [l for l in lc.splitlines() if any(k in l for k in keys)]
    print("=== relevant logcat (%d lines, last 30) ===" % len(hits))
    for l in hits[-30:]:
        print("  " + l[:220])

    print("=== RESULT ===")
    print("  install    : OK")
    print("  cold start : %s" % ("OK (process alive)" if alive else "process NOT alive"))
    print("  next       : open Face tracking / Face recognition video page and check")
    print("               real-time boxes, then run the re-entry test in the research note.")
    return 0 if alive else 5


if __name__ == "__main__":
    sys.exit(main())
