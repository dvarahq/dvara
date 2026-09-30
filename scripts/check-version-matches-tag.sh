#!/usr/bin/env bash
#
# Every pom in the tree must carry exactly the given version.
#
#   scripts/check-version-matches-tag.sh 1.8.4-rc1
#   scripts/check-version-matches-tag.sh 1.8.4 --no-maven    # the literal check only
#
# The release workflow runs this on the tagged commit before it builds or publishes anything, and
# cut-release.sh runs it after its version bump. The version in a released jar has to be the version
# committed at the tag, so that checking out the tag and building it gives the same artifact. A
# workflow that set the version itself from the tag name would publish a version that no commit
# records.
#
# Two checks, because each catches what the other cannot:
#   - the literal <version> of the root project and the <parent><version> of every module, read
#     from the files. A module whose parent version was missed by a bump still builds when the old
#     parent is in the local repository, so the resolved model alone would not show it.
#   - the version Maven itself resolves for the root project, which is what the jars are named after.
#
set -uo pipefail

WANT="${1:-}"
USE_MAVEN=1
[ "${2:-}" = "--no-maven" ] && USE_MAVEN=0

if [ -z "$WANT" ]; then
  echo "usage: $0 <version> [--no-maven]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

FAIL=0
report() {
  # The ::error:: form is read by GitHub Actions and is plain text anywhere else.
  echo "::error file=$1::$2"
  FAIL=1
}

# Prints two fields: the version inside <parent>, and the project's own <version>, which is the first
# one after </parent> and before any section that holds dependency or plugin versions. Either is "-"
# when absent. Every pom here writes each element on its own line, which is all this relies on.
versions_of() {
  awk '
    /<parent>/                   { inparent = 1 }
    inparent && /<version>/      { pv = $0 }
    /<\/parent>/                 { inparent = 0; next }
    /<(dependencies|dependencyManagement|build|properties|profiles|modules|reporting)>/ { stop = 1 }
    !inparent && !stop && !own && /<version>/ { own = $0 }
    END {
      gsub(/.*<version>|<\/version>.*/, "", pv);  if (pv == "")  pv = "-"
      gsub(/.*<version>|<\/version>.*/, "", own); if (own == "") own = "-"
      print pv, own
    }
  ' "$1"
}

[ -f pom.xml ] || { report "pom.xml" "no root pom.xml in $ROOT"; exit 1; }

read -r _ root_own <<<"$(versions_of pom.xml)"
if [ "$root_own" != "$WANT" ]; then
  report "pom.xml" "the root project version is $root_own, not $WANT"
fi

modules="$(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' pom.xml)"
[ -n "$modules" ] || report "pom.xml" "the root pom lists no modules"

count=0
for m in $modules; do
  count=$((count + 1))
  p="$m/pom.xml"
  if [ ! -f "$p" ]; then
    report "$p" "module $m has no pom.xml"
    continue
  fi
  read -r parent own <<<"$(versions_of "$p")"
  [ "$parent" = "$WANT" ] || report "$p" "the parent version is $parent, not $WANT"
  # A module normally inherits its version. One that states its own must state the same one.
  if [ "$own" != "-" ] && [ "$own" != "$WANT" ]; then
    report "$p" "the module version is $own, not $WANT"
  fi
done

if [ "$USE_MAVEN" = "1" ]; then
  resolved="$(./mvnw -B -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null | tail -1)"
  if [ "$resolved" != "$WANT" ]; then
    report "pom.xml" "Maven resolves the project version as '${resolved:-nothing}', not $WANT"
  fi
fi

if [ "$FAIL" = "1" ]; then
  echo "The committed poms do not carry $WANT. Commit the version the release publishes, then tag"
  echo "that commit: scripts/cut-release.sh does both."
  exit 1
fi
echo "root and $count module poms carry $WANT"
