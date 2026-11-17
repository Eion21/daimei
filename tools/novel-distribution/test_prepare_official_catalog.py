"""Tests for prepare-official-catalog.py.

Refusal paths use the real public pass5 unsigned APKs and the real apksigner
where possible. Positive end-to-end tests use fake aapt2/apksigner scripts
whose outputs embed representative lines captured from the public pass5 SDK
dumps (aapt2 dump badging / aapt2 dump xmltree of the unsigned Release APKs).
Those fakes verify FORMAT ONLY: no real signature is verified, no signing key
exists or is generated, and no cryptographic success is claimed. The real
signed happy path stays pending the human signing step.

The output structure this tool produces is additionally pinned on the Kotlin
side: NovelDistributionCatalogTest decodes the same field layout through the
real NovelDistributionCatalogCodec.
"""

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parent / "prepare-official-catalog.py"
AAPT2 = Path("/home/jervis/Proyectos/Desarrollo/Android/Sdk/build-tools/36.0.0/aapt2")
APKSIGNER = Path("/home/jervis/Proyectos/Desarrollo/Android/Sdk/build-tools/36.0.0/apksigner")
UNSIGNED_MATCHA = (
    Path("/home/jervis/Proyectos/Desarrollo/Ln lector/odd/s39/public-release-readiness")
    / "unsigned-apks-141033df-pass5"
    / "novel-matchanovels-extension-universal"
    / "novel-matchanovels-extension-release-unsigned.apk"
)
OFFICIAL_CERT = "2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408"
# Format-only test vector: a syntactically valid full 40-hex commit. It is NOT
# a real catalog commit and no real URL is claimed to exist at this address.
TEST_COMMIT = "0123456789abcdef0123456789abcdef01234567"
RAW_BASE = "https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo"
DEFAULT_BASE_PATH = "distribution/official/s39"

PACKAGE_TO_BASENAME = {
    "app.novel.extension.matchanovels": "matcha-signed.apk",
    "app.novel.extension.skynovels": "skynovels-signed.apk",
    "app.novel.extension.jabrascan": "jabrascan-signed.apk",
}
PLAN_VERSIONS = {
    "app.novel.extension.matchanovels": (3, "1.0.2"),
    "app.novel.extension.skynovels": (3, "1.0.2"),
    "app.novel.extension.jabrascan": (4, "1.0.3"),
}

# Representative lines captured from the public pass5 unsigned Release SDK
# dumps (aapt2 dump badging). These are public build-tool outputs of unsigned
# APKs; they contain no secrets and prove no signature.
BADGING = {
    "matcha-signed.apk": (
        "package: name='app.novel.extension.matchanovels' versionCode='3' versionName='1.0.2'"
        " platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37'"
        " compileSdkVersionCodename='17'\n"
        "minSdkVersion:'26'\n"
        "targetSdkVersion:'36'\n"
        "application: label='MatchaNovels (extension)' icon=''\n"
        "feature-group: label=''\n"
        "  uses-feature: name='app.novel.extension'\n"
        "  uses-feature: name='android.hardware.faketouch'\n"
        "  uses-implied-feature: name='android.hardware.faketouch'"
        " reason='default feature for all apps'\n"
        "supports-screens: 'small' 'normal' 'large' 'xlarge'\n"
        "supports-any-density: 'true'\n"
        "locales:\n"
        "densities:\n"
    ),
    "skynovels-signed.apk": (
        "package: name='app.novel.extension.skynovels' versionCode='3' versionName='1.0.2'"
        " platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37'"
        " compileSdkVersionCodename='17'\n"
        "minSdkVersion:'26'\n"
        "targetSdkVersion:'36'\n"
        "application: label='SkyNovels (extension)' icon=''\n"
        "feature-group: label=''\n"
        "  uses-feature: name='app.novel.extension'\n"
        "  uses-implied-feature: name='android.hardware.faketouch'"
        " reason='default feature for all apps'\n"
        "supports-screens: 'small' 'normal' 'large' 'xlarge'\n"
        "supports-any-density: 'true'\n"
        "locales:\n"
        "densities:\n"
    ),
    "jabrascan-signed.apk": (
        "package: name='app.novel.extension.jabrascan' versionCode='4' versionName='1.0.3'"
        " platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37'"
        " compileSdkVersionCodename='17'\n"
        "minSdkVersion:'26'\n"
        "targetSdkVersion:'36'\n"
        "application: label='JabraScan (extension)' icon=''\n"
        "feature-group: label=''\n"
        "  uses-feature: name='app.novel.extension'\n"
        "  uses-implied-feature: name='android.hardware.faketouch'"
        " reason='default feature for all apps'\n"
        "supports-screens: 'small' 'normal' 'large' 'xlarge'\n"
        "supports-any-density: 'true'\n"
        "locales:\n"
        "densities:\n"
    ),
}

