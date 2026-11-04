"""Static About destination contracts; not a substitute for rendered Android UI proof."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ABOUT = ROOT / 'app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt'


class AboutDestinationContractTest(unittest.TestCase):
    def test_about_does_not_render_hardcoded_empty_link_destinations(self):
        source = ABOUT.read_text(encoding='utf-8')
        self.assertNotRegex(source, r'\burl\s*=\s*"\s*"')
        self.assertNotRegex(source, r'\bopenUri\(\s*"\s*"\s*\)')

    def test_existing_functional_discord_and_upstream_license_routes_remain(self):
        source = ABOUT.read_text(encoding='utf-8')
        constants = (ROOT / 'core/common/src/main/kotlin/tachiyomi/core/common/Constants.kt').read_text(encoding='utf-8')
        self.assertIn('url = Constants.URL_DISCORD', source)
        self.assertRegex(constants, r'URL_DISCORD\s*=\s*"https://[^"\s]+"')
        self.assertIn('stringResource(MR.strings.about_mihon_credit)', source)
        self.assertIn('uriHandler.openUri("https://github.com/mihonapp/mihon")', source)
        self.assertIn('navigator.push(OpenSourceLicensesScreen())', source)
        resource = ET.parse(ROOT / 'i18n/src/commonMain/moko-resources/base/strings.xml').getroot()
        credit = next(''.join(item.itertext()) for item in resource if item.get('name') == 'about_mihon_credit')
        self.assertIn('Mihon', credit)
        self.assertIn('Apache 2.0', credit)


if __name__ == '__main__':
    unittest.main()
