"""Public CLI format/refusal tests. Fake SDK output NEVER establishes crypto success."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/signing/verify_beta1_signed.py'
APP = 'e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77'
EXT = '2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408'
DEFS = [('daimei-arm64', 'app.daimei', 2, '0.1.0-beta.1', None), ('daimei-universal', 'app.daimei', 2, '0.1.0-beta.1', None), ('matchanovels', 'app.novel.extension.matchanovels', 3, '1.0.2', '1.1'), ('skynovels', 'app.novel.extension.skynovels', 3, '1.0.2', '1.1'), ('jabrascan', 'app.novel.extension.jabrascan', 4, '1.0.3', '1.3')]


class SignedCheckerTest(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'app/build/beta1-signing-format-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.sdk = self.root / 'sdk'
        self.sdk.mkdir()
        self.signed = self.root / 'signed'
        self.rows, self.facts = [], {}
        for ident, package, code, version, api in DEFS:
            filename = {'daimei-arm64': 'daimei-v0.1.0-beta.1-arm64-v8a.apk', 'daimei-universal': 'daimei-v0.1.0-beta.1-universal.apk'}.get(ident, ident + '-v' + version + '.apk')
            unsigned = self.root / 'unsigned' / filename
            unsigned.parent.mkdir(exist_ok=True)
            signed = self.signed / ident / filename
            signed.parent.mkdir(parents=True)
            for path, comment in [(unsigned, b''), (signed, b'FORMAT_ONLY_NOT_A_SIGNATURE')]:
                with zipfile.ZipFile(path, 'w') as z:
                    z.writestr('AndroidManifest.xml', 'SYNTHETIC_FORMAT_FIXTURE')
                    z.writestr('classes.dex', ident)
                    z.comment = comment
            payload = [[n, len(v), hashlib.sha256(v).hexdigest()] for n, v in [('AndroidManifest.xml', b'SYNTHETIC_FORMAT_FIXTURE'), ('classes.dex', ident.encode())]]
            digest = hashlib.sha256(json.dumps(payload, separators=(',', ':')).encode()).hexdigest()
            abis = ['arm64-v8a'] if ident == 'daimei-arm64' else (['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'] if ident == 'daimei-universal' else [])
            row = dict(id=ident, filename=filename, unsignedPath='unsigned/' + filename, signedPath=ident + '/' + filename, packageName=package, versionCode=code, versionName=version, minSdk=26, targetSdk=36, nativeAbis=abis, novelApi=api, unsignedSha256=hashlib.sha256(unsigned.read_bytes()).hexdigest(), payloadSha256=digest, usesPermissions=[])
            self.rows.append(row)
            self.facts[filename] = dict(package=package, code=code, version=version, sdk=26, target=36, abis=abis, certs=[APP if package == 'app.daimei' else EXT], signatureExit=0)
        self.manifest = self.root / 'manifest.json'
        self.save_manifest()
        fake = '''#!/usr/bin/env python3
import json,sys
from pathlib import Path
facts=json.loads((Path(__file__).parent/'facts.json').read_text())[Path(sys.argv[-1]).name]
if Path(__file__).name=='apksigner':
 assert sys.argv[1:]==['verify','--verbose','--print-certs','--min-sdk-version','26','--max-sdk-version','36',sys.argv[-1]]
 if facts['signatureExit']: print('DOES NOT VERIFY',file=sys.stderr);sys.exit(facts['signatureExit'])
 print('Number of signers: '+str(len(facts['certs'])))
 for i,cert in enumerate(facts['certs'],1): print(f'Signer #{i} certificate SHA-256 digest: '+cert)
elif Path(__file__).name=='zipalign':
 assert sys.argv[1:5]==['-c','-P','16','4']
else:
 assert sys.argv[1:3]==['dump','badging']
 print("package: name='%s' versionCode='%s' versionName='%s'"%(facts['package'],facts['code'],facts['version']))
 print("sdkVersion:'%s'"%facts['sdk']); print("targetSdkVersion:'%s'"%facts['target'])
 if facts['abis']: print('native-code: '+' '.join("'%s'"%x for x in facts['abis']))
'''
        for name in ['apksigner', 'aapt2', 'zipalign']:
            p = self.sdk / name
            p.write_text(fake)
            p.chmod(0o700)

    def save_manifest(self):
        self.manifest.write_text(json.dumps(dict(schema='daimei-beta1-human-signing/v1', sourceCommit='72ecd6dadb15a80c56de1d51e56fda2101a6cd37', artifacts=self.rows)))

    def run_cli(self):
        (self.sdk / 'facts.json').write_text(json.dumps(self.facts))
        before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.rglob('*') if p.is_file()}
        q = subprocess.run([sys.executable, '-B', str(SCRIPT), '--manifest', str(self.manifest), '--signed-dir', str(self.signed), '--build-tools', str(self.sdk)], capture_output=True, text=True)
        after = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(before, after, 'Checker must leave all inputs/counters/files unchanged')
        return q

    def rejected(self, message):
        q = self.run_cli()
        self.assertEqual(q.returncode, 1, q.stderr)
        self.assertEqual(q.stdout, '')
        self.assertEqual(q.stderr, 'error: ' + message + '\n')

    def test_format_only_five_success_rows_and_real_file_hashes(self):
        q = self.run_cli()
        self.assertEqual(q.returncode, 0, q.stderr)
        self.assertEqual(q.stderr, '')
        out = json.loads(q.stdout)
        self.assertEqual(out['status'], 'SIGNED_SET_VERIFIED')
        self.assertEqual(len(out['artifacts']), 5)
        for a in out['artifacts']:
            p = self.signed / a['signedPath']
            self.assertEqual(a['sha256'], hashlib.sha256(p.read_bytes()).hexdigest())
            self.assertEqual(a['size'], p.stat().st_size)

    def test_unsigned_sdk_rejected(self):
        self.facts[self.rows[0]['filename']]['signatureExit'] = 1
        self.rejected('apksigner rejected daimei-arm64 (exit 1)')

    def test_wrong_and_additional_signer_rejected(self):
        for certs in [['0' * 64], [APP, EXT], [APP, APP]]:
            with self.subTest(certs=certs):
                self.facts[self.rows[0]['filename']]['certs'] = certs
                self.rejected('unexpected signer set: daimei-arm64')

    def test_metadata_boundaries(self):
        facts = self.facts[self.rows[0]['filename']]
        for key, value in [('package', 'app.daimei.dev'), ('code', 1), ('version', '0.1.0'), ('sdk', 25), ('target', 35), ('abis', ['x86'])]:
            with self.subTest(key=key):
                old = facts[key]
                facts[key] = value
                self.rejected('metadata mismatch: daimei-arm64')
                facts[key] = old

    def test_payload_mutation_rejected(self):
        p = self.signed / self.rows[0]['signedPath']
        with zipfile.ZipFile(p, 'a') as z:
            z.writestr('unrequested-resource', b'changed')
        self.rejected('payload mismatch: daimei-arm64')

    def test_missing_signed_file_rejected(self):
        (self.signed / self.rows[0]['signedPath']).unlink()
        self.rejected('missing or linked signed APK: daimei-arm64')

    def test_symlink_rejected(self):
        p = self.signed / self.rows[0]['signedPath']
        p.unlink()
        p.symlink_to(self.root / self.rows[0]['unsignedPath'])
        self.rejected('missing or linked signed APK: daimei-arm64')

    def test_unchanged_unsigned_bytes_not_distribution_bytes(self):
        row = self.rows[0]
        (self.signed / row['signedPath']).write_bytes((self.root / row['unsignedPath']).read_bytes())
        self.rejected('signed bytes equal unsigned input: daimei-arm64')

    def test_unexpected_debug_apk_rejected(self):
        (self.signed / 'debug.apk').write_bytes(b'not selected')
        self.rejected('signed directory must contain exactly the five selected APKs')

    def test_manifest_version_and_api_cannot_change(self):
        self.rows[-1]['novelApi'] = '1.4'
        self.save_manifest()
        self.rejected('invalid manifest identity: jabrascan')


if __name__ == '__main__':
    unittest.main()
