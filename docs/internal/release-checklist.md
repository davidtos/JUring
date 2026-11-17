# 0.1.0 release checklist (maintainer)

This is the human-only part of the release; everything before it is prepared in the repository
and CI. Do not tag or deploy from a dirty tree.

## Prerequisites (one-time)

- [ ] Sonatype Central account with the `io.github.davidtos` namespace verified (GitHub account
      `davidtos`).
- [ ] GPG signing key created and published (`gpg --keyserver keyserver.ubuntu.com --send-keys ...`).
- [ ] GitHub environment `release` with secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`
      (Central user token), `GPG_PRIVATE_KEY` (ASCII-armoured), `GPG_PASSPHRASE`.
- [ ] Branch protection on the default branch; CI required.

## Pre-release gate

- [ ] `git status` clean; on the release commit (expected `master` after fast-forward).
- [ ] CI green on JDK 25 and 26 (`mvn -B -ntp clean test` + strict javadoc).
- [ ] `mvn -B -ntp -Prelease -DskipTests -Dgpg.skip=true package` produces
      `target/juring-0.1.0{,-sources,-javadoc}.jar`.
- [ ] `jar --describe-module --file target/juring-0.1.0.jar` shows `JUring@0.1.0` exporting only
      `com.davidvlijmincx.lio.api`.
- [ ] Benchmark claims match BENCHMARK.md / DATA-AVAILABILITY.md (no unlabelled overclaim);
      claims-rerun session from `bench/m1/run-claims-0.1.sh` archived if used.
      **State 2026-11-17:** control run done with the fixed sub-session harness
      (`bench/results/claims-0.1-20261117/read-matrix-{matched,matchedbatch}`); the no-regression
      check is in `BENCHMARK.md` §2.1a. Archive that session, plus
      `bench/results/perf-claims-0.1-20261117` and the lever logs in `scratch/exp-*`, with the
      release data archive.
- [ ] `CHANGELOG.md`, `docs/api-stability.md`, `docs/UPGRADING.md` say `0.1.0`.

## Tag and deploy

- [ ] `git tag -s v0.1.0 -m "JUring 0.1.0"` and push the tag.
- [ ] GitHub release `v0.1.0` from the tag with the CHANGELOG section; attach the benchmark data
      archive (aggregate JSON/manifests/analyzers + SHA-256, see DATA-AVAILABILITY.md).
- [ ] Run the "Release" GitHub workflow with `tag=v0.1.0` (tests + deploy to Central staging).
- [ ] In the Central Portal, verify the deployment (sources + javadoc present, signatures valid)
      and publish it (autoPublish is deliberately false).
- [ ] Verify from a clean environment:
      `mvn dependency:get -Dartifact=io.github.davidtos:juring:0.1.0`
      and a tiny `JUring.isSupported()` program with `--enable-native-access=JUring`.

## After release

- [ ] Confirm the README install snippet resolves (no snapshot coordinates).
- [ ] Finalize the article/HN/OpenJDK material (Phase 5) only after the artifact is live.
- [ ] Open the next `0.1.x` milestone; deprecations/breaking changes go through
      `docs/UPGRADING.md` per `docs/api-stability.md`.
