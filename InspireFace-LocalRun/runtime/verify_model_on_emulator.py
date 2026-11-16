# -*- coding: utf-8 -*-
"""Prove the InspireFace model algorithm actually works on the phone emulator.

Builds are not algorithm validation. This script boots RealCue_API35, then runs
the device-side Sdk124SmokeTest instrumented tests on it. Those tests load the
real libInspireFace.so, launch Pikachu and Megatron model packs, and run face
detection, 106-point landmarks, feature extraction/comparison and tracking
against the bundled kun.jpg sample face.

All writable state stays on D:. C: is read-only (JDK, emulator.exe, Gradle
read-only dependency cache).

Pass a fully qualified test class as argv[1] to run a different device-side
suite, e.g. com.example.inspireface_example.view.FaceRepositorySdkTest to
validate the persistent face gallery (insert -> search -> reopen -> same ID).
"""
import glob
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(BASE)
DEMO = os.path.join(PROJECT, "android-demo")
SDK = os.path.join(BASE, "android")
GLOBAL_SDK = r"C:\Users\pjd02\AppData\Local\Android\Sdk"
EMU = os.path.join(GLOBAL_SDK, "emulator", "emulator.exe")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
GRADLE_BAT = os.path.join(BASE, "gradle", "gradle-8.7", "bin", "gradle.bat")
JAVA_HOME = r"C:\Users\pjd02\.jdks\jbr-17.0.14"
RO_DEP_CACHE = r"C:\Users\pjd02\.gradle\caches"
GRADLE_USER_HOME = os.path.join(BASE, "gradle-home")
AVD_HOME = os.path.join(BASE, "avd")
AVD_ID = "RealCue_API35"
LOGS = os.path.join(BASE, "logs")
TMP = os.path.join(BASE, "tmp")
DEFAULT_TEST_CLASS = "com.example.inspireface_example.view.ModelFeasibilityTest"
TEST_CLASS = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TEST_CLASS

DETACHED = 0x00000008 | 0x00000200


def log(msg):
    print(msg, flush=True)


def run(cmd, timeout=180, env=None):
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout, env=env)
        out = (r.stdout + r.stderr).decode("utf-8", errors="replace")
        return r.returncode, out
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT"
    except Exception as e:
        return -1, str(e)


def make_env():
    env = os.environ.copy()
    env["ANDROID_HOME"] = SDK
    env["ANDROID_SDK_ROOT"] = SDK
    env["ANDROID_EMULATOR_HOME"] = AVD_HOME
    env["ANDROID_AVD_HOME"] = AVD_HOME
    env["JAVA_HOME"] = JAVA_HOME
    env["GRADLE_USER_HOME"] = GRADLE_USER_HOME
    env["GRADLE_RO_DEP_CACHE"] = RO_DEP_CACHE
    env["TMP"] = TMP
    env["TEMP"] = TMP
    env["LOCALAPPDATA"] = os.path.join(TMP, "local-appdata")
    for path in (GRADLE_USER_HOME, TMP, env["LOCALAPPDATA"]):
        os.makedirs(path, exist_ok=True)
    env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")
    return env


