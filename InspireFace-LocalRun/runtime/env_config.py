# -*- coding: utf-8 -*-
"""Central, portable configuration for the RealCue build scripts.

A fresh clone does NOT contain the ~14 GB local toolchain (Android SDK,
Gradle distribution, phone system image, AVD, InspireFace AAR, APK).
Everything machine-specific is resolved here so that a new machine can be
configured with environment variables instead of editing these scripts.

Resolution order, highest priority first:
    1. REALCUE_* environment variable (explicit override)
    2. standard environment variable (JAVA_HOME, ANDROID_HOME, ...)
    3. common install locations auto-detected on this machine

Usage:
    python runtime/env_config.py            # print resolved values + readiness

See ENVIRONMENT_SETUP.md at the repository root for the full setup guide.
"""
import glob
import os
import shutil
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ---------------------------------------------------------------------------
# Repository layout (always derived from this file, never hard-coded)
# ---------------------------------------------------------------------------
BASE = os.path.dirname(os.path.abspath(__file__))        # .../runtime
PROJECT = os.path.dirname(BASE)                          # .../InspireFace-LocalRun
REPO_ROOT = os.path.dirname(PROJECT)                     # .../RealCue
DEMO = os.path.join(PROJECT, "android-demo")

# Self-contained SDK / Gradle / state, all kept inside the project directory.
SDK = os.environ.get("REALCUE_LOCAL_SDK") or os.path.join(BASE, "android")
DL = os.environ.get("REALCUE_DOWNLOADS") or os.path.join(BASE, "downloads")
GRADLE_HOME = os.path.join(BASE, "gradle")
GRADLE_DIST = os.path.join(GRADLE_HOME, "gradle-8.7")
GRADLE_BAT = os.path.join(GRADLE_DIST, "bin",
                          "gradle.bat" if os.name == "nt" else "gradle")
GRADLE_USER_HOME = (os.environ.get("REALCUE_GRADLE_USER_HOME")
                    or os.path.join(BASE, "gradle-home"))
AVD_HOME = os.environ.get("REALCUE_AVD_HOME") or os.path.join(BASE, "avd")
LOGS = os.path.join(BASE, "logs")
TMP = os.path.join(BASE, "tmp")
AVD_ID = "RealCue_API35"
INSPIREFACE_AAR = os.path.join(DL, "inspireface-1.2.4.post1.aar")

# Pure-ASCII mirror used only by build_ascii.py when the repo path has
# non-ASCII characters. Defaults to a sibling of the repository root.
ASCII_MIRROR = (os.environ.get("REALCUE_ASCII_MIRROR")
                or os.path.join(os.path.dirname(REPO_ROOT), "ifd-build"))

# Optional second directory to look in for large archives.
EXTRA_DOWNLOADS = os.environ.get("REALCUE_EXTRA_DOWNLOADS") or None


def _java_major(java_home):
    """Read JAVA_VERSION from <jdk>/release; return 17, 21, ... or None."""
    release = os.path.join(java_home, "release")
    try:
        with open(release, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith("JAVA_VERSION="):
                    raw = line.split("=", 1)[1].strip().strip('"')
                    parts = raw.split(".")
                    if parts and parts[0] == "1" and len(parts) > 1:
                        return int(parts[1])
                    return int(parts[0]) if parts and parts[0].isdigit() else None
    except (OSError, ValueError):
        pass
    return None


def _java_binary(java_home):
    for name in ("java.exe", "java"):
        path = os.path.join(java_home, "bin", name)
        if os.path.isfile(path):
            return path
    return None


def _find_jdk17():
    """AGP 8.6.1 requires JDK 17. Android Studio's bundled JBR is often 21."""
    explicit = os.environ.get("REALCUE_JAVA_HOME")
    if explicit and os.path.isdir(explicit):
        return explicit

    home = os.path.expanduser("~")
    candidates = []
    candidates += sorted(glob.glob(os.path.join(home, ".jdks", "*17*")),
                         reverse=True)
    local_app = os.environ.get("LOCALAPPDATA", "")
    if local_app:
        candidates += sorted(glob.glob(os.path.join(
            local_app, "Programs", "Eclipse Adoptium", "jdk-17*")), reverse=True)
    candidates += [
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                     "Java", "jdk-17"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                     "Android", "Android Studio", "jbr"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"),
                     "Eclipse Adoptium", "jdk-17"),
        "/usr/lib/jvm/java-17-openjdk-amd64",
        "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home",
    ]
    standard = os.environ.get("JAVA_HOME")
    if standard:
        candidates.append(standard)

    seen = set()
    for path in candidates:
        if not path or path in seen or not os.path.isdir(path):
            continue
        seen.add(path)
        if _java_major(path) == 17 and _java_binary(path):
            return path

    # Last resort: trust JAVA_HOME even if the version could not be read.
    if standard and os.path.isdir(standard):
        return standard
    return None


