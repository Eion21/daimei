#!/usr/bin/env bash
#
# Packaging proof for a novel extension APK.
#
# The host loads an extension with DelegateLastClassLoaderCompat, which resolves a class from the
# APK's own dex BEFORE falling back to the host. A class the APK *defines* therefore shadows the
# host's copy, while a class it merely *references* is resolved from the host at runtime and is
# harmless. So the metric here is DEFINED classes — the `Class descriptor` entries of the dex
# `class_def` table — never mere mentions, which appear thousands of times in every dump.
#
# Every `classes*.dex` of the APK is read: an extension APK commonly carries several, and one of
# them (the one holding the extension's own code) is not `classes.dex`.
#
# Non-vacuity: an APK that defines none of its own classes would make any count meaningless, so the
# extension's own package is counted too and a zero there is a failure.
#
# Usage:
#   tools/check-extension-apk-packaging.sh [--own-package <path/>] <apk> [<apk> ...]
#
#   --own-package   Descriptor prefix of the extension's own package, without the leading L, with
#                   the trailing slash. Default: app/novel/extension/    (own_package env too)
#
# Environment:
#   ANDROID_HOME or ANDROID_SDK_ROOT   where build-tools/*/dexdump lives
#   DEXDUMP                            full path to dexdump, wins over the SDK lookup
#
# Exit code: 0 only when every APK defines zero host-owned classes and has a non-empty own package.
#            Non-zero on a violation, an unreadable APK, or a missing tool.

set -euo pipefail

HOST_PACKAGES="Lkotlin/ Lkotlinx/ Lapp/novel/source/api/android/ Lapp/novel/source/api/"

usage() {
    sed -n '3,28p' "$0" | sed -e 's/^#//' -e 's/^ //'
}

die() {
    printf 'error: %s\n' "$1" >&2
    exit 2
}

# defined_in <descriptor-prefix> <dump-file> -> how many classes that dex DEFINES with that prefix.
# `grep -c` exits 1 when it counts zero, which `set -e` would treat as a failure: hence `|| true`.
defined_in() {
    local prefix="$1" file="$2" n
    n=$(grep -cE "^[[:space:]]*Class descriptor[[:space:]]*:[[:space:]]*'${prefix}" "$file" || true)
    printf '%s' "${n:-0}"
}

# defined_total <dump-file> -> how many classes that dex defines at all.
defined_total() {
    local file="$1" n
    n=$(grep -cE "^[[:space:]]*Class descriptor[[:space:]]*:" "$file" || true)
    printf '%s' "${n:-0}"
}

own_package="${own_package:-app/novel/extension/}"
apks=()

while [ $# -gt 0 ]; do
    case "$1" in
        --own-package)
            [ $# -ge 2 ] || die "--own-package needs a value"
            own_package="$2"
            shift 2
            ;;
        --own-package=*)
            own_package="${1#*=}"
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        -*)
            die "unknown option: $1 (try --help)"
            ;;
        *)
            apks+=("$1")
            shift
            ;;
    esac
done

[ ${#apks[@]} -gt 0 ] || die "no APK given (try --help)"

command -v unzip >/dev/null 2>&1 || die "unzip is not installed"

if [ -z "${DEXDUMP:-}" ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -n "$sdk" ] || die "no dexdump: set ANDROID_HOME (or ANDROID_SDK_ROOT), or DEXDUMP to its path"
    DEXDUMP=$(ls "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -n 1 || true)
fi
[ -n "${DEXDUMP:-}" ] && [ -x "$DEXDUMP" ] || die "dexdump not found (looked under ANDROID_HOME=${ANDROID_HOME:-unset}); set DEXDUMP"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

printf 'dexdump: %s\n' "$DEXDUMP"
printf 'own package: L%s\n\n' "$own_package"
printf '%-40s %-10s %8s %8s %8s %10s %12s %8s\n' \
    apk dex defined kotlin kotlinx api api-android own

problems=0

for apk in "${apks[@]}"; do
    [ -f "$apk" ] || die "APK not found: $apk"

    name=$(basename "$apk")
    work="$tmp/$name"
    mkdir -p "$work"
    unzip -o -q "$apk" -d "$work" 'classes*.dex' 2>/dev/null || true

    dexes=("$work"/classes*.dex)
    if [ ! -e "${dexes[0]}" ]; then
        printf '  VIOLATION: %s carries no classes*.dex\n' "$name" >&2
        problems=1
        continue
    fi
    # Version order, so classes2 comes before classes10 in the table.
    mapfile -t dexes < <(printf '%s\n' "${dexes[@]}" | sort -V)

    apk_defined=0
    apk_own=0
    apk_host=()

    for dex in "${dexes[@]}"; do
        label=$(basename "$dex" .dex)
        dump="$work/$label.dump"
        "$DEXDUMP" "$dex" >"$dump" 2>&1

        dex_defined=$(defined_total "$dump")
        dex_kotlin=$(defined_in "Lkotlin/" "$dump")
        dex_kotlinx=$(defined_in "Lkotlinx/" "$dump")
        dex_api_all=$(defined_in "Lapp/novel/source/api/" "$dump")
        dex_api_android=$(defined_in "Lapp/novel/source/api/android/" "$dump")
        dex_api=$((dex_api_all - dex_api_android))
        dex_own=$(defined_in "L$own_package" "$dump")

        printf '%-40s %-10s %8s %8s %8s %10s %12s %8s\n' \
            "$name" "$label" "$dex_defined" "$dex_kotlin" "$dex_kotlinx" \
            "$dex_api" "$dex_api_android" "$dex_own"

        apk_defined=$((apk_defined + dex_defined))
        apk_own=$((apk_own + dex_own))
        apk_host+=("$dex_kotlin" "$dex_kotlinx" "$dex_api" "$dex_api_android")

        if [ "$((dex_kotlin + dex_kotlinx + dex_api + dex_api_android))" -gt 0 ]; then
            printf '  VIOLATION: %s defines %s kotlin/, %s kotlinx/, %s api/ (of which %s api/android/)\n' \
                "$label" "$dex_kotlin" "$dex_kotlinx" "$dex_api" "$dex_api_android"
            problems=1
        fi
    done

    kotlin=0
    kotlinx=0
    api=0
    api_android=0
    i=0
    while [ $i -lt ${#apk_host[@]} ]; do
        kotlin=$((kotlin + apk_host[i]))
        kotlinx=$((kotlinx + apk_host[i + 1]))
        api=$((api + apk_host[i + 2]))
        api_android=$((api_android + apk_host[i + 3]))
        i=$((i + 4))
    done

    printf 'SUMMARY %s: defined=%s kotlin=%s kotlinx=%s api=%s api-android=%s own=%s\n' \
        "$name" "$apk_defined" "$kotlin" "$kotlinx" "$api" "$api_android" "$apk_own"

    if [ "$apk_own" -eq 0 ]; then
        printf '  NON-VACUITY FAILURE: %s defines no class from its own package L%s\n' \
            "$name" "$own_package" >&2
        problems=1
    fi

    printf '\n'
done

if [ "$problems" -ne 0 ]; then
    printf 'RESULT: FAIL\n'
    exit 1
fi

printf 'RESULT: PASS\n'
