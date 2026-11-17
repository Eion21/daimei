#!/usr/bin/env python3
"""Read-only Beta1 signed-APK checks; never sign, open stores, publish or install.

Print JSON only after all five inputs pass. A trusted Android SDK verifies actual
signatures for API26–36; payload equality binds DEX/resources/manifest/source IDs
and permissions to the prepared unsigned APKs, independent of ZIP alignment.
This is not a device runtime compatibility test. No output files are written.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

SOURCE = '72ecd6dadb15a80c56de1d51e56fda2101a6cd37'
APP_CERT = 'e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77'
EXT_CERT = '2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408'
IDENTITIES = {
    'daimei-arm64': ('app.daimei', 2, '0.1.0-beta.1', None, ['arm64-v8a']),
    'daimei-universal': ('app.daimei', 2, '0.1.0-beta.1', None, ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']),
    'matchanovels': ('app.novel.extension.matchanovels', 3, '1.0.2', '1.1', []),
    'skynovels': ('app.novel.extension.skynovels', 3, '1.0.2', '1.1', []),
    'jabrascan': ('app.novel.extension.jabrascan', 4, '1.0.3', '1.3', []),
}
SIGNATURE_ENTRY = re.compile(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', re.I)


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def payload(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), 'duplicate ZIP entries')
        rows = []
        for name in sorted(names):
            if not SIGNATURE_ENTRY.fullmatch(name):
                data = z.read(name)
                rows.append([name, len(data), hashlib.sha256(data).hexdigest()])
    return hashlib.sha256(json.dumps(rows, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


def sdk_run(tool, args, ident):
    q = subprocess.run([str(tool), *args], capture_output=True, text=True, timeout=120)
    require(q.returncode == 0, f'{tool.name} rejected {ident} (exit {q.returncode})')
    return {'tool': tool.name, 'exit': q.returncode, 'stdout': q.stdout, 'stderr': q.stderr}


def filename(ident, version):
    if ident == 'daimei-arm64':
        return 'daimei-v0.1.0-beta.1-arm64-v8a.apk'
    if ident == 'daimei-universal':
        return 'daimei-v0.1.0-beta.1-universal.apk'
    return ident + '-v' + version + '.apk'


def verify(manifest_path, signed_dir, sdk):
    manifest = json.loads(manifest_path.read_text())
    require(manifest.get('schema') == 'daimei-beta1-human-signing/v1' and manifest.get('sourceCommit') == SOURCE, 'invalid prepared manifest/source')
    rows = manifest.get('artifacts', [])
    require(len(rows) == 5 and {r['id'] for r in rows} == set(IDENTITIES), 'manifest requires exactly five selected identities')
    selected = set()
    for row in rows:
        ident = row['id']
        package, code, version, api, abis = IDENTITIES[ident]
        name = filename(ident, version)
        require((row['packageName'], row['versionCode'], row['versionName'], row.get('novelApi'), row['minSdk'], row['targetSdk'], sorted(row['nativeAbis'])) == (package, code, version, api, 26, 36, abis) and row['filename'] == name and row['unsignedPath'] == 'unsigned/' + name and row['signedPath'] == ident + '/' + name, 'invalid manifest identity: ' + ident)
        p = signed_dir / row['signedPath']
        require(p.is_file() and not p.is_symlink() and p.resolve() == p.absolute(), 'missing or linked signed APK: ' + ident)
        selected.add(p.relative_to(signed_dir).as_posix())
    require({p.relative_to(signed_dir).as_posix() for p in signed_dir.rglob('*.apk')} == selected, 'signed directory must contain exactly the five selected APKs')
    output = []
    for row in rows:
        ident = row['id']
        unsigned = manifest_path.parent / row['unsignedPath']
        require(unsigned.is_file() and not unsigned.is_symlink() and unsigned.resolve() == unsigned.absolute(), 'missing or linked unsigned input: ' + ident)
        require(sha(unsigned) == row['unsignedSha256'] and payload(unsigned) == row['payloadSha256'], 'prepared input mismatch: ' + ident)
        p = signed_dir / row['signedPath']
        before = sha(p)
        require(before != row['unsignedSha256'], 'signed bytes equal unsigned input: ' + ident)
        require(payload(p) == row['payloadSha256'], 'payload mismatch: ' + ident)
        signature = sdk_run(sdk / 'apksigner', ['verify', '--verbose', '--print-certs', '--min-sdk-version', '26', '--max-sdk-version', '36', str(p)], ident)
        certs = re.findall(r'certificate SHA-256 digest:\s*([0-9a-f]{64})', signature['stdout'], re.I)
        expected_cert = APP_CERT if row['packageName'] == 'app.daimei' else EXT_CERT
        require([x.lower() for x in certs] == [expected_cert] and re.search(r'^Number of signers:\s*1\s*$', signature['stdout'], re.M), 'unexpected signer set: ' + ident)
        badging = sdk_run(sdk / 'aapt2', ['dump', 'badging', str(p)], ident)
        text = badging['stdout']
        package_line = next((x for x in text.splitlines() if x.startswith('package:')), '')
        facts = dict(re.findall(r"(\w+)='([^']*)'", package_line))
        minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
        target = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
        native = next((x for x in text.splitlines() if x.startswith('native-code:')), '')
        permissions = sorted(x for x in text.splitlines() if x.startswith(('uses-permission:', 'uses-permission-sdk-23:', 'uses-implied-permission:')))
        require(facts.get('name') == row['packageName'] and facts.get('versionCode') == str(row['versionCode']) and facts.get('versionName') == row['versionName'] and minimum and minimum.group(1) == '26' and target and target.group(1) == '36' and sorted(re.findall(r"'([^']+)'", native)) == sorted(row['nativeAbis']) and permissions == sorted(row['usesPermissions']) and 'application-debuggable' not in text, 'metadata mismatch: ' + ident)
        alignment = sdk_run(sdk / 'zipalign', ['-c', '-P', '16', '4', str(p)], ident)
        require(sha(p) == before, 'APK changed during verification: ' + ident)
        output.append({k: row[k] for k in ['id', 'signedPath', 'filename', 'packageName', 'versionCode', 'versionName', 'minSdk', 'targetSdk', 'nativeAbis', 'novelApi']} | {'sha256': before, 'size': p.stat().st_size, 'currentSigners': [expected_cert], 'payloadMatchesPrepared': True, 'commands': [signature, badging, alignment]})
    return {'status': 'SIGNED_SET_VERIFIED', 'sourceCommit': SOURCE, 'signatureSdkRange': [26, 36], 'runtimeDeviceTest': 'NOT_RUN', 'artifacts': output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True, help='Prepared manifest.json, with sibling unsigned/ inputs')
    parser.add_argument('--signed-dir', type=Path, required=True, help='Container of five per-profile output directories; no extra APKs')
    parser.add_argument('--build-tools', type=Path, required=True, help='Trusted Android SDK directory containing apksigner/aapt2/zipalign')
    args = parser.parse_args()
    try:
        result = verify(args.manifest.absolute(), args.signed_dir.absolute(), args.build_tools.absolute())
        print(json.dumps(result, indent=2))
        return 0
    except (VerificationError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print('error: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
