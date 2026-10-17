# Contributing to DVARA

Thank you for your interest. This document says what to expect before you spend
time on a change.

## The Contributor Licence Agreement

**Most contributions need no paperwork at all.** Apache-2.0 section 5 provides
that anything you submit for inclusion is under the terms of that licence unless
you say otherwise, so a typo, a bug fix or a documentation change is accepted as
it stands.

**For substantial contributions we may ask you to sign [CLA.md](CLA.md).** That
is at DVARA Labs' discretion, and we will say so on the pull request — there is
nothing to do in advance. Signing is a single comment; CLA.md gives the wording,
and a person checks it rather than a bot.

What the agreement adds, beyond what Apache-2.0 already grants, is the ability to
relicense the project itself later and your representation that you are entitled
to grant what you are granting. You keep your copyright either way.

Contributions made in the course of employment need a corporate agreement **in
addition to** the individual one, not instead of it; [CLA.md](CLA.md) says how to
start it.

## Before you open a pull request

**Open an issue first for anything beyond an obvious fix.** A typo, a broken link
or a one-line correction needs no preamble. Anything that changes behaviour,
adds a dependency, or introduces a new interface does — the answer may be that it
is out of scope for this repository, and it is better to hear that before you
build it.

## What belongs here, and what does not

This repository is the **open-source LLM Gateway** of DVARA. What is here:
the governance engines, the providers, the request pipeline, and the policy and
PII machinery — and the audit chain, which writes every decision to an
HMAC-chained file with a verifier that reports what it checked.

Some components are not published here, and a change that would move something
across that line is a product decision rather than a code review — so raise it as
an issue rather than opening a pull request. Nothing that stops an attack depends
on a licence: the enforcement paths are all in this repository.

## Building

Requires **JDK 25** and Maven 3.9+ (or the bundled `./mvnw`).

```bash
./mvnw clean install          # build and test everything
./mvnw -pl <module> test      # one module
```

## What a good pull request looks like

- **One change per pull request.** A refactor bundled with a fix is two reviews
  wearing one hat, and the fix is the one that gets less attention.
- **Tests that would fail without the change.** A test added alongside a fix should
  be demonstrably red before it. If a change cannot be tested, say so in the
  description and say why.
- **A description that explains the *why*.** The diff already says what changed.
  What it cannot say is what went wrong, what else you tried, and what you decided
  against.
- **No unrelated formatting.** A reformatted file hides the change inside it.
- **No tool attribution in commit messages.** A commit whose message carries an
  AI-assistant trailer such as `Co-Authored-By: Claude` or `Generated with Claude`
  fails the repository's checks. Write the message yourself.
- **An optional dependency says what guards its absence.** A `<dependency>` marked
  `<optional>true</optional>` is left out of every published jar, so a consumer may
  not have it. Put a comment directly above it that begins `guarded-by:` and names
  what makes the absence safe: a `@ConditionalOnClass` probe, a condition that is
  false without it, or another dependency that supplies it anyway. The repository's
  checks fail without that comment, and it is the one comment a pom must keep.

## How a release is cut

For maintainers.

A pushed version tag builds, tests, publishes the artifacts to Maven Central, attaches the jar to a
GitHub Release and publishes the image. The Central upload stops at a staged bundle and is released
by hand, because a version on Central can never be deleted or replaced.

`scripts/cut-release.sh` makes the tag. It opens a pull request that sets the version, waits for
the `build` and `gate` checks, squash-merges it and tags the merge commit. A second pull request
then names the release in the README and opens the next `-SNAPSHOT`. It only prints its plan
unless given `--execute`.

The same script runs from the Actions tab: **cut-release** → Run workflow, with the version, the
release-candidate number (blank for a final release) and `confirm`. It plans unless `confirm` is
`execute`. The workflow needs a repository secret, `RELEASE_TOKEN`, and stops at its first step
without it. The job's own token cannot be used: a pull request or tag it pushes starts no other
workflow, so the checks would never run and nothing would be published. To create the token:

1. Signed in as grabdoc: Settings → Developer settings → Personal access tokens → Fine-grained
   tokens → Generate new token. The script refuses a token owned by anyone else.
2. Resource owner **dvarahq**. Repository access: **Only select repositories** → `dvarahq/dvara`.
   Choose the longest expiry allowed, and note the date.
3. Repository permissions:
   - **Contents: Read and write**: push the release branches and the tag, merge, and delete the
     branches after.
   - **Pull requests: Read and write**: open the two pull requests and squash-merge them.
   - Metadata: Read is added by GitHub. Nothing else. The repository is public, so the checks on
     each pull request and the workflow runs are read without a permission. No Workflows either,
     because the release commits change only the poms and the README.
4. If dvarahq requires approval of fine-grained tokens, an organisation owner approves it.
5. Add it to `dvarahq/dvara` as the repository secret `RELEASE_TOKEN` (Settings → Secrets and
   variables → Actions), or run `gh secret set RELEASE_TOKEN -R dvarahq/dvara`.
6. Check it with a plan run. The preflight must print `gh user: grabdoc`.

The rules on `main` must let the token's owner merge a pull request once `build` and `gate` pass;
the pull requests still need both checks green. When the token expires, the plan stops at the
`gh user` check; generate a new one with the same settings and replace the secret.

## Code of conduct

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting a security issue

Do not open a public issue. See [SECURITY.md](SECURITY.md).

## Licence

By contributing you agree that your contributions are licensed under the
Apache Licence, Version 2.0.
