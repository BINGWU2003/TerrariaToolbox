"""Verify the installable release and its unchanged bundled saves; write delivery hashes."""

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


def command(*args):
    return subprocess.check_output([str(arg) for arg in args], stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/distribution"))
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--assets", type=Path, default=Path("Masters Of Terraria"))
    parser.add_argument("--expected-tag")
    args = parser.parse_args()
    apks = list(args.apk_dir.glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit("Expected exactly one release APK; clean the distribution directory before building.")
    apk = apks[0]
    executable_suffix = ".exe" if (args.build_tools / "aapt.exe").is_file() else ""
    badging = command(args.build_tools / f"aapt{executable_suffix}", "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if package is None:
        raise SystemExit("APK package metadata could not be read.")
    application_id, version_code, version = package.groups()
    if application_id != "io.github.bingwu2003.terrariatoolbox":
        raise SystemExit(f"Unexpected application ID: {application_id}")
    if "application-debuggable" in badging or "debug" in apk.name.lower():
        raise SystemExit("Delivery must be a non-debuggable release without debug in its filename.")
    if apk.name != f"TerrariaToolbox-v{version}.apk":
        raise SystemExit("APK filename does not match its versionName.")
    if args.expected_tag and args.expected_tag != f"v{version}":
        raise SystemExit(f"Tag {args.expected_tag} does not match APK version v{version}.")
    signer = args.build_tools / ("apksigner.bat" if executable_suffix else "apksigner")
    signature = command(signer, "verify", "--verbose", "--print-certs", apk)
    if "Android Debug" in signature:
        raise SystemExit("Delivery must use the permanent release signing key.")
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signature)
    if certificate is None:
        raise SystemExit("Signing certificate digest was not reported.")

    expected_assets = {f"assets/{path.relative_to(args.assets).as_posix()}": path
                       for path in args.assets.rglob("*") if path.suffix.lower() in (".plr", ".wld")}
    with zipfile.ZipFile(apk) as archive:
        # Android APK tools can write UTF-8 names without ZIP's UTF-8 flag.
        entries = {}
        for entry in archive.infolist():
            name = entry.filename
            if not entry.flag_bits & 0x800:
                try:
                    name = name.encode("cp437").decode("utf-8")
                except UnicodeError:
                    pass
            if name in entries:
                raise SystemExit(f"Duplicate APK entry: {name}")
            entries[name] = entry
        actual_assets = {name for name in entries if name.startswith("assets/") and name.endswith((".plr", ".wld"))}
        if not expected_assets or actual_assets != set(expected_assets):
            raise SystemExit("Bundled save catalog does not match the supplied resources.")
        for name, source in expected_assets.items():
            if archive.read(entries[name]) != source.read_bytes():
                raise SystemExit(f"Bundled save bytes changed: {name}")
        if any(name.startswith("assets/") and name.endswith(".md") for name in entries):
            raise SystemExit("Documentation was included in APK assets.")

    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (args.apk_dir / "SHA256SUMS.txt").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    (args.apk_dir / "signature.txt").write_text(signature, encoding="utf-8")
    report = {"applicationId": application_id, "versionName": version, "versionCode": int(version_code),
              "debuggable": False, "apk": apk.name, "bytes": apk.stat().st_size, "sha256": digest,
              "certificateSha256": certificate.group(1), "unchangedBundledSaves": len(expected_assets)}
    (args.apk_dir / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
