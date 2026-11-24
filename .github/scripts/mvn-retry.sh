#!/usr/bin/env bash
#
# Run Maven, retrying when Maven Central rate-limits the runner (HTTP 429) or an
# artifact download fails transiently. Genuine build/test failures are not retried.
#
# GitHub-hosted runners share IPs that Maven Central occasionally rate-limits. The
# failure surfaces as "Could not transfer artifact ... HTTP Status: 429" while Maven
# resolves build plugins, which otherwise fails the whole job even though nothing in
# the project is broken.
#
# Usage: .github/scripts/mvn-retry.sh <maven arguments...>
#
# Environment:
#   MVN_RETRY_MAX             total attempts (default 4)
#   MVN_RETRY_DELAY_SECONDS   delay between attempts (default 30)
set -uo pipefail

max_attempts=${MVN_RETRY_MAX:-4}
delay=${MVN_RETRY_DELAY_SECONDS:-30}
log=$(mktemp)
trap 'rm -f "$log"' EXIT

attempt=1
while true; do
    mvn "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    if [ "$status" -eq 0 ]; then
        exit 0
    fi
    if [ "$attempt" -ge "$max_attempts" ]; then
        echo "Maven failed after ${attempt} attempt(s); giving up." >&2
        exit "$status"
    fi
    if grep -qE 'HTTP Status: 429|Could not transfer artifact|Could not transfer metadata|Could not resolve dependencies' "$log"; then
        attempt=$((attempt + 1))
        echo "::warning::Maven Central rate-limited or a download failed; retrying (attempt ${attempt}/${max_attempts}) in ${delay}s." >&2
        sleep "$delay"
        continue
    fi
    exit "$status"
done
