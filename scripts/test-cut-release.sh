#!/usr/bin/env bash
#
# Runs scripts/cut-release.sh against a scratch copy of this repository. Nothing reaches GitHub.
#
#   scripts/test-cut-release.sh
#
# The copy's origin is a bare repository whose pre-receive hook refuses every push to main, as the
# main-branch rules do. A stand-in `gh` on the PATH opens, checks and squash-merges pull requests
# by writing to that bare repository directly. Each case builds the copy with the real Maven
# wrapper (package, never install), so JAVA_HOME must be a JDK 25. It takes a few minutes.
#
set -uo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cut-release-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

pass() { echo "  ok   $*"; }
bad()  { echo "  FAIL $*"; FAILED=1; }
check() { if eval "$2"; then pass "$1"; else bad "$1"; fi; }

# ------------------------------------------------------------- the stand-in gh --
mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
# Pull requests live in $STUB_DIR; the "remote" is the bare repository $STUB_ORIGIN.
set -u
G=(git --git-dir="$STUB_ORIGIN" -c user.name=grabdoc -c user.email=grabdoc@users.noreply.github.com)
arg() { local want="$1"; shift; while [ $# -gt 0 ]; do [ "$1" = "$want" ] && { echo "${2:-}"; return; }; shift; done; }
echo "gh $*" >> "$STUB_DIR/calls"
case "$1 ${2:-}" in
  "api user") echo grabdoc ;;
  "pr create")
    n=$(( $(cat "$STUB_DIR/next" 2>/dev/null || echo 100) + 1 )); echo "$n" > "$STUB_DIR/next"
    arg --head "$@" > "$STUB_DIR/pr-$n.head"
    echo "https://github.com/example/example/pull/$n" ;;
  "pr checks")
    n="$3"; c=$(( $(cat "$STUB_DIR/pr-$n.polls" 2>/dev/null || echo 0) + 1 )); echo "$c" > "$STUB_DIR/pr-$n.polls"
    # Not started, then running, then finished: an empty list must never read as passed.
    case "$c" in
      1) ;;
      2) printf 'build pending\ngate pass\n' ;;
      *) if [ "${STUB_CHECKS:-pass}" = "fail" ]; then printf 'build fail\ngate pass\n'
         else printf 'build pass\ngate pass\nsnapshot skipping\n'; fi ;;
    esac
    exit 0 ;;
  "pr merge")
    n="$3"; head="$(cat "$STUB_DIR/pr-$n.head")"
    main="$("${G[@]}" rev-parse refs/heads/main)"
    tree="$("${G[@]}" rev-parse "refs/heads/$head^{tree}")"
    sha="$("${G[@]}" commit-tree "$tree" -p "$main" -m "$(arg --subject "$@")")"
    "${G[@]}" update-ref refs/heads/main "$sha" "$main"
    echo "$sha" > "$STUB_DIR/pr-$n.merged"
    if [ "${STUB_MOVE_MAIN:-0}" = "1" ]; then
      other="$("${G[@]}" commit-tree "$tree" -p "$sha" -m "Someone else's change")"
      "${G[@]}" update-ref refs/heads/main "$other" "$sha"
    fi ;;
  "pr view")
    n="$3"
    if [ -f "$STUB_DIR/pr-$n.merged" ]; then
      case "$(arg --json "$@")" in state) echo MERGED ;; mergeCommit) cat "$STUB_DIR/pr-$n.merged" ;; esac
    else
      case "$(arg --json "$@")" in state) echo OPEN ;; mergeCommit) echo "" ;; esac
    fi ;;
  *) echo "stand-in gh: unexpected: $*" >&2; exit 1 ;;
esac
STUB
chmod +x "$WORK/bin/gh"

# ------------------------------------------------------------------ fixtures --
# The tracked files as they are in this checkout, including uncommitted edits, as one commit.
git -C "$SRC" ls-files -z | (cd "$SRC" && tar --null -cf - -T -) > "$WORK/tree.tar"
# The project's own version: the first <version> after the parent block.
ownver() { awk '/<\/parent>/ { p = 1; next } p && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }'; }
BASE_VERSION="$(ownver < "$SRC/pom.xml")"
case "$BASE_VERSION" in *-SNAPSHOT) ;; *) echo "the root pom is at $BASE_VERSION, not a snapshot"; exit 2 ;; esac
X="${BASE_VERSION%-SNAPSHOT}"

# fresh <name>: a bare origin whose main refuses pushes, and a clone of it at main.
fresh() {
  local d="$WORK/$1"
  mkdir -p "$d/seed" "$d/stub"
  tar -xf "$WORK/tree.tar" -C "$d/seed"
  git -C "$d/seed" init -q -b main
  git -C "$d/seed" add -A
  git -C "$d/seed" -c user.name=grabdoc -c user.email=grabdoc@users.noreply.github.com commit -q -m "base"
  # The README names a release, so the release before this one exists as a tag.
  git -C "$d/seed" -c user.name=grabdoc -c user.email=grabdoc@users.noreply.github.com tag -a 0.0.1 -m 0.0.1
  git clone -q --bare "$d/seed" "$d/origin.git"
  cat > "$d/origin.git/hooks/pre-receive" <<'HOOK'
#!/usr/bin/env bash
while read -r old new ref; do
  if [ "$ref" = "refs/heads/main" ]; then
    echo "push declined due to repository rule violations: main takes changes only through pull requests" >&2
    exit 1
  fi
done
HOOK
  chmod +x "$d/origin.git/hooks/pre-receive"
  git clone -q "$d/origin.git" "$d/cut"
  echo "$d"
}

