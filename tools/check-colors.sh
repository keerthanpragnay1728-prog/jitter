#!/bin/sh
# Fails on an inline colour literal anywhere under ui/, outside the one file
# allowed to define them.
#
# The launcher arrived with six private colour constants and two one-off
# Color(0xFF...) literals buried in a modifier chain. That is not a style
# problem. A palette that lives in a dozen places cannot be changed, and the
# one rule this app's palette actually has, that #FF5555 appears only at the
# terminal tier, is unenforceable the moment a hex literal can appear
# anywhere.
#
# The single permitted definition site is ui/theme/Color.kt. Everything else
# names a token.
#
# Also catches Color.Red and friends: the named constants in
# androidx.compose.ui.graphics.Color are inline literals wearing a different
# spelling, and Color.Red in particular would defeat the terminal-tier rule
# while passing a hex-only grep.
set -eu
cd "$(dirname "$0")/.."

ALLOWED="app/src/main/java/dev/molasses/ui/theme/Color.kt"
status=0

files=$(git ls-files -- 'app/src/*/java/dev/molasses/ui/*.kt' | grep -v "^$ALLOWED$" || true)

for f in $files; do
    # Color(0xFF...) and any other hex literal handed to Color(...).
    if grep -n 'Color(0[xX]' "$f"; then
        echo "  ^ inline hex colour in $f"
        status=1
    fi
    # The named constants. Color.Unspecified and Color.Transparent are fine:
    # neither is a colour choice, they are "no colour" and "see through".
    if grep -nE 'Color\.(Red|Green|Blue|Yellow|Magenta|Cyan|White|Black|Gray|LightGray|DarkGray)\b' "$f"; then
        echo "  ^ named Color constant in $f"
        status=1
    fi
done

if [ "$status" -ne 0 ]; then
    echo
    echo "Inline colours are not allowed under ui/."
    echo "Define the colour in $ALLOWED and name the token instead."
    echo "Remember that TerminalAlert is reserved for the terminal tier."
    exit 1
fi

echo "No inline colour literals under ui/ outside Color.kt."
