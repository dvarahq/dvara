#!/usr/bin/env bash
#
# Cut a release of this repository: commit the released version, tag that commit, and open the next
# development version.
#
# A tag publishes: release.yml builds the tagged commit, releases it on Maven Central, pushes the
# image and creates the GitHub Release. A version on Central can never be replaced, so this prints
# its whole plan and changes nothing unless --execute is given, and every check that can refuse
# runs before anything is committed or pushed.
#
#   scripts/cut-release.sh 1.8.4 --rc 2              # plan only
#   scripts/cut-release.sh 1.8.4 --rc 2 --execute    # tag 1.8.4-rc2, main opens 1.8.4-SNAPSHOT
#   scripts/cut-release.sh 1.8.4 --execute           # tag 1.8.4,     main opens 1.8.5-SNAPSHOT
#
# Cut it from main's commit: a checkout of main, or a worktree detached at origin/main.
#
set -uo pipefail

VERSION=""
RC=""
EXECUTE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --rc) RC="${2:-}"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) VERSION="$1"; shift ;;
  esac
done

die()  { echo "refused: $*" >&2; exit 1; }
# In plan mode a failed check is reported and the plan keeps printing; with --execute it refuses.
# The plan is most useful precisely when something is not ready yet.
guard() { if [ "$EXECUTE" = "1" ]; then die "$*"; else echo "   WOULD REFUSE: $*"; fi; }
step() { echo; echo "== $*"; }
run()  {
  if [ "$EXECUTE" = "1" ]; then "$@" || die "failed: $*"; else echo "   would run: $*"; fi
}

[ -n "$VERSION" ] || die "give the version to cut, for example 1.8.4"
echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || die "'$VERSION' is not X.Y.Z"
if [ -n "$RC" ]; then
  echo "$RC" | grep -qE '^[1-9][0-9]*$' || die "--rc takes a positive number, not '$RC'"
fi

TAG="$VERSION"
[ -n "$RC" ] && TAG="$VERSION-rc$RC"
# After a candidate, main keeps working towards the same release. After the release, it moves on to
# the next patch.
if [ -n "$RC" ]; then
  NEXT="$VERSION-SNAPSHOT"
else
  NEXT="$(echo "$VERSION" | awk -F. '{print $1"."$2"."$3+1"-SNAPSHOT"}')"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || die "cannot enter $ROOT"

# Every commit and the tag are made as grabdoc whatever this machine's git configuration says. The
# gate rejects any other pusher, and a release commit by somebody else would be refused after the
# tag had already been pushed.
GIT_NAME="grabdoc"
GIT_EMAIL="grabdoc@users.noreply.github.com"
GIT=(git -c "user.name=$GIT_NAME" -c "user.email=$GIT_EMAIL")

POMS=(pom.xml)
for m in $(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' pom.xml); do POMS+=("$m/pom.xml"); done

echo "cutting $TAG   (main then opens $NEXT)"
[ "$EXECUTE" = "1" ] || echo "PLAN ONLY: nothing is committed, tagged or pushed. Add --execute to act."

# ---------------------------------------------------------------- preflight --
step "Preflight"

command -v gh >/dev/null || die "gh is not installed"
LOGIN="$(gh api user --jq .login 2>/dev/null)"
[ "$LOGIN" = "grabdoc" ] || guard "gh is signed in as '${LOGIN:-nobody}', not grabdoc"
echo "   gh user: ${LOGIN:-none}"

# -c sets the configuration, but GIT_AUTHOR_* and GIT_COMMITTER_* in the environment win over it.
AUTHOR="$("${GIT[@]}" var GIT_AUTHOR_IDENT | sed 's/>.*/>/')"
COMMITTER="$("${GIT[@]}" var GIT_COMMITTER_IDENT | sed 's/>.*/>/')"
[ "$AUTHOR" = "$GIT_NAME <$GIT_EMAIL>" ]    || guard "commits would be authored by '$AUTHOR'; unset GIT_AUTHOR_NAME and GIT_AUTHOR_EMAIL"
[ "$COMMITTER" = "$GIT_NAME <$GIT_EMAIL>" ] || guard "commits would be committed by '$COMMITTER'; unset GIT_COMMITTER_NAME and GIT_COMMITTER_EMAIL"
echo "   git identity: $AUTHOR"

[ -z "$(git status --porcelain)" ] || guard "the working tree is not clean"
git fetch -q origin main --tags || die "could not fetch origin"
HEAD_SHA="$(git rev-parse HEAD)"
MAIN_SHA="$(git rev-parse origin/main)"
if [ "$HEAD_SHA" = "$MAIN_SHA" ]; then
  echo "   at origin/main ${MAIN_SHA:0:9}"
else
  guard "HEAD ${HEAD_SHA:0:9} is not origin/main ${MAIN_SHA:0:9}; cut from main, or a worktree detached at origin/main"
fi

# A tag is never moved. Once pushed, release.yml has published from it, and Central keeps what it
# published; a candidate that needs another try is the next rc.
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  guard "$TAG already exists locally; a tag is never moved, cut the next rc instead"
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  guard "$TAG already exists on origin; a tag is never moved, cut the next rc instead"
fi

CURRENT="$(./mvnw -B -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null | tail -1)"
case "$CURRENT" in
  *-SNAPSHOT) echo "   main is at $CURRENT" ;;
  "")         die "could not read the project version; is JAVA_HOME a JDK 25?" ;;
  *)          guard "main is at $CURRENT, not a snapshot; a release was cut and main never reopened" ;;
