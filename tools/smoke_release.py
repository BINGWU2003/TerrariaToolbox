"""Install the signed APK on a dedicated emulator and verify its actual home screen."""

import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "io.github.bingwu2003.terrariatoolbox"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/distribution"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, default=Path("app/build/outputs/release-smoke"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb] + (["-s", args.serial] if args.serial else [])

    def adb(*arguments):
        return subprocess.check_output(prefix + list(arguments), stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    # Never run this install/UI test against a player's physical phone.
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise SystemExit("Release smoke requires a dedicated Android emulator.")
    apks = list(args.apk_dir.glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit("Expected one signed release APK.")
    print(adb("install", "-r", str(apks[0])).strip())
    adb("shell", "am", "force-stop", PACKAGE)
    print(adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity").strip())

    def screen():
        adb("shell", "uiautomator", "dump", "/sdcard/terraria-release-smoke.xml")
        raw = adb("shell", "cat", "/sdcard/terraria-release-smoke.xml")
        return raw, ET.fromstring(raw)

    for attempt in range(15):
        raw, root = screen()
        if any(node.get("package") == PACKAGE for node in root.iter("node")):
            break
        time.sleep(1)
    else:
        print(adb("logcat", "-b", "crash", "-d", "-v", "brief"))
        raise SystemExit("Signed release did not display its home screen.")

    (args.output / "home.xml").write_text(raw, encoding="utf-8")
    for attempt in range(8):
        labels = {node.get("text") for node in root.iter("node") if node.get("package") == PACKAGE}
        if {"GitHub 项目与反馈", "哔哩哔哩主页"}.issubset(labels):
            break
        dimensions = re.search(r"(?:Override|Physical) size: (\d+)x(\d+)", adb("shell", "wm", "size"))
        if dimensions is None:
            raise SystemExit("Emulator display size could not be read.")
        width, height = map(int, dimensions.groups())
        adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 4), "350")
        raw, root = screen()
    else:
        raise SystemExit("Signed release home is missing the project or author link.")
    (args.output / "home-links.xml").write_text(raw, encoding="utf-8")
    adb("shell", "screencap", "-p", "/sdcard/terraria-release-smoke.png")
    adb("pull", "/sdcard/terraria-release-smoke.png", str(args.output / "home-links.png"))
    print("PASS: signed release starts and displays both homepage links.")


if __name__ == "__main__":
    main()
