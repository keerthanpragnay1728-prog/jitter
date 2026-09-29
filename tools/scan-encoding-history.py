#!/usr/bin/env python3
"""Forensic scan for encoding damage across git history.

    python3 tools/scan-encoding-history.py HEAD
    python3 tools/scan-encoding-history.py $(git rev-list HEAD)

Companion to tools/check-encoding.sh. That one guards the working tree and
runs on every commit; this one answers "when did this get damaged, and what
else did the same sync path touch". It reads blobs out of git rather than the
working tree, so a file already repaired locally still shows its history.

Written after LauncherActivity.kt arrived holding two raw 0x95 bytes, which
meant the file in git could never have compiled and was therefore not the file
that built on the device. Bounding the blast radius of whatever re-encoded it
needed a scan of every blob in every commit, not just the tip.

Exits non-zero if anything is found.
"""
import re
import signal
import subprocess
import sys

# Behave like a normal unix filter when piped into head or less.
try:
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
except (AttributeError, ValueError):
    pass

# CP1252 round-trip signatures. When UTF-8 text is decoded as CP1252 or
# Latin-1 and re-encoded as UTF-8, these byte sequences are what you get.
# Written as code points so this file stays pure ASCII.
def _seq(*points):
    """Build a byte-sequence signature from code points.

    Written this way so this file's own bytes stay pure ASCII. Spelling the
    sequences literally would make the scanner match itself, which is exactly
    what happened to tools/check-encoding.sh at b2086c3.
    """
    return "".join(chr(p) for p in points)


# CP1252 round-trip signatures. When UTF-8 text is decoded as CP1252 or
# Latin-1 and re-encoded as UTF-8, these are what you get.
MOJIBAKE = [
    (_seq(0xE2, 0x20AC, 0x2122), "right single quote via CP1252"),
    (_seq(0xE2, 0x20AC, 0x153), "left double quote via CP1252"),
    (_seq(0xE2, 0x20AC, 0x9D), "right double quote via CP1252"),
    (_seq(0xE2, 0x20AC, 0x201C), "en dash via CP1252"),
    (_seq(0xE2, 0x20AC, 0x201D), "em dash via CP1252"),
    (_seq(0xE2, 0x20AC, 0xA2), "bullet via CP1252"),
    (_seq(0xC2, 0xA0), "non-breaking space via CP1252"),
    (_seq(0xC3, 0xA9), "e-acute via CP1252"),
    (_seq(0xC3, 0xA8), "e-grave via CP1252"),
    (_seq(0xEF, 0xBB, 0xBF), "BOM decoded as Latin-1"),
]

REPLACEMENT = _seq(0xFFFD)
SKIP = (".png", ".jpg", ".jpeg", ".webp", ".ttf", ".otf", ".jar", ".keystore",
        ".zip", ".so", ".ico", ".pb", ".bin", ".gz")


# Binary by path, not by extension: raw Android resources hold audio. The
# same single directory tools/check-encoding.sh allows, and ScanAllowanceTest
# pins both.
RAW_BINARY_DIR = "app/src/main/res/raw/"


def scan(data):
    out = []
    try:
        text = data.decode("utf-8")
        valid = True
    except UnicodeDecodeError as e:
        valid = False
        out.append(("INVALID_UTF8", "%s at byte %d" % (e.reason, e.start)))
        text = data.decode("utf-8", "replace")

    if not valid:
        raw = [(i, b) for i, b in enumerate(data) if 0x80 <= b <= 0x9F]
        for i, b in raw[:6]:
            ctx = data[max(0, i - 30):i + 15]
            out.append(("CP1252_RAW_BYTE", "0x%02x at %d: %r" % (b, i, ctx)))

    for m in re.finditer(re.escape(REPLACEMENT), text):
        out.append(("U_FFFD", repr(text[max(0, m.start() - 30):m.start() + 15])))

    for seq, label in MOJIBAKE:
        for m in re.finditer(re.escape(seq), text):
            ctx = text[max(0, m.start() - 30):m.start() + 20]
            out.append(("MOJIBAKE", "%s: %r" % (label, ctx)))

    if data[:3] == b"\xef\xbb\xbf":
        out.append(("BOM", "file starts with a UTF-8 BOM"))
    return out


def files_in(ref):
    r = subprocess.run(["git", "ls-tree", "-r", "--name-only", ref],
                       capture_output=True, text=True, check=True)
    return [f for f in r.stdout.splitlines() if f]


def blob(ref, path):
    r = subprocess.run(["git", "show", "%s:%s" % (ref, path)],
                       capture_output=True)
    return r.stdout if r.returncode == 0 else None


def main():
    total_hits = 0
    for ref in sys.argv[1:]:
        print("\n===== %s =====" % ref)
        hits = 0
        for path in files_in(ref):
            if path.lower().endswith(SKIP) or path.startswith(RAW_BINARY_DIR):
                continue
            data = blob(ref, path)
            if data is None:
                continue
            found = scan(data)
            if found:
                hits += 1
                total_hits += 1
                print("\n  %s" % path)
                for kind, detail in found[:8]:
                    print("    [%s] %s" % (kind, detail))
                if len(found) > 8:
                    print("    ... and %d more" % (len(found) - 8))
        if hits == 0:
            print("  clean")
    return 1 if total_hits else 0


if __name__ == "__main__":
    sys.exit(main())
