#!/usr/bin/env bash
# Serves the parser fixtures so HttpNovelSource has a real server to talk to.
#
# Usage:
#   ./tools/serve-fixtures.sh [port]        # default 8080
#
# Then build and run the app on an Android emulator. 10.0.2.2 is the host machine as seen from
# inside the emulator, so the source is registered against:
#
#   http://10.0.2.2:8080/
#
# which resolves to the addresses the source expects:
#
#   /index.html                     popular listing
#   /search.html?q=<query>          search        (the query string is ignored by this server)
#   /novel/<id>.html                detail        (only torre-de-cristal exists)
#   /chapter/<id>.html              chapter       (only the four torre-de-cristal chapters)
#
# Only La torre de cristal has a detail fixture, so the other two novels in the listing answer 404.
# That is a gap in the fixture set, not in the source: it returns the listing data back unchanged
# and an empty chapter list rather than failing.
#
# A physical device cannot reach 10.0.2.2; use `adb reverse tcp:8080 tcp:8080` and configure
# http://127.0.0.1:8080/ instead.

set -euo pipefail

PORT="${1:-8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

exec python3 -m http.server "$PORT" \
    --bind 0.0.0.0 \
    --directory "$ROOT/novel-http-source/src/test/resources/fixtures"
