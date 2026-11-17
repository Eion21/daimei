"""Core parsers for official S39 catalog preparation; CLI follows in the next unit."""
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
