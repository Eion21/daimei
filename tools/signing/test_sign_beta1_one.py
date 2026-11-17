"""Headless/help checks only. Never invoke a signer or touch private stores."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/signing/sign_beta1_one.sh'


class HumanLauncherTest(unittest.TestCase):
    def test_help_is_public_and_no_signing(self):
        p = subprocess.run(['bash', str(SCRIPT), '--help'], stdin=subprocess.DEVNULL, capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(p.stderr, '')
        self.assertIn('WORKSET PROFILE', p.stdout)
        self.assertIn('private terminal', p.stdout)

    def test_headless_refuses_before_accessing_workset_or_creating_outputs(self):
        workset = ROOT / 'app/build/NO_TTY_MUST_NOT_CREATE_BETA1'
        self.assertFalse(workset.exists())
        for profile in ['daimei-arm64', 'daimei-universal', 'matchanovels', 'skynovels', 'jabrascan']:
            p = subprocess.run(['bash', str(SCRIPT), str(workset), profile], stdin=subprocess.DEVNULL, capture_output=True, text=True)
            self.assertEqual(p.returncode, 2, p.stderr)
            self.assertEqual(p.stdout, '')
            self.assertEqual(p.stderr, 'STOP: private TTY required; no signing command was run.\n')
            self.assertFalse(workset.exists())


if __name__ == '__main__':
    unittest.main()