# Representative lines captured from the public pass5 aapt2 dump xmltree
# outputs. Note the typed float meta-data value for novelApi (no quotes) and
# the single fully-act entry class per extension.
XMLTREE = {
    "matcha-signed.apk": (
        "N: android=http://schemas.android.com/apk/res/android (line=2)\n"
        "  E: manifest (line=2)\n"
        "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=3\n"
        "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=\"1.0.2\""
        " (Raw: \"1.0.2\")\n"
        "    A: package=\"app.novel.extension.matchanovels\""
        " (Raw: \"app.novel.extension.matchanovels\")\n"
        "      E: uses-sdk (line=7)\n"
        "        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26\n"
        "        A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=36\n"
        "      E: uses-feature (line=16)\n"
        "        A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extension\" (Raw: \"app.novel.extension\")\n"
        "      E: application (line=18)\n"
        "        A: http://schemas.android.com/apk/res/android:label(0x01010001)="
        "\"MatchaNovels (extension)\" (Raw: \"MatchaNovels (extension)\")\n"
        "        A: http://schemas.android.com/apk/res/android:extractNativeLibs(0x010104ea)=false\n"
        "          E: meta-data (line=31)\n"
        "            A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extension.class\" (Raw: \"app.novel.extension.class\")\n"
        "            A: http://schemas.android.com/apk/res/android:value(0x01010024)="
        "\"app.novel.extension.matchanovels.MatchaNovelsSource\""
        " (Raw: \"app.novel.extension.matchanovels.MatchaNovelsSource\")\n"
        "          E: meta-data (line=40)\n"
        "            A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extensionApi\" (Raw: \"app.novel.extensionApi\")\n"
        "            A: http://schemas.android.com/apk/res/android:value(0x01010024)=1.1\n"
    ),
    "skynovels-signed.apk": (
        "N: android=http://schemas.android.com/apk/res/android (line=2)\n"
        "  E: manifest (line=2)\n"
        "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=3\n"
        "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=\"1.0.2\""
        " (Raw: \"1.0.2\")\n"
        "    A: package=\"app.novel.extension.skynovels\""
        " (Raw: \"app.novel.extension.skynovels\")\n"
        "      E: uses-sdk (line=7)\n"
        "        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26\n"
        "        A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=36\n"
        "      E: uses-feature (line=16)\n"
        "        A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extension\" (Raw: \"app.novel.extension\")\n"
        "      E: application (line=18)\n"
        "        A: http://schemas.android.com/apk/res/android:label(0x01010001)="
        "\"SkyNovels (extension)\" (Raw: \"SkyNovels (extension)\")\n"
        "          E: meta-data (line=31)\n"
        "            A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extension.class\" (Raw: \"app.novel.extension.class\")\n"
        "            A: http://schemas.android.com/apk/res/android:value(0x01010024)="
        "\"app.novel.extension.skynovels.SkyNovelsSource\""
        " (Raw: \"app.novel.extension.skynovels.SkyNovelsSource\")\n"
        "          E: meta-data (line=40)\n"
        "            A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extensionApi\" (Raw: \"app.novel.extensionApi\")\n"
        "            A: http://schemas.android.com/apk/res/android:value(0x01010024)=1.1\n"
    ),
    "jabrascan-signed.apk": (
        "N: android=http://schemas.android.com/apk/res/android (line=2)\n"
        "  E: manifest (line=2)\n"
        "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=4\n"
        "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=\"1.0.3\""
        " (Raw: \"1.0.3\")\n"
        "    A: package=\"app.novel.extension.jabrascan\""
        " (Raw: \"app.novel.extension.jabrascan\")\n"
        "      E: uses-sdk (line=7)\n"
        "        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26\n"
        "        A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=36\n"
        "      E: uses-feature (line=16)\n"
        "        A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extension\" (Raw: \"app.novel.extension\")\n"
        "      E: application (line=18)\n"
        "        A: http://schemas.android.com/apk/res/android:label(0x01010001)="
        "\"JabraScan (extension)\" (Raw: \"JabraScan (extension)\")\n"
        "          E: meta-data (line=31)\n"
        "            A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extension.class\" (Raw: \"app.novel.extension.class\")\n"
        "            A: http://schemas.android.com/apk/res/android:value(0x01010024)="
        "\"app.novel.extension.jabrascan.JabraScanSource\""
        " (Raw: \"app.novel.extension.jabrascan.JabraScanSource\")\n"
        "          E: meta-data (line=40)\n"
        "            A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        "\"app.novel.extensionApi\" (Raw: \"app.novel.extensionApi\")\n"
        "            A: http://schemas.android.com/apk/res/android:value(0x01010024)=1.3\n"
    ),
}


