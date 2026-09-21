#!/usr/bin/env python3
"""The README must not quote a version that is no longer the current one.

Four lines of this README named 1.8.0-rc1 while rc2 and then the final release
shipped. Nobody noticed, because nothing looked. A checklist item would have had
the same fate, so this is a check the build runs.

It flags a CONCRETE version only. The README uses a literal `<version>` as a
placeholder in several places, and a placeholder is always correct.

Three shapes are checked:

  ghcr.io/dvarahq/dvara-gateway-oss:1.2.3   an image tag
  <version>1.2.3</version>                  a Maven coordinate
  com.dvarahq:artifact:1.2.3                a Gradle coordinate

For an image, `latest` and the major.minor stream are both fine: they move on
their own and cannot go stale. Anywhere else the version must be the newest
release tag exactly, because a dependency coordinate has to be reproducible.

Run it by hand the same way CI does: python3 scripts/check-readme-versions.py
"""

import re
import subprocess
import sys

# A line that must keep an old version on purpose — an upgrade note, say. Add an
# entry only with the reason it is provably right. An exclusion added to make a
# build pass is how a guard starts rotting.
ALLOW: set[tuple[int, str]] = set()

README = "README.md"


def current_release() -> str:
    """The newest release tag. A missing tag is a hard failure, not a skip.

    Passing when the version cannot be determined would make this check
    unfailable, which is the defect it exists to prevent rather than a safe
    default.
    """
    out = subprocess.run(
        ["git", "tag", "-l"], capture_output=True, text=True, check=True,
    ).stdout.split()

    # The ordering is done here rather than with `git tag --sort=-v:refname`,
    # which ranks 1.8.0-rc2 ABOVE 1.8.0: git treats the suffix as extra version
    # detail unless versionsort.suffix is configured. That is not a detail — it
    # made this check pick a release candidate as the current release, and so
    # demand that the README name the rc.
    parsed = []
    for t in out:
        m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-rc(\d+))?", t)
        if m:
            major, minor, patch, rc = m.groups()
            # A final release outranks every candidate of the same number.
            parsed.append(((int(major), int(minor), int(patch),
                            0 if rc else 1, int(rc or 0)), t))
    if not parsed:
        print("::error::no release tag found, so the README cannot be checked against one. "
              "Fetch tags (actions/checkout needs fetch-depth: 0) and re-run.")
        sys.exit(1)
    return max(parsed)[1]


def main() -> int:
    version = current_release()
    stream = ".".join(version.split(".")[:2])          # 1.8.0 -> 1.8
    text = open(README, encoding="utf-8").read()
    lines = text.splitlines()

    checks = [
        (re.compile(r"dvara-gateway-oss:([A-Za-z0-9._-]+)"), "image tag", {"latest", version, stream}),
        (re.compile(r"<version>(\d[^<]*)</version>"), "Maven version", {version}),
        (re.compile(r"com\.dvarahq:[A-Za-z0-9.-]+:(\d[A-Za-z0-9.-]*)"), "Gradle version", {version}),
    ]

    hits = []
    for pattern, what, allowed in checks:
        for m in pattern.finditer(text):
            found = m.group(1)
            if found in allowed:
                continue
            line = text[: m.start()].count("\n") + 1
            if (line, found) in ALLOW:
                continue
            hits.append((line, what, found, lines[line - 1].strip()[:110]))

    for line, what, found, src in sorted(hits):
        print(f"::error file={README},line={line}::{what} '{found}' is not the current "
              f"release '{version}': {src}")

    if hits:
        print()
        print(f"The current release is {version}. A README that names an older version sends")
        print("someone to an image or an artifact that is not the one this repository")
        print("documents, and it stays wrong until a human happens to notice — which is how")
        print("1.8.0-rc1 survived two releases.")
        print("For an image, 'latest' or the major.minor stream also pass, since neither goes")
        print("stale. A dependency coordinate must be the exact version.")
        return 1

    print(f"readme versions ok (current release {version}; 'latest' and {stream} accepted for images)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
