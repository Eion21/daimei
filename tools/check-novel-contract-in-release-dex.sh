#!/usr/bin/env bash
#
# Contract proof for the host release dex.
#
# `app/proguard-rules.pro` keeps the novel contract (`app.novel.source.api.**` and
# `app.novel.source.api.android.**`) so that R8 does not strip the interfaces an external novel
# extension binds to. Without those rules the release dex lost `NetworkedNovelSource` and every
# novel extension failed to load with a ClassNotFoundException. This script proves the release dex
# still DEFINES every class the contract modules compile — by NAME, not by count: a count would let
# a class disappear while an unrelated one appeared.
#
# The expected list is derived from the compiled classes of `:novel-api` and `:novel-api-android`,
# never written by hand, so a class added to the contract is checked without editing this file:
#   novel-api          build/classes/*/main/**/*.class
#   novel-api-android  build/intermediates/compile_library_classes_jar/release/*/classes.jar
# Build them first: ./gradlew :novel-api:assemble :novel-api-android:assemble
#
# Exceptions, named with their reason (never silent):
#   Lapp/novel/source/api/ChapterContent$$ExternalSyntheticLambda0;
#   Lapp/novel/source/api/ContentBlock$Divider$$ExternalSyntheticLambda0;
#       Synthesized by D8 while dexing, so they never appear in compiled classes or in a jar. They
#       are listed so that a toolchain which does emit them cannot cause a false failure.
#   BuildConfig and R are deliberately NOT excluded: AGP compiles them, the keep rules retain them,
#   and the release dex defines them — the check holds them to the same standard.
#
# Every `classes*.dex` of the APK is read: the host APK carries several and the contract classes are
# not necessarily in `classes.dex`.
#
# Usage:
#   tools/check-novel-contract-in-release-dex.sh <apk>
#
# Environment:
#   ANDROID_HOME or ANDROID_SDK_ROOT   where build-tools/*/dexdump lives
#   DEXDUMP                            full path to dexdump, wins over the SDK lookup
#
# Exit code: 0 when every expected contract class is defined by the dex.
#            1 on a missing class, or on a vacuous expectation (no class derived).
#            2 on an unreadable APK, a missing tool, or an unbuildable module list.

set -euo pipefail

CONTRACT_PREFIX="app/novel/source/api/"
MODULES="novel-api novel-api-android"

# Expected descriptors that must NOT be required, with the reason. See the header.
EXCLUDED=(
    'Lapp/novel/source/api/ChapterContent$$ExternalSyntheticLambda0;'
    'Lapp/novel/source/api/ContentBlock$Divider$$ExternalSyntheticLambda0;'
)

usage() {
    sed -n '3,41p' "$0" | sed -e 's/^#//' -e 's/^ //'
}

die() {
    printf 'error: %s\n' "$1" >&2
    exit 2
}

