"""Public resource and documentation contracts for the Daimei identity transition."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "i18n/src/commonMain/moko-resources"
FAMILIES = ("supportUsScreen.", "donationCampaign.")


class DaimeiIdentityTest(unittest.TestCase):
    def test_locales_do_not_override_old_donation_families(self):
        stale = []
        for path in sorted(RESOURCES.glob("*/strings.xml")):
            if path.parent.name == "base":
                continue
            for element in ET.parse(path).getroot():
                name = element.get("name", "")
                if name.startswith(FAMILIES):
                    stale.append(f"{path.parent.name}: {name}")
        self.assertEqual([], stale, "stale localized donation resources remain")

    def test_base_fork_and_license_attribution_is_not_a_donation_cta(self):
        entries = {
            e.get("name"): "".join(e.itertext())
            for e in ET.parse(RESOURCES / "base/strings.xml").getroot()
            if e.get("name", "").startswith(FAMILIES)
        }
        self.assertIn("Daimei", entries["donationCampaign.paragraph1"])
        self.assertIn("Mihon", entries["donationCampaign.paragraph1"])
        self.assertIn("Apache 2.0", entries["donationCampaign.paragraph2"])
        for text in entries.values():
            self.assertNotRegex(text, r"(?i)https?://|www\.|patreon|opencollective|\bdonat(?:e|ion)\b")

    def test_legacy_google_services_file_is_absent(self):
        # Do not read the file: only its presence is part of this contract.
        self.assertFalse((ROOT / "app/google-services.json").exists())

    def test_readme_and_contributing_link_separate_daimei_history(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        contributing = (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8")
        self.assertRegex(readme, r"(?im)^#\s+Daimei\b")
        for document in (readme, contributing):
            self.assertRegex(document, r"(?i)\]\((?:\./)?DAIMEI-CHANGELOG\.md(?:#[^)]*)?\)")
        self.assertTrue((ROOT / "DAIMEI-CHANGELOG.md").is_file())
        self.assertTrue((ROOT / "CHANGELOG.md").is_file())
        self.assertIn("Mihon", readme)
        self.assertIn("https://github.com/mihonapp/mihon", readme)


if __name__ == "__main__":
    unittest.main()
