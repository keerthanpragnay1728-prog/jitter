#!/bin/sh
# Fails if an em dash or an en dash appears in repository text.
#
# The two characters are written literally below rather than as code points,
# so this runs on GNU and BSD grep with no -P and no (*UTF). That is also why
# the check lives in a script instead of inline in CLAUDE.md: a markdown file
# containing the characters would trip the check it documents.
#
# *.xml is in scope because display copy lives in res/values/strings.xml. It
# was out of scope once, and an em dash sat in shipped copy unnoticed.
set -eu
cd "$(dirname "$0")/.."

if grep -rn -e '—' -e '–' --include='*.md' --include='*.kt' --include='*.xml' . ; then
    echo
    echo "Found em or en dashes. Use a period, a comma, or parentheses."
    exit 1
fi

echo "No em or en dashes in *.md, *.kt, *.xml."
