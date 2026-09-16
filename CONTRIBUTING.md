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

## Code of conduct

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting a security issue

Do not open a public issue. See [SECURITY.md](SECURITY.md).

## Licence

By contributing you agree that your contributions are licensed under the
Apache Licence, Version 2.0.
