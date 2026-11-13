# S39 interactive installation stays disabled in production

The NEW-only coordinator, durable journal and public Android transport are connected, but
**S39 cannot call Android PackageInstaller.commit**. Android pre-install signature verification
is still NOT_VERIFIED. Catalog and archive observations never become Stage1 evidence.

## Controlled path

1. Supply an already-quarantined artifact, decoder-owned expectation and explicit installation consent.
2. Reinspect the standalone base APK; require complete current signer observations and installed absence.
3. Insert the exact operation binding, then persist its execution hold before creating a session.
4. Stream and hash the actual bytes; accept only the exact expected size and SHA-256 after fsync/close.
5. Immediately before submission, reinspect the bytes and reread installed absence and session ownership.
6. Correlate callbacks by operation, random nonce and session. Pending user action requires another
   explicit decision; it is never launched by the receiver.
7. After reported success, independently read the real installed package, version and whole current
   signer set. Observe host recognition separately. Both recognized and unrecognized installs remain held.

The production runtime uses the real installed reader, inert inspector, persistent hold store,
file journal and final Android bridge. Its activation getter is permanently false; its submit
method independently always returns DISABLED and contains no call to Session.commit. There is
no preference or feature flag that changes either barrier. JVM tests use a controlled bridge,
not a switch in the production adapter.

## States and failure handling

| Event | Outcome |
| --- | --- |
| Existing shared/private package or unavailable installed state | NOT_NEW; no session |
| Missing installation consent | CONSENT_REQUIRED; no journal/session mutation |
| Missing unknown-source permission | PERMISSION_REQUIRED; held, explicit resume required |
| Changed bytes, version, installed state or current signer set | Typed rejection; no submission |
| Stream failure or invalid receipt | Abandon owned session; preserve hold |
| Foreign or duplicate callback | No new state or execution authority |
| Pending system confirmation | PENDING_USER_ACTION; one explicit launch only |
| Callback success with exact installed identity | INSTALLED_HELD or INSTALLED_UNRECOGNIZED |
| Failure/cancellation | Persist failed/cancelled state; no trust grant |
| Uncertain dispatch or missing process-local request | RECOVERY_REQUIRED; never resubmit automatically |
| Restart | Observe persisted binding and installed identity; never replay consent or restore trust |

Recognition defaults to unknown/false in production. The coordinator does not reload extensions,
instantiate source classes, write TrustExtension/preferences, or call grantExecution. A reported
Android result, installed identity and source recognition are separate observations. The existing
signature/trust gate still intersects the S39 hold before any classloader or cached instance reuse.

## Persistence

NovelFileInstallJournal uses private 0700/0600 files, bounded binary records, an OS lock and atomic
replacement. Updates compare the exact current revision and preserve the immutable artifact/package/
version/current-signer/nonce binding. A session identifier cannot change after binding. The serialized
execution-decision byte must be DENY. Missing, malformed, ambiguous or unavailable storage is an
explicit error; it never authorizes dispatch or execution.

A failure after atomic replacement may leave a newer DENY record on disk even when the write returns
failure. Recovery observes that state but cannot reconstruct a live execution lease. Session creation
and journaling are not an Android transaction: process death in between may leave an orphan session.
It is not adopted, committed or trusted automatically. This availability limitation is explicit.

## Android boundary

The dedicated transport uses only public MODE_FULL_INSTALL sessions, requests USER_ACTION_REQUIRED
on API31+, and retains explicit user decisions for earlier APIs. No Mihon installer preference,
Shizuku, private .ext installation, silent install or automatic execution trust is used.

The manifest declares an explicit non-exported result receiver. Callback decoding validates its
action and URI, but never launches supplied confirmation automatically. Explicit confirmation also
checks fresh package absence and session ownership, and the Android wrapper accepts only a resolved
system activity. There is deliberately no active production PendingIntent/commit path while
Android signature verification remains unproved.

JVM Android calls are mocked. They do not prove Android parsing, Binder delivery, actual installer
UI, ART loading, installability or certificate verification. No phone, emulator or APK installation
is part of this work.

## Verification

Use the task-owned scoped formatter init file and run production/test compilation plus the S38/S39,
loader, journal, coordinator and Android-bridge JVM tests. The cycle evidence retains exact RED,
compiler-preparation failures, GREEN and final independent verification outputs.

Next activation work requires separately authorized Android identity/signature/trust proof; it is
not a flag flip. S38 remains read-only with hostCompatible:false and null install URLs.