esac
if [ "$CURRENT" != "$VERSION-SNAPSHOT" ]; then
  echo "   NOTE: main works towards ${CURRENT%-SNAPSHOT}, and this cuts $TAG"
fi

# ------------------------------------------------------------------ release --
step "Set every pom to $TAG"
run ./mvnw -q versions:set -DnewVersion="$TAG" -DgenerateBackupPoms=false
run scripts/check-version-matches-tag.sh "$TAG"

# The jar names prove the bump took, not the exit code. The build cache is off because it leaves the
# version out of its input hash, and a cache hit would restore the previous version's jars.
step "Build $TAG without tests"
run ./mvnw -B -ntp clean install -DskipTests -Dmaven.build.cache.enabled=false
if [ "$EXECUTE" = "1" ]; then
  jar="dvara-gateway-server/target/dvara-gateway-server-$TAG-app.jar"
  [ -f "$jar" ] || die "$jar was not built; the bump did not take"
  echo "   built $jar"
fi

step "Commit Release $TAG and tag it"
run "${GIT[@]}" add -- "${POMS[@]}"
if [ "$EXECUTE" = "1" ]; then
  staged="$(git diff --cached --name-only)"
  [ -n "$staged" ] || die "the version change staged nothing"
  echo "$staged" | grep -qv 'pom\.xml$' && die "something other than a pom is staged: $staged"
fi
run "${GIT[@]}" commit -q -m "Release $TAG"
run "${GIT[@]}" tag -a "$TAG" -m "$TAG"

# --------------------------------------------------------------------- next ---
step "Open $NEXT"
run ./mvnw -q versions:set -DnewVersion="$NEXT" -DgenerateBackupPoms=false
run scripts/check-version-matches-tag.sh "$NEXT" --no-maven
run "${GIT[@]}" add -- "${POMS[@]}"
run "${GIT[@]}" commit -q -m "Open $NEXT"

# --------------------------------------------------------------------- push ---
# One atomic push carries both commits and the tag: main moves to the Open commit and the tag points
# at the Release commit, or nothing lands at all. release.yml runs from the tag, so it builds the
# Release commit and its version check passes. build.yml runs once, for main's new head, which is a
# snapshot, and publishes it as usual. Two separate pushes would leave main at a release version
# between them, and a failure there would leave it so.
step "Push"
run git push --atomic origin HEAD:main "refs/tags/$TAG"

step "After the push"
echo "   release.yml publishes $TAG to Maven Central, pushes the image and creates the GitHub"
echo "   Release. Watch it with:"
echo "     gh run list --workflow release.yml --branch $TAG --limit 1"
echo
echo "   FOLLOW-UP: the README must name the new release. Until it does,"
echo "   scripts/check-readme-versions.py fails the gate, which checks the README against the newest"
echo "   tag. Update README.md to $TAG in its own change."

echo
if [ "$EXECUTE" = "1" ]; then
  echo "done: $TAG tagged and pushed, main open at $NEXT"
else
  echo "plan complete: add --execute to cut $TAG"
fi
