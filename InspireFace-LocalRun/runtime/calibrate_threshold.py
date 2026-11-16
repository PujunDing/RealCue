# -*- coding: utf-8 -*-
"""B0 threshold calibration: push two real people's photos, run the device-side
calibration test, pull the score distributions back and derive T_match / T_new.

Usage:
    python runtime/calibrate_threshold.py [sample_dir]

sample_dir must contain:
    A/   >= 2 photos of person A (jpg/png/webp)
    B/   >= 2 photos of person B (jpg/png/webp)

Shoot them on the phone you will actually deploy to: different angles,
distances, lighting and expressions, one face per photo, front-facing.

If sample_dir is omitted or incomplete, NO samples are pushed and the device
test deliberately runs a clearly flagged synthetic dry run. That still asserts
the whole pipeline, but the printed thresholds must NOT be used.

All writable state stays on D:. C: is read-only (JDK, emulator, Gradle cache).
"""
import glob
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_model_on_emulator as v  # noqa: E402  (shared env/adb constants)

sys.stdout.reconfigure(encoding="utf-8")

BASE = v.BASE
LOGS = v.LOGS
DEMO = v.DEMO
ADB = v.ADB
EMU = v.EMU
SDK = v.SDK
GRADLE_BAT = v.GRADLE_BAT
AVD_ID = v.AVD_ID

PKG = "com.example.inspireface_example"
# /sdcard is served through FUSE, where Android/data is hidden from adb
# on API 30+. The /storage/emulated/0 path is the SAME directory but
# visible to adbd, so both push and pull must use it.
DEVICE_DIR = "/storage/emulated/0/Android/data/%s/files/calibration" % PKG
TEST_CLASS = "com.example.inspireface_example.view.GalleryThresholdCalibrationTest"
RESULT_JSON = "threshold-result.json"
RESULT_CSV = "threshold-pairs.csv"
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp")

DEFAULT_SAMPLES = os.path.join(BASE, "calibration")
SAMPLES = (os.path.abspath(sys.argv[1]) if len(sys.argv) > 1
           else DEFAULT_SAMPLES)

log = v.log
run = v.run


def images(folder):
    if not os.path.isdir(folder):
        return []
    return sorted(
        os.path.join(folder, name) for name in os.listdir(folder)
        if name.lower().endswith(IMAGE_EXT)
        and os.path.isfile(os.path.join(folder, name)))


def collect_local_samples():
    a = images(os.path.join(SAMPLES, "A"))
    b = images(os.path.join(SAMPLES, "B"))
    return a, b


def print_collection_guide():
    log("")
    log("=== HOW TO COLLECT REAL SAMPLES ===")
    log("  1. mkdir -p %s" % os.path.join(SAMPLES, "A"))
    log("     mkdir -p %s" % os.path.join(SAMPLES, "B"))
    log("  2. Put >= 2 photos of ONE person in A/, >= 2 of a DIFFERENT person in B/.")
    log("     Use the phone you will deploy to. Vary angle / distance / light / expression.")
    log("     One frontal face per photo; avoid heavy occlusion and other people in frame.")
    log("  3. python runtime/calibrate_threshold.py %s" % SAMPLES)
    log("")


def push_samples(serial, env, files_a, files_b):
    dir_a = DEVICE_DIR + "/A"
    dir_b = DEVICE_DIR + "/B"
    run([ADB, "-s", serial, "shell", "rm", "-rf", dir_a, dir_b], 40, env)
    code, out = run([ADB, "-s", serial, "shell", "mkdir", "-p", dir_a, dir_b],
                    40, env)
    if code != 0:
        log("WARN mkdir: %s" % out.strip()[:200])
    failures = 0
    for local, remote_dir in ([(f, dir_a) for f in files_a]
                              + [(f, dir_b) for f in files_b]):
        code, out = run([ADB, "-s", serial, "push", local, remote_dir + "/"],
                        120, env)
        if code != 0:
            failures += 1
            log("WARN push failed for %s: %s" % (local, out.strip()[:160]))
    log("=== pushed A=%d B=%d (failures=%d) ===" % (
        len(files_a), len(files_b), failures))
    code, listing = run([ADB, "-s", serial, "shell", "ls",
                         dir_a, dir_b], 40, env)
    log("device listing: " + " ".join(listing.split())[:500])
    return failures == 0


def wait_for_device(env):
    serial = None
    for _ in range(36):
        code, out = run([ADB, "devices"], 15, env)
        for line in out.splitlines():
            if line.startswith("emulator-") and "\tdevice" in line:
                serial = line.split("\t")[0]
                break
        if serial:
            break
        time.sleep(5)
    if not serial:
        return None
    for _ in range(120):
        code, out = run([ADB, "-s", serial, "shell", "getprop",
                         "sys.boot_completed"], 20, env)
        if out.strip().endswith("1"):
            return serial
        time.sleep(5)
    return None


