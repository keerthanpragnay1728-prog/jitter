#!/bin/sh
# Push the current branch, but only if every check is green.
#
# The only way to push from this repository. See CLAUDE.md, "Pushing".
#
# ## Why a script and not a habit
# A failing suite has been pushed twice (677928f, then 3e5214e), both times
# because the push was chained after the checks rather than gated on them.
# `check-all.sh; git push` pushes whatever the checks said. This does not.
#
# ## What it requires
# 1. A clean working tree, untracked files included. The checks read the
#    working tree and the push sends commits; they are the same thing only
#    when nothing is uncommitted. The pure suite also compiles untracked
#    sources under its include paths, so an untracked file could turn a red
#    commit green.
# 2. check-structure.py exits zero.
# 3. The last line check-all.sh prints is exactly "check-all: PASS". The
#    printed verdict, not an exit code, for the reason check-all.sh gives.
#
# Anything else exits non-zero and pushes nothing.
set -u
cd "$(dirname "$0")/.."

fail() {
    echo "push-if-green: $1. Nothing pushed."
    exit 1
}

branch=$(git rev-parse --abbrev-ref HEAD) || fail "cannot read the branch"
[ "$branch" != "HEAD" ] || fail "detached HEAD"

dirty=$(git status --porcelain --untracked-files=all) || fail "cannot read git status"
[ -z "$dirty" ] || fail "the working tree is not clean, so the checks would not test what is pushed"

echo "== check-structure.py =="
python3 tools/check-structure.py || fail "check-structure.py failed"

echo
echo "== check-all.sh =="
log=$(mktemp) || fail "cannot create a log file"
trap 'rm -f "$log"' EXIT
tools/check-all.sh >"$log" 2>&1
cat "$log"
last=$(tail -n 1 "$log")
[ "$last" = "check-all: PASS" ] || fail "check-all did not end with PASS (last line: $last)"

echo
echo "push-if-green: checks green on $(git rev-parse --short HEAD), pushing $branch"
git push -u origin "$branch"
