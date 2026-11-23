# B6 W24 durability A/B -- aggregate (supporting evidence, not a milestone bar)

Session: `b6-durability-2026-11-15`  
Source: `<SESSIONS>/b6-durability-2026-11-15`  
Generated: <TIMESTAMP>  
Contract: `docs/b6-design.md` §6.3. Raw protocol: `<SESSIONS>/b6-durability-2026-11-15/manifest.txt`.

Protocol observed in the cell JSONs: warmup 5s + measured 15s per process, sync-every 4194304 B, seed 20261113; the driver protocol (R, 300 s rests, alternating arm order, writeback bracket control) is recorded in the session manifest.

## Tier-1 arms (decisive): per-arm summary (all reps kept)

| engine | arm | n | MiB/s mean | sd | 95% CI ± | CV% | syncs/s | MiB/sync | sync_wait_share | sync p50 (us) | sync p99 (us) | write p50 (us) | passes | class f/m/s | unstable |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| juring | fdatasync | 3 | 1562.7 | 1259.00 | 3127.77 | 80.57 | 391.4 | 3.993 | 0.460 | 717.4 | 6570.5 | 20.7 | 11.7 | 1/0/2 | yes |
| juring | writeback | 3 | 2721.3 | 2233.26 | 5548.17 | 82.07 | 0.0 | n/a | 0.241 | 2308.9 | 8925.6 | 274.0 | 20.7 | 2/0/1 | yes |
| seastar | fdatasync | 3 | 1277.4 | 959.46 | 2383.63 | 75.11 | 320.6 | 3.983 | 0.533 | 1646.1 | 10499.1 | 3.3 | 10.0 | 1/0/2 | yes |
| seastar | writeback | 3 | 2749.9 | 2080.12 | 5167.72 | 75.64 | 0.0 | n/a | 0.274 | 281.9 | 3600.9 | 3.1 | 20.7 | 2/0/1 | yes |

## Tier-2 / exploratory arms

| engine | arm | n | MiB/s mean | CV% | syncs/s | sync_wait_share | sync p50 (us) | sync p99 (us) | class f/m/s | note |
|---|---|---|---|---|---|---|---|---|---|---|
| juring | dsync | 2 | 321.5 | 31.98 | 0.0 | 0.247 | 2427.3 | 12839.1 | 0/1/1 | exploratory (O_DSYNC per write; no explicit sync) |
| juring | fsync | 2 | 483.5 | 0.15 | 121.1 | 0.681 | 5452.1 | 7765.5 | 0/2/0 | JUring-only (seastar has no fsync(2) path) |
| juring | writeback-bracket | 1 | 973.7 | n/a | 0.0 | 0.243 | 864.8 | 3596.8 | 1/0/0 | state-control / bracket |
| seastar | dsync | 2 | 385.5 | 3.01 | 0.0 | 0.279 | 2730.4 | 7118.2 | 0/2/0 | exploratory (O_DSYNC per write; no explicit sync) |

## Within-rep paired durability cost (durable MiB/s / writeback MiB/s, same rep)

| engine | pair | n all | ratio mean (all) | 95% CI | cost mean (all) | n state-matched | ratio mean (matched) | cost mean (matched) | ranking |
|---|---|---|---|---|---|---|---|---|---|
| juring | juring-fdatasync vs juring-writeback | 3 | 0.997 | 1.954 | 0.003 | 2 | 1.292 | -0.292 | unstable-no-ranking (CV > 10%) |
| seastar | seastar-fdatasync vs seastar-writeback | 3 | 0.840 | 1.933 | 0.160 | 2 | 1.149 | -0.149 | unstable-no-ranking (CV > 10%) |

Per-rep paired detail (positions and state classes attached; every rep kept):

| engine | rep | control MiB/s | durable MiB/s | ratio | cost % | control class | durable class | state matched |
|---|---|---|---|---|---|---|---|---|
| juring | 1 | 142.6 | 269.5 | 1.890 | -89.0 | slow | slow | yes |
| juring | 2 | 4007.5 | 1634.1 | 0.408 | 59.2 | fast | slow | no |
| juring | 3 | 4013.9 | 2784.4 | 0.694 | 30.6 | fast | fast | yes |
| seastar | 1 | 352.2 | 603.7 | 1.714 | -71.4 | slow | slow | yes |
| seastar | 2 | 3825.1 | 852.5 | 0.223 | 77.7 | fast | slow | no |
| seastar | 3 | 4072.3 | 2375.9 | 0.583 | 41.7 | fast | fast | yes |

## Writeback state-control bracket (Tier-1 block)

| start control (rep 1 JUring-writeback) | end control (last overall) | diff | within 10% |
|---|---|---|---|
| 142.6 MiB/s (slow) | 973.7 MiB/s (fast) | 148.91 % | NO |

The two writeback controls differ by 148.9% > 10%: the session is state-confounded; only the raw per-rep table is reported and no durability-cost number may be quoted.

## Data quality (no rep silently dropped)

- MISSING W24-128k / juring / dsync rep 3
- MISSING W24-128k / juring / fsync rep 3
- MISSING W24-128k / juring / writeback-bracket rep 2
- MISSING W24-128k / juring / writeback-bracket rep 3
- MISSING W24-128k / seastar / dsync rep 3

## Disclosures

- Supporting evidence only: W24 is not a milestone bar and makes no new cross-engine write win/loss claim (docs/b6-design.md §6.3/§6.5; methodology D7).
- The M2 W21/W22 write comparison issued 2 fdatasync/pass on seastar and 0 on JUring (undisclosed in M2); the W24 arms match durability on both engines and run a writeback-vs-writeback control first/last, so the paired ratio here is a within-engine cost of durability, not an engine ranking.
- Durability points include a write-behind drain on both engines (B2 flush() and seastar fstream flush() both drain); the matched arms isolate the sync cost (writeback = drain only vs fdatasync = drain + one sync), but the absolute sync_wait_share is an upper bound on pure sync cost (§9.7).
- seastar's pass-end path issues two fdatasync per pass (output_stream flush() + close()); the JUring pass-end path issues one. With --sync-every 4 MiB this is <1 % of the durability points and is reported through syncs/s and MiB/sync instead of being hidden.
- seastar has no fsync(2) path (file::flush() is fdatasync only); the juring-fsync arm is JUring-only and is not compared across engines.
- State classes are derived (not pre-registered) from M1 §4 + M2 §6 and fixed in this analyzer before the campaign: fast iff MiB/s >= 550 and write p50 <= 250 us; slow iff MiB/s < 300 or write p50 > 600 us; else mixed; plus the M1 0.92 rule.
- The W24 dataset is 2 GiB per pass on the existing 10 GiB write file (the file is never resized): a disclosed deviation from the frozen 10 GiB dataset (§9.8).
- No cache drop and no power-loss test: durability is the fdatasync/fsync/O_DSYNC guarantee, not something W24 measures (§9.1).

State classes are computed **within each arm** (`fast` iff MiB/s >= 550 and write p50 <= 250 us; `slow` iff MiB/s < 300 or write p50 > 600 us; else `mixed`; the M1 0.92 same-arm rule also marks a rep slow). Arms are ranked only if both sides have >= 2 reps in the same class and neither arm has CV > 10% (methodology D7); the writeback controls bracket the Tier-1 block and must agree within 10%.