# cut <dir> <args...>: runs the script in the clone with the stand-in gh.
cut() {
  local d="$1"; shift
  (cd "$d/cut" && PATH="$WORK/bin:$PATH" STUB_DIR="$d/stub" STUB_ORIGIN="$d/origin.git" \
     CUT_RELEASE_POLL=0 scripts/cut-release.sh "$@") > "$d/out.log" 2>&1
}
unset GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL
refs() { git --git-dir="$1/origin.git" for-each-ref --format='%(refname) %(objectname)'; }
pomver() { git --git-dir="$1/origin.git" show "$2:pom.xml" | ownver; }

# ----------------------------------------------------------------------- cases --
echo "case: plan mode changes nothing"
d="$(fresh plan)"; before="$(refs "$d")"; head_before="$(git -C "$d/cut" rev-parse HEAD)"
cut "$d" "$X" --rc 1; rc=$?
check "plan exits 0" '[ "$rc" = 0 ]'
check "plan names the pull requests" 'grep -q "open \"Release $X-rc1\"" "$d/out.log" && grep -q "release/$X-rc1-next" "$d/out.log"'
check "plan pushes, commits and tags nothing" '[ "$(refs "$d")" = "$before" ] && [ "$(git -C "$d/cut" rev-parse HEAD)" = "$head_before" ] && [ -z "$(git -C "$d/cut" status --porcelain)" ] && ! grep -q "^gh pr " "$d/stub/calls"'

echo "case: a release candidate goes through two pull requests"
d="$(fresh rc)"
cut "$d" "$X" --rc 1 --execute; rc=$?
[ "$rc" = 0 ] || sed 's/^/     /' "$d/out.log" | tail -30
check "exits 0" '[ "$rc" = 0 ]'
tag_commit="$(git --git-dir="$d/origin.git" rev-parse "refs/tags/$X-rc1^{commit}" 2>/dev/null)"
check "the tag is on the first merge commit" '[ -n "$tag_commit" ] && [ "$tag_commit" = "$(cat "$d/stub/pr-101.merged")" ]'
check "the tag is annotated by grabdoc" '[ "$(git --git-dir="$d/origin.git" cat-file -t "refs/tags/$X-rc1")" = tag ] && git --git-dir="$d/origin.git" cat-file tag "refs/tags/$X-rc1" | grep -q "^tagger grabdoc <grabdoc@users.noreply.github.com>"'
check "the poms at the tag say $X-rc1" '[ "$(pomver "$d" "refs/tags/$X-rc1")" = "$X-rc1" ]'
check "main is the second merge commit, on top of the tagged one" '[ "$(git --git-dir="$d/origin.git" rev-parse main)" = "$(cat "$d/stub/pr-102.merged")" ] && [ "$(git --git-dir="$d/origin.git" rev-parse main^)" = "$tag_commit" ]'
check "main is at $X-SNAPSHOT" '[ "$(pomver "$d" main)" = "$X-SNAPSHOT" ]'
check "the README on main names $X-rc1" 'git --git-dir="$d/origin.git" show main:README.md | grep -q "<version>$X-rc1</version>"'
check "the README passes its check once the tag exists" '(cd "$d/cut" && git fetch -q origin --tags && git checkout -q --detach origin/main && python3 scripts/check-readme-versions.py >/dev/null)'
check "the release branches are deleted" '! git --git-dir="$d/origin.git" show-ref --verify -q "refs/heads/release/$X-rc1" && ! git --git-dir="$d/origin.git" show-ref --verify -q "refs/heads/release/$X-rc1-next"'
check "it waited through the empty and pending check lists" '[ "$(cat "$d/stub/pr-101.polls")" -ge 3 ]'

echo "case: an existing tag refuses before any push"
d="$(fresh exists)"
git -C "$d/seed" -c user.name=grabdoc -c user.email=grabdoc@users.noreply.github.com tag -a "$X-rc1" -m x
git -C "$d/seed" push -q "$d/origin.git" "refs/tags/$X-rc1"
before="$(refs "$d")"
cut "$d" "$X" --rc 1 --execute; rc=$?
check "exits non-zero" '[ "$rc" != 0 ]'
check "says the tag exists" 'grep -q "already exists" "$d/out.log"'
check "nothing reached origin" '[ "$(refs "$d")" = "$before" ]'

echo "case: a failed check stops before the merge"
d="$(fresh failing)"
STUB_CHECKS=fail cut "$d" "$X" --rc 1 --execute; rc=$?
check "exits non-zero" '[ "$rc" != 0 ]'
check "main is untouched and nothing is tagged" '[ "$(pomver "$d" main)" = "$BASE_VERSION" ] && ! git --git-dir="$d/origin.git" show-ref -q --tags "$X-rc1"'
check "says what is left open" 'grep -q "is not ready to merge; nothing is tagged" "$d/out.log"'

echo "case: main moves between the merge and the tag"
d="$(fresh moved)"
STUB_MOVE_MAIN=1 cut "$d" "$X" --rc 1 --execute; rc=$?
check "exits non-zero" '[ "$rc" != 0 ]'
check "nothing is tagged" '! git --git-dir="$d/origin.git" show-ref -q --tags "$X-rc1"'
check "says main moved" 'grep -q "main moved" "$d/out.log"'
check "no second pull request" '[ ! -f "$d/stub/pr-102.head" ]'

echo "case: the script never pushes to main"
check "no push to main was attempted in any case" '! grep -rq "push declined" "$WORK"/*/out.log'

echo
if [ "$FAILED" = "0" ]; then echo "all cases passed"; else echo "some cases FAILED"; fi
exit "$FAILED"
