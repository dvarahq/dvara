#!/usr/bin/env bash
#
# Cut a release of this repository through two pull requests: one that sets the released version,
# whose merge commit is tagged, and one that names the release in the README and opens the next
# development version. Main accepts changes only through pull requests with passing checks, so
# nothing here pushes to main.
#
# A tag publishes: release.yml builds the tagged commit, releases it on Maven Central, pushes the
# image and creates the GitHub Release. A version on Central can never be replaced, so this prints
# its whole plan and changes nothing unless --execute is given, and every check that can refuse
# runs before anything is pushed.
#
#   scripts/cut-release.sh 1.8.5 --rc 1              # plan only
#   scripts/cut-release.sh 1.8.5 --rc 1 --execute    # tag 1.8.5-rc1, main opens 1.8.5-SNAPSHOT
#   scripts/cut-release.sh 1.8.5 --execute           # tag 1.8.5,     main opens 1.8.6-SNAPSHOT
#
# Cut it from main's commit: a checkout of main, or a worktree detached at origin/main. The script
# works on a detached HEAD and leaves the checkout detached at main's new head.
#
# CUT_RELEASE_CHECK_TIMEOUT (seconds, default 3600) bounds the wait for a pull request's checks;
# CUT_RELEASE_POLL (seconds, default 30) is how often they are read.
#
set -uo pipefail

VERSION=""
RC=""
EXECUTE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --rc) RC="${2:-}"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) VERSION="$1"; shift ;;
  esac
done

die()  { echo "refused: $*" >&2; exit 1; }
# After the first push a failure is not a refusal: something is already on the remote. The message
# says what was left behind.
fail() { echo "stopped: $*" >&2; exit 1; }
# In plan mode a failed check is reported and the plan keeps printing; with --execute it refuses.
# The plan is most useful precisely when something is not ready yet.
guard() { if [ "$EXECUTE" = "1" ]; then die "$*"; else echo "   WOULD REFUSE: $*"; fi; }
step() { echo; echo "== $*"; }
run()  {
  if [ "$EXECUTE" = "1" ]; then "$@" || die "failed: $*"; else echo "   would run: $*"; fi
}
# The same, for a command after the first push.
act()  {
  if [ "$EXECUTE" = "1" ]; then "$@" || fail "failed: $*"; else echo "   would run: $*"; fi
}

[ -n "$VERSION" ] || die "give the version to cut, for example 1.8.5"
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

REPO="dvarahq/dvara"
RELEASE_BRANCH="release/$TAG"
NEXT_BRANCH="release/$TAG-next"
RELEASE_TITLE="Release $TAG"
NEXT_TITLE="The README names $TAG, and main opens $NEXT"
# The checks the main-branch rules require. A pull request is merged only when both have passed
# and no other check has failed.
REQUIRED_CHECKS=(build gate)
CHECK_TIMEOUT="${CUT_RELEASE_CHECK_TIMEOUT:-3600}"
POLL="${CUT_RELEASE_POLL:-30}"

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
[ "$EXECUTE" = "1" ] || echo "PLAN ONLY: nothing is committed, pushed, merged or tagged. Add --execute to act."

# ---------------------------------------------------------------- helpers ----

# Opens a pull request from a pushed branch and prints its number.
open_pr() { # <branch> <title> <body>
  local url
  url="$(gh pr create -R "$REPO" --base main --head "$1" --title "$2" --body "$3")" || return 1
  echo "${url##*/}"
}

# Waits until every required check on the pull request has passed. Refuses on any failed or
# cancelled check, and when the wait runs out. A check that has not started yet is not listed at
# all, so an empty or partial list means "wait", never "pass".
wait_for_checks() { # <pr>
  local pr="$1" deadline out name missing
  deadline=$(( $(date +%s) + CHECK_TIMEOUT ))
  echo "   waiting for the checks on #$pr (${REQUIRED_CHECKS[*]})"
  while :; do
    out="$(gh pr checks "$pr" -R "$REPO" --json name,bucket --jq '.[] | "\(.name) \(.bucket)"' 2>/dev/null)"
    if echo "$out" | awk '$NF == "fail" || $NF == "cancel"' | grep -q .; then
      echo "$out" | sed 's/^/     /'
      return 1
    fi
    missing=""
    for name in "${REQUIRED_CHECKS[@]}"; do
      echo "$out" | grep -qx "$name pass" || missing="$missing $name"
    done
    if [ -z "$missing" ] && ! echo "$out" | awk '$NF == "pending"' | grep -q .; then
      echo "   checks passed on #$pr"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "   still waiting for:$missing after ${CHECK_TIMEOUT}s"
      return 1
    fi
    sleep "$POLL"
  done
}

