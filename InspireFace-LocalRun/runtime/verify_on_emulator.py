# -*- coding: utf-8 -*-
"""Boot the phone AVD, install the built APK and cold-start the demo.

Usage: python runtime/verify_on_emulator.py [AVD_ID]
Default AVD: RealCue_API35 (Android 15, x86_64, portrait phone, dual cameras).
This verifies install + process survival + SDK init, NOT real-face accuracy.
WHPX acceleration is available on this host.
"""
import os
import sys
import time
import subprocess

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
import env_config as cfg  # noqa: E402  (machine-specific paths live here)

PROJECT = cfg.PROJECT
APK = os.path.join(PROJECT, "android-demo", "app", "build", "outputs",
                   "apk", "debug", "app-debug.apk")
GLOBAL_SDK = cfg.GLOBAL_SDK
SDK = cfg.SDK
EMU = cfg.EMU
ADB = os.path.join(SDK, "platform-tools",
                   "adb.exe" if os.name == "nt" else "adb")
AVD_HOME = cfg.AVD_HOME
AVD_ID = sys.argv[1] if len(sys.argv) > 1 else "RealCue_API35"
LOGS = os.path.join(BASE, "logs")
PKG = "com.example.inspireface_example"
ACTIVITY = ".HomeActivity"

DETACHED = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
BREAKAWAY = 0x01000000               # CREATE_BREAKAWAY_FROM_JOB


def run(cmd, timeout=180):
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        out = (r.stdout + r.stderr).decode("utf-8", errors="replace").strip()
        return r.returncode, out
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT"
    except Exception as e:
        return -1, str(e)


def log(msg):
    print(msg, flush=True)


def capture_screenshot(serial, path):
    try:
        r = subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"],
                           capture_output=True, timeout=90)
        if r.returncode == 0 and r.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            with open(path, "wb") as f:
                f.write(r.stdout)
            return True, "%d bytes" % len(r.stdout)
        detail = (r.stdout + r.stderr).decode("utf-8", errors="replace")[-300:]
        return False, "rc=%d %s" % (r.returncode, detail)
    except Exception as e:
        return False, str(e)


