"""Execute the workflow's real verification step with explicit SDK-tool fixtures.
These are contract tests, not claims of testing real APKs/signatures.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ReleaseVerificationTest(unittest.TestCase):
    def verify(self, embedded_code="1261", file_code="1261", signature_ok=True,
               embedded_abis=None, embedded_package="sovietgram.com",
               embedded_name="12.10.6", file_name="12.10.6", commit=""):
        workflow = (ROOT / ".github/workflows/android-release.yml").read_text()
        block = workflow.split("      - name: Verify APK output\n", 1)[1].split("      - name:", 1)[0]
        command = block.split("        run: |\n", 1)[1]
        command = "\n".join(line[10:] for line in command.splitlines() if line.strip())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "TMessagesProj/build/outputs/apk/release"
            output.mkdir(parents=True)
            for abi in ("arm64-v8a", "x86_64"):
                (output / f"SovietGram-v{file_name}({file_code})-{abi}.apk").write_bytes(b"explicit test fixture, not an APK")
            (root / "TMessagesProj/build.gradle").write_text("def verCode = 1261\n")
            (root / "gradle.properties").write_text("APP_VERSION_NAME=12.10.6\nAPP_PACKAGE=sovietgram.com\n")
            (root / "scripts").mkdir()
            for script in (ROOT / "scripts").glob("verify-release*"):
                (root / "scripts" / script.name).write_bytes(script.read_bytes())
            tools = root / "sdk/build-tools/36.0.0"
            tools.mkdir(parents=True)
            native_lines = {}
            for abi in ("arm64-v8a", "x86_64"):
                abis = (abi,) if embedded_abis is None else embedded_abis
                native_lines[abi] = "native-code:" + "".join(f" '{value}'" for value in abis) if abis else ""
            (tools / "aapt2").write_text(
                "#!/bin/sh\nprintf \"package: name='" + embedded_package + "' versionCode='" + embedded_code
                + "' versionName='" + embedded_name + "'\\n\"\n"
                + 'case "$3" in\n'
                + "".join(f"*-{abi}.apk) printf \"{line}\\n\" ;;\n" for abi, line in native_lines.items())
                + "esac\n")
            (tools / "apksigner").write_text("#!/bin/sh\nexit " + ("0" if signature_ok else "1") + "\n")
            for tool in tools.iterdir():
                tool.chmod(0o755)
            github_output = root / "outputs"
            github_output.write_text("existing=value\n")
            env = dict(os.environ, ANDROID_SDK_ROOT=str(root / "sdk"),
                       GITHUB_OUTPUT=str(github_output), COMMIT_ID=commit)
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", command], cwd=root,
                                    env=env, text=True, capture_output=True)
            manifest = output / "verified-release.json"
            result.manifest = json.loads(manifest.read_text()) if manifest.exists() else None
            result.github_output = github_output.read_text()
            return result

    def test_valid_embedded_release_is_accepted(self):
        result = self.verify()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("embedded versionCode=1261", result.stdout)
        digest = hashlib.sha256(b"explicit test fixture, not an APK").hexdigest()
        self.assertEqual([
            dict(file=f"SovietGram-v12.10.6(1261)-{abi}.apk", package="sovietgram.com",
                 versionCode=1261, versionName="12.10.6", abi=abi, sha256=digest)
            for abi in ("arm64-v8a", "x86_64")
        ], result.manifest)
        self.assertEqual("existing=value\nversion_code=1261\nversion_name=12.10.6\n",
                         result.github_output)

    def test_wrong_embedded_abi_is_rejected(self):
        result = self.verify(embedded_abis=("armeabi-v7a",))
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Embedded APK ABI mismatch", result.stderr)
        self.assertIsNone(result.manifest)
        self.assertEqual("existing=value\n", result.github_output)

    def test_missing_embedded_abi_is_rejected(self):
        result = self.verify(embedded_abis=())
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Embedded APK ABI mismatch", result.stderr)

    def test_multiple_embedded_abis_are_rejected(self):
        result = self.verify(embedded_abis=("arm64-v8a", "x86_64"))
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Embedded APK ABI mismatch", result.stderr)

    def test_wrong_embedded_package_is_rejected(self):
        result = self.verify(embedded_package="other.package")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Embedded APK metadata mismatch", result.stderr)

    def test_wrong_embedded_version_name_is_rejected(self):
        result = self.verify(embedded_name="12.10.5")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Embedded APK metadata mismatch", result.stderr)

    def test_commit_suffixed_release_is_accepted(self):
        result = self.verify(commit="abcdef0123456789", embedded_name="12.10.6-abcdef0",
                             file_name="12.10.6-abcdef0")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual({"12.10.6-abcdef0"}, {record["versionName"] for record in result.manifest})
        self.assertEqual("existing=value\nversion_code=1261\nversion_name=12.10.6-abcdef0\n",
                         result.github_output)

    def test_missing_commit_suffix_is_rejected(self):
        result = self.verify(commit="abcdef0123456789")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Embedded APK metadata mismatch", result.stderr)

    def test_mislabeled_filename_is_rejected(self):
        result = self.verify(file_code="1260")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Filename does not match", result.stderr)

    def test_invalid_signature_is_rejected(self):
        result = self.verify(signature_ok=False)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_mislabeled_old_archive_is_rejected(self):
        result = self.verify(embedded_code="1259")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

if __name__ == "__main__":
    unittest.main(verbosity=2)