def main():
    os.makedirs(LOGS, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    emu_log = os.path.join(LOGS, "model-emulator-%s.log" % stamp)
    gradle_log = os.path.join(LOGS, "model-gradle-%s.log" % stamp)
    env = make_env()

    log("=== launching RealCue_API35 for instrumented model tests ===")
    emu_cmd = [EMU, "-avd", AVD_ID, "-no-window", "-no-audio", "-no-metrics",
               "-no-boot-anim", "-gpu", "swiftshader_indirect",
               "-no-snapshot-load", "-no-snapshot-save", "-no-snapstorage"]
    log("CMD: " + " ".join(emu_cmd))
    with open(emu_log, "wb") as lf:
        subprocess.Popen(emu_cmd, cwd=SDK, env=env, stdout=lf,
                         stderr=subprocess.STDOUT,
                         creationflags=DETACHED, close_fds=True)

    serial = None
    for _ in range(36):
        code, out = run([ADB, "devices"], timeout=15, env=env)
        for line in out.splitlines():
            if line.startswith("emulator-") and "\tdevice" in line:
                serial = line.split("\t")[0]
                break
        if serial:
            break
        time.sleep(5)
    if not serial:
        log("FAIL: no emulator device within 180s")
        return 3
    log("=== device online: %s ===" % serial)

    booted = False
    for _ in range(120):
        code, out = run([ADB, "-s", serial, "shell", "getprop",
                         "sys.boot_completed"], timeout=20, env=env)
        if out.strip().endswith("1"):
            booted = True
            break
        time.sleep(5)
    if not booted:
        log("FAIL: sys.boot_completed not reached within 600s")
        return 4
    log("=== boot completed ===")

    # Run ONLY the SDK smoke test: it is the one that exercises the real model.
    cmdline = ('"%s" --no-daemon -p "%s" -PinspirefaceSdkSource=local '
               '-Pandroid.testInstrumentationRunnerArguments.class=%s '
               ':app:connectedDebugAndroidTest') % (GRADLE_BAT, DEMO, TEST_CLASS)
    log("=== running device-side model tests ===")
    log("CMD: " + cmdline)
    log("LOG: " + gradle_log)
    with open(gradle_log, "w", encoding="utf-8", errors="replace") as gl:
        gl.write("CMD: %s\n\n" % cmdline)
        proc = subprocess.Popen(cmdline, cwd=DEMO, env=env, shell=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            log(line)
            gl.write(line + "\n")
        gradle_rc = proc.wait()
    log("=== gradle exit code %d ===" % gradle_rc)

    # Collect whatever AGP wrote, then report every testcase.
    xmls = glob.glob(os.path.join(DEMO, "app", "build", "outputs",
                                  "androidTest-results", "**", "TEST-*.xml"),
                     recursive=True)
    if not xmls:
        xmls = glob.glob(os.path.join(DEMO, "app", "build", "test-results",
                                      "**", "TEST-*.xml"), recursive=True)
    total = failures = errors = skipped = 0
    log("=== INSTRUMENTED RESULT ===")
    for path in sorted(xmls):
        root = ET.parse(path).getroot()
        t = int(root.get("tests", 0))
        f = int(root.get("failures", 0))
        e = int(root.get("errors", 0))
        s = int(root.get("skipped", 0))
        total += t
        failures += f
        errors += e
        skipped += s
        log("  suite %s: tests=%d failures=%d errors=%d skipped=%d"
            % (os.path.basename(path), t, f, e, s))
        for case in root.findall("testcase"):
            status = "PASS"
            if case.find("failure") is not None:
                status = "FAIL"
            elif case.find("error") is not None:
                status = "ERROR"
            elif case.find("skipped") is not None:
                status = "SKIP"
            log("    [%s] %s.%s" % (status, case.get("classname", ""),
                                    case.get("name", "")))
            for node in list(case.findall("failure")) + list(case.findall("error")):
                log("      " + (node.get("message", "") or "")[:400])
    log("  TOTAL: tests=%d failures=%d errors=%d skipped=%d"
        % (total, failures, errors, skipped))

    # Native/model evidence from logcat.
    code, lc = run([ADB, "-s", serial, "logcat", "-d", "-t", "2000"],
                   timeout=90, env=env)
    # Any project-added test tag (RealCueModel, RealCueGallery, ...) must be
    # captured, not just the original ModelFeasibilityTest tag.
    with open(os.path.join(LOGS, "model-logcat-%s.txt" % stamp), "w",
              encoding="utf-8", errors="replace") as lcf:
        lcf.write(lc)
    keys = ("RealCue", "InspireFace", "FaceEngine", "GlobalLaunch",
            "Pikachu", "Megatron", "HFCreate", "native", "TestRunner", "FATAL")
    hits = [l for l in lc.splitlines() if any(k in l for k in keys)]
    log("=== relevant logcat lines (%d) ===" % len(hits))
    for line in hits[-50:]:
        log("  " + line[:220])

    run([ADB, "-s", serial, "emu", "kill"], timeout=30, env=env)
    log("=== emulator stopped ===")

    ok = gradle_rc == 0 and total > 0 and failures == 0 and errors == 0
    log("=== RESULT: %s ===" % ("MODEL ALGORITHM VALIDATED" if ok else "NOT VALIDATED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
