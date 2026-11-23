# State-controlled write protocol — gate analysis (frozen)

- valid generations: 2 (w2-g1, w2-g3)
- **verdict: INCONCLUSIVE** — only 2 valid generation(s) (< 4)

| cell | comparator | n | mean J/fio | 95% CI |
|---|---|---|---|---|
| W7 | matched | 2 | 0.9459 | [0.9316, 0.9602] |
| W7 | matchedbatch | 2 | 0.9531 | [0.9369, 0.9693] |
| W8 | matched | 2 | 0.9533 | [0.9244, 0.9821] |
| W8 | matchedbatch | 2 | 0.9835 | [0.6333, 1.3336] |

## Per-generation run classification

| generation | cell | engine | variant | iops | steady | reason | cv |
|---|---|---|---|---|---|---|---|
| w2-g1 | W7 | juring | std | 188,309 | True | ok | 0.081 |
| w2-g1 | W7 | fio-io_uring | matched | 198,841 | True | ok | 0.122 |
| w2-g1 | W7 | fio-io_uring | matchedbatch | 197,842 | True | ok | 0.116 |
| w2-g1 | W8 | juring | std | 188,285 | True | ok | 0.089 |
| w2-g1 | W8 | fio-io_uring | matched | 197,988 | True | ok | 0.123 |
| w2-g1 | W8 | fio-io_uring | matchedbatch | 186,235 | True | ok | 0.251 |
| w2-g2 | W7 | juring | std | 188,137 | True | ok | 0.087 |
| w2-g2 | W7 | fio-io_uring | matched | 84,766 | True | ok | 0.120 |
| w2-g2 | W7 | fio-io_uring | matchedbatch | 197,314 | True | ok | 0.125 |
| w2-g2 | W8 | juring | std | 187,507 | True | ok | 0.100 |
| w2-g2 | W8 | fio-io_uring | matched | 197,660 | True | ok | 0.134 |
| w2-g2 | W8 | fio-io_uring | matchedbatch | 198,194 | True | ok | 0.120 |
| w2-g3 | W7 | juring | std | 188,570 | True | ok | 0.092 |
| w2-g3 | W7 | fio-io_uring | matched | 199,592 | True | ok | 0.123 |
| w2-g3 | W7 | fio-io_uring | matchedbatch | 197,588 | True | ok | 0.116 |
| w2-g3 | W8 | juring | std | 188,592 | True | ok | 0.097 |
| w2-g3 | W8 | fio-io_uring | matched | 197,367 | True | ok | 0.131 |
| w2-g3 | W8 | fio-io_uring | matchedbatch | 197,293 | True | ok | 0.123 |
| w2-g4 | W7 | juring | std | 190,187 | True | ok | 0.091 |
| w2-g4 | W7 | fio-io_uring | matched | 197,990 | True | ok | 0.119 |
| w2-g4 | W7 | fio-io_uring | matchedbatch | 39,734 | True | ok | 0.126 |
| w2-g4 | W8 | juring | std | 190,046 | True | ok | 0.101 |
| w2-g4 | W8 | fio-io_uring | matched | 84,219 | True | ok | 0.407 |
| w2-g4 | W8 | fio-io_uring | matchedbatch | 197,051 | True | ok | 0.122 |
| w2-g5 | W7 | juring | std | 188,900 | True | ok | 0.085 |
| w2-g5 | W7 | fio-io_uring | matched | 199,305 | True | ok | 0.123 |
| w2-g5 | W7 | fio-io_uring | matchedbatch | 52,672 | True | ok | 0.427 |
| w2-g5 | W8 | juring | std | 189,851 | True | ok | 0.087 |
| w2-g5 | W8 | fio-io_uring | matched | 162,381 | True | ok | 0.138 |
| w2-g5 | W8 | fio-io_uring | matchedbatch | 199,002 | True | ok | 0.117 |
