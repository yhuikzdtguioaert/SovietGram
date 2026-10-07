#!/usr/bin/env python3
"""CI-only APK archive/signature validation; never trusts a renamed file."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root = Path.cwd()
source = (root / "TMessagesProj/build.gradle").read_text()
expected_code = re.search(r"^def verCode = (\d+)\s*$", source, re.M).group(1)
properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.startswith("#"))
expected_name = properties["APP_VERSION_NAME"]
commit = os.environ.get("COMMIT_ID", "")
if commit:
    expected_name += "-" + commit[:7]
tools = Path(os.environ["ANDROID_SDK_ROOT"]) / "build-tools/36.0.0"
apks = sorted((root / "TMessagesProj/build/outputs/apk/release").glob("*.apk"))
if len(apks) != 2:
    raise SystemExit(f"Expected two release APK splits, found {len(apks)}")
records = []
remaining_abis = {"arm64-v8a", "x86_64"}
for apk in apks:
    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package:
        raise SystemExit(f"Cannot read embedded package version: {apk.name}")
    app_id, code, name = package.groups()
    if (app_id, code, name) != (properties["APP_PACKAGE"], expected_code, expected_name):
        raise SystemExit(f"Embedded APK metadata mismatch: {apk.name}: {package.group(0)}")
    abi = next((abi for abi in remaining_abis
                if apk.name == f"SovietGram-v{name}({code})-{abi}.apk"), None)
    if abi is None:
        raise SystemExit(f"Filename does not match embedded release version/expected split: {apk.name}")
    native_code = re.search(r"^native-code:(.*)$", badging, re.M)
    embedded_abis = set(re.findall(r"'([^']+)'", native_code.group(1))) if native_code else set()
    if embedded_abis != {abi}:
        raise SystemExit(f"Embedded APK ABI mismatch: {apk.name}: expected {abi}, found {sorted(embedded_abis)}")
    remaining_abis.remove(abi)
    subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], check=True)
    with apk.open("rb") as archive:
        digest = hashlib.file_digest(archive, "sha256").hexdigest()
    records.append(dict(file=apk.name, package=app_id, versionCode=int(code), versionName=name, abi=abi, sha256=digest))
    print(f"Verified {apk.name}: embedded versionCode={code} versionName={name}")
manifest = apks[0].parent / "verified-release.json"
manifest.write_text(json.dumps(records, indent=2) + "\n")
if os.environ.get("GITHUB_OUTPUT"):
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"version_code={code}\nversion_name={name}\n")
