# -*- coding: utf-8 -*-
"""Prepare the self-contained Android build environment for the InspireFace demo.

Idempotent: re-running only fills in what is missing. All downloads go to
runtime/downloads and all installed components stay under runtime/.
"""
import os
import sys
import shutil
import zipfile
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.abspath(__file__))          # .../runtime
sys.path.insert(0, BASE)
import env_config as cfg  # noqa: E402  (machine-specific paths live here)

PROJECT = cfg.PROJECT
DEMO = cfg.DEMO
DL = cfg.DL
SDK = cfg.SDK
GRADLE_HOME = cfg.GRADLE_HOME
# Machine-wide Android SDK, used ONLY to copy already-accepted licenses.
# None is fine: setup_env.py can still assemble the self-contained SDK.
GLOBAL_SDK = cfg.GLOBAL_SDK

GOOGLE = "https://dl.google.com/android/repository/"
GRADLE_URL = "https://services.gradle.org/distributions/gradle-8.7-bin.zip"

PKGS = {
    "build-tools_r35_windows.zip": GOOGLE + "build-tools_r35_windows.zip",
    "platform-tools_r37.0.3-win.zip": GOOGLE + "platform-tools_r37.0.3-win.zip",
    "gradle-8.7-bin.zip": GRADLE_URL,
}


def log(msg):
    print(msg, flush=True)


