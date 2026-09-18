#!/bin/sh
# Every static check, in one command. Run before committing.
#
# This is not the build. The Android layer is not compiled here; see
# CLAUDE.md, "Build and verification", for what green does and does not mean.
#
# ## Why it ends by saying PASS or FAIL
# `set -e` aborts this script on the first failure, which is correct and was
# never the problem. The problem is the caller: pipe this into `tail` or
# `grep` and the exit code you read afterwards belongs to the pipe, not to the
# checks. That has already happened, and a failing suite was committed and
# pushed because the last four lines of output looked calm.
#
# So the last line is now the verdict, and it survives a pipe. A run that ends
# without one has died somewhere in the middle, and the trap says so rather
# than leaving silence to be read as success.
#
# pipefail is set where the shell has it, which covers bash and ksh and is
# harmless under dash. It is a second belt rather than the fix: the printed
# verdict is the thing a human actually reads.
set -eu
# shellcheck disable=SC3040
(set -o pipefail 2>/dev/null) && set -o pipefail || true
cd "$(dirname "$0")/.."

verdict() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo
        echo "check-all: FAIL (exit $status)"
    fi
}
trap verdict EXIT

echo "== dashes =="
tools/check-dashes.sh

echo
echo "== encoding =="
tools/check-encoding.sh

echo
echo "== format strings =="
python3 tools/check-format-strings.py

echo
echo "== colours =="
tools/check-colors.sh

echo
echo "== structure =="
python3 tools/check-structure.py

echo
echo "== pure suite =="
(cd tools/pure-verify && ./gradlew test --rerun-tasks -q)
echo "Pure suite green."

echo
echo "check-all: PASS"
