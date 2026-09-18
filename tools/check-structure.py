#!/usr/bin/env python3
"""Catch a Kotlin declaration that has escaped its enclosing function.

## The bug this exists for

`TerminalHomeView` acquired one extra `}` in the middle of itself. The
function closed three hundred lines early and the rest of it became top level
declarations. The file did not compile, and every check in `check-all.sh`
passed anyway, across seven commits.

Two things let that happen.

The first is that the file is balanced. A brace in the wrong place and a brace
that is missing are different defects, and only the second one shows up in a
total. Counting `{` against `}` reports zero for both a healthy file and this
one, which is why the ad hoc count that was being run by hand never had a
chance.

The second is that nothing compiles these files. `tools/pure-verify` builds
`core`, `engine`, part of `sensing`, `legacy` and `debug`. Everything else in
`app/src/main` is compiled only by the Android toolchain, which is unreachable
from the usual session environment. For those files "check-all green" has only
ever meant "no grep found anything", and this script is the smallest thing
that makes it mean slightly more.

## What it checks, and what it does not

It tracks brace depth with string literals, character literals, raw strings,
backtick identifiers and both comment forms handled, and reports any indented
line that begins a statement while the depth is zero. That is exactly the
shape a function closing early produces, and it is the shape a balance count
cannot see.

It is not a parser and it is not a compiler. It will not catch a type error, a
missing import, an unresolved reference or a brace misplaced in a way that
leaves the following lines un-indented. It catches one class of fault, which
is one more than was being caught before.

## Scope

The set is derived from `tools/pure-verify/build.gradle.kts` rather than
written down here: every `.kt` under `app/src/main/java` that the pure build
does not compile. Deriving it means a file moving into or out of the pure set
cannot leave this list stale, and it means the scope is exactly the gap rather
than an approximation of it.
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/main/java")
GRADLE = os.path.join(ROOT, "tools/pure-verify/build.gradle.kts")

# A line starting with one of these, while brace depth is zero and the line is
# indented, is a statement that has escaped. Deliberately not exhaustive: this
# is a tripwire, and a wider net on a heuristic buys false positives rather
# than coverage.
STARTERS = (
    "val ", "var ", "fun ", "if (", "if(", "when ", "when(", "when {",
    "for (", "while (", "return", "item {", "item(", "items(",
)

# A previous line ending in one of these means the next line is a continuation
# rather than a new statement. `=` is the one that matters: an expression body
# split across lines sits at depth zero and is indented, and is correct.
CONTINUES = ("=", "->", "&&", "||", "+", "-", ",", "(", ".", ":", "?:")


def pure_prefixes():
    with open(GRADLE, encoding="utf-8") as f:
        text = f.read()
    block = re.search(r"val pureMain = listOf\((.*?)\n\)", text, re.S)
    if block is None:
        sys.exit("check-structure: could not read pureMain from the pure-verify build")
    return [m for m in re.findall(r'"([^"]+)"', block.group(1))]


def uncompiled(prefixes):
    out = []
    for folder, _, names in os.walk(MAIN):
        for name in names:
            if not name.endswith(".kt"):
                continue
            rel = os.path.relpath(os.path.join(folder, name), MAIN)
            covered = any(
                rel == p if p.endswith(".kt") else rel.startswith(p + "/")
                for p in prefixes
            )
            if not covered:
                out.append(rel)
    return sorted(out)


def escaped(path):
    """Lines that begin a statement while nothing is open. Plus the depth."""
    with open(path, encoding="utf-8") as f:
        src = f.read()

    i, n = 0, len(src)
    depth = paren = 0
    line = 1
    at_line_start = True
    previous = ""
    current = ""
    found = []

    while i < n:
        c = src[i]

        if c == "\n":
            stripped = current.strip()
            if stripped and not stripped.startswith(("*", "//", "/*")):
                previous = stripped
            current = ""
            line += 1
            i += 1
            at_line_start = True
            continue

        if at_line_start:
            j = i
            while j < n and src[j] in " \t":
                j += 1
            end = src.find("\n", j)
            rest = src[j:end if end >= 0 else n]
            indented = j > i
            if depth == 0 and paren == 0 and indented and rest.startswith(STARTERS):
                if not previous.endswith(CONTINUES):
                    found.append((line, rest[:70]))
            at_line_start = False

        if src.startswith("//", i):
            j = src.find("\n", i)
            current += src[i:j if j >= 0 else n]
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            line += src.count("\n", i, j)
            i = j
            continue
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if c == "`":
            j = src.find("`", i + 1)
            i = n if j < 0 else j + 1
            continue
        if c == "'":
            j = i + 1
            if j < n and src[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and src[j] == "'":
                i = j + 1
                continue
            i += 1
            continue

        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        elif c == "(":
            paren += 1
        elif c == ")":
            paren -= 1
        current += c
        i += 1

    return depth, found


def main():
    files = uncompiled(pure_prefixes())
    if not files:
        sys.exit("check-structure: found no uncompiled files; the derivation has rotted")

    failures = 0
    for rel in files:
        path = os.path.join(MAIN, rel)
        depth, found = escaped(path)
        if depth != 0:
            print(f"{rel}: braces do not balance (ends at depth {depth})")
            failures += 1
        for line, text in found[:5]:
            print(f"{rel}:{line}: statement at top level, so a function closed early")
            print(f"    {text}")
            failures += 1

    if failures:
        print()
        print(f"check-structure: {failures} problem(s).")
        print("Nothing else in check-all.sh compiles these files, so this is")
        print("the only thing standing between a syntax error and a commit.")
        return 1

    print(f"No escaped declarations in the {len(files)} files nothing else compiles.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