def main():
    os.makedirs(LOGS, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    emu_log = os.path.join(LOGS, "emulator-%s.log" % stamp)

    if not os.path.isfile(APK):
        log("ERROR: APK missing: " + APK)
        log("Build it first: python runtime/run_gradle.py :app:assembleDebug")
        return 2
    if not os.path.isfile(EMU):
        log("ERROR: emulator binary not found: " + EMU)
        log("Set REALCUE_EMULATOR or install the emulator in your machine-wide "
            "Android SDK. See ENVIRONMENT_SETUP.md.")
        return 2

    env = os.environ.copy()
    env["ANDROID_HOME"] = SDK
    env["ANDROID_SDK_ROOT"] = SDK
    env["ANDROID_EMULATOR_HOME"] = AVD_HOME
    env["ANDROID_AVD_HOME"] = AVD_HOME
    os.makedirs(os.path.join(BASE, "tmp"), exist_ok=True)
    env["TMP"] = os.path.join(BASE, "tmp")
    env["TEMP"] = os.path.join(BASE, "tmp")
    env["LOCALAPPDATA"] = os.path.join(BASE, "tmp", "local-appdata")
    os.makedirs(env["LOCALAPPDATA"], exist_ok=True)

    emu_cmd = [EMU, "-avd", AVD_ID, "-no-window", "-no-audio", "-no-metrics",
               "-no-boot-anim", "-gpu", "swiftshader_indirect",
               "-no-snapshot-load", "-no-snapshot-save", "-no-snapstorage"]
    log("=== launching emulator (detached) ===")
    log("CMD: " + " ".join(emu_cmd))
    log("EMU LOG: " + emu_log)
    lf = open(emu_log, "wb")
    persistent = True
    try:
        subprocess.Popen(emu_cmd, cwd=SDK, env=env, stdout=lf,
                         stderr=subprocess.STDOUT,
                         creationflags=DETACHED | BREAKAWAY, close_fds=True)
    except OSError as e:
        persistent = False
        log("WARN: CREATE_BREAKAWAY_FROM_JOB unavailable (%s); using plain detached process" % e)
        subprocess.Popen(emu_cmd, cwd=SDK, env=env, stdout=lf,
                         stderr=subprocess.STDOUT,
                         creationflags=DETACHED, close_fds=True)

    # 1. wait for an emulator device to appear
    serial = None
    for i in range(36):  # 36 * 5s = 180s
        code, out = run([ADB, "devices"], timeout=15)
        for line in out.splitlines():
            if line.startswith("emulator-") and "\tdevice" in line:
                serial = line.split("\t")[0]
                break
        if serial:
            break
        time.sleep(5)
    if not serial:
        log("FAIL: no emulator device appeared within 180s")
        log("--- emulator log tail ---")
        try:
            log(open(emu_log, encoding="utf-8", errors="replace").read()[-4000:])
        except Exception as e:
            log(str(e))
        return 3
    log("=== device online: %s ===" % serial)

    # 2. wait for boot_completed
    booted = False
    for i in range(120):  # 120 * 5s = 600s
        code, out = run([ADB, "-s", serial, "shell", "getprop",
                         "sys.boot_completed"], timeout=20)
        if out.strip().endswith("1"):
            booted = True
            break
        time.sleep(5)
    if not booted:
        log("FAIL: sys.boot_completed not reached within 600s")
        log("--- emulator log tail ---")
        try:
            log(open(emu_log, encoding="utf-8", errors="replace").read()[-4000:])
        except Exception as e:
            log(str(e))
        return 4
    log("=== boot completed ===")

    # 3. install
    log("=== installing APK (%d bytes) ===" % os.path.getsize(APK))
    code, out = run([ADB, "-s", serial, "install", "-r", APK], timeout=420)
    log("install rc=%d\n%s" % (code, out[-1500:]))
    if code != 0 or "Success" not in out:
        return 5

    # 4. resolve launcher & cold start
    code, out = run([ADB, "-s", serial, "shell", "cmd", "package",
                     "resolve-activity", "--brief", PKG], timeout=60)
    log("=== launcher ===\n" + out[-600:])
    code, out = run([ADB, "-s", serial, "shell", "am", "start", "-n",
                     PKG + "/" + ACTIVITY], timeout=60)
    log("=== am start ===\n" + out[-800:])
    time.sleep(8)

    # 5. process alive + focused activity
    code, out = run([ADB, "-s", serial, "shell", "pidof", PKG], timeout=30)
    log("=== pidof %s: rc=%d %s ===" % (PKG, code, out))
    alive = code == 0 and out.strip().isdigit()
    code2, out2 = run([ADB, "-s", serial, "shell", "dumpsys", "activity",
                       "activities"], timeout=90)
    focus = [l.strip() for l in out2.splitlines()
             if "mResumedActivity" in l or "topResumedActivity" in l]
    log("=== focused activity ===\n" + "\n".join(focus[:4]))

    # 5b. phone identity + screenshot captured while the emulator is alive.
    props = {}
    for key in ("ro.product.model", "ro.build.version.release",
                "ro.build.version.sdk"):
        code, value = run([ADB, "-s", serial, "shell", "getprop", key], timeout=30)
        props[key] = value.strip()
    log("=== device info ===")
    for key, value in props.items():
        log("  %-24s %s" % (key, value))
    shot = os.path.join(LOGS, "RealCue-emulator-home-%s.png" % stamp)
    ok, detail = capture_screenshot(serial, shot)
    log("=== screenshot: %s (%s) ===" % (shot if ok else "FAILED", detail))

    # 6. crash / SDK-init log evidence
    code, lc = run([ADB, "-s", serial, "logcat", "-d", "-t", "600"], timeout=90)
    keys = ("AndroidRuntime", "FATAL EXCEPTION", "InspireFace", "FaceEngine",
            "GlobalLaunch", "CameraX", "native version", "C API")
    hits = [l for l in lc.splitlines() if any(k in l for k in keys)]
    log("=== relevant logcat lines (%d) ===" % len(hits))
    for l in hits[-40:]:
        log("  " + l[:220])

    log("=== RESULT ===")
    log("  device       : %s" % serial)
    log("  install      : OK")
    log("  cold start   : %s" % ("OK (process alive)" if alive else "process NOT alive"))
    log("  emulator log : %s" % emu_log)
    log("  screenshot   : %s" % (shot if ok else "FAILED"))
    log("  NOTE: virtual cameras do not show a real person;")
    log("        this validates install/startup/SDK loading only, not detection accuracy.")
    if persistent:
        log("  Emulator launched outside the automation job; stop with: adb -s %s emu kill" % serial)
    else:
        log("  Emulator may be stopped when this automation job ends.")
    return 0 if alive else 6


if __name__ == "__main__":
    sys.exit(main())