def install_apks(env, stamp):
    """Install target + androidTest APK first.

    Android 11+ forbids shell from creating /storage/emulated/0/Android,
    so the app's external files dir only exists once its APK is
    installed. Pushing samples before that silently fails.
    """
    log_path = os.path.join(LOGS, "calibration-install-%s.log" % stamp)
    cmdline = ('"%s" --no-daemon -p "%s" -PinspirefaceSdkSource=local '
               ':app:installDebug :app:installDebugAndroidTest') % (
                   GRADLE_BAT, DEMO)
    log("=== installing target + androidTest APK before pushing samples ===")
    proc = subprocess.run(cmdline, cwd=DEMO, env=env, shell=True,
                          stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT)
    out = proc.stdout.decode("utf-8", errors="replace")
    with open(log_path, "w", encoding="utf-8", errors="replace") as handle:
        handle.write(out)
    for line in out.splitlines():
        if "BUILD " in line or "FAILED" in line:
            log("  " + line)
    log("install exit code %d (%s)" % (proc.returncode, log_path))
    return proc.returncode


def run_calibration_test(env, stamp, attempt):
    gradle_log = os.path.join(
        LOGS, "calibration-gradle-%s-run%d.log" % (stamp, attempt))
    cmdline = ('"%s" --no-daemon -p "%s" -PinspirefaceSdkSource=local '
               '-Pandroid.testInstrumentationRunnerArguments.class=%s '
               ':app:connectedDebugAndroidTest') % (GRADLE_BAT, DEMO, TEST_CLASS)
    log("=== calibration run %d ===" % attempt)
    log("LOG: " + gradle_log)
    with open(gradle_log, "w", encoding="utf-8", errors="replace") as handle:
        handle.write("CMD: %s\n\n" % cmdline)
        proc = subprocess.Popen(cmdline, cwd=DEMO, env=env, shell=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            log(line)
            handle.write(line + "\n")
        rc = proc.wait()
    log("=== gradle exit code %d ===" % rc)

    xmls = glob.glob(os.path.join(DEMO, "app", "build", "outputs",
                                  "androidTest-results", "**", "TEST-*.xml"),
                     recursive=True)
    total = failures = errors = skipped = 0
    for path in sorted(xmls):
        root = ET.parse(path).getroot()
        total += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        for case in root.findall("testcase"):
            status = "PASS"
            if case.find("failure") is not None:
                status = "FAIL"
            elif case.find("error") is not None:
                status = "ERROR"
            elif case.find("skipped") is not None:
                status = "SKIP"
            log("  [%s] %s" % (status, case.get("name", "")))
            for node in list(case.findall("failure")) + list(case.findall("error")):
                log("      " + (node.get("message", "") or "")[:400])
    log("  TOTAL: tests=%d failures=%d errors=%d skipped=%d"
        % (total, failures, errors, skipped))
    return rc, total, failures, errors


def pull_results(serial, env, outdir, logcat_text):
    os.makedirs(outdir, exist_ok=True)
    for name in (RESULT_JSON, RESULT_CSV):
        dest = os.path.join(outdir, name)
        if os.path.isfile(dest):
            os.remove(dest)  # never reuse a previous attempt's artifact
        remote = DEVICE_DIR + "/" + name
        dest = os.path.join(outdir, name)
        code, out = run([ADB, "-s", serial, "pull", remote, dest], 60, env)
        if code == 0 and os.path.isfile(dest) and os.path.getsize(dest) > 0:
            log("pulled %s (%d bytes)" % (name, os.path.getsize(dest)))
            continue
        log("WARN adb pull %s failed (%s); trying exec-out cat"
            % (name, out.strip()[:120]))
        code, payload = run([ADB, "-s", serial, "exec-out", "cat", remote],
                           60, env)
        looks_valid = (name.endswith(".json")
                       and payload.lstrip().startswith("{")) or (
                       name.endswith(".csv")
                       and "group,pair,score" in payload)
        if code == 0 and looks_valid:
            with open(dest, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(payload)
            log("pulled %s via shell cat (%d bytes)" % (name, len(payload)))
        else:
            log("WARN could not retrieve %s" % name)
    if not os.path.isfile(os.path.join(outdir, RESULT_JSON)):
        recover_from_logcat(outdir, logcat_text)
    path = os.path.join(outdir, RESULT_JSON)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except Exception as exc:
        log("WARN parse result json: %s" % exc)
        return None


def recover_from_logcat(outdir, logcat_text):
    """Rebuild JSON/CSV from the tagged mirror lines in logcat."""
    if not logcat_text:
        return False
    json_lines, csv_lines = [], []
    in_json = in_csv = False
    for line in logcat_text.splitlines():
        if "RESULT_JSON_BEGIN" in line:
            in_json = True
            continue
        if "RESULT_JSON_END" in line:
            in_json = False
            continue
        if "RESULT_CSV_BEGIN" in line:
            in_csv = True
            continue
        if "RESULT_CSV_END" in line:
            in_csv = False
            continue
        if in_json and "JSON|" in line:
            json_lines.append(line.split("JSON|", 1)[1])
        elif in_csv and "CSV|" in line:
            csv_lines.append(line.split("CSV|", 1)[1])
    json_text = "\n".join(json_lines).strip()
    csv_text = "\n".join(csv_lines).strip()
    if not (json_text.startswith("{") and json_text.endswith("}")):
        log("WARN logcat mirror did not contain a complete JSON")
        return False
    with open(os.path.join(outdir, RESULT_JSON), "w",
              encoding="utf-8", newline="\n") as handle:
        handle.write(json_text + "\n")
    if csv_text.startswith("group,pair,score"):
        with open(os.path.join(outdir, RESULT_CSV), "w",
                  encoding="utf-8", newline="\n") as handle:
            handle.write(csv_text + "\n")
        log("recovered CSV from logcat (%d lines)" % len(csv_lines))
    else:
        log("WARN CSV mirror incomplete; JSON is still usable")
    log("recovered JSON from logcat (%d lines)" % len(json_lines))
    return True


def capture_logcat(serial, env, stamp):
    code, lc = run([ADB, "-s", serial, "logcat", "-d", "-t", "3000"], 90, env)
    path = os.path.join(LOGS, "calibration-logcat-%s.txt" % stamp)
    with open(path, "w", encoding="utf-8", errors="replace") as handle:
        handle.write(lc)
    hits = [line for line in lc.splitlines() if "RealCue" in line]
    log("=== calibration logcat lines (%d) -> %s ===" % (len(hits), path))
    for line in hits[-40:]:
        log("  " + line[:220])
    return path, lc


def write_summary(outdir, result, stamp):
    result = result or {}
    stats = result.get("stats") or {}
    lines = [
        "# B0 阈值标定结果",
        "",
        "运行时间 %s · 数据目录 `%s`" % (stamp, outdir),
        "",
        "| 项目 | 值 |",
        "| --- | --- |",
        "| 模式 | `%s` |" % (result.get("mode", "N/A") if result else "N/A"),
        "| 模型 | `%s` |" % (result.get("model", "N/A") if result else "N/A"),
        "| 官方阈值 | %.4f |" % float(result.get("officialThreshold", 0) or 0),
        "| 特征维度 | %s |" % (result.get("featureLength", "N/A") if result else "N/A"),
        "| 同人样本对 / 异人样本对 | %s / %s |" % (
            stats.get("sameCount", "N/A"), stats.get("diffCount", "N/A")),
        "| 同人最低分 sameMin | %.4f |" % float(stats.get("sameMin", 0) or 0),
        "| 异人最高分 diffMax | %.4f |" % float(stats.get("diffMax", 0) or 0),
        "| 分布可分 separable | %s |" % stats.get("separable", "N/A"),
        "| 间隔 margin | %.4f |" % float(stats.get("margin", 0) or 0),
        "| **建议 T_match** | **%.4f** |" % float(stats.get("matchThreshold", 0) or 0),
        "| **建议 T_new** | **%.4f** |" % float(stats.get("newThreshold", 0) or 0),
        "| T_match 下同人召回 | %.4f |" % float(stats.get("recallAtMatch", 0) or 0),
        "| T_match 下异人误接受 | %.4f |" % float(stats.get("falseAcceptAtMatch", 0) or 0),
        "| 官方阈值误接受风险 | %s |" % (
            result.get("falseAcceptAtOfficial", "N/A") if result else "N/A"),
        "| 官方阈值漏识别风险 | %s |" % (
            result.get("falseRejectAtOfficial", "N/A") if result else "N/A"),
        "",
        "## 警告",
        "",
        (result.get("warning", "") if result else "未拉到结果文件"),
        "",
        "## 文件",
        "",
        "- `threshold-result.json` 完整统计",
        "- `threshold-pairs.csv` 每一对样本的分数明细",
        "- `../calibration-logcat-%s.txt` 设备端日志" % stamp,
        "",
    ]
    if result and result.get("doNotUseForThreshold"):
        lines.insert(2, "> **此结果不可用于设定阈值**：请阅读下方警告。"
                        " 常见原因是合成演练，或 A/ 与 B/ 误放了同一个人。")
        lines += [
            "## 采集说明",
            "",
            "1. 在 `%s` 下建 `A/` 和 `B/` 两个目录。" % DEFAULT_SAMPLES,
            "2. `A/` 放同一个人的至少 2 张照片，`B/` 放另一个人的至少 2 张照片。",
            "   用最终要部署的那台手机拍，覆盖不同角度、距离、光照、表情，每张只含一张正脸。",
            "3. 重跑：`python runtime/calibrate_threshold.py %s`" % DEFAULT_SAMPLES,
            "",
        ]
    path = os.path.join(outdir, "calibration-summary.md")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    return path


def print_conclusion(result):
    if not result:
        log("=== RESULT: NO RESULT FILE PULLED ===")
        return
    stats = result.get("stats", {})
    log("=== CALIBRATION RESULT ===")
    log("  mode=%s official=%.4f" % (
        result.get("mode"), float(result.get("officialThreshold", 0) or 0)))
    log("  sameMin=%.4f diffMax=%.4f separable=%s margin=%.4f" % (
        float(stats.get("sameMin", 0) or 0),
        float(stats.get("diffMax", 0) or 0),
        stats.get("separable"), float(stats.get("margin", 0) or 0)))
    log("  T_match=%.4f T_new=%.4f recall=%.4f FAR=%.4f" % (
        float(stats.get("matchThreshold", 0) or 0),
        float(stats.get("newThreshold", 0) or 0),
        float(stats.get("recallAtMatch", 0) or 0),
        float(stats.get("falseAcceptAtMatch", 0) or 0)))
    log("  warning: %s" % result.get("warning", ""))
    if result.get("doNotUseForThreshold"):
        log("=== NOT USABLE FOR THRESHOLD - see warning above ===")
    else:
        log("=== B0 THRESHOLDS AVAILABLE ===")


def main():
    os.makedirs(LOGS, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    outdir = os.path.join(LOGS, "calibration-" + stamp)
    env = v.make_env()

    files_a, files_b = collect_local_samples()
    has_samples = len(files_a) >= 2 and len(files_b) >= 2
    if has_samples:
        log("=== local samples A=%d B=%d from %s ===" % (
            len(files_a), len(files_b), SAMPLES))
    else:
        log("=== NO REAL SAMPLES (A=%d B=%d) ===" % (len(files_a), len(files_b)))
        print_collection_guide()

    emu_log = os.path.join(LOGS, "calibration-emulator-%s.log" % stamp)
    emu_cmd = [EMU, "-avd", AVD_ID, "-no-window", "-no-audio", "-no-metrics",
               "-no-boot-anim", "-gpu", "swiftshader_indirect",
               "-no-snapshot-load", "-no-snapshot-save", "-no-snapstorage"]
    log("=== launching %s ===" % AVD_ID)
    with open(emu_log, "wb") as handle:
        subprocess.Popen(emu_cmd, cwd=SDK, env=env, stdout=handle,
                         stderr=subprocess.STDOUT,
                         creationflags=v.DETACHED, close_fds=True)
    serial = wait_for_device(env)
    if not serial:
        log("FAIL: emulator not ready")
        return 3
    log("=== device online and booted: %s ===" % serial)

    install_rc = install_apks(env, stamp)
    if install_rc != 0:
        log("WARN explicit install failed; connectedAndroidTest will install")

    if has_samples:
        push_samples(serial, env, files_a, files_b)

    rc, total, failures, errors = run_calibration_test(env, stamp, 1)
    logcat_text = capture_logcat(serial, env, stamp)[1]
    result = pull_results(serial, env, outdir, logcat_text)

    # Retry only when the pushed samples genuinely did not reach the test.
    # A missing result file is recovered from logcat, not mistaken for a
    # sample-delivery failure.
    if has_samples and (not result or result.get("mode") != "external_samples"):
        log("=== samples did not reach the test; re-pushing and rerunning once ===")
        push_samples(serial, env, files_a, files_b)
        rc, total, failures, errors = run_calibration_test(env, stamp, 2)
        logcat_text = capture_logcat(serial, env, stamp)[1]
        result = pull_results(serial, env, outdir, logcat_text)
    summary = write_summary(outdir, result, stamp)
    print_conclusion(result)
    log("=== artifacts: %s ===" % outdir)
    log("=== summary: %s ===" % summary)

    run([ADB, "-s", serial, "emu", "kill"], 30, env)
    log("=== emulator stopped ===")

    ok = (rc == 0 and total > 0 and failures == 0 and errors == 0
          and result is not None)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
