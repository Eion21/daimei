package eu.kanade.tachiyomi.extension.util

/**
 * The loader's decision, before it builds a classloader or instantiates any third-party class.
 *
 * The order is the contract and must not change:
 *
 * 1. an unsupported contract version is refused first — an apk built against an interface this app cannot run is
 *    not worth any further work;
 * 2. a missing signature is refused next, and a present-but-untrusted one after it. These two are the boundary
 *    between running third-party code and not running it, so nothing past them — in particular the novel branch —
 *    may be reached without passing through them.
 *
 * The novel case supplies its feature and its version key earlier, but it flows through this same gate: there is no
 * value of [ExtensionKind] that lets a package reach [ALLOWED] without `hasSignatures && isTrusted`.
 */
internal enum class ExtensionGateDecision { UNSUPPORTED_VERSION, UNSIGNED, UNTRUSTED, FILTERED, ALLOWED }

internal fun decideExtensionGate(
    supportedVersion: Boolean,
    hasSignatures: Boolean,
    isTrusted: Boolean,
    contentWarningAllowed: Boolean,
): ExtensionGateDecision = when {
    !supportedVersion -> ExtensionGateDecision.UNSUPPORTED_VERSION
    !hasSignatures -> ExtensionGateDecision.UNSIGNED
    !isTrusted -> ExtensionGateDecision.UNTRUSTED
    !contentWarningAllowed -> ExtensionGateDecision.FILTERED
    else -> ExtensionGateDecision.ALLOWED
}
