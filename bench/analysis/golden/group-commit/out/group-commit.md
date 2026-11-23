# B6 W25 group-commit micro-benchmark -- aggregate (JUring-only)

Session: `b6-group-commit-2026-11-15`  
Source: `<SESSIONS>/b6-group-commit-2026-11-15`  
Generated: <TIMESTAMP>  
Contract: `docs/b6-design.md` §6.4 + A2 rest adjudication. Raw protocol: `<SESSIONS>/b6-group-commit-2026-11-15/manifest.txt`.

Protocol observed in the cell JSONs: warmup 5s + measured 15s, bs 4096 B, durability fdatasync, seed 20261113; R=3.

## Contract identities (machine-checkable acceptance)

| cell | batch | n | fsyncs_per_group mean | == 1.0 | groups*batch == committed_writes | syncs_failed | restart_verify.ok | wrap-arounds |
|---|---|---|---|---|---|---|---|---|
| W25-4k-b1 | 1 | 3 | 1.000000 | PASS | PASS | 0 | PASS | 0 |
| W25-4k-b8 | 8 | 3 | 1.000000 | PASS | PASS | 0 | PASS | 7416 |
| W25-4k-b32 | 32 | 3 | 1.000000 | PASS | PASS | 0 | PASS | 81177 |
| W25-4k-b128 | 128 | 3 | 1.000000 | PASS | PASS | 0 | PASS | 58720 |

All identities hold: every measured batch issued exactly one fdatasync (`fsyncs_per_group == 1.0`), `groups_total * batch_size == committed_writes`, and every fresh-process re-read verified the deterministic pattern.

## Cost / latency per cell (all reps kept)

| cell | batch | n | groups/s | writes/s | MiB/s mean | sd | 95% CI ± | CV% | group p50 (ms) | group p99 (ms) | sync p50 (us) | sync p99 (us) | syncs/s | MiB/sync | sync_wait_share | fsyncs/group |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| W25-4k-b1 | 1 | 3 | 1521.51 | 1522 | 5.9 | 1.77 | 4.39 | 29.70 | 0.581 | 2.195 | 580.1 | 2189.8 | 1521.5 | 0.004 | 0.997 | 1.000000 |
| W25-4k-b8 | 8 | 3 | 2011.12 | 16089 | 62.8 | 20.39 | 50.65 | 32.44 | 0.496 | 1.146 | 489.4 | 1134.4 | 2011.1 | 0.031 | 0.987 | 1.000000 |
| W25-4k-b32 | 32 | 3 | 2350.04 | 75201 | 293.8 | 6.57 | 16.31 | 2.24 | 0.397 | 0.686 | 373.1 | 658.0 | 2350.0 | 0.125 | 0.943 | 1.000000 |
| W25-4k-b128 | 128 | 3 | 1441.39 | 184497 | 720.7 | 8.15 | 20.24 | 1.13 | 0.669 | 0.976 | 574.4 | 876.4 | 1441.4 | 0.500 | 0.861 | 1.000000 |

Per-rep detail (exactly one fdatasync per committed batch; the fresh-process re-read verifies the deterministic pattern):

| cell | rep | groups | committed writes | MiB/s | group p50 (ms) | group p99 (ms) | sync p50 (us) | fsyncs/group | verified bytes | wrap-arounds |
|---|---|---|---|---|---|---|---|---|---|---|
| W25-4k-b1 | 1 | 15371 | 15371 | 4.0 | 0.818 | 4.512 | 817.0 | 1.000000 | 62959616 | 0 |
| W25-4k-b1 | 2 | 24475 | 24475 | 6.4 | 0.471 | 1.148 | 469.8 | 1.000000 | 100249600 | 0 |
| W25-4k-b1 | 3 | 28624 | 28624 | 7.5 | 0.455 | 0.926 | 453.7 | 1.000000 | 117243904 | 0 |
| W25-4k-b8 | 1 | 20628 | 165024 | 43.0 | 0.866 | 1.512 | 860.2 | 1.000000 | 675938304 | 0 |
| W25-4k-b8 | 2 | 29690 | 237520 | 61.9 | 0.312 | 0.999 | 305.5 | 1.000000 | 972881920 | 0 |
| W25-4k-b8 | 3 | 40184 | 321472 | 83.7 | 0.309 | 0.927 | 302.4 | 1.000000 | 1073741824 | 7416 |
| W25-4k-b32 | 1 | 34347 | 1099104 | 286.2 | 0.398 | 0.648 | 373.4 | 1.000000 | 1073741824 | 26155 |
| W25-4k-b32 | 2 | 35791 | 1145312 | 298.3 | 0.396 | 0.605 | 372.0 | 1.000000 | 1073741824 | 27599 |
| W25-4k-b32 | 3 | 35615 | 1139680 | 296.8 | 0.398 | 0.807 | 374.0 | 1.000000 | 1073741824 | 27423 |
| W25-4k-b128 | 1 | 21889 | 2801792 | 729.6 | 0.663 | 0.997 | 568.2 | 1.000000 | 1073741824 | 19841 |
| W25-4k-b128 | 2 | 21565 | 2760320 | 718.8 | 0.673 | 0.930 | 578.1 | 1.000000 | 1073741824 | 19517 |
| W25-4k-b128 | 3 | 21410 | 2740480 | 713.6 | 0.672 | 1.000 | 576.9 | 1.000000 | 1073741824 | 19362 |

## Data quality / contract violations (no rep silently dropped)

Every cell JSON parsed, carried the `b6-durability-1` extension, satisfied the W25 identities and passed the fresh-process restart check.

## Disclosures

- W25 is JUring-only: seastar has no group-commit primitive. The nearest seastar equivalent is the W24 seastar-fdatasync arm (one fstream flush per batch, which also drains write-behind); that is an upper bound on the cost, not a matched comparison (docs/b6-design.md §6.4/§9.7).
- 'One fdatasync per batch' is proven by GroupCommitStats counters + the JSON identity (fsyncs_per_group == 1.0), not by a syscall trace; 'visibility after restart' is a fresh-process re-read of the deterministic pattern (process-restart visibility, NOT a power-loss test; §9.1).
- sync_latency_us is the commit() call duration and is therefore an UPPER BOUND on the sync-only cost (it includes waiting for the batch writes; the public API exposes no sync-only hook).
- The W25 file is preallocated with setLength (sparse) and offsets wrap modulo the file capacity; the pattern is position-indexed, so the verified prefix is deterministic across wraps (wrap_arounds reported).
- Rests (A2 adjudication): 5 s pause between reps inside a cell, 120 s idle rest between cells - this is a count/latency micro-benchmark, not a throughput campaign.
- Configuration/durability: durability fdatasync (never fsync), bs 4096 B, cells b1/b8/b32/b128; the W25 measured window is 15 s after a 5 s warmup.

