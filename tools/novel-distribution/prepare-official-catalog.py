#!/usr/bin/env python3
"""Finalize the official S39 novel-distribution catalog from real signed APKs.

Reads the noninstallable plan JSON (schemaVersion 2, every artifact field null),
pins the three official Beta 1 extension facts (package, source ID, novel API,
entry class, and the official extensions certificate), inspects three signed
APKs with aapt2 and apksigner, and writes a schemaVersion 2 catalog whose
artifact URLs are immutable Raw HTTPS paths pinned to a full Git commit hash.

Safety properties:
  * Invalid arguments (commit, base path, APK mappings) are rejected before any
    SDK tool runs and before any output side effect.
  * The plan is pinned to the official three extensions; an edited plan cannot
    substitute a package, source ID, API, entry class, or certificate.
  * Unsigned APKs, wrong packages, unsupported APIs, and version mismatches
    are rejected without writing any output.
  * An existing catalog is never overwritten; the output is written atomically
    and no partial or empty catalog is ever left behind.
  * The tool never invents a URL, hash, or commit. No fake or abbreviated
    commit (the tool rejects anything that is not a full 40-hex or 64-hex
    lowercase Git commit hash), and no default URL is ever emitted.

Test vectors elsewhere in this repository use fake aapt2/apksigner output to
verify FORMAT ONLY; they verify no signature and claim none. The real signed
happy path stays pending the human signing step.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

RAW_HOST = "https://raw.githubusercontent.com"
OFFICIAL_REPO = "Daimei-Extensions/extensions-repo"
DEFAULT_BASE_PATH = "distribution/official/s39"
EXPECTED_ENTRIES = 3
SDK_TIMEOUT_SECONDS = 120
MAX_BASE_PATH_BYTES = 512

# Official extensions certificate SHA-256 (public fingerprint, not a secret).
EXPECTED_EXTENSIONS_CERT = "2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408"

# The official Beta 1 extension facts pinned from the closure spec (S5/S6).
OFFICIAL_PACKAGES = {
    "app.novel.extension.matchanovels": {
        "sourceIds": ["matchanovels"],
        "novelApi": "1.1",
        "entryClasses": ["app.novel.extension.matchanovels.MatchaNovelsSource"],
    },
    "app.novel.extension.skynovels": {
        "sourceIds": ["skynovels"],
        "novelApi": "1.1",
        "entryClasses": ["app.novel.extension.skynovels.SkyNovelsSource"],
    },
    "app.novel.extension.jabrascan": {
        "sourceIds": ["jabrascan"],
        "novelApi": "1.3",
        "entryClasses": ["app.novel.extension.jabrascan.JabraScanSource"],
    },
}

PLAN_TOP_KEYS = {"protocol", "schemaVersion", "repository", "extensions"}
PLAN_ENTRY_KEYS = {
    "packageName", "longVersionCode", "versionName", "novelApi",
    "entryClasses", "minSdk", "abis", "sourceIds", "currentSigners", "artifact",
}

COMMIT_RE = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
BASE_PATH_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*$")
APK_BASENAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$")


class CatalogError(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=SDK_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        raise CatalogError(f"SDK tool timed out after {SDK_TIMEOUT_SECONDS}s: {cmd[0]}")
    except OSError as error:
        raise CatalogError(f"SDK tool failed to run: {cmd[0]}: {error}")


def _sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(8192), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def _parse_badging(text: str) -> dict[str, Any]:
    data: dict[str, Any] = {"features": []}
    package_match = re.search(
        r"package:\s*name='([^']+)'\s*versionCode='(\d+)'\s*versionName='([^']+)'",
        text,
    )
    if package_match:
        data["packageName"] = package_match.group(1)
        data["versionCode"] = int(package_match.group(2))
        data["versionName"] = package_match.group(3)
    sdk_match = re.search(r"minSdkVersion:'(\d+)'", text)
    if sdk_match:
        data["minSdk"] = int(sdk_match.group(1))
    for feature in re.findall(r"uses-feature:\s*name='([^']+)'", text):
        data["features"].append(feature)
    return data


def _parse_manifest(text: str) -> dict[str, str]:
    data: dict[str, str] = {}
    name: str | None = None
    for line in text.splitlines():
        line = line.strip()
        if "name(0x01010003)=" in line:
            match = re.search(r'name\([^)]*\)="([^"]+)"', line)
            if match:
                name = match.group(1)
        elif "value(0x01010024)=" in line:
            if name:
                match = re.search(r'value\([^)]*\)="?([^"\s]+)"?', line)
                if match:
                    data[name] = match.group(1)
                name = None
        else:
            name = None
    return data


def _parse_apksigner_certs(text: str) -> list[str]:
    certs: list[str] = []
    for line in text.splitlines():
        match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", line, re.IGNORECASE)
        if match:
            certs.append(match.group(1).lower())
    return certs


def _validate_commit(commit: str) -> None:
    if not COMMIT_RE.match(commit):
        raise CatalogError(
            f"invalid commit '{commit}': expected a full 40-hex (or 64-hex) lowercase Git commit hash",
        )


def _validate_base_path(value: str) -> None:
    if (
        not value
        or len(value.encode("utf-8")) > MAX_BASE_PATH_BYTES
        or value.startswith("/")
        or value.endswith("/")
        or ".." in value
        or "\\" in value
        or not BASE_PATH_RE.match(value)
    ):
        raise CatalogError(
            f"invalid base path '{value}': expected a canonical repository-relative path "
            "without '..', URI components, backslashes, or encoded traversal",
        )


def _positive_int(value: Any, description: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CatalogError(f"{description} must be a positive integer")
    return value


def _load_and_pin_plan(plan_path: Path) -> dict[str, Any]:
    if not plan_path.is_file():
        raise CatalogError(f"Plan file not found: {plan_path}")
    try:
        with open(plan_path, "r", encoding="utf-8") as handle:
            plan = json.load(handle)
    except json.JSONDecodeError as error:
        raise CatalogError(f"plan JSON parse error: {error}")
    if not isinstance(plan, dict) or set(plan.keys()) != PLAN_TOP_KEYS:
        raise CatalogError("plan must contain exactly the schema keys: protocol, schemaVersion, repository, extensions")
    if plan["protocol"] != "daimei-novel-distribution":
        raise CatalogError("plan protocol must be 'daimei-novel-distribution'")
    if plan["schemaVersion"] != 2:
        raise CatalogError("plan schemaVersion must be 2")
    if not isinstance(plan["repository"], str) or not plan["repository"]:
        raise CatalogError("plan repository must be a non-empty string")
    extensions = plan["extensions"]
    if not isinstance(extensions, list) or len(extensions) != EXPECTED_ENTRIES:
        raise CatalogError(f"plan must contain exactly {EXPECTED_ENTRIES} extensions")
    seen: set[str] = set()
    source_ids: set[str] = set()
    for extension in extensions:
        if not isinstance(extension, dict):
            raise CatalogError("every plan entry must be an object")
        if set(extension.keys()) != PLAN_ENTRY_KEYS:
            raise CatalogError(
                "plan entry must contain exactly the schema keys: "
                + ", ".join(sorted(PLAN_ENTRY_KEYS)),
            )
        package_name = extension["packageName"]
        if not isinstance(package_name, str) or package_name not in OFFICIAL_PACKAGES:
            raise CatalogError(f"plan package {package_name!r} is not an official Beta 1 extension")
        if package_name in seen:
            raise CatalogError(f"plan must contain exactly the three official packages; {package_name!r} is duplicated")
        seen.add(package_name)
        pinned = OFFICIAL_PACKAGES[package_name]
        _positive_int(extension["longVersionCode"], f"plan longVersionCode for {package_name}")
        if not isinstance(extension["versionName"], str) or not extension["versionName"]:
            raise CatalogError(f"plan versionName for {package_name} must be a non-empty string")
        if extension["novelApi"] != pinned["novelApi"]:
            raise CatalogError(f"plan novelApi for {package_name} must be '{pinned['novelApi']}'")
        if extension["entryClasses"] != pinned["entryClasses"]:
            raise CatalogError(f"plan entryClasses for {package_name} must be {pinned['entryClasses']}")
        _positive_int(extension["minSdk"], f"plan minSdk for {package_name}")
        abis = extension["abis"]
        if abis is not None and (
            not isinstance(abis, list) or not abis or not all(isinstance(a, str) and a for a in abis)
        ):
            raise CatalogError(f"plan abis for {package_name} must be null or a non-empty list of strings")
        if extension["sourceIds"] != pinned["sourceIds"]:
            raise CatalogError(f"plan sourceIds for {package_name} must be {pinned['sourceIds']}")
        for source_id in pinned["sourceIds"]:
            if source_id in source_ids:
                raise CatalogError(f"plan sourceIds must be distinct across extensions: {source_id!r}")
            source_ids.add(source_id)
        if extension["currentSigners"] != [EXPECTED_EXTENSIONS_CERT]:
            raise CatalogError(
                "plan currentSigners must be exactly the official extensions certificate "
                f"{EXPECTED_EXTENSIONS_CERT}",
            )
        if extension["artifact"] is not None:
            raise CatalogError(
                f"plan artifact for {package_name} must be null; the finalizer creates it from real APKs",
            )
    if seen != set(OFFICIAL_PACKAGES):
        raise CatalogError(
            "plan must contain exactly the three official packages: "
            + ", ".join(sorted(OFFICIAL_PACKAGES)),
        )
    return plan


def _parse_apk_mappings(mappings: list[str]) -> dict[str, Path]:
    apk_map: dict[str, Path] = {}
    basenames: dict[str, str] = {}
    for mapping in mappings:
        if "=" not in mapping:
            raise CatalogError(f"APK mapping must be PACKAGE=PATH: {mapping}")
        package_name, path_text = mapping.split("=", 1)
        if package_name not in OFFICIAL_PACKAGES:
            raise CatalogError(
                f"APK mapping package {package_name!r} is not an official-package; expected one of: "
                + ", ".join(sorted(OFFICIAL_PACKAGES)),
            )
        if package_name in apk_map:
            raise CatalogError(f"duplicate package mapping {package_name!r}")
        path = Path(path_text)
        basename = path.name
        if not APK_BASENAME_RE.match(basename):
            raise CatalogError(f"APK basename {basename!r} must match [A-Za-z0-9][A-Za-z0-9._-]*.apk")
        if basename in basenames:
            raise CatalogError(f"duplicate APK basename {basename!r}")
        basenames[basename] = package_name
        apk_map[package_name] = path
    if set(apk_map) != set(OFFICIAL_PACKAGES):
        missing = sorted(set(OFFICIAL_PACKAGES) - set(apk_map))
        raise CatalogError(
            "APK mappings must cover exactly the official-packages; missing: " + ", ".join(missing),
        )
    return apk_map


def _inspect_apk(apk_path: Path, aapt2: Path, apksigner: Path) -> dict[str, Any]:
    if not apk_path.is_file():
        raise CatalogError(f"APK not found: {apk_path}")
    badging = _run([str(aapt2), "dump", "badging", str(apk_path)])
    if badging.returncode != 0:
        raise CatalogError(f"aapt2 badging failed for {apk_path}: {badging.stderr.strip()}")
    manifest = _run([str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk_path)])
    if manifest.returncode != 0:
        raise CatalogError(f"aapt2 manifest failed for {apk_path}: {manifest.stderr.strip()}")
    verify = _run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk_path)])
    if verify.returncode != 0:
        raise CatalogError(f"apksigner verification failed for {apk_path}: APK is unsigned or signature is invalid")
    info = _parse_badging(badging.stdout)
    meta = _parse_manifest(manifest.stdout)
    certs = _parse_apksigner_certs(verify.stdout)
    if not certs:
        raise CatalogError(f"No certificate found for {apk_path}")
    return {
        "info": info,
        "meta": meta,
        "certs": certs,
        "sha256": _sha256(apk_path),
        "size": apk_path.stat().st_size,
    }


def _build_url(commit: str, base_path: str, filename: str) -> str:
    return f"{RAW_HOST}/{OFFICIAL_REPO}/{commit}/{base_path}/{filename}"


def _validate_extension(extension: dict[str, Any], observed: dict[str, Any], apk_path: Path) -> None:
    package_name = extension["packageName"]
    info = observed["info"]
    meta = observed["meta"]
    if info.get("packageName") != package_name:
        raise CatalogError(f"APK {apk_path} package {info.get('packageName')} does not match plan {package_name}")
    if info.get("versionCode") != extension["longVersionCode"]:
        raise CatalogError(
            f"APK {apk_path} versionCode {info.get('versionCode')} does not match plan {extension['longVersionCode']}",
        )
    if info.get("versionName") != extension["versionName"]:
        raise CatalogError(
            f"APK {apk_path} versionName {info.get('versionName')} does not match plan {extension['versionName']}",
        )
    if info.get("minSdk") != extension["minSdk"]:
        raise CatalogError(f"APK {apk_path} minSdk {info.get('minSdk')} does not match plan {extension['minSdk']}")
    observed_api = meta.get("app.novel.extensionApi")
    if observed_api != extension["novelApi"]:
        raise CatalogError(f"APK {apk_path} novelApi {observed_api} does not match plan {extension['novelApi']}")
    entry_class = meta.get("app.novel.extension.class")
    if not entry_class:
        raise CatalogError(f"APK {apk_path} missing app.novel.extension.class metadata")
    normalized_entry = entry_class
    if normalized_entry.startswith("."):
        normalized_entry = package_name + normalized_entry
    if extension["entryClasses"] != [normalized_entry]:
        raise CatalogError(
            f"APK {apk_path} entryClass {normalized_entry} does not match plan {extension['entryClasses']}",
        )
    if "app.novel.extension" not in info.get("features", []):
        raise CatalogError(f"APK {apk_path} does not declare app.novel.extension feature")
    if "tachiyomi.extension" in info.get("features", []):
        raise CatalogError(f"APK {apk_path} declares forbidden tachiyomi.extension feature")
    expected_signers = set(extension["currentSigners"])
    if set(observed["certs"]) != expected_signers:
        raise CatalogError(f"APK {apk_path} certificate set {observed['certs']} does not match plan {expected_signers}")


def finalize(plan: dict[str, Any], apk_map: dict[str, Path], commit: str, base_path: str) -> dict[str, Any]:
    observations: dict[str, dict[str, Any]] = {}
    for extension in plan["extensions"]:
        package_name = extension["packageName"]
        observed = _inspect_apk(apk_map[package_name], apk_map["aapt2"], apk_map["apksigner"])
        _validate_extension(extension, observed, apk_map[package_name])
        observations[package_name] = observed

    extensions: list[dict[str, Any]] = []
    for extension in plan["extensions"]:
        package_name = extension["packageName"]
        observed = observations[package_name]
        apk_path = apk_map[package_name]
        entry: dict[str, Any] = {
            "packageName": package_name,
            "longVersionCode": extension["longVersionCode"],
            "versionName": extension["versionName"],
            "novelApi": extension["novelApi"],
            "entryClasses": list(extension["entryClasses"]),
            "minSdk": extension["minSdk"],
            "artifact": {
                "url": _build_url(commit, base_path, apk_path.name),
                "size": observed["size"],
                "sha256": observed["sha256"],
            },
            "currentSigners": list(extension["currentSigners"]),
            "sourceIds": list(extension["sourceIds"]),
        }
        # The real Kotlin codec rejects an explicit null for optional fields,
        # so a null/absent plan 'abis' is omitted rather than spread.
        if extension["abis"]:
            entry["abis"] = list(extension["abis"])
        extensions.append(entry)

    return {
        "protocol": plan["protocol"],
        "schemaVersion": plan["schemaVersion"],
        "repository": plan["repository"],
        "extensions": extensions,
    }


def _write_catalog_atomic(out: Path, catalog: dict[str, Any]) -> None:
    if out.exists():
        raise CatalogError(f"refusing to overwrite existing catalog: {out}")
    out.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(dir=out.parent, prefix=".catalog-", suffix=".tmp")
    temp_path = Path(temp_name)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(catalog, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if out.exists():
            raise CatalogError(f"refusing to overwrite existing catalog: {out}")
        # os.link fails atomically if the destination appeared meanwhile.
        os.link(temp_path, out)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise
    temp_path.unlink(missing_ok=True)


def run(plan_path: Path, commit: str, apk_args: list[str], out: Path, aapt2: Path, apksigner: Path, base_path: str) -> dict[str, Any]:
    plan = _load_and_pin_plan(plan_path)
    apk_map = _parse_apk_mappings(apk_args)
    _validate_commit(commit)
    _validate_base_path(base_path)
    if out.exists():
        raise CatalogError(f"refusing to overwrite existing catalog: {out}")
    apk_map["aapt2"] = aapt2
    apk_map["apksigner"] = apksigner
    return finalize(plan, apk_map, commit, base_path)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Finalize the official S39 catalog from the noninstallable plan and three real signed "
            "APKs. Rejects invalid arguments before any SDK call, never overwrites an existing "
            "catalog, and never invents a URL, hash, or commit."
        ),
    )
    parser.add_argument("--plan", type=Path, required=True, help="Noninstallable plan JSON with artifact: null")
    parser.add_argument(
        "--commit",
        required=True,
        help="Full 40-hex (or 64-hex) lowercase Git commit hash for the immutable Raw HTTPS path",
    )
    parser.add_argument(
        "--apk",
        action="append",
        required=True,
        dest="apk_args",
        help="PACKAGE=PATH for each of the exactly three signed official APKs",
    )
    parser.add_argument("--out", type=Path, required=True, help="Output catalog path (must not already exist)")
    parser.add_argument(
        "--base-path",
        default=DEFAULT_BASE_PATH,
        help=f"Canonical repository-relative artifact path (default: {DEFAULT_BASE_PATH})",
    )
    parser.add_argument("--aapt2", type=Path, default=Path("aapt2"), help="Path to aapt2")
    parser.add_argument("--apksigner", type=Path, default=Path("apksigner"), help="Path to apksigner")
    args = parser.parse_args()

    if len(args.apk_args) != EXPECTED_ENTRIES:
        print(f"error: exactly {EXPECTED_ENTRIES} --apk arguments are required", file=sys.stderr)
        return 1

    try:
        catalog = run(args.plan, args.commit, args.apk_args, args.out, args.aapt2, args.apksigner, args.base_path)
        _write_catalog_atomic(args.out, catalog)
    except CatalogError as error:
        print(f"error: {error.message}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
