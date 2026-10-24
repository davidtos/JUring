#!/usr/bin/env bash
# Runs the JMH benchmark suite (bench.suite.BenchmarkRunner) with the test classpath.
#
# JMH_PERF is pointed at the genuine perf binary so that `--perfnorm` counts kernel + user time.
# Optional leading `--pin <cpu-list>` pins the entire JVM and its forked JMH children via `taskset -c`.
#
# Usage:
#   ./bench.sh --pin 4 --include 'DirectReadBenchmark.juringDirectRead' --param qd=64 --decision --gc
#   ./bench.sh --pin 0-7 --threads-scaling --include 'MultiThreadScalingBenchmark.juringDirectRead'
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JMH_PERF:-}" && -x /usr/bin/perf ]]; then
    export JMH_PERF=/usr/bin/perf
fi

PIN_CPUS=""
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --pin)
            PIN_CPUS="$2"
            shift 2
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ ! -f target/cp.txt ]]; then
    mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
else
    mvn -q test-compile
fi

CMD=(java --enable-native-access=ALL-UNNAMED -Djuring.countEnters=true
     -cp "target/test-classes:target/classes:$(cat target/cp.txt)"
     bench.suite.BenchmarkRunner "${ARGS[@]}")

if [[ -n "$PIN_CPUS" ]]; then
    echo "Pinning benchmark JVM to CPUs: $PIN_CPUS"
    exec taskset -c "$PIN_CPUS" "${CMD[@]}"
else
    exec "${CMD[@]}"
fi