# derive_expected -> one dex descriptor per line, sorted, on stdout.
# Reads the compiled classes of the contract modules and keeps only CONTRACT_PREFIX, so the
# comparison is a set difference of exact names.
derive_expected() {
    local module jar
    for module in $MODULES; do
        [ -d "$module" ] || die "module not found: $module (run from the repository root)"
        # Kotlin/JVM output (novel-api). `*/main/*` keeps test variants out.
        find "$module/build/classes" -path '*/main/*' -name '*.class' 2>/dev/null || true
        # AGP library output (novel-api-android).
        jar=$(ls "$module"/build/intermediates/compile_library_classes_jar/release/*/classes.jar 2>/dev/null | sort -V | tail -n 1 || true)
        if [ -n "$jar" ]; then
            unzip -Z1 "$jar" 2>/dev/null | grep -E '\.class$' | sed "s|^|$jar/|" || true
        fi
    done | sed -nE "s|.*(${CONTRACT_PREFIX}[^ ]*)\.class$|L\1;|p" | sort -u
}

# defined_contract <dump-file> -> number of contract classes that dex DEFINES.
# `grep -c` exits 1 when it counts zero, which `set -e` would treat as a failure: hence `|| true`.
defined_contract() {
    local file="$1" n
    n=$(grep -cE "^[[:space:]]*Class descriptor[[:space:]]*:[[:space:]]*'L${CONTRACT_PREFIX}" "$file" || true)
    printf '%s' "${n:-0}"
}

[ $# -eq 1 ] || {
    usage >&2
    exit 2
}
apk="$1"
[ -f "$apk" ] || die "APK not found: $apk"

command -v unzip >/dev/null 2>&1 || die "unzip is not installed"

if [ -z "${DEXDUMP:-}" ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -n "$sdk" ] || die "no dexdump: set ANDROID_HOME (or ANDROID_SDK_ROOT), or DEXDUMP to its path"
    DEXDUMP=$(ls "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -n 1 || true)
fi
[ -n "${DEXDUMP:-}" ] && [ -x "$DEXDUMP" ] || die "dexdump not found (looked under ANDROID_HOME=${ANDROID_HOME:-unset}); set DEXDUMP"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

expected="$tmp/expected.txt"
defined="$tmp/defined.txt"
required="$tmp/required.txt"

derive_expected >"$expected"
for excluded in "${EXCLUDED[@]}"; do
    grep -vFx "$excluded" "$expected" >"$expected.next" || true
    mv "$expected.next" "$expected"
done

if [ ! -s "$expected" ]; then
    printf 'error: derived no contract class from %s\n' "$MODULES" >&2
    printf '       build the modules first: ./gradlew :novel-api:assemble :novel-api-android:assemble\n' >&2
    exit 1
fi

printf 'dexdump: %s\n' "$DEXDUMP"
printf 'apk: %s\n' "$apk"
printf 'expected contract classes (derived): %s\n\n' "$(wc -l <"$expected" | tr -d ' ')"

work="$tmp/apk"
mkdir -p "$work"
unzip -o -q "$apk" -d "$work" 'classes*.dex' 2>/dev/null || true

dexes=("$work"/classes*.dex)
if [ ! -e "${dexes[0]}" ]; then
    die "$(basename "$apk") carries no classes*.dex"
fi
# Version order, so classes2 comes before classes10 in the table.
mapfile -t dexes < <(printf '%s\n' "${dexes[@]}" | sort -V)

printf '%-12s %10s %10s\n' dex defined contract
for dex in "${dexes[@]}"; do
    label=$(basename "$dex" .dex)
    dump="$work/$label.dump"
    "$DEXDUMP" "$dex" >"$dump" 2>&1
    printf '%-12s %10s %10s\n' \
        "$label" \
        "$(grep -cE "^[[:space:]]*Class descriptor[[:space:]]*:" "$dump" || true)" \
        "$(defined_contract "$dump")"
done

# `|| true` for the same reason as in defined_contract: an APK that defines no contract class makes
# grep exit 1, which pipefail would turn into an abort before the diagnostic below can be printed.
grep -hoE "^[[:space:]]*Class descriptor[[:space:]]*:[[:space:]]*'L${CONTRACT_PREFIX}[^']*'" "$work"/*.dump \
    | sed -E "s/.*'(.*)'/\1/" | sort -u >"$defined" || true
printf '\n'

if [ ! -s "$defined" ]; then
    printf 'NON-VACUITY FAILURE: %s defines no class from the contract package L%s\n' \
        "$(basename "$apk")" "$CONTRACT_PREFIX" >&2
    printf 'RESULT: FAIL\n'
    exit 1
fi

comm -23 "$expected" "$defined" >"$required"
if [ -s "$required" ]; then
    printf 'MISSING contract classes in %s:\n' "$(basename "$apk")" >&2
    while IFS= read -r missing; do
        printf "  %s\n" "$missing" >&2
    done <"$required"
    printf '\nA missing contract class means the keep rules in app/proguard-rules.pro no longer\n' >&2
    printf 'cover it: add or fix the rule for its package, then rebuild the release APK.\n' >&2
    printf 'RESULT: FAIL\n'
    exit 1
fi

printf 'contract classes expected=%s defined=%s (all present)\n' \
    "$(wc -l <"$expected" | tr -d ' ')" "$(wc -l <"$defined" | tr -d ' ')"
printf 'RESULT: PASS\n'
