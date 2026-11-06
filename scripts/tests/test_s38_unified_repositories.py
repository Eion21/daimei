"""Source/resource contracts, not rendered Compose or phone acceptance.

Run from the application checkout: python3 -m unittest discover -s scripts/tests
-p test_s38_unified_repositories.py -v
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java"
TAB = JAVA / "eu/kanade/tachiyomi/ui/browse/extension/ExtensionsTab.kt"
EMPTY = JAVA / "eu/kanade/presentation/browse/ExtensionsScreen.kt"
SETTINGS = JAVA / "eu/kanade/presentation/more/settings/screen/SettingsBrowseScreen.kt"
ACTIVITY = JAVA / "eu/kanade/tachiyomi/ui/main/MainActivity.kt"
RESOURCES = ROOT / "i18n/src/commonMain/moko-resources"


def strings(locale):
    entries = ET.parse(RESOURCES / locale / "strings.xml").getroot().findall("string")
    names = [entry.attrib["name"] for entry in entries]
    if len(names) != len(set(names)):
        raise AssertionError(f"Duplicate resource names in {locale}")
    return {entry.attrib["name"]: "".join(entry.itertext()) for entry in entries}


class UnifiedRepositoryContractsTest(unittest.TestCase):
    def test_overflow_uses_the_single_repository_action_factory(self):
        source = TAB.read_text()
        self.assertIn("actions = extensionRepositoryOverflowActions(", source)
        self.assertNotIn("ExtensionStoresScreen", source)
        self.assertIn("MR.strings.novel_catalog_title", source)

    def test_empty_state_and_settings_open_the_same_novel_screen(self):
        for path in (EMPTY, SETTINGS):
            with self.subTest(path=path.name):
                source = path.read_text()
                self.assertIn("openExtensionRepositories", source)
                self.assertIn("MR.strings.novel_catalog_title", source)
                self.assertNotIn("ExtensionStoresScreen", source)
        settings = SETTINGS.read_text()
        self.assertNotIn("getExtensionStoreCountAsFlow", settings)
        self.assertIn("MR.strings.novel_catalog_query_hint", settings)

    def test_legacy_intents_only_show_notice_and_manifest_is_retained(self):
        source = ACTIVITY.read_text()
        self.assertNotIn("ExtensionStoresScreen", source)
        self.assertIn("rejectLegacyMangaRepositoryLink(", source)
        self.assertIn("MR.strings.novel_catalog_legacy_repository_unsupported", source)
        action_view = source.split("Intent.ACTION_VIEW -> {", 1)[1].split("else -> return false", 1)[0]
        self.assertNotIn('getQueryParameter("url")', action_view)
        self.assertLess(action_view.index("rejectLegacyMangaRepositoryLink("), action_view.index('endsWith(".tachibk")'))
        self.assertIn('if (!legacyRejected && intent.data.toString().endsWith(".tachibk"))', action_view)
        notice = action_view.split("rejectLegacyMangaRepositoryLink(", 1)[1].split("if (!legacyRejected", 1)[0]
        self.assertIn("MR.strings.novel_catalog_legacy_repository_unsupported", notice)
        self.assertIn("Toast.LENGTH_LONG", notice)
        for forbidden in ("navigator.", "getQueryParameter", "toHttpUrl", "query(", "install", "trust"):
            self.assertNotIn(forbidden, notice)
        android = "{http://schemas.android.com/apk/res/android}"
        filters = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").findall(".//intent-filter")
        pairs = {
            (scheme, host)
            for intent_filter in filters
            for scheme in (item.get(android + "scheme") for item in intent_filter.findall("data"))
            for host in (item.get(android + "host") for item in intent_filter.findall("data"))
            if scheme and host
        }
        self.assertTrue({("tachiyomi", "add-repo"), ("mihon", "extension-store")} <= pairs)

    def test_every_s38_resource_has_a_spanish_translation_and_matching_formats(self):
        base, es = strings("base"), strings("es")
        names = {name for name in base if name.startswith("novel_catalog_")}
        self.assertTrue(names)
        self.assertFalse(names - es.keys(), f"Missing Spanish resources: {sorted(names - es.keys())}")
        for name in names:
            with self.subTest(resource=name):
                self.assertEqual(re.findall(r"%\d+\$[sd]", base[name]), re.findall(r"%\d+\$[sd]", es[name]))
        self.assertEqual("Extension repositories", base["novel_catalog_title"])
        self.assertEqual("Repositorios de extensiones", es["novel_catalog_title"])
        self.assertEqual("URL HTTPS del catálogo", es["novel_catalog_url"])
        self.assertEqual("Consultar catálogo", es["novel_catalog_fetch"])
        self.assertEqual("Catálogo incompatible", es["novel_catalog_error_schema"])

    def test_copy_distinguishes_temporary_metadata_from_installed_extensions(self):
        es = strings("es")
        self.assertIn("Consulta temporal", es.get("novel_catalog_query_hint", ""))
        self.assertIn("no se guarda", es.get("novel_catalog_query_hint", ""))
        self.assertIn("no extensiones instaladas", es.get("novel_catalog_available", ""))
        self.assertIn("Solo consulta", es.get("novel_catalog_blocked", ""))
        self.assertIn("Instalación no disponible", es.get("novel_catalog_blocked", ""))
        self.assertIn("hostCompatible: false", es.get("novel_catalog_blocked", ""))
        self.assertEqual("Todavía no se ha consultado ningún catálogo.", es.get("novel_catalog_initial", ""))

    def test_installed_extension_callbacks_remain_connected(self):
        source = TAB.read_text()
        for callback in (
            "onInstallExtension", "onUninstallExtension", "onUpdateExtension",
            "onTrustExtension", "onOpenExtension", "onClickUpdateAll", "onRefresh",
        ):
            with self.subTest(callback=callback):
                self.assertRegex(source, rf"\b{callback}\s*=")
        for existing_preference in ("hideInLibraryItems", "enabledContentWarnings", "applyContentWarningsToInstalled"):
            self.assertIn(existing_preference, SETTINGS.read_text())


if __name__ == "__main__":
    unittest.main()
