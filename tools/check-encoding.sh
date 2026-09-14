#!/bin/sh
# Fails on source-encoding faults that a compiler reports late, confusingly,
# or not at all.
#
# Every check here corresponds to a bug that actually reached this repository:
#
#   BOM in res/ XML   A UTF-8 BOM was added to accessibility_service_config.xml
#                     by a commit whose message described fixing an XML parse
#                     error. A BOM before an XML declaration is itself an aapt2
#                     failure mode on some versions, so the fix plausibly
#                     introduced the fault it was describing.
#
#   U+FFFD            LauncherActivity.kt carried two raw 0xFFFD bytes where a
#                     face glyph should have been. See the invalid-UTF-8 check
#                     below for the related, worse case.
#
#   invalid UTF-8     The same line actually held two raw 0x95 bytes, a CP1252
#                     bullet, so the file was not valid UTF-8 at all. kotlinc
#                     reads sources as UTF-8; a stray high byte is a build
#                     failure or, worse, a silently mangled string literal.
#
# Kept separate from check-dashes.sh because that script greps for two literal
# characters and must stay portable across GNU and BSD grep. This one needs
# byte-level inspection, so it uses od, which is in POSIX.
set -eu
cd "$(dirname "$0")/.."

status=0

# ---------------------------------------------------------------- BOM in XML
# Only res/ is checked. A BOM elsewhere is untidy; in a resource XML it can
# stop the build.
for f in $(find app/src -name '*.xml' -type f | sort); do
    first3=$(od -An -tx1 -N3 "$f" | tr -d ' \n')
    if [ "$first3" = "efbbbf" ]; then
        echo "BOM: $f"
        status=1
    fi
done

# ----------------------------------------------------------- U+FFFD anywhere
# The replacement character means text was decoded with the wrong codec and
# the original bytes are gone. It is never intentional.
if grep -rln '�' --include='*.kt' --include='*.xml' --include='*.md' \
        --include='*.proto' --include='*.kts' . 2>/dev/null; then
    echo "Found U+FFFD replacement characters in the files above."
    echo "Something was decoded with the wrong codec. Recover the original text."
    status=1
fi

# ------------------------------------------------------------- invalid UTF-8
# Catches the raw high bytes that U+FFFD only appears in place of once
# something has already re-encoded them.
for f in $(find app/src tools -name '*.kt' -o -name '*.xml' -o -name '*.kts' 2>/dev/null | sort); do
    [ -f "$f" ] || continue
    if ! iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1; then
        echo "Not valid UTF-8: $f"
        status=1
    fi
done

if [ "$status" -ne 0 ]; then
    echo
    echo "Encoding check failed."
    exit 1
fi

echo "No BOMs in res XML, no U+FFFD, all sources are valid UTF-8."