def _plan_extensions():
    facts = {
        "app.novel.extension.matchanovels": (
            "matchanovels", "1.1", ["app.novel.extension.matchanovels.MatchaNovelsSource"],
        ),
        "app.novel.extension.skynovels": (
            "skynovels", "1.1", ["app.novel.extension.skynovels.SkyNovelsSource"],
        ),
        "app.novel.extension.jabrascan": (
            "jabrascan", "1.3", ["app.novel.extension.jabrascan.JabraScanSource"],
        ),
    }
    extensions = []
    for pkg, (source_id, api, classes) in facts.items():
        version_code, version_name = PLAN_VERSIONS[pkg]
        extensions.append(
            {
                "packageName": pkg,
                "longVersionCode": version_code,
                "versionName": version_name,
                "novelApi": api,
                "entryClasses": classes,
                "minSdk": 26,
                "abis": None,
                "sourceIds": [source_id],
                "currentSigners": [OFFICIAL_CERT],
                "artifact": None,
            }
        )
    return extensions


def _plan_document():
    return {
        "protocol": "daimei-novel-distribution",
        "schemaVersion": 2,
        "repository": "Daimei-Extensions/extensions-repo",
        "extensions": _plan_extensions(),
    }


def _load_module():
    spec = importlib.util.spec_from_file_location("prepare_official_catalog", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PrepareOfficialCatalogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="s39-catalog-test-"))
        self.addCleanup(self._cleanup)
        self.plan = self.tmp / "plan.json"
        self.plan.write_text(json.dumps(_plan_document(), indent=2), encoding="utf-8")
        self.aapt2 = self._write_fake_aapt2()
        self.apksigner = self._write_fake_apksigner()
        self.out = self.tmp / "catalog.json"

    def _cleanup(self) -> None:
        import shutil

        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_fake_aapt2(self, badging=None, xmltree=None) -> Path:
        path = self.tmp / "fake-aapt2.py"
        path.write_text(
            "#!/usr/bin/env python3\n"
            "import sys\n"
            "from pathlib import Path\n"
            "BADGING = " + repr(badging or BADGING) + "\n"
            "XMLTREE = " + repr(xmltree or XMLTREE) + "\n"
            "args = sys.argv[1:]\n"
            "data = Path(args[-1]).name\n"
            "if 'badging' in args:\n"
            "    sys.stdout.write(BADGING.get(data, ''))\n"
            "else:\n"
            "    sys.stdout.write(XMLTREE.get(data, ''))\n",
            encoding="utf-8",
        )
        path.chmod(0o755)
        return path

    def _write_fake_apksigner(self, digest: str = OFFICIAL_CERT) -> Path:
        path = self.tmp / "fake-apksigner.py"
        path.write_text(
            "#!/usr/bin/env python3\n"
            "DIGEST = " + repr(digest) + "\n"
            "print('Verifies')\n"
            "print('Verified using v1 scheme (JAR signing): true')\n"
            "print('Signer #1 certificate SHA-256 digest: ' + DIGEST)\n",
            encoding="utf-8",
        )
        path.chmod(0o755)
        return path

    def _write_sentinel_tool(self) -> Path:
        path = self.tmp / "sentinel-tool.py"
        path.write_text(
            "#!/usr/bin/env python3\n"
            "from pathlib import Path\n"
            "Path(__file__).resolve().with_name('sdk-called').write_text('called')\n",
            encoding="utf-8",
        )
        path.chmod(0o755)
        return path

    def _make_apks(self) -> dict:
        seeds = {
            "matcha-signed.apk": b"matcha-signed-format-only-bytes",
            "skynovels-signed.apk": b"skynovels-signed-format-only-bytes",
            "jabrascan-signed.apk": b"jabrascan-signed-format-only-bytes",
        }
        apks = {}
        for pkg, name in PACKAGE_TO_BASENAME.items():
            path = self.tmp / name
            path.write_bytes(seeds[name] * 4)
            apks[pkg] = path
        return apks

    def _run_tool(
        self,
        apks: dict,
        plan: Path,
        out: Path,
        commit: str = TEST_COMMIT,
        base_path: str = DEFAULT_BASE_PATH,
        aapt2=None,
        apksigner=None,
        raw_apk_args=None,
    ) -> subprocess.CompletedProcess:
        argv = [
            sys.executable, str(SCRIPT),
            "--plan", str(plan),
            "--commit", commit,
            "--out", str(out),
            "--aapt2", str(aapt2 or self.aapt2),
            "--apksigner", str(apksigner or self.apksigner),
        ]
        if base_path is not None:
            argv += ["--base-path", base_path]
        if raw_apk_args is not None:
            for raw in raw_apk_args:
                argv += ["--apk", raw]
        else:
            for pkg, path in apks.items():
                argv += ["--apk", f"{pkg}={path}"]
        return subprocess.run(argv, capture_output=True, text=True)

    def _mutated_plan(self, mutate) -> Path:
        document = _plan_document()
        mutate(document)
        path = self.tmp / "plan-mutated.json"
        path.write_text(json.dumps(document, indent=2), encoding="utf-8")
        return path

    def _assert_rejected(
        self,
        result: subprocess.CompletedProcess,
        out: Path,
        stderr_fragment: str,
    ) -> None:
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)
        self.assertIn(stderr_fragment, result.stderr)
        self.assertFalse(out.exists(), "rejection must not write output")

    def _assert_no_temp_leftovers(self) -> None:
        leftovers = [p.name for p in self.tmp.iterdir() if p.name.startswith(".catalog")]
        self.assertEqual([], leftovers)

    def test_help(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--help"], capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode)
        self.assertIn("noninstallable", result.stdout)

    def test_missing_required_args_rejected(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], capture_output=True, text=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("usage:", result.stderr)

    def test_wrong_apk_count_rejected(self) -> None:
        apks = self._make_apks()
        del apks["app.novel.extension.jabrascan"]
        result = self._run_tool(apks, self.plan, self.out)
        self._assert_rejected(result, self.out, "exactly 3 --apk arguments are required")

    def test_real_unsigned_apk_rejected(self) -> None:
        # Real apksigner against a real public unsigned APK: an honest
        # cryptographic refusal, no mocks.
        apks = self._make_apks()
        apks["app.novel.extension.matchanovels"] = UNSIGNED_MATCHA
        result = self._run_tool(apks, self.plan, self.out, aapt2=AAPT2, apksigner=APKSIGNER)
        self._assert_rejected(result, self.out, "unsigned or signature is invalid")

    def test_wrong_observed_certificate_rejected(self) -> None:
        apksigner = self._write_fake_apksigner(digest="0" * 64)
        result = self._run_tool(self._make_apks(), self.plan, self.out, apksigner=apksigner)
        self._assert_rejected(result, self.out, "does not match plan")

    def test_existing_catalog_never_overwritten(self) -> None:
        self.out.write_text("verified-sentinel", encoding="utf-8")
        result = self._run_tool(self._make_apks(), self.plan, self.out)
        self.assertEqual(1, result.returncode)
        self.assertIn("refusing to overwrite", result.stderr)
        self.assertEqual("verified-sentinel", self.out.read_text(encoding="utf-8"))
        self._assert_no_temp_leftovers()

    def test_badging_parser_reads_pass5_format(self) -> None:
        module = _load_module()
        parsed = module._parse_badging(BADGING["matcha-signed.apk"])
        self.assertEqual("app.novel.extension.matchanovels", parsed["packageName"])
        self.assertEqual(3, parsed["versionCode"])
        self.assertEqual("1.0.2", parsed["versionName"])
        self.assertEqual(26, parsed["minSdk"])
        self.assertIn("app.novel.extension", parsed["features"])
        self.assertNotIn("tachiyomi.extension", parsed["features"])

    def test_manifest_parser_reads_typed_float_api_and_single_class(self) -> None:
        module = _load_module()
        parsed = module._parse_manifest(XMLTREE["jabrascan-signed.apk"])
        self.assertEqual("1.3", parsed["app.novel.extensionApi"])
        self.assertEqual(
            "app.novel.extension.jabrascan.JabraScanSource",
            parsed["app.novel.extension.class"],
        )
        parsed_matcha = module._parse_manifest(XMLTREE["matcha-signed.apk"])
        self.assertEqual("1.1", parsed_matcha["app.novel.extensionApi"])
        self.assertEqual(
            "app.novel.extension.matchanovels.MatchaNovelsSource",
            parsed_matcha["app.novel.extension.class"],
        )

    def test_apksigner_cert_parser_reads_pass5_format(self) -> None:
        module = _load_module()
        text = (
            "Verifies\n"
            "Verified using v1 scheme (JAR signing): true\n"
            "Signer #1 certificate SHA-256 digest: 2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408\n"
            "Signer #1 certificate SHA-1 digest: 8d0f4b7d3f9b6c5a2e1f0d9c8b7a6e5f4d3c2b1a\n"
        )
        self.assertEqual([OFFICIAL_CERT], module._parse_apksigner_certs(text))


if __name__ == "__main__":
    unittest.main()