def download(name, url):
    dst = os.path.join(DL, name)
    if os.path.isfile(dst) and os.path.getsize(dst) > 1024:
        log("[skip] already downloaded %s (%d bytes)" % (name, os.path.getsize(dst)))
        return dst
    tmp = dst + ".part"
    log("[download] %s <- %s" % (name, url))
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        while True:
            chunk = r.read(1024 * 1024)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total and done // (20 * 1024 * 1024) != (done - len(chunk)) // (20 * 1024 * 1024):
                log("  %d / %d MB" % (done // 1048576, total // 1048576))
    os.replace(tmp, dst)
    log("[ok] %s (%d bytes)" % (name, os.path.getsize(dst)))
    return dst


def zip_file_count(zpath):
    with zipfile.ZipFile(zpath) as z:
        return sum(1 for n in z.namelist() if not n.endswith("/"))


def disk_file_count(path):
    return sum(len(f) for _, _, f in os.walk(path))


def ensure_extracted(zpath, dst_root, expected_subdir=None, target_name=None):
    """Extract a zip into dst_root; optionally rename its single top folder."""
    with zipfile.ZipFile(zpath) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        top = os.path.commonprefix([n.replace("\\", "/") for n in names]).split("/")[0]
    log("[extract] %s top=%s -> %s" % (os.path.basename(zpath), top, dst_root))
    os.makedirs(dst_root, exist_ok=True)
    with zipfile.ZipFile(zpath) as z:
        z.extractall(dst_root)
    if target_name and top != target_name:
        src = os.path.join(dst_root, top)
        dst = os.path.join(dst_root, target_name)
        if os.path.isdir(src):
            if os.path.isdir(dst):
                shutil.rmtree(dst)
            os.rename(src, dst)
            log("[rename] %s -> %s" % (top, target_name))
    return dst_root


def main():
    os.makedirs(DL, exist_ok=True)
    os.makedirs(SDK, exist_ok=True)
    os.makedirs(GRADLE_HOME, exist_ok=True)

    # 1. Android platform 35 (large archive supplied by the developer).
    plat_zip = os.path.join(DL, "platform.api35.zip")
    if not os.path.isfile(plat_zip):
        raise FileNotFoundError(
            "Missing %s\n"
            "Download the Android 15 (API 35) platform archive as "
            "'platform.api35.zip' and place it in runtime/downloads/. "
            "See ENVIRONMENT_SETUP.md for the URL." % plat_zip)
    plat_dir = os.path.join(SDK, "platforms", "android-35")
    need = zip_file_count(plat_zip)
    have = disk_file_count(plat_dir) if os.path.isdir(plat_dir) else 0
    if have >= need and os.path.isfile(os.path.join(plat_dir, "android.jar")):
        log("[skip] platform android-35 complete (%d files)" % have)
    else:
        log("[fix] platform android-35 incomplete: %d/%d files, re-extracting" % (have, need))
        ensure_extracted(plat_zip, os.path.join(SDK, "platforms"))
    log("[ok] android.jar = %d bytes" % os.path.getsize(os.path.join(plat_dir, "android.jar")))

    # 2. Downloads.
    for name, url in PKGS.items():
        download(name, url)

    # 3. build-tools 35.0.0 (AGP 8.6.1 default).
    bt_dir = os.path.join(SDK, "build-tools", "35.0.0")
    if os.path.isfile(os.path.join(bt_dir, "aapt2.exe")):
        log("[skip] build-tools 35.0.0 present")
    else:
        ensure_extracted(os.path.join(DL, "build-tools_r35_windows.zip"),
                         os.path.join(SDK, "build-tools"), target_name="35.0.0")
    log("[ok] aapt2.exe = %s" % os.path.isfile(os.path.join(bt_dir, "aapt2.exe")))

    # 4. platform-tools.
    pt_dir = os.path.join(SDK, "platform-tools")
    if os.path.isfile(os.path.join(pt_dir, "adb.exe")):
        log("[skip] platform-tools present")
    else:
        ensure_extracted(os.path.join(DL, "platform-tools_r37.0.3-win.zip"), SDK)
    log("[ok] adb.exe = %s" % os.path.isfile(os.path.join(pt_dir, "adb.exe")))

    # 5. Gradle 8.7 standalone distribution.
    gd = os.path.join(GRADLE_HOME, "gradle-8.7")
    if os.path.isfile(os.path.join(gd, "bin", "gradle")):
        log("[skip] gradle-8.7 present")
    else:
        ensure_extracted(os.path.join(DL, "gradle-8.7-bin.zip"), GRADLE_HOME)
    log("[ok] gradle launcher = %s" % os.path.isfile(os.path.join(gd, "bin", "gradle")))

    # 6. SDK licenses (accepting terms is outside this script; copy existing ones).
    lic_src = os.path.join(GLOBAL_SDK, "licenses") if GLOBAL_SDK else None
    lic_dst = os.path.join(SDK, "licenses")
    if lic_src and os.path.isdir(lic_src) and not os.path.isdir(lic_dst):
        shutil.copytree(lic_src, lic_dst)
        log("[ok] licenses copied from %s" % lic_src)
    elif os.path.isdir(lic_dst):
        log("[skip] licenses already present")
    else:
        log("[skip] no machine-wide SDK licenses found; run "
            "'sdkmanager --licenses' with your own SDK if AGP asks for them")

    # 7. local.properties -- pure ASCII with \\uXXXX escapes so Java Properties
    #    loads the Chinese path correctly regardless of ISO-8859-1 / UTF-8.
    sdk_path = SDK.replace("\\", "/")
    escaped = "".join(c if ord(c) < 128 else "\\u%04x" % ord(c) for c in sdk_path)
    lp = os.path.join(DEMO, "local.properties")
    with open(lp, "w", encoding="ascii", newline="\n") as f:
        f.write("# Generated by runtime/setup_env.py. Points at the self-contained SDK.\n")
        f.write("sdk.dir=" + escaped + "\n")
    log("[ok] wrote %s" % lp)

    # 8. sdk.local.properties -- relative ASCII path, enables offline local AAR.
    sp = os.path.join(DEMO, "sdk.local.properties")
    with open(sp, "w", encoding="ascii", newline="\n") as f:
        f.write("# Generated by runtime/setup_env.py. Gitignored.\n")
        f.write("aarPath=../runtime/downloads/inspireface-1.2.4.post1.aar\n")
    log("[ok] wrote %s" % sp)
    if not os.path.isfile(cfg.INSPIREFACE_AAR):
        log("[warn] InspireFace AAR not found at %s" % cfg.INSPIREFACE_AAR)
        log("        Offline local builds will fail until you add it, or use "
            "-PinspirefaceSdkSource=jitpack for an online build.")

    log("=== SUMMARY ===")
    for label, path in (("platform", plat_dir), ("build-tools", bt_dir),
                        ("platform-tools", pt_dir), ("gradle", gd)):
        log("  %-16s %s  (%d files)" % (label, path, disk_file_count(path)))


if __name__ == "__main__":
    main()
