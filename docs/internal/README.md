# Internal process artifacts

This directory contains the historical design, benchmark-campaign, verification and handover
records that produced JUring 0.1.0. They are kept for auditability and for future maintainers —
they are **not** user documentation and are not maintained as a guide to the current API.

The user-facing documentation lives in:

- [README.md](../../README.md) and [BENCHMARK.md](../../BENCHMARK.md)
- [docs/api-stability.md](../api-stability.md), [docs/UPGRADING.md](../UPGRADING.md)
- [docs/examples.md](../examples.md), [docs/benchmark-methodology.md](../benchmark-methodology.md)
- [docs/m4-decision.md](../m4-decision.md) (scope/QoS decision), [docs/platforms.md](../platforms.md)
- [CHANGELOG.md](../../CHANGELOG.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), [SECURITY.md](../../SECURITY.md)

What is in here:

| file(s) | what it records |
|---|---|
| `HANDOVER.md`, `FINAL-PLAN.md`, `SEASTAR-PLAN.md` | session handovers, the B0–B10 roadmap and the Seastar comparison programme plan |
| `b1-*` … `b10-*` | design contracts and delivery reports for the API milestones (file handle, streams, lifecycle, shard runtime, observability, durability, API stability, ring multiplexing/ioprio) |
| `m1-*`, `m2-report.md`, `m3-*`, `stepd-*`, `baseline-2026-11-13.md` | pre-registered benchmark campaigns, device-state gating, host-representativeness runs and the signed/INCONCLUSIVE verdicts |
| `juring-inventory.md`, `seastar-inventory.md` | capability inventories used to scope the work |
| `article-virtual-threads-io-uring.md`, `conference-proposal-io-uring.md` | publication drafts (review in progress; not published from this directory) |
| `BACKLOG-0.1-followups.md` | engineering items deliberately deferred after 0.1.0 |

These documents may contain links to files that have since moved, stale API names, and statements
that were true for a specific session/commit. When in doubt, the source and the user-facing docs
above are authoritative.
