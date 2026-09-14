#!/bin/sh
# Fails on source-encoding damage: the class of fault a compiler reports late,
# confusingly, or not at all.
#
# Every check here corresponds to something that actually reached this
# repository, in one commit, 3f1303a:
#
#   invalid UTF-8   LauncherActivity.kt held two raw 0x95 bytes, a CP1252
#                   bullet, where a face glyph should have been. kotlinc reads
#                   sources as UTF-8, so the file could never have compiled.
#                   That is the important one: it proves the file in git was
#                   not the file that built on the device, so something in the
#                   sync path re-encoded it.
#
#   BOM in XML      A UTF-8 BOM was added to accessibility_service_config.xml
#                   by a commit whose message described fixing an XML parse
#                   error. A BOM before an XML declaration is itself an aapt2
#                   failure mode on some versions.
#
#   U+FFFD          What those raw bytes become once anything re-encodes them.
#                   By then the original text is gone and unrecoverable.
#
#   mojibake        The other half of a CP1252 round trip. Still valid UTF-8,
#                   so iconv passes it, but the text is wrong. Catching this
#                   needs a pattern, not a decoder.
#
# Scope is every file under app/src and tools, not just res/ XML, because the
# file that was actually damaged was Kotlin.
#
# This script is kept pure ASCII. The byte sequences it searches for are built
# with printf octal escapes rather than written literally, so the checker does
# not match itself. An earlier version did exactly that.
set -eu
cd "$(dirname "$0")/.."

status=0

# Binary types that are not expected to decode as text.
is_binary() {
    case "$1" in
        *.png|*.jpg|*.jpeg|*.webp|*.gif|*.ttf|*.otf|*.jar|*.keystore) return 0 ;;
        *.zip|*.so|*.ico|*.pb|*.bin|*.gz|*.class) return 0 ;;
        *) return 1 ;;
    esac
}

# Tracked files only, via git ls-files rather than find. Two reasons: it
# respects .gitignore, so Gradle's build and cache directories under
# tools/pure-verify are not scanned, and it checks exactly the set that
# actually reaches another machine, which is the set the sync path can damage.
text_files() {
    git ls-files -- app/src tools | sort | while read -r f; do
        [ -f "$f" ] || continue
        is_binary "$f" || printf '%s\n' "$f"
    done
}

# U+FFFD and the CP1252 round-trip signatures, as byte sequences.
FFFD=$(printf '\357\277\275')
# "a-circumflex, euro" is the prefix of every CP1252-mangled punctuation mark
# (right quote, left quote, en dash, em dash, bullet).
MOJI_PUNCT=$(printf '\303\242\342\202\254')
# "A-circumflex" then a continuation lead: a mangled non-breaking space.
MOJI_NBSP=$(printf '\303\202\302')
# "A-tilde" then a continuation lead: a mangled accented Latin letter.
MOJI_ACCENT=$(printf '\303\203\302')

# ------------------------------------------------------------- invalid UTF-8
# The check that would have caught the bug. A stray high byte is a build
# failure or, worse, a silently mangled string literal.
for f in $(text_files); do
    if ! iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1; then
        echo "Not valid UTF-8: $f"
        od -An -c -j 0 "$f" 2>/dev/null | grep -n '\\[0-9][0-9][0-9]' | head -2 || true
        status=1
    fi
done

# ---------------------------------------------------------------- BOM in XML
# Only a resource XML can actually stop the build, but a BOM anywhere is a
# sign the file went through an editor that does not default to UTF-8.
for f in $(text_files); do
    if [ "$(od -An -tx1 -N3 "$f" | tr -d ' \n')" = "efbbbf" ]; then
        echo "BOM: $f"
        status=1
    fi
done

# --------------------------------------------------------- U+FFFD, mojibake
for f in $(text_files); do
    if grep -qF "$FFFD" "$f" 2>/dev/null; then
        echo "U+FFFD replacement character: $f"
        echo "  Something was decoded with the wrong codec and the original"
        echo "  bytes are gone. Recover the text from a good copy."
        status=1
    fi
    for pattern in "$MOJI_PUNCT" "$MOJI_NBSP" "$MOJI_ACCENT"; do
        if grep -qF "$pattern" "$f" 2>/dev/null; then
            echo "CP1252 round-trip mojibake: $f"
            echo "  Valid UTF-8, wrong text. The file was decoded as CP1252 or"
            echo "  Latin-1 somewhere and re-encoded."
            status=1
            break
        fi
    done
done

if [ "$status" -ne 0 ]; then
    echo
    echo "Encoding check failed."
    exit 1
fi

echo "All files under app/src and tools are valid UTF-8, no BOM, no mojibake."
