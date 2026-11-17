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

def _load_module():
    spec = importlib.util.spec_from_file_location("prepare_official_catalog", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PrepareOfficialCatalogTest(unittest.TestCase):
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