# Squash-merges the pull request and prints the merge commit once GitHub reports it.
merge_pr() { # <pr> <subject>
  local pr="$1" state oid tries=0
  gh pr merge "$pr" -R "$REPO" --squash --subject "$2 (#$pr)" --body "" >/dev/null || return 1
  while [ "$tries" -lt 30 ]; do
    state="$(gh pr view "$pr" -R "$REPO" --json state --jq .state 2>/dev/null)"
    oid="$(gh pr view "$pr" -R "$REPO" --json mergeCommit --jq '.mergeCommit.oid // ""' 2>/dev/null)"
    if [ "$state" = "MERGED" ] && [ -n "$oid" ]; then echo "$oid"; return 0; fi
    tries=$((tries + 1))
    sleep "$POLL"
  done
  return 1
}

# README lines that name a concrete release, rewritten to name $TAG. An image's `latest` and its
# major.minor stream are left alone, as check-readme-versions.py accepts both.
rewrite_readme() {
  TAG="$TAG" perl -pi -e '
    s{(dvara-gateway-oss:)\d+\.\d+\.\d+[A-Za-z0-9.-]*}{$1$ENV{TAG}}g;
    s{<version>\d[^<]*</version>}{<version>$ENV{TAG}</version>}g;
    s{(com\.dvarahq:[A-Za-z0-9.-]+:)\d[A-Za-z0-9.-]*}{$1$ENV{TAG}}g;
  ' README.md
}

# ---------------------------------------------------------------- preflight --
step "Preflight"

command -v gh >/dev/null || die "gh is not installed"
command -v perl >/dev/null || die "perl is not installed"
command -v python3 >/dev/null || die "python3 is not installed"
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

# A branch left by an earlier attempt would be pushed over, or its open pull request reused.
for b in "$RELEASE_BRANCH" "$NEXT_BRANCH"; do
  if git ls-remote --exit-code --heads origin "refs/heads/$b" >/dev/null 2>&1; then
    guard "the branch $b already exists on origin; close its pull request and delete it first"
  fi
done

CURRENT="$(./mvnw -B -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null | tail -1)"
case "$CURRENT" in
  *-SNAPSHOT) echo "   main is at $CURRENT" ;;
  "")         die "could not read the project version; is JAVA_HOME a JDK 25?" ;;
  *)          guard "main is at $CURRENT, not a snapshot; a release was cut and main never reopened" ;;
esac
if [ "$CURRENT" != "$VERSION-SNAPSHOT" ]; then
  echo "   NOTE: main works towards ${CURRENT%-SNAPSHOT}, and this cuts $TAG"
fi

# Both commits are made here, before anything is pushed, so that every check on their content can
# still refuse. They are made on a detached HEAD: no local branch is created or moved.
run git checkout -q --detach "$MAIN_SHA"

# ------------------------------------------------------ prepare the release --
step "Set every pom to $TAG"
run ./mvnw -q versions:set -DnewVersion="$TAG" -DgenerateBackupPoms=false
run scripts/check-version-matches-tag.sh "$TAG"

# The jar names prove the bump took, not the exit code. The build cache is off because it leaves the
# version out of its input hash, and a cache hit would restore the previous version's jars. It
# packages rather than installs, so the local repository never holds a version that was not
# released.
step "Build $TAG without tests"
run ./mvnw -B -ntp clean package -DskipTests -Dmaven.build.cache.enabled=false
if [ "$EXECUTE" = "1" ]; then
  jar="dvara-gateway-server/target/dvara-gateway-server-$TAG-app.jar"
  [ -f "$jar" ] || die "$jar was not built; the bump did not take"
  echo "   built $jar"
