#!/usr/bin/env python3
"""Keep releases inside Maven Central's monthly publishing limits.

Central counts an organization's releases, files and megabytes per calendar month (UTC). The
limits for the com.dvarahq organization are 7 releases, 1,000 files and 80 MB a month, shared by
every project it publishes. Over a limit, Central gives a grace period and then refuses new
releases until the next month.

The budget here is tighter than the limits, so an urgent security release still fits:

  releases a month    4     (limit 7)
  files per release   160   (a release is about 132 since the runnable jar left Central)
  MB per release      15    (about 11)

Three commands:

  central-budget.py budget
      Prints the budgeted number of releases a month.

  central-budget.py releases-this-month [--exclude VERSION]
      Prints how many releases the organization published on Central this month, read from the
      dates of the published poms.

  central-budget.py check-bundle BUNDLE.zip --version VERSION
      Measures a bundle built with -Ddvara.central.skipUpload=true and refuses it if it is over the
      per-release budget, or if this month already has the budgeted number of releases.

scripts/cut-release.sh runs releases-this-month when it plans a release; release.yml runs
check-bundle before it uploads anything.

Environment:
  CENTRAL_BUDGET_OVERRIDE     a reason; the check reports what is over and passes. For a release
                              that cannot wait for next month, such as a security fix. Tell Central
                              support first (central-support@sonatype.com).
  CENTRAL_RELEASES_THIS_MONTH a number used instead of asking Central (tests).
"""

import argparse
import datetime
import email.utils
import os
import re
import sys
import urllib.request
import zipfile

MAX_RELEASES_PER_MONTH = 4
MAX_FILES_PER_RELEASE = 160
MAX_MB_PER_RELEASE = 15

REPO = "https://repo1.maven.org/maven2"
# One artifact per project the organization releases. Every release of a project publishes its
# parent pom, so the dates of those poms are the project's release dates.
PROJECTS = [
    "com/dvarahq/dvara-platform",
    "com/dvarahq/oss/evals4j-parent",
]


def _get(url, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "dvara-central-budget"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode() if method == "GET" else resp.headers


def releases_this_month(exclude=None, now=None):
    override = os.environ.get("CENTRAL_RELEASES_THIS_MONTH")
    if override:
        return int(override), []
    now = now or datetime.datetime.now(datetime.timezone.utc)
    found = []
    for path in PROJECTS:
        artifact = path.rsplit("/", 1)[1]
        try:
            metadata = _get(f"{REPO}/{path}/maven-metadata.xml")
        except Exception as e:  # a project not published yet has no metadata
            print(f"note: no metadata for {artifact} ({e})", file=sys.stderr)
            continue
        for version in re.findall(r"<version>([^<]+)</version>", metadata):
            if version.endswith("-SNAPSHOT") or version == exclude:
                continue
            headers = _get(f"{REPO}/{path}/{version}/{artifact}-{version}.pom", method="HEAD")
            published = email.utils.parsedate_to_datetime(headers["Last-Modified"])
            if (published.year, published.month) == (now.year, now.month):
                found.append(f"{artifact} {version} ({published:%Y-%m-%d})")
    return len(found), found


def check_bundle(bundle, version):
    with zipfile.ZipFile(bundle) as z:
        entries = [i for i in z.infolist() if not i.is_dir()]
    files = len(entries)
    mb = sum(i.file_size for i in entries) / (1024 * 1024)
    count, names = releases_this_month(exclude=version)

    print(f"this release: {files} files, {mb:.1f} MB "
          f"(budget {MAX_FILES_PER_RELEASE} files, {MAX_MB_PER_RELEASE} MB)")
    print(f"releases on Central this month before it: {count} (budget {MAX_RELEASES_PER_MONTH})")
    for n in names:
        print(f"  {n}")

    over = []
    if files > MAX_FILES_PER_RELEASE:
        over.append(f"{files} files is over the {MAX_FILES_PER_RELEASE}-file budget for one release")
    if mb > MAX_MB_PER_RELEASE:
        over.append(f"{mb:.1f} MB is over the {MAX_MB_PER_RELEASE} MB budget for one release")
    if count >= MAX_RELEASES_PER_MONTH:
        over.append(f"this month already has {count} releases on Central; the budget is "
                    f"{MAX_RELEASES_PER_MONTH} (Central's limit is 7, shared by every project)")
    if not over:
        print("within budget")
        return 0
    for o in over:
        print(f"over budget: {o}")
    reason = os.environ.get("CENTRAL_BUDGET_OVERRIDE", "").strip()
    if reason:
        print(f"passing anyway: CENTRAL_BUDGET_OVERRIDE ({reason})")
        return 0
    print("Nothing was uploaded. Wait for next month, make the bundle smaller, or set the repository")
    print("variable CENTRAL_BUDGET_OVERRIDE to the reason (after telling central-support@sonatype.com)")
    print("and re-run the release.")
    return 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("budget")
    r = sub.add_parser("releases-this-month")
    r.add_argument("--exclude")
    c = sub.add_parser("check-bundle")
    c.add_argument("bundle")
    c.add_argument("--version", required=True)
    args = parser.parse_args(argv)
    if args.command == "budget":
        print(MAX_RELEASES_PER_MONTH)
        return 0
    if args.command == "releases-this-month":
        count, names = releases_this_month(exclude=args.exclude)
        for n in names:
            print(n, file=sys.stderr)
        print(count)
        return 0
    return check_bundle(args.bundle, args.version)


if __name__ == "__main__":
    sys.exit(main())
