#!/usr/bin/env python3
"""Fails on a malformed format specifier in strings.xml.

Four strings shipped with specifiers that would have thrown at runtime the
moment anything rendered them:

    launcher_screentime_target_fmt   "%1 : %2 min"
    ledger_total_screentime          "TODAY: %1 hr, %2 min"
    filter_status_active             "FILTER ACTIVE: %1 CLEARED"
    launcher_telemetry_fmt           "... BAT: %3$s%"   (dangling percent)

"%1" is not a format specifier. String.format raises
UnknownFormatConversionException on it, and aapt2 does not catch it because
the string carried formatted="false" or because a bare "%1" reads as an
unknown conversion rather than a syntax error. They were latent only because
the renderer hardcoded its literals instead of reading the resources.

The rules enforced here are the ones CLAUDE.md already states:

  * every conversion is positional, %n$s, never %s and never %d
    (a %d handed a Double throws IllegalFormatConversionException, and
    several of these strings take Double thresholds)
  * a literal percent is written %%
  * formatted="false" and positional arguments are mutually exclusive

Python rather than shell: this needs a real XML parse to see entity-decoded
text, and the specifier grammar is past what grep should be asked to do.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# A conversion is %n$s or %n$d, or the escape %%. Scanned left to right so
# that the second percent of a %% is consumed rather than read as the start
# of a new conversion, which is the bug the first version of this check had.
TOKEN = re.compile(r"%%|%(\d+)\$([a-zA-Z])")


def check(path: Path) -> list[str]:
    problems: list[str] = []
    root = ET.parse(path).getroot()

    for el in list(root.findall("string")) + list(root.findall(".//item")):
        name = el.get("name") or "(item)"
        text = "".join(el.itertext())
        unformatted = el.get("formatted") == "false"

        seen: list[int] = []
        i = 0
        while True:
            j = text.find("%", i)
            if j < 0:
                break
            m = TOKEN.match(text, j)
            if not m:
                snippet = text[max(0, j - 20):j + 10].replace("\n", " ")
                problems.append(
                    f"{name}: malformed specifier at {j!r} ... {snippet!r}"
                )
                i = j + 1
                continue
            if m.group(1) is not None:
                index, conv = int(m.group(1)), m.group(2)
                if conv != "s":
                    problems.append(
                        f"{name}: %{index}${conv} should be %{index}$s. "
                        "A %d handed a Double throws at runtime."
                    )
                seen.append(index)
            i = m.end()

        if unformatted and seen:
            problems.append(
                f"{name}: formatted=\"false\" but carries positional arguments"
            )
        if seen and sorted(set(seen)) != list(range(1, max(seen) + 1)):
            problems.append(
                f"{name}: argument indices {sorted(set(seen))} are not 1..n"
            )

    return problems


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    files = sorted(root.glob("app/src/**/res/values*/strings.xml"))
    if not files:
        print("No strings.xml found.", file=sys.stderr)
        return 1

    failed = False
    total = 0
    for path in files:
        problems = check(path)
        total += len(ET.parse(path).getroot().findall("string"))
        for p in problems:
            print(f"{path.relative_to(root)}: {p}")
            failed = True

    if failed:
        print()
        print("Format string check failed. Every conversion must be %n$s, "
              "and a literal percent must be %%.")
        return 1

    print(f"All {total} strings have well formed positional specifiers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
