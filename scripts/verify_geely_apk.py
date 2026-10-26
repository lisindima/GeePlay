#!/usr/bin/env python3
"""Verify the exact APK that may be published; never print credential contents."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--profile", choices=("diagnostic", "release"), required=True)
    parser.add_argument("--certificate-sha256")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match:
        raise SystemExit("Cannot read APK identity")
    package, code, version = match.groups()
    diagnostic = args.profile == "diagnostic"
    assert package == "com.lllhys.geeplay" + (".debug" if diagnostic else ""), "Unexpected package ID"
    assert int(code) == args.version_code, "Unexpected versionCode"
    assert version == args.version + ("-debug" if diagnostic else ""), "Unexpected versionName"
    assert ("application-debuggable" in badging) == diagnostic, "Unexpected debug flag"
    signing = subprocess.check_output([args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signing)
    assert len(certificates) == 1, "Expected one signing certificate"
    fingerprint = certificates[0].lower()
    if args.certificate_sha256:
        assert fingerprint == args.certificate_sha256.replace(":", "").lower(), "Signing identity changed"
    elif not diagnostic:
        raise SystemExit("Release requires a pinned signing certificate")
    manifest = subprocess.check_output([args.aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
    for forbidden in ("MapEmbedService", "MyCarAppService", "PhoneAudioConnectionService", "AutoDisplay", "com.lllhys.geeplay.permission.NAVIGATION"):
        assert forbidden not in manifest, "Forbidden component in merged manifest: " + forbidden
    assert "GeePlayApplication" in manifest, "Wrong application class"
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        credentials = [name for name in names if name.startswith("assets/") and (
            "/offline-mfi/" in name or Path(name).suffix.lower() in
            {".pk8", ".p7b", ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore"})]
        expected = {"assets/offline-mfi/identity.pk8", "assets/offline-mfi/certificate.p7b"}
        if diagnostic:
            assert not credentials, "Diagnostic APK must not contain authentication assets"
        else:
            assert set(credentials) == expected, "Unexpected or missing authentication assets"
            assert all(archive.getinfo(name).file_size > 0 for name in expected), "Empty authentication asset"
        for abi in ("arm64-v8a", "armeabi-v7a"):
            assert f"lib/{abi}/libdiplay_opus_jni.so" in names, "Missing Opus backend: " + abi
    report = {"package": package, "versionCode": int(code), "versionName": version,
              "profile": args.profile, "signerSha256": fingerprint,
              "sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest(),
              "authentication": "absent" if diagnostic else "bundled; distribution permission required"}
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