fi

step "Commit $RELEASE_TITLE"
run "${GIT[@]}" add -- "${POMS[@]}"
if [ "$EXECUTE" = "1" ]; then
  staged="$(git diff --cached --name-only)"
  [ -n "$staged" ] || die "the version change staged nothing"
  echo "$staged" | grep -qv 'pom\.xml$' && die "something other than a pom is staged: $staged"
fi
run "${GIT[@]}" commit -q -m "$RELEASE_TITLE"
# In plan mode nothing is committed; the placeholder keeps the printed steps readable.
RELEASE_COMMIT="<the Release commit>"
[ "$EXECUTE" = "1" ] && RELEASE_COMMIT="$(git rev-parse HEAD)"

# ----------------------------------------------------- prepare the next one --
step "Commit: $NEXT_TITLE"
run ./mvnw -q versions:set -DnewVersion="$NEXT" -DgenerateBackupPoms=false
run scripts/check-version-matches-tag.sh "$NEXT" --no-maven
run rewrite_readme
# The README is checked against the release it will name, before that release is tagged.
run python3 scripts/check-readme-versions.py --release "$TAG"
run "${GIT[@]}" add -- README.md "${POMS[@]}"
if [ "$EXECUTE" = "1" ]; then
  staged="$(git diff --cached --name-only)"
  echo "$staged" | grep -qvE '(^|/)pom\.xml$|^README\.md$' && die "something other than the poms and README is staged: $staged"
fi
run "${GIT[@]}" commit -q -m "$NEXT_TITLE"
NEXT_COMMIT="<the README and snapshot commit>"
[ "$EXECUTE" = "1" ] && NEXT_COMMIT="$(git rev-parse HEAD)"

# Everything that can refuse has run. From here on a failure leaves something on the remote, and
# the message says what.

