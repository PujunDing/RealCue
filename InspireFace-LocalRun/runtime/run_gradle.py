# -*- coding: utf-8 -*-
"""Reusable Gradle gate for the RealCue demo, with all writable state on D:.

Usage:
    python runtime/run_gradle.py :app:testDebugUnitTest
    python runtime/run_gradle.py :app:assembleDebug :app:assembleDebugAndroidTest
    python runtime/run_gradle.py :app:testDebugUnitTest --tests <fqcn>

Prints a compact summary and writes the full log to runtime/logs/gradle-<task>-<stamp>.txt.
"""
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
import verify_model_on_emulator as v  # noqa: E402

args = sys.argv[1:]
if not args:
    print("no gradle task given", flush=True)
    sys.exit(2)

env = v.make_env()
os.makedirs(v.LOGS, exist_ok=True)
stamp = time.strftime("%Y%m%d-%H%M%S")
tag = args[0].replace(":", "_").strip("_")
log_path = os.path.join(v.LOGS, "gradle-%s-%s.txt" % (tag, stamp))

cmdline = ('"%s" --no-daemon --offline -p "%s" -PinspirefaceSdkSource=local %s'
           % (v.GRADLE_BAT, v.DEMO, " ".join('"%s"' % a if " " in a else a for a in args)))
print("CMD:", cmdline, flush=True)
print("LOG:", log_path, flush=True)

proc = subprocess.run(cmdline, cwd=v.DEMO, env=env, shell=True,
                      capture_output=True)
out = (proc.stdout + proc.stderr).decode("utf-8", errors="replace")
with open(log_path, "w", encoding="utf-8", errors="replace") as fh:
    fh.write("CMD: " + cmdline + "\n")
    fh.write("RC: %d\n\n" % proc.returncode)
    fh.write(out)

keys = ("BUILD SUCCESSFUL", "BUILD FAILED", "FAILED", "error:", "Error:",
        "tests completed", "test completed", "FAILURE:", "What went wrong",
        "warning:", "e: ", "Execution failed")
lines = out.splitlines()
print("RC:", proc.returncode, flush=True)
for line in lines:
    if any(k in line for k in keys):
        print("  ", line.strip()[:220], flush=True)
print("--- tail ---", flush=True)
for line in lines[-18:]:
    print("  ", line.rstrip()[:220], flush=True)
sys.exit(proc.returncode)
