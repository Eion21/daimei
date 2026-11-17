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

    # ---------- fake SDK tools (format-only, never crypto proof) ----------

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

    # ---------- CLI driver ----------

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

    # ---------- help / basic CLI ----------

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

    # ---------- argument validation before SDK calls ----------

    def test_invalid_commits_rejected_before_sdk_calls(self) -> None:
        sentinel = self._write_sentinel_tool()
        apks = self._make_apks()
        for bad in [
            "abc123",  # too short
            "abc123" * 8,  # 48 hex, wrong length
            "A" * 40,  # uppercase, noncanonical
            "z" * 40,  # not hex
            "0123456789abcdef0123456789abcdef0123456",  # 39 chars
        ]:
            result = self._run_tool(apks, self.plan, self.out, commit=bad, aapt2=sentinel, apksigner=sentinel)
            self._assert_rejected(result, self.out, "invalid commit")
            self._assert_no_temp_leftovers()
        self.assertFalse((self.tmp / "sdk-called").exists(), "invalid commit must be rejected before SDK calls")

    def test_invalid_base_paths_rejected_before_sdk_calls(self) -> None:
        sentinel = self._write_sentinel_tool()
        apks = self._make_apks()
        for bad in [
            "../escape",
            "distribution/official/../x",
            "/leading-slash",
            "trailing/",
            "a?query",
            "a#fragment",
            "back\\slash",
            "percent%2Fencoded",
            "scheme:path",
            ".hidden/start",
        ]:
            result = self._run_tool(apks, self.plan, self.out, base_path=bad, aapt2=sentinel, apksigner=sentinel)
            self._assert_rejected(result, self.out, "invalid base path")
        self.assertFalse((self.tmp / "sdk-called").exists(), "invalid base path must be rejected before SDK calls")

    def test_unclean_apk_basename_rejected(self) -> None:
        apks = self._make_apks()
        bad = self.tmp / "matcha signed.apk"
        bad.write_bytes(b"x" * 16)
        apks["app.novel.extension.matchanovels"] = bad
        result = self._run_tool(apks, self.plan, self.out)
        self._assert_rejected(result, self.out, "APK basename")

    def test_duplicate_apk_basenames_rejected(self) -> None:
        dir_a = self.tmp / "a"
        dir_b = self.tmp / "b"
        dir_a.mkdir()
        dir_b.mkdir()
        (dir_a / "twin.apk").write_bytes(b"a" * 16)
        (dir_b / "twin.apk").write_bytes(b"b" * 16)
        apks = self._make_apks()
        apks["app.novel.extension.matchanovels"] = dir_a / "twin.apk"
        apks["app.novel.extension.skynovels"] = dir_b / "twin.apk"
        result = self._run_tool(apks, self.plan, self.out)
        self._assert_rejected(result, self.out, "duplicate APK basename")

    def test_duplicate_package_mapping_rejected(self) -> None:
        apks = self._make_apks()
        twin = self.tmp / "matcha-twin-signed.apk"
        twin.write_bytes(apks["app.novel.extension.matchanovels"].read_bytes())
        raw = [
            f"app.novel.extension.matchanovels={apks['app.novel.extension.matchanovels']}",
            f"app.novel.extension.matchanovels={twin}",
            f"app.novel.extension.jabrascan={apks['app.novel.extension.jabrascan']}",
        ]
        result = self._run_tool(apks, self.plan, self.out, raw_apk_args=raw)
        self._assert_rejected(result, self.out, "duplicate package mapping")

    def test_unknown_mapping_package_rejected(self) -> None:
        apks = self._make_apks()
        del apks["app.novel.extension.jabrascan"]
        apks["app.novel.extension.evil"] = self.tmp / "evil-signed.apk"
        apks["app.novel.extension.evil"].write_bytes(b"e" * 16)
        result = self._run_tool(apks, self.plan, self.out)
        self._assert_rejected(result, self.out, "official-package")

    def test_missing_mapping_package_rejected(self) -> None:
        # A missing mapping shortens the argument list, so the count gate fires
        # first; the missing-package set check in the parser is defense-in-depth
        # for callers that bypass the CLI count gate.
        apks = self._make_apks()
        del apks["app.novel.extension.skynovels"]
        result = self._run_tool(apks, self.plan, self.out)
        self._assert_rejected(result, self.out, "exactly 3 --apk arguments are required")

    # ---------- plan pinning ----------

    def test_malformed_plan_json_rejected(self) -> None:
        bad = self.tmp / "plan-bad.json"
        bad.write_text("{not json", encoding="utf-8")
        result = self._run_tool(self._make_apks(), bad, self.out)
        self._assert_rejected(result, self.out, "plan JSON parse error")

    def test_plan_wrong_protocol_rejected(self) -> None:
        plan = self._mutated_plan(lambda d: d.update(protocol="other"))
        result = self._run_tool(self._make_apks(), plan, self.out)
        self._assert_rejected(result, self.out, "protocol")

    def test_plan_wrong_schema_version_rejected(self) -> None:
        plan = self._mutated_plan(lambda d: d.update(schemaVersion=1))
        result = self._run_tool(self._make_apks(), plan, self.out)
        self._assert_rejected(result, self.out, "schemaVersion")

    def test_plan_extra_top_level_key_rejected(self) -> None:
        plan = self._mutated_plan(lambda d: d.update(extra=True))
        result = self._run_tool(self._make_apks(), plan, self.out)
        self._assert_rejected(result, self.out, "schema keys")

    def test_plan_unknown_package_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["packageName"] = "app.novel.extension.evil"

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "not an official Beta 1 extension")

    def test_plan_duplicate_package_rejected(self) -> None:
        def mutate(document):
            document["extensions"][1]["packageName"] = document["extensions"][0]["packageName"]

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "exactly the three official packages")

    def test_plan_wrong_certificate_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["currentSigners"] = ["0" * 64]

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "official extensions certificate")

    def test_plan_wrong_api_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["novelApi"] = "1.3"

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "plan novelApi for app.novel.extension.matchanovels must be '1.1'")

    def test_plan_unsupported_api_rejected(self) -> None:
        def mutate(document):
            document["extensions"][2]["novelApi"] = "1.4"

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "plan novelApi for app.novel.extension.jabrascan must be '1.3'")

    def test_plan_wrong_source_ids_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["sourceIds"] = ["skynovels"]

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "plan sourceIds for app.novel.extension.matchanovels must be")

    def test_plan_wrong_entry_classes_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["entryClasses"] = ["app.novel.extension.matchanovels.Other"]

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "plan entryClasses for app.novel.extension.matchanovels must be")

    def test_plan_non_null_artifact_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["artifact"] = {
                "url": "https://example.invalid/x.apk", "size": 1, "sha256": "0" * 64,
            }

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "must be null")

    def test_plan_nonpositive_version_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["longVersionCode"] = 0

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "longVersionCode")

    def test_plan_string_version_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["longVersionCode"] = "3"

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "longVersionCode")

    def test_plan_extra_entry_key_rejected(self) -> None:
        def mutate(document):
            document["extensions"][0]["unexpected"] = True

        result = self._run_tool(self._make_apks(), self._mutated_plan(mutate), self.out)
        self._assert_rejected(result, self.out, "schema keys")

    # ---------- observation validation ----------

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

    def test_observed_version_code_mismatch_rejected(self) -> None:
        badging = dict(BADGING)
        badging["matcha-signed.apk"] = BADGING["matcha-signed.apk"].replace("versionCode='3'", "versionCode='99'")
        aapt2 = self._write_fake_aapt2(badging=badging)
        result = self._run_tool(self._make_apks(), self.plan, self.out, aapt2=aapt2)
        self._assert_rejected(result, self.out, "versionCode 99 does not match plan")

    def test_observed_package_mismatch_rejected(self) -> None:
        badging = dict(BADGING)
        badging["matcha-signed.apk"] = BADGING["matcha-signed.apk"].replace(
            "app.novel.extension.matchanovels", "app.novel.extension.other",
        )
        aapt2 = self._write_fake_aapt2(badging=badging)
        result = self._run_tool(self._make_apks(), self.plan, self.out, aapt2=aapt2)
        self._assert_rejected(result, self.out, "does not match plan")

    def test_observed_min_sdk_mismatch_rejected(self) -> None:
        badging = dict(BADGING)
        badging["matcha-signed.apk"] = BADGING["matcha-signed.apk"].replace("minSdkVersion:'26'", "minSdkVersion:'27'")
        aapt2 = self._write_fake_aapt2(badging=badging)
        result = self._run_tool(self._make_apks(), self.plan, self.out, aapt2=aapt2)
        self._assert_rejected(result, self.out, "minSdk 27 does not match plan")

    def test_observed_api_mismatch_rejected(self) -> None:
        xmltree = dict(XMLTREE)
        xmltree["jabrascan-signed.apk"] = XMLTREE["jabrascan-signed.apk"].replace(")=1.3", ")=1.1")
        aapt2 = self._write_fake_aapt2(xmltree=xmltree)
        result = self._run_tool(self._make_apks(), self.plan, self.out, aapt2=aapt2)
        self._assert_rejected(result, self.out, "novelApi")

    def test_observed_entry_class_mismatch_rejected(self) -> None:
        xmltree = dict(XMLTREE)
        xmltree["matcha-signed.apk"] = XMLTREE["matcha-signed.apk"].replace(
            "MatchaNovelsSource", "OtherSource",
        )
        aapt2 = self._write_fake_aapt2(xmltree=xmltree)
        result = self._run_tool(self._make_apks(), self.plan, self.out, aapt2=aapt2)
        self._assert_rejected(result, self.out, "entryClass")

    def test_missing_sdk_tool_rejected_cleanly(self) -> None:
        result = self._run_tool(
            self._make_apks(), self.plan, self.out,
            aapt2=self.tmp / "nonexistent-aapt2", apksigner=self.tmp / "nonexistent-apksigner",
        )
        self._assert_rejected(result, self.out, "SDK tool failed to run")
        self.assertNotIn("Traceback", result.stderr)

    def test_missing_apk_file_rejected(self) -> None:
        apks = self._make_apks()
        apks["app.novel.extension.matchanovels"] = self.tmp / "does-not-exist.apk"
        result = self._run_tool(apks, self.plan, self.out)
        self._assert_rejected(result, self.out, "APK not found")

    # ---------- output safety ----------

    def test_existing_catalog_never_overwritten(self) -> None:
        self.out.write_text("verified-sentinel", encoding="utf-8")
        result = self._run_tool(self._make_apks(), self.plan, self.out)
        self.assertEqual(1, result.returncode)
        self.assertIn("refusing to overwrite", result.stderr)
        self.assertEqual("verified-sentinel", self.out.read_text(encoding="utf-8"))
        self._assert_no_temp_leftovers()

    # ---------- positive end-to-end (format only, not crypto proof) ----------

    def test_positive_end_to_end_output_structure(self) -> None:
        # Fake SDK tools verify FORMAT ONLY: no real signature exists here and
        # no cryptographic success is claimed. The real signed happy path stays
        # pending the human signing step. The produced structure must decode
        # through the real NovelDistributionCatalogCodec (pinned on the Kotlin
        # side by NovelDistributionCatalogTest's official finalize structure
        # test, which mirrors this exact field layout).
        apks = self._make_apks()
        result = self._run_tool(apks, self.plan, self.out)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)
        self.assertTrue(self.out.exists())
        self._assert_no_temp_leftovers()

        def expected_entry(pkg, source_id, api, klass, apk):
            version_code, version_name = PLAN_VERSIONS[pkg]
            data = apk.read_bytes()
            return {
                "packageName": pkg,
                "longVersionCode": version_code,
                "versionName": version_name,
                "novelApi": api,
                "entryClasses": [klass],
                "minSdk": 26,
                "artifact": {
                    "url": f"{RAW_BASE}/{TEST_COMMIT}/{DEFAULT_BASE_PATH}/{apk.name}",
                    "size": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                },
                "currentSigners": [OFFICIAL_CERT],
                "sourceIds": [source_id],
            }

        expected = {
            "protocol": "daimei-novel-distribution",
            "schemaVersion": 2,
            "repository": "Daimei-Extensions/extensions-repo",
            "extensions": [
                expected_entry(
                    "app.novel.extension.matchanovels", "matchanovels", "1.1",
                    "app.novel.extension.matchanovels.MatchaNovelsSource",
                    apks["app.novel.extension.matchanovels"],
                ),
                expected_entry(
                    "app.novel.extension.skynovels", "skynovels", "1.1",
                    "app.novel.extension.skynovels.SkyNovelsSource",
                    apks["app.novel.extension.skynovels"],
                ),
                expected_entry(
                    "app.novel.extension.jabrascan", "jabrascan", "1.3",
                    "app.novel.extension.jabrascan.JabraScanSource",
                    apks["app.novel.extension.jabrascan"],
                ),
            ],
        }
        actual = json.loads(self.out.read_text(encoding="utf-8"))
        self.assertEqual(expected, actual)

        # Null optional fields from the plan must NOT be spread into the
        # catalog: the real codec rejects an explicit "abis": null.
        for entry in actual["extensions"]:
            self.assertNotIn("abis", entry)
        # source IDs, classes and APIs are preserved.
        by_package = {e["packageName"]: e for e in actual["extensions"]}
        self.assertEqual(["matchanovels"], by_package["app.novel.extension.matchanovels"]["sourceIds"])
        self.assertEqual(["skynovels"], by_package["app.novel.extension.skynovels"]["sourceIds"])
        self.assertEqual(["jabrascan"], by_package["app.novel.extension.jabrascan"]["sourceIds"])
        self.assertEqual("1.3", by_package["app.novel.extension.jabrascan"]["novelApi"])
        self.assertEqual(
            ["app.novel.extension.jabrascan.JabraScanSource"],
            by_package["app.novel.extension.jabrascan"]["entryClasses"],
        )

    # ---------- parser unit tests over embedded pass5 SDK lines ----------

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
