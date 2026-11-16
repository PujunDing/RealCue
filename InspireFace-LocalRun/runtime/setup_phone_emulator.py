# -*- coding: utf-8 -*-
"""Install the Android 15 x86_64 phone image and create the RealCue AVD.

The system image archive is downloaded separately to runtime/downloads (or
D:/paper/ifd-build/downloads during migration). This script verifies its SHA-1,
extracts it into the project SDK, and writes a phone AVD without avdmanager.
"""
import hashlib
import os
from pathlib import Path
import shutil
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

BASE = Path(__file__).resolve().parent
SDK = BASE / "android"
DL = BASE / "downloads"
FALLBACK_DL = Path(r"D:\paper\ifd-build\downloads")
AVD_HOME = BASE / "avd"
AVD_ID = "RealCue_API35"
IMAGE_DIR = SDK / "system-images" / "android-35" / "google_apis" / "x86_64"
ZIP_NAME = "x86_64-35_r09.zip"
ZIP_SIZE = 1738815903
ZIP_SHA1 = "0103e6dab21290c4b9d16550a3ce99476f884eef"

CONFIG = """AvdId=RealCue_API35
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=RealCue API 35 Phone
avd.ini.encoding=UTF-8
disk.dataPartition.size=8G
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=Google
hw.device.name=pixel_6
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=4096
hw.screen=touch
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
image.sysdir.1=system-images\\android-35\\google_apis\\x86_64\\
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=yes
skin.dynamic=yes
tag.display=Google APIs
tag.id=google_apis
target=android-35
vm.heapSize=512
"""


def log(message):
    print(message, flush=True)


def find_archive():
    for path in (DL / ZIP_NAME, FALLBACK_DL / ZIP_NAME):
        if path.is_file():
            return path
    raise FileNotFoundError("Missing %s in %s or %s" % (ZIP_NAME, DL, FALLBACK_DL))


def sha1(path):
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    if not (IMAGE_DIR / "system.img").is_file():
        archive = find_archive()
        actual_size = archive.stat().st_size
        if actual_size != ZIP_SIZE:
            raise RuntimeError("Incomplete archive: %d/%d bytes" % (actual_size, ZIP_SIZE))
        log("[verify] SHA-1 of %s" % archive)
        actual_sha1 = sha1(archive)
        if actual_sha1 != ZIP_SHA1:
            raise RuntimeError("SHA-1 mismatch: %s" % actual_sha1)
        log("[ok] archive size and SHA-1 match Google repository metadata")
        IMAGE_DIR.parent.mkdir(parents=True, exist_ok=True)
        log("[extract] %s -> %s" % (archive, IMAGE_DIR.parent))
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(IMAGE_DIR.parent)
        if not (IMAGE_DIR / "system.img").is_file():
            raise RuntimeError("Extraction finished but system.img is missing: %s" % IMAGE_DIR)
    else:
        log("[skip] phone system image already installed")

    AVD_HOME.mkdir(parents=True, exist_ok=True)
    avd_dir = AVD_HOME / (AVD_ID + ".avd")
    avd_dir.mkdir(exist_ok=True)
    (avd_dir / "config.ini").write_text(CONFIG, encoding="utf-8", newline="\n")
    ini = "avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-35\n" % (
        str(avd_dir).replace("\\", "/"), AVD_ID)
    (AVD_HOME / (AVD_ID + ".ini")).write_text(ini, encoding="utf-8", newline="\n")
    log("[ok] AVD written: %s" % avd_dir)
    log("[ok] system image: %s" % IMAGE_DIR)
    log("Next: python runtime/verify_on_emulator.py")


if __name__ == "__main__":
    main()
