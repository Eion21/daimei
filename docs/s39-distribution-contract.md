# S39 Stage 1: distribution expectations and policy

This is a **dormant contract**, not an installer. Decoding a catalog confers no trust, proves no artifact identity, and authorizes neither download nor installation. Stage 1 has no production issuer for privileged evidence and does not verify signatures, inspect APKs, contact a network, change trust, or invoke Android installation. S38 schema 1 remains read-only and cannot be promoted to this protocol.

## Wire format

UTF-8 JSON, protocol `daimei-novel-distribution`, integer `schemaVersion` 2. Objects are closed: unknown, missing, or duplicate keys (including escaped equivalents) are rejected. No coercion of strings, numbers or booleans is performed. Nesting is limited to 16 containers. Strings are 1–256 UTF-16 code units with no ISO control characters; the complete input is at most 2,000,000 bytes. The caller owns the input stream.

```json
{
  "protocol": "daimei-novel-distribution",
  "schemaVersion": 2,
  "repository": "example",
  "extensions": [{
    "packageName": "app.novel.extension.matchanovels",
    "longVersionCode": 2,
    "versionName": "2",
    "novelApi": "1.1",
    "entryClasses": ["app.novel.extension.matchanovels.Source"],
    "minSdk": 26,
    "artifact": {
      "url": "https://example.invalid/matcha.apk",
      "size": 100,
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "currentSigners": ["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
    "abis": ["arm64-v8a"],
    "sourceIds": ["matchanovels"]
  }]
}
```

`repository` is an unverified identifier, not ownership. `extensions` has 1–1,000 entries with unique package names. Package and class names use dot-separated ASCII Java identifiers. `longVersionCode`, `minSdk`, and artifact `size` are positive JSON integers; the first is at most `Long.MAX_VALUE`, `minSdk` at most `Int.MAX_VALUE`, and size at most 134,217,728 bytes. `novelApi` is a string of decimal digits, a dot, and decimal digits; policy separately checks host support. `entryClasses` contains 1–16 unique class strings. `currentSigners` contains 1–16 unique certificate SHA-256 digests. Optional `abis` and `sourceIds`, if present, contain 1–32 unique strings; neither is evidence of actual APK/device/source properties. A digest is exactly 64 lowercase ASCII hexadecimal characters, encoding the 32 digest bytes in order with two characters per byte; uppercase and separators are invalid. APK URLs are syntactically HTTPS, with a host, no userinfo, no fragment, and a valid port; this does not establish a safe destination, enrolled origin, or trustworthy publisher.

Decode failures are `SIZE` (byte ceiling), `JSON` (invalid UTF-8, malformed JSON, duplicate keys or nesting), and `SCHEMA` (closed shape, type, range or content). No input is mutated. Decoder I/O exceptions propagate to the caller. The catalog and entry expose sealed read-only interfaces; the codec owns their private implementations and ordinarily constructs them only after validating the complete protocol and schema. Policy also revalidates entry fields and rejects other implementations or malformed reflectively forged entries. This construction boundary is not a cryptographic proof and is not intended to resist privileged reflection. These protocol declarations are **only expectations**.

## Evidence and decisions

Candidate evidence has independently represented observed package, code/name, API, entry classes, min SDK, artifact length/hash, complete current signer set, signer history, rotation status, device SDK/ABI, APK ABI/features, and separately attested source identities. Installed state has origin, freshness, package, version, complete current signer set, history, and source identities. Trust is a separate package/version/full-current-signer grant. Classes are non-data, private-constructor, defensively snapshotted and have no production issuer in this stage. Tests use reflective synthetic fixtures only. Future issuers must bind every observation to the exact immutable artifact and state and preserve these restrictions; there is currently no evidence that such verification exists.

`NEW` and `UPDATE` are explicit operations, never inferred from a collision. Both require matched expected and observed metadata, bytes and complete current signers, supported API, a separately observed standalone APK layout, compatible device/ABI and novel-only feature, explicit exact trust, and a current installed snapshot. When present, declared ABI and source sets must exactly equal separately observed candidate sets; missing observations reject rather than being inferred from declarations. Absent optional declarations do not themselves establish any observation. `NEW` additionally requires a coherent absence snapshot; `UPDATE` requires a shared installed package, strictly higher version, complete exact current-signer equality, no declared/unknown rotation, and known matching separately attested source IDs. History is never substituted for current signers; history presence alone is not rotation. Trust and catalog fingerprints do not establish signature validity.

| Reason | Refusal |
|---|---|
| `INCOMPLETE` | Required evidence, signer history or rotation status is unknown, or absence contradicts installed fields |
| `EXPECTATION_MISMATCH` | Expectation is not decoder-owned or fails field validation, or observed artifact/metadata/optional declarations differ |
| `PACKAGE` | Candidate, expectation and target package disagree |
| `SIGNATURE` | Current signer evidence absent, incomplete, malformed or unlike expected full set |
| `API` | Candidate API outside the pinned supported set |
| `COMPATIBILITY` | Standalone layout, SDK, native ABI or novel-only feature compatibility not established |
| `COLLISION` | Operation and installed origin disagree, including private/unknown origin |
| `TRUST` | Exact candidate package/version/current-signer grant absent |
| `VERSION` | Installed version unknown/nonpositive or candidate not strictly newer |
| `SIGNER_CONTINUITY` | Installed complete current set absent, malformed or unequal |
| `SOURCE_CONTINUITY` | Separately attested identities missing, changed or inconsistent with reviewed map |
| `STALE_STATE` | Snapshot is stale or freshness unknown |
| `ROTATION` | Candidate declares/proves rotation |

The policy pins APIs `1.0`, `1.1`, `1.2`, `1.3`, checked by a host-helper regression test. Source map: `app.novel.extension.matchanovels` → `matchanovels`; `app.novel.extension.skynovels` → `skynovels`; `app.novel.extension.jabrascan` → `jabrascan`. `NovelMapper.generateSourceId` delegates to `NovelSourceId.generate`: MD5 of the UTF-8 string `novel/<infoId>`, first eight digest bytes interpreted big-endian, with the sign bit cleared. This existing numeric mapping is not changed or migrated. Neither manifest declarations nor catalog strings prove runtime source identity. Even a policy-eligible update needs trusted-load and real-APK source continuity acceptance later.

## Later prerequisites

A separately authorized Stage 2 must select and validate a real platform-compatible APK verifier, verify signatures and complete current signer sets over the same immutable bytes as the digest/metadata observations, and establish provenance for device and source facts. Later work must re-read Android package state immediately before any operation, handle races and interactive system consent, and prove trusted-load recognition. Origin enrollment, destination restrictions, secure acquisition and any trust persistence require separately authorized designs. No Stage 1 eligibility decision is a download, trust mutation, install command, or guarantee that Android will accept a package.
