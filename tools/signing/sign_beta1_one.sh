#!/usr/bin/env bash
# HUMAN-ONLY launcher. Never source it, run it in CI, or pipe/record its prompts.
set +x
set -euo pipefail
usage() {
    printf '%s\n' 'Usage: bash tools/signing/sign_beta1_one.sh WORKSET PROFILE' \
        'Run ONE APK in your real, unrecorded private terminal. No password arguments.' \
        'Profiles: daimei-arm64 daimei-universal matchanovels skynovels jabrascan' \
        'Each profile requires fresh signed/PROFILE and work/PROFILE output directories.'
}
if [[ ${1:-} == --help && $# == 1 ]]; then usage; exit 0; fi
if [[ $# != 2 ]]; then usage >&2; exit 2; fi
if [[ ! -t 0 || ! -t 1 || ! -t 2 ]]; then
    printf '%s\n' 'STOP: private TTY required; no signing command was run.' >&2
    exit 2
fi
if [[ -n ${CI:-} || -n ${GITHUB_ACTIONS:-} ]]; then
    printf '%s\n' 'STOP: CI is not a private signing terminal.' >&2; exit 2
fi
case "$2" in
    daimei-arm64) name=daimei-v0.1.0-beta.1-arm64-v8a.apk; expected=a1cfb22551daf632f61ab83ef364bb3b80011588f08fc02eae3266e00acb3558 ;;
    daimei-universal) name=daimei-v0.1.0-beta.1-universal.apk; expected=9a83d88c07eba00f65e76bcfdf6e1208b0c1c45cf37bf991590092791c6cb6ee ;;
    matchanovels) name=matchanovels-v1.0.2.apk; expected=a82a8dcceb1efb10d4168cf42cb25cab9d1db80fc1bd1a267c242dbad8e64a0c ;;
    skynovels) name=skynovels-v1.0.2.apk; expected=015b019ec62eaacc71870007bb645b9207fb775511e564e60923c4a4a2009a5f ;;
    jabrascan) name=jabrascan-v1.0.3.apk; expected=0a40de5cad674d4ea2e523e85e0bb1912b122a53b01d4a247547e053c4cabf15 ;;
    *) printf '%s\n' 'STOP: unknown profile.' >&2; exit 2 ;;
esac
case "$2" in
    daimei-*) store=/home/jervis/.local/share/daimei-signing/daimei-app-release.p12; alias=daimei-app-release; cert=e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77 ;;
    *) store=/home/jervis/.local/share/daimei-signing/daimei-extensions-release.p12; alias=daimei-extensions-release; cert=2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408 ;;
esac
sdk=/home/jervis/Proyectos/Desarrollo/Android/Sdk/build-tools/36.0.0
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
if [[ -n ${JAVA_HOME:-} ]]; then java="$JAVA_HOME/bin/java"; else java=java; fi
# Public Java-console probe has no password API; failure stops before signer use.
"$java" "$script_dir/RequireSigningConsole.java"
root=$(cd -- "$1" && pwd -P)
input="$root/unsigned/$name"
[[ -f "$input" && ! -L "$input" ]]
actual=$(sha256sum -- "$input")
[[ ${actual%% *} == "$expected" ]] || { printf '%s\n' 'STOP: unsigned input hash mismatch.' >&2; exit 2; }
[[ -d "$root/signed" && ! -L "$root/signed" ]]
umask 077
if [[ ! -e "$root/work" ]]; then mkdir -m 0700 -- "$root/work"; fi
[[ -d "$root/work" && ! -L "$root/work" ]]
# No overwrite or cleanup on partial failure. Do not blindly rerun a profile.
mkdir -m 0700 -- "$root/work/$2"
mkdir -m 0700 -- "$root/signed/$2"
work="$root/work/$2"
output="$root/signed/$2/$name"
cp -- "$input" "$work/input-unsigned.apk"
"$sdk/zipalign" -P 16 4 "$work/input-unsigned.apk" "$work/aligned-unsigned.apk"
"$sdk/zipalign" -c -P 16 4 "$work/aligned-unsigned.apk"
# No password transport: apksigner must prompt on the already-proven console.
"$sdk/apksigner" sign --ks "$store" --ks-type PKCS12 --ks-key-alias "$alias" \
    --out "$output" "$work/aligned-unsigned.apk"
"$sdk/apksigner" verify --verbose --print-certs --min-sdk-version 26 --max-sdk-version 36 "$output"
"$sdk/zipalign" -c -P 16 4 "$output"
printf 'Required singleton certificate SHA-256: %s\n' "$cert"
sha256sum -- "$output"
stat -c '%s' -- "$output"
printf '%s\n' 'Run the read-only five-APK checker before accepting any publication set. Never publish unsigned hashes.'
