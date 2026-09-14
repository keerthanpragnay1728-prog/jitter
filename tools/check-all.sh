#!/bin/sh
# Every static check, in one command. Run before committing.
#
# This is not the build. The Android layer is not compiled here; see
# CLAUDE.md, "Build and verification".
set -eu
cd "$(dirname "$0")/.."

echo "== dashes =="
tools/check-dashes.sh

echo
echo "== encoding =="
tools/check-encoding.sh

echo
echo "== format strings =="
python3 tools/check-format-strings.py

echo
echo "== pure suite =="
(cd tools/pure-verify && ./gradlew test --rerun-tasks -q)
echo "Pure suite green."
