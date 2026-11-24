#!/usr/bin/env bash
# bench/cells.sh -- shell include for the canonical benchmark cell names.
#
# Sourced by the benchmark runners; not meant to be executed directly.
#
# The table lives in exactly one place, bench/analysis/cells.py.  Sourcing this
# file loads it into bash associative arrays (one python3 call, no duplicated
# literal table) and defines:
#
#   cell_slug <slug>      canonical slug (identity; validates a known workload)
#   cell_label <slug>     human label ("4 KiB random write, 64 in flight")
#   cell_for <kind> <workload> <bs> [qd] [batch]
#                         canonical slug from a runner's own parameters
#   cell_for_shards <n>   "shards-<n>" (the multi-shard scaling cells)
#   pass_name <n>         "passN"
#   pass_label <n>        "pass N"
#
# See bench/README.md ("Identifiers").
_CELLS_SH_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
_BENCH_CELLS_PY="$_CELLS_SH_DIR/analysis/cells.py"
if ! _bench_cells_decls=$(python3 "$_BENCH_CELLS_PY" --shell); then
    echo "bench/cells.sh: cannot load the cell map from $_BENCH_CELLS_PY" >&2
    echo "bench/cells.sh: is python3 available?" >&2
    return 1 2>/dev/null || exit 1
fi
eval "$_bench_cells_decls"
unset _bench_cells_decls

cell_slug() {
    local value=${1:-}
    printf '%s' "${CELL_SLUG_BY_ID[$value]:-$value}"
}

cell_label() {
    local value=${1:-}
    printf '%s' "${CELL_LABEL_BY_ID[$value]:-$value}"
}

cell_for() { # cell_for <kind> <workload> <bs> [qd] [batch]
    local key="$1|$2|$3|${4:-}|${5:-}"
    local found=${CELL_BY_WORKLOAD[$key]:-}
    if [[ -z $found ]]; then
        echo "cell_for: no cell for kind=$1 workload=$2 bs=$3 qd=${4:-} batch=${5:-}" >&2
        return 1
    fi
    printf '%s' "$found"
}

cell_for_shards() { # cell_for_shards <n>
    local found=${CELL_BY_SHARDS[$1]:-}
    if [[ -z $found ]]; then
        echo "cell_for_shards: no cell for $1 shard(s)" >&2
        return 1
    fi
    printf '%s' "$found"
}

pass_name() {
    local value=${1:-}
    if [[ $value =~ ^[Pp][Aa][Ss][Ss]([0-9]+)$ ]]; then
        printf 'pass%s' "${BASH_REMATCH[1]}"
    else
        printf '%s' "$value"
    fi
}

pass_label() {
    local name
    name=$(pass_name "${1:-}")
    if [[ $name =~ ^pass([0-9]+)$ ]]; then
        printf 'pass %s' "${BASH_REMATCH[1]}"
    else
        printf '%s' "$name"
    fi
}
