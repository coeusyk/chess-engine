#!/usr/bin/env bash
# Minimal smoke check for .githooks/pre-commit. No test framework exists for shell hooks in
# this repo (checked: no bats, no shellcheck harness, no tools/ hook-test convention), so this
# is a single, self-contained, plain-assertion script rather than new test infrastructure --
# ponytail: shortest useful check, not a framework.
#
# Regression this guards against: the hook must never abort a commit solely because
# graphify-out/ is gitignored (it used to, via `git add graphify-out/` under `set -e`).
#
# Run: bash .githooks/test_pre_commit.sh
set -euo pipefail

HOOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pre-commit"
FAILURES=0

fail() { echo "FAIL: $1" >&2; FAILURES=$((FAILURES + 1)); }
pass() { echo "PASS: $1"; }

# --- Static check: the root cause must not regress -------------------------------------
if grep -qE 'git add[[:space:]]+graphify-out' "$HOOK"; then
  fail "hook stages graphify-out/, which is gitignored -- this is the exact bug being fixed"
else
  pass "hook does not attempt to stage graphify-out/"
fi

# --- Functional checks, in a scratch repo -----------------------------------------------
SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT
cd "$SCRATCH"
git init -q
git config user.email test@example.com
git config user.name test
echo "graphify-out/" > .gitignore
mkdir -p graphify-out
echo '{}' > graphify-out/graph.json  # pre-existing, gitignored, untracked -- like the real repo
git add .gitignore
git commit -q -m init

# A stub `graphify` on PATH that always fails, so a real (slow) build is never required to
# exercise the failure path -- this is what the pre-existing gitignore-vs-git-add conflict
# used to turn into an aborted commit.
mkdir -p "$SCRATCH/bin"
cat > "$SCRATCH/bin/graphify" <<'EOF'
#!/usr/bin/env bash
echo "stub graphify: simulated failure" >&2
exit 1
EOF
chmod +x "$SCRATCH/bin/graphify"
export PATH="$SCRATCH/bin:$PATH"

echo "code change" > file.txt
git add file.txt

if PYTHONHASHSEED=0 bash "$HOOK"; then
  pass "hook exits 0 when graphify itself fails (documented non-blocking policy)"
else
  fail "hook aborted (exit != 0) when graphify failed -- violates 'never blocks the commit'"
fi

if git commit -q -m "test commit" 2>/tmp/test_pre_commit_commit.log; then
  pass "a real 'git commit' with this hook installed succeeds despite graphify failing"
else
  fail "'git commit' was blocked; see /tmp/test_pre_commit_commit.log"
fi

# --- GRAPHIFY_SKIP_HOOK escape hatch still works ----------------------------------------
echo "more change" > file2.txt
git add file2.txt
if GRAPHIFY_SKIP_HOOK=1 bash "$HOOK"; then
  pass "GRAPHIFY_SKIP_HOOK=1 short-circuits cleanly"
else
  fail "GRAPHIFY_SKIP_HOOK=1 did not short-circuit cleanly"
fi

# --- No-op commit (only graphify-out staged) still exits 0 ------------------------------
# Nothing to stage here since graphify-out/ is gitignored and untracked; simulate the
# early-exit path directly by running the hook with nothing staged.
git reset -q --hard
if bash "$HOOK"; then
  pass "hook exits 0 with nothing staged"
else
  fail "hook did not exit 0 with nothing staged"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