def _find_global_sdk():
    """Machine-wide Android SDK: supplies emulator.exe and accepted licenses.

    This is DIFFERENT from SDK above, which is the self-contained SDK assembled
    inside runtime/android by setup_env.py.
    """
    for key in ("REALCUE_GLOBAL_ANDROID_SDK", "ANDROID_HOME",
                "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        if value and os.path.isdir(value):
            return value
    local_app = os.environ.get("LOCALAPPDATA", "")
    if local_app:
        default = os.path.join(local_app, "Android", "Sdk")
        if os.path.isdir(default):
            return default
    return None


def _find_emulator(global_sdk):
    explicit = os.environ.get("REALCUE_EMULATOR")
    if explicit:
        return explicit
    roots = [p for p in (global_sdk, SDK) if p]
    for root in roots:
        for name in ("emulator.exe", "emulator"):
            path = os.path.join(root, "emulator", name)
            if os.path.isfile(path):
                return path
    return shutil.which("emulator") or "emulator"


def _find_ro_dep_cache():
    """Optional read-only Gradle cache on C:. Reuse, never write to it."""
    explicit = os.environ.get("REALCUE_GRADLE_RO_DEP_CACHE")
    if explicit:
        return explicit if os.path.isdir(explicit) else None
    default = os.path.join(os.path.expanduser("~"), ".gradle", "caches")
    return default if os.path.isdir(default) else None


JAVA_HOME = _find_jdk17()
GLOBAL_SDK = _find_global_sdk()
EMU = _find_emulator(GLOBAL_SDK)
RO_DEP_CACHE = _find_ro_dep_cache()


def describe():
    """Print every resolved value; return True when the core toolchain is ready."""
    rows = [
        ("REPO_ROOT", REPO_ROOT),
        ("DEMO", DEMO),
        ("local SDK", SDK),
        ("downloads", DL),
        ("Gradle launcher", GRADLE_BAT),
        ("GRADLE_USER_HOME", GRADLE_USER_HOME),
        ("AVD_HOME", AVD_HOME),
        ("AVD_ID", AVD_ID),
        ("JAVA_HOME", JAVA_HOME),
        ("global SDK", GLOBAL_SDK),
        ("emulator", EMU),
        ("read-only Gradle cache", RO_DEP_CACHE),
        ("ASCII mirror", ASCII_MIRROR),
        ("InspireFace AAR", INSPIREFACE_AAR),
    ]
    print("=== RealCue resolved environment ===")
    for label, value in rows:
        print("  %-24s %s" % (label, value if value else "(not found)"))

    problems = []
    if not JAVA_HOME:
        problems.append("JDK 17 not found. Set REALCUE_JAVA_HOME to a JDK 17 "
                        "directory (Android Studio's JBR 21 will NOT work).")
    elif _java_major(JAVA_HOME) not in (None, 17):
        problems.append("JAVA_HOME resolves to JDK %s, but AGP 8.6.1 needs "
                        "JDK 17. Set REALCUE_JAVA_HOME." % _java_major(JAVA_HOME))
    if not os.path.isfile(GRADLE_BAT):
        problems.append("Gradle 8.7 launcher missing. Run "
                        "python runtime/setup_env.py first.")
    if not os.path.isfile(os.path.join(SDK, "platforms", "android-35",
                                       "android.jar")):
        problems.append("android-35 platform missing. Run "
                        "python runtime/setup_env.py first.")
    if not os.path.isfile(INSPIREFACE_AAR):
        problems.append("InspireFace AAR missing at %s. See "
                        "ENVIRONMENT_SETUP.md." % INSPIREFACE_AAR)

    print("=== readiness ===")
    if problems:
        for item in problems:
            print("  [TODO] " + item)
        print("  RESULT: NOT READY")
        return False
    print("  RESULT: READY (core build toolchain present)")
    return True


def require_jdk():
    """Fail early with an actionable message when JDK 17 is unavailable."""
    if not JAVA_HOME or not _java_binary(JAVA_HOME):
        raise RuntimeError(
            "JDK 17 not found. Set REALCUE_JAVA_HOME to a JDK 17 directory.\n"
            "Android Studio's bundled JBR is usually JDK 21 and is NOT "
            "compatible with AGP 8.6.1. See ENVIRONMENT_SETUP.md.")
    return JAVA_HOME


if __name__ == "__main__":
    sys.exit(0 if describe() else 1)
