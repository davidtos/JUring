# B2 W21/W22 stream matrix -- aggregate

Session: `m2-final`  
Source: `<SESSIONS>/m2-final`  
Generated: <TIMESTAMP>  
Verdict bar (plan M2): `juring MiB/s >= 0.85 * seastar MiB/s`  
Contract: `docs/b2-design.md` §3.3. Raw protocol: `<SESSIONS>/m2-final/manifest.txt`.

Protocol observed in the cell JSONs: warmup 10s + measured 60s per process, R=5 reps per cell (preliminary; the full M2 campaign uses longer cells/rests).

## Verdict

| cell | workload | n (paired) | juring MiB/s | seastar MiB/s | ratio mean | ratio 95% CI | one-sided 95% LB | verdict |
|---|---|---|---|---|---|---|---|---|
| W21-128k | stream_write | 5 | 610.0 | 438.6 | 3.147 | [-1.036, 7.330] | -0.066 | inconclusive |
| W22-128k | stream_read | 5 | 2284.6 | 2327.5 | 0.982 | [0.961, 1.003] | 0.966 | pass |
| W21-1M | stream_write | 5 | 569.5 | 355.7 | 3.056 | [-0.493, 6.606] | 0.330 | inconclusive |
| W22-1M | stream_read | 5 | 4662.8 | 4724.3 | 0.986 | [0.952, 1.021] | 0.960 | pass |

Overall: point-estimate PASS; strict one-sided-95%-lower-bound NOT ESTABLISHED.

Parity note: mean paired ratio over the primary cells ranges 0.982..3.147; the best cell is W21-128k at 3.147. A ratio >= 1.0 means JUring matched or beat seastar fstream on that cell.

## Model-MiB/s detail (mean ± 95% CI, sample sd, CV)

| cell | engine | n | MiB/s mean | sd | 95% CI ± | CV% | errors | direct_io | registered_files |
|---|---|---|---|---|---|---|---|---|---|
| W21-128k | juring | 5 | 610.0 | 324.48 | 402.83 | 53.20 | 0 | True | 1 |
| W21-128k | seastar | 5 | 438.6 | 289.83 | 359.81 | 66.09 | 0 | True | False |
| W21-128k | juring-buffered (exploratory) | 5 | 3678.5 | 307.12 | 381.28 | 8.35 | 0 | False | 1 |
| W22-128k | juring | 5 | 2284.6 | 61.37 | 76.19 | 2.69 | 0 | True | 1 |
| W22-128k | seastar | 5 | 2327.5 | 58.20 | 72.26 | 2.50 | 0 | True | False |
| W22-128k | juring-buffered (exploratory) | 5 | 11989.2 | 65.68 | 81.54 | 0.55 | 0 | False | 1 |
| W21-1M | juring | 5 | 569.5 | 311.50 | 386.71 | 54.70 | 0 | True | 1 |
| W21-1M | seastar | 5 | 355.7 | 241.72 | 300.09 | 67.95 | 0 | True | False |
| W21-1M | juring-buffered (exploratory) | 5 | 4056.6 | 146.66 | 182.07 | 3.62 | 0 | False | 1 |
| W22-1M | juring | 5 | 4662.8 | 231.65 | 287.59 | 4.97 | 0 | True | 1 |
| W22-1M | seastar | 5 | 4724.3 | 105.76 | 131.30 | 2.24 | 0 | True | False |
| W22-1M | juring-buffered (exploratory) | 5 | 13146.8 | 72.64 | 90.18 | 0.55 | 0 | False | 1 |

## Chunk latency (mean of per-run p50/p99, microseconds)

| cell | engine | n | p50 mean | p99 mean |
|---|---|---|---|---|
| W21-128k | juring | 5 | 285.4 | 1613.6 |
| W21-128k | seastar | 5 | 13.0 | 2609.4 |
| W21-128k | juring-buffered | 5 | 10.5 | 158.6 |
| W22-128k | juring | 5 | 56.6 | 119.9 |
| W22-128k | seastar | 5 | 56.5 | 118.3 |
| W22-128k | juring-buffered | 5 | 10.3 | 13.3 |
| W21-1M | juring | 5 | 2279.7 | 13076.2 |
| W21-1M | seastar | 5 | 93.6 | 22352.9 |
| W21-1M | juring-buffered | 5 | 91.3 | 1053.0 |
| W22-1M | juring | 5 | 188.4 | 604.9 |
| W22-1M | seastar | 5 | 185.2 | 593.1 |
| W22-1M | juring-buffered | 5 | 75.3 | 81.1 |

## Data quality (no rep silently dropped)

Every rep file parsed, status ok, zero errors, and every expected rep was paired.

Notes: the exploratory `juring-buffered` row is disclosed only and is never used for the
verdict (seastar fstream is O_DIRECT/DMA here, so the matched-primary rows are O_DIRECT on
both sides). 128K cells are the methodology's W21/W22 wording; 1M cells address the plan's
"seq 1M" wording. The overall verdict requires all primary cells listed above to clear the
bar; the strict reading additionally requires the one-sided 95% lower bound of the paired
ratio to clear the bar. This is a preliminary measurement (durations/rests in manifest.txt).