# ------------------------------------------------------------ release PR -----
step "Pull request: $RELEASE_TITLE"
act git push -q origin "$RELEASE_COMMIT:refs/heads/$RELEASE_BRANCH"
RELEASE_BODY="Sets the root pom and every module pom to $TAG. Once merged, the merge commit is tagged
\`$TAG\`, so the poms at the tag carry the tag's version; \`release.yml\` checks that before it builds
or publishes anything.

The next pull request names $TAG in the README and opens $NEXT."
RELEASE_PR=""
if [ "$EXECUTE" = "1" ]; then
  RELEASE_PR="$(open_pr "$RELEASE_BRANCH" "$RELEASE_TITLE" "$RELEASE_BODY")" \
    || fail "could not open the pull request; the branch $RELEASE_BRANCH is on origin, delete it before retrying"
  echo "   opened #$RELEASE_PR"
  wait_for_checks "$RELEASE_PR" \
    || fail "#$RELEASE_PR is not ready to merge; nothing is tagged. Fix it and merge it by hand, or close it and delete $RELEASE_BRANCH"
else
  echo "   would push $RELEASE_BRANCH, open \"$RELEASE_TITLE\", and wait for ${REQUIRED_CHECKS[*]}"
fi

step "Squash-merge $RELEASE_TITLE"
MERGE_SHA=""
if [ "$EXECUTE" = "1" ]; then
  MERGE_SHA="$(merge_pr "$RELEASE_PR" "$RELEASE_TITLE")" \
    || fail "#$RELEASE_PR did not merge; nothing is tagged"
  echo "   merged as ${MERGE_SHA:0:9}"
  git push -q origin --delete "$RELEASE_BRANCH" 2>/dev/null || echo "   (the branch $RELEASE_BRANCH was already deleted)"
else
  echo "   would squash-merge it and delete $RELEASE_BRANCH"
fi

# ------------------------------------------------------------------- tag -----
# The tag goes on the merge commit, and only when main is still at it and it carries the version.
# If main moved in between, the merge commit may no longer be what a user checks out as the
# release, and a tag is never moved afterwards, so it stops here and a human decides.
step "Tag the merge commit $TAG"
if [ "$EXECUTE" = "1" ]; then
  git fetch -q origin main --tags || fail "could not fetch origin; #$RELEASE_PR is merged, nothing is tagged"
  NOW_MAIN="$(git rev-parse origin/main)"
  [ "$NOW_MAIN" = "$MERGE_SHA" ] \
    || fail "main moved to ${NOW_MAIN:0:9} after the merge ${MERGE_SHA:0:9}; $TAG is NOT tagged. Decide by hand where it goes"
  [ "$(git rev-parse "$MERGE_SHA^{tree}")" = "$(git rev-parse "$RELEASE_COMMIT^{tree}")" ] \
    || fail "the merge commit ${MERGE_SHA:0:9} does not hold the tree that was built; $TAG is NOT tagged"
  git checkout -q --detach "$MERGE_SHA" || fail "could not check out ${MERGE_SHA:0:9}"
  scripts/check-version-matches-tag.sh "$TAG" || fail "the merge commit does not carry $TAG; $TAG is NOT tagged"
  "${GIT[@]}" tag -a "$TAG" -m "$TAG" "$MERGE_SHA" || fail "could not tag ${MERGE_SHA:0:9}"
  git push -q origin "refs/tags/$TAG" || fail "could not push the tag $TAG; it exists locally only"
  echo "   pushed $TAG on ${MERGE_SHA:0:9}"
else
  echo "   would check that origin/main is the merge commit and holds the built tree"
  echo "   would run: scripts/check-version-matches-tag.sh $TAG   (on the merge commit)"
  echo "   would run: git tag -a $TAG -m $TAG <merge commit>"
  echo "   would run: git push origin refs/tags/$TAG   (the tag alone)"
fi

# --------------------------------------------------------------- next PR -----
step "Pull request: $NEXT_TITLE"
# The prepared commit moves onto the merge commit. Their trees match, so this cannot conflict.
act "${GIT[@]}" cherry-pick "$NEXT_COMMIT"
act git push -q origin "HEAD:refs/heads/$NEXT_BRANCH"
NEXT_BODY="$TAG is tagged on the \"$RELEASE_TITLE\" commit, whose poms say $TAG.

- The README names $TAG, so \`scripts/check-readme-versions.py\` passes again.
- The root pom and every module pom move to $NEXT."
NEXT_PR=""
if [ "$EXECUTE" = "1" ]; then
  NEXT_PR="$(open_pr "$NEXT_BRANCH" "$NEXT_TITLE" "$NEXT_BODY")" \
    || fail "could not open the pull request; $TAG is tagged, $NEXT_BRANCH is on origin"
  echo "   opened #$NEXT_PR"
  wait_for_checks "$NEXT_PR" \
    || fail "#$NEXT_PR is not ready to merge; $TAG is tagged and publishing. Fix #$NEXT_PR and merge it by hand"
  merge_pr "$NEXT_PR" "$NEXT_TITLE" >/dev/null || fail "#$NEXT_PR did not merge; $TAG is tagged"
  git push -q origin --delete "$NEXT_BRANCH" 2>/dev/null || true
  git fetch -q origin main || fail "could not fetch origin"
  git checkout -q --detach origin/main || fail "could not check out origin/main"
  scripts/check-version-matches-tag.sh "$NEXT" --no-maven || fail "main does not carry $NEXT after #$NEXT_PR"
else
  echo "   would push $NEXT_BRANCH, open \"$NEXT_TITLE\", wait for ${REQUIRED_CHECKS[*]},"
  echo "   squash-merge it, delete $NEXT_BRANCH and check that main carries $NEXT"
fi

step "After"
echo "   release.yml publishes $TAG to Maven Central, pushes the image and creates the GitHub"
echo "   Release. Watch it with:"
echo "     gh run list -R $REPO --workflow release.yml --branch $TAG --limit 1"

echo
if [ "$EXECUTE" = "1" ]; then
  echo "done: $TAG tagged on ${MERGE_SHA:0:9} (#$RELEASE_PR), main open at $NEXT (#$NEXT_PR)"
else
  # Plan mode ran nothing, so the checkout is where it was.
  echo "plan complete: add --execute to cut $TAG"
fi
